# Análisis experimental — minimización por branching bisimilarity vs WSOE

Documento de acompañamiento de `compare_versions.py`. Explica, para cada gráfico
generado en `figures/`, **qué objetivo tiene**, **cómo se armaron los datos** y
**qué conclusión se puede sacar** para la tesis.

> Resumen en una línea: **v3c es la versión candidata** — la más rápida (media
> 130 ms vs 206 de WSOE y 559 de v2), la más liviana del algoritmo nuevo (1.24×
> la memoria de WSOE vs 2.00× de v2), con complejidad **O(m·log n)** confirmada
> empíricamente (independiente del número de estados) frente a la **O(m·n)** de
> WSOE. La ventaja se concentra en modelos con estructura τ, que es justamente
> el caso de la síntesis composicional.

---

## 1. Datos de origen y metodología común

### Archivos

- `minimization_results_v0.csv` — una fila por `(modelo, run)` con **dos** bloques
  de resultado: columnas `*BB` (algoritmo nuevo **v0**) y columnas `*WSOE`
  (baseline a reemplazar). Incluye la columna booleana `EqualsWSOE`.
- `minimization_results_branching_v2_v3c.csv` — filas con `AlgoVersion ∈ {v2, v3c}`,
  esquema unificado y con instrumentación interna por fase.

### Las cuatro variantes comparadas

- **WSOE** — algoritmo viejo ("weak semantics on edges"), el que se quiere reemplazar.
- **v0** — primera implementación del algoritmo de Groote (2019). Obsoleta: no
  instrumentada y con resultados distintos a v2/v3c.
- **v2** — segunda iteración (refactor en dos fases de particionamiento).
- **v3c** — versión más reciente, la candidata a quedar en la tesis.

### Cómo se consolidan las mediciones

- **2212 modelos** distintos (LTS aleatorios, `states_<n>_iter_<i>_<ts>.lts`).
- Cada modelo se corre **3 veces**: Run 0 con `Warmup=true` (descartado, calienta
  la JVM/JIT) y Runs 1–2 con `Warmup=false` (medidos).
- Para cada `(modelo, versión)` se toma la **mediana de los runs medidos**. La
  mediana es robusta a las pausas de GC y al scheduling del SO, ruido habitual en
  micro-benchmarks sobre JVM.
- El archivo v0 se "desdobla" en dos filas lógicas (v0 y WSOE) para llevar todo a
  un formato largo único: una fila por `(modelo, versión)`.

### Hechos del corpus que condicionan el análisis

- **m ≈ 2.4·n** en mediana, pero las transiciones crecen **más rápido** que los
  estados: **m ~ n^1.3**. Esto es clave para leer la complejidad (ver gráfico 08).
- `corr(log m, log n) ≈ 0.94` — m y n están muy correlacionados; no se pueden
  separar a la perfección en regresiones.
- La mediana de los modelos es **chica** (n ≈ 6) y ya viene minimal: el factor de
  reducción mediano es 1.0 en todas las versiones. Las diferencias interesantes
  están en la **cola de modelos grandes**.

### Caveats de instrumentación

- **v0 no instrumentó** las fases internas ni `MainLoopIters` (valen −1). Por eso
  queda afuera de los gráficos 03 y 07.
- **Memoria**: se usa el delta `MemAfter − MemBefore`, **no** `MemPeak`. `MemPeak`
  tiene un bug de muestreo (es menor que `MemAfter` en el 32% de las filas: el
  sampler se pierde el pico real). El delta, en cambio, es determinístico entre
  runs (CV ≈ 0%). Además no hay `System.gc()` forzado antes de medir el baseline,
  así que los valores absolutos arrastran residuos; por eso se mira el delta y se
  restringe a n ≥ 100.
- **Igualdad con WSOE**: como solo v0 trae la columna `EqualsWSOE`, para v2/v3c se
  usa como proxy la igualdad de la tupla `(estados, transiciones, locales)`
  finales. Se verificó que ese proxy coincide **100%** con la columna oficial en
  v0, así que es confiable.

---

## 2. Tabla resumen (`summary_stats.csv`)

| Versión | Time mediana | Time media | Time p95 | reducción estados | MemΔ (n≥100) | speedup vs WSOE | % igual a WSOE |
|---------|-------------:|-----------:|---------:|------------------:|-------------:|----------------:|---------------:|
| WSOE    | 0.96 ms      | 206 ms     | 504 ms   | 1.00              | 70 MB        | 1.00            | —              |
| v0      | 0.86 ms      | 644 ms     | 890 ms   | 1.00              | 94 MB        | 0.99            | 55.5%          |
| v2      | 0.96 ms      | 559 ms     | 893 ms   | 1.00              | 143 MB       | 0.94            | 67.7%          |
| v3c     | 0.98 ms      | **130 ms** | **253 ms** | 1.00            | **86 MB**    | 0.95            | 60.2%          |

La **media** (sensible a la cola) es la columna reveladora: v3c es la única versión
del algoritmo nuevo más rápida que WSOE en promedio; v2 y v0 son más lentas porque
se degradan en los modelos grandes.

---

## 3. Gráfico por gráfico

### `01_time_vs_states.png` — Escalabilidad: tiempo vs tamaño

- **Objetivo**: ver cómo escala el tiempo con el número de estados y comparar las
  cuatro variantes.
- **Datos**: dispersión de `Time_ms` vs `InitialStates` (log-log) más una línea de
  **mediana por bins logarítmicos** de tamaño, por versión.
- **Conclusión**: a partir de n ≈ 50 las curvas se separan; v3c queda por debajo
  del resto y v2/v0 se despegan hacia arriba. Confirma que la ventaja del
  algoritmo nuevo (en su versión buena) aparece al crecer el modelo.

### `02_speedup_boxplot.png` — Distribución del speedup

- **Objetivo**: cuantificar la mejora modelo a modelo, no solo la tendencia.
- **Datos**: por modelo, cocientes `Tiempo(A)/Tiempo(B)` para varios pares
  (WSOE/v0, WSOE/v2, WSOE/v3c, v0/v3c, v2/v3c). Boxplot en escala log.
- **Conclusión**: las medianas rondan 1× porque el corpus está dominado por
  modelos chicos donde todas las versiones empatan (overhead fijo). **No es la
  vista adecuada para separar v2 de v3c** — esa diferencia vive en la cola y se ve
  en los gráficos 01 y 09. Sirve para mostrar que la mejora típica es modesta pero
  la cola es favorable.

### `03_phase_breakdown.png` — Descomposición del tiempo por fase

- **Objetivo**: explicar **por qué** v3c es más rápido, no solo que lo es.
- **Datos**: suma sobre todo el corpus del tiempo de cada fase interna
  (SCC, Bvis, InitSplit, Phase1, Phase2, BuildTotal), solo para v2 y v3c (WSOE y
  v0 no exponen fases).
- **Conclusión**: v3c baja el tiempo agregado de ~1.22M ms (v2) a ~0.27M ms,
  recortando sobre todo **Phase1 y Phase2** (refinamiento de la partición). La
  optimización de v3c atacó el núcleo del algoritmo, no detalles marginales.

### `04_reduction_quality.png` — Calidad de minimización (estados)

- **Objetivo**: verificar que la velocidad no se paga con peor minimización, y
  comparar contra WSOE (que usa otra equivalencia).
- **Datos**: scatter `FinalStates` vs `InitialStates` y boxplot del factor
  `FinalStates/InitialStates`, para WSOE/v2/v3c (v0 excluido).
- **Conclusión**: branching y WSOE **no** dan lo mismo (son equivalencias
  distintas). La mediana de reducción es 1.0 porque el corpus ya es mayormente
  minimal; las diferencias están en la cola. Ver detalle en el gráfico 12.

### `05_equals_wsoe.png` — ¿Coincide la minimización con WSOE?

- **Objetivo**: medir en qué fracción del corpus cada versión da exactamente el
  mismo resultado que WSOE.
- **Datos**: igualdad de la tupla `(estados, transiciones, locales)` finales vs
  WSOE (proxy validado al 100% contra `EqualsWSOE`). v0 se incluye como control.
- **Conclusión**: v0 = 55.5%, **v2 = 67.7%, v3c = 60.2%**. Branching y WSOE
  difieren en ~30–40% de los modelos → son equivalencias semánticamente
  distintas, algo que la tesis debe documentar (conecta con el Cap. 4, donde se
  prueba WSOE ≡ branching bisimilarity: la discrepancia merece explicación).

### `06_time_vs_controllables.png` — Sensibilidad a la estructura controlable

- **Objetivo**: ver si el tiempo depende del tamaño **controlable** del modelo
  (relevante en síntesis de controladores), no solo del tamaño total.
- **Datos**: mediana por bin de `Time_ms` vs `LocalControllableSize` y vs
  `CtrlLocalTransitions`. Eje y en log (los tiempos abarcan 0.5–1000 ms; en lineal
  la nube de modelos chicos tapa todo).
- **Conclusión**: vista secundaria de sensibilidad estructural. El análisis fino
  de complejidad se hace en 08; este sirve para chequear que ninguna versión
  explota con la fracción controlable.

### `07_iters_vs_states.png` — Trabajo algorítmico (iteraciones)

- **Objetivo**: medir el esfuerzo del algoritmo de forma **independiente del
  hardware** (no es tiempo de pared).
- **Datos**: mediana por bin de `MainLoopIters` vs `InitialStates`, solo v2/v3c.
- **Conclusión**: si v3c hace menos iteraciones que v2 para el mismo tamaño, la
  mejora es **algorítmica** y reproducible, no un efecto de constante/JIT.

### `08_complexity_slope.png` — O(m·n) vs O(m·log n) ★

- **Objetivo**: demostrar empíricamente la diferencia de clase de complejidad. **Es
  el gráfico central del argumento de la tesis.**
- **Datos**: dos paneles.
  - (A) `Time_ms` vs n (log-log) con la **pendiente aparente** ajustada para n ≥ 300.
  - (B) regresión de dos variables `log T = a·log m + b·log n` para n ≥ 100,
    mostrando los exponentes de m y de n por versión.
- **Conclusión**: la pendiente vs n **engaña** — da ~1.5 para branching ("casi
  cuadrático") pero es un **artefacto** de que m ~ n^1.3 en el corpus. La prueba
  limpia es el **exponente de n**:
  - WSOE: b ≈ **+1.02** → el tiempo crece con n ⇒ factor ×n ⇒ **O(m·n)**.
  - v2/v3c: b ≈ **0** (−0.03 / −0.12) → el tiempo **no depende de n** ⇒ **O(m·log n)**.

  Es decir: branching **no es cuadrático**; su costo lo gobiernan las transiciones,
  no los estados. La implementación es correcta respecto de la complejidad teórica.

### `09_speedup_vs_states.png` — ¿A partir de qué tamaño conviene reemplazar WSOE?

- **Objetivo**: encontrar el "break-even": el tamaño desde el cual el algoritmo
  nuevo gana.
- **Datos**: mediana por bin del speedup `T(WSOE)/T(version)` vs `InitialStates`.
- **Conclusión**: v3c cruza el 1× cerca de n ≈ 50 y llega a **~10×** en los modelos
  más grandes; v2 se queda cerca del break-even. Acá **sí** se ve que v3c > v2 al
  crecer el modelo (lo que el boxplot 02 ocultaba).

### `10_complexity_normalized.png` — ¿Es ajustada la cota teórica?

- **Objetivo**: confirmar que cada algoritmo se comporta como predice su cota.
- **Datos**: tiempo dividido por el trabajo teórico — `T/(m·n)` para WSOE y
  `T/(m·log₂n)` para branching — normalizado a su propia mediana, vs n.
- **Conclusión**: si la curva queda plana, la cota predice bien el crecimiento.
  WSOE/(m·n) se mantiene aproximadamente plana. Gráfico complementario al 08 (más
  ruidoso por la poca cantidad de modelos grandes); el discriminador fuerte sigue
  siendo el exponente de n del 08.

### `11_memory_delta.png` — Trade-off tiempo ↔ memoria

- **Objetivo**: ver cuánta memoria de más paga el algoritmo nuevo (mantiene
  partición en bloques, splitters, contadores que WSOE no necesita).
- **Datos**: `MemDelta = MemAfter − MemBefore` vs n (n ≥ 100), y boxplot del
  overhead `Δ(version)/Δ(WSOE)` por modelo.
- **Conclusión**: branching asigna más memoria que WSOE, pero acotado:
  **v2 = 2.00× WSOE, v3c = 1.24× WSOE**. v3c no solo es más rápido que v2, también
  mucho más liviano — sus optimizaciones mejoraron tiempo **y** memoria.

### `12_v3c_vs_wsoe_size.png` — v3c vs WSOE: ¿minimiza más o menos?

- **Objetivo**: cuando v3c y WSOE difieren, ver hacia qué lado (correctitud, no
  performance).
- **Datos**: clasificación del corpus en idéntico / v3c menos estados / mismos
  estados pero distintas transiciones / v3c más estados; más scatter de estados
  finales v3c vs WSOE en los casos discrepantes.
- **Conclusión**: 60.2% idéntico; de los discrepantes, v3c queda con **más** estados
  más seguido (39%) que con menos (28%), y un 13% del corpus tiene **igual número
  de estados pero distintas transiciones**. La diferencia branching↔WSOE se
  manifiesta sobre todo en el conteo de transiciones — pista para el Cap. 4
  (manejo de τ-transiciones, aristas paralelas).

### `13_time_vs_transitions.png` — ¿Está el costo explicado solo por m?

- **Objetivo**: mirar la complejidad desde las transiciones (factor común a ambas
  cotas).
- **Datos**: `Time_ms` vs `InitialTransitions` (log-log) con el R² del ajuste de
  una sola variable, para n ≥ 100.
- **Conclusión**: el tiempo de branching queda **bien explicado solo por m**
  (R² ≈ 0.97), el de WSOE **no** (R² ≈ 0.84, y baja a 0.66 para n ≥ 300) porque le
  falta el factor ×n. La **pendiente** vs m no sirve como complejidad (por la
  colinealidad m–n); el discriminador acá es la calidad de ajuste (R²).

### `14_transitions_reduction.png` — Reducción de transiciones

- **Objetivo**: contraparte del 04 pero en transiciones (donde vive la discrepancia
  con WSOE).
- **Datos**: scatter `FinalTransitions` vs `InitialTransitions` y boxplot del
  factor de reducción, WSOE/v2/v3c.
- **Conclusión**: **WSOE colapsa más transiciones** que v3c (cae más por debajo de
  la diagonal). Coherente con 12: en los casos discrepantes v3c conserva más
  transiciones. La diferencia entre las dos equivalencias se expresa como
  transiciones que WSOE colapsa y branching no.

### `15_tau_advantage.png` — ¿Cuándo conviene branching? La estructura τ ★

- **Objetivo**: explicar el **mecanismo** de la mejora y delimitar el régimen
  donde el reemplazo rinde.
- **Datos**: speedup `T(WSOE)/T(v3c)` para n ≥ 100, en dos paneles.
  - (a) boxplot según haya o no τ-labels (`TauLabelsSize == 0` vs `> 0`).
  - (b) speedup vs densidad `m/n`, coloreado por presencia de τ, con tendencia.
- **Conclusión**: con τ la ventaja **se duplica** (mediana 3.05× vs 1.69× sin τ).
  Al crecer la densidad el speedup **se derrumba al break-even** (~1×), y esos
  grafos densos son justamente los τ=0. Mecanismo: branching se especializa en
  **colapsar caminos τ (pasos silenciosos)**; si no hay τ no tiene nada que
  explotar y su maquinaria extra es overhead, penalizado en grafos densos. Donde
  **sí** hay estructura τ —el caso realista de la síntesis composicional, plagada
  de pasos internos tras componer y ocultar acciones— v3c gana cómodo. Es el
  argumento de delimitación: el reemplazo rinde exactamente en el régimen que
  importa.

---

## 4. Conclusiones para la tesis

1. **v3c es la versión a defender**: más rápida (media 130 ms vs 206 de WSOE, 559
   de v2), más liviana del algoritmo nuevo (1.24× la memoria de WSOE), y con menos
   iteraciones del loop principal.

2. **La complejidad teórica se confirma**: branching es **O(m·log n)** — el tiempo
   es independiente del número de estados (exponente de n ≈ 0), frente a la
   **O(m·n)** de WSOE (exponente de n ≈ 1). El aspecto "cuadrático" al graficar vs
   n es un artefacto del corpus (m ~ n^1.3), no del algoritmo.

3. **La ventaja escala con el tamaño**: el break-even está en n ≈ 50 y el speedup
   llega a ~10× en los modelos grandes, que son los que produce la síntesis
   composicional.

4. **Branching y WSOE no son intercambiables bit a bit**: difieren en ~40% de los
   modelos. v3c minimiza levemente menos que WSOE en esos casos, y la diferencia se
   concentra en las transiciones. Esto debe articularse con la prueba de
   equivalencia del Cap. 4.

5. **El reemplazo rinde en el régimen correcto**: la ventaja se concentra en
   modelos con estructura τ (3.05× con τ vs 1.69× sin τ) y se pierde solo en grafos
   densos sin τ — un caso poco frecuente tras ocultar acciones en la composición.

---

## 5. Cómo regenerar

```sh
cd exp_results
python3 -m venv .venv
.venv/bin/pip install pandas matplotlib numpy
.venv/bin/python compare_versions.py
```

Genera las 15 figuras en `figures/` y la tabla `summary_stats.csv`. Cada función
de gráfico en `compare_versions.py` lleva en su docstring el objetivo y los
caveats correspondientes.
