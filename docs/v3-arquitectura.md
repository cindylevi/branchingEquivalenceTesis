# BranchingEquivalence v3 — arquitectura

Documento que describe cómo funciona la versión 3 del algoritmo (post-Phase C
del plan Jansen et al. 2019) y qué representa cada estructura de datos.
Está pensado como referencia para volver al código después de un tiempo y
poder ubicarse rápido. Asume conocimiento básico de branching bisimilarity y
del esquema general de Groote–Vaandrager (split-by-splitter).

> **Estado del archivo `BranchingEquivalence version 3.java`:** las fases A,
> B, C y D del plan están integradas en este snapshot. Los snapshots
> intermedios (`v3-A1 y D`, `v3-B`) quedan en `versiones/` como referencia
> histórica.

## Idea general

El algoritmo refina dos particiones simultáneamente hasta el punto fijo:

- **Πs** — partición de estados. Inicia con `{Bvis, Binvis, errorBlock?}` y
  se refina hasta que cada bloque sea una clase de equivalencia de branching
  bisimilarity.
- **Πt** — partición de transiciones (los "bunches" del paper). Inicia como
  un único bunch con todas las transiciones no-inertes y se refina por
  `(action, target_block)`.

El loop principal alterna dos fases:

```
while splitterList no vacío  ó  hay block-bunch-slice inestable:
    FASE 1 (estabilizar bloques): drenar splitterList partiendo bloques con split()
    FASE 2 (refinar bunches):     popear UNA block-bunch-slice inestable y peelear su bunch
```

Phase 1 y Phase 2 se alimentan mutuamente: partir un bloque marca BBS
inestables (porque las nuevas piezas pueden distinguir bunches), y peelear
un bunch genera splitters nuevos.

## Las tres estructuras

### `RefinablePartition` — Πs

Implementación array-based de la partición de estados. Reemplaza la versión
naive con `List<Set<Long>>` + `Map<Long, Set<Long>> stateToBlockMap` de v2.

**Representación.** Un único array maestro `elements: long[]` con cada bloque
ocupando un slice contiguo. Tres "rangos" anidados por bloque:

```
start         bottomEnd        rDestEnd          end
  │              │                 │               │
  ▼              ▼                 ▼               ▼
  [   bottoms   ][   non-bottom   ][  R-destined  ]
```

Invariante: `start ≤ bottomEnd ≤ rDestEnd ≤ end`. Las tres particiones
internas son slices contiguos del bloque y se mantienen así con swaps.

**Campos clave de `Block`:**

| Campo        | Significado |
|--------------|---|
| `start, end` | Rango del bloque en `elements[]` |
| `bottomEnd`  | Fin del prefijo de bottoms (estados sin τ-out a otra SCC dentro del bloque) |
| `rDestEnd`   | Inicio del sufijo R-destined (estados marcados durante un `split` en curso) |
| `id`         | Identificador único, usado para keys de bunches |
| `alive`      | Falso después de `splitOffR` cuando se reemplaza por uBlock + rBlock |

**Operaciones críticas:**

| Operación | Costo | Qué hace |
|-----------|-------|----------|
| `blockOf(s)` | O(1) | Lookup posición + `blockOfElement[pos]` |
| `addToR(s)` | O(1) | Swap a `rDestEnd-1`, decrementa rDestEnd |
| `clearR(b)` | O(1) | `rDestEnd = end` |
| `splitOffR(b)` | O(\|b\|) | Crea `(rBlock, uBlock)`, reasigna `blockOfElement` en el sufijo |
| `markAsBottom(s)` | O(1) | Swap a `bottomEnd`, incrementa bottomEnd |
| `bottoms(b)` | iterador | Rango `[start, bottomEnd)` |
| `states(b)` | iterador | Rango `[start, end)` |
| `rDestStates(b)` | iterador | Rango `[rDestEnd, end)` |

**Importante.** `states(b)` es un iterador *posicional* sobre `elements[]`.
Si se llama a `addToR(s)` durante esa iteración, los swaps internos pueden
hacer que el iterador salte estados (un estado swap-eado por encima del
cursor nunca se vuelve a leer). Las rutinas que mutan durante iteración
(`applyExplicit`, `applyComplement` en `BranchingEquivalence`) toman
**snapshot a `List<Long>` antes de empezar** para evitar el bug.

### `LinkedTransitionPartitions` — Πt encadenada

Refinable partition de transiciones (Jansen et al. §5.1). Reemplaza cuatro
estructuras de v2/v3-B:

| v2/v3-B | v3 |
|---------|----|
| `Set<Triple<Long,String,Long>>` (cada bunch) | `BunchSlice` con identidad |
| `Set<Set<Triple>>` Πt | `Lt.liveBunches()` |
| `IdentityQueue<Set<Triple>>` Pi_t_cola | `Lt.globalUnstable` (lista de BBS con flag por slice) |
| `Map<Long, Set<Set<Triple>>> targetStateToBunches` | `byTarget` (estática) + `notifyBlockSplit` |

**Cuatro vistas simultáneas de cada transición:**

```
Transition t
  ├── bunch          : BunchSlice         — qué bunch de Πt la contiene  (refinable)
  ├── blockBunch     : BlockBunchSlice    — sub-grupo (bunch × source-block) (refinable)
  ├── outgoing(s)    : por estado fuente  — bySource[s]                   (estática)
  └── incoming(s)    : por estado destino — byTarget[s]                   (estática)
```

Las dos primeras se parten al refinar Πt y Πs respectivamente. Las dos
últimas no cambian: las define el LTS de entrada.

**`Transition`.** Objeto con identidad (no `Triple`). Campos: `source`,
`action`, `target`, punteros a `bunch` y `blockBunch`, contador `untested`
(reservado, hoy sin uso — se usa la versión per-SCC adentro de `split`).

**`BunchSlice`.**

| Campo | Significado |
|-------|-------------|
| `id` | Único por bunch |
| `alive` | Falso si quedó vacío después de un peel |
| `transitions` | `Set<Transition>` con identidad |
| `blockSlices` | `Map<Block, BlockBunchSlice>` por bloque fuente vivo |

**`BlockBunchSlice`.**

| Campo | Significado |
|-------|-------------|
| `id`, `bunch`, `sourceBlock` | Tripleta que identifica la BBS |
| `alive` | Falso después de `notifyBlockSplit` |
| `stable` | Falso ⇔ está en `globalUnstable` |
| `posInGlobalUnstable` | Índice para swap-remove O(1) |
| `transitions` | `Set<Transition>` con identidad |

**Tablas auxiliares.**

| Estructura | Para qué sirve |
|------------|----------------|
| `allTransitions` | Lista maestra (sin orden particular) |
| `transitionLookup: Triple → Transition` | Dedupe en `addTransition` (asegura una sola instancia por triple) |
| `bySource: Long → List<Transition>` | Vista estática outgoing |
| `byTarget: Long → List<Transition>` | Vista estática incoming, usada en target-side de `notifyBlockSplit` |
| `liveBunches: List<BunchSlice>` | Iteración de bunches vivos |
| `slicesByBlock: Block → List<BBS>` | Indexa BBS por su bloque fuente |
| `globalUnstable: List<BBS>` | "Cola" de BBS a procesar en Phase 2 |

**Operaciones críticas:**

| Operación | Qué hace |
|-----------|----------|
| `seedSingleBunch(initial)` | Πt₀: un solo bunch con todas las transiciones; crea BBS por bloque fuente |
| `newBunch(initial)` | Bunch nuevo con BBS inestables (cascada τ no-inerte de Phase 1) |
| `peelSlice(bunch)` | Subagrupa por `(action, target_block.id)`, mueve la primera slice ≤ \|bunch\|/2 a un bunch nuevo |
| `notifyBlockSplit(old, u, r)` | A llamar tras `Pi_s.splitOffR`. Hace dos cosas (ver abajo) |
| `popUnstable()` | Saca BBS de `globalUnstable` y la marca estable |
| `markUnstable(bbs)` | La agrega a `globalUnstable` con `posInGlobalUnstable` |

**`notifyBlockSplit(oldBlock, uBlock, rBlock)` — dos jobs en uno:**

1. **Source-side.** Para cada BBS con `sourceBlock == oldBlock`, redistribuye
   sus transiciones en dos BBS nuevas (una para uBlock, otra para rBlock)
   según `Pi_s.blockOf(t.source)`. Las nuevas BBS nacen inestables; la vieja
   queda muerta.
2. **Target-side.** Las BBS de cualquier bunch con transiciones que
   *apuntaban* a estados ahora en rBlock se marcan inestables (reemplazo de
   `enqueueAffectedBunches` de v3-B). Iterar solamente rBlock alcanza para
   detectar bunches refinables.

### `BranchingEquivalence` — el algoritmo

Coordina las dos particiones anteriores. Las piezas grandes:

- `getPartitions(...)` — el loop principal con las dos fases.
- `split(...)` — la subrutina dual-BFS con abort-on-half (corazón de §5.1).
- `refineSplitters(...)` — redirige splitters pendientes cuando se parte un
  bloque al que apuntaban.
- `findBottomStates(...)` — marca como bottom las SCCs sin τ-out interno;
  muta Πs para dejarlos en el prefijo del bloque.
- `findNewNonInertTransitions(...)` — descubre τ que cruzan src→tgt (recién
  dejaron de ser inertes) durante la cascada de Phase 1.
- `partitionIntoSCCWithTauLabels(...)` — Tarjan iterativo (Phase D).
- `computeBvis(...)` — partición inicial Bvis vs Binvis vía SCCs.

## El loop principal — `getPartitions`

```
1. Calcular SCCs τ-conexas con Tarjan iterativo (Phase D).
2. Calcular Bvis (estados que pueden alcanzar una acción visible).
3. Sembrar Πs con [errorBlock?, Bvis, Binvis].
4. Sembrar Πt con todas las transiciones no-inertes en un único bunch.
5. Crear un splitter primario por cada BBS del bunch inicial.
6. while splitterList no vacío  ó  Lt.hasUnstable():
       FASE 1: drenar splitterList
       FASE 2: popear UNA BBS inestable y peelear su bunch
```

### Fase 1 — estabilizar bloques

Cada iteración:

1. Pop primer splitter `sp = (block B, transitions T, marks M, isPrimary, groupId)`.
2. Si `B` no está vivo, descartar.
3. Llamar `(R, U) = split(B, T, ...)`. Si `R == null` o `U == null`, no hubo
   partición real; descartar.
4. Si `sp.isPrimary`, buscar el secundario en mismo grupo y mismo bloque B y
   re-targetearlo a R (con sources filtrados). Esto sólo lo redirige a R; el
   lado U del secundario se descarta porque ya se vio que el primario
   asignó esos estados a R (heredado de v3-B, decisión de diseño).
5. `refineSplitters(B, R, U, splitterList)` redirige el resto de splitters
   con `block == B` a R/U según corresponda.
6. **Cascada τ no-inerte.** Encolar dos frontiers `(R, U)` y `(U, R)`.
   Para cada frontier `(src, tgt)`:
   - Buscar τ-edges que cruzan `src → tgt` (eran inertes, ahora no).
   - Por cada (action, conjunto de transiciones cruzadas):
     - Crear bunch nuevo con esas transiciones (`Lt.newBunch`).
     - Partir `src` con esas transiciones como splitter (`split(src, ...)`).
     - Si parte en `(N, src')`, marcar bottoms de `N`, generar splitters
       secundarios para los demás bunches que tengan source en `N`, y
       agregar nuevos frontiers `(N, src')`, `(src', N)`, `(N, tgt)`,
       `(src', tgt)`.

### Fase 2 — refinar bunches

Cada iteración (sólo una por loop iter):

1. `bbs = Lt.popUnstable()`. Si murió o su bunch murió, continuar.
2. `newBunch = Lt.peelSlice(bbs.bunch)`. Si no se pudo refinar (bunch
   trivial: una sola `(action, target_block)`), continuar.
3. Para cada bloque fuente con transiciones en `newBunch`:
   - Crear splitter primario con las transiciones de `newBunch` (la "smaller
     half" peeled).
   - Crear splitter secundario con las transiciones que quedaron en el bunch
     original (con marks por bottom-source que también es source del primario).

## La rutina `split` — dual BFS con abort-on-half

Esta es **la** optimización que da O(m log n). Reemplaza la BFS plana de v2/v3-B.

**Idea.** Dos BFS hacia atrás corren en lockstep sobre el sub-DAG de SCCs
τ-conexas dentro de B:

- **forward (lado R).** Sembrado con las SCCs de los splitter sources.
  Expande por τ-pred-SCCs dentro de B. Equivalente a la BFS hacia atrás de
  v3-B, pero a nivel de SCC.
- **reverse (lado U).** Sembrado con las SCCs *sumidero* del sub-DAG (las
  que no tienen τ-outs dentro de B y que NO están en el seed de R).
  Mantiene un contador `untestedSCC[σ] = |τ-out-SCCs(σ) ∩ B|`. Cuando se
  clasifica una σ' como U, decrementa el contador de los predecesores de σ';
  si el contador llega a 0 y σ' no es R, σ' es U.

**Por qué SCCs y no estados.** Las SCCs τ-conexas (precomputadas con
Tarjan) actúan como super-estados: clasificar una σ implica clasificar
todos sus estados. Es la regla que preserva la invariante de branching
bisimilarity sin trabajo adicional.

**Abort-on-half.** Cuando el tamaño en estados de un lado supera \|B\|/2,
ese lado es la "larger half" y se aborta. El otro continúa hasta vaciar
su queue (cota ≤ \|B\|/2) y los estados sin clasificar quedan asignados
al lado abortado. Esto es lo que cierra la complejidad: la BFS plana de
v3-B era O(\|B\|) por iteración; con abort, el lado más chico paga el costo.

**Cuatro casos de salida del lockstep:**

| Salida | Qué quedó | Acción |
|--------|-----------|--------|
| `rAbort` | R era más grande | drenar reverse, aplicar `R := B \ U` (`applyComplement`) |
| `uAbort` | U era más grande | drenar forward, aplicar `R := rSCCs` (`applyExplicit`) |
| `rQueue` vacía sin abortar | R drenó natural; rSCCs es completo | `applyExplicit` |
| `uQueue` vacía sin abortar | U drenó natural; uSCCs es completo | `applyComplement` |

**`applyExplicit`** y **`applyComplement`** mutan `Pi_s` con `addToR` para
cada estado correspondiente. Por el bug de iteración-vs-swap descrito en
`RefinablePartition`, ambas toman **snapshot a `List<Long>`** antes de
mutar.

**Casos borde:**

- `rSCCs` vacío (ningún splitter source en B): devolver `(null, B)` sin
  tocar la partición.
- `R` cubre B (todos los estados pasan a R): `splitOffR` devuelve
  `(B, null)` sin matar B, sólo limpia `rDestEnd`.
- `U` cubre B (nadie pasa a R): `splitOffR` devuelve `(null, B)` sin tocar.

## Coroutinas en un lenguaje sin coroutinas

El paper §5.1 describe el split como dos coroutinas (forward worker y reverse
worker) que ejecutan en lockstep: una hace un paso, después la otra, y la
primera que cruza \|B\|/2 se aborta. Java no tiene coroutinas nativas — ni
`yield`, ni mecanismo de canales tipo Go, ni async/await — así que la
construcción del paper se traduce con la transformación canónica:

> **coroutina ⟶ función pura por paso atómico + estado externalizado +
> driver explícito.**

Es exactamente la misma transformación que aplican los compiladores cuando
generan código nativo a partir de coroutinas: el "stack frame" lógico de la
coroutina se aplana en variables que viven afuera, y los `yield` se vuelven
`return`.

### Cómo se ve concretamente

Cada coroutina del paper es un método estático en `BranchingEquivalence`:

```java
rStep(rQueue, rSCCs, inSCCs, sccSizeInB, rSize) → newRSize
uStep(uQueue, uSCCs, rSCCs, untestedSCC, inSCCs, sccSizeInB, uSize) → newUSize
```

Cada llamada hace **un paso de BFS**: poll-ea un elemento de su queue,
expande sus predecesores, actualiza los conjuntos compartidos (`rSCCs` /
`uSCCs` / `untestedSCC`), y devuelve el tamaño actualizado. Eso corresponde
a lo que en una coroutina "real" sería el código entre dos `yield`.

El driver es el while de lockstep adentro de `split`:

```java
while (!rAbort && !uAbort) {
    if (rQueue.isEmpty()) break;
    rSize = rStep(rQueue, rSCCs, inSCCs, sccSizeInB, rSize);
    if (rSize > half) { rAbort = true; break; }

    if (uQueue.isEmpty()) break;
    uSize = uStep(uQueue, uSCCs, rSCCs, untestedSCC, inSCCs, sccSizeInB, uSize);
    if (uSize > half) { uAbort = true; break; }
}
```

Eso es el **scheduler manual**: alterna las dos "tareas", chequea las
condiciones de aborto entre cada paso, y termina cuando una de las dos
abort-ea o cuando una queue se vacía. No hay `yield`, no hay `await`; cada
step es una llamada a método.

### Qué se externaliza

Lo que en una coroutina real serían variables locales (la cola, los
conjuntos vistos, los contadores) viven como argumentos del step y como
variables del frame de `split`:

| Variable local de la coroutina (paper) | En v3 |
|---|---|
| Queue de la BFS | `rQueue`, `uQueue` (`Deque<Set<Long>>`) |
| Conjunto de SCCs ya vistas | `rSCCs`, `uSCCs` (sets por identidad) |
| Contador `untested[t]` por transición | `untestedSCC: IdentityHashMap<Set<Long>, Integer>` (a nivel de SCC) |
| Tamaño actual del lado | `rSize`, `uSize` (int devueltos por cada step) |
| Flag de aborto | `rAbort`, `uAbort` (bool) |

Cada step es **stateless** (función pura sobre sus argumentos): lee el
estado, hace UN poll + las adiciones correspondientes, devuelve el tamaño
nuevo. Por eso el driver puede alternar sin perder progreso: el "stack
frame" de la coroutina no existe como objeto propio, vive desparramado
entre las variables del driver.

### Por qué un step y no la BFS entera por lado

La tentación obvia es escribir dos métodos `runForwardBFS` y
`runReverseBFS` que hagan la BFS completa cada uno, y llamar a uno o al
otro. **No funciona** porque entonces no se puede hacer abort-on-half:
para cuando vuelve uno, ya consumió todo su trabajo. Y abort-on-half es
exactamente la propiedad que da O(m log n).

Entonces el paso atómico tiene que ser chico — un poll + expansión de un
solo elemento de la queue — y el driver chequea el invariante
(`size > half`) **entre pasos**. Eso simula el `yield` de las coroutinas:
cada step es lo que pasaría entre dos yields consecutivos.

### Otras opciones (descartadas)

| Approach | Por qué no |
|---|---|
| Threads + lock + signaling | Funciona, pero el overhead de context switch + locks por step lo hace más lento que el approach manual y agrega bugs de concurrencia que no necesitamos (el algoritmo es secuencial por construcción). |
| Java 21 virtual threads (Loom) | Más liviano que threads OS pero sigue siendo más overhead que llamar a una función. Suma una dependencia de Java 21+ a un proyecto que está en una versión más vieja. |
| State-machine explícita (case/switch sobre `enum State`) | Útil cuando la coroutina tiene varios `yield` en lugares distintos del cuerpo. Acá hay un único punto de yield (al final de cada paso de BFS), así que no hace falta la indirección. |
| Generator pattern con `Iterator<State>` | Es básicamente lo que tenemos pero con boxing extra y una capa más de indirección por step. |

### Lo que se preserva (y lo que no)

**Asintótico.** El bound del split (`O(m log n)` = `O(\|T\| log \|States\|)`)
se mantiene: el lockstep + abort-on-half hacen que el costo de cada split
quede acotado por el lado más chico, y el smaller-half argument sobre
estados da el factor `log \|States\|`. Esa es la parte que el paper exige y
v3 cumple.

**Constantes.** Cada step tiene overhead de llamada a método (vs. acceso
local en una coroutina compilada) y los argumentos se pasan por valor en
cada paso. En la práctica el compilador JIT inline-a `rStep` y
`uStep` después de unas pocas iteraciones, así que el overhead es
mínimo.

**Sintaxis.** Lo único que se "pierde" es la legibilidad de la versión
con coroutinas explícitas. Es el costo natural de implementar una
abstracción de control flow en un lenguaje que no la tiene.

## Splitters: primary y secondary

Un `Splitter` agrupa transiciones que sirven como "test" para partir un
bloque. Campos:

| Campo | Significado |
|-------|-------------|
| `block` | Bloque a partir |
| `transitions` | Las transiciones del splitter (los sources son el seed de R) |
| `marks` | Subset usado para marcar transiciones críticas (bottoms-related) |
| `isPrimary` | true para el slice peeled (la "smaller half"), false para el resto |
| `groupId` | Comparte con su par; usado para el hook `if (currentSplitter.isPrimary)` |

**Distinción primary/secondary.** Cuando se peelea un bunch en `(newBunch,
oldBunch_remainder)`, se generan dos splitters por bloque fuente:

- **Primary** con transiciones de `newBunch`: estas transiciones **acaban
  de pasar** a un bunch separado, así que potencialmente parten el bloque.
- **Secondary** con transiciones del remanente: pueden refinar más usando
  bottoms como marks (sólo los bottoms del split discriminan, no todos los
  estados).

## La interacción entre las tres estructuras

```
                ┌─────────────────────────┐
                │   BranchingEquivalence  │
                │     (getPartitions)     │
                └────────┬─────────┬──────┘
                         │         │
              splitOffR  │         │  notifyBlockSplit
                         ▼         ▼
              ┌────────────────┐ ┌────────────────────────┐
              │ Refinable-     │ │ LinkedTransition-      │
              │ Partition (Πs) │ │ Partitions (Πt)        │
              │                │ │                        │
              │  blockOf(s) ◄──┼─┤ usa Pi_s en peelSlice  │
              │                │ │ y notifyBlockSplit     │
              └────────────────┘ └────────────────────────┘
```

**Direccionalidad.** Πt depende de Πs (los bunches se subagrupan por
`Pi_s.blockOf(t.target)`), no al revés. `RefinablePartition` no conoce a
`LinkedTransitionPartitions`. El acoplamiento se hace explícito con
`notifyBlockSplit`, llamado por `BranchingEquivalence` justo después de
`Pi_s.splitOffR` — no se usa listener pattern para no esconder el flujo.

**Coreografía típica al partir un bloque:**

```
1. BranchingEquivalence.split(B, ...) decide R vs U dentro de B.
2. Pi_s.addToR(s) por cada s ∈ R-side.  // marca con rDestEnd-- y swaps
3. Pi_s.splitOffR(B) → (rBlock, uBlock).  // mata B, crea bloques nuevos
4. Lt.notifyBlockSplit(B, uBlock, rBlock):
     a) Source-side: BBS con sourceBlock=B se reparten en BBS para rBlock y uBlock.
     b) Target-side: BBS con transiciones a rBlock se marcan inestables.
5. refineSplitters(B, R, U, splitterList) re-targetea splitters pendientes.
6. Cascada τ no-inerte sobre frontiers (R, U) y (U, R).
```

## Lo que NO hace v3 (y por qué)

- **Peel O(1) puro.** El paper §5.1 mantiene los bunches como slices
  contiguos del array maestro y permite peel O(1) cuando la slice está
  al borde. v3 usa `Set<Transition>` por bunch y `peelSlice` re-subagrupa
  por `(action, target_block)` en cada llamada — costo O(\|bunch\|) por peel
  en lugar de O(1). En el caso típico (smaller-halves balanceadas, ≈ \|bunch\|/2),
  la suma telescópica da O(\|T\| log \|States\|) — la misma clase asintótica
  que el paper, sólo que con una constante peor. En el caso patológico
  (HashMap nos da siempre la sub-slice más chica), el bound se degrada hasta
  O(\|T\| × \|Σ\| × \|States\|) por los peels, aunque sigue dominado en la
  práctica por el split. Documentado en
  `LinkedTransitionPartitions design notes.md`.
- **Per-transition `untested` counter.** El campo `Transition.untested`
  está disponible pero sin uso. La rutina `split` mantiene el contador a
  nivel de SCC en un `IdentityHashMap<Set<Long>, Integer>` local. Una
  versión per-transición (con array paralelo denso) ahorraría boxing y
  un lookup; queda como optimización de constante.
- **Densificación de IDs de transiciones.** Los estados están densificados
  (Pi_s usa `denseIdOf`); las transiciones no, porque no se indexan por
  posición en ningún array maestro.

## Referencias rápidas

- Plan original: `docs/superpowers/plans/2026-05-06-branching-equivalence-v3-jansen-optimizations.md`.
- Diff entre versiones: `versiones/comparacion-versiones.md`.
- Diseño de Πs: `versiones/RefinablePartition design notes.md`.
- Diseño de Πt: `versiones/LinkedTransitionPartitions design notes.md`.
- Paper original: `Papers/jansen.pdf` (Jansen, Groote, Keiren, Wijs 2019).
