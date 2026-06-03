# `BranchingEquivalence` versión 0 — explicación del algoritmo

Documento de análisis de `versiones/BranchingEquivalence version 0.java`, mostrando
**cómo implementa el algoritmo del paper de Jansen** (`Papers/jansen.pdf`:
*"A simpler O(m log n) algorithm for branching bisimilarity on labelled transition
systems"*, Jansen, Groote, Keiren, Wijs, 2019).

El objetivo del código es **minimizar un MTS módulo bisimilaridad branching**:
agrupar los estados que son *branching bisimilares* en una sola clase y construir
el autómata cociente.

> **Qué es "versión 0".** Es la primera implementación, fiel al **algoritmo
> abstracto** del paper (Algoritmos 1 y 2). Usa estructuras de datos directas
> (`HashSet<Set<Long>>` para la partición, `Map` de estado→bloque, etc.) en lugar
> de las *refinable partitions* sobre arrays descritas en la Sección 5 del paper.
> Por eso es correcta pero **todavía no alcanza la cota O(m log n)**: prioriza
> claridad sobre las cotas de tiempo. Las versiones 1, 2 y 3 van introduciendo
> esas optimizaciones.

---

## 1. Conceptos del paper que hay que tener presentes

El algoritmo es un **refinamiento de particiones** que mantiene en paralelo dos
particiones (Sección 3.1 del paper):

| Símbolo | Nombre | En el código | Qué representa |
|---------|--------|--------------|----------------|
| Π_s | partición de **estados** en *bloques* (`B`) | `Pi_s : List<Set<Long>>` | Conocimiento actual sobre qué estados *no* son equivalentes. Al terminar, cada bloque = una clase de bisimilaridad branching. |
| Π_t | partición de **transiciones** no-inertes en *bunches* (`T`) | `Pi_t : List<Set<Triple>>` | Conocimiento actual sobre qué transiciones pueden actuar como *splitter*. |

Definiciones clave:

- **Transición inerte**: una `τ`-transición `s -τ-> s'` donde `s` y `s'` están en
  el mismo bloque. Es "invisible" porque no distingue estados.
- **Estado bottom** de un bloque `B`: estado sin `τ`-transiciones inertes
  salientes *dentro* de `B` (`Bottom(B)`).
- **Bunch trivial**: el que contiene una sola *action-block-slice* (mismas etiqueta
  y bloque destino). El objetivo final es que **todos** los bunches sean triviales.
- **Splitter**: una *block-bunch-slice* `T_{B→}` que aún puede dividir un bloque.
- **Invariante principal (Inv. 3.2)**: si un bunch `T` tiene una transición desde un
  bloque `B`, entonces **todo estado bottom de `B`** tiene una transición en ese mismo
  bunch. Cuando esto se rompe, hay que **partir** (split) el bloque para restaurarlo.

La idea de usar una partición de **transiciones** (bunches), tomada de Valmari, es
lo que da el O(m log n) más simple respecto al algoritmo previo de Groote–Vaandrager.

---

## 2. Mapa: funciones del código ↔ partes del paper

| Función Java | Parte del paper | Rol |
|--------------|-----------------|-----|
| `buildMinimisedMTS` | (post-proceso, no está en el paper) | Construye el MTS cociente a partir de la partición final Π_s. |
| `getPartitions` | **Algoritmo 1** completo (líneas 1.1–1.30) | Núcleo: refina Π_s y Π_t hasta estabilizar. Devuelve `(Π_s, Π_t)`. |
| `partitionIntoSCCWithTauLabels` | Línea 1.1 (contraer `τ`-SCCs) | Calcula las componentes fuertemente conexas vía `τ`. |
| `forwardDFSWithTauLabels` / `backwardsDFSWithTauLabels` / `buildReversedGraph` | Línea 1.1 (auxiliares) | DFS de Kosaraju (ida sobre el grafo `τ`, vuelta sobre el reverso). |
| `computeBvis` | Línea 1.2 (`B_vis`) | Estados desde los que se alcanza una acción visible inertemente. |
| `split` | **Algoritmo 2** (las dos corutinas) | Parte un bloque `B` en `R` (alcanza el splitter) y `U` (no). |
| `findSplittableBlocks` | `splittableBlocks(T_{a→B'})`, líneas 1.8–1.9 | Bloques con transiciones en *ambos* bunches nuevos. |
| `findBottomStates` | `Bottom(B)`, usado en líneas 1.26–1.27 | Estados bottom de un bloque. |
| `findNewNonInertTransitions` | `R -τ-> U`, líneas 1.20–1.21 / 1.25 | `τ`-transiciones que dejaron de ser inertes tras un split. |
| `updateStateToBlockMap` | (mantenimiento) | Actualiza el mapa estado→bloque tras un split. |
| `Splitter` (record externo) | concepto de *splitter* en la *splitter list* | Tupla `(bloque, transiciones, tipo PRIMARY/SECONDARY)`. |

---

## 3. `getPartitions` — el algoritmo general paso a paso

Esta función es el corazón. Sigue la estructura del **Algoritmo 1** del paper.

### 3.1. Preprocesamiento — contraer `τ`-SCCs (línea 1.1 del paper)

```java
List<Set<Long>> toMinimiseSCC = partitionIntoSCCWithTauLabels(toMinimise, tauLabels);
Map<Long, Set<Long>> stateToSCCMap = ...; // estado -> su SCC
```

El paper exige que **no haya ciclos de `τ`** (Invariante 3.7): los estados dentro de
una misma `τ`-SCC son trivialmente branching bisimilares, así que el paper los
**fusiona en un solo estado** antes de empezar. Esto garantiza que todo `τ`-camino
es finito y que desde cualquier estado se alcanza un estado bottom (Lema 3.8).

**Detalle de implementación de la versión 0:** en vez de reconstruir el grafo
fusionando físicamente los estados, esta versión **calcula las SCCs y guarda
`stateToSCCMap`**, y luego, en cada punto donde importa, **ignora** las
`τ`-transiciones internas a una misma SCC con la comprobación
`stateToSCCMap.get(s) != stateToSCCMap.get(t)`. Es decir: simula la contracción.
Esa comprobación aparece en `split` (línea 421) y en `findBottomStates` (línea 474).

`partitionIntoSCCWithTauLabels` es **Kosaraju** clásico:

1. `forwardDFSWithTauLabels`: DFS sobre el subgrafo de `τ`-transiciones, apilando los
   estados en orden de finalización (`SCCorder`).
2. `buildReversedGraph`: construye el grafo `τ` invertido.
3. `backwardsDFSWithTauLabels`: desapila en orden inverso y hace DFS sobre el reverso;
   cada árbol resultante es una SCC.

(Ambos DFS están escritos en forma iterativa con `Stack` para evitar desbordar la
pila de recursión en grafos grandes.)

### 3.2. Particiones iniciales (líneas 1.2–1.4 del paper)

```java
Set<Long> Bvis   = computeBvis(...);              // línea 1.2: B_vis
Set<Long> Binvis = todos los estados \ Bvis;       // línea 1.2: B_invis
Pi_s = [Bvis, Binvis] (los no vacíos);             // línea 1.3: Π_s := {B_vis, B_invis}
```

**`computeBvis`** implementa `B_vis = { s | s puede alcanzar inertemente una acción
visible }` de forma eficiente (líneas 560–636):

1. Mapea cada SCC a un id entero (para no hashear `Set`s).
2. Marca como **inicialmente visibles** las SCCs que tienen *alguna* transición con
   etiqueta visible (no-`τ`).
3. Construye el **grafo de predecesores** (reverso) entre SCCs siguiendo las
   `τ`-transiciones *entre* SCCs distintas.
4. Hace **propagación hacia atrás** (BFS/DFS): si una SCC es visible, todos sus
   predecesores `τ` también lo son (pueden alcanzar lo visible inertemente).
5. `Bvis` = unión de los estados de todas las SCCs marcadas visibles.

`B_invis` es el resto: estados desde los que solo se alcanzan deadlocks.

La distinción inicial es válida porque un estado visible y uno invisible **no pueden**
ser branching bisimilares (Inv. 3.6).

**Bunch inicial** (línea 1.4): un único bunch con **todas las transiciones
no-inertes**:

```java
for cada transición s -a-> s':
    if a es visible:                     initialBunch.add((s,a,s'))   // no-inerte por ser visible
    else if Bvis.contains(s) && Binvis.contains(s'): initialBunch.add(...) // τ entre bloques distintos
Pi_t = [initialBunch];
```

### 3.3. Estructuras de trabajo

```java
Pi_t_cola      : Deque<bunch>     // bunches pendientes de revisar (lazo externo)
splitterList   : Deque<Splitter>  // la "splitter list" del paper (lazo interno)
markings       : Map<Splitter, Set<Triple>>  // Marked(T): transiciones marcadas
stateToBlockMap: Map<Long, Set<Long>>         // estado -> bloque actual
```

`markings` es la implementación del concepto **Marked(T_{B→})** del paper: las
transiciones marcadas permiten saber en tiempo constante si un estado bottom tiene
transición en un splitter (Inv. 3.11), sin recorrer transiciones no marcadas.

### 3.4. Lazo externo: partir bunches no triviales (líneas 1.5–1.7)

```java
while (!Pi_t_cola.isEmpty()) {
    bunch = Pi_t_cola.pop();
    // agrupar las transiciones del bunch en "slices" por (acción, bloque destino)
    slices = group bunch by (action, targetBlock);
    if (slices.size() <= 1) continue;   // línea 1.5: bunch trivial -> nada que hacer
```

Esto corresponde a la guarda de la línea 1.5 (`#aB'(T) > 1`): solo se procesan
bunches **no triviales** (con más de una action-block-slice).

**Selección de la slice a separar (línea 1.6):** se elige una acción `a` y un bloque
destino `B'` tal que la slice `T_{a→B'}` tenga `≤ |T|/2` transiciones — el principio
**"process the smaller half"** de Hopcroft, esencial para el O(m log n):

```java
for (slice : slices) if (slice.size() <= bunch.size()/2) { chosen = slice; break; }
if (chosen == null) chosen = primera slice no vacía;  // fallback de seguridad
```

**Separar el bunch (línea 1.7):** `Π_t := (Π_t \ {T}) ∪ {T_{a→B'}, T \ T_{a→B'}}`.
La slice elegida (`chosenTransitions`) se llama **splitter primario** y el resto
(`newBunch`) **splitter secundario**:

```java
Pi_t.remove(bunch);
newBunch = bunch \ chosenTransitions;
Pi_t_cola.push(newBunch); Pi_t_cola.push(chosenTransitions);
Pi_t.add(newBunch); Pi_t.add(chosenTransitions);
```

### 3.5. Preparar splitters y marcas (líneas 1.8–1.12)

Para cada bloque que quedó "partible" (`findSplittableBlocks`) se crean **dos
splitters** (primario y secundario) y se inicializan sus marcas:

```java
for (block : findSplittableBlocks(chosenTransitions, newBunch, stateToBlockMap)) {
    primarySlice   = transiciones de chosenTransitions que salen de block;
    secondarySlice = transiciones de newBunch que salen de block;

    splitterList.addLast(primarySplitter);    // línea 1.9: primero el primario...
    splitterList.addLast(secondarySplitter);  //            ...después el secundario

    markings.put(primary, copia de primarySlice);   // línea 1.10: marcar TODA la slice primaria
    // línea 1.11: por cada estado origen de una transición primaria que también
    //             tenga transición secundaria, marcar UNA secundaria
    for (state : origenes de primarySlice)
        if (state tiene secundarias) secondaryMarks.add(una de ellas);
    markings.put(secondary, secondaryMarks);
}
```

- **`findSplittableBlocks`** implementa `splittableBlocks(T_{a→B'})` (líneas 532–559):
  un bloque es partible si tiene transiciones en **ambos** bunches nuevos (∅ ⊂
  T_{B,a→B'} ⊂ T_{B→}). Solo esos bloques pueden volverse inestables; el resto sigue
  estable y no hace falta tocarlo.
- El **orden primario→secundario** y las **marcas** son exactamente las líneas
  1.9–1.11 del paper.

### 3.6. Lazo interno: estabilizar partiendo bloques (líneas 1.13–1.29)

```java
while (!splitterList.isEmpty()) {
    currentSplitter = splitterList.removeFirst();   // línea 1.13
    B = currentSplitter.sourceBlock();
    if (!Pi_s.contains(B)) continue;  // el bloque ya fue refinado por otro splitter -> obsoleto

    (R, U) = split(B, currentSplitter.transitions(), markings.get(currentSplitter), ...); // línea 1.14
    if (R.isEmpty() || U.isEmpty()) continue;   // no hubo split real

    Pi_s.remove(B); Pi_s.add(R); Pi_s.add(U);   // línea 1.16: Π_s := (Π_s\{B}) ∪ {R,U}
    updateStateToBlockMap(stateToBlockMap, R, U);
```

`R` = estados de `B` que **pueden** alcanzar inertemente una transición del splitter;
`U` = los que **no**. Ver `split` (§4).

**Optimización del split primario (líneas 1.17–1.18):** si el splitter actual era
**primario**, su secundario asociado (que debería ser el siguiente en la cola) se
puede simplificar: `U` ya es estable respecto al bunch secundario, así que solo hay
que volver a encolar la parte que afecta a `R`:

```java
if (currentSplitter.type() == PRIMARY) {
    secondaryForB = splitterList.peekFirst();
    if (es el secundario de B) {
        splitterList.removeFirst();               // la tarea original para B ya no vale
        secondarySliceForR = secundarias que salen de R;
        if (!vacío) { encolar nuevo secundario para R; reajustar markings; } // línea 1.18 (3-way split de Paige–Tarjan)
    }
}
```

**Transiciones que dejan de ser inertes (líneas 1.20–1.27):** al partir `B` en `R` y
`U`, las `τ`-transiciones `R -τ-> U` que antes eran inertes (dentro de `B`) ahora
cruzan bloques: **se vuelven no-inertes**.

```java
newNonInert_R_to_U = findNewNonInertTransitions(R, U, ...);   // R -τ-> U
if (!newNonInert_R_to_U.isEmpty()) {
    Pi_t.add(newNonInert_R_to_U);                  // línea 1.21: nuevo bunch con R-τ->U, todas marcadas
    (N, R') = split(R, newNonInert_R_to_U, marcas, ...);   // línea 1.22: re-estabilizar R
    if (N y R' no vacíos) {
        Pi_s.remove(R); Pi_s.add(N); Pi_s.add(R');  // línea 1.24
        updateStateToBlockMap(...);
        // línea 1.25: añadir N-τ->R' al mismo bunch
        newNonInert_R_to_U.addAll(findNewNonInertTransitions(N, R', ...));
    }
    if (!N.isEmpty()) {
        bottomStatesOfN = findBottomStates(N, ...);    // nuevos estados bottom
        for (bunch b : Pi_t) {                          // líneas 1.26–1.27
            sliceFromN = transiciones de b que salen de N;
            if (vacío) continue;
            splitterList.addLast(new Splitter(N, sliceFromN, SECONDARY));  // re-estabilizar N
            // marcar UNA transición saliente por cada estado bottom de N
            for (bottom : bottomStatesOfN) if (tiene en sliceFromN) marcar una;
        }
    }
}
```

El razonamiento del paper (Sección 3.3, Fig. 1d):

- `R` es el único bloque que puede haberse vuelto inestable respecto al nuevo bunch
  `R -τ-> U`, por eso se re-parte con `split(R, ...)` → `(N, R')`.
- `N` contiene **nuevos estados bottom**. Según Groote–Vaandrager, la estabilidad no
  se preserva cuando aparecen estados bottom nuevos, así que hay que **re-estabilizar
  `N` respecto a todos los bunches** donde tenga transiciones salientes (líneas
  1.26–1.27). Por eso se encolan splitters secundarios para `N` marcando una
  transición por estado bottom.

Cuando ambos lazos terminan, todos los bunches son triviales ⇒ cada bloque de `Π_s`
es una clase de equivalencia branching bisimilar (Teorema 3.10). Se devuelve
`(Pi_s, Pi_t)`.

---

## 4. `split` — refinar un bloque bajo un splitter (Algoritmo 2)

```java
Pair<Set<Long>,Set<Long>> split(B, transitions, currentMarks, toMinimise, tauLabels, stateToSCCMap)
```

Implementa el `split(B, T)` del paper (Ecuación (1) y Algoritmo 2). Devuelve `(R, U)`:

- **R** = `{ s ∈ B | s puede alcanzar inertemente una transición del splitter }`
- **U** = `B \ R`

> **Nota importante.** El paper especifica `split` como **dos corutinas en lockstep**
> (columnas izquierda=U y derecha=R del Algoritmo 2) que se ejecutan en paralelo y se
> abortan mutuamente en cuanto una termina, para garantizar que el costo se imputa al
> **subbloque más chico** (clave del O(m log n)). **La versión 0 NO hace lockstep**:
> calcula `R` directamente con alcanzabilidad hacia atrás y define `U = B \ R`. Es
> correcto pero pierde la cota de complejidad fina. Esa corutina es lo que las
> versiones posteriores introducen.

Cómo calcula `R`:

```java
// línea 2.2: estados con transición MARCADA saliente entran a R
for (t : currentMarks) R.add(t.getFirst());
// y los orígenes de las transiciones del splitter
for (t : transitions) R.add(t.getFirst());

// construir predecesores inertes DENTRO de B (ignorando los de la misma SCC)
for (s : B) for (s -τ-> t con t en B):
    if (stateToSCCMap.get(s) != stateToSCCMap.get(t))   // <- contracción de SCCs simulada
        inertPredecessorsInB[t].add(s);

// alcanzabilidad hacia atrás por transiciones inertes (worklist/BFS)
while (worklist no vacía) {
    s = pop;
    for (pred : inertPredecessorsInB[s]) if (R.add(pred)) worklist.add(pred);
}

U = B \ R;
return (R, U);
```

Esto es la **"backward reachability along inert transitions"** del paper: un estado
llega a `R` si tiene una transición marcada/del splitter, o si por `τ`-transiciones
inertes puede llegar a otro estado de `R`.

> Comentario en el código (línea 415): hay un `break` por `R.size() > B.size()/2`
> **desactivado** (`NO ANDA`). En el paper ese corte es justamente lo que limita el
> trabajo a la mitad más chica; al desactivarlo, la versión 0 recorre `R` completo.

---

## 5. Funciones auxiliares del refinamiento

### `findBottomStates(N, ...)` — `Bottom(B)` (líneas 454–488)

Un estado `s` es **bottom** si no tiene `τ`-transiciones inertes salientes que se
queden dentro del bloque (y, de nuevo, ignorando las que van a la misma SCC):

```java
for (s : N):
    si NO existe (s -τ-> dst) con dst en N y stateToSCCMap.get(s)!=stateToSCCMap.get(dst):
        bottomStates.add(s);
```

Se usa en las líneas 1.26–1.27 para marcar una transición por cada nuevo estado
bottom de `N`.

### `findNewNonInertTransitions(R, U, ...)` — `R -τ-> U` (líneas 490–513)

Encuentra las `τ`-transiciones que iban de `R` a `U` y que, tras separar el bloque,
**dejaron de ser inertes** (porque ahora `R` y `U` son bloques distintos):

```java
for (s : R) for (s -τ-> dst): if (dst en U) result.add((s, τ, dst));
```

Corresponde a las líneas 1.20–1.21 y 1.25.

### `findSplittableBlocks(chosen, remaining, map)` — `splittableBlocks` (líneas 532–559)

Devuelve los bloques que tienen transiciones en **ambos** bunches resultantes (el
primario `chosen` y el secundario `remaining`); solo esos pueden volverse inestables.

### `updateStateToBlockMap` / `Splitter`

- `updateStateToBlockMap`: tras un split, reapunta cada estado de `R` y `U` a su
  nuevo bloque.
- `Splitter` (record en el paquete): `(sourceBlock, transitions, type)` con
  `type ∈ {PRIMARY, SECONDARY}`. En la versión 0 las **marcas** no van dentro del
  record sino en el `Map<Splitter, Set<Triple>> markings`.

---

## 6. `buildMinimisedMTS` — construir el cociente (no está en el paper)

Una vez que `getPartitions` devuelve `Π_s` (cada bloque = una clase), esta función
arma el MTS minimizado:

1. Crea **un estado nuevo por bloque** (`blockToNewStateId`) y mapea cada estado viejo
   a su id nuevo (`oldStateToNewStateId`). Fija el estado inicial nuevo como el bloque
   que contenía al inicial viejo.
2. Recorre las transiciones originales y las **proyecta** al cociente:

```java
for (block : pi_s) for (oldFrom : block) for (oldFrom -a-> oldTo):
    newTo = oldStateToNewStateId.get(oldTo);
    if (newTo != null)
        if (newFrom != newTo  ||  a no es τ)
            result.addRequired(newFrom, a, newTo);     // transición entre clases distintas, o visible
        else
            // τ-loop sobre la misma clase: se renombra c_<a> y se registra en translatorControllable
            result.addRequired(newFrom, a, newTo);
```

La rama `else` maneja las `τ`-transiciones que quedan **dentro del mismo bloque** (un
auto-loop en el cociente): las traduce a una etiqueta controlable `c_<a>` registrada
en `translatorControllable`. Esto es específico del contexto **DCS / síntesis de
controladores** donde vive este código (`...DCS.Compositional`), no del paper de
Jansen, que solo minimiza el LTS. (El bloque comentado de las líneas 71–85 muestra la
versión "de referencia" equivalente con `addTransition`.)

---

## 7. Resumen del flujo

```
buildMinimisedMTS
└─ getPartitions                                   (Algoritmo 1)
   ├─ partitionIntoSCCWithTauLabels                (línea 1.1: contraer τ-SCCs)
   │   └─ forward/backwardDFS + buildReversedGraph (Kosaraju)
   ├─ computeBvis                                  (línea 1.2: B_vis / B_invis)
   ├─ inicializar Π_s y bunch inicial              (líneas 1.3–1.4)
   └─ while bunch no trivial                       (líneas 1.5–1.30)
      ├─ elegir slice ≤ |T|/2 y partir el bunch    (líneas 1.6–1.7)
      ├─ findSplittableBlocks + crear splitters    (líneas 1.8–1.12)
      └─ while splitterList no vacía               (líneas 1.13–1.29)
         ├─ split(B, ...) -> (R,U)                 (línea 1.14, Algoritmo 2)
         ├─ Π_s := Π_s\{B} ∪ {R,U}                 (línea 1.16)
         ├─ optimización split primario            (líneas 1.17–1.18)
         └─ findNewNonInertTransitions R-τ->U       (líneas 1.20–1.27)
            ├─ nuevo bunch + split(R) -> (N,R')
            ├─ findBottomStates(N)
            └─ re-estabilizar N en todos los bunches
└─ proyectar transiciones al cociente              (post-proceso, fuera del paper)
```

## 8. Diferencias clave entre la "versión 0" y el paper

1. **SCCs no se fusionan físicamente**: se calculan y se simulan con `stateToSCCMap`
   + chequeo `SCC(s) != SCC(t)` donde el paper asumiría estados ya contraídos.
2. **`split` no usa corutinas en lockstep**: calcula `R` por alcanzabilidad hacia
   atrás y hace `U = B \ R`, sin el aborto mutuo que imputa el costo al lado más chico.
   → se pierde la cota fina O(m log n).
3. **Corte "process the smaller half" desactivado dentro de `split`** (comentario
   `NO ANDA` en la línea 415).
4. **Estructuras de datos directas** (`Set`, `Map`, `Deque`) en lugar de las
   *refinable partitions* sobre arrays de la Sección 5 → más claro, menos eficiente.
5. **`buildMinimisedMTS` agrega lógica DCS** (traducción de `τ`-loops a etiquetas
   controlables `c_<a>`) que no forma parte del algoritmo de bisimilaridad.
