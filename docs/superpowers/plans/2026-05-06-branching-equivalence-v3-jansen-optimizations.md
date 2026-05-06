# BranchingEquivalence v3 — optimizaciones de Jansen et al. (2019)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cerrar la brecha entre `BranchingEquivalence version 2.java` y el Algorithm 1 + §5.1/§5.2 del paper de Jansen, Groote, Keiren, Wijs (2019), incorporando las dos optimizaciones explícitas faltantes y las cuatro estructuras de datos refinables que dan la complejidad O(m log n) prometida.

**Architecture:** Cuatro fases incrementales. Fase A baja fruto al piso: dos parches chicos en el flujo de Fase 1 (saltar el splitter recién aplicado, evitar splitters cuando `Bottom(N) ⊆ Bottom(R)`) — son ~20 líneas y no tocan tipos. Fase B reemplaza `List<Set<Long>>` por una *refinable partition* indexada (estructura tipo Valmari/Lehtinen) con bloques como rangos contiguos y separación bottom/non-bottom/R-destined. Fase C reemplaza `Set<Triple<Long,String,Long>>` + `targetStateToBunches` por cuatro particiones refinables encadenadas (per bunch / per block-bunch-slice / per source / per target), lo que habilita el peel O(1), la flag de estabilidad por slice, y el `untested[t]` con coroutines en lockstep. Fase D reemplaza el Kosaraju de la pre-computación de SCCs τ por Tarjan iterativo (un solo DFS, sin grafo invertido). Cada fase sale como una versión nueva en `versiones/` y se valida contra un corpus golden capturado de v2. **Fase D es ortogonal a B/C y se puede ejecutar en cualquier orden** (se sugiere al final por snapshot, pero podés moverla justo después de Fase A si querés acumular cheap wins antes del rewrite grande).

**Tech Stack:** Java (MTSA, paquete `MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional`). Implementación target: `BranchingEquivalence.java` en MTSA. Snapshots: `~/Documents/Exactas/Tesis/versiones/BranchingEquivalence version 3.java` (al final de cada fase). Paper de referencia: `~/Documents/Exactas/Tesis/Papers/jansen.pdf`.

**Convenciones:**
- Cada referencia "línea 1.X" remite al Algorithm 1 del paper (página 8).
- Cada referencia "§5.1 / §5.2" remite al paper de Jansen.
- "v2" = `versiones/BranchingEquivalence version 2.java` (la baseline).
- "v3" = la versión final al cerrar el plan; "v3-A", "v3-B", "v3-C" = checkpoints intermedios.
- "MTSA" = repo donde vive el código real; en este plan solo se referencia sin asumir path local.

---

## Fase 0 — Red de seguridad (golden tests + benchmark)

Antes de tocar lógica, capturar el comportamiento de v2 sobre un corpus de MTSs reales. Sin esto, cada refactor de Fase B/C es ciego.

**Decisión clave:** la partición de equivalencia es única (es la clase mínima); el orden interno de los bloques o los IDs no lo son. Comparar canonicalizando: cada bloque → conjunto ordenado de IDs viejos; particiones → conjunto ordenado de bloques. Esa es la firma a chequear.

### Task 0.1: Capturar corpus de entrada

**Files:**
- Create: `versiones/golden/inputs/<caseName>.json` (uno por caso)

- [ ] **Step 1: Identificar casos del benchmark del paper composicional.** TravelAgency, AirTrafficController, AGV, DiningPhilosophers, BidingWorkflow (los que usa Mohajerani et al. y/o `arxiv.org/abs/2506.16557`). Si el benchmark de MTSA tiene estos como ejemplos, listarlos.

- [ ] **Step 2: Decidir formato de fixture.** Cada caso es una tupla `(states, transitions, tauLabels, fluents, totalTranslator)`. Serializar como JSON con shape:

```json
{
  "name": "TravelAgency-3",
  "initialState": 0,
  "states": [0, 1, 2, ...],
  "transitions": [[0, "tau", 1], [1, "book", 2], ...],
  "tauLabels": ["tau", "internal_step"],
  "fluents": [{"name": "Booked", "initiating": ["book"], "terminating": ["cancel"]}],
  "totalTranslator": [{"book": "BOOK_OK"}]
}
```

- [ ] **Step 3: Escribir un dumper en MTSA** que tome `MTS<Long, String>` + parámetros y escriba el JSON anterior. Usar Jackson o `gson` (lo que ya esté en MTSA).

- [ ] **Step 4: Correr el dumper sobre todos los casos identificados.** Guardar en `versiones/golden/inputs/`.

- [ ] **Step 5: Commit.**

```bash
git add versiones/golden/inputs/
git commit -m "test: capture input fixtures for branching equivalence golden tests"
```

### Task 0.2: Capturar partición esperada de v2

**Files:**
- Create: `versiones/golden/outputs/v2/<caseName>.json`
- Create: `versiones/golden/PartitionSignature.java` (helper)

- [ ] **Step 1: Definir la "firma canónica" de una partición.**

```java
public final class PartitionSignature {
    public static String of(List<Set<Long>> partition) {
        List<List<Long>> sorted = partition.stream()
            .map(block -> block.stream().sorted().collect(Collectors.toList()))
            .sorted(Comparator.comparing(b -> b.get(0)))
            .collect(Collectors.toList());
        return sorted.toString();
    }
}
```

- [ ] **Step 2: Escribir un runner** que cargue cada `inputs/*.json`, llame a `BranchingEquivalence.getPartitions(...)`, calcule la firma de `Pi_s` y la escriba en `outputs/v2/<caseName>.json` junto con el `Map<String, Double>` de timings.

- [ ] **Step 3: Correr el runner con v2.** Verificar manualmente 1-2 casos chicos a ojo (que la firma tenga sentido: estados equivalentes en el mismo bloque, error block solo, etc.).

- [ ] **Step 4: Commit.**

```bash
git add versiones/golden/outputs/v2/ versiones/golden/PartitionSignature.java
git commit -m "test: capture v2 reference partitions for golden test corpus"
```

### Task 0.3: Test runner reusable

**Files:**
- Create: `versiones/golden/GoldenTest.java`

- [ ] **Step 1: Escribir test driver.**

```java
public class GoldenTest {
    public static void main(String[] args) throws Exception {
        String version = args[0]; // "v2", "v3-A", ...
        Path inputsDir = Paths.get("versiones/golden/inputs");
        Path expectedDir = Paths.get("versiones/golden/outputs/v2");

        int pass = 0, fail = 0;
        for (Path input : Files.list(inputsDir).collect(Collectors.toList())) {
            String name = input.getFileName().toString().replace(".json", "");
            MTS<Long, String> mts = loadMTS(input);
            // ... cargar tauLabels, fluents, totalTranslator del JSON
            var result = BranchingEquivalence.getPartitions(mts, tauLabels, totalTranslator, fluents);
            String actual = PartitionSignature.of(result.getFirst().getFirst());
            String expected = readSignature(expectedDir.resolve(name + ".json"));
            if (actual.equals(expected)) { pass++; }
            else {
                fail++;
                System.err.println("FAIL " + name);
                System.err.println("  expected: " + expected);
                System.err.println("  actual:   " + actual);
            }
        }
        System.out.printf("%d/%d passed%n", pass, pass + fail);
        if (fail > 0) System.exit(1);
    }
}
```

- [ ] **Step 2: Correrlo contra v2 mismo** para validar que la firma es consistente (sanity check: el runner pasa cuando se compara v2 contra v2).

```bash
java GoldenTest v2
# expected: N/N passed
```

- [ ] **Step 3: Commit.**

```bash
git add versiones/golden/GoldenTest.java
git commit -m "test: add golden test driver for branching equivalence versions"
```

### Task 0.4: Benchmark baseline

**Files:**
- Create: `versiones/golden/Benchmark.java`
- Create: `versiones/golden/benchmarks/v2.csv`

- [ ] **Step 1: Driver que mida 5 corridas warm + 10 corridas medidas** por caso, reporte mediana y p95 de tiempo total y de cada subfase del `timingMap`. Salida CSV con columnas `case, version, total_ms_p50, total_ms_p95, phase1_ms_p50, phase2_ms_p50, iterCount`.

- [ ] **Step 2: Correr contra v2.** Guardar como `benchmarks/v2.csv`.

- [ ] **Step 3: Commit.**

```bash
git add versiones/golden/Benchmark.java versiones/golden/benchmarks/v2.csv
git commit -m "test: capture v2 performance baseline"
```

---

## Fase A — Optimizaciones de §5.2 (cheap wins)

Dos cambios pequeños en `getPartitions`. No modifican tipos de datos. La hipótesis: ahorran trabajo de splitting redundante en runs reales pero no cambian la salida (firma canónica idéntica).

### Task A.1: Saltear el bunch recién aplicado al iterar Pi_t

**Files:**
- Modify: MTSA — `BranchingEquivalence.java:386-401` (corresponde a `versiones/BranchingEquivalence version 2.java:386-401`)

**Contexto.** Después de partir `currentSrc` en `N` y `src_prime` usando `validTNew` como splitter (líneas 357-405), v2 itera `for (Set<Triple<Long, String, Long>> b : Pi_t)` y crea splitters secundarios de `N` para cada bunch. El paper §5.2 nota que `N` ya es estable respecto del bunch `validTNew` que acabamos de aplicar (es lo que generó `N` como bloque coherente), así que ese bunch específico no necesita splitter.

- [ ] **Step 1: Agregar guard de identidad en el loop.**

```java
if (!N.isEmpty()) {
    Set<Long> bottoms = findBottomStates(N, toMinimise, tauLabels, stateToSCCMap);
    for (Set<Triple<Long, String, Long>> b : Pi_t) {
        if (b == validTNew) continue;  // §5.2 opt: N is already stable wrt validTNew
        // ... resto del cuerpo igual
    }
}
```

Notar que `Pi_t` usa `IdentityHashMap`, así que `==` es la comparación correcta.

- [ ] **Step 2: Correr golden test.**

```bash
java GoldenTest v3-A
# expected: N/N passed (la firma debe ser idéntica)
```

- [ ] **Step 3: Si falla**, revertir y buscar el caso mínimo que difiere. Posibles causas: (a) no se está usando `IdentityHashMap` realmente — chequear `Pi_t` declaration; (b) el guard era válido solo bajo otra invariante. NO seguir hasta que el golden test pase.

- [ ] **Step 4: Commit.**

```bash
git add <BranchingEquivalence.java>
git commit -m "perf: skip just-applied bunch when enqueueing N's secondary splitters (Jansen §5.2)"
```

### Task A.2: Evitar splitters de N cuando Bottom(N) ⊆ Bottom(currentSrc)

**Files:**
- Modify: MTSA — `BranchingEquivalence.java:367-405`

**Contexto.** El paper §5.2 (optimización 3) prueba que si `N` no introduce bottom states nuevos respecto del bloque del que vino (`currentSrc` antes del split), entonces `T_N→` es estable para todo bunch `T` que ya era estable para `T_currentSrc→`. Sin esa propiedad, v2 igual encola splitters secundarios para todo bunch de `Pi_t`.

**Decisión de diseño.** Computar `bottomsCurrentSrc = findBottomStates(currentSrc, ...)` ANTES del `split` (línea 363), porque después `currentSrc` ya no está en `Pi_s`. Cachearlo en una variable local.

- [ ] **Step 1: Calcular bottoms del padre antes de split.**

```java
// línea 362-364 actuales:
Pair<Set<Long>, Set<Long>> splitRes = split(currentSrc, validTNew, validTNew, toMinimise, tauLabels, stateToSCCMap);
Set<Long> N = splitRes.getFirst();
Set<Long> src_prime = splitRes.getSecond();
```

Reemplazar por:

```java
Set<Long> bottomsCurrentSrc = findBottomStates(currentSrc, toMinimise, tauLabels, stateToSCCMap);
Pair<Set<Long>, Set<Long>> splitRes = split(currentSrc, validTNew, validTNew, toMinimise, tauLabels, stateToSCCMap);
Set<Long> N = splitRes.getFirst();
Set<Long> src_prime = splitRes.getSecond();
```

- [ ] **Step 2: Computar `bottoms` (de N) una sola vez y testear inclusión.**

```java
if (!N.isEmpty()) {
    Set<Long> bottoms = findBottomStates(N, toMinimise, tauLabels, stateToSCCMap);
    boolean hasNewBottoms = !bottomsCurrentSrc.containsAll(bottoms);
    if (!hasNewBottoms) {
        currentSrc = N;
        continue;  // §5.2 opt 3: N has no new bottoms → all T_N→ are stable
    }
    for (Set<Triple<Long, String, Long>> b : Pi_t) {
        if (b == validTNew) continue;
        // ... resto igual
    }
}
```

- [ ] **Step 3: Golden test.**

```bash
java GoldenTest v3-A
# expected: N/N passed
```

- [ ] **Step 4: Si falla**, sospechar que la prueba del paper no aplica al setting con MTS controlables / fluents. Documentar el contraejemplo y pensar si el guard necesita un refinamiento (por ejemplo, "Bottom" computado contra `tauLabels` después de filtrar initiating actions). Si es así, reformular y reintentar.

- [ ] **Step 5: Commit.**

```bash
git commit -m "perf: skip N's splitter creation when no new bottoms (Jansen §5.2)"
```

### Task A.3: Snapshot v3-A y benchmark

**Files:**
- Create: `versiones/BranchingEquivalence version 3-A.java`
- Modify: `versiones/golden/benchmarks/v3-A.csv`
- Modify: `versiones/comparacion-versiones.md` (agregar sección breve "v3-A — §5.2 cheap wins")

- [ ] **Step 1: Copiar el archivo de MTSA al directorio de versiones.**

```bash
cp <MTSA>/.../BranchingEquivalence.java "versiones/BranchingEquivalence version 3-A.java"
```

- [ ] **Step 2: Correr benchmark con v3-A.** Comparar contra v2.csv. Esperable: tiempo total similar o menor; iterCount probablemente menor (menos splitters procesados de gusto).

```bash
java Benchmark v3-A > versiones/golden/benchmarks/v3-A.csv
```

- [ ] **Step 3: Documentar en `comparacion-versiones.md`.** Agregar una sección entre "v2" y "v3" (al final) listando los dos cambios y el delta de benchmark. Una tabla de 2 filas alcanza.

- [ ] **Step 4: Commit.**

```bash
git commit -m "docs: snapshot v3-A and document §5.2 optimizations"
```

---

## Fase B — Refinable partition para estados (§5.1, opt #4)

Reemplazar `List<Set<Long>>` por una estructura indexada donde cada bloque es un *slice* de un array contiguo, con tres particiones internas: bottom, non-bottom, R-destined (estados que están en R durante un split en curso).

**Por qué.** v2 hace operaciones tipo `Pi_s.contains(B)`, `block.equals(other)`, `stateToBlockMap.get(s)`, `findBottomStates` iterando el bloque entero. Con la estructura del paper:
- "¿Está el bloque B vivo?" → mirar un puntero, O(1).
- "¿De qué bloque es s?" → un índice fijo en el array.
- "Iterá los bottom states del bloque" → un sub-rango del slice del bloque, O(|bottoms|) sin filtrar.
- "Movéte un estado de non-bottom a bottom" → swap con el primer non-bottom, O(1).

Esto es lo que habilita en Fase C el `untested[t]` y la abort-on-half del split.

### Task B.1: Diseñar la API de `RefinablePartition`

**Files:**
- Create: MTSA — `…/Compositional/RefinablePartition.java`
- Create: `versiones/RefinablePartition design notes.md`

- [ ] **Step 1: Documentar invariantes.** En `design notes.md`:

```
Estructura interna:
  long[] elements          // todos los estados, en algún orden
  int[]  position          // position[s] = índice de s en elements
  Block[] blockOfElement   // blockOfElement[i] = bloque que contiene elements[i]

Cada Block: { int start, int end, int bottomEnd, int rDestEnd, int id }
  start..bottomEnd-1   = bottom states
  bottomEnd..rDestEnd  = non-bottom, NOT in R during current split
  rDestEnd..end-1      = R-destined non-bottom states
  invariante: start ≤ bottomEnd ≤ rDestEnd ≤ end

Operaciones:
  Block blockOf(long s)           O(1)
  Iterable<Long> states(Block b)   O(|b|) sin allocation
  Iterable<Long> bottoms(Block b)  O(|bottoms|) sin allocation
  void markBottom(long s, Block b) O(1) swap
  void addToR(long s, Block b)     O(1) swap (solo si no estaba ya en R)
  void clearR(Block b)             O(1) (rDestEnd := end)
  Pair<Block, Block> split(Block b) — separa R de no-R en dos bloques nuevos, O(|b|)
  boolean isAlive(Block b)         O(1) (chequea si b sigue en la partición o fue partido)
```

- [ ] **Step 2: Definir la clase pública con stubs.**

```java
public final class RefinablePartition {
    private final long[] elements;
    private final int[] position;
    private final Block[] blockOfElement;
    private final List<Block> liveBlocks;
    private int nextBlockId;

    public static final class Block {
        int start, end, bottomEnd, rDestEnd;
        final int id;
        boolean alive = true;
        Block(int start, int end, int id) { /* ... */ }
        public int size() { return end - start; }
        public int bottomCount() { return bottomEnd - start; }
        public int rDestCount() { return end - rDestEnd; }
    }

    public RefinablePartition(Collection<Long> initialUniverse) { /* ... */ }
    public Block blockOf(long s) { return blockOfElement[position[(int) s]]; /* asume IDs densos */ }
    public Iterable<Long> states(Block b) { /* slice view */ }
    public Iterable<Long> bottoms(Block b) { /* range view */ }
    public void markAsBottom(long s) { /* swap to [start..bottomEnd) */ }
    public void unmarkAsBottom(long s) { /* swap out */ }
    public void addToR(long s) { /* swap to [rDestEnd..end) */ }
    public void clearR(Block b) { b.rDestEnd = b.end; }
    public Pair<Block, Block> splitOffR(Block b) { /* ... */ }
    public Pair<Block, Block> seedAndSplit(Set<Long> initialBlock1, Set<Long> initialBlock2) { /* para refineSplitters */ }
    public List<Block> liveBlocks() { return Collections.unmodifiableList(liveBlocks); }
}
```

- [ ] **Step 3: Decidir manejo de IDs no densos.** El MTS de DCS puede tener `-1L` (error) y números no-contiguos. Opciones: (a) mapear `Long → int` denso al construir; (b) usar `Long2IntOpenHashMap` (fastutil). Elegir (a): un `Map<Long, Integer> denseIdOf` y un array de Long de tamaño N. Documentar en design notes.

- [ ] **Step 4: Commit.**

```bash
git commit -m "design: scaffold RefinablePartition data structure"
```

### Task B.2: Implementar `RefinablePartition` con tests unitarios

**Files:**
- Modify: `…/Compositional/RefinablePartition.java`
- Create: `…/test/.../RefinablePartitionTest.java`

- [ ] **Step 1: Test 1 — construcción inicial.**

```java
@Test public void singleBlockHoldsAllStates() {
    var p = new RefinablePartition(List.of(0L, 1L, 2L, 3L));
    var b = p.blockOf(0L);
    assertEquals(4, b.size());
    assertEquals(b, p.blockOf(3L));
}
```

- [ ] **Step 2: Implementar constructor + `blockOf` + `states`** hasta que pase.

- [ ] **Step 3: Test 2 — addToR / clearR.**

```java
@Test public void addToRMovesStateToTail() {
    var p = new RefinablePartition(List.of(0L, 1L, 2L, 3L));
    var b = p.blockOf(0L);
    p.addToR(1L);
    p.addToR(2L);
    assertEquals(2, b.rDestCount());
    var rDestStates = StreamSupport.stream(p.rDestStates(b).spliterator(), false)
        .collect(Collectors.toSet());
    assertEquals(Set.of(1L, 2L), rDestStates);
    p.clearR(b);
    assertEquals(0, b.rDestCount());
}
```

- [ ] **Step 4: Implementar `addToR`, `clearR`, `rDestStates` con la mecánica de swap.** Asegurarse de que `position[]` y `blockOfElement[]` se mantienen consistentes en cada swap. Un swap es:

```java
private void swap(int i, int j) {
    long si = elements[i], sj = elements[j];
    elements[i] = sj; elements[j] = si;
    position[denseIdOf(si)] = j;
    position[denseIdOf(sj)] = i;
}
```

- [ ] **Step 5: Test 3 — splitOffR mueve los R-states a bloque nuevo.**

```java
@Test public void splitOffRCreatesTwoBlocks() {
    var p = new RefinablePartition(List.of(0L, 1L, 2L, 3L));
    var b = p.blockOf(0L);
    p.addToR(1L); p.addToR(3L);
    var split = p.splitOffR(b);
    var rBlock = split.getFirst();
    var uBlock = split.getSecond();
    assertEquals(Set.of(1L, 3L), toSet(p.states(rBlock)));
    assertEquals(Set.of(0L, 2L), toSet(p.states(uBlock)));
    assertFalse(b.alive);
    assertTrue(rBlock.alive);
    assertTrue(uBlock.alive);
}
```

- [ ] **Step 6: Implementar `splitOffR`.** El truco: como los R-states están en el sufijo `[rDestEnd..end)`, partir es solo "crear un Block con esos índices y otro con `[start..rDestEnd)`, marcar `b.alive = false`". No hay copia.

- [ ] **Step 7: Test 4 — markAsBottom funciona dentro de un bloque.**

- [ ] **Step 8: Implementar `markAsBottom` / `unmarkAsBottom` con swap entre `[start..bottomEnd)` y `[bottomEnd..rDestEnd)`.**

- [ ] **Step 9: Test 5 — invariante: posición consistente tras 1000 operaciones aleatorias.** Generar una secuencia random de addToR / splitOffR / markAsBottom y al final, para todo estado s, `position[denseIdOf(s)]` debe apuntar a `s` y `blockOfElement[position[s]]` debe ser un bloque vivo que contiene a `s`.

- [ ] **Step 10: Si el test 5 falla**, debuggear con un test más chico hasta encontrar la operación que rompe la invariante.

- [ ] **Step 11: Commit.**

```bash
git commit -m "feat: implement RefinablePartition with swap-based block ops"
```

### Task B.3: Wrapper de compatibilidad con `List<Set<Long>>`

**Files:**
- Modify: `RefinablePartition.java`

**Contexto.** El consumidor `buildMinimisedMTS` espera `List<Set<Long>>` para iterar bloques. No vale la pena reescribir esa parte ahora; en cambio, exponer una vista.

- [ ] **Step 1: Método `asLegacyView()`.**

```java
public List<Set<Long>> asLegacyView() {
    return liveBlocks.stream()
        .map(b -> {
            Set<Long> view = new HashSet<>(b.size());
            for (Long s : states(b)) view.add(s);
            return view;
        })
        .collect(Collectors.toList());
}
```

- [ ] **Step 2: Commit.**

```bash
git commit -m "feat: add legacy view for RefinablePartition consumers"
```

### Task B.4: Reemplazar `Pi_s` y `stateToBlockMap` en `getPartitions`

**Files:**
- Modify: MTSA — `BranchingEquivalence.java` (todo `getPartitions`)

**Estrategia.** Esto toca casi todo `getPartitions`. Hacerlo en un paso atómico, no por etapas, porque las representaciones tienen que ser consistentes.

- [ ] **Step 1: Cambiar tipos en la inicialización (líneas 208-217 en v2).**

```java
// antes:
List<Set<Long>> Pi_s = new ArrayList<>();
if (!errorBlock.isEmpty()) Pi_s.add(errorBlock);
if (!Bvis.isEmpty()) Pi_s.add(Bvis);
if (!Binvis.isEmpty()) Pi_s.add(Binvis);
IdentityHashMap<Set<Long>, Integer> blockIdMap = new IdentityHashMap<>();
int nextBlockId = 0;
for (Set<Long> block : Pi_s) blockIdMap.put(block, nextBlockId++);

Map<Long, Set<Long>> stateToBlockMap = new HashMap<>();
for (Set<Long> block : Pi_s) for (Long state : block) stateToBlockMap.put(state, block);

// después:
RefinablePartition Pi_s = new RefinablePartition(toMinimise.getStates());
List<Set<Long>> initialBlocks = new ArrayList<>();
if (!errorBlock.isEmpty()) initialBlocks.add(errorBlock);
if (!Bvis.isEmpty()) initialBlocks.add(Bvis);
if (!Binvis.isEmpty()) initialBlocks.add(Binvis);
Pi_s.seedFromInitialBlocks(initialBlocks);
// blockIdMap deja de existir: cada Block ya tiene .id
// stateToBlockMap deja de existir: Pi_s.blockOf(s) es O(1)
```

Implementar `seedFromInitialBlocks` en `RefinablePartition`: parte el bloque universal en los bloques iniciales reordenando `elements` para que cada bloque sea un slice contiguo.

- [ ] **Step 2: Reemplazar todos los `stateToBlockMap.get(s)` por `Pi_s.blockOf(s)`.** Esto incluye:
  - Línea 237-238 (initial bunch construction)
  - Línea 269 (`bunchBySource`)
  - Línea 419 (`targetBlock = stateToBlockMap.get(targetState)`)
  - Línea 464-465 (`findSplittableBlocks`) — ahora devuelve `Set<Block>` en vez de `Set<Set<Long>>`
  - Línea 698-700 (cuerpo de `findSplittableBlocks`)

- [ ] **Step 3: Cambiar `Splitter.block` de `Set<Long>` a `Block`.**

```java
static class Splitter {
    final RefinablePartition.Block block;
    // resto igual
}
```

Adaptar todos los sitios que crean Splitters (líneas 274, 324, 399, 480, 499, 565-566).

- [ ] **Step 4: Adaptar `split(Block b, ..., RefinablePartition Pi_s, ...)`.** El signature cambia de:

```java
private static Pair<Set<Long>, Set<Long>> split(Set<Long> B, ...)
```

a:

```java
private static Pair<RefinablePartition.Block, RefinablePartition.Block> split(
    RefinablePartition.Block B, RefinablePartition Pi_s, ...)
```

El cuerpo: en lugar de devolver dos `Set<Long>` y que el caller los meta en `Pi_s`, ahora `split` usa `Pi_s.addToR(s)` por cada estado a poner en R, y al final llama a `Pi_s.splitOffR(B)` que devuelve los dos bloques nuevos. **Importante:** `clearR(B)` al inicio para asegurar estado limpio.

- [ ] **Step 5: Adaptar `refineSplitters` con `Block` en vez de `Set<Long>`.** Ahora ya no hace falta el chequeo `if (pending.block.equals(oldBlock))`: usar `pending.block == oldBlock` (identidad). Y al subdividir un splitter, las transiciones de R / U se filtran con `Pi_s.blockOf(t.getFirst()) == R`.

- [ ] **Step 6: Adaptar `enqueueAffectedBunches`.** Su parámetro `R` pasa de `Set<Long>` a `Block`; iterar con `Pi_s.states(R)` en vez de `for (Long s : R)`.

- [ ] **Step 7: Adaptar `findBottomStates`.** Antes devolvía `Set<Long>`; ahora puede devolver una vista — pero dado que el caller la usa para mark/iterate, lo más simple es: la nueva versión USA la `RefinablePartition` directamente, marcando los bottoms de un bloque con `markAsBottom(s)`. Resultado: `findBottomStates(Block b)` se convierte en una rutina void que actualiza la posición del bloque (mueve sus bottoms al prefijo `[start..bottomEnd)`). Documentar este cambio de semántica en un comment.

- [ ] **Step 8: Adaptar `buildMinimisedMTS` y `buildMinimisedMTSFromPartition`.** Usar `Pi_s.asLegacyView()` (Task B.3) al final de `getPartitions` para devolver `List<Set<Long>>` en el `Pair` resultado, manteniendo retrocompatibilidad con el llamador `CompositionalApproach`.

- [ ] **Step 9: Compilar y arreglar errores de tipos** hasta que el archivo cierre.

- [ ] **Step 10: Golden test.**

```bash
java GoldenTest v3-B
# expected: N/N passed
```

- [ ] **Step 11: Si falla**, NO seguir. Identificar el caso mínimo. Causas probables:
  - `seedFromInitialBlocks` mezcló estados de bloques distintos
  - `splitOffR` no actualizó `liveBlocks`
  - Un consumidor sigue usando el `stateToBlockMap` viejo (búsqueda: `grep -n stateToBlockMap`)
  - `findBottomStates` ahora muta y un caller esperaba que no mutara

- [ ] **Step 12: Commit.**

```bash
git commit -m "refactor: replace Pi_s + stateToBlockMap with RefinablePartition"
```

### Task B.5: Snapshot v3-B y benchmark

- [ ] **Step 1: Copiar el archivo a `versiones/BranchingEquivalence version 3-B.java`.**

- [ ] **Step 2: Correr benchmark `v3-B`.** Esperable: tiempo total levemente menor o similar; el cambio grande viene en Fase C.

- [ ] **Step 3: Documentar en `comparacion-versiones.md`** la sección "v3-B — refinable partition para estados". Incluir tabla del shape de los datos (start/end/bottomEnd/rDestEnd).

- [ ] **Step 4: Commit.**

```bash
git commit -m "docs: snapshot v3-B"
```

---

## Fase C — Particiones refinables encadenadas para transiciones

Reemplaza `Set<Triple<Long,String,Long>>` (bunches), `targetStateToBunches`, y los `Map<Pair<String,Integer>, Set<Triple>>` que se construyen en cada Fase 2. Es el corazón de §5.1 y la única manera de cerrar la complejidad O(m log n).

**Estructura.** Una transición tiene cuatro "vistas" simultáneas, cada una como una refinable partition:

1. **Por bunch**: el conjunto Πt; cada bunch es un slice.
2. **Por block-bunch-slice**: dentro de un bunch, las transiciones agrupadas por bloque fuente. Esto es lo que permite el "peel" O(1).
3. **Por source state**: para cada estado s, las transiciones salientes. Esto da el `untested[t]` y la enumeración rápida en split.
4. **Por target state**: lo que hoy hace `targetStateToBunches`, pero indexado al nivel de transición individual.

Las cuatro comparten el mismo array de transiciones; las particiones son distintos órdenes/agrupamientos del mismo array.

**Implementación.** Usar el mismo patrón que `RefinablePartition` (Task B.1): un array maestro `Transition[] transitions`, y cuatro arrays de `int[] position` (uno por vista) más cuatro arrays paralelos de `Slice[] sliceOfTransition`.

### Task C.1: Diseñar `LinkedTransitionPartitions`

**Files:**
- Create: `versiones/LinkedTransitionPartitions design notes.md`

- [ ] **Step 1: Definir el tipo `Transition`.**

```java
public static final class Transition {
    public final long source, target;
    public final String action;
    int posInBunch, posInBlockBunch, posInSource, posInTarget;
    BunchSlice bunch;
    BlockBunchSlice blockBunch;
    SourceSlice sourceSlice;
    TargetSlice targetSlice;
    int untested;  // §5.1: counter for split coroutine abort
}
```

- [ ] **Step 2: Definir cada Slice.**

```java
public static final class BunchSlice { int start, end; final int id; boolean alive = true; List<BlockBunchSlice> blockSlices; /* ... */ }
public static final class BlockBunchSlice { int start, end; final int id; final RefinablePartition.Block block; final BunchSlice bunch; boolean stable; /* ... */ }
public static final class SourceSlice { int start, end; final long source; }
public static final class TargetSlice { int start, end; final long target; }
```

- [ ] **Step 3: Documentar las operaciones que cada vista debe soportar.**

```
BunchSlice:
  Iterable<Transition> transitions(BunchSlice b)
  BlockBunchSlice peelFirstSlice(BunchSlice b)  // O(1) — el primer block-bunch-slice (smaller half)
  BlockBunchSlice peelLastSlice(BunchSlice b)   // O(1)

BlockBunchSlice:
  Iterable<Transition> transitions(BlockBunchSlice s)
  void markStable(BlockBunchSlice s)
  void markUnstable(BlockBunchSlice s)
  Iterable<BlockBunchSlice> stableSlicesOfBlock(Block b)
  Iterable<BlockBunchSlice> unstableSlices()  // global

SourceSlice:
  Iterable<Transition> outgoing(long source)
  Iterable<Transition> outgoingTo(long source, RefinablePartition.Block targetBlock)

TargetSlice:
  Iterable<BlockBunchSlice> blockBunchesContaining(long target)
```

- [ ] **Step 4: Documentar la operación clave — `splitBunch(BunchSlice b, BlockBunchSlice slice)`.** Mueve `slice` a un BunchSlice nuevo, redirige los punteros, mantiene block-bunch-slices coherentes para ambos lados. O(|slice|).

- [ ] **Step 5: Commit.**

```bash
git commit -m "design: linked refinable partitions for transitions"
```

### Task C.2: Implementar la vista por source y por target

**Files:**
- Create: `…/Compositional/LinkedTransitionPartitions.java`
- Create: `…/test/.../LinkedTransitionPartitionsTest.java`

Empezar por las dos vistas más simples (no participan en peeling). Vamos a poder usar `outgoing(s)` en Fase Test C.5 incluso antes de tener las otras dos vistas listas.

- [ ] **Step 1: Test — outgoing devuelve las transiciones salientes del estado.**

```java
@Test public void outgoingReturnsAllOutgoingTransitions() {
    var transitions = List.of(
        new Transition(0, "a", 1),
        new Transition(0, "b", 2),
        new Transition(1, "a", 2));
    var ltp = new LinkedTransitionPartitions(transitions);
    var out0 = toList(ltp.outgoing(0L));
    assertEquals(2, out0.size());
}
```

- [ ] **Step 2: Implementar la SourceSlice.** Ordenar `transitions[]` agrupado por source, mantener `Map<Long, SourceSlice>`.

- [ ] **Step 3: Test — outgoingTo filtra por bloque destino.** Sin pasar por bloques: usar un mock `Predicate<Long>`. Cuando RefinablePartition esté integrado, el wrapper resuelve el predicado.

- [ ] **Step 4: Implementar la TargetSlice.** Misma idea, con array indexado por target.

- [ ] **Step 5: Commit.**

```bash
git commit -m "feat: implement source/target views of LinkedTransitionPartitions"
```

### Task C.3: Implementar BunchSlice + BlockBunchSlice + peel O(1)

**Files:**
- Modify: `LinkedTransitionPartitions.java`

- [ ] **Step 1: Test — bunch inicial contiene todas las transiciones no-inertes.**

- [ ] **Step 2: Implementar el array maestro de bunches** y `BunchSlice`. Cada Bunch tiene un `start..end` en el array maestro, igual que RefinablePartition.

- [ ] **Step 3: Test — un bunch se subdivide en block-bunch-slices coherentes con la partición de bloques.**

```java
@Test public void blockBunchesGroupBySourceBlock() {
    // setup: 2 blocks of states, transitions cruzados, todos en mismo bunch
    var ltp = ...;
    var bunch = ltp.singleBunch();
    var slices = ltp.blockBunchesOf(bunch);
    assertEquals(2, slices.size()); // uno por block source
    // ...
}
```

- [ ] **Step 4: Implementar BlockBunchSlice como sub-slice del bunch.** Cada block-bunch-slice también es contiguo en el array maestro: el invariante es que dentro de un bunch, las transiciones están agrupadas por bloque fuente. Mantener `bunch.blockSlices: List<BlockBunchSlice>` ordenado.

- [ ] **Step 5: Test — peelFirstSlice retorna el primer block-bunch-slice y lo saca del bunch original.**

```java
@Test public void peelFirstSliceMovesItToNewBunch() {
    var ltp = ...;
    var bunch = ltp.singleBunch();
    int totalBefore = bunch.size();
    var firstSlice = bunch.blockSlices.get(0);
    var newBunch = ltp.peelFirstSlice(bunch);
    assertEquals(firstSlice, newBunch.blockSlices.get(0));
    assertEquals(totalBefore, bunch.size() + newBunch.size());
}
```

- [ ] **Step 6: Implementar peelFirstSlice.** Creando un BunchSlice nuevo que cubre `[oldBunch.start..firstSlice.end)` y moviendo `oldBunch.start := firstSlice.end`. Las block-bunch-slices migran de lista. **No hay copia de transiciones.**

- [ ] **Step 7: Test — refinar un bunch arbitrariamente (no solo first/last) sigue siendo O(|slice|).** Para el caso del paper, la operación canónica es peel del primero o del último. Pero en v2 / Fase 2 actual se elige una slice arbitraria que cumpla `≤ |T|/2`. **Decisión**: implementar `peelArbitrarySlice(bunch, slice)` también, que requiere una pasada O(|slice|) para reordenar el array maestro.

Acá tenés que elegir: (a) seguir la heurística de v2 (cualquier slice `≤ |T|/2`); (b) cambiar a la del paper (peel first o last, ambos son guaranteed `≤ |T|/2` por construcción). El paper §5.1 dice que **action-block-slice grouping** + peel first/last da el `≤ |T|/2` automáticamente: si vas en orden por (action, target_block), la primera o la última slice cumple. Implementar (b) — es más simple y respeta la complejidad del paper.

- [ ] **Step 8: Adaptar el orden interno del bunch para que respete (action, target_block).** Esto significa que cuando se inserta una transición a un bunch, su posición depende del action y del target block. Mantener el bunch como una secuencia de "action-block-slices" concatenadas.

- [ ] **Step 9: Test final — peel se mantiene O(1) si elegís first/last slice.** Medir con assert de tiempo: `assertTimeout(Duration.ofMillis(1), () -> ltp.peelFirstSlice(bigBunch))` con un bunch de 10⁵ transiciones.

- [ ] **Step 10: Commit.**

```bash
git commit -m "feat: implement bunch / block-bunch-slice views with O(1) peel"
```

### Task C.4: Stability flag por block-bunch-slice + lista de inestables

**Files:**
- Modify: `LinkedTransitionPartitions.java`

**Contexto.** §5.1 reemplaza `Pi_t_cola` (la cola de bunches a refinar) por: cada block-bunch-slice tiene una flag `stable`; cuando se vuelve inestable (porque su bloque fuente cambió, o porque su bunch fue partido), entra en una lista global de unstables. El loop principal saca de esa lista en vez de la cola de bunches.

- [ ] **Step 1: Agregar lista global y campos.**

```java
private final List<BlockBunchSlice> globalUnstable = new ArrayList<>();
// en BlockBunchSlice:
boolean stable = true;
int posInGlobalUnstable = -1;  // -1 si está stable
```

- [ ] **Step 2: Implementar markUnstable / markStable** con O(1) (swap-remove en `globalUnstable`).

- [ ] **Step 3: Test — al partir un bloque, las block-bunch-slices del bloque viejo se vuelven inestables.**

- [ ] **Step 4: Implementar el hook en `RefinablePartition.splitOffR`.** Cuando se llama, notificar a `LinkedTransitionPartitions` (vía callback registrado o evento) para marcar inestables las block-bunch-slices afectadas. Decisión de diseño: hacer que `LinkedTransitionPartitions` reciba la `RefinablePartition` y registre un listener al constructor.

- [ ] **Step 5: Documentar la coreografía** entre las dos clases en `design notes.md`. Esto es importante porque el acoplamiento entre RefinablePartition y LinkedTransitionPartitions es la fuente más probable de bugs.

- [ ] **Step 6: Commit.**

```bash
git commit -m "feat: add stability flag + global unstable list for block-bunch-slices"
```

### Task C.5: `untested[t]` counter + dual coroutines en `split`

**Files:**
- Modify: `BranchingEquivalence.java` — la rutina `split`

**Contexto crítico.** Esta es **la** optimización que da O(m log n). El paper §5.1 dice (parafraseado):

```
split(B, splitter):
  R := { sources of splitter }
  forward_workers = [ BFS from R via inert-tau predecessors-in-B ]
  reverse_workers = [ BFS from B \ R via inert-tau successors-in-B that point to non-R ]
  ejecutá ambas en LOCKSTEP (un step de cada una alternando)
  el primero que toque |B|/2 estados:
    el OTRO es la "smaller half"
    abort el primero, el otro consume su trabajo

  untested[t] : por cada transition t saliente de un estado en B,
    cuántos targets de t aún no han sido testeados
  cuando untested[t] == 0 y t apunta a R-only → s va a R
  cuando untested[t] == 0 y t apunta a U-only → s va a U
```

Esto es lo que logra la abort-on-half: si la smaller half es chica, ni vas a tocar la larger half completa. v2 hace una BFS plana hasta agotar; en el peor caso es O(|B|) por iteración, lo que perdés el log.

- [ ] **Step 1: Documentar la coroutine en `BranchingEquivalence.java` arriba de `split`.** Pseudocódigo Java:

```java
/**
 * §5.1 split with abort-on-half. Two BFSs run in lockstep:
 *   - forward: from R-states, follow inert-τ predecessors in B
 *   - reverse: from non-R-states, follow inert-τ successors in B
 *               that point only to non-R targets (using untested[t] counter)
 *
 * untested[t]: for each tau-transition t = (s, τ, s'), the count of s' still
 * unclassified (initially deg(s')). When untested[t] hits 0, s can be
 * classified (R if all its tau-targets are R, U otherwise).
 *
 * Abort: as soon as one side surpasses |B|/2, the OTHER side is the smaller
 * half. Stop the larger side and assign all unclassified states to the
 * larger side.
 */
```

- [ ] **Step 2: Reemplazar `split` con la versión coroutine.** No hay forma de hacer esto en pasos chicos: es un rewrite atómico. Esquema:

```java
private static Pair<Block, Block> split(Block B, Iterable<Transition> splitter,
                                         RefinablePartition Pi_s,
                                         LinkedTransitionPartitions Lt,
                                         Set<String> tauLabels,
                                         Map<Long, Set<Long>> stateToSCCMap) {
    // initialize R with sources of splitter (expanding by SCC, like v2)
    Pi_s.clearR(B);
    for (Transition t : splitter) {
        if (Pi_s.blockOf(t.source) == B) {
            for (Long sccState : stateToSCCMap.get(t.source)) Pi_s.addToR(sccState);
        }
    }
    int rSize = B.rDestCount();
    int half = B.size() / 2;

    Deque<Long> forwardQ = new ArrayDeque<>();  // R candidates
    Deque<Long> reverseQ = new ArrayDeque<>();  // U candidates
    // seed: forwardQ con states ya en R; reverseQ con un estado de B no-R cualquiera

    // initialize untested[t] for all tau-out from B that points within B
    // ...

    boolean forwardAlive = true, reverseAlive = true;
    while (forwardAlive && reverseAlive) {
        // forward step
        if (!forwardQ.isEmpty()) {
            Long s = forwardQ.poll();
            for (Long pred : inertTauPredecessorsInB(s, B, tauLabels)) {
                if (Pi_s.addToR(pred)) {  // returns true if newly added
                    forwardQ.add(pred);
                    rSize++;
                    if (rSize > half) { forwardAlive = false; break; }
                }
            }
        } else { forwardAlive = false; }

        if (!forwardAlive) break;

        // reverse step
        if (!reverseQ.isEmpty()) {
            Long s = reverseQ.poll();
            for (Transition tauOut : Lt.outgoingTau(s)) {
                if (Pi_s.blockOf(tauOut.target) == B) {
                    tauOut.untested--;
                    if (tauOut.untested == 0 && !isInR(tauOut.target)) {
                        // s is U-only via this tau; check all its tau outs
                        // ... if all classified U → mark as U, push pred onto reverseQ
                    }
                }
            }
            // if (B.size() - rSize - U so far) > half → reverseAlive = false;
        } else { reverseAlive = false; }
    }

    // whichever side stopped first, the OTHER is smaller. Assign remainders to larger.
    if (!forwardAlive) {
        // R won (got too big or finished). U = explicitly classified as U so far + drained reverse
        // assign the rest to R via Pi_s.addToR
    } else {
        // U won. Assign rest to U: clear them from R if still there
    }

    return Pi_s.splitOffR(B);
}
```

**Esto es ~150 líneas de código denso.** No intentar implementarlo en una sola sesión: dividir en sub-pasos.

- [ ] **Step 3: Sub-paso (a) — implementar solo forward worker** (sin abort, sin reverse). Verificar que reproduce v2.split exactamente.

- [ ] **Step 4: Sub-paso (b) — agregar `untested[t]` initialization** y un check del invariante (suma de untested == suma de inert-τ-out).

- [ ] **Step 5: Sub-paso (c) — agregar reverse worker sin abort.** Verificar contra forward: idealmente clasifican igual.

- [ ] **Step 6: Sub-paso (d) — agregar abort.**

- [ ] **Step 7: Golden test.**

```bash
java GoldenTest v3-C
# expected: N/N passed
```

- [ ] **Step 8: Si falla**, generar un MTS sintético chico (5-10 estados) que reproduzca el bug. Esto es el sub-paso más arriesgado del plan; reservar tiempo de debugging.

- [ ] **Step 9: Commit.**

```bash
git commit -m "feat: O(m log n) split with untested counter and dual coroutines (Jansen §5.1)"
```

### Task C.6: Reemplazar `Pi_t_cola` por la lista global de unstables

**Files:**
- Modify: `BranchingEquivalence.java` — el while principal en `getPartitions`

- [ ] **Step 1: Eliminar `Pi_t_cola`** y `targetStateToBunches`. La estructura `LinkedTransitionPartitions` los reemplaza.

- [ ] **Step 2: El loop principal cambia** de:

```java
while (!Pi_t_cola.isEmpty() || !splitterList.isEmpty()) {
  // FASE 1: drenar splitterList
  // FASE 2: sacar UN bunch de Pi_t_cola y partirlo
}
```

a:

```java
while (Lt.hasUnstable() || !splitterList.isEmpty()) {
  // FASE 1: drenar splitterList
  // FASE 2: sacar UNA block-bunch-slice unstable de Lt.globalUnstable.
  //         Si su bunch es trivial, marcarla stable y seguir.
  //         Si no, peel first o last slice, generar splitters.
}
```

- [ ] **Step 3: Reemplazar `enqueueAffectedBunches`** por el callback ya hecho en Task C.4 (al partir un bloque, las block-bunch-slices de su src vieja se marcan inestables automáticamente).

- [ ] **Step 4: Adaptar Fase 2** para usar `Lt.peelFirstSlice(bunch)` en vez de buscar slice `≤ |T|/2` con HashMap.

- [ ] **Step 5: Adaptar la cascada de `newFrontiers` en Fase 1** para crear bunches nuevos en `Lt` (no en `Pi_t` directamente).

- [ ] **Step 6: Golden test.**

```bash
java GoldenTest v3-C
# expected: N/N passed
```

- [ ] **Step 7: Commit.**

```bash
git commit -m "refactor: replace Pi_t_cola with global unstable list of block-bunch-slices"
```

### Task C.7: Snapshot v3 final + benchmark

**Files:**
- Create: `versiones/BranchingEquivalence version 3.java`
- Create: `versiones/golden/benchmarks/v3.csv`
- Modify: `versiones/comparacion-versiones.md`

- [ ] **Step 1: Copiar el archivo de MTSA a `versiones/BranchingEquivalence version 3.java`.**

- [ ] **Step 2: Correr el benchmark con v3 final** y guardar como `v3.csv`. **Comparación esperada:** v3 debería ser asintóticamente mejor que v2 en casos grandes (>10⁴ estados); en casos chicos puede ser más lento por overhead constante. Documentar.

- [ ] **Step 3: Generar gráfico de scaling.** Tiempo total vs. |estados| + |transiciones|, en escala log-log, una curva por versión. Exportarlo a `versiones/golden/benchmarks/scaling.png`. Esto es directamente material para el Cap. 6.

- [ ] **Step 4: Documentar v3 en `comparacion-versiones.md`.** Sección completa: "v3 — refinable partitions + O(m log n) split". Mencionar:
  - Las 8 diferencias del análisis del usuario, con check ✅ donde se cerraron.
  - Por qué la complejidad asintótica ahora sí es O(m log n) (citar §5.1).
  - Resultado del benchmark (delta sobre casos del corpus).

- [ ] **Step 5: Actualizar `planificacion-tesis.md`.** Marcar la implementación como "completa" en la sección de próximos pasos. Apuntar al snapshot v3 desde Cap. 5 (nota lateral en el archivo, no escribir el cap. todavía).

- [ ] **Step 6: Commit.**

```bash
git commit -m "docs: snapshot v3 final, benchmark, and scaling analysis"
```

---

## Fase D — SCC τ con Tarjan iterativo

**Contexto.** v2 implementa Kosaraju (`partitionIntoSCCWithTauLabels`, líneas 771-869): forward DFS para orden topológico, `buildReversedGraph` para construir el grafo τ-invertido, backward DFS sobre el invertido. Tres pasadas, una estructura de datos extra (`reversedGraph: Map<Long, Set<Long>>`).

**Objetivo.** Reemplazar por Tarjan iterativo: un solo DFS, emite SCCs a medida que termina cada uno, no hace falta el grafo invertido.

**Por qué.**
- **Memoria:** se elimina `reversedGraph` (O(m) extra para el grafo τ-invertido).
- **Tiempo constante:** un DFS en lugar de dos + un build del grafo invertido. Mismo asintótico O(V+E) pero con menor constante.
- **Concisión:** una sola rutina en vez de cuatro (`partitionIntoSCCWithTauLabels`, `forwardDFSWithTauLabels`, `backwardsDFSWithTauLabels`, `buildReversedGraph`).
- **Riesgo:** bajo. Tarjan es algoritmo de manual, y el Kosaraju que estamos reemplazando ya tuvo un bug en v0 (falta de `break`, ver `comparacion-versiones.md` punto 11) — menos código = menos superficie para bugs.

**Imperativo:** la implementación tiene que ser **iterativa**, no recursiva. Los LTSs del benchmark composicional pueden tener cadenas τ-conexas más largas que el stack default de la JVM (256k-1M frames). Una versión recursiva tira `StackOverflowError` en casos grandes.

### Task D.1: Capturar SCCs como fixture extra

**Files:**
- Modify: `versiones/golden/PartitionSignature.java`
- Modify: `versiones/golden/outputs/v2/<caseName>.json` (re-generar)

**Contexto.** El golden test actual compara `Pi_s` final. Eso ya cubre indirectamente correctitud de SCCs (si Tarjan computa mal una SCC, la partición final cambia). Pero un test directo de SCCs ayuda a localizar bugs cuando algo falla.

- [ ] **Step 1: Agregar firma de SCCs.**

```java
public static String sccsOf(List<Set<Long>> sccs) {
    // misma canonicalización que of() pero sobre la lista de SCCs
    return of(sccs);
}
```

- [ ] **Step 2: Modificar el runner de Task 0.2** para que también capture la firma de SCCs (exponer `partitionIntoSCCWithTauLabels` o agregar un getter al resultado de `getPartitions`). Guardar `sccs_signature` en cada `outputs/v2/<case>.json`.

- [ ] **Step 3: Re-correr Task 0.2** sobre v2 para regenerar fixtures con el campo nuevo.

- [ ] **Step 4: Modificar `GoldenTest.java`** para comparar `sccs_signature` además de `pi_s_signature`.

- [ ] **Step 5: Correr golden test contra v2** para validar que el comparador con SCCs anda.

```bash
java GoldenTest v2
# expected: N/N passed
```

- [ ] **Step 6: Commit.**

```bash
git add versiones/golden/
git commit -m "test: capture SCC signatures as part of golden test fixtures"
```

### Task D.2: Implementar Tarjan iterativo

**Files:**
- Modify: MTSA — `BranchingEquivalence.java`

- [ ] **Step 1: Agregar método nuevo `partitionIntoSCCWithTauLabelsTarjan`.** Lo dejamos paralelo al viejo durante el cutover.

```java
private static List<Set<Long>> partitionIntoSCCWithTauLabelsTarjan(
        MTS<Long, String> toMinimise, Set<String> tauLabels) {

    Set<Long> states = toMinimise.getStates();
    int n = states.size();

    // Densificación opcional para arrays. Si los IDs son densos, saltar.
    Map<Long, Integer> denseId = new HashMap<>(n);
    Long[] byId = new Long[n];
    int idx = 0;
    for (Long s : states) { denseId.put(s, idx); byId[idx] = s; idx++; }

    int[] index    = new int[n];   Arrays.fill(index, -1);
    int[] lowlink  = new int[n];
    boolean[] onStack = new boolean[n];
    Deque<Integer> tarjanStack = new ArrayDeque<>();
    List<Set<Long>> sccs = new ArrayList<>();
    int[] nextIndex = {0};

    // Pre-cachear hijos τ por nodo para evitar re-llamar getTransitions en cada DFS step
    int[][] tauChildren = new int[n][];
    for (int i = 0; i < n; i++) {
        Long s = byId[i];
        List<Integer> ch = new ArrayList<>();
        for (Pair<String, Long> t : toMinimise.getTransitions(s, MTS.TransitionType.REQUIRED)) {
            if (tauLabels.contains(t.getFirst())) {
                Integer di = denseId.get(t.getSecond());
                if (di != null) ch.add(di);
            }
        }
        tauChildren[i] = ch.stream().mapToInt(Integer::intValue).toArray();
    }

    int[] iterCursor = new int[n]; // posición actual en tauChildren[v] para cada v en el call stack
    Deque<Integer> callStack = new ArrayDeque<>();

    for (int root = 0; root < n; root++) {
        if (index[root] != -1) continue;

        // push root
        index[root] = nextIndex[0]; lowlink[root] = nextIndex[0]; nextIndex[0]++;
        tarjanStack.push(root); onStack[root] = true;
        callStack.push(root); iterCursor[root] = 0;

        while (!callStack.isEmpty()) {
            int v = callStack.peek();
            int[] children = tauChildren[v];
            if (iterCursor[v] < children.length) {
                int w = children[iterCursor[v]++];
                if (index[w] == -1) {
                    index[w] = nextIndex[0]; lowlink[w] = nextIndex[0]; nextIndex[0]++;
                    tarjanStack.push(w); onStack[w] = true;
                    callStack.push(w); iterCursor[w] = 0;
                } else if (onStack[w]) {
                    if (index[w] < lowlink[v]) lowlink[v] = index[w];
                }
            } else {
                // post-visit
                callStack.pop();
                if (lowlink[v] == index[v]) {
                    Set<Long> scc = new HashSet<>();
                    int w;
                    do {
                        w = tarjanStack.pop();
                        onStack[w] = false;
                        scc.add(byId[w]);
                    } while (w != v);
                    sccs.add(scc);
                }
                if (!callStack.isEmpty()) {
                    int parent = callStack.peek();
                    if (lowlink[v] < lowlink[parent]) lowlink[parent] = lowlink[v];
                }
            }
        }
    }
    return sccs;
}
```

- [ ] **Step 2: Test unitario con un caso conocido.** Un grafo τ chico con 2 SCCs no triviales: `{0→1→2→0, 1→3, 3→3}` con todas τ. Esperado: `[{0,1,2}, {3}]` (en algún orden). Comparar firmas canónicas.

- [ ] **Step 3: Test de stack-safety.** Cadena lineal τ de 1 millón de estados (`0 -τ→ 1 -τ→ ... -τ→ 1M`). El Tarjan iterativo debe terminar sin `StackOverflowError`. (Sanity check del "iterative" del título — el viejo `forwardDFSWithTauLabels` ya era iterativo en v2, pero conviene verificar el nuevo también.)

- [ ] **Step 4: Test cruzado contra Kosaraju.** Sobre cada caso del corpus golden:

```java
var kos = partitionIntoSCCWithTauLabels(mts, tauLabels);
var tar = partitionIntoSCCWithTauLabelsTarjan(mts, tauLabels);
assertEquals(PartitionSignature.sccsOf(kos), PartitionSignature.sccsOf(tar));
```

- [ ] **Step 5: Si el cross-check falla**, generar el caso mínimo y debuggear. Causas probables: (a) `tauChildren` no filtra correctamente (un edge no-τ se cuela); (b) la condición `onStack[w]` está mal cuando hay self-loops τ. NO seguir al Step 6 hasta que cross-check pase 100%.

- [ ] **Step 6: Commit.**

```bash
git commit -m "feat: implement iterative Tarjan SCC for tau-graph (alternative to Kosaraju)"
```

### Task D.3: Cutover y borrar Kosaraju

**Files:**
- Modify: MTSA — `BranchingEquivalence.java`

- [ ] **Step 1: Cambiar la única call site.** En `getPartitions` línea 184:

```java
// antes:
List<Set<Long>> toMinimiseSCC = partitionIntoSCCWithTauLabels(toMinimise, tauLabels);

// después:
List<Set<Long>> toMinimiseSCC = partitionIntoSCCWithTauLabelsTarjan(toMinimise, tauLabels);
```

- [ ] **Step 2: Golden test completo.**

```bash
java GoldenTest v3-D
# expected: N/N passed (incluyendo sccs_signature)
```

- [ ] **Step 3: Borrar las cuatro rutinas viejas.** Una vez que el golden pasa:
  - `partitionIntoSCCWithTauLabels` (líneas 771-799 en v2)
  - `forwardDFSWithTauLabels` (líneas 801-827)
  - `backwardsDFSWithTauLabels` (líneas 829-853)
  - `buildReversedGraph` (líneas 855-869)

  Renombrar `partitionIntoSCCWithTauLabelsTarjan` a `partitionIntoSCCWithTauLabels` (sin sufijo) para no dejar el nombre raro.

- [ ] **Step 4: Golden test otra vez** para verificar que el rename no rompió nada.

- [ ] **Step 5: Commit.**

```bash
git commit -m "refactor: replace Kosaraju with Tarjan for tau-SCC computation"
```

### Task D.4: Snapshot v3-D y benchmark

**Files:**
- Create: `versiones/BranchingEquivalence version 3-D.java` (o `version 3.java` si esta es la última fase ejecutada)
- Modify: `versiones/golden/benchmarks/v3-D.csv`
- Modify: `versiones/comparacion-versiones.md`

- [ ] **Step 1: Snapshot.**

```bash
cp <MTSA>/.../BranchingEquivalence.java "versiones/BranchingEquivalence version 3-D.java"
```

- [ ] **Step 2: Benchmark.** Esperable: la SCC step pasa a ser ~2x más rápida; sobre el total general el delta es chico (la SCC corre una vez al inicio, el bucle principal domina). En LTSs muy τ-densos el ahorro es más visible.

```bash
java Benchmark v3-D > versiones/golden/benchmarks/v3-D.csv
```

- [ ] **Step 3: Documentar en `comparacion-versiones.md`.** Sección "v3-D — Tarjan SCC". Mencionar:
  - Desaparece `reversedGraph` y los tres helpers DFS.
  - Una sola pasada en lugar de dos + build de grafo invertido.
  - Iterativo (sigue siendo necesario por profundidad de stack).

- [ ] **Step 4: Commit.**

```bash
git commit -m "docs: snapshot v3-D and document Tarjan SCC migration"
```

---

## Self-review

**Cobertura del análisis del usuario** (8 diferencias detectadas + 1 mejora extra):

| # | Item | Tarea(s) |
|---|---|---|
| 1 | Mark all → add sources to R (ya presente) | — (no se necesita tarea) |
| 2 | Skip validTNew al iterar Pi_t | A.1 |
| 3 | Skip splitters si Bottom(N) ⊆ Bottom(R) | A.2 |
| 4 | Refinable partition para estados | B.1, B.2, B.3, B.4 |
| 5 | Cuatro refinable partitions encadenadas para transiciones | C.1, C.2, C.3 |
| 6 | Action-block-slice grouping + O(1) peel | C.3 (steps 7-9) |
| 7 | Stability flag + lista de unstables | C.4, C.6 |
| 8 | untested[t] + coroutines lockstep en split | C.5 |
| extra | Tarjan iterativo en lugar de Kosaraju para SCCs τ | D.1, D.2, D.3 |

Todos los items quedan cubiertos.

**Riesgos del plan:**

1. **Task C.5 es el cuello del proyecto.** El split con coroutine + untested es el pedazo más sutil del paper (la prueba de complejidad depende de invariantes finos). Es razonable que tome 3-5x el tiempo nominal. Si se traba más de eso, opción de fallback: dejar v3 con Fase A + B + C.1-C.4 + C.6 (sin coroutine, usando el split de v2 adaptado a Block). Sigue siendo una mejora sustancial respecto de v2 aunque no cierre la complejidad O(m log n).

2. **Acoplamiento RefinablePartition ↔ LinkedTransitionPartitions.** El callback al splitOffR es la fuente más probable de bugs subtle. Mitigación: el design notes de Task C.4 step 5 + tests de invariante (un test que tras N operaciones random verifique que `posInBunch[t]`, `posInBlockBunch[t]`, etc., apuntan al lugar correcto y que `bunch.start ≤ blockBunch.start ≤ blockBunch.end ≤ bunch.end` siempre).

3. **Golden test corpus puede ser insuficiente.** Si los casos del benchmark del paper composicional no ejercitan los caminos nuevos (ej. cascada de τ no-inertes), un bug puede pasar desapercibido. Mitigación: agregar al corpus al menos 2 MTSs sintéticos diseñados específicamente para ejercitar (a) τ-loops grandes con SCCs no triviales, (b) cascadas de splits que disparan re-restabilización.

4. **El plan asume que tenés acceso al repo MTSA y lo podés modificar.** Si la edición es solo sobre los snapshots en `versiones/`, parte del flujo (compilar, integrar con tests de MTSA) no aplica. Verificar al inicio de Fase 0.

**Decisiones que el plan deja al implementador:**

- Lenguaje del callback en C.4: listener pattern explícito vs. polling. El plan sugiere listener; si encontrás que es más simple iterar `Pi_s.recentlyChangedBlocks()` desde Fase 1, está bien cambiar.
- Densificación de IDs en RefinablePartition (B.1 step 3): mapping Long→int vs. fastutil. El plan sugiere mapping; si MTSA ya depende de fastutil, usalo y ahorrás complejidad.
- Si la heurística de slice "any ≤ |T|/2" de v2 (vs. paper's first/last) afecta la calidad de la partición. **No debería** (la partición final es única), pero los timings sí. El plan elige first/last (paper) en C.3 step 7.

---

## Execution handoff

**Plan complete and saved to `~/Documents/Exactas/Tesis/docs/superpowers/plans/2026-05-06-branching-equivalence-v3-jansen-optimizations.md`.**

Dos opciones de ejecución:

**1. Subagent-Driven (recomendada para Fases B y C)** — fresh subagent por tarea, review entre cada una, iteración rápida. Buena para los rewrites grandes (B.4, C.5) donde el contexto se llena.

**2. Inline Execution** — ejecutar las tareas en esta sesión usando `executing-plans`, batch con checkpoints.

Para Fase A las dos andan bien (es chico). Para Fase 0 (golden tests) también — es independiente del resto.

**¿Qué enfoque usamos?**
