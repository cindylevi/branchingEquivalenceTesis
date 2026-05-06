# Comparación de versiones — `BranchingEquivalence`

Tres iteraciones del mismo algoritmo (Groote 2019) para minimizar un MTS módulo
*branching bisimilarity*. Las versiones son incrementales: cada una arregla
problemas de la anterior y/o la integra mejor con el resto del sistema.

---

## Idea general del algoritmo (común a las tres versiones)

El algoritmo refina simultáneamente:

- una **partición de estados** `Pi_s` (que arranca con dos bloques: `Bvis` /
  `Binvis`, separando estados que pueden ver acciones visibles de los que no);
- una **partición de transiciones** `Pi_t` en *bunches* (que arranca con un
  único bunch: las transiciones no-inertes).

En cada iteración, encuentra inestabilidades entre `Pi_s` y `Pi_t` (algún bloque
con transiciones que no se comportan uniformemente) y parte bloques o bunches
hasta llegar al punto fijo. Cuando ya nada se puede partir, los bloques son las
clases de equivalencia y se construye el MTS minimizado.

Para soportar transiciones τ-inertes (que viven dentro de un mismo bloque y
deben ignorarse), antes del bucle principal se computan las **SCCs τ-conexas**
y se mantiene `stateToSCCMap` para distinguir τ-inerte (intra-SCC) de τ
no-inerte (inter-SCC).

---

## Relación con el pseudocódigo del paper (Jansen et al. 2019)

El paper de referencia es **Jansen, Groote, Keiren, Wijs (2019), "A simpler
O(m log n) algorithm for branching bisimilarity on labelled transition
systems"** (en `papers/jansen.pdf`). El Algorithm 1 (página 8) es lo que las
tres versiones implementan.

### Pseudocódigo del paper (resumido)

```
1.5: while existe T ∈ Πt no trivial do                  ← bucle externo (sobre bunches)
1.6:    elegir a, B' tal que |T -a→ B'| ≤ |T|/2
1.7:    Πt := (Πt \ {T}) ∪ {T -a→ B', T \ T -a→ B'}
1.8:    for B ∈ splittableBlocks(...) do                ← preparación
1.9:       agregar primary y secondary al splitter list
1.10–11:   marcar transiciones
1.12:   end for
1.13:   for cada T'_B→ en splitter list (in order) do   ← bucle interno (drena splitters)
1.14:      ⟨R, U⟩ := split(B, T'_B→)
1.16:      Πs := (Πs \ {B}) ∪ ({R, U} \ {∅})
1.17–18:   si T' es primary, descartar el secondary de U (es estable)
1.20–28:   si R-τ→U ≠ ∅: crear nuevo bunch, splitear R, agendar splitters de N
1.29:   end for
1.30: end while
```

### v0 = traducción literal del paper

Los comentarios de v0 lo dicen explícitamente: hay líneas como
`// Línea 1.21`, `// Línea 1.25`, `// Línea 1.26`, `// Línea 1.27` que
referencian directamente el numerado del Algorithm 1. v0 mantiene el mismo
anidamiento outer-while + inner-for, con la restabilización de τ no-inertes
(líneas 1.20–1.28) inline.

### ¿"Un solo while" en el paper, dos fases en v1: por qué?

**El paper ya tiene dos bucles anidados** (outer while + inner for), no es
realmente un bucle plano. Lo que v1 hace es **invertir el anidamiento**:

| | Paper / v0 | v1 / v2 |
|---|---|---|
| Estructura | outer (bunches) + inner (splitters) anidados | dos fases que se alternan al mismo nivel |
| Orden | un bunch → drenar TODOS sus splitters → próximo bunch | drenar todos los splitters pendientes → un bunch → drenar splitters nuevos → ... |

**No es una optimización asintótica.** La complejidad O(m log n) del paper no
cambia con la refactorización. La separación en fases mejora cuatro cosas
distintas:

**1. Invariantes verificables.** En la forma del paper, cuando estás dentro del
inner for y aparecen τ no-inertes nuevas (línea 1.21), creás simultáneamente
un bunch nuevo y un splitter nuevo, y seguís dentro del mismo for. Distinguir
"estoy procesando splitters del bunch original" de "estoy procesando
splitters de un bunch que acabo de crear" se vuelve borroso. Separar en fases
te da invariantes claros:

- Fin de Fase 1: `Pi_s` estable respecto de **todos** los splitters pendientes.
- Fin de Fase 2: un bunch fue partido; splitters nuevos quedan en `splitterList`.

**2. Re-encolado correcto al partir un bloque.** Cuando un splitter parte el
bloque B en R y U (línea 1.16), todos los splitters pendientes que apuntaban
a B quedan obsoletos: hay que tener uno para R y otro para U. En el paper se
discute informalmente; en v0 está hecho a medias. v1 lo extrae a
`refineSplitters` y lo aplica sistemáticamente. Además, con
`targetStateToBunches`, v1 re-encola **sólo los bunches afectados** (no
todos), optimización que no se acomoda bien al nesting del paper.

**3. Bugs latentes de mutación.** v0 usa `ArrayDeque<Set<Triple>>` y muta los
bunches dentro de la cola (`addAll`, `removeAll` sobre el set ya encolado).
El `equals`/`hashCode` de `Set` depende del contenido, así que
`Pi_t_cola.contains(bunch)` puede dar falsos negativos después de la
mutación. v1 lo arregla con `IdentityQueue` (identidad por referencia, no por
valor). Este bug "sólo se nota" cuando separás las fases — porque mientras
seguís el flujo lineal del paper mutás-y-usás el mismo bunch sin que la cola
opine.

**4. Instrumentación y composición.** Tener fases nombradas deja medir
`tPhase1` vs. `tPhase2`. Y deja agregar cosas como `errorBlock` (v1) o el
filtrado de initiating actions de fluents (v2) en lugares semánticamente
correctos.

En resumen: v1 y v2 implementan el mismo algoritmo del paper, con la misma
complejidad, pero con una estructura de código que prioriza invariantes,
robustez y extensibilidad por sobre la fidelidad textual al pseudocódigo.

---

## Decisiones de implementación que el paper no especifica

El Algorithm 1 del paper deja muchos detalles abiertos. Acá están agrupados
por tema, indicando qué decide cada versión.

### Estructuras de datos

| Decisión | Paper dice | v0 | v1 | v2 |
|---|---|---|---|---|
| Tipo de `Πt` | "set of bunches" | `List<Set<Triple>>` | `List<Set<Triple>>` | `Set<Set<Triple>>` con `IdentityHashMap` |
| Cola de bunches a refinar | nada (el while externo itera sobre Πt) | `ArrayDeque` | `IdentityQueue` (identidad por referencia) | `IdentityQueue` |
| Tipo de `splitter list` | "list" (orden importa: primary antes que secondary) | `Deque<Splitter>` | `Deque<Splitter>` | `Deque<Splitter>` |
| Marcas | "almacenadas separadamente en el block-bunch-slice" | `Map<Splitter, Set<Triple>>` aparte | campo `marks` dentro del Splitter | igual a v1 |
| Lookup estado → bloque | nada | `Map<Long, Set<Long>>` | igual | igual |
| Lookup estado destino → bunches que lo contienen | nada (no hace falta porque itera todo Πt) | no existe | `targetStateToBunches: Map<Long, Set<Set<Triple>>>` | igual a v1 |
| Identificador de bloque | nada | el `Set<Long>` mismo | el `Set<Long>` mismo | `IdentityHashMap<Set<Long>, Integer>` (`blockIdMap`) |
| Emparejar primary/secondary del mismo refinamiento | nada (asume orden secuencial en la lista) | adyacencia en `Deque` (frágil) | `groupId: long` en cada Splitter | igual a v1 |

### SCCs τ-conexas

El paper (línea 1.1) dice **"contraer cada SCC τ a un único estado"**. Las
tres versiones evitan la contracción real (más cara, requiere reescribir el
MTS) y simulan el efecto manteniendo `stateToSCCMap`:

| Decisión | Paper | v0 | v1 / v2 |
|---|---|---|---|
| Contracción real de SCCs | sí | no, `stateToSCCMap` | no, `stateToSCCMap` |
| `R` arranca con... | "estados con marcas" (sobre estados ya contraídos) | estados sueltos con marca | **SCCs enteras** del origen de cada marca |
| Propagación backward | predecesores τ | predecesores τ sueltos | predecesores τ + expansión por SCC entera |
| Bottom states | "estados sin τ saliente que quede en el bloque" (sobre estados contraídos) | itera estado por estado | itera **por SCC**: una SCC es bottom si ninguno de sus estados tiene τ saliente que termine en otra SCC del mismo bloque |

v0 no respeta la atomicidad de las SCCs: dos estados de la misma SCC podrían
quedar uno en `R` y otro en `U`, lo cual es semánticamente incorrecto. v1/v2
lo arreglan expandiendo siempre por SCCs.

### Orden de operaciones

| Decisión | Paper | v0 | v1 / v2 |
|---|---|---|---|
| Cuál bunch elegir cuando hay varios no triviales | "exists T ∈ Πt with #aB'(T) > 1" (cualquiera) | el primero de `Pi_t_cola` (FIFO) | igual a v0 |
| Cuál slice elegir como "small half" | "some a, B' tal que `|T -a→ B'| ≤ |T|/2`" (cualquiera) | la **primera** que cumple ≤ \|T\|/2 al iterar el HashMap | igual |
| Qué hacer si **ninguna** slice cumple ≤ \|T\|/2 | no se discute (asume que siempre hay) | la primera no vacía | igual |
| Orden de drenado de splitters | "in order" (primary, después secondary del mismo bloque) | FIFO sobre `Deque`, primary se inserta antes que secondary | FIFO sobre `Deque`, emparejados por `groupId` |
| Cuándo procesar bunches creados por restabilización (línea 1.21) | implícito: en una iteración futura del while externo | inmediatamente, dentro del mismo while | en la próxima Fase 2 |

### Manejo de splitters obsoletos al partir un bloque

Cuando un splitter parte el bloque B en R y U, **los demás splitters
pendientes que apuntaban a B quedan sin un blanco válido**. El paper no
detalla qué hacer con ellos.

| Decisión | v0 | v1 / v2 |
|---|---|---|
| Splitter pendiente con `block == B` después del split | se descarta cuando se saca: `if (!Pi_s.contains(B)) continue;` | `refineSplitters` los **subdivide en dos**: uno para R, otro para U, preservando marcas según primary/secondary |
| Secondary asociado al primary que acaba de partir | hay un `peekFirst()` ad-hoc | `Iterator` sobre la lista buscando por `groupId` |
| Marcas tras subdividir un splitter secondary | recomputa marcas con bottom states de cada parte | `findBottomStates` por SCC para R y U, marca una transición saliente por bottom |

### Restabilización por τ no-inertes nuevas (líneas 1.20–1.28)

| Decisión | Paper | v0 | v1 / v2 |
|---|---|---|---|
| Dirección de las τ nuevas | sólo R-τ→U (justifica que U-τ→R no puede existir) | sólo R-τ→U | **ambas direcciones**: R-τ→U y U-τ→R, vía cola `newFrontiers` |
| Cascada al partir R en N y R' | "agregar N-τ→R' al bunch que ya creamos para R-τ→U" | hace exactamente eso, inline | `newFrontiers` se realimenta con (N, R'), (R', N), (N, tgt), (R', tgt) hasta agotar |
| Marcas en restabilización | "marcar una transición saliente por bottom state nuevo en cada T_N→" | se marca primera transición de `secondaryBySource.get(state)` | igual + `findBottomStates` por SCC |

v0 sigue al paper literalmente (sólo R-τ→U). v1/v2 agregan U-τ→R y la
cascada porque al integrarse con MTSs reales aparecieron casos donde, tras
partir, las τ "del otro lado" también pasan a ser no-inertes.

### Re-encolar bunches afectados

El paper no necesita re-encolar nada porque su outer while siempre re-itera
sobre todo Πt. v1/v2 usan una cola y necesitan re-encolado explícito:

| Decisión | v0 | v1 / v2 |
|---|---|---|
| Detectar bunches afectados por un split | no aplica (drena splitters dentro del mismo while que procesa el bunch) | `targetStateToBunches`: índice estado destino → bunches |
| Cuándo re-encolar | n/a | `enqueueAffectedBunches(R, ...)` después de cada split que mueve estados |
| Re-encolar al partir un bunch | n/a | actualizar `targetStateToBunches` con `chosenTransitions` y `newBunch` |

### Adaptaciones al dominio

Estas decisiones **no están en el paper** y son específicas del contexto de
síntesis de controladores discretos:

| Decisión | v0 | v1 | v2 |
|---|---|---|---|
| Estado de error `-1L` separado | no se trata | bloque inicial propio en `Pi_s` | igual a v1 |
| Self-loop τ en el MTS minimizado | se renombra a `c_<acción>` y se registra en `translatorControllable` | igual | igual |
| Acciones que disparan fluents (initiating actions) | no se contempla | no se contempla | se sacan de `tauLabels` antes del bucle |
| API para reusar partición precomputada | sólo `buildMinimisedMTS` | + `buildMinimisedMTSFromPartition` | igual a v1 |

El último punto (fluents) es el que más cambia la semántica del algoritmo:
si una acción modifica el valor de un fluent, **distinguir estados** depende
de esa acción, así que tratarla como τ haría que el algoritmo colapse
estados que el modelo considera distintos. Es un parche fuera del paper que
viene de la integración con MTSA.

### Construcción del MTS minimizado (no aparece en el paper)

El paper se ocupa sólo de calcular la partición; **construir el MTS
resultante no es parte de Algorithm 1**. Las tres versiones lo hacen igual:

1. Asignar un nuevo ID a cada bloque.
2. Mapear el initial state al bloque que lo contiene.
3. Por cada transición `s -a→ s'` del MTS original, crear `block(s) -a→ block(s')`
   en el resultado. Si es self-loop con `a ∈ tauLabels`, renombrar a
   `c_a` y registrar en `translatorControllable` (truco específico de DCS
   para preservar acciones controlables que de otro modo se perderían).

### Instrumentación

| Decisión | v0 | v1 / v2 |
|---|---|---|
| Medición de tiempos por fase | no | `Map<String, Double>` con `System.nanoTime()` por fase y subfase |
| Conteo de iteraciones del bucle principal | no | sí (`iterCount`) |

---

## Resumen rápido

| | v0 | v1 | v2 |
|---|---|---|---|
| Bucle principal | un único while sobre `Pi_t_cola` con todo entremezclado | dos fases explícitas (estabilizar estados / refinar bunches) | igual a v1 |
| `Splitter` | clase externa, enum `PRIMARY/SECONDARY`, marks afuera | clase interna, `boolean isPrimary` + `groupId`, marks adentro | igual a v1 |
| Cola de bunches | `ArrayDeque` | `IdentityQueue` | `IdentityQueue` |
| `split` | inicializa `R` con estados sueltos | inicializa `R` con SCCs enteras | igual a v1 |
| Bottom states | itera estado por estado | itera por SCC única | igual a v1 |
| Bloque error `-1L` | sin tratamiento especial | bloque inicial separado | igual a v1 |
| Restabilización | inline | abstraída en `refineSplitters` + `targetStateToBunches` | igual a v1 |
| Fluents (initiating actions) | no contempla | no contempla | saca initiating actions de τ |
| Hashing de slices | `Pair<String, Set<Long>>` (hash O(n)) | `Pair<String, Set<Long>>` | `Pair<String, Integer>` con `blockIdMap` |
| Instrumentación de tiempos | no | sí | sí |

---

## v0 — versión base

**Estructura del bucle.** Un único `while (!Pi_t_cola.isEmpty())` que mezcla todo:

1. Saca un bunch de la cola.
2. Lo parte por `(acción, bloque destino)` y elige una *slice* chica
   (`<= |T|/2` cuando se puede).
3. Por cada bloque "splittable", encola un splitter PRIMARIO y uno SECUNDARIO.
4. Drena `splitterList` adentro del mismo while: para cada splitter llama a
   `split(B, ...)` → si parte el bloque, ajusta `Pi_s`, y si quedaron τ
   no-inertes nuevas entre `R` y `U`, hace una segunda pasada de
   restabilización inline (busca bottoms, marca, agenda más splitters).

### Estructuras de datos clave (v0)

| Estructura | Tipo | Rol |
|---|---|---|
| `Pi_s` | `List<Set<Long>>` | partición de estados |
| `stateToBlockMap` | `Map<Long, Set<Long>>` | lookup estado → bloque |
| `Pi_t` | `List<Set<Triple<Long,String,Long>>>` | partición de transiciones (bunches) |
| `Pi_t_cola` | `Deque<Set<Triple>>` (`ArrayDeque`) | bunches pendientes de refinar |
| `splitterList` | `Deque<Splitter>` | tareas de estabilización |
| `Splitter` | clase con `sourceBlock`, `transitions`, `type` (enum) | tarea: "intentá partir este bloque con estas transiciones" |
| `markings` | `Map<Splitter, Set<Triple>>` | marcas por splitter, separado del splitter mismo |
| `stateToSCCMap` | `Map<Long, Set<Long>>` | SCCs τ-conexas, para distinguir inerte / no-inerte |
| `Bvis` / `Binvis` | `Set<Long>` | partición inicial (visible vs. invisible) |
| `predGraph` (en `computeBvis`) | `List<Set<Integer>>` | grafo inverso entre SCCs para BFS backwards |

### Input y output de un ciclo (v0)

- **Input**: `Pi_t_cola` no vacía, `Pi_s`, `stateToBlockMap`, `markings`.
- **Cada ciclo**:
  - Saca un bunch `T`.
  - Si `T` es trivial (todas sus transiciones tienen mismo `(a, B')`), no hace
    nada y sigue.
  - Elige una *slice* `chosenTransitions` y parte `T` en
    `chosenTransitions ∪ newBunch`.
  - Por cada bloque splittable, encola primary+secondary y los drena en el
    while interno (esto puede a su vez generar splits, generar nuevos τ
    no-inertes, generar más splitters).
- **Output**: `Pi_s` y `Pi_t` más refinados; `Pi_t_cola` con los bunches nuevos.
- **Termina** cuando `Pi_t_cola` queda vacía y no hay splitters.

### Limitaciones de v0 (que motivan v1)

- **Mezcla las dos fases del algoritmo de Groote.** En el paper las fases
  "refinar estados" y "refinar bunches" son separadas; acá están entrelazadas
  y es difícil razonar sobre invariantes.
- **`split` ingenuo respecto de SCCs**: arranca `R` con estados sueltos. Si dos
  estados están en la misma SCC τ deberían moverse juntos siempre, pero v0 no
  lo garantiza por construcción.
- **`equals`/`hashCode` peligrosos**: usa un `ArrayDeque<Set<Triple>>` y los
  bunches se mutan (`addAll`, `removeAll`). Si un bunch entra a la cola y
  después se modifica, `contains` puede mentir.
- **Restabilización inline difícil de seguir**: ~80 líneas dentro del while que
  hacen un segundo split y agendan splitters de "restablecimiento".
- **No maneja el bloque error**: el estado `-1L` queda mezclado con `Bvis` o
  `Binvis`, lo cual es semánticamente incorrecto en el contexto de DCS.
- **Sin instrumentación**: no se puede medir dónde se gasta el tiempo.

---

## v1 — refactor mayor: dos fases explícitas

### Cambios estructurales

**1. El bucle principal se parte en dos fases que se alternan:**

```
while (Pi_t_cola no vacía OR splitterList no vacía):
    FASE 1 — estabilizar estados: drená splitterList completo
    FASE 2 — refinar bunches: sacá UN bunch de Pi_t_cola y partilo
```

Esto coincide con el algoritmo de Groote: primero estabilizás los bloques
respecto de los splitters pendientes, después refinás un bunch (lo que crea
nuevos splitters), y volvés a estabilizar.

**2. `Splitter` pasa a ser clase interna estática** con `groupId` para
emparejar primario/secundario que vienen del mismo refinamiento de bunch, y
las marcas viven adentro del splitter (no en un mapa aparte).

**3. `IdentityQueue<T>`**: cola que decide pertenencia por **identidad de
referencia** (`IdentityHashMap`), no por `equals`. Esto evita el bug latente
de v0: ahora podés mutar un bunch y la cola sigue sabiendo que es "el mismo".

**4. `targetStateToBunches`**: índice inverso `estado → bunches que lo tienen
como destino`. Cuando se parte un bloque, en lugar de re-encolar todo
`Pi_t`, sólo re-encolás los bunches afectados.

**5. `refineSplitters`**: cuando un bloque `B` se parte en `R` y `U`, todos
los splitters pendientes que apuntaban a `B` se subdividen en dos splitters
(uno para `R`, uno para `U`), preservando marcas correctamente según sean
primarios o secundarios.

**6. `split` ahora trabaja con SCCs**: `R` arranca con las **SCCs enteras** de
los estados de origen de las transiciones, y la propagación backward también
expande por SCCs. Esto refleja la propiedad del algoritmo de Groote: una SCC
τ-conexa es atómica (sus estados son siempre branching-equivalentes entre sí).

**7. `findBottomStates` por SCC**: una SCC es "bottom" si ninguno de sus
estados tiene τ saliente que termine en otra SCC dentro del mismo bloque.
Más eficiente y conceptualmente más claro que mirar estado por estado.

**8. `findNewNonInertTransitions` agrupa por etiqueta**: devuelve
`Map<String, Set<Triple>>`, lo que permite procesar las transiciones nuevas
acción por acción (cada acción puede generar un splitter distinto).

**9. `errorBlock`**: el estado `-1L` se separa como bloque inicial propio.

**10. `newFrontiers`**: cola local `Queue<Pair<Set<Long>, Set<Long>>>` que
maneja la cascada de splits provocados por τ no-inertes recién aparecidas
entre `(R, U)` y `(U, R)`. Antes esto estaba enredado con el bucle externo.

**11. Tarjan corregido**: los DFS forward/backward agregan `break` después de
empujar un hijo no visitado (Tarjan iterativo correcto: no hay que seguir
mirando hermanos en el mismo nivel hasta volver).

**12. Instrumentación**: cada fase tiene su `System.nanoTime()` y devuelve un
`Map<String, Double>` con timings.

**13. Nuevo método `buildMinimisedMTSFromPartition`**: permite reusar una
partición ya calculada para construir el MTS sin recomputar.

### Estructuras de datos clave (v1)

Todo lo de v0 más:

| Estructura | Tipo | Rol |
|---|---|---|
| `Splitter.groupId` | `long` | empareja primary/secondary del mismo origen |
| `Splitter.marks` | `Set<Triple>` (campo del splitter) | marcas que arrancan `R` en `split` |
| `IdentityQueue<T>` | clase con `ArrayDeque` + `IdentityHashMap` | cola con identidad por referencia |
| `targetStateToBunches` | `Map<Long, Set<Set<Triple>>>` (con `IdentityHashMap` interno) | estado destino → bunches que lo contienen |
| `errorBlock` | `Set<Long>` con `{-1L}` | bloque distinguido para el estado de error |
| `newFrontiers` | `Queue<Pair<Set<Long>, Set<Long>>>` (local a Fase 1) | pares `(R, U)` pendientes de propagar tras un split |
| `timingMap` | `Map<String, Double>` | instrumentación de tiempos por fase |

### Input y output de un ciclo (v1)

Un "ciclo" del while externo corre una pasada de Fase 1 + una pasada de Fase 2.

**Fase 1 — Estabilizar estados**

- **Input**: `splitterList` (puede tener varios), `Pi_s`, `stateToBlockMap`,
  `targetStateToBunches`.
- **Cada iteración interna**:
  - Saca un splitter `(B, transitions, marks)`.
  - Si `B` ya no existe en `Pi_s` (fue partido por una iteración anterior), lo
    descarta.
  - Llama a `split(B, transitions, marks)` → obtiene `(R, U)`.
  - Si ambos no vacíos: actualiza `Pi_s`, `stateToBlockMap`, llama a
    `refineSplitters` para subdividir splitters pendientes que apuntaban a
    `B`, llama a `enqueueAffectedBunches` para re-encolar bunches afectados.
  - Lanza la cascada `newFrontiers` con `(R, U)` y `(U, R)`: por cada par,
    busca τ no-inertes nuevas, las agrega como bunches a `Pi_t`, y si esos
    splits generan más particiones, encola más frontiers.
- **Output**: `Pi_s` estable respecto de todos los splitters que entraron;
  bunches nuevos en `Pi_t_cola`; `splitterList` vacía.

**Fase 2 — Refinar bunches**

- **Input**: `Pi_t_cola` no vacía, `stateToBlockMap` actualizado.
- **Una sola iteración**:
  - Saca un bunch `T`.
  - Lo parte en slices por `(acción, bloque destino)`.
  - Si trivial (slice única), no hace nada.
  - Elige una slice chica `chosenTransitions` (≤ |T|/2 cuando es posible).
  - Parte `T` en `chosenTransitions ∪ newBunch`, actualiza
    `targetStateToBunches`.
  - Por cada bloque splittable, encola en `splitterList` un primary y un
    secondary con `groupId` compartido.
- **Output**: un bunch nuevo en `Pi_t`, splitters nuevos en `splitterList`
  para que la próxima Fase 1 los procese.

**Termina** cuando ambas estructuras quedan vacías.

### Por qué los cambios v0 → v1

| Cambio | Motivación |
|---|---|
| Dos fases explícitas | seguir el paper de Groote literalmente, simplificar invariantes y debugging |
| `Splitter` interno con `marks` adentro | evitar el `Map<Splitter, ...>` aparte que se desincronizaba |
| `IdentityQueue` | bug latente: mutar un bunch en `ArrayDeque` rompe `equals`/`hashCode` |
| `split` por SCCs | corregir la semántica: una SCC τ-conexa es atómica |
| Bottom por SCC | misma razón + eficiencia |
| `targetStateToBunches` | evitar re-escanear todo `Pi_t` cada vez que se parte un bloque |
| `refineSplitters` extraído | tenía 30 líneas inline difíciles de razonar |
| `errorBlock` separado | semánticamente correcto; el estado de error no es equivalente a nada |
| Tarjan con `break` | bug: sin el `break` el DFS no es un DFS bien formado |
| Instrumentación | medir antes de optimizar |

---

## v2 — soporte de fluents + optimización de hashing

v2 es estructuralmente igual a v1. Tres cambios concretos:

**1. Initiating actions de fluents fuera de τ.** `getPartitions` ahora recibe
`Vector<HashMap<String, String>> totalTranslator` y `Set<Fluent> fluents`. Al
inicio:

```java
HashSet<String> allInitiatingActions = new HashSet<>();
for (Fluent fluent : fluents) {
    for (Symbol initiatingAction : fluent.getInitiatingActions()) {
        allInitiatingActions.addAll(translateFromOriginal(...));
        allInitiatingActions.add(initiatingAction.toString());
    }
}
tauLabels.removeAll(allInitiatingActions);
```

**Por qué**: las initiating actions cambian el valor de un fluent y por lo
tanto distinguen estados desde la lógica del sistema. Si las dejás como τ, el
algoritmo las trata como invisibles y colapsa estados que el modelo considera
distintos.

**2. `blockIdMap`: hashing barato para bloques.**

```java
IdentityHashMap<Set<Long>, Integer> blockIdMap = new IdentityHashMap<>();
```

Cada bloque tiene un `int` asociado. En la Fase 2, las slices se indexan por
`Pair<String, Integer>` (acción, blockId) en lugar de
`Pair<String, Set<Long>>` (acción, bloque entero como clave).

**Por qué**: el `hashCode` de un `Set<Long>` es O(n) (suma los hashes de
todos los elementos). Con bloques grandes y muchas slices, esto domina. Con
un `int`, el hash es O(1). El `blockIdMap` se mantiene sincronizado en cada
split: `blockIdMap.remove(B); blockIdMap.put(R, ...); blockIdMap.put(U, ...)`.

**3. `Pi_t` pasa de `List` a `Set` con `IdentityHashMap`.** Evita duplicados
por identidad de referencia.

### Estructuras de datos clave (v2)

Todo lo de v1 más:

| Estructura | Tipo | Rol |
|---|---|---|
| `allInitiatingActions` | `HashSet<String>` | acciones que se sacan de `tauLabels` antes del bucle |
| `blockIdMap` | `IdentityHashMap<Set<Long>, Integer>` | id entero por bloque, para hashing eficiente en Fase 2 |
| `nextBlockId` | `int` | contador para asignar ids nuevos en cada split |
| `Pi_t` | `Set<Set<Triple>>` con `IdentityHashMap` interno | igual a v1 pero como Set para evitar duplicados |

### Input y output de un ciclo (v2)

Idénticos a v1. La diferencia es que la Fase 2 indexa por `(acción, blockId)`
en vez de `(acción, bloque)`, lo cual no cambia la semántica pero acelera el
hashing.

### Por qué los cambios v1 → v2

| Cambio | Motivación |
|---|---|
| Sacar initiating actions de τ | corregir bug semántico al integrar con el resto del DCS (los fluents distinguen estados) |
| `blockIdMap` para hashing | optimización de performance: el hashCode de Set domina cuando los bloques son grandes |
| `Pi_t` como Set por identidad | robustez: evitar bunches duplicados accidentalmente |

---

## Cronología sugerida para entender el código

1. Leer `partitionIntoSCCWithTauLabels` y `computeBvis` (común a las tres
   versiones, no cambia mucho).
2. Leer v0 entera para entender el algoritmo en su forma más directa.
3. Leer v1 viendo cómo se separan las dos fases y por qué hace falta
   `IdentityQueue` y `targetStateToBunches`.
4. Mirar el diff v1 → v2 (es chico): qué hace y por qué.
