#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
compare_versions.py
===================

Comparación de las versiones del algoritmo de minimización por bisimulación
branching (sustituto de WSOE en la síntesis composicional — ver tesis).

Variantes comparadas
---------------------
  * WSOE  : baseline a reemplazar (algoritmo viejo de "weak semantics on edges").
  * v0    : primera implementación del algoritmo de Groote (2019).
  * v2    : segunda iteración (optimizaciones de particionamiento).
  * v3fix : versión candidata a quedar en la tesis. Es v3c
            (BranchingEquivalenceV3C) con el bug de sobre-fusión corregido:
            v3c re-marcaba inestables solo las BBS que perdían transiciones tras
            peelSlice, dejando sin refinar el remanente de otras source-blocks
            (sub-refinamiento -> over-merge). El fix (LinkedTransitionPartitions
            .isRefinable + re-mark acotado) preserva O(m log n). La campaña
            v3fix es la re-corrida completa con ese build; reemplaza a v3c en
            todos los gráficos y hereda su color.

Las cuatro producen la MISMA minimización salvo WSOE, que puede diferir
(columna EqualsWSOE en el dataset v0). v0/v2/v3fix deberían coincidir en
FinalStates entre sí (y con el fix, v3fix coincide con v2 sin sobre-fusionar).

Fuentes de datos (en esta misma carpeta)
----------------------------------------
  * minimization_results_v0.csv
        -> una fila por (Model, Run) con DOS bloques de resultado:
           columnas *BB   = algoritmo nuevo "v0"
           columnas *WSOE = baseline
  * minimization_results_branching_v2_v3c.csv
        -> filas con AlgoVersion in {v2, v3c}, esquema unificado
           (timings de fase SIN sufijo). SOLO se usa la parte v2; v3c quedó
           obsoleta (buggy) y se descarta.
  * minimization_results_branching_v3fix.csv
        -> filas con AlgoVersion = v3fix, MISMO esquema que el archivo v2_v3c.

Metodología de medición
------------------------
Cada modelo se corre 3 veces: Run 0 con Warmup=true (descartado para tiempos,
es el calentamiento de la JVM/JIT) y Runs 1-2 con Warmup=false (medidos).
Para cada (Model, Version) tomamos la MEDIANA de los runs medidos: la mediana
es robusta a outliers de pausas de GC / scheduling del SO, que en micro-
benchmarks sobre JVM son frecuentes.

Salida
------
  figures/   -> todos los gráficos PNG
  summary_stats.csv -> tabla resumen por versión (tiempos, reducción, etc.)

Uso
---
  ./.venv/bin/python compare_versions.py
"""

import os
import warnings
import numpy as np
import pandas as pd
import matplotlib

matplotlib.use("Agg")  # backend sin display, escribe PNGs directo
import matplotlib.pyplot as plt

warnings.filterwarnings("ignore")

# --------------------------------------------------------------------------- #
# Configuración
# --------------------------------------------------------------------------- #
HERE = os.path.dirname(os.path.abspath(__file__))
F_V0 = os.path.join(HERE, "minimization_results_v0.csv")
F_V23 = os.path.join(HERE, "minimization_results_branching_v2_v3c.csv")
F_V3FIX = os.path.join(HERE, "minimization_results_branching_v3fix.csv")
FIGDIR = os.path.join(HERE, "figures")
os.makedirs(FIGDIR, exist_ok=True)

# Orden y colores consistentes en TODOS los gráficos (clave para que el lector
# de la tesis asocie siempre el mismo color a la misma versión).
# v3fix hereda el rol/color de v3c: es la MISMA versión candidata con el bug de
# sobre-fusión de peelSlice corregido (ver docstring del módulo).
ORDER = ["WSOE", "v0", "v2", "v3fix"]
COLORS = {"WSOE": "#999999", "v0": "#d62728", "v2": "#ff7f0e", "v3fix": "#1f77b4"}

# Versiones del algoritmo nuevo (sin WSOE) — útiles cuando comparamos calidad
# de minimización frente al baseline.
NEW_ORDER = ["v0", "v2", "v3fix"]

# Versiones que SÍ exponen instrumentación interna (timings por fase y
# MainLoopIters). v0 las dejó en -1 (sin instrumentar), así que queda afuera
# de los gráficos de fases/iteraciones.
INSTRUMENTED = ["v2", "v3fix"]

# Para los gráficos de complejidad/calidad nos interesa comparar el baseline
# contra las versiones "candidatas" del algoritmo nuevo (v0 quedó obsoleta:
# no instrumentada y con resultados distintos a v2/v3fix).
CANDIDATES_VS_WSOE = ["WSOE", "v2", "v3fix"]


def binned_median(d, xcol, ycol, log=True, nbins=25):
    """Mediana de ycol por bins de xcol. Devuelve (x, y) o (None, None) si no
    hay datos suficientes para binear (evita 'bins must increase monotonically')."""
    d = d[(d[xcol] > 0) & (d[ycol].notna()) & (d[ycol] > 0)]
    if d[xcol].nunique() < 2:
        return None, None
    lo, hi = d[xcol].min(), d[xcol].max()
    bins = (np.logspace(np.log10(lo), np.log10(hi), nbins) if log
            else np.linspace(lo, hi, nbins))
    bins = np.unique(bins)
    if len(bins) < 2:
        return None, None
    d = d.assign(_bin=pd.cut(d[xcol], bins))
    med = (d.groupby("_bin", observed=True)
           .agg(x=(xcol, "median"), y=(ycol, "median")).dropna())
    return med["x"], med["y"]


def savefig(fig, name):
    path = os.path.join(FIGDIR, name)
    fig.tight_layout()
    fig.savefig(path, dpi=140, bbox_inches="tight")
    plt.close(fig)
    print(f"  -> {os.path.relpath(path, HERE)}")


# --------------------------------------------------------------------------- #
# Carga y normalización a formato "largo" (una fila por Model/Run/Version)
# --------------------------------------------------------------------------- #
# Columnas de entrada comunes a todos los modelos (independientes de la versión):
INPUT_COLS = [
    "InitialStates",
    "InitialTransitions",
    "InitialLocalTransitions",
    "LocalAlphabetSize",
    "LocalControllableSize",
    "TauLabelsSize",
    "AllLocalTransitions",
    "CtrlLocalTransitions",
]

# Esquema canónico de cada fila normalizada.
PHASE_COLS = [
    "SCC_ms",
    "Bvis_ms",
    "InitSplit_ms",
    "Phase1_ms",
    "Phase2_ms",
    "PartTotal_ms",
    "MainLoopIters",
    "BuildStates_ms",
    "BuildTrans_ms",
    "BuildTotal_ms",
]


def load_long():
    dfv0 = pd.read_csv(F_V0)
    # v2 sale del archivo v2_v3c (se descarta v3c, que estaba buggeada);
    # v3fix sale de su propia campaña. Mismo esquema -> se concatenan.
    dfv2 = pd.read_csv(F_V23)
    dfv2 = dfv2[dfv2["AlgoVersion"] == "v2"].copy()
    dffix = pd.read_csv(F_V3FIX)
    dfv23 = pd.concat([dfv2, dffix], ignore_index=True)

    rows = []

    # --- v0 file: split BB (=v0) y WSOE en dos filas lógicas ---------------- #
    base = dfv0[["Model", "Run", "Warmup"] + INPUT_COLS].copy()

    # bloque v0 (BB)
    v0 = base.copy()
    v0["Version"] = "v0"
    v0["FinalStates"] = dfv0["FinalStatesBB"]
    v0["FinalTransitions"] = dfv0["FinalTransitionsBB"]
    v0["FinalLocalTransitions"] = dfv0["FinalLocalTransitionsBB"]
    v0["Time_ms"] = dfv0["TimeBB_ms"]
    v0["GC_ms"] = dfv0["BB_GC_ms"]
    v0["MemPeak_MB"] = dfv0["MemPeakBB_MB"]
    # memoria asignada por el algoritmo (delta) — más confiable que MemPeak,
    # que tiene un bug de muestreo (pico < final en 32% de los casos)
    v0["MemDelta_MB"] = dfv0["MemAfterBB_MB"] - dfv0["MemBeforeBB_MB"]
    for c in PHASE_COLS:
        v0[c] = dfv0["BB_" + c]
    rows.append(v0)

    # bloque WSOE (baseline; no expone fases internas)
    w = base.copy()
    w["Version"] = "WSOE"
    w["FinalStates"] = dfv0["FinalStatesWSOE"]
    w["FinalTransitions"] = dfv0["FinalTransitionsWSOE"]
    w["FinalLocalTransitions"] = dfv0["FinalLocalTransitionsWSOE"]
    w["Time_ms"] = dfv0["TimeWSOE_ms"]
    w["GC_ms"] = dfv0["WSOE_GC_ms"]
    w["MemPeak_MB"] = dfv0["MemPeakWSOE_MB"]
    w["MemDelta_MB"] = dfv0["MemAfterWSOE_MB"] - dfv0["MemBeforeWSOE_MB"]
    for c in PHASE_COLS:
        w[c] = np.nan
    rows.append(w)

    # --- v2 + v3fix (ya concatenados): vienen en formato largo -------------- #
    v23 = dfv23[["Model", "Run", "Warmup", "AlgoVersion"] + INPUT_COLS].copy()
    v23 = v23.rename(columns={"AlgoVersion": "Version"})
    v23["FinalStates"] = dfv23["FinalStates"]
    v23["FinalTransitions"] = dfv23["FinalTransitions"]
    v23["FinalLocalTransitions"] = dfv23["FinalLocalTransitions"]
    v23["Time_ms"] = dfv23["Time_ms"]
    v23["GC_ms"] = dfv23["GC_ms"]
    v23["MemPeak_MB"] = dfv23["MemPeak_MB"]
    v23["MemDelta_MB"] = dfv23["MemAfter_MB"] - dfv23["MemBefore_MB"]
    for c in PHASE_COLS:
        v23[c] = dfv23[c]
    rows.append(v23)

    df = pd.concat(rows, ignore_index=True)
    # Tipado: Warmup viene como string "true"/"false"
    df["Warmup"] = df["Warmup"].astype(str).str.lower().eq("true")
    return df


def aggregate(df):
    """Mediana de los runs MEDIDOS (Warmup=false) por (Model, Version).

    Quedan los inputs (InitialStates, etc.) y las métricas de salida ya
    consolidadas: una fila por modelo y versión.
    """
    measured = df[~df["Warmup"]].copy()
    metric_cols = (
        ["FinalStates", "FinalTransitions", "FinalLocalTransitions",
         "Time_ms", "GC_ms", "MemPeak_MB", "MemDelta_MB"] + PHASE_COLS
    )
    # -1.0 es el sentinel de "no medido" en las columnas de fase de WSOE/v0
    for c in PHASE_COLS:
        measured.loc[measured[c] < 0, c] = np.nan

    agg = (
        measured.groupby(["Model", "Version"], as_index=False)
        .agg({**{c: "median" for c in metric_cols},
              **{c: "first" for c in INPUT_COLS}})
    )
    return agg


# --------------------------------------------------------------------------- #
# GRÁFICOS
# Cada función deja en su docstring POR QUÉ el gráfico es interesante para el
# análisis de la tesis.
# --------------------------------------------------------------------------- #

def plot_time_vs_states(agg):
    """Tiempo de minimización vs tamaño de entrada (escala log-log).

    POR QUÉ INTERESA: es el gráfico central de escalabilidad. La pendiente en
    log-log aproxima el exponente empírico de complejidad. Permite responder
    la pregunta de fondo de la tesis: ¿el algoritmo nuevo (Groote) escala mejor
    que WSOE, y v3fix mejora la pendiente/constante respecto de v0? Si las rectas
    de v0/v2/v3fix quedan por debajo de WSOE y con pendiente menor, hay evidencia
    de que conviene reemplazar WSOE en la síntesis composicional.
    """
    fig, ax = plt.subplots(figsize=(8, 6))
    for v in ORDER:
        d = agg[agg["Version"] == v]
        d = d[(d["InitialStates"] > 0) & (d["Time_ms"] > 0)]
        # binned median para que la tendencia no quede tapada por la nube de puntos
        ax.scatter(d["InitialStates"], d["Time_ms"], s=8, alpha=0.15,
                   color=COLORS[v])
        # mediana por bins logarítmicos de tamaño
        x, y = binned_median(d, "InitialStates", "Time_ms")
        if x is not None:
            ax.plot(x, y, "-o", ms=4, color=COLORS[v], label=v, lw=2)
    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.set_xlabel("Estados iniciales (log)")
    ax.set_ylabel("Tiempo de minimización [ms] (log)")
    ax.set_title("Escalabilidad: tiempo vs tamaño de entrada\n(línea = mediana por bin)")
    ax.legend(title="Versión")
    ax.grid(True, which="both", alpha=0.3)
    savefig(fig, "01_time_vs_states.png")


def plot_speedup_boxplot(agg):
    """Speedup por modelo respecto de WSOE y de v0 (boxplot, escala log).

    POR QUÉ INTERESA: el gráfico anterior muestra tendencias agregadas pero no
    la DISTRIBUCIÓN de la mejora modelo a modelo. Acá calculamos, para cada
    modelo, el cociente Time(baseline)/Time(version). Un boxplot por encima de
    1 indica aceleración consistente. Sirve para reportar en la tesis un número
    duro tipo "v3fix es en mediana N× más rápido que WSOE", y para ver si la
    mejora es robusta (caja angosta) o depende del modelo (caja ancha / colas).
    """
    piv = agg.pivot_table(index="Model", columns="Version", values="Time_ms")
    piv = piv[(piv > 0).all(axis=1)]

    ratios = {
        "WSOE / v0": piv["WSOE"] / piv["v0"],
        "WSOE / v2": piv["WSOE"] / piv["v2"],
        "WSOE / v3fix": piv["WSOE"] / piv["v3fix"],
        "v0 / v3fix": piv["v0"] / piv["v3fix"],
        "v2 / v3fix": piv["v2"] / piv["v3fix"],
    }
    fig, ax = plt.subplots(figsize=(9, 6))
    labels = list(ratios.keys())
    data = [ratios[k].replace([np.inf, -np.inf], np.nan).dropna() for k in labels]
    bp = ax.boxplot(data, labels=labels, showfliers=False, patch_artist=True)
    for patch in bp["boxes"]:
        patch.set_facecolor("#cfe8ff")
    ax.axhline(1.0, color="k", ls="--", lw=1, label="sin mejora (1×)")
    ax.set_yscale("log")
    ax.set_ylabel("Speedup = Tiempo(A) / Tiempo(B)  [log]")
    ax.set_title("Distribución del speedup por modelo")
    # anotar medianas
    for i, d in enumerate(data, 1):
        ax.text(i, d.median(), f"{d.median():.2f}×", ha="center",
                va="bottom", fontsize=9, fontweight="bold")
    ax.legend()
    ax.grid(True, axis="y", which="both", alpha=0.3)
    savefig(fig, "02_speedup_boxplot.png")


def plot_phase_breakdown(agg):
    """Descomposición del tiempo por fase del algoritmo (barras apiladas).

    POR QUÉ INTERESA: explica el PORQUÉ de las diferencias de tiempo entre
    versiones. WSOE no expone fases y v0 quedó SIN instrumentar (sus columnas
    de fase valen -1), así que solo se comparan v2 y v3fix. Si v3fix reduce el
    tiempo total, este gráfico muestra qué fase concreta se optimizó (p. ej.
    InitSplit o Phase2). Es el argumento técnico de la tesis: no solo "v3fix es
    más rápido" sino "v3fix es más rápido PORQUE atacó la fase X".
    Se agregan las fases sobre todo el corpus (suma de medianas).
    """
    phases = ["SCC_ms", "Bvis_ms", "InitSplit_ms", "Phase1_ms", "Phase2_ms",
              "BuildTotal_ms"]
    fig, ax = plt.subplots(figsize=(7, 6))
    bottoms = np.zeros(len(INSTRUMENTED))
    cmap = plt.get_cmap("tab20")
    for j, ph in enumerate(phases):
        vals = []
        for v in INSTRUMENTED:
            d = agg[agg["Version"] == v]
            vals.append(d[ph].sum())  # suma de medianas sobre el corpus
        ax.bar(INSTRUMENTED, vals, bottom=bottoms, label=ph.replace("_ms", ""),
               color=cmap(j * 2))
        bottoms += np.array(vals)
    ax.set_ylabel("Tiempo agregado sobre el corpus [ms]")
    ax.set_title("Descomposición del tiempo por fase\n(suma sobre todos los modelos)")
    ax.legend(title="Fase", bbox_to_anchor=(1.02, 1), loc="upper left")
    ax.grid(True, axis="y", alpha=0.3)
    savefig(fig, "03_phase_breakdown.png")


def plot_reduction_quality(agg):
    """Calidad de la minimización: estados finales vs iniciales.

    POR QUÉ INTERESA: la velocidad no sirve si la minimización es distinta.
    v2/v3fix implementan branching bisimulation; WSOE usa otra equivalencia. NO
    coinciden exactamente: validado contra la columna EqualsWSOE (ver gráfico
    05). Con el bug de sobre-fusión corregido, v3fix ya no minimiza de más y
    coincide con v2. Este gráfico justifica el reemplazo por CALIDAD: si los puntos quedan
    por debajo de los de WSOE, el algoritmo nuevo produce modelos más chicos,
    lo que beneficia a la síntesis composicional (menos estados que arrastrar
    en la composición). Se excluye v0 (versión obsoleta, no candidata).
    """
    versions = CANDIDATES_VS_WSOE  # WSOE, v2, v3fix
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(13, 5.5))

    # (a) FinalStates vs InitialStates
    for v in versions:
        d = agg[agg["Version"] == v]
        ax1.scatter(d["InitialStates"], d["FinalStates"], s=8, alpha=0.25,
                    color=COLORS[v], label=v)
    lim = [1, agg["InitialStates"].max()]
    ax1.plot(lim, lim, "k--", lw=1, label="sin reducción")
    ax1.set_xscale("log"); ax1.set_yscale("log")
    ax1.set_xlabel("Estados iniciales (log)")
    ax1.set_ylabel("Estados finales (log)")
    ax1.set_title("Tamaño del resultado")
    ax1.legend(); ax1.grid(True, which="both", alpha=0.3)

    # (b) ratio de reducción FinalStates/InitialStates (boxplot por versión)
    data = []
    for v in versions:
        d = agg[agg["Version"] == v]
        r = (d["FinalStates"] / d["InitialStates"]).replace(
            [np.inf, -np.inf], np.nan).dropna()
        data.append(r)
    bp = ax2.boxplot(data, labels=versions, showfliers=False, patch_artist=True)
    for patch, v in zip(bp["boxes"], versions):
        patch.set_facecolor(COLORS[v])
        patch.set_alpha(0.6)
    ax2.set_ylabel("Estados finales / iniciales")
    ax2.set_title("Factor de reducción por versión\n(más bajo = minimiza más)")
    ax2.grid(True, axis="y", alpha=0.3)
    savefig(fig, "04_reduction_quality.png")


def plot_equals_wsoe(agg):
    """¿En qué fracción del corpus cada versión da la MISMA minimización que WSOE?

    POR QUÉ INTERESA: complementa el gráfico de calidad. Muestra qué fracción de
    los modelos quedan idénticos a WSOE y en cuáles la equivalencia branching da
    un resultado distinto. Si la diferencia es sistemática (no esporádica),
    confirma que branching y WSOE son equivalencias semánticamente distintas y
    que el reemplazo cambia el modelo minimizado — algo que la tesis debe
    documentar explícitamente.

    El dataset trae la columna booleana EqualsWSOE SOLO para v0. Para v2/v3fix se
    usa como proxy la igualdad de la tupla (FinalStates, FinalTransitions,
    FinalLocalTransitions): se verificó que ese proxy coincide al 100% con la
    columna EqualsWSOE real en v0, así que es confiable. Se incluye v0 como
    control (la barra del proxy debe coincidir con el booleano oficial).
    """
    wide = agg.pivot_table(
        index="Model", columns="Version",
        values=["FinalStates", "FinalTransitions", "FinalLocalTransitions"])

    def frac_equal(v):
        cols = ["FinalStates", "FinalTransitions", "FinalLocalTransitions"]
        ok = np.ones(len(wide), dtype=bool)
        valid = np.ones(len(wide), dtype=bool)
        for c in cols:
            a, b = wide[(c, v)], wide[(c, "WSOE")]
            valid &= a.notna().values & b.notna().values
            ok &= (a.values == b.values)
        ok = ok[valid]
        return ok.mean(), valid.sum()

    versions = ["v0", "v2", "v3fix"]
    fig, ax = plt.subplots(figsize=(7, 5.5))
    fr = [frac_equal(v) for v in versions]
    equal = [f[0] * 100 for f in fr]
    distinct = [100 - e for e in equal]
    x = np.arange(len(versions))
    ax.bar(x, equal, color="#2ca02c", label="= WSOE")
    ax.bar(x, distinct, bottom=equal, color="#d62728", label="≠ WSOE")
    for i, (e, f) in enumerate(zip(equal, fr)):
        ax.text(i, e / 2, f"{e:.0f}%", ha="center", va="center",
                color="white", fontweight="bold")
        ax.text(i, e + (100 - e) / 2, f"{100-e:.0f}%", ha="center",
                va="center", color="white", fontweight="bold")
    ax.set_xticks(x); ax.set_xticklabels(versions)
    ax.set_ylabel("% de modelos del corpus")
    ax.set_ylim(0, 100)
    ax.set_title("¿La minimización branching coincide con WSOE?\n"
                 "(igualdad de estados+transiciones; v0 = control del proxy)")
    ax.legend(loc="lower right")
    ax.grid(True, axis="y", alpha=0.3)
    savefig(fig, "05_equals_wsoe.png")


def plot_time_vs_controllables(agg):
    """Tiempo vs tamaño controlable (acciones y transiciones controlables).

    POR QUÉ INTERESA: en síntesis de controladores lo que duele es la parte
    CONTROLABLE del modelo. Este gráfico mide la sensibilidad del tiempo a
    LocalControllableSize y a CtrlLocalTransitions, en vez de al tamaño total.
    Si el algoritmo nuevo es menos sensible a la fracción controlable que WSOE,
    es un argumento directo a favor del reemplazo en el contexto de la tesis
    (composición de plantas/requerimientos con muchas acciones controlables).
    """
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(13, 5.5))
    for ax, xcol, xlabel in [
        (ax1, "LocalControllableSize", "Acciones controlables (alfabeto)"),
        (ax2, "CtrlLocalTransitions", "Transiciones controlables"),
    ]:
        for v in ORDER:
            d = agg[agg["Version"] == v]
            x, y = binned_median(d, xcol, "Time_ms", log=False, nbins=20)
            if x is not None:
                ax.plot(x, y, "-o", ms=4, color=COLORS[v], label=v)
        ax.set_yscale("log")
        ax.set_xlabel(xlabel)
        ax.set_ylabel("Tiempo [ms] (log)")
        ax.grid(True, which="both", alpha=0.3)
        ax.legend(title="Versión")
    fig.suptitle("Sensibilidad del tiempo a la estructura controlable "
                 "(mediana por bin)")
    savefig(fig, "06_time_vs_controllables.png")


def plot_iters_vs_states(agg):
    """Iteraciones del loop principal vs tamaño (solo algoritmo nuevo).

    POR QUÉ INTERESA: MainLoopIters es una métrica independiente del hardware
    (no es tiempo de pared). Si v3fix hace MENOS iteraciones que v2 para el mismo
    tamaño, demuestra que la mejora es algorítmica y no solo de constante/JIT.
    Es la evidencia "limpia" de que la optimización es real y reproducible.
    v0 no instrumentó esta métrica (queda afuera).
    """
    fig, ax = plt.subplots(figsize=(8, 6))
    for v in INSTRUMENTED:
        d = agg[agg["Version"] == v]
        x, y = binned_median(d, "InitialStates", "MainLoopIters")
        if x is not None:
            ax.plot(x, y, "-o", ms=4, color=COLORS[v], label=v, lw=2)
    ax.set_xscale("log"); ax.set_yscale("log")
    ax.set_xlabel("Estados iniciales (log)")
    ax.set_ylabel("Iteraciones del loop principal (log)")
    ax.set_title("Trabajo algorítmico vs tamaño\n(métrica independiente del hardware)")
    ax.legend(title="Versión"); ax.grid(True, which="both", alpha=0.3)
    savefig(fig, "07_iters_vs_states.png")


def plot_speedup_vs_states(agg):
    """Speedup respecto de WSOE EN FUNCIÓN del tamaño del modelo.

    POR QUÉ INTERESA: es el gráfico que cierra el argumento de la tesis. El
    boxplot de speedup da una mediana ~1× porque el corpus está dominado por
    modelos chicos donde manda el overhead fijo (y WSOE es competitivo). Pero
    la pregunta real es: ¿a partir de qué tamaño conviene el algoritmo nuevo?
    Acá graficamos speedup = Tiempo(WSOE)/Tiempo(version) vs estados iniciales.
    El punto donde la curva cruza la línea de 1× es el "break-even": por encima
    de ese tamaño el reemplazo de WSOE es netamente beneficioso. Como la síntesis
    composicional genera justamente los modelos grandes, este gráfico es el que
    justifica la decisión de diseño central de la tesis.
    """
    fig, ax = plt.subplots(figsize=(8, 6))
    wide = agg.pivot_table(index="Model", columns="Version",
                           values="Time_ms")
    sizes = agg.groupby("Model")["InitialStates"].first()
    for v in NEW_ORDER:
        sub = wide[["WSOE", v]].dropna()
        sub = sub[(sub > 0).all(axis=1)]
        d = pd.DataFrame({
            "InitialStates": sizes.reindex(sub.index),
            "speedup": sub["WSOE"] / sub[v],
        }).dropna()
        x, y = binned_median(d, "InitialStates", "speedup")
        if x is not None:
            ax.plot(x, y, "-o", ms=4, color=COLORS[v], label=v, lw=2)
    ax.axhline(1.0, color="k", ls="--", lw=1, label="break-even (1×)")
    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.set_xlabel("Estados iniciales (log)")
    ax.set_ylabel("Speedup vs WSOE = T(WSOE)/T(version)  [log]")
    ax.set_title("¿A partir de qué tamaño conviene reemplazar WSOE?\n"
                 "(mediana por bin; por encima de 1× gana el algoritmo nuevo)")
    ax.legend(title="Versión")
    ax.grid(True, which="both", alpha=0.3)
    savefig(fig, "09_speedup_vs_states.png")


def _fit_loglog(x, y):
    """Ajusta y ≈ C·x^a en log-log; devuelve (a, C). a es el exponente empírico."""
    lx, ly = np.log10(x), np.log10(y)
    a, b = np.polyfit(lx, ly, 1)
    return a, 10 ** b


def plot_complexity_slope(agg):
    """Tiempo vs n en log-log con el EXPONENTE empírico ajustado por versión.

    POR QUÉ INTERESA: es la demostración de la diferencia de complejidad que
    motiva la tesis. WSOE es O(m·n) y branching (Groote) es O(m·log n).

    OJO con leer la complejidad como "pendiente vs n": en este corpus las
    transiciones crecen MÁS rápido que los estados (m ~ n^1.3), así que graficar
    el tiempo contra n INFLA el exponente aparente — branching sale con pendiente
    ~1.5 ("casi cuadrático") aunque NO sea cuadrático. Esa pendiente es un
    artefacto de la relación m–n del corpus, no una propiedad del algoritmo.

    La prueba limpia es separar los dos factores con una regresión de dos
    variables  log T = a·log m + b·log n  (panel derecho). Lo que distingue las
    clases es el EXPONENTE DE n:
        WSOE      : b ≈ 1   -> el tiempo SÍ crece con n  => factor ×n => O(m·n)
        branching : b ≈ 0   -> el tiempo NO depende de n => O(m·log n)
    Que el exponente de n de branching sea ~0 (y el de WSOE ~1) es la evidencia
    de que el algoritmo nuevo NO es cuadrático: su costo está gobernado por las
    transiciones, no por los estados. (Por la fuerte colinealidad m–n ≈ 0.94 el
    exponente exacto de m no es fiable; el de n, que es el discriminador, sí es
    robusto y grande.)
    """
    NMIN = 300       # régimen asintótico para la pendiente vs n
    NMIN_REG = 100   # umbral para la regresión 2-variables (más datos)
    SCATTER_MIN = 20  # los modelos chicos solo ensucian: no se grafican
    fig, (axA, axB) = plt.subplots(1, 2, figsize=(14, 6))

    # ----- Panel A: Tiempo vs n (pendiente aparente, inflada por m~n^1.3) -----
    for v in CANDIDATES_VS_WSOE:
        d = agg[agg["Version"] == v]
        d = d[(d["InitialStates"] >= SCATTER_MIN) & (d["Time_ms"] > 0)]
        axA.scatter(d["InitialStates"], d["Time_ms"], s=6, alpha=0.10,
                    color=COLORS[v])
        fitd = d[d["InitialStates"] >= NMIN]
        if len(fitd) > 5:
            a, C = _fit_loglog(fitd["InitialStates"].values, fitd["Time_ms"].values)
            xx = np.array([NMIN, d["InitialStates"].max()], dtype=float)
            axA.plot(xx, C * xx ** a, "-", color=COLORS[v], lw=2.5,
                     label=f"{v}: pend. aparente ≈ {a:.2f}")
    yref = agg[(agg["Version"] == "v3fix") & (agg["InitialStates"] >= NMIN)]["Time_ms"].median()
    xr = np.array([NMIN, agg["InitialStates"].max()], dtype=float)
    axA.plot(xr, yref * (xr / NMIN) ** 1, "k:", lw=1, alpha=0.7, label="pendiente 1")
    axA.plot(xr, yref * (xr / NMIN) ** 2, "k--", lw=1, alpha=0.7, label="pendiente 2")
    axA.set_xscale("log"); axA.set_yscale("log")
    axA.set_xlabel("Estados iniciales n (log)")
    axA.set_ylabel("Tiempo [ms] (log)")
    axA.set_title("Tiempo vs n (pendiente APARENTE)\ninflada porque m ~ n^1.3 — NO leer como complejidad")
    axA.legend(fontsize=8.5); axA.grid(True, which="both", alpha=0.3)

    # ----- Panel B: regresión 2-variables -> exponente de n es el discriminador -
    exps = {}
    for v in CANDIDATES_VS_WSOE:
        d = agg[(agg["Version"] == v) & (agg["InitialStates"] >= NMIN_REG)]
        d = d[(d["InitialTransitions"] > 0) & (d["Time_ms"] > 0)]
        X = np.column_stack([np.log10(d["InitialTransitions"]),
                             np.log10(d["InitialStates"]),
                             np.ones(len(d))])
        y = np.log10(d["Time_ms"].values)
        coef, *_ = np.linalg.lstsq(X, y, rcond=None)
        exps[v] = (coef[0], coef[1])  # (exp_m, exp_n)

    x = np.arange(len(CANDIDATES_VS_WSOE))
    w = 0.36
    axB.bar(x - w/2, [exps[v][0] for v in CANDIDATES_VS_WSOE], w,
            label="exponente de m", color="#8c8c8c")
    axB.bar(x + w/2, [exps[v][1] for v in CANDIDATES_VS_WSOE], w,
            label="exponente de n", color=[COLORS[v] for v in CANDIDATES_VS_WSOE])
    axB.axhline(1, color="r", ls="--", lw=1, alpha=0.6, label="exp.n=1  → O(m·n)")
    axB.axhline(0, color="g", ls="--", lw=1, alpha=0.6, label="exp.n=0  → O(m·log n)")
    for i, v in enumerate(CANDIDATES_VS_WSOE):
        axB.text(i + w/2, exps[v][1], f"{exps[v][1]:+.2f}", ha="center",
                 va="bottom" if exps[v][1] >= 0 else "top", fontsize=9, fontweight="bold")
    axB.set_xticks(x); axB.set_xticklabels(CANDIDATES_VS_WSOE)
    axB.set_ylabel("Exponente en  T ~ m^a · n^b")
    axB.set_title("Regresión 2-variables (n ≥ %d)\nWSOE depende de n (b≈1); branching NO (b≈0)" % NMIN_REG)
    axB.legend(fontsize=8.5, loc="upper right"); axB.grid(True, axis="y", alpha=0.3)
    savefig(fig, "08_complexity_slope.png")


def plot_complexity_normalized(agg):
    """Tiempo NORMALIZADO por la cota teórica, vs n: ¿la cota es ajustada?

    POR QUÉ INTERESA: el gráfico de pendiente muestra el exponente; este muestra
    que la cota teórica de cada algoritmo es la CORRECTA. Dividimos el tiempo por
    el trabajo predicho:
        WSOE      : T / (m·n)
        branching : T / (m·log2 n)
    Si la cota es ajustada, la curva normalizada se APLANA (tiende a una
    constante = el costo por operación). Si en cambio sigue creciendo, la cota
    subestima el costo real; si decrece, lo sobreestima. Ver la curva de v3fix
    aplanarse al dividir por m·log n —y NO al dividir por algo menor— es la
    confirmación de que el algoritmo se comporta como O(m·log n) en la práctica,
    cerrando el argumento teórico de la tesis con evidencia empírica.
    Cada curva se normaliza a su propia mediana para poder compararlas en un
    mismo eje (lo que importa es la FORMA: plana = cota ajustada).
    """
    n = agg.groupby("Model")["InitialStates"].first().astype(float)
    m = agg.groupby("Model")["InitialTransitions"].first().astype(float)
    wide = agg.pivot_table(index="Model", columns="Version", values="Time_ms")

    work = {
        "WSOE": m * n,                        # O(m·n)
        "v2": m * np.log2(n.clip(lower=2)),   # O(m·log n)
        "v3fix": m * np.log2(n.clip(lower=2)),
    }
    titles = {"WSOE": "T / (m·n)", "v2": "T / (m·log₂n)", "v3fix": "T / (m·log₂n)"}

    fig, ax = plt.subplots(figsize=(8.5, 6.5))
    for v in CANDIDATES_VS_WSOE:
        t = wide[v]
        norm = (t / work[v]).replace([np.inf, -np.inf], np.nan)
        d = pd.DataFrame({"InitialStates": n, "y": norm}).dropna()
        d = d[(d["InitialStates"] >= 100) & (d["y"] > 0)]
        # normalizar a la mediana propia -> comparar formas, no magnitudes
        d["y"] = d["y"] / d["y"].median()
        x, y = binned_median(d, "InitialStates", "y")
        if x is not None:
            ax.plot(x, y, "-o", ms=4, color=COLORS[v], lw=2,
                    label=f"{v}:  {titles[v]}")
    ax.axhline(1.0, color="k", ls="--", lw=1, alpha=0.6,
               label="constante (cota ajustada)")
    ax.set_xscale("log"); ax.set_yscale("log")
    ax.set_xlabel("Estados iniciales n (log)")
    ax.set_ylabel("Tiempo / trabajo teórico  (normalizado a su mediana)")
    ax.set_title("¿Es ajustada la cota de complejidad?\n"
                 "(curva plana ⇒ T crece como el trabajo teórico predicho)")
    ax.legend(fontsize=9)
    ax.grid(True, which="both", alpha=0.3)
    savefig(fig, "10_complexity_normalized.png")


def plot_memory_delta(agg):
    """Memoria ASIGNADA por el algoritmo (MemAfter - MemBefore) vs tamaño.

    POR QUÉ INTERESA: muestra el trade-off tiempo↔memoria del reemplazo. El
    algoritmo de Groote (branching) mantiene estructuras extra —partición en
    bloques, listas de splitters, contadores— que WSOE no necesita, así que se
    espera que asigne MÁS memoria. Confirmarlo (y acotar cuánto) es necesario
    para defender el reemplazo: ganamos tiempo, pero hay que verificar que el
    costo en memoria no sea prohibitivo en los modelos grandes que produce la
    síntesis composicional.

    POR QUÉ delta y NO MemPeak: el dataset trae MemPeak, pero es un muestreo
    poco confiable —en el 32% de las filas MemPeak < MemAfter, lo cual es
    imposible si el pico estuviera bien medido: el sampler se pierde el pico
    real—. La memoria asignada delta = MemAfter - MemBefore es, en cambio,
    determinística entre runs del mismo modelo (CV ≈ 0%), más estable incluso
    que el tiempo. Caveats del registro (a corregir si se quiere medir el pico
    absoluto): no hay GC forzado antes de MemBefore, por lo que el baseline
    arrastra residuos del modelo anterior (varía 25-1267 MB); por eso miramos
    el DELTA y no los valores absolutos, y restringimos a n>=100 donde la señal
    supera al ruido del baseline. v0 se omite (versión obsoleta).
    """
    NMIN = 100
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(13, 5.5))

    # (a) delta de memoria vs n (mediana por bin), WSOE vs v2 vs v3fix
    for v in CANDIDATES_VS_WSOE:
        d = agg[agg["Version"] == v]
        d = d[(d["InitialStates"] >= NMIN) & (d["MemDelta_MB"] > 0)]
        x, y = binned_median(d, "InitialStates", "MemDelta_MB")
        if x is not None:
            ax1.plot(x, y, "-o", ms=4, color=COLORS[v], label=v, lw=2)
    ax1.set_xscale("log"); ax1.set_yscale("log")
    ax1.set_xlabel("Estados iniciales n (log)")
    ax1.set_ylabel("Memoria asignada Δ = After − Before [MB] (log)")
    ax1.set_title(f"Memoria asignada vs tamaño (n ≥ {NMIN}, mediana por bin)")
    ax1.legend(title="Versión"); ax1.grid(True, which="both", alpha=0.3)

    # (b) overhead de memoria de branching RESPECTO de WSOE, por modelo.
    #     Cociente Δ(version)/Δ(WSOE): >1 => branching asigna más que WSOE.
    wide = agg.pivot_table(index="Model", columns="Version", values="MemDelta_MB")
    sizes = agg.groupby("Model")["InitialStates"].first()
    data, labels = [], []
    for v in ["v2", "v3fix"]:
        sub = wide[["WSOE", v]].copy()
        sub = sub[(sizes.reindex(sub.index) >= NMIN)]
        sub = sub[(sub["WSOE"] > 0) & (sub[v] > 0)]
        ratio = (sub[v] / sub["WSOE"]).replace([np.inf, -np.inf], np.nan).dropna()
        data.append(ratio); labels.append(f"{v} / WSOE")
    bp = ax2.boxplot(data, labels=labels, showfliers=False, patch_artist=True)
    for patch, v in zip(bp["boxes"], ["v2", "v3fix"]):
        patch.set_facecolor(COLORS[v]); patch.set_alpha(0.6)
    ax2.axhline(1.0, color="k", ls="--", lw=1, label="igual que WSOE")
    for i, dvals in enumerate(data, 1):
        ax2.text(i, dvals.median(), f"{dvals.median():.2f}×", ha="center",
                 va="bottom", fontsize=9, fontweight="bold")
    ax2.set_ylabel("Memoria asignada vs WSOE  (Δ_version / Δ_WSOE)")
    ax2.set_title("Overhead de memoria de branching (n ≥ 100)\n(>1 = asigna más que WSOE)")
    ax2.legend(); ax2.grid(True, axis="y", alpha=0.3)
    savefig(fig, "11_memory_delta.png")


def plot_v3c_vs_wsoe_size(agg):
    """v3fix vs WSOE: cuando difieren, ¿v3fix minimiza más o menos? (solo v3fix).

    POR QUÉ INTERESA: la tesis demuestra (Cap. 4) que WSOE ≡ branching
    bisimilarity, por lo que los cocientes deberían ser isomorfos —MISMO número
    de estados siempre—. Este gráfico contrasta esa predicción con los datos:
    clasifica cada modelo del corpus en idéntico a WSOE / v3fix con menos estados
    / mismo nº de estados pero distintas transiciones / v3fix con más estados.

    Es la figura donde se ve el efecto del FIX: v3c (buggy) minimizaba de más
    (bucket "menos estados") en cientos de modelos por el sub-refinamiento de
    peelSlice; v3fix corrige eso, por lo que ese bucket debe colapsar a ~0 y
    crecer "idéntico a WSOE". Los casos discrepantes que sobrevivan son la
    diferencia REAL branching↔WSOE (conteo de τ-transiciones, aristas paralelas,
    manejo de divergencia, etc.), no el bug. Es una verificación de CORRECTITUD,
    no de performance: el dato que un jurado va a mirar antes de aceptar el
    reemplazo de WSOE.
    """
    cols = ["FinalStates", "FinalTransitions", "FinalLocalTransitions"]
    wide = agg.pivot_table(index="Model", columns="Version", values=cols)
    s_v, s_w = wide[("FinalStates", "v3fix")], wide[("FinalStates", "WSOE")]
    t_v, t_w = wide[("FinalTransitions", "v3fix")], wide[("FinalTransitions", "WSOE")]
    l_v, l_w = wide[("FinalLocalTransitions", "v3fix")], wide[("FinalLocalTransitions", "WSOE")]
    ok = s_v.notna() & s_w.notna() & t_v.notna() & t_w.notna() & l_v.notna() & l_w.notna()
    s_v, s_w, t_v, t_w, l_v, l_w = (x[ok] for x in (s_v, s_w, t_v, t_w, l_v, l_w))
    n = ok.sum()

    identico = (s_v == s_w) & (t_v == t_w) & (l_v == l_w)
    difiere = ~identico
    v3_menos = difiere & (s_v < s_w)
    v3_mas = difiere & (s_v > s_w)
    igual_st = difiere & (s_v == s_w)  # mismos estados, distintas trans/locales

    cats = [
        ("idéntico a WSOE", identico.sum(), "#2ca02c"),
        ("v3fix menos estados\n(minimiza más)", v3_menos.sum(), "#1f77b4"),
        ("= estados,\n≠ transiciones", igual_st.sum(), "#9467bd"),
        ("v3fix más estados\n(minimiza menos)", v3_mas.sum(), "#d62728"),
    ]

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(13.5, 5.6))

    # (a) breakdown del corpus completo
    labels = [c[0] for c in cats]
    vals = [100 * c[1] / n for c in cats]
    colors = [c[2] for c in cats]
    bars = ax1.bar(range(len(cats)), vals, color=colors)
    for i, (b, c) in enumerate(zip(bars, cats)):
        ax1.text(i, b.get_height(), f"{vals[i]:.1f}%\n({c[1]})", ha="center",
                 va="bottom", fontsize=9)
    ax1.set_xticks(range(len(cats)))
    ax1.set_xticklabels(labels, fontsize=8.5)
    ax1.set_ylabel("% de modelos del corpus")
    ax1.set_ylim(0, max(vals) * 1.18)
    ax1.set_title(f"v3fix vs WSOE en todo el corpus (n={n})")
    ax1.grid(True, axis="y", alpha=0.3)

    # (b) entre los que difieren: estados finales v3fix vs WSOE (log-log)
    ndif = int(difiere.sum())
    df_d = pd.DataFrame({"wsoe": s_w[difiere], "v3fix": s_v[difiere]})
    ax2.set_xscale("log"); ax2.set_yscale("log")
    ax2.set_xlabel("Estados finales WSOE (log)")
    ax2.set_ylabel("Estados finales v3fix (log)")
    ax2.set_title("Solo casos discrepantes: tamaño v3fix vs WSOE\n"
                  "(arriba de la línea = v3fix más grande = minimiza menos)")
    if ndif > 0:
        ax2.scatter(df_d["wsoe"], df_d["v3fix"], s=12, alpha=0.35, color="#444444")
        lim = [max(1, df_d.min().min()), df_d.max().max()]
        ax2.plot(lim, lim, "k--", lw=1.2, label="igual nº de estados")
        media = (s_v[difiere] - s_w[difiere]).mean()
        ax2.text(0.05, 0.95,
                 f"de {ndif} discrepantes:\n"
                 f"  v3fix menos: {v3_menos.sum()} ({100*v3_menos.sum()/ndif:.0f}%)\n"
                 f"  v3fix más:  {v3_mas.sum()} ({100*v3_mas.sum()/ndif:.0f}%)\n"
                 f"  Δestados medio: {media:+.1f}",
                 transform=ax2.transAxes, va="top", fontsize=9,
                 bbox=dict(boxstyle="round", fc="white", alpha=0.8))
        ax2.legend(loc="lower right")
    else:
        ax2.text(0.5, 0.5, "sin casos discrepantes\n(v3fix ≡ WSOE en todo el corpus)",
                 transform=ax2.transAxes, ha="center", va="center", fontsize=11,
                 bbox=dict(boxstyle="round", fc="#eafbea", alpha=0.9))
    ax2.grid(True, which="both", alpha=0.3)
    savefig(fig, "12_v3c_vs_wsoe_size.png")


def _loglog_fit_r2(x, y):
    """Ajuste log-log y≈C·x^a; devuelve (a, R2)."""
    lx, ly = np.log10(x), np.log10(y)
    a, b = np.polyfit(lx, ly, 1)
    r2 = np.corrcoef(lx, ly)[0, 1] ** 2
    return a, r2


def plot_time_vs_transitions(agg):
    """Tiempo vs transiciones m, y ¿queda el tiempo EXPLICADO solo por m?

    POR QUÉ INTERESA: las cotas son O(m·n) (WSOE) y O(m·log n) (branching); m es
    el factor común. Probar a graficar el tiempo contra m responde una pregunta
    de complejidad distinta a la del exponente vs n: ¿cuánto del costo está
    determinado por la cantidad de transiciones? La respuesta separa las clases:
    el tiempo de branching está MUY bien explicado solo por m (R²≈0.95, porque
    el factor log n casi no varía), mientras que el de WSOE NO (R²≈0.66): le
    falta el factor ×n. Que el ajuste de una sola variable funcione para
    branching y falle para WSOE es, en sí mismo, evidencia del salto de
    complejidad —vista desde las transiciones, como la que buscábamos—.

    OJO (por qué la PENDIENTE vs m no sirve como en vs n): m y n están muy
    correlacionados en el corpus (corr log ≈ 0.94), así que la pendiente vs m
    queda distorsionada y hasta ordena al revés. El discriminador limpio acá es
    el R² (calidad de ajuste), no la pendiente; el exponente de complejidad se
    lee en el gráfico Tiempo vs n (08).
    """
    NMIN = 100
    fig, ax = plt.subplots(figsize=(8.5, 6.5))
    for v in CANDIDATES_VS_WSOE:
        d = agg[agg["Version"] == v]
        d = d[(d["InitialTransitions"] > 0) & (d["Time_ms"] > 0)]
        ax.scatter(d["InitialTransitions"], d["Time_ms"], s=6, alpha=0.12,
                   color=COLORS[v])
        x, y = binned_median(d, "InitialTransitions", "Time_ms")
        fitd = d[d["InitialStates"] >= NMIN]
        a, r2 = _loglog_fit_r2(fitd["InitialTransitions"].values,
                               fitd["Time_ms"].values)
        if x is not None:
            ax.plot(x, y, "-o", ms=4, color=COLORS[v], lw=2,
                    label=f"{v}:  R²={r2:.2f}  (pend.={a:.2f})")
    ax.set_xscale("log"); ax.set_yscale("log")
    ax.set_xlabel("Transiciones iniciales m (log)")
    ax.set_ylabel("Tiempo [ms] (log)")
    ax.set_title("Tiempo vs transiciones: ¿está el costo explicado solo por m?\n"
                 f"(R² del ajuste log-log para n ≥ {NMIN}; "
                 "branching sí, WSOE no → le falta el ×n)")
    ax.legend(title="Versión", fontsize=9)
    ax.grid(True, which="both", alpha=0.3)
    savefig(fig, "13_time_vs_transitions.png")


def plot_transitions_reduction(agg):
    """Reducción de TRANSICIONES: final vs inicial, y factor por versión.

    POR QUÉ INTERESA: es la contraparte en transiciones del gráfico de reducción
    de estados (04). Importa especialmente acá porque la discrepancia entre
    branching y WSOE se concentra en las transiciones (recordar: ~13% del corpus
    tiene IGUAL número de estados pero distinto número de transiciones). Ver el
    factor de reducción de transiciones lado a lado muestra si v3fix colapsa las
    transiciones tanto, más, o menos que WSOE —lo que afecta directamente el
    tamaño del modelo que se arrastra en la composición—.
    """
    versions = CANDIDATES_VS_WSOE
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(13, 5.5))

    # (a) FinalTransitions vs InitialTransitions
    for v in versions:
        d = agg[agg["Version"] == v]
        d = d[(d["InitialTransitions"] > 0) & (d["FinalTransitions"] > 0)]
        ax1.scatter(d["InitialTransitions"], d["FinalTransitions"], s=8,
                    alpha=0.25, color=COLORS[v], label=v)
    lim = [1, agg["InitialTransitions"].max()]
    ax1.plot(lim, lim, "k--", lw=1, label="sin reducción")
    ax1.set_xscale("log"); ax1.set_yscale("log")
    ax1.set_xlabel("Transiciones iniciales (log)")
    ax1.set_ylabel("Transiciones finales (log)")
    ax1.set_title("Tamaño del resultado (transiciones)")
    ax1.legend(); ax1.grid(True, which="both", alpha=0.3)

    # (b) factor de reducción de transiciones por versión
    data = []
    for v in versions:
        d = agg[agg["Version"] == v]
        r = (d["FinalTransitions"] / d["InitialTransitions"]).replace(
            [np.inf, -np.inf], np.nan).dropna()
        data.append(r)
    bp = ax2.boxplot(data, labels=versions, showfliers=False, patch_artist=True)
    for patch, v in zip(bp["boxes"], versions):
        patch.set_facecolor(COLORS[v]); patch.set_alpha(0.6)
    ax2.set_ylabel("Transiciones finales / iniciales")
    ax2.set_title("Factor de reducción de transiciones\n(más bajo = colapsa más)")
    ax2.grid(True, axis="y", alpha=0.3)
    savefig(fig, "14_transitions_reduction.png")


def plot_tau_advantage(agg):
    """¿Cuándo gana branching? La ventaja vive en la estructura τ (silent steps).

    POR QUÉ INTERESA: explica el MECANISMO de la mejora y delimita cuándo
    conviene el reemplazo —insumo directo para el capítulo de evaluación—.
    Branching bisimulation se especializa en colapsar caminos τ (pasos
    silenciosos): ahí es donde reduce y donde su trabajo rinde. Si el modelo NO
    tiene τ, no hay nada que colapsar y su maquinaria extra (partición en
    bloques, splitters) es puro overhead; sobre grafos densos esa penalidad
    hace que WSOE incluso empate o gane. Donde SÍ hay τ —el caso realista de la
    síntesis composicional, llena de pasos internos tras componer y ocultar—
    v3fix gana cómodo.

    Dos paneles, ambos en el régimen con señal (n ≥ 100; abajo todo es ~igual
    por el overhead fijo) y midiendo speedup = T(WSOE)/T(v3fix) (>1 ⇒ v3fix gana):
      (a) speedup según haya o no τ-labels: la mediana ~duplica con τ.
      (b) speedup vs densidad m/n, coloreado por τ: los grafos densos (que son
          los τ=0) caen al break-even; los ralos con τ están bien arriba.
    """
    NMIN = 100
    wide = agg.pivot_table(index="Model", columns="Version", values="Time_ms")
    info = agg.groupby("Model")[["InitialStates", "InitialTransitions",
                                 "TauLabelsSize"]].first()
    d = info.join(wide[["WSOE", "v3fix"]]).dropna()
    d = d[(d["WSOE"] > 0) & (d["v3fix"] > 0) & (d["InitialStates"] >= NMIN)]
    d["sp"] = d["WSOE"] / d["v3fix"]
    d["dens"] = d["InitialTransitions"] / d["InitialStates"]
    sin_tau = d["TauLabelsSize"] == 0
    con_tau = ~sin_tau

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(13.5, 5.6))

    # (a) boxplot speedup por presencia de τ
    data = [d.loc[sin_tau, "sp"], d.loc[con_tau, "sp"]]
    labels = [f"sin τ\n(τ=0, n={sin_tau.sum()})", f"con τ\n(τ>0, n={con_tau.sum()})"]
    bp = ax1.boxplot(data, labels=labels, showfliers=False, patch_artist=True)
    for patch, c in zip(bp["boxes"], ["#d62728", "#1f77b4"]):
        patch.set_facecolor(c); patch.set_alpha(0.55)
    ax1.axhline(1.0, color="k", ls="--", lw=1, label="break-even (1×)")
    for i, dd in enumerate(data, 1):
        ax1.text(i, dd.median(), f"{dd.median():.2f}×", ha="center",
                 va="bottom", fontsize=10, fontweight="bold")
    ax1.set_yscale("log")
    ax1.set_ylabel("Speedup  T(WSOE) / T(v3fix)   [log]")
    ax1.set_title("La ventaja de v3fix se duplica cuando hay τ\n(n ≥ %d)" % NMIN)
    ax1.legend(); ax1.grid(True, axis="y", which="both", alpha=0.3)

    # (b) speedup vs densidad, coloreado por τ + tendencia
    ax2.scatter(d.loc[con_tau, "dens"], d.loc[con_tau, "sp"], s=14, alpha=0.4,
                color="#1f77b4", label="con τ (τ>0)")
    ax2.scatter(d.loc[sin_tau, "dens"], d.loc[sin_tau, "sp"], s=14, alpha=0.5,
                color="#d62728", label="sin τ (τ=0)")
    x, y = binned_median(d, "dens", "sp", log=True, nbins=12)
    if x is not None:
        ax2.plot(x, y, "k-o", ms=4, lw=2, label="mediana por bin")
    ax2.axhline(1.0, color="k", ls="--", lw=1)
    ax2.set_xscale("log"); ax2.set_yscale("log")
    ax2.set_xlabel("Densidad  m / n  (transiciones por estado, log)")
    ax2.set_ylabel("Speedup  T(WSOE) / T(v3fix)   [log]")
    ax2.set_title("Grafos densos ⇒ τ=0 ⇒ se pierde la ventaja\n(la ventaja cae al break-even)")
    ax2.legend(fontsize=9); ax2.grid(True, which="both", alpha=0.3)
    savefig(fig, "15_tau_advantage.png")


def plot_time_vs_ctrl(agg):
    """Tiempo vs cantidad de transiciones controlables locales.

    POR QUÉ INTERESA: en síntesis de controladores lo que duele es la parte
    CONTROLABLE del modelo. CtrlLocalTransitions cuenta las transiciones con
    label controlable local (es el COMPLEMENTO de las τ dentro de las locales:
    AllLocalTransitions = transiciones τ + CtrlLocalTransitions). Graficar el
    tiempo contra ellas mide la sensibilidad del costo a la fracción controlable;
    si v2/v3fix son menos sensibles que WSOE, es un argumento a favor del reemplazo
    en la composición de plantas/requerimientos con muchas acciones controlables.
    Es la contraparte de 06 (que las binnea linealmente) en escala log-log con
    ajuste, y el complemento de 17_time_vs_tau (que grafica las τ propiamente).

    Sólo el régimen con controlables (CtrlLocalTransitions > 0). Se reporta el
    ajuste log-log (pendiente y R²) sobre n ≥ NMIN, como en 13_time_vs_transitions.
    """
    NMIN = 100
    fig, ax = plt.subplots(figsize=(8.5, 6.5))
    n_sin = {}
    for v in CANDIDATES_VS_WSOE:
        d = agg[agg["Version"] == v]
        n_sin[v] = int((d["CtrlLocalTransitions"] == 0).sum())
        d = d[(d["CtrlLocalTransitions"] > 0) & (d["Time_ms"] > 0)]
        ax.scatter(d["CtrlLocalTransitions"], d["Time_ms"], s=6, alpha=0.12,
                   color=COLORS[v])
        x, y = binned_median(d, "CtrlLocalTransitions", "Time_ms")
        fitd = d[d["InitialStates"] >= NMIN]
        # NO se muestra la pendiente: en este eje (correlacionado con n) no es un
        # discriminador de complejidad válido (ver 13); sólo el R² (qué tan bien
        # esta variable explica el tiempo) es informativo. La complejidad se lee
        # en 08 (vs estados).
        label = v
        if len(fitd) >= 2:
            _, r2 = _loglog_fit_r2(fitd["CtrlLocalTransitions"].values,
                                   fitd["Time_ms"].values)
            label = f"{v}:  R²={r2:.2f}"
        if x is not None:
            ax.plot(x, y, "-o", ms=4, color=COLORS[v], lw=2, label=label)
    ax.set_xscale("log"); ax.set_yscale("log")
    ax.set_xlabel("Transiciones controlables locales (log)")
    ax.set_ylabel("Tiempo [ms] (log)")
    ax.set_title("Tiempo vs transiciones controlables: sensibilidad del\n"
                 "costo a la fracción controlable  "
                 f"(R² del ajuste log-log para n ≥ {NMIN})")
    ax.legend(title="Versión", fontsize=9)
    ax.grid(True, which="both", alpha=0.3)
    # Nota explícita de cuántos modelos quedan afuera por no tener controlables.
    sin = "  ".join(f"{v}: {n_sin[v]}" for v in CANDIDATES_VS_WSOE)
    ax.text(0.02, 0.02, f"modelos sin transiciones controlables excluidos →  {sin}",
            transform=ax.transAxes, fontsize=8, color="#555555",
            va="bottom", ha="left")
    savefig(fig, "16_time_vs_ctrl.png")


def plot_time_vs_tau(agg):
    """Tiempo vs cantidad de transiciones τ (las locales NO controlables).

    POR QUÉ INTERESA: τ es exactamente lo que branching bisimulation sabe
    colapsar. En esta campaña las τ son los labels locales NO controlables
    (tauLabelsForBB = localAlphabet \\ controlables; ver TestBranchingEquivalence
    líneas 417-419), y la columna que las cuenta es InitialLocalTransitions —NO
    CtrlLocalTransitions, que es su complemento—. Graficar el tiempo contra la
    cantidad de τ mide la sensibilidad del costo a la estructura que el algoritmo
    nuevo explota. La lectura esperada es que v2/v3fix escalen con τ con
    pendiente/constante menores que WSOE —argumento directo a favor del reemplazo
    en el régimen realista de la composición, donde tras ocultar hay muchos τ—.

    Sólo el régimen con τ (InitialLocalTransitions > 0); los modelos sin τ no
    tienen nada que colapsar y se analizan aparte (ver 15_tau_advantage). Se
    reporta sólo el R² del ajuste log-log sobre n ≥ NMIN (no la pendiente: en
    este eje no es un discriminador de complejidad válido; ver 13 y 08).
    """
    NMIN = 100
    fig, ax = plt.subplots(figsize=(8.5, 6.5))
    n_sin_tau = {}
    for v in CANDIDATES_VS_WSOE:
        d = agg[agg["Version"] == v]
        n_sin_tau[v] = int((d["InitialLocalTransitions"] == 0).sum())
        d = d[(d["InitialLocalTransitions"] > 0) & (d["Time_ms"] > 0)]
        ax.scatter(d["InitialLocalTransitions"], d["Time_ms"], s=6, alpha=0.12,
                   color=COLORS[v])
        x, y = binned_median(d, "InitialLocalTransitions", "Time_ms")
        fitd = d[d["InitialStates"] >= NMIN]
        # NO se muestra la pendiente: en este eje (correlacionado con n) no es un
        # discriminador de complejidad válido (ver 13); sólo el R² (qué tan bien
        # esta variable explica el tiempo) es informativo. La complejidad se lee
        # en 08 (vs estados).
        label = v
        if len(fitd) >= 2:
            _, r2 = _loglog_fit_r2(fitd["InitialLocalTransitions"].values,
                                   fitd["Time_ms"].values)
            label = f"{v}:  R²={r2:.2f}"
        if x is not None:
            ax.plot(x, y, "-o", ms=4, color=COLORS[v], lw=2, label=label)
    ax.set_xscale("log"); ax.set_yscale("log")
    ax.set_xlabel("Transiciones τ  (locales no controlables, log)")
    ax.set_ylabel("Tiempo [ms] (log)")
    ax.set_title("Tiempo vs cantidad de τ: sensibilidad al trabajo que\n"
                 "branching bisimulation sabe colapsar  "
                 f"(R² del ajuste log-log para n ≥ {NMIN})")
    ax.legend(title="Versión", fontsize=9)
    ax.grid(True, which="both", alpha=0.3)
    # Nota explícita de cuántos modelos quedan afuera por no tener τ.
    sin = "  ".join(f"{v}: {n_sin_tau[v]}" for v in CANDIDATES_VS_WSOE)
    ax.text(0.02, 0.02, f"modelos sin τ excluidos →  {sin}",
            transform=ax.transAxes, fontsize=8, color="#555555",
            va="bottom", ha="left")
    savefig(fig, "17_time_vs_tau.png")


# --------------------------------------------------------------------------- #
# Tabla resumen
# --------------------------------------------------------------------------- #
def summary_table(agg):
    """Tabla resumen por versión -> summary_stats.csv y print por consola."""
    rows = []
    piv_t = agg.pivot_table(index="Model", columns="Version", values="Time_ms")
    piv_t = piv_t[(piv_t > 0).all(axis=1)]
    # igualdad con WSOE (tupla de tamaños finales; ver plot_equals_wsoe)
    eq_cols = ["FinalStates", "FinalTransitions", "FinalLocalTransitions"]
    wide_eq = agg.pivot_table(index="Model", columns="Version", values=eq_cols)
    for v in ORDER:
        d = agg[agg["Version"] == v]
        red = (d["FinalStates"] / d["InitialStates"]).replace(
            [np.inf, -np.inf], np.nan).dropna()
        row = {
            "Version": v,
            "n_modelos": d["Model"].nunique(),
            "Time_ms_mediana": d["Time_ms"].median(),
            "Time_ms_media": d["Time_ms"].mean(),
            "Time_ms_p95": d["Time_ms"].quantile(0.95),
            "reduccion_estados_mediana": red.median(),
            # memoria asignada en el régimen con señal (n>=100); ver plot_memory_delta
            "MemDelta_MB_med_n100": d.loc[d["InitialStates"] >= 100, "MemDelta_MB"].median(),
        }
        if v in piv_t.columns:
            row["speedup_vs_WSOE_mediana"] = (piv_t["WSOE"] / piv_t[v]).median()
        if v != "WSOE":
            eq = np.ones(len(wide_eq), dtype=bool)
            for c in eq_cols:
                eq &= (wide_eq[(c, v)].values == wide_eq[(c, "WSOE")].values)
            row["pct_igual_a_WSOE"] = 100 * np.nanmean(eq)
        rows.append(row)
    out = pd.DataFrame(rows).set_index("Version").reindex(ORDER)
    out_path = os.path.join(HERE, "summary_stats.csv")
    out.to_csv(out_path, float_format="%.4f")
    print("\n=== Resumen por versión ===")
    with pd.option_context("display.width", 160,
                           "display.float_format", lambda x: f"{x:.3f}"):
        print(out)
    print(f"\n  -> {os.path.relpath(out_path, HERE)}")


# --------------------------------------------------------------------------- #
def main():
    print("Cargando y normalizando datos...")
    df = load_long()
    agg = aggregate(df)
    print(f"  modelos: {agg['Model'].nunique()}  | versiones: {sorted(agg['Version'].unique())}")

    print("\nGenerando gráficos en figures/ ...")
    plot_time_vs_states(agg)
    plot_speedup_boxplot(agg)
    plot_phase_breakdown(agg)
    plot_reduction_quality(agg)
    plot_equals_wsoe(agg)
    plot_time_vs_controllables(agg)
    plot_iters_vs_states(agg)
    plot_complexity_slope(agg)
    plot_speedup_vs_states(agg)
    plot_complexity_normalized(agg)
    plot_memory_delta(agg)
    plot_v3c_vs_wsoe_size(agg)
    plot_time_vs_transitions(agg)
    plot_transitions_reduction(agg)
    plot_tau_advantage(agg)
    plot_time_vs_ctrl(agg)
    plot_time_vs_tau(agg)

    summary_table(agg)
    print("\nListo.")


if __name__ == "__main__":
    main()
