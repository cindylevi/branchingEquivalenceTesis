# `RefinablePartition` — diseño

Estructura de datos para reemplazar `List<Set<Long>>` + `stateToBlockMap` +
`blockIdMap` en `BranchingEquivalence`. Es la "refinable partition" de Jansen,
Groote, Keiren, Wijs (2019) §5.1, opt #4 (la idea original viene de Valmari /
Lehtinen).

## Idea

Todos los estados viven en un único array `elements`. Cada bloque es un
**slice contiguo** `[start..end)` de ese array. La operación clave —
"separar los R-states del resto del bloque" — se hace **sin copiar**: se
reordena `elements` con swaps O(1) para que los R-states queden en un
sufijo, y después un nuevo `Block` toma posesión de ese sufijo.

Adentro de cada bloque hay tres rangos:

```
 start            bottomEnd          rDestEnd            end
   │                 │                  │                  │
   ▼                 ▼                  ▼                  ▼
   ┌──────────────┬──────────────────┬─────────────────────┐
   │  bottoms     │  non-bottom,     │  R-destined         │
   │              │  NOT in R        │  (non-bottom)       │
   └──────────────┴──────────────────┴─────────────────────┘
```

Invariante: `start ≤ bottomEnd ≤ rDestEnd ≤ end`.

## Por qué

| Operación frecuente en `BranchingEquivalence` | v2 | RefinablePartition |
|---|---|---|
| `Pi_s.contains(B)` | O(\|Pi_s\|) | `B.alive`, O(1) |
| `stateToBlockMap.get(s)` | O(1) hash | `blockOfElement[position[denseId(s)]]`, O(1) array |
| `block.equals(other)` | O(\|block\|) | identidad por referencia |
| `findBottomStates(B)` (iterá los bottoms) | itera todo B y filtra | itera `[start..bottomEnd)` directo |
| Split B en R y U | construye dos `HashSet` de O(\|B\|) | reordena swaps + crea dos `Block`, sin copia |
| "¿Está s en R?" durante un split | `R.contains(s)` con HashSet temporal | `position[denseId(s)] >= rDestEnd`, O(1) |

El tercer punto (identidad por referencia) elimina una clase entera de bugs:
en v2 hay varios `block.equals(other)` que recorren los conjuntos y son
correctos sólo porque las referencias casualmente coinciden.

## API pública

```java
final class RefinablePartition {
    // Construcción
    RefinablePartition(Collection<Long> universe);
    void seedFromInitialBlocks(List<Set<Long>> initialBlocks);

    // Lookup
    Block blockOf(long s);                  // O(1)
    Iterable<Long> states(Block b);          // O(|b|), sin allocation
    Iterable<Long> bottoms(Block b);         // O(|b.bottomCount|)
    Iterable<Long> rDestStates(Block b);     // O(|b.rDestCount|)
    List<Block> liveBlocks();                // unmodifiable

    // Mutación durante un split
    void addToR(long s);                     // O(1) swap, idempotente
    void clearR(Block b);                    // O(1)
    Pair<Block, Block> splitOffR(Block b);   // O(1) — los R-states ya están en el sufijo

    // Mutación de bottom states (recompute después de cada split)
    void markAsBottom(long s);
    void unmarkAsBottom(long s);
    void resetBottoms(Block b);              // O(1) — bottomEnd := start
}

final class Block {
    int start, end, bottomEnd, rDestEnd;
    final int id;
    boolean alive;
    int size();
    int bottomCount();
    int rDestCount();
}
```

## Mapeo de IDs no densos (decisión)

El MTS de DCS puede tener `-1L` (estado de error) y números no contiguos.
Hay dos opciones:

(a) **mapeo `Long → int` denso** al construir, vía
`HashMap<Long, Integer> denseIdOf` y `long[] byDenseId`.
(b) **`Long2IntOpenHashMap` (fastutil)** para mapeo directo.

**Elegimos (a)**: no agrega una dependencia y los MTS típicos tienen
~10⁴–10⁵ estados, así que el overhead del HashMap es despreciable. Por
performance crítica, los lookups están en operaciones que ya son O(1)
amortizado.

```java
private final Map<Long, Integer> denseIdOf;
private final long[] byDenseId;        // inverso: denseId → estado original
private final long[] elements;         // elements[i] = estado en posición i (Long)
private final int[] position;          // position[denseId(s)] = posición de s
private final Block[] blockOfElement;  // blockOfElement[i] = bloque en posición i
```

## Mecánica del swap

```java
private void swap(int i, int j) {
    long si = elements[i], sj = elements[j];
    elements[i] = sj; elements[j] = si;
    position[denseIdOf.get(si)] = j;
    position[denseIdOf.get(sj)] = i;
    // blockOfElement[i] y blockOfElement[j] no cambian: los slices se siguen
    // apuntando al mismo Block; sólo las posiciones internas se reordenan.
}
```

`blockOfElement[]` sólo cambia cuando hacemos `splitOffR`: el sufijo
`[rDestEnd..end)` pasa a apuntar al nuevo Block.

## `addToR` / `clearR` — cómo se mueven al sufijo

```java
void addToR(long s) {
    int pos = position[denseIdOf.get(s)];
    Block b = blockOfElement[pos];
    if (pos >= b.rDestEnd) return;       // ya estaba en R, no hacemos nada
    if (pos < b.bottomEnd) {              // s era bottom: lo sacamos del prefijo bottom
        swap(pos, b.bottomEnd - 1);
        b.bottomEnd--;
        pos = b.bottomEnd;
    }
    // ahora pos ∈ [b.bottomEnd, b.rDestEnd)
    swap(pos, b.rDestEnd - 1);
    b.rDestEnd--;
}

void clearR(Block b) {
    b.rDestEnd = b.end;
    // los estados del sufijo siguen ahí, sólo "olvidamos" que estaban en R.
}
```

Notar que `addToR` puede sacar un estado del prefijo bottom. Esto es
deseable: un bottom-state que pasa a R durante un split deja de ser bottom
del bloque viejo (pasa a un bloque nuevo), y los bottoms se recomputan
después.

## `splitOffR` — el corazón

```java
Pair<Block, Block> splitOffR(Block b) {
    if (b.rDestEnd == b.end) {
        // R vacío: no se parte nada
        return new Pair<>(null, b);
    }
    if (b.rDestEnd == b.start) {
        // U vacío: el bloque entero es R
        return new Pair<>(b, null);
    }
    Block rBlock = new Block(b.rDestEnd, b.end, nextBlockId++);
    Block uBlock = new Block(b.start, b.rDestEnd, nextBlockId++);
    // bottomEnd y rDestEnd de los bloques nuevos arrancan en start (sin bottoms ni R)
    rBlock.bottomEnd = rBlock.start; rBlock.rDestEnd = rBlock.end;
    uBlock.bottomEnd = uBlock.start; uBlock.rDestEnd = uBlock.end;
    for (int i = rBlock.start; i < rBlock.end; i++) blockOfElement[i] = rBlock;
    for (int i = uBlock.start; i < uBlock.end; i++) blockOfElement[i] = uBlock;
    b.alive = false;
    liveBlocks.remove(b);
    liveBlocks.add(uBlock);
    liveBlocks.add(rBlock);
    return new Pair<>(rBlock, uBlock);
}
```

El reasignar `blockOfElement[i]` para todo `i` del bloque viejo es O(\|b\|).
**No se puede evitar** sin agregar otra capa de indirección. Pero notar que
es una sola pasada lineal sobre el bloque, igual que iterar el bloque para
hacer cualquier otra cosa. La diferencia con v2 es que no hay copia de
estados a un nuevo `HashSet`.

## `seedFromInitialBlocks` — partir el bloque universal

Al construir, hay un solo bloque que contiene todos los estados en el orden
en que vinieron. `seedFromInitialBlocks(initial)` reordena `elements` para
que cada bloque inicial sea un slice contiguo, y crea los `Block` objects
correspondientes.

Estrategia: dos punteros. Para cada bloque inicial en orden, mover sus
estados al sufijo desde la izquierda y avanzar el puntero. Es la misma
operación que un `partition` de quicksort generalizado a k bloques.

## Cosas que el plan no resuelve y cómo las resuelvo acá

1. **`bottoms` como vista vs. iterable.** El plan dice `Iterable<Long>`,
   pero al iterar el rango `[start..bottomEnd)` puede pasar que durante la
   iteración alguien llame a `markAsBottom`, lo que mueve el `bottomEnd`. La
   solución: las vistas `states/bottoms/rDestStates` capturan los punteros
   al momento de crearse y son **estáticas** durante la iteración. El caller
   se compromete a no mutar el bloque mientras itera.

2. **Bloques vacíos.** Pueden surgir si todos los estados de un bloque son
   R-destined. `splitOffR` devuelve `null` para el bloque que quedó vacío.
   `liveBlocks` no contiene `null`s.

3. **Identidad de Block.** Dos llamadas a `splitOffR` con el mismo `b`
   (después de la primera `b.alive = false`) son un error. El caller debe
   verificar `b.alive` antes de cada operación.

4. **Hacer público `Block.start/end`.** Para usuarios que quieren iterar el
   array directamente con `for (int i = b.start; i < b.end; i++)`. La
   alternativa (encapsular todo en métodos) agrega allocations en hot paths
   de `getPartitions`. Trade-off aceptado.

## Cambios de semántica respecto de v2

- `Set<Long>` block → `Block` con identidad. `block.equals(other)` ya no
  existe; usar `block == other`.
- `stateToBlockMap.get(s)` → `Pi_s.blockOf(s)`.
- `Pi_s.contains(B)` → `B.alive`.
- `findBottomStates(N)` ahora **muta**: marca los bottoms del bloque y se
  iteran con `Pi_s.bottoms(N)`. Documentado en el método.
- `blockIdMap` deja de existir: cada `Block` tiene su `id`.
- `Pi_s` para iterar todos los bloques: `Pi_s.liveBlocks()`.

## Lo que NO hace esta estructura

No reemplaza la partición de transiciones (eso es Fase C). En esta fase, las
transiciones siguen siendo `Set<Triple<Long, String, Long>>` y los bunches
siguen siendo `Set<Set<Triple>>`. La única firma que cambia es la de los
bloques.
