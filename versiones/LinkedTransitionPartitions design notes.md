# `LinkedTransitionPartitions` — diseño

Estructura de datos para reemplazar `Set<Triple<Long, String, Long>>` (los
bunches de Πt), `IdentityHashMap` de Πt, `IdentityQueue` (la cola de bunches
a refinar), y `Map<Long, Set<Set<Triple>>> targetStateToBunches` en
`BranchingEquivalence`. Es la "linked refinable partition" de Jansen,
Groote, Keiren, Wijs (2019) §5.1 (la idea original viene también de
Valmari/Lehtinen).

Es la contraparte para transiciones de
[`RefinablePartition`](RefinablePartition%20design%20notes.md) (estados):
ambos juntos cierran las cuatro estructuras refinables encadenadas que dan
la complejidad O(m log n) prometida por el paper.

## Idea

Cada transición de Πt es un **objeto con identidad** (no un `Triple`
inmutable que se compara estructuralmente). El mismo objeto participa
simultáneamente de cuatro "vistas":

```
Transition t
  ├── bunch          : BunchSlice           — qué bunch de Πt la contiene
  ├── blockBunch     : BlockBunchSlice      — sub-grupo (bunch × source-block)
  ├── source listing : por estado fuente    — outgoing(s)
  └── target listing : por estado destino   — incoming(s)
```

Las dos primeras vistas son **refinables**: bunches se parten al refinar
Πt; block-bunch-slices se parten cuando se parte un bloque de Πs. Las dos
últimas son **estáticas** (la lista de transiciones salientes/entrantes de
un estado nunca cambia: la define el LTS de entrada).

Cada vista permite operaciones distintas de la matriz de transiciones:

| Vista | Operación que habilita |
|---|---|
| Por bunch | iterar transiciones de un bunch, contar `\|bunch\|` para peel ≤ \|T\|/2 |
| Por block-bunch-slice | flag de estabilidad por (bloque fuente, bunch); `peel` O(1) si la slice está al borde del bunch |
| Por source state | construir splitters refinados (filtrar por `Pi_s.blockOf(t.source) == B`) y enumerar inert-τ-out de un estado en O(deg(s)) |
| Por target state | reaccionar a `Pi_s.splitOffR(B)`: encontrar block-bunch-slices afectadas (las que tienen una transición que apuntaba a algún estado de B) |

## Por qué reemplaza a v3-B

| Operación frecuente en v3-B | Costo en v3-B | Costo en v3 |
|---|---|---|
| `for (Triple t : bunch)` con `bunch` un `HashSet` | O(\|bunch\|) iter + box | O(\|bunch\|) iter sin box |
| `Pi_t_cola.add(bunch)` | O(1) hash + dedup | O(1) flag por slice + global list |
| `targetStateToBunches.get(s)` para encolar bunches afectadas | O(deg-incoming(s)) HashMap lookups | O(deg-incoming(s)) por iteración del index estático |
| Identidad de bunch entre splits | `==` con `IdentityHashMap` | identidad por referencia con `Class BunchSlice` |
| Subagrupar un bunch por (action, target_block) | re-construir `Map<Pair<String, Integer>, Set<Triple>>` cada vez | (opcional) cache lazy invalidado al peel |

El trade-off principal: las operaciones que en v3-B eran "construir un
HashSet temporal y descartarlo" pasan a ser "marcar/desmarcar flags en
slices que ya existen". Eso elimina garbage colectable de hot path y abre
la puerta a la implementación O(1) del peel (paper §5.1 opt #4-5).

## API pública

```java
final class LinkedTransitionPartitions {

    // Transitions ----------------------------------------------------------
    Transition addTransition(long source, String action, long target);
    int totalTransitions();
    Iterable<Transition> outgoing(long source);              // O(deg-out(source))
    Iterable<Transition> incoming(long target);              // O(deg-in(target))

    // Bunches and slices ---------------------------------------------------
    void seedSingleBunch(Iterable<Transition> initial);      // crea Πt_0 con un solo bunch
    BunchSlice newBunch(Iterable<Transition> initial);       // alta de bunch (Fase 1 cascada)
    List<BunchSlice> liveBunches();
    BunchSlice peelSlice(BunchSlice bunch);                  // separa slice ≤ |bunch|/2 a un nuevo bunch

    // Stability ------------------------------------------------------------
    boolean hasUnstable();
    BlockBunchSlice popUnstable();
    void markUnstable(BlockBunchSlice s);
    void markStable(BlockBunchSlice s);

    // Block-bunch-slices ---------------------------------------------------
    Iterable<BlockBunchSlice> blockBunchSlicesOf(BunchSlice bunch);
    Iterable<BlockBunchSlice> blockBunchSlicesOf(RefinablePartition.Block block);

    // Hook para acoplamiento con Pi_s --------------------------------------
    void notifyBlockSplit(RefinablePartition.Block oldBlock,
                          RefinablePartition.Block uBlock,
                          RefinablePartition.Block rBlock);
}

final class Transition {
    final long source, target;
    final String action;
    BunchSlice bunch;
    BlockBunchSlice blockBunch;
    int untested;            // §5.1: contador para abort-on-half del split
}

final class BunchSlice {
    final int id;
    boolean alive;
    int size();
    Iterable<Transition> transitions();
}

final class BlockBunchSlice {
    final int id;
    final BunchSlice bunch;
    final RefinablePartition.Block sourceBlock;
    boolean alive;
    boolean stable;
    Iterable<Transition> transitions();
    int size();
}
```

## Layout interno

```java
private final List<Transition> allTransitions;

// Vistas estáticas: índices por source/target (no cambian al refinar Πs/Πt).
private final Map<Long, List<Transition>> bySource;
private final Map<Long, List<Transition>> byTarget;

// Vista por bunch: cada BunchSlice mantiene su propio Set<Transition> con
// identidad. La descomposición en block-bunch-slices se mantiene como un
// IdentityHashMap<Block, BlockBunchSlice> dentro del bunch.
private final List<BunchSlice> liveBunches;

// Vista por block-bunch-slice: un IdentityHashMap<Block, List<BlockBunchSlice>>
// que dado un bloque de Pi_s devuelve todas las slices que lo tienen como
// source. Esencial para `notifyBlockSplit`: cuando un bloque se parte en
// dos, hay que recorrer todas sus block-bunch-slices y subdividirlas.
private final Map<RefinablePartition.Block, List<BlockBunchSlice>> slicesByBlock;

// Estabilidad: lista global con swap-remove por índice. Es la "cola de
// inestables" del paper, pero implementada como ArrayList con position
// tracking para remoción O(1).
private final List<BlockBunchSlice> globalUnstable;

private int nextBunchId, nextBlockBunchId;
private final RefinablePartition Pi_s;
```

## Por qué `Set<Transition>` en BunchSlice y no un slice contiguo

El paper describe una versión del bunch como slice contiguo de un array
maestro de transiciones, igual que `RefinablePartition` lo hace para
estados. Eso permite peel O(1) puro.

En esta implementación, los bunches usan `Set<Transition>` con identidad
(`Collections.newSetFromMap(new IdentityHashMap<>())`). Trade-off:

- **Contra:** el peel es O(|slice|) en lugar de O(1) — hay que iterar la
  slice para moverla del set viejo al nuevo.
- **A favor:** evita densificar IDs de transiciones, mantener `position[]`
  paralelo a `Pi_s`, y reordenar el array al partir bunches; reduce ~150
  líneas de código sutil.

El peel sigue siendo asintóticamente correcto si elegimos siempre la
slice ≤ |bunch|/2: es la "smaller half" — el costo total amortizado del
peel es O(|T| log |T|), igual que el bound del paper. Lo que se pierde es
el factor constante.

Si en una versión futura hace falta el O(1) absoluto, esta clase se puede
reescribir manteniendo la API: el `BranchingEquivalence` no toca la
representación.

## Coreografía con `RefinablePartition`

El acoplamiento crítico es: **cuando `Pi_s.splitOffR(B)` parte B en
(uBlock, rBlock), las block-bunch-slices que tenían `sourceBlock == B` se
quedan obsoletas**. Hay que crear nuevas slices para uBlock y rBlock,
redistribuir las transiciones entre ellas según `Pi_s.blockOf(t.source)`,
y marcar las nuevas como inestables.

```
Pi_s.splitOffR(B) → (uBlock, rBlock)
       ↓
Lt.notifyBlockSplit(B, uBlock, rBlock)
       ↓
para cada BlockBunchSlice old con old.sourceBlock == B:
    crear newU = BlockBunchSlice(bunch=old.bunch, sourceBlock=uBlock)
    crear newR = BlockBunchSlice(bunch=old.bunch, sourceBlock=rBlock)
    para cada Transition t en old:
        if Pi_s.blockOf(t.source) == uBlock: t.blockBunch = newU; newU.transitions.add(t)
        else: t.blockBunch = newR; newR.transitions.add(t)
    old.alive = false
    old removida de globalUnstable si estaba
    newU, newR registradas en slicesByBlock[uBlock] y slicesByBlock[rBlock]
    newU, newR marcadas como inestables (entran a globalUnstable)
```

**Quién llama a `notifyBlockSplit`:** el `BranchingEquivalence`, justo
después de cada `Pi_s.splitOffR`. No usamos un listener pattern porque
acoplar las clases con un callback explícito implícito es más opaco que
una llamada directa, y solamente hay un lugar en el código donde se parte
un bloque: el método `split()`. (Si en el futuro `RefinablePartition`
gana otra operación de partición, recordar agregar la notificación.)

**Por qué no escribir `Pi_s.splitOffRWithNotification(...)`:** porque
`RefinablePartition` no debe conocer `LinkedTransitionPartitions`. La
direccionalidad es la natural: la partición de transiciones depende de la
de estados, no al revés.

## Stability flag — invariantes

```
para toda BlockBunchSlice s viva:
    s.stable = false  ⟺  s ∈ globalUnstable  ⟺  s.posInGlobalUnstable >= 0
```

Operaciones:

```java
markUnstable(s):
    if s.stable:
        s.stable = false
        s.posInGlobalUnstable = globalUnstable.size()
        globalUnstable.add(s)

markStable(s):                              // implícito al popear
    if !s.stable:
        s.stable = true
        swap-remove de globalUnstable usando posInGlobalUnstable
        s.posInGlobalUnstable = -1
```

El swap-remove es O(1):

```
last = globalUnstable.size() - 1
if s.posInGlobalUnstable != last:
    movido = globalUnstable[last]
    globalUnstable[s.posInGlobalUnstable] = movido
    movido.posInGlobalUnstable = s.posInGlobalUnstable
globalUnstable.removeLast()
```

## Peel — heurística

`peelSlice(bunch)` separa una slice ≤ |bunch|/2 del bunch. Estrategia:

1. Subagrupar las transiciones de `bunch` por la clave `(action,
   targetBlock)` — esto da las "action-target-block-slices" del bunch.
2. Si solo hay una clave, el bunch es trivial (no se subdivide en
   splitters distintos). Devolver `null`: el bunch no era refinable.
3. Si hay ≥ 2 claves, elegir la primera con tamaño ≤ |bunch|/2 (existe
   por pigeonhole).
4. Crear un BunchSlice nuevo con esas transiciones; redistribuir
   block-bunch-slices entre los dos bunches.
5. Marcar las block-bunch-slices del bunch viejo afectadas por el peel
   como inestables (las que perdieron transiciones), y las del bunch
   nuevo como inestables (todas).

Coste: O(|bunch|) para subagrupar (paso 1) + O(|slice|) para mover.
Amortizado sobre todas las refinaciones, esto suma O(|T| log |T|).

**Nota.** Una versión más fiel al paper §5.1 mantendría las
action-target-block-slices ordenadas dentro del bunch desde la
construcción inicial; el paso 1 sería O(1) (mirar la primera o última
sub-slice). Esa optimización está documentada en el plan original (Task
C.3 step 7-8) pero requiere mantener un orden interno explícito; en esta
implementación lo dejamos como peeling O(|bunch|) y delegamos esa
optimización a una iteración futura. La diferencia de complejidad final
es solo en la constante.

## `notifyBlockSplit` — invariante post-condición

Antes de llamar:
- `Pi_s.splitOffR(B)` ya devolvió `(uBlock, rBlock)`. `B.alive == false`.
- Para todo `t` ∈ Πt con `t.source ∈ B`: `Pi_s.blockOf(t.source)` ahora
  devuelve `uBlock` o `rBlock` (no `B`). `t.blockBunch.sourceBlock` aún
  apunta a `B`, pero `t.blockBunch.alive == true` y `t.blockBunch.bunch.alive == true`.

Después de llamar:
- Para todo `t` ∈ Πt con `t.source` originalmente en `B`:
  `t.blockBunch.sourceBlock == Pi_s.blockOf(t.source)`. La block-bunch-slice
  vieja está muerta y removida de `slicesByBlock[B]` y de
  `globalUnstable`.
- Las block-bunch-slices nuevas están registradas en `slicesByBlock` y
  marcadas como inestables.

## Loop principal de `getPartitions` con esta estructura

```
inicialización:
    Pi_s = new RefinablePartition(...)
    Lt   = new LinkedTransitionPartitions(Pi_s)
    Lt.seedSingleBunch(...)  // crea bunch_0 + sus block-bunch-slices iniciales
    splitterList = ArrayDeque<Splitter>()
    para cada BlockBunchSlice s del bunch_0: marcar como Splitter primario inicial

loop:
    while !splitterList.isEmpty() || Lt.hasUnstable():
        // FASE 1: estabilizar bloques
        while !splitterList.isEmpty():
            sp = splitterList.removeFirst()
            (R, U) = split(sp.block, sp.transitions, ...)
            Lt.notifyBlockSplit(sp.block, U, R)         // reasigna BBS
            refineSplitters(sp.block, R, U, splitterList, ...)
            // cascada Fase 1 sobre nuevos τ no-inertes (igual que v3-B)

        // FASE 2: refinar bunches
        if Lt.hasUnstable():
            bbs = Lt.popUnstable()
            if !bbs.alive || bbs.size() == 0: continue
            newBunch = Lt.peelSlice(bbs.bunch)
            if newBunch == null: continue   // bunch trivial
            generar splitters para los bloques afectados (igual que v3-B)
```

El cambio respecto de v3-B es:

- **El elemento que sale de la cola es una BlockBunchSlice, no un bunch
  entero.** El loop popea `bbs` y refina su `bunch`. Esto es lo que da el
  log: cada BBS solo se procesa cuando algo cambió en su (bunch,
  block_fuente).
- **`enqueueAffectedBunches` desaparece.** En v3-B esa rutina iteraba los
  estados del bloque R recién creado, miraba `targetStateToBunches[s]`, y
  encolaba todos los bunches afectados. Acá, esa misma lógica se hace
  dentro de `notifyBlockSplit` automáticamente: las BBS del bloque viejo
  pasan a las dos BBS nuevas, y ambas son marcadas inestables.
- **`Pi_t_cola` y la membership en `Pi_t` desaparecen.** `liveBunches` los
  reemplaza para iterar bunches, y `globalUnstable` para encolar trabajo.

## Cosas que esta estructura **NO** resuelve

1. **El orden interno del bunch.** Como dijimos arriba, mantenemos el
   bunch como `Set<Transition>` y agrupamos por `(action, targetBlock)`
   en cada peel. La versión paper-pure mantendría ese ordenamiento
   incrementalmente.

2. **Densificación de transition IDs.** `RefinablePartition` densifica
   IDs de estados; acá no densificamos IDs de transiciones porque no
   indexamos `Transition[]` por posición — todo va por referencia. Si
   alguna vez se quiere bajar a un layout array-based, hay que
   densificar al construir.

3. **Per-transition `untested` counter.** El campo `Transition.untested`
   queda disponible pero sin uso. La iteración C.5 (split con coroutine
   dual + abort-on-half) implementa el contador a nivel de SCC en un
   `IdentityHashMap<Set<Long>, Integer>` local al `split`. Una variante
   per-transición — densificada a un array paralelo de `Transitions` —
   ahorraría boxing y un lookup; queda como optimización de constante.

## Cambios de semántica respecto de v3-B

- `Set<Triple<Long, String, Long>>` (un bunch) → `BunchSlice`. Hash y
  equals dejan de ser estructurales; usar `==` o `bunch.id`.
- `IdentityHashMap<Set<Triple>, ...>` (Pi_t) → `liveBunches()`.
- `IdentityQueue<Set<Triple>>` (Pi_t_cola) → `globalUnstable` + flag por
  BBS. La unidad de trabajo cambia: del bunch entero a la (bunch ×
  source_block).
- `Map<Long, Set<Set<Triple>>> targetStateToBunches` → `byTarget` (lista
  estática de transiciones por destino) + `slicesByBlock` (lista de BBS
  por bloque).
- Una "transición" deja de ser un valor inmutable; ahora es un objeto
  con identidad. Comparar transiciones es por referencia.
- El `Splitter.transitions` puede seguir siendo `Set<Transition>` con
  identidad, o cambiar a `BlockBunchSlice` directo. En esta
  implementación lo dejamos como `Set<Transition>` por minimizar el
  delta con `refineSplitters` heredado de v3-B.

## Lo que NO hace esta estructura (vs v3-A)

- No reemplaza la representación de Πs (eso es Fase B, ya hecha en v3-B).
- No cambia el cálculo de SCCs τ (eso es Fase D, ya hecha en v3-A1).
- No cambia las dos optimizaciones de §5.2 (eso es Fase A, ya hecha en
  v3-A1; se mantiene el `if (b == validTNew) continue`).
