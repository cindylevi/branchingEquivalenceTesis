# `BranchingEquivalence` versión 2 — explicación del algoritmo

Documento de análisis de `versiones/BranchingEquivalence version 2.java`, mostrando
**cómo implementa el algoritmo del paper de Jansen** (`Papers/jansen.pdf`:
*"A simpler O(m log n) algorithm for branching bisimilarity on labelled transition
systems"*, Jansen, Groote, Keiren, Wijs, 2019).

Igual que la versión 0, el objetivo es **minimizar un MTS módulo bisimilaridad
branching**: agrupar los estados *branching bisimilares* en una clase y construir el
autómata cociente. Conviene leer primero
`BranchingEquivalence-v0-explicacion.md`, porque este documento asume los conceptos
del paper ya explicados ahí (Π_s / Π_t, bunches, splitters, transiciones inertes,
estados bottom, Invariante 3.2). Acá nos concentramos en **cómo lo hace la v2** y en
**qué gana respecto de v0** (sección final).

> **Qué es "versión 2".** Es una reescritura madura del mismo algoritmo, pensada para
> ser **correcta, robusta y eficiente** al integrarse con el resto del sistema
> (síntesis de controladores discretos, MTSA). Mantiene la misma complejidad
> O(m log n) del paper, pero:
> 1. reorganiza el cuerpo en **dos fases explícitas** (estabilizar estados / refinar
>    bunches) que se alternan;
> 2. trata las **SCCs τ como unidades atómicas** en `split` y `findBottomStates`
>    (corrige un bug semántico de v0);
> 3. usa **índices y estructuras por identidad** (`IdentityQueue`,
>    `targetStateToBunches`, `blockIdMap`) para no recorrer todo en cada paso;
> 4. agrega **adaptaciones de dominio** (estado de error, fluents) e
>    **instrumentación de tiempos**.

---

## 1. Conceptos del paper (recordatorio breve)

El algoritmo es un **refinamiento de particiones** que mantiene dos particiones en
paralelo (Sección 3.1 del paper):

| Símbolo | En el código | Qué representa |
|---------|--------------|----------------|
| Π_s (bloques `B`) | `Pi_s : List<Set<Long>>` | Qué estados *no* son equivalentes. Al terminar, cada bloque = una clase. |
| Π_t (bunches `T`) | `Pi_t : Set<Set<Triple>>` (por identidad) | Qué transiciones no-inertes pueden actuar como *splitter*. |

Recordatorio de definiciones (ver el doc de v0 para el detalle):
**transición inerte** = `τ` dentro del mismo bloque; **estado bottom** = sin `τ`
inerte saliente dentro del bloque; **bunch trivial** = una sola action-block-slice;
**Invariante 3.2** = si un bunch tiene transición desde `B`, todo bottom de `B`
tiene transición en ese bunch. Cuando se rompe, hay que **partir** el bloque.

---

## 2. Mapa: funciones del código ↔ partes del paper

| Función Java | Parte del paper | Rol |
|--------------|-----------------|-----|
| `buildMinimisedMTS` / `buildMinimisedMTSFromPartition` | post-proceso (no está en el paper) | Construyen el MTS cociente desde Π_s. La segunda permite **reusar** una partición ya calculada. |
| `getPartitions` | **Algoritmo 1** completo (1.1–1.30) | Núcleo: refina Π_s y Π_t. En v2 está organizado en **dos fases**. |
| `partitionIntoSCCWithTauLabels` + DFS + `buildReversedGraph` | Línea 1.1 (contraer τ-SCCs) | Kosaraju sobre el grafo `τ`. |
| `computeBvis` | Línea 1.2 (`B_vis`) | Estados que alcanzan inertemente una acción visible. |
| `split` | **Algoritmo 2** | Parte `B` en `R` (alcanza el splitter) y `U`. En v2 **expande por SCCs enteras**. |
| `findSplittableBlocks` | `splittableBlocks(T_{a→B'})`, líneas 1.8–1.9 | Bloques con transiciones en el bunch primario. |
| `findBottomStates` | `Bottom(B)`, líneas 1.26–1.27 | En v2 calcula bottoms **por SCC**. |
| `findNewNonInertTransitions` | `R -τ-> U`, líneas 1.20–1.25 | En v2 devuelve las τ nuevas **agrupadas por etiqueta** (`Map<String, …>`). |
| `refineSplitters` | manejo (informal en el paper) de splitters obsoletos tras un split | Subdivide los splitters que apuntaban a `B` en uno para `R` y otro para `U`. |
| `enqueueAffectedBunches` | re-encolado (implícito en el while externo del paper) | Re-encola solo los bunches afectados por un split, vía `targetStateToBunches`. |
| `updateStateToBlockMap` | mantenimiento | Reapunta estado→bloque tras un split. |
| `Splitter` (clase interna) | concepto de *splitter* en la *splitter list* | `(block, transitions, marks, isPrimary, groupId)`. |
| `IdentityQueue<T>` | (estructura auxiliar, no en el paper) | Cola con pertenencia por **identidad de referencia**. |

---

## 3. `getPartitions` — el algoritmo general paso a paso

Es el corazón. Implementa el **Algoritmo 1**, pero con el anidamiento *invertido*
respecto del paper: en vez de "un bunch → drenar todos sus splitters → próximo
bunch", la v2 alterna **Fase 1 (drenar todos los splitters pendientes)** y
**Fase 2 (partir un bunch)**. Es el mismo algoritmo y la misma complejidad; solo
cambia el orden en que se visitan las tareas.

### 3.0. Pre-paso de dominio — sacar las *initiating actions* de τ (no está en el paper)

```java
for (Fluent fluent : fluents)
    for (Symbol ia : fluent.getInitiatingActions())
        allInitiatingActions.add(...);            // + traducciones
tauLabels.removeAll(allInitiatingActions);
```

Las *initiating actions* de un fluent **cambian el valor de una variable lógica del
sistema**, así que distinguen estados. Si quedaran tratadas como `τ`, el algoritmo
las consideraría invisibles y **colapsaría estados que el modelo considera
distintos**. Por eso se las quita de `tauLabels` antes de empezar. Esto es específico
del contexto DCS / MTSA, no del paper.

### 3.1. Preprocesamiento — contraer `τ`-SCCs (línea 1.1)

```java
List<Set<Long>> toMinimiseSCC = partitionIntoSCCWithTauLabels(toMinimise, tauLabels);
Map<Long, Set<Long>> stateToSCCMap = ...;   // estado -> su SCC
```

Igual que v0: el paper exige no tener ciclos de `τ` (Inv. 3.7) fusionando cada SCC en
un estado. La v2 **no fusiona físicamente**, sino que guarda `stateToSCCMap` y, en
cada punto crítico, ignora las `τ` internas a una SCC con
`!stateToSCCMap.get(s).equals(stateToSCCMap.get(t))`. La diferencia clave con v0 es
que la v2 **trata cada SCC como una unidad atómica** (ver §4).

`partitionIntoSCCWithTauLabels` es Kosaraju (forward DFS apilando por finalización +
backward DFS sobre el grafo `τ` invertido). En v2 los DFS iterativos llevan un
`break` después de empujar un hijo no visitado (DFS bien formado).

### 3.2. Particiones iniciales (líneas 1.2–1.4)

```java
Set<Long> Bvis   = computeBvis(...);           // 1.2: B_vis
Set<Long> Binvis = todos \ Bvis;                // 1.2: B_invis
// v2: separa además el ESTADO DE ERROR -1L en su propio bloque
if (estados contiene -1L) { errorBlock = {-1L}; Bvis.remove(-1L); Binvis.remove(-1L); }
Pi_s = [errorBlock?, Bvis?, Binvis?] (los no vacíos);   // 1.3
```

`computeBvis` es idéntica a v0: mapea SCCs a ids, marca como visibles las SCCs con
alguna transición no-`τ`, construye el grafo de predecesores `τ` entre SCCs y propaga
hacia atrás (BFS). `B_vis` = unión de las SCCs alcanzadas.

**Diferencia v2:** el **estado de error `-1L`** se separa como bloque propio. En DCS
el estado de error no es equivalente a ningún otro, así que dejarlo mezclado con
`Bvis`/`Binvis` sería incorrecto.

**Bunch inicial** (línea 1.4): todas las transiciones no-inertes.

```java
for cada s -a-> s':
    if a visible:                                       initialBunch.add(...)
    else if block(s) != block(s'):                      initialBunch.add(...)   // τ inter-bloque
Pi_t = { initialBunch };
```

### 3.3. Estructuras de trabajo (v2)

```java
Pi_t        : Set<Set<Triple>> (IdentityHashMap)   // bunches, por identidad
Pi_t_cola   : IdentityQueue<Set<Triple>>           // bunches pendientes de refinar (Fase 2)
splitterList: Deque<Splitter>                      // tareas de estabilización (Fase 1)
stateToBlockMap     : Map<Long, Set<Long>>         // estado -> bloque
blockIdMap          : IdentityHashMap<Set<Long>,Integer> // bloque -> id entero (hashing O(1))
targetStateToBunches: Map<Long, Set<Set<Triple>>>  // estado destino -> bunches que lo contienen
currentGroupId      : long                          // empareja primary/secondary del mismo refinamiento
```

Tres índices nuevos respecto de v0 (`blockIdMap`, `targetStateToBunches`,
`IdentityQueue`) que se explican en la comparación final.

**Splitters iniciales:** el bunch inicial se agrupa por bloque de origen y cada grupo
entra como splitter primario:

```java
for (cada bloque que origina transiciones del bunch inicial)
    splitterList.addLast(new Splitter(block, slice, slice, /*isPrimary*/true, groupId++));
```

### 3.4. El bucle principal — dos fases que se alternan (líneas 1.5–1.30)

```java
while (!Pi_t_cola.isEmpty() || !splitterList.isEmpty()) {
    // ───── FASE 1: ESTABILIZAR ESTADOS ─────  (drena toda la splitterList)
    // ───── FASE 2: REFINAR BUNCHES   ─────  (parte UN bunch de Pi_t_cola)
}
```

- **Fin de Fase 1:** Π_s queda estable respecto de *todos* los splitters pendientes.
- **Fin de Fase 2:** un bunch se partió y dejó splitters nuevos para la próxima Fase 1.

Esto corresponde 1:1 al Algoritmo 1; solo se reordena en qué momento se procesa cada
tarea. Termina cuando ambas estructuras quedan vacías ⇒ todos los bunches triviales ⇒
cada bloque es una clase (Teorema 3.10).

#### Fase 1 — estabilizar estados (líneas 1.13–1.29)

```java
while (!splitterList.isEmpty()) {
    Splitter cur = splitterList.removeFirst();   // 1.13
    B = cur.block;
    if (!Pi_s.contains(B)) continue;             // bloque ya refinado -> splitter obsoleto

    (R, U) = split(B, cur.transitions, cur.marks, ...);   // 1.14
    if (R.isEmpty() || U.isEmpty()) continue;

    Pi_s.remove(B); Pi_s.add(R); Pi_s.add(U);    // 1.16
    updateStateToBlockMap(...); blockIdMap: quitar B, dar id a R y U;

    // 1.17–1.18: si cur es PRIMARIO, su secundario asociado (mismo groupId) se
    //            simplifica a solo la parte de R (U ya es estable)
    if (cur.isPrimary) { buscar secundario por groupId, removerlo, reencolar solo R; }

    refineSplitters(B, R, U, splitterList, ...);          // subdividir splitters de B
    enqueueAffectedBunches(R, targetStateToBunches, Pi_t_cola);  // reencolar bunches afectados

    // 1.20–1.27: τ que dejaron de ser inertes, EN AMBAS DIRECCIONES (R->U y U->R)
    newFrontiers = [(R,U), (U,R)];
    while (newFrontiers no vacío) {
        (src, tgt) = poll;
        crossTaus = findNewNonInertTransitions(src, tgt);   // agrupadas por etiqueta
        for (tNew : crossTaus.values()) {
            Pi_t.add(tNew); Pi_t_cola.add(tNew); actualizar targetStateToBunches;  // 1.21
            (N, src') = split(src, tNew, tNew, ...);                               // 1.22
            if (N y src' no vacíos) {
                Pi_s: reemplazar src por N y src'; refineSplitters; enqueueAffectedBunches;  // 1.24
                newFrontiers += (N,src'), (src',N), (N,tgt), (src',tgt);            // cascada
            }
            if (!N.isEmpty()) {                                                     // 1.26–1.27
                bottoms = findBottomStates(N);
                for (bunch b : Pi_t) {
                    slice = transiciones de b que salen de N;
                    marcar UNA saliente por cada bottom; encolar Splitter(N, slice, marks, secondary);
                }
            }
            src = N;   // continuar la cascada desde N
        }
    }
}
```

Puntos a destacar de la Fase 1 en v2:

- **`split` primario optimizado (1.17–1.18):** cuando el splitter era primario, el
  secundario hermano (mismo `groupId`) se reduce a la parte de `R`, porque `U` ya
  quedó estable respecto del bunch secundario. Es el *3-way split* de Paige–Tarjan.
- **`refineSplitters`:** al partir `B`, todo splitter pendiente que apuntaba a `B`
  queda sin blanco; se subdivide en uno para `R` y otro para `U`, reconstruyendo las
  marcas (todas, si era primario; una por bottom, si era secundario).
- **`enqueueAffectedBunches`:** en vez de re-escanear todo `Π_t`, usa
  `targetStateToBunches` para re-encolar solo los bunches cuyos destinos cambiaron de
  bloque.
- **Ambas direcciones de τ + cascada (`newFrontiers`):** a diferencia del paper (que
  solo crea `R -τ-> U`), la v2 también considera `U -τ-> R` y realimenta la cola con
  los sub-bloques resultantes hasta agotar. Esto cubre casos que aparecen al
  integrarse con MTSs reales.

#### Fase 2 — refinar bunches (líneas 1.5–1.12)

```java
if (!Pi_t_cola.isEmpty()) {
    bunch = Pi_t_cola.pop();
    // agrupar por (acción, ID de bloque destino)  <-- v2 usa blockIdMap, no el Set entero
    slices = group bunch by (action, blockIdMap.get(targetBlock));
    if (slices.size() <= 1) continue;            // 1.5: bunch trivial

    // 1.6: elegir slice con count <= |bunch|/2 (process the smaller half); fallback: primera no vacía
    chosenTransitions = ...;

    // 1.7: partir el bunch y mantener targetStateToBunches sincronizado
    Pi_t.remove(bunch); newBunch = bunch \ chosen;
    Pi_t.add(newBunch); Pi_t.add(chosen); Pi_t_cola.add(newBunch); Pi_t_cola.add(chosen);
    actualizar targetStateToBunches para chosen y newBunch;

    // 1.8–1.12: por cada bloque splittable, crear primary + secondary con groupId compartido
    for (block : findSplittableBlocks(chosen, stateToBlockMap)) {
        primaryTrans   = chosen   ∩ (origen en block);
        secondaryTrans = newBunch ∩ (origen en block);
        gid = currentGroupId++;
        splitterList.addLast(Splitter(block, primaryTrans, primaryTrans /*marcar todo*/, true, gid));   // 1.10
        if (!secondaryTrans.isEmpty()) {
            // 1.11: marcar UNA secundaria por cada estado que también origina una primaria
            splitterList.addLast(Splitter(block, secondaryTrans, secondaryMarks, false, gid));
        }
    }
}
```

`findSplittableBlocks` en v2 devuelve directamente los bloques que originan
transiciones del bunch primario (`chosen`); el chequeo de "tener también
secundarias" se resuelve al construir las slices.

---

## 4. `split` — refinar un bloque bajo un splitter (Algoritmo 2)

```java
Pair<Set<Long>,Set<Long>> split(B, transitions, currentMarks, toMinimise, tauLabels, stateToSCCMap)
```

Devuelve `(R, U)` con `R` = estados de `B` que alcanzan inertemente una transición
del splitter, `U = B \ R`.

> Igual que v0, la v2 **no implementa las dos corutinas en lockstep** del paper:
> calcula `R` por alcanzabilidad hacia atrás y define `U = B \ R`. La diferencia
> esencial con v0 es la **atomicidad de las SCCs**:

```java
// v2: R arranca con la SCC ENTERA de cada origen marcado (no el estado suelto)
for (t : transitions) if (B.contains(t.getFirst())) R.addAll(stateToSCCMap.get(t.getFirst()));

// predecesores inertes dentro de B, ignorando los de la misma SCC
for (s : B) for (s -τ-> t en B):
    if (!SCC(s).equals(SCC(t))) inertPredecessorsInB[t].add(s);

// alcanzabilidad hacia atrás: al agregar un predecesor, se agrega su SCC ENTERA
while (worklist no vacía) {
    s = pop;
    for (pred : inertPredecessorsInB[s])
        for (estado : stateToSCCMap.get(pred))     // <-- expande por SCC completa
            if (R.add(estado)) worklist.add(estado);
}
U = B \ R;
```

Esto refleja una propiedad del algoritmo: **los estados de una misma SCC `τ` son
branching-bisimilares entre sí**, así que deben moverse siempre juntos. En v0, `R`
arrancaba con estados sueltos y la propagación era estado por estado, de modo que dos
estados de la misma SCC podían quedar uno en `R` y otro en `U` — semánticamente
incorrecto. La v2 lo evita por construcción.

---

## 5. Funciones auxiliares del refinamiento

### `findBottomStates(N, ...)` — `Bottom(B)` **por SCC**

```java
for (cada SCC distinta presente en N) {
    sccTieneTauSalienteEnN = ∃ s∈scc, s -τ-> dst, dst∈N, SCC(s)!=SCC(dst);
    if (!sccTieneTauSalienteEnN) bottomStates.addAll(scc);   // SCC entera es bottom
}
```

Una SCC es *bottom* si ninguno de sus estados tiene una `τ` saliente hacia otra SCC
del mismo bloque. Es más eficiente que el recorrido estado-por-estado de v0 y respeta
la atomicidad de las SCCs.

### `findNewNonInertTransitions(src, tgt, ...)` — `R -τ-> U` **agrupado por etiqueta**

```java
for (s : src) for (s -τ-> dst): if (dst en tgt) result[label].add((s, label, dst));
return result;   // Map<String, Set<Triple>>
```

Devuelve las `τ` que cruzan de `src` a `tgt` (dejaron de ser inertes), **agrupadas por
etiqueta**, para que cada acción pueda generar su propio bunch/splitter. En v0 esto
devolvía un único `Set` y solo se aplicaba en la dirección `R -> U`.

### `refineSplitters(B, R, U, ...)` — manejo de splitters obsoletos (v2, no en v0)

Cuando `B` se parte en `R` y `U`, recorre `splitterList` y, por cada splitter pendiente
con `block == B`, lo **subdivide**: reparte sus transiciones según el origen caiga en
`R` o `U`, reconstruye las marcas (todas si era primario; una por estado bottom de cada
parte si era secundario) y reinserta los sub-splitters al frente de la lista.

### `enqueueAffectedBunches(R, ...)` — re-encolado selectivo (v2, no en v0)

Por cada estado de `R`, consulta `targetStateToBunches` y re-encola en `Pi_t_cola`
solo los bunches que tienen a ese estado como destino. Evita re-escanear todo `Π_t`.

### `findSplittableBlocks`, `updateStateToBlockMap`, `Splitter`, `IdentityQueue`

- `findSplittableBlocks(chosen, map)`: bloques que originan transiciones del bunch
  primario (candidatos a volverse inestables).
- `updateStateToBlockMap`: reapunta los estados de `R` y `U` a su nuevo bloque.
- `Splitter` (clase interna): `(block, transitions, marks, isPrimary, groupId)`. Las
  marcas viven **adentro** del splitter y el `groupId` empareja primario/secundario.
- `IdentityQueue<T>`: `ArrayDeque` + `IdentityHashMap`; pertenencia por **referencia**,
  no por `equals`, para poder mutar bunches sin romper la cola.

---

## 6. `buildMinimisedMTS` / `buildMinimisedMTSFromPartition` — construir el cociente

Una vez que `getPartitions` devuelve `Π_s`, se arma el MTS minimizado igual que en v0:

1. Un estado nuevo por bloque; se mapea cada estado viejo a su id nuevo y se fija el
   inicial.
2. Cada transición original `oldFrom -a-> oldTo` se proyecta a `block(from) -a->
   block(to)`. Si queda un **auto-loop `τ`** dentro del mismo bloque, se traduce a una
   etiqueta controlable `c_<a>` registrada en `translatorControllable` (truco
   específico de DCS para no perder acciones controlables; no es parte del paper).

La v2 agrega **`buildMinimisedMTSFromPartition`**: misma construcción pero recibiendo
una partición ya calculada (no recomputa `getPartitions`) y devolviendo además
timings. Útil para reusar la partición o instrumentar.

---

## 7. Resumen del flujo (v2)

```
buildMinimisedMTS
└─ getPartitions                                   (Algoritmo 1, dos fases)
   ├─ sacar initiating actions de τ                (pre-paso de dominio, fuera del paper)
   ├─ partitionIntoSCCWithTauLabels                (1.1: contraer τ-SCCs, Kosaraju)
   ├─ computeBvis + errorBlock(-1L)                (1.2–1.3)
   ├─ bunch inicial + splitters iniciales          (1.4 + 1.8–1.12)
   └─ while (Pi_t_cola o splitterList no vacías)    (1.5–1.30)
      ├─ FASE 1: drenar splitterList               (1.13–1.29)
      │   ├─ split(B) -> (R,U)                      (1.14, Algoritmo 2, por SCCs)
      │   ├─ Pi_s := Pi_s\{B} ∪ {R,U}; blockIdMap   (1.16)
      │   ├─ optimización split primario           (1.17–1.18)
      │   ├─ refineSplitters + enqueueAffectedBunches
      │   └─ newFrontiers: τ no-inertes R->U y U->R + cascada (1.20–1.27)
      └─ FASE 2: partir UN bunch                    (1.5–1.12)
          ├─ slices por (acción, blockId)
          ├─ elegir slice <= |T|/2; partir bunch
          └─ findSplittableBlocks -> primary+secondary (mismo groupId)
└─ proyectar transiciones al cociente              (post-proceso, fuera del paper)
```

---

## 8. Comparación con la versión 0 — qué cambia y por qué es positivo

Las dos versiones implementan el **mismo algoritmo del paper con la misma complejidad
O(m log n)**. Los cambios de v2 no alteran la cota asintótica; mejoran **corrección,
robustez, eficiencia constante y mantenibilidad**. Tabla resumen y luego el detalle:

| Aspecto | v0 | v2 | Por qué v2 es mejor |
|---|---|---|---|
| Estructura del bucle | un único `while` que mezcla todo | dos fases explícitas (estabilizar / refinar) que se alternan | invariantes claros y verificables; más fácil de razonar y depurar |
| `split` y SCCs | `R` arranca con **estados sueltos**, propaga estado por estado | `R` arranca con **SCCs enteras**, propaga por SCC | **corrige un bug semántico**: dos estados de la misma SCC ya no pueden quedar separados |
| `findBottomStates` | itera estado por estado | itera por SCC (una SCC es bottom o no, en conjunto) | coherente con la atomicidad de SCCs y más eficiente |
| τ no-inertes nuevas | solo `R -τ-> U`, inline | `R -τ-> U` **y** `U -τ-> R`, con cascada (`newFrontiers`) | cubre casos reales que v0 no restabiliza |
| Cola de bunches | `ArrayDeque<Set<…>>` (pertenencia por `equals`) | `IdentityQueue` (pertenencia por referencia) | **elimina un bug latente**: mutar un bunch encolado rompe `equals`/`hashCode` |
| Splitters obsoletos tras split | se descartan con `if(!Pi_s.contains(B)) continue` | `refineSplitters` los **subdivide** en R y U preservando marcas | no se pierde trabajo de estabilización pendiente |
| Re-encolado de bunches | n/a (todo entremezclado) | `targetStateToBunches` + `enqueueAffectedBunches` (solo afectados) | evita re-escanear todo `Π_t` en cada split |
| Hashing de slices | clave `Pair<String, Set<Long>>` (hash O(\|bloque\|)) | clave `Pair<String, Integer>` vía `blockIdMap` | hash **O(1)**: domina cuando los bloques son grandes |
| Emparejar primary/secondary | adyacencia frágil en el `Deque` (`peekFirst`) | `groupId` explícito en cada `Splitter` | robusto ante reordenamientos de la lista |
| Marcas del splitter | `Map<Splitter, Set<Triple>>` aparte | campo `marks` dentro del `Splitter` | no se desincronizan splitter y marcas |
| Estado de error `-1L` | mezclado en `Bvis`/`Binvis` | bloque propio `errorBlock` | **semánticamente correcto**: el error no equivale a nada |
| Fluents (initiating actions) | no se contempla | se sacan de `tauLabels` antes del bucle | **corrige bug semántico**: esas acciones distinguen estados |
| Tarjan (DFS) | sin `break` tras empujar hijo | con `break` | DFS bien formado |
| Instrumentación | ninguna | `timingMap` por fase/subfase, `iterCount` | permite medir antes de optimizar |
| API de construcción | solo `buildMinimisedMTS` | + `buildMinimisedMTSFromPartition` | reusar particiones precomputadas |

### 8.1. Dos fases explícitas en vez de un while entremezclado

En v0, sacar un bunch, partirlo, crear splitters, drenarlos, detectar τ no-inertes y
restabilizar pasaba todo **dentro del mismo `while`**, lo que hacía difícil saber en
qué estado quedaban las particiones en cada punto. La v2 separa:

- **Fase 1** drena *toda* la `splitterList` ⇒ al terminar, Π_s es estable respecto de
  todos los splitters pendientes.
- **Fase 2** parte *un* bunch ⇒ deja splitters nuevos para la próxima Fase 1.

**Positivo:** invariantes claros en los límites de fase, lo que simplifica el
razonamiento de correctitud y el debugging. No cambia la complejidad.

### 8.2. SCCs atómicas en `split` y `findBottomStates` (corrección de bug)

El paper asume que las SCCs `τ` ya fueron **contraídas a un único estado**, de modo
que son indivisibles. v0 simula la contracción con `stateToSCCMap` pero **opera sobre
estados individuales** en `split`: inicializa `R` con estados sueltos y propaga estado
por estado. Resultado: dos estados de la misma SCC podían terminar uno en `R` y otro
en `U`, partiendo una clase que el algoritmo considera atómica → **resultado
incorrecto** en ciertos grafos.

v2 expande siempre por **SCC completa** (en la inicialización de `R`, en la
propagación backward y en `findBottomStates`). **Positivo:** restaura la semántica del
paper sin pagar el costo de reescribir físicamente el MTS.

### 8.3. `IdentityQueue` (corrección de bug latente)

v0 encola `Set<Triple>` en un `ArrayDeque` y luego **muta** esos sets (`addAll`,
`removeAll`) mientras siguen en la cola. Como `equals`/`hashCode` de un `Set` dependen
del contenido, `contains` puede dar falsos negativos/positivos tras la mutación. v2
usa pertenencia por **identidad de referencia** (`IdentityHashMap`), así que un bunch
sigue siendo "el mismo" aunque cambie su contenido. **Positivo:** elimina una clase de
bugs difíciles de reproducir.

### 8.4. `refineSplitters` + `targetStateToBunches` (no perder trabajo / no re-escanear)

Cuando un bloque `B` se parte, en v0 los splitters pendientes que apuntaban a `B` se
**descartaban** al sacarlos (`if(!Pi_s.contains(B)) continue`). Eso es correcto solo
porque v0 procesa todo dentro del mismo paso; con fases separadas hay que **conservar**
ese trabajo. `refineSplitters` subdivide cada splitter de `B` en uno para `R` y otro
para `U` (con marcas reconstruidas según primary/secondary). Y `targetStateToBunches`
permite re-encolar **solo** los bunches afectados por el split en vez de todo `Π_t`.
**Positivo:** corrección bajo el nuevo orden de evaluación + menos trabajo redundante.

### 8.5. `blockIdMap` (eficiencia de hashing)

v0 usa el `Set<Long>` del bloque como clave de hash al agrupar slices; el `hashCode`
de un set es O(tamaño del bloque). Con bloques grandes y muchas slices, ese costo
domina. v2 asocia un `int` a cada bloque (`blockIdMap`, sincronizado en cada split) y
hashea por `(acción, id)` en O(1). **Positivo:** mejora la constante de tiempo, sobre
todo en instancias grandes.

### 8.6. Adaptaciones de dominio (corrección al integrar con DCS/MTSA)

- **`errorBlock` (`-1L`):** el estado de error se aísla en su propio bloque; no debe
  fusionarse con nadie. v0 lo dejaba mezclado.
- **Fluents:** las *initiating actions* se quitan de `tauLabels` antes del bucle,
  porque distinguen estados. v0 las habría tratado como invisibles y colapsado estados
  distintos.

**Positivo:** la v2 produce resultados correctos en el contexto real donde corre el
algoritmo, no solo en LTSs "puros".

### 8.7. Cosas que **no** cambian

`computeBvis`, la idea de Kosaraju, el bunch inicial, la elección "process the smaller
half" (`≤ |T|/2`), el manejo del split primario (3-way de Paige–Tarjan) y la
construcción del MTS cociente (incluido el truco `c_<a>` para self-loops τ) son
esencialmente iguales. Y, sobre todo, **`split` sigue sin usar las corutinas en
lockstep del paper**: tanto v0 como v2 calculan `R` por alcanzabilidad hacia atrás y
hacen `U = B \ R`, por lo que ninguna de las dos alcanza todavía la cota fina del
Algoritmo 2 (esa optimización corresponde a versiones posteriores).
