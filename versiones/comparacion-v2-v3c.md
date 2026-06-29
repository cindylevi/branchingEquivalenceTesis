# Comparación `v2` → `v3c` de `BranchingEquivalence`

Dos iteraciones del mismo algoritmo (Jansen, Groote, Keiren, Wijs, 2019) para
minimizar un MTS módulo *branching bisimilarity*. **v2** es la versión que
quedó funcionando dentro del flujo de síntesis composicional de MTSA tras la
reorganización descrita en [`comparacion-v0-v2.md`](comparacion-v0-v2.md);
**v3c** es la versión final, donde el algoritmo deja de ser sólo *correcto* y
pasa a ser *eficiente*: adopta las estructuras de datos refinables y el `split`
con *abort-on-half* del paper (§5.1–5.2) y con eso **alcanza efectivamente la
cota O(m log n)** que v2 prometía pero no realizaba.

> Nota metodológica: entre v2 y v3c hay tres versiones intermedias que el
> repositorio conserva (`version 3-A1 y D`, `version 3-B`, `version 3-C`).
> Corresponden a tres fases de refactor incremental que se aplicaron una
> arriba de la otra. Este documento las menciona sólo como referencia del
> camino recorrido; igual que [`comparacion-v0-v2.md`](comparacion-v0-v2.md)
> ignoró v1, acá el foco está en los dos extremos: el punto de partida (v2) y
> el punto de llegada (v3c). Cuando este documento dice "v3c" se refiere a
> `BranchingEquivalenceV3C.java` (idéntico a `version 3-C` salvo el nombre de
> la clase, renombrado para que las versiones coexistan en una misma campaña de
> benchmarking).

---

## 1. Idea general del algoritmo (común a las dos versiones)

El algoritmo no cambia de v2 a v3c: **la partición que computan es la misma**,
y la salida `buildMinimisedMTS` es bit-a-bit equivalente. Lo que cambia es
*cómo* se representa el trabajo intermedio y *cuánto cuesta* cada operación.

Recordando lo esencial (idéntico a la sección 1 de `comparacion-v0-v2.md`): se
refinan **simultáneamente** dos particiones hasta un punto fijo:

- una **partición de estados** `Pi_s`, que arranca con `Bvis`/`Binvis` (más el
  bloque de error `-1L` si existe);
- una **partición de transiciones** `Pi_t` en *bunches*, que arranca con un
  único bunch: todas las transiciones no-inertes.

El bucle alterna dos fases: **Fase 1** estabiliza estados (drena la
`splitterList` por completo) y **Fase 2** refina bunches (parte un bunch en dos
y agenda splitters nuevos). Las τ-inertes (intra-bloque) se ignoran apoyándose
en las **SCCs τ-conexas** precomputadas (`stateToSCCMap`); una SCC τ es atómica
y se mueve entera en cada `split`.

### El punto de quiebre: complejidad

El paper de referencia, **Jansen, Groote, Keiren, Wijs (2019), "A simpler
O(m log n) algorithm for branching bisimilarity on labelled transition
systems"** (`Papers/jansen.pdf`), no es sólo un algoritmo: es un algoritmo
*con una cota de complejidad demostrada*, y esa cota **depende de las
estructuras de datos** que el paper describe en su §5. El Algorithm 1 (que v2
implementa fielmente) describe *qué* hacer; la §5 describe *con qué estructuras*
hacerlo para que sea O(m log n).

**v2 implementa el Algorithm 1 con estructuras ingenuas.** Es correcto, pero
varias de sus operaciones son super-lineales:

- `Pi_s.contains(B)` sobre un `List<Set<Long>>` es O(|Pi_s|).
- `split` recorre `B` entero con una BFS hacia atrás **plana** (O(|B|) por
  invocación, sin importar qué tan chico sea el lado que efectivamente se
  separa) — no aplica el truco de "procesar la mitad más chica".
- Indexar slices por `(acción, bloque)` requería hashear el bloque; v2 ya lo
  había mitigado con `blockIdMap` (ver `comparacion-v0-v2.md` §2.4), pero el
  hash estructural de los `Set<Triple>` reaparece en otros lados.
- partir un bloque copia dos `HashSet` nuevos de tamaño O(|B|).

v3c reemplaza esas estructuras por las **dos particiones refinables encadenadas**
del paper (`RefinablePartition` para `Pi_s`, `LinkedTransitionPartitions` para
`Pi_t`) y reescribe `split` como la **coroutine dual con abort-on-half** de §5.1.
Recién ahí el costo de cada `split` se carga a la mitad más chica del bloque, y
el total amortizado cierra en O(m log n).

> En una frase: `comparacion-v0-v2.md` fue "el mismo algoritmo, reorganizado y
> corregido, **a igual complejidad**". Este documento es lo contrario: "el mismo
> algoritmo y la misma salida, pero **bajando la complejidad** de v2 a la cota
> del paper". Casi todo lo nuevo en v3c es estructura de datos, no lógica.

### El camino intermedio (contexto, no foco)

El salto v2 → v3c se hizo en tres fases incrementales, cada una validada contra
los mismos tests antes de pasar a la siguiente. No son el foco de este
documento, pero ubican de dónde sale cada cambio:

| Versión | Fase | Qué introdujo |
|---|---|---|
| `v3-A1 y D` | A + D | Optimizaciones §5.2 (saltar el bunch recién aplicado) + Tarjan iterativo de una pasada para las SCCs (reemplaza el Kosaraju de dos pasadas de v2) |
| `v3-B` | B | `RefinablePartition` para `Pi_s` (array maestro + slices contiguos) |
| `v3-C` (= v3c) | C + C.5 | `LinkedTransitionPartitions` para `Pi_t` + `split` con coroutine dual *abort-on-half* |

De acá en más se compara directamente v2 contra v3c, sin volver sobre las
intermedias.

---

## 2. Por qué v3c: las motivaciones

A diferencia de v0 → v2 (donde las motivaciones eran corrección, claridad y
adaptación al dominio), las motivaciones de v2 → v3c son **casi todas de
performance**, y todas apuntan al mismo objetivo: hacer que la implementación
realice la cota O(m log n) del paper.

### 2.1. La cota O(m log n) requiere las estructuras de §5.1

El paper demuestra O(m log n) suponiendo cuatro estructuras encadenadas:

1. una **refinable partition** de estados con bloques como slices contiguos de
   un array maestro, lookup estado→bloque en O(1), y split que mueve sólo los
   estados del lado que se separa;
2. una **linked refinable partition** de transiciones, donde cada transición es
   un objeto con identidad que participa de varias "vistas" simultáneas (por
   bunch, por block-bunch-slice, por estado fuente, por estado destino);
3. el `split` que **explora las dos mitades en paralelo y aborta la más grande**
   apenas cruza |B|/2, cargando el costo a la mitad chica;
4. el *peel* de un bunch que separa siempre la sub-slice ≤ |T|/2.

v2 no tiene ninguna de las cuatro. v3c tiene las cuatro (con un par de
concesiones de factor constante, ver §2.4). Ese es el corazón del cambio.

### 2.2. Eliminar las operaciones super-lineales de `Pi_s`

`Pi_s` como `List<Set<Long>>` + `stateToBlockMap` + `blockIdMap` arrastra costos
que `RefinablePartition` elimina de raíz:

| Operación | v2 | v3c |
|---|---|---|
| `Pi_s.contains(B)` | O(|Pi_s|) recorre la lista | `B.isAlive()`, O(1) (flag) |
| estado → bloque | `stateToBlockMap.get(s)`, O(1) hash | `Pi_s.blockOf(s)`, O(1) array |
| identidad de bloque | `block.equals(other)`, O(|block|) | `==` por referencia |
| partir `B` en `R`/`U` | dos `HashSet` nuevos O(|B|) | reordenar swaps + dos `Block` sobre el mismo array, sin copia |
| iterar los bottom states | recorre todo `B` y filtra | itera el prefijo `[start..bottomEnd)` directo |
| id de bloque (para hashear slices) | `blockIdMap` (un `IdentityHashMap` aparte) | `Block.id`, campo del bloque |

El segundo punto importa especialmente: en v2 varios `block.equals(other)`
recorren los conjuntos y son correctos sólo porque las referencias casualmente
coinciden. La identidad por referencia de `RefinablePartition` elimina esa clase
entera de fragilidades.

### 2.3. Eliminar el rehashing y el re-encolado manual de `Pi_t`

`Pi_t` como `Set<Set<Triple>>` (con `IdentityHashMap`) + `IdentityQueue` +
`targetStateToBunches` tiene dos problemas que `LinkedTransitionPartitions`
resuelve:

- **Las transiciones son `Triple` inmutables con `equals`/`hashCode`
  estructurales.** Cada vez que se arma un set temporal de transiciones (en cada
  split, en cada refinamiento de splitter) se paga el hash estructural. v3c hace
  de cada transición un **objeto con identidad** (`Transition`); comparar y
  agrupar transiciones pasa a ser por referencia.
- **Re-encolar los bunches afectados por un split era manual.** v2 mantiene el
  índice inverso `targetStateToBunches` (estado destino → bunches) y, tras cada
  split, recorre los estados de `R` para re-encolar (`enqueueAffectedBunches`).
  En v3c esa lógica desaparece: cuando `Pi_s` parte un bloque, una sola llamada
  a `Lt.notifyBlockSplit` redistribuye las *block-bunch-slices* del lado fuente
  y **marca automáticamente como inestables** las del lado destino afectadas.

Además, la unidad de trabajo de la Fase 2 cambia (ver §2.5).

### 2.4. Las concesiones de factor constante (honestidad sobre la cota)

v3c no es una transcripción *paper-pure*. La implementación toma dos atajos que
mantienen la cota **amortizada** pero pierden factor constante respecto del
óptimo absoluto, y conviene dejarlos explícitos:

- **El *peel* es O(|bunch|), no O(1).** `LinkedTransitionPartitions` guarda cada
  bunch como `Set<Transition>` por identidad en vez de como slice contiguo de un
  array de transiciones. Eso evita densificar IDs de transiciones y reordenar un
  array al partir bunches (≈150 líneas de código sutil menos), a costa de que el
  peel itere la slice. Como siempre se elige la sub-slice ≤ |bunch|/2 ("smaller
  half"), el total amortizado sigue siendo O(|T| log |T|): se pierde la
  constante, no el orden.
- **`splitOffR` reasigna `blockOfElement[]` para todo `B`, O(|B|).** El array
  maestro permite separar el sufijo R sin copiar estados, pero reetiquetar a qué
  `Block` apunta cada posición es una pasada lineal sobre el bloque que se parte
  (no sólo sobre la mitad chica).

Lo que sí se elimina por completo son los factores **super-lineales** de v2
(`contains` O(|Pi_s|), hash estructural O(n) de `Set<Long>`, BFS de split plana
O(|B|) sin abort). El `split` dual con abort-on-half es el cambio que más mueve
la aguja: su comentario en el código lo dice explícitamente —"es la optimización
que cierra la complejidad O(m log n) del paper (la BFS plana de v3-B era O(|B|)
por iteración)"—.

### 2.5. Beneficios secundarios

- **La unidad de trabajo de la Fase 2 se vuelve más fina.** v2 saca de la cola
  un *bunch entero* y lo reprocesa. v3c saca una *block-bunch-slice* (un par
  bunch × bloque-fuente): sólo se reexamina lo que cambió para ese par, no todo
  el bunch. Es exactamente lo que el "log" de la cota necesita.
- **`notifyBlockSplit` centraliza el acople `Pi_s`↔`Pi_t`.** Hay un único lugar
  donde se parte un bloque (`split`), así que hay un único lugar donde notificar.
  El re-encolado deja de estar disperso.
- **La API externa no cambia.** v3c expone `Pi_s.asLegacyView()` y
  `Lt.asLegacyView()` que reconstruyen el `List<Set<Long>>` / `List<Set<Triple>>`
  que esperan `buildMinimisedMTS` y el resto del DCS. El refactor es interno.

---

## 3. Diferencias concretas v2 → v3c

### 3.1. Resumen

| | v2 | v3c |
|---|---|---|
| `Pi_s` | `List<Set<Long>>` + `stateToBlockMap` + `blockIdMap` | `RefinablePartition` (array maestro, slices contiguos, `Block` con `id`/`alive`) |
| `Pi_t` | `Set<Set<Triple>>` (`IdentityHashMap`) | `LinkedTransitionPartitions.liveBunches()` |
| Cola de refinamiento | `IdentityQueue<Set<Triple>>` (bunch entero) | `globalUnstable` de `BlockBunchSlice` + flag `stable` por slice (swap-remove O(1)) |
| Índice destino→trabajo | `targetStateToBunches: Map<Long, Set<Set<Triple>>>` | vista estática `byTarget` + `notifyBlockSplit` (marca BBS afectadas) |
| Transición | `Triple<Long,String,Long>` inmutable (hash estructural) | `Transition`, objeto con identidad + 4 vistas |
| Bunch | `Set<Triple>` | `BunchSlice` (`id`, `alive`) |
| (bunch × bloque-fuente) | implícito (recomputa slices al vuelo) | `BlockBunchSlice` explícito (`id`, `bunch`, `sourceBlock`, `stable`) |
| `Splitter.block` | `Set<Long>` | `RefinablePartition.Block` |
| `Splitter.transitions`/`marks` | `Set<Triple>` | `Set<Transition>` (identidad) |
| `split` | BFS hacia atrás **plana** por SCC, O(|B|), sin abort | **coroutine dual** (forward R / reverse U con `untestedSCC`) + **abort-on-half** |
| Unidad de Fase 2 | popea bunch entero, reconstruye slices con `Map<Pair<String,Integer>,Set>` | popea `BlockBunchSlice`, refina con `Lt.peelSlice` |
| Re-encolar afectados | `enqueueAffectedBunches` (itera `R`, `targetStateToBunches`) | desaparece: lo hace `notifyBlockSplit` |
| §5.2 opt "saltar bunch recién aplicado" | no (re-itera todo `Pi_t`) | sí (`if (b == newBunchSlice) continue`) |
| SCCs τ | Kosaraju 2 pasadas (DFS forward + grafo invertido + DFS backward) | Tarjan iterativo de 1 pasada |
| `findBottomStates` | devuelve `Set<Long>` nuevo | **muta** `Pi_s` (marca bottoms en `[start..bottomEnd)`) |
| Salida de `getPartitions` | `Pi_s`/`Pi_t` directos | `Pi_s.asLegacyView()` / `Lt.asLegacyView()` |
| Complejidad efectiva | super-lineal (no realiza la cota) | O(m log n) amortizado (con concesiones de constante, §2.4) |

### 3.2. Estructuras de datos

| Decisión | Paper §5.1 | v2 | v3c |
|---|---|---|---|
| Partición de estados | refinable partition (array + slices) | `List<Set<Long>>` + mapas auxiliares | `RefinablePartition` |
| Partición de transiciones | linked refinable partition | `Set<Set<Triple>>` | `LinkedTransitionPartitions` |
| Transición | objeto con identidad y vistas | `Triple` inmutable | `Transition` con identidad |
| Lookup estado→bloque | array `blockOf` O(1) | `HashMap` O(1) | array O(1) |
| "¿`s` está en `R`?" durante split | posición en el array, O(1) | `R.contains(s)` con `HashSet` temporal | posición en el array, O(1) |
| Cola de trabajo | lista de inestables con remoción O(1) | `IdentityQueue` (bunch entero) | `globalUnstable` (BBS) con swap-remove O(1) |
| Bottoms de un bloque | prefijo del slice | recorre el bloque y filtra | prefijo `[start..bottomEnd)` |

### 3.3. El `split`: de BFS plana a coroutine dual con abort-on-half

Es **el cambio algorítmico más importante** del salto v2 → v3c, y el único que
toca la cota de complejidad de manera esencial.

**v2** (`split`, líneas 587–633): siembra `R` con las SCCs enteras de los
orígenes marcados y propaga `R` hacia atrás por τ-inertes con un worklist, hasta
saturar. `U = B \ R`. El recorrido es **plano**: visita tantos estados como haya
que arrastrar a `R`, pero la operación entera está acotada sólo por O(|B|) —si la
mitad que se separa es grande, se paga grande.

**v3c** (`split`, líneas 619–744): corre **dos BFS en lockstep** sobre el
sub-DAG de SCCs τ-conexas dentro de `B`:

- **forward (lado R):** sembrada con las SCCs de los splitter sources, expande
  hacia atrás por τ-pred-SCCs (es la BFS de v2, pero a nivel de SCC).
- **reverse (lado U):** sembrada con las SCCs *sumidero* del sub-DAG (las que no
  tienen τ-outs dentro de `B` y no están en `R`). Mantiene un contador
  `untestedSCC[σ] = |τ-out-SCCs de σ dentro de B aún sin clasificar como U|`;
  cuando una σ' pasa a `U`, decrementa el contador de sus predecesores, y al
  llegar a 0 (y no estar en `R`) ese predecesor también es `U`.

Las dos avanzan un paso cada una por iteración (`forwardStep` / `reverseStep`).
**El primero que supera |B|/2 estados es la "mitad grande" y se aborta**; el otro
se drena hasta vaciar su queue (cota ≤ |B|/2) y los estados sin clasificar se
asignan al lado abortado. Según cómo se sale del lockstep hay cuatro casos
(`applyExplicit` si `R` es el lado chico, `applyComplement` si lo es `U`).
Finalmente `Pi_s.splitOffR(B)` materializa el corte y `Lt.notifyBlockSplit`
propaga el efecto a `Pi_t`.

La invariante de branching bisimilarity se preserva sin trabajo extra porque las
SCCs actúan como **super-estados**: clasificar una SCC clasifica todos sus
estados, igual que en v2.

### 3.4. La Fase 2: de "bunch entero" a "block-bunch-slice + peel"

**v2** (líneas 413–502): saca un bunch de `Pi_t_cola`, reconstruye sus slices
con un `Map<Pair<String,Integer>, Set<Triple>>` agrupando por
`(acción, blockId destino)`, elige la slice ≤ |bunch|/2 y reparte el bunch en
`chosenTransitions` + `newBunch`. Después actualiza a mano `Pi_t`, `Pi_t_cola` y
`targetStateToBunches`, y genera los splitters primario/secundario por bloque.

**v3c** (líneas 434–496): saca una `BlockBunchSlice` de `Lt.globalUnstable`,
toma su `bunch` y delega el corte a `Lt.peelSlice(bunch)`, que hace exactamente
el mismo trabajo de subagrupar por `(acción, target_block.id)` y elegir la
smaller-half, pero **encapsulado en la estructura** (mantiene las vistas
sincronizadas y marca las BBS afectadas como inestables solo). Si `peelSlice`
devuelve `null`, el bunch era trivial. Después genera los splitters
primario/secundario igual que v2, pero sobre `Set<Transition>`.

La diferencia de fondo es la granularidad: v2 reexamina el bunch completo; v3c
reexamina sólo el par (bunch × bloque-fuente) que se volvió inestable.

### 3.5. Optimización §5.2: saltar el bunch recién aplicado

Cuando la cascada de τ no-inertes parte `currentSrc` en `N` y `src'` creando un
bunch nuevo, ese bunch nuevo es justamente lo que acaba de separar `N`, así que
**`N` ya es estable respecto de él** y no hace falta agendar un splitter
secundario para esa combinación.

- **v2** (líneas 386–402): no tiene esta guarda. La cascada itera `for (b :
  Pi_t)` sobre *todos* los bunches, incluido el recién creado `validTNew`, y
  agenda un splitter que no aporta (`N` no se va a partir más por ahí).
- **v3c** (líneas 392–395): `for (BunchSlice b : Lt.liveBunches())` con
  `if (b == newBunchSlice) continue;` —comparación por identidad de referencia—.

Esta optimización entró en la fase A (v3-A1) y se mantiene en v3c. No cambia el
orden de complejidad pero recorta trabajo redundante medible.

### 3.6. SCCs τ-conexas: de Kosaraju a Tarjan iterativo

- **v2** (`partitionIntoSCCWithTauLabels`, líneas 771–799): Kosaraju de **dos
  pasadas** —`forwardDFSWithTauLabels` para el orden de finalización,
  `buildReversedGraph` para invertir el subgrafo τ, y `backwardsDFSWithTauLabels`
  sobre el invertido—. Tres recorridos y una copia del grafo.
- **v3c** (líneas 999–1095): **Tarjan iterativo de una sola pasada** con pila
  explícita de frames (`index`/`lowlink`/`onStack`), sin construir el grafo
  invertido. Densifica los estados a `int` para usar arrays planos.

Ambos son lineales; Tarjan de una pasada ahorra el grafo invertido y una pasada
de DFS, y los arrays planos evitan el boxing. Entró en la fase D (v3-A1).

### 3.7. `findBottomStates`: de función pura a mutación de `Pi_s`

- **v2** (líneas 635–667): construye y devuelve un `Set<Long>` con los estados
  bottom de `N` (las SCCs sin τ-saliente dentro de `N`). Los callers iteran ese
  set.
- **v3c** (líneas 847–884): **muta** `Pi_s` —`resetBottoms(N)` y luego
  `markAsBottom(s)` por cada estado bottom—, dejándolos en el prefijo
  `[start..bottomEnd)` del bloque. Los callers iteran con `Pi_s.bottoms(N)`, que
  recorre sólo ese prefijo en vez de filtrar el bloque entero.

Es coherente con el diseño de `RefinablePartition`: el "ser bottom" deja de ser
un set aparte y pasa a ser un rango del array maestro.

### 3.8. Lo que NO cambia de v2 a v3c

Conviene marcar explícitamente lo que se preservó intacto, porque acota el
alcance del refactor:

- **El preprocesamiento de fluents** (sacar las initiating actions de
  `tauLabels` antes del bucle) es idéntico.
- **El bloque de error `-1L`** se sigue extrayendo a su propio bloque inicial.
- **`computeBvis`** (propagación de visibilidad hacia atrás por el grafo de SCCs
  invertido) es idéntico salvo que consume las SCCs que ahora calcula Tarjan.
- **La construcción del MTS minimizado** (`buildMinimisedMTS`,
  `buildMinimisedMTSFromPartition`, el renombre de self-loops τ a `c_<acción>`)
  es idéntica.
- **La lógica de `refineSplitters`** (separar los splitters pendientes que
  apuntaban al bloque viejo en dos, primarios marcan todo, secundarios marcan
  por bottom) es la misma; sólo cambia el tipo (`Set<Transition>` y `Block`).
- **La estructura de dos fases y la cascada de τ no-inertes vía `newFrontiers`**
  es la misma de v2 (incluida la siembra simétrica defensiva `(R,U)`/`(U,R)`).
- **La firma pública de `getPartitions`** y de `buildMinimisedMTS` no cambia.

---

## 4. Cómo funciona v3c en detalle

Esta sección recorre v3c de arriba a abajo, marcando en cada paso qué hace
distinto respecto de v2.

### 4.1. Punto de entrada y preprocesamiento (igual que v2)

`buildMinimisedMTS(...)` llama a `getPartitions` y construye el MTS resultante a
partir de los bloques. Dentro de `getPartitions`, lo primero —idéntico a v2— es
sacar las **initiating actions de los fluents** de `tauLabels` para que se traten
como visibles, y luego computar SCCs τ y `Bvis`/`Binvis`/`errorBlock`.

### 4.2. Inicialización de las dos particiones refinables

Acá aparece la primera diferencia estructural fuerte:

```java
// Pi_s: refinable partition de estados.
RefinablePartition Pi_s = new RefinablePartition(toMinimise.getStates());
List<Set<Long>> initialBlocks = new ArrayList<>();
if (!errorBlock.isEmpty()) initialBlocks.add(errorBlock);
if (!Bvis.isEmpty())      initialBlocks.add(Bvis);
if (!Binvis.isEmpty())    initialBlocks.add(Binvis);
Pi_s.seedFromInitialBlocks(initialBlocks);

// Lt: refinable partition de transiciones, acoplada a Pi_s.
LinkedTransitionPartitions Lt = new LinkedTransitionPartitions(Pi_s);
```

`seedFromInitialBlocks` reordena el array maestro para que cada bloque inicial
sea un slice contiguo (un `partition` de quicksort generalizado a *k* bloques).
`Lt` recibe `Pi_s` porque la partición de transiciones **depende** de la de
estados (no al revés): es quien reacciona a los splits de bloques.

### 4.3. Partición inicial de transiciones

El bunch inicial son **todas las transiciones no-inertes** (las visibles, más
las τ cuyo origen y destino caen en bloques distintos), igual que v2. La
diferencia es el tipo: cada transición se da de alta como objeto `Transition` vía
`Lt.addTransition(s, a, sPrime)`, y el conjunto se sella con
`Lt.seedSingleBunch(initialTransitions)`, que crea el `BunchSlice` inicial y sus
`BlockBunchSlice` (una por bloque-fuente).

Después se marcan esas BBS como inestables (`Lt.markUnstable`, el espejo del
`Pi_t_cola.add` de v2) y se siembra `splitterList` con un splitter primario por
cada BBS del bunch inicial.

### 4.4. El bucle principal: dos fases (misma forma, distinta maquinaria)

```
while (splitterList no vacía OR Lt.hasUnstable()):
    FASE 1 — estabilizar estados: drenar splitterList por completo
    FASE 2 — refinar bunches: popear UNA BlockBunchSlice inestable y peel su bunch
```

#### Fase 1 — Estabilizar estados

Por cada splitter `(B, transitions, marks)`:

- Si `!B.isAlive()` (lo partió un splitter anterior), se descarta —O(1) por
  flag, en lugar del `Pi_s.contains(B)` O(|Pi_s|) de v2—.
- `split(B, Pi_s, Lt, ...)` → `(R, U)` con la **coroutine dual abort-on-half**
  (§3.3). Si alguno es `null`, no hubo split. `split` ya invocó internamente
  `Pi_s.splitOffR` y `Lt.notifyBlockSplit`.
- Si el splitter era primario, se busca su secundario hermano (mismo `groupId`,
  mismo `B`) y se lo reemplaza por uno restringido a `R` (igual que v2, pero la
  pertenencia se chequea con `Pi_s.blockOf(t.source) == R`).
- `refineSplitters(B, R, U, ...)` arregla los splitters pendientes que apuntaban
  a `B` (misma lógica que v2, sobre `Transition`/`Block`).

Notar que **`enqueueAffectedBunches` ya no se llama**: el re-encolado de bunches
afectados por el split lo hizo `Lt.notifyBlockSplit` dentro de `split`, marcando
target-side las BBS cuyas transiciones apuntaban a estados de `B`.

A continuación, la **cascada de τ no-inertes** vía `newFrontiers`, igual en forma
que v2: se siembran los pares `(R,U)` y `(U,R)` (este último defensivo, da vacío
por la atomicidad por SCC del split), y por cada par se buscan las τ que dejaron
de ser inertes (`findNewNonInertTransitions`), se crea un bunch nuevo
(`Lt.newBunch`, cuyas BBS nacen inestables), se vuelve a partir `currentSrc` en
`N`/`src'`, se realimentan los cuatro pares `(N,src')`,`(src',N)`,`(N,tgt)`,
`(src',tgt)`, y se agendan splitters secundarios por bottom para `N` —saltando
el bunch recién creado (§3.5)—. La guarda final `if (N == null || src_prime ==
null) break;` corta el for cuando no hubo partición real.

#### Fase 2 — Refinar bunches

```java
if (Lt.hasUnstable()) {
    BlockBunchSlice unstable = Lt.popUnstable();
    if (unstable == null || !unstable.isAlive()) continue;
    BunchSlice bunch = unstable.bunch;
    if (!bunch.isAlive()) continue;

    BunchSlice newBunch = Lt.peelSlice(bunch);   // smaller-half por (acción, target_block.id)
    if (newBunch == null) continue;              // bunch trivial

    // splitters primario/secundario por bloque afectado (igual que v2)
    ...
}
```

La unidad que sale de la cola es una **`BlockBunchSlice`**, no un bunch entero:
se refina sólo cuando algo cambió en su par (bunch × bloque-fuente). `peelSlice`
encapsula el subagrupado por `(acción, id del bloque destino)` y la elección de
la mitad chica que en v2 estaba inline.

**Termina** cuando `splitterList` y `Lt.globalUnstable` quedan ambas vacías. Se
devuelve `(Pi_s.asLegacyView(), Lt.asLegacyView())` más el `timingMap`.

### 4.5. Procedimientos auxiliares (qué cambió respecto de v2)

- **`split`** — reescrito por completo: coroutine dual con `forwardStep` /
  `reverseStep`, contador `untestedSCC`, abort-on-half, y los cuatro casos de
  salida con `applyExplicit` / `applyComplement`. Internamente llama a
  `Pi_s.splitOffR` y `Lt.notifyBlockSplit`. (§3.3)

- **`forwardStep` / `reverseStep`** — nuevos: un paso de cada BFS del lockstep.

- **`applyExplicit` / `applyComplement`** — nuevos: materializan qué estados van
  a `R` (explícito: las SCCs en `rSCCs`; complemento: las que no están en
  `uSCCs`). Toman un *snapshot* del bloque antes de mover, porque `addToR`
  reordena el array y desincronizaría un iterador posicional.

- **`refineSplitters`** — misma lógica que v2; tipos `Transition`/`Block`; usa
  `Pi_s.blockOf(t.source) == R` en vez de `R.contains(...)` y `Pi_s.bottoms(...)`
  en vez de `findBottomStates(...)` devolviendo un set.

- **`findBottomStates`** — ahora **muta** `Pi_s` en vez de devolver un set (§3.7).

- **`findNewNonInertTransitions`** — misma idea (τ recién no-inertes agrupadas
  por etiqueta) pero da de alta `Transition` en `Lt` y devuelve `Set<Transition>`.

- **`enqueueAffectedBunches`** — **eliminado**; lo reemplaza `notifyBlockSplit`.

- **`newIdentitySet` / `newIdentityBlockSet` / `newIdentitySCCSet`** — nuevos
  helpers para sets por identidad (evitan el hash estructural O(n) de
  `Set<Long>`/`Set<Triple>`).

- **`partitionIntoSCCWithTauLabels`** — Tarjan iterativo de una pasada (§3.6);
  desaparecen `forwardDFSWithTauLabels`, `backwardsDFSWithTauLabels` y
  `buildReversedGraph`.

- **`computeBvis`** — sin cambios de lógica.

---

## 5. Cronología sugerida para leer el código

1. Leer **v2** entera con `comparacion-v0-v2.md` §4 al lado: es el algoritmo en
   su forma correcta pero con estructuras ingenuas. Es la base de la comparación.
2. Leer las design notes de las dos estructuras nuevas, en este orden:
   [`RefinablePartition design notes.md`](RefinablePartition%20design%20notes.md)
   (estados) y
   [`LinkedTransitionPartitions design notes.md`](LinkedTransitionPartitions%20design%20notes.md)
   (transiciones). Explican el *por qué* de cada decisión y las concesiones de
   factor constante (§2.4).
3. Leer **v3c** con la sección 4 de este documento al lado, prestando atención
   en este orden:
   - (a) la inicialización de las dos particiones refinables (§4.2–4.3);
   - (b) el `split` dual con abort-on-half (§3.3) —el cambio que cierra la cota—;
   - (c) cómo `notifyBlockSplit` reemplaza el re-encolado manual (§4.4);
   - (d) la Fase 2 sobre `BlockBunchSlice` + `peelSlice` (§3.4).
4. Si interesa el camino incremental, leer en orden `version 3-A1 y D` →
   `version 3-B` → `version 3-C`; los headers de cada archivo documentan qué
   fase introdujo cada cambio.
