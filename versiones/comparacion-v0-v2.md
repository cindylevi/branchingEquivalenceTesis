# Comparación `v0` → `v2` de `BranchingEquivalence`

Dos iteraciones del mismo algoritmo (Jansen, Groote, Keiren, Wijs, 2019) para
minimizar un MTS módulo *branching bisimilarity*. **v0** es la traducción
literal del pseudocódigo del paper; **v2** es la versión que efectivamente se
integra en el flujo de síntesis composicional de MTSA. Este documento ignora
deliberadamente las versiones intermedias: presenta sólo el punto de partida
(v0) y el punto de llegada (v2), las motivaciones que llevaron de uno al otro,
las diferencias concretas, y un recorrido detallado del funcionamiento de v2.

> Nota metodológica: en el repositorio, el archivo `BranchingEquivalence
> version 2.java` y `BranchingEquivalenceV2.java` son **idénticos salvo el
> nombre de la clase** (el segundo se renombró para que las versiones puedan
> coexistir en una misma campaña de benchmarking). Cuando este documento dice
> "v2" se refiere a ese código.

---

## 1. Idea general del algoritmo (común a las dos versiones)

El algoritmo refina **simultáneamente** dos particiones hasta llegar a un punto
fijo:

- una **partición de estados** `Pi_s`, que arranca con dos bloques —`Bvis` y
  `Binvis`— separando los estados que pueden alcanzar acciones visibles de los
  que no pueden;
- una **partición de transiciones** `Pi_t` en *bunches*, que arranca con un
  único bunch: todas las transiciones no-inertes.

En cada paso busca **inestabilidades** entre `Pi_s` y `Pi_t` (un bloque cuyas
transiciones no se comportan de manera uniforme respecto de algún bunch) y parte
bloques o bunches. Cuando ya nada se puede partir, los bloques de `Pi_s` son las
clases de equivalencia y con ellas se construye el MTS minimizado.

Para tratar las transiciones **τ-inertes** —las que viven dentro de un mismo
bloque y deben ignorarse— antes del bucle principal se computan las **SCCs
τ-conexas** (componentes fuertemente conexas considerando sólo aristas τ) y se
mantiene un mapa `stateToSCCMap`. Dos estados en la misma SCC τ son siempre
branching-equivalentes; el mapa permite distinguir τ-inerte (intra-SCC) de τ
no-inerte (inter-SCC) en tiempo constante.

### Relación con el pseudocódigo del paper

El paper de referencia es **Jansen, Groote, Keiren, Wijs (2019), "A simpler
O(m log n) algorithm for branching bisimilarity on labelled transition
systems"** (`Papers/jansen.pdf`). Su Algorithm 1 (página 8) es lo que ambas
versiones implementan. Reproducido en forma resumida:

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

**v0 es la traducción literal de este pseudocódigo.** Sus comentarios lo
explicitan: hay líneas marcadas `// Línea 1.21`, `// Línea 1.25`, `// Línea
1.26`, `// Línea 1.27` que referencian directamente la numeración del Algorithm
1. v0 conserva el anidamiento outer-while (sobre bunches) + inner-for (sobre
splitters), con la restabilización por τ no-inertes (líneas 1.20–1.28) escrita
*inline* dentro del bucle interno.

**v2 reorganiza el mismo algoritmo** en dos fases explícitas que se alternan,
corrige varios bugs latentes de la traducción literal, y agrega las adaptaciones
necesarias para funcionar dentro del DCS de MTSA. No cambia la complejidad
asintótica O(m log n): es el mismo algoritmo con otra estructura de código.

---

## 2. Por qué v2: las motivaciones

Las motivaciones se agrupan en cuatro ejes: **corrección**,
**robustez/claridad**, **integración con el dominio** y **performance**.

### 2.1. Corrección: bugs de la traducción literal

v0 funciona como descripción del algoritmo, pero arrastra errores que sólo se
manifiestan al correrlo sobre MTSs reales del flujo composicional:

- **`split` no respeta la atomicidad de las SCCs.** v0 inicializa `R` con
  estados sueltos (los orígenes de las transiciones marcadas) y propaga hacia
  atrás estado por estado. Pero una SCC τ-conexa es atómica: sus estados son
  siempre branching-equivalentes, así que **deben moverse juntos** entre `R` y
  `U`. v0 puede dejar dos estados de la misma SCC repartidos en bloques
  distintos, lo cual es semánticamente incorrecto.

- **`equals`/`hashCode` peligrosos sobre bunches mutables.** v0 usa
  `ArrayDeque<Set<Triple>>` como cola de bunches y **muta** los bunches ya
  encolados (`addAll`, `removeAll`). El `hashCode` de un `Set` depende de su
  contenido, de modo que después de mutar un bunch encolado, `cola.contains(...)`
  puede dar falsos negativos. Es un bug latente que sólo se vuelve visible
  cuando se separan las fases del algoritmo.

- **DFS de Tarjan mal formado.** Los DFS forward/backward de v0 que computan las
  SCCs no cortan (`break`) después de empujar un hijo no visitado, de modo que
  siguen mirando hermanos en el mismo nivel antes de descender. No es un DFS
  iterativo bien formado.

- **El estado de error no recibe tratamiento.** En el contexto de DCS, el estado
  `-1L` representa el error y no es equivalente a ningún otro estado. v0 lo deja
  mezclado dentro de `Bvis` o `Binvis`, lo cual es incorrecto.

### 2.2. Robustez y claridad: invariantes verificables

El paper ya tiene **dos bucles anidados** (outer while + inner for): no es un
bucle plano. Lo que hace v2 es **invertir el anidamiento** en dos fases que se
alternan al mismo nivel:

| | Paper / v0 | v2 |
|---|---|---|
| Estructura | outer (bunches) + inner (splitters) anidados | dos fases que se alternan al mismo nivel |
| Orden | un bunch → drenar TODOS sus splitters → próximo bunch | drenar todos los splitters pendientes → un bunch → drenar splitters nuevos → ... |

Esta separación **no es una optimización asintótica** (la complejidad no
cambia). Mejora cuatro cosas:

1. **Invariantes verificables.** En la forma del paper, cuando dentro del inner
   for aparecen τ no-inertes nuevas (línea 1.21) se crea simultáneamente un
   bunch nuevo y un splitter nuevo y se sigue dentro del mismo for; distinguir
   "estoy procesando splitters del bunch original" de "estoy procesando
   splitters de un bunch recién creado" se vuelve borroso. Separar en fases da
   invariantes nítidos:
   - Fin de Fase 1: `Pi_s` estable respecto de **todos** los splitters pendientes.
   - Fin de Fase 2: un bunch fue partido; los splitters nuevos quedan en `splitterList`.

2. **Re-encolado correcto al partir un bloque.** Cuando un split parte `B` en
   `R` y `U`, todos los splitters pendientes que apuntaban a `B` quedan
   obsoletos: hay que generar uno para `R` y otro para `U`. El paper lo discute
   informalmente; v0 lo hace a medias (con un `peekFirst()` ad-hoc frágil). v2
   lo extrae a `refineSplitters` y lo aplica sistemáticamente.

3. **Bugs de mutación.** La separación en fases es justamente lo que hace
   aflorar el problema de `equals`/`hashCode` descrito arriba; v2 lo resuelve de
   raíz con `IdentityQueue` (pertenencia por identidad de referencia).

4. **Instrumentación y composición.** Tener fases nombradas permite medir
   `tPhase1` vs. `tPhase2`, contar iteraciones, y agregar adaptaciones de
   dominio (bloque de error, filtrado de initiating actions) en lugares
   semánticamente correctos.

### 2.3. Integración con el dominio (DCS de MTSA)

Estas necesidades no existen en el paper y aparecen sólo al integrar el
algoritmo en la síntesis de controladores:

- **Fluents.** Una *initiating action* de un fluent cambia el valor de ese
  fluent y por lo tanto **distingue estados** desde la lógica del sistema. Si se
  la trata como τ (invisible), el algoritmo colapsa estados que el modelo
  considera distintos. v2 saca las initiating actions de `tauLabels` antes del
  bucle.

- **Reuso de partición precomputada.** Para benchmarking e integración hace
  falta poder construir el MTS minimizado a partir de una partición ya
  calculada, sin recomputarla. v2 agrega `buildMinimisedMTSFromPartition`.

### 2.4. Performance: hashing de bloques

Esta motivación no es de dominio (aplica a cualquier LTS, no sólo al DCS) ni de
corrección: es puramente de eficiencia. El `hashCode` de un `Set<Long>` es O(n)
—suma los hashes de todos sus elementos—, así que en la Fase 2, indexar las
slices por `(acción, bloque)` hace que ese costo domine cuando los bloques son
grandes. v2 asigna un `int` a cada bloque (`blockIdMap`) e indexa por
`(acción, blockId)`, llevando el hash a O(1). El `blockIdMap` se mantiene
sincronizado en cada split (`remove(B)` + `put(R)` + `put(U)`).

---

## 3. Diferencias concretas v0 → v2

### 3.1. Resumen

| | v0 | v2 |
|---|---|---|
| Bucle principal | un único `while` sobre la cola de bunches con todo entremezclado | dos fases explícitas que se alternan (estabilizar estados / refinar bunches) |
| `Splitter` | clase externa, `enum PRIMARY/SECONDARY`, marcas en un `Map` aparte | clase interna estática, `boolean isPrimary` + `groupId`, marcas como campo del splitter |
| `Pi_t` (partición de transiciones) | `List<Set<Triple>>` | `Set<Set<Triple>>` con `IdentityHashMap` |
| Cola de bunches | `ArrayDeque` (pertenencia por valor) | `IdentityQueue` (pertenencia por referencia) |
| `split` | inicializa `R` con estados sueltos | inicializa `R` con **SCCs enteras** |
| Bottom states | itera estado por estado | itera **por SCC** |
| Bloque de error `-1L` | sin tratamiento (queda en `Bvis`/`Binvis`) | bloque inicial propio en `Pi_s` |
| Restabilización por τ no-inertes | inline, ~80 líneas dentro del while | abstraída en `refineSplitters` + `targetStateToBunches` + cola `newFrontiers` |
| Cascada de τ nuevas | inline en el bucle | cola local `newFrontiers` drenada hasta el punto fijo (`U→R` se siembra de forma defensiva: da vacío por el cierre hacia atrás) |
| Re-encolado de bunches | no aplica (re-itera todo `Pi_t`) | `targetStateToBunches` + `enqueueAffectedBunches` (sólo afectados) |
| Hashing de slices (Fase 2) | `Pair<String, Set<Long>>` (hash O(n)) | `Pair<String, Integer>` con `blockIdMap` (hash O(1)) |
| Fluents (initiating actions) | no contempla | las saca de `tauLabels` antes del bucle |
| Tarjan | sin `break` tras empujar hijo (mal formado) | con `break` (DFS iterativo correcto) |
| Instrumentación | no | `Map<String, Double>` con `System.nanoTime()` por fase |
| API | sólo `buildMinimisedMTS` | + `buildMinimisedMTSFromPartition` |
| Firma de `getPartitions` | `(toMinimise, tauLabels)` | `(toMinimise, tauLabels, totalTranslator, fluents)` |

### 3.2. Estructuras de datos

| Decisión | Paper dice | v0 | v2 |
|---|---|---|---|
| Tipo de `Πt` | "set of bunches" | `List<Set<Triple>>` | `Set<Set<Triple>>` con `IdentityHashMap` |
| Cola de bunches a refinar | nada (el while externo itera sobre Πt) | `ArrayDeque` | `IdentityQueue` (identidad por referencia) |
| Tipo de splitter list | "list" (el orden importa) | `Deque<Splitter>` | `Deque<Splitter>` |
| Marcas | "almacenadas separadamente en el block-bunch-slice" | `Map<Splitter, Set<Triple>>` aparte | campo `marks` dentro del `Splitter` |
| Lookup estado → bloque | nada | `Map<Long, Set<Long>>` | igual |
| Lookup estado destino → bunches | nada | no existe | `targetStateToBunches: Map<Long, Set<Set<Triple>>>` |
| Identificador de bloque | nada | el `Set<Long>` mismo | `IdentityHashMap<Set<Long>, Integer>` (`blockIdMap`) |
| Emparejar primary/secondary | nada (asume orden secuencial) | adyacencia en `Deque` (frágil) | `groupId: long` en cada `Splitter` |

### 3.3. SCCs τ-conexas

El paper (línea 1.1) dice **"contraer cada SCC τ a un único estado"**. Ambas
versiones evitan la contracción real (cara, requiere reescribir el MTS) y simulan
el efecto con `stateToSCCMap`. La diferencia es que v0 no respeta la atomicidad
de la SCC y v2 sí:

| Decisión | Paper | v0 | v2 |
|---|---|---|---|
| Contracción real de SCCs | sí | no, `stateToSCCMap` | no, `stateToSCCMap` |
| `R` arranca con... | estados con marcas (ya contraídos) | estados sueltos con marca | **SCCs enteras** del origen de cada marca |
| Propagación backward | predecesores τ | predecesores τ sueltos | predecesores τ, expandiendo por SCC entera |
| Bottom states | estados sin τ saliente que quede en el bloque | itera estado por estado | itera **por SCC**: una SCC es bottom si ninguno de sus estados tiene τ saliente hacia otra SCC del mismo bloque |

### 3.4. Restabilización por τ no-inertes nuevas (líneas 1.20–1.28)

| Decisión | Paper | v0 | v2 |
|---|---|---|---|
| Dirección de las τ nuevas | sólo `R-τ→U` (argumenta que `U-τ→R` no puede existir) | sólo `R-τ→U` | `(R,U)` **y** `(U,R)` (esta última, defensiva: da vacío por el cierre hacia atrás) |
| Estructura de la cascada | inline en el bucle | inline (≈80 líneas, re-disparada vía `splitterList`) | cola local `newFrontiers`, drenada hasta vaciar |
| Realimentación tras partir `R` en `N` y `R'` | agregar `N-τ→R'` al bunch ya creado | exactamente eso, inline | `newFrontiers` se realimenta con `(N,R')`, `(R',N)`, `(N,tgt)`, `(R',tgt)`, con guarda de vigencia |
| Marcas en restabilización | una transición saliente por bottom nuevo | primera transición de `secondaryBySource.get(state)` | igual + `findBottomStates` por SCC |

Por el invariante del cierre hacia atrás de `split`, **no puede haber τ inertes de
`U` hacia `R`**, así que la dirección `U→R` siempre da vacío; tanto v0 como v2 son
correctas al respecto. La diferencia real no es de dirección sino de
**estructura**: v2 reemplaza la restabilización inline de v0 por una cola
(`newFrontiers`) que expresa la cascada de forma uniforme, iterativa y fácil de
auditar (ver §4.4, mecanismo (c), para el detalle del procesamiento).

### 3.5. Adaptaciones al dominio (no están en el paper)

| Decisión | v0 | v2 |
|---|---|---|
| Estado de error `-1L` separado | no se trata | bloque inicial propio en `Pi_s` |
| Self-loop τ en el MTS minimizado | se renombra a `c_<acción>` y se registra en `translatorControllable` | igual |
| Initiating actions de fluents | no se contempla | se sacan de `tauLabels` antes del bucle |
| API para reusar partición precomputada | sólo `buildMinimisedMTS` | + `buildMinimisedMTSFromPartition` |

El punto de los fluents es el que más cambia la semántica: tratar una initiating
action como τ haría que el algoritmo colapse estados que el modelo considera
distintos. Es un parche fuera del paper, propio de la integración con MTSA.

### 3.6. Construcción del MTS minimizado (no aparece en el paper)

El Algorithm 1 sólo computa la partición; **construir el MTS resultante no es
parte del algoritmo**. Ambas versiones lo hacen igual:

1. Asignar un ID nuevo a cada bloque.
2. Mapear el estado inicial al bloque que lo contiene.
3. Por cada transición `s -a→ s'` del original, crear `bloque(s) -a→ bloque(s')`
   en el resultado. Si es self-loop con `a ∈ tauLabels`, renombrarla a `c_a` y
   registrarla en `translatorControllable` (truco específico de DCS para
   preservar acciones controlables que de otro modo se perderían).

---

## 4. Cómo funciona v2 en detalle

Esta sección recorre v2 de arriba a abajo: preprocesamiento, estructuras,
inicialización, el bucle de dos fases, y los procedimientos auxiliares.

### 4.1. Punto de entrada y preprocesamiento

`buildMinimisedMTS(toMinimise, tauLabels, translatorControllable,
totalTranslator, fluents)` orquesta todo: llama a `getPartitions` para obtener
`Pi_s` y luego construye el MTS resultante a partir de los bloques (sección 3.6).
`buildMinimisedMTSFromPartition` hace lo mismo a partir de una partición ya
calculada, midiendo tiempos.

Dentro de `getPartitions`, lo primero es **sacar las initiating actions de los
fluents de `tauLabels`**:

```java
HashSet<String> allInitiatingActions = new HashSet<>();
for (Fluent fluent : fluents) {
    for (Symbol initiatingAction : fluent.getInitiatingActions()) {
        allInitiatingActions.addAll(translateFromOriginal(initiatingAction.toString(), totalTranslator));
        allInitiatingActions.add(initiatingAction.toString());
    }
}
tauLabels.removeAll(allInitiatingActions);
```

A partir de acá esas acciones se tratan como visibles.

### 4.2. SCCs τ-conexas y partición inicial de estados

1. `partitionIntoSCCWithTauLabels` computa las SCCs considerando sólo aristas τ
   (Tarjan iterativo: DFS forward para el orden de finalización, DFS backward
   sobre el grafo invertido). Se llena `stateToSCCMap`.

2. `computeBvis` calcula `Bvis` (estados que pueden alcanzar una acción
   visible): construye el grafo de SCCs invertido, marca como visibles las SCCs
   con alguna transición visible saliente, y propaga hacia atrás por aristas τ.
   `Binvis` es el complemento.

3. **Bloque de error.** Si existe el estado `-1L`, se lo extrae a su propio
   bloque `errorBlock` y se lo quita de `Bvis`/`Binvis`.

4. `Pi_s` arranca con `{errorBlock?, Bvis, Binvis}` (los no vacíos). Se inicializa
   `blockIdMap` asignando un `int` a cada bloque y `stateToBlockMap` (estado →
   bloque).

### 4.3. Partición inicial de transiciones

El bunch inicial son **todas las transiciones no-inertes**: las visibles, más
las τ cuyo origen y destino caen en bloques distintos (τ no-inerte).

```java
if (!tauLabels.contains(a)) {
    initialBunch.add(new Triple<>(s, a, sPrime));            // visible
} else {
    Set<Long> sourceBlock = stateToBlockMap.get(s);
    Set<Long> targetBlock = stateToBlockMap.get(sPrime);
    if (sourceBlock != null && targetBlock != null && !sourceBlock.equals(targetBlock)) {
        initialBunch.add(new Triple<>(s, a, sPrime));        // τ no-inerte
    }
}
```

`Pi_t` se crea como `Set` por identidad (`Collections.newSetFromMap(new
IdentityHashMap<>())`) y la cola `Pi_t_cola` (un `IdentityQueue`) se siembra con
el bunch inicial. Se construye `targetStateToBunches` (índice estado destino →
bunches que lo contienen). Finalmente se siembra `splitterList`: por cada bloque
que tiene transiciones en el bunch inicial se crea un splitter primario inicial
(con `groupId` propio).

### 4.4. El bucle principal: dos fases

```
while (Pi_t_cola no vacía OR splitterList no vacía):
    FASE 1 — estabilizar estados: drenar splitterList por completo
    FASE 2 — refinar bunches: sacar UN bunch de Pi_t_cola y partirlo
```

#### Fase 1 — Estabilizar estados

Mientras haya splitters pendientes, se saca uno `(B, transitions, marks)`:

- Si `B` ya no está en `Pi_s` (lo partió un splitter anterior), se descarta.
- Se llama a `split(B, transitions, marks)` → `(R, U)`. Si alguno es vacío, no
  hubo split, sigue.
- Si hubo split: se actualiza `Pi_s` (sacar `B`, agregar `R` y `U`),
  `stateToBlockMap`, y `blockIdMap` (sacar `B`, dar id a `R` y `U`).
- **Si el splitter era primario**, se busca en `splitterList` el secundario
  hermano (mismo `groupId`, mismo `B`): como `U` ya es estable respecto del
  secundario, se lo reemplaza por un secundario sólo para la parte de `R`.

Hasta acá nada distingue demasiado a v2 de v0. La diferencia importante está en
lo que pasa **después de cada split**: partir `B` en `R` y `U` invalida un montón
de trabajo pendiente, y v2 lo reconstruye con tres mecanismos sistemáticos que en
v0 estaban resueltos de manera ad-hoc, parcial o incorrecta. Estos tres puntos
son el núcleo del cambio v0 → v2.

**(a) `refineSplitters(B, R, U, ...)` — arreglar los splitters obsoletos.**

Cuando `B` deja de existir, *todos* los demás splitters de `splitterList` que
apuntaban a `B` quedan sin blanco válido: sus transiciones salían de estados de
`B` que ahora están repartidos entre `R` y `U`. Hay que convertir cada uno de
esos splitters en dos: uno que opere sobre `R` y otro sobre `U`.

- **En v0** esto no se hace. v0 simplemente *descarta* el splitter obsoleto
  cuando lo saca de la cola (`if (!Pi_s.contains(B)) continue;`), tirando el
  trabajo que ese splitter representaba; y para el único caso que sí atiende —el
  secundario hermano del primario que acaba de partir— usa un `peekFirst()`
  ad-hoc que depende de que ese secundario esté justo al frente de la cola
  (frágil).
- **En v2**, `refineSplitters` recorre `splitterList` y, por cada splitter cuyo
  `block == B`, reparte sus transiciones según salgan de `R` o de `U`, recalcula
  las marcas (todas si era primario; una por *bottom state* vía
  `findBottomStates` si era secundario), y crea hasta dos splitters nuevos
  preservando `isPrimary` y `groupId`. Los inserta al frente de la cola para que
  se procesen a continuación. Nada de trabajo pendiente se pierde.

**(b) `enqueueAffectedBunches(R, ...)` — revisar los bunches que ahora pueden ser no triviales.**

Un split no sólo afecta splitters: también puede volver *refinable* a un bunch.
Un bunch se vuelve no trivial cuando sus transiciones empiezan a apuntar a más de
un `(acción, bloque destino)`. Al mover estados a `R`, cualquier bunch que tenga
una transición *cuyo destino* cayó en `R` puede haber pasado a distinguir slices
nuevas y debe revisarse en la Fase 2.

- **En v0** esta necesidad queda enmascarada por la estructura anidada: como v0
  drena los splitters dentro del mismo `while` que está procesando el bunch, y
  vuelve a empujar las mitades del bunch a la cola, nunca razona explícitamente
  sobre "qué bunches afectó este split de estados".
- **En v2**, con el modelo de cola y dos fases, hace falta re-encolar explícito.
  Para no reescanear todo `Pi_t` en cada split, v2 mantiene el índice inverso
  `targetStateToBunches` (estado destino → bunches que lo tienen como destino) y
  re-encola en `Pi_t_cola` **sólo** los bunches afectados: los que apuntan a
  algún estado de `R`.

**(c) Cascada de τ no-inertes vía `newFrontiers` — propagar las τ que dejaron de ser inertes.**

Al partir `B` en `R` y `U`, una transición τ que antes era *inerte* (vivía dentro
de `B`, así que se ignoraba) puede volverse *no-inerte* porque ahora cruza el
corte. Esas τ nuevas representan comportamiento observable y deben agregarse a
`Pi_t` como bunches; además pueden forzar más splits, en cascada.

*Sólo el lado `R→U` puede aparecer (en v2).* `split` no parte `B` de cualquier
manera: inicializa `R` con los orígenes de las transiciones marcadas y luego lo
**cierra hacia atrás** siguiendo τ-inertes dentro de `B`. En v2, además, ese
cierre **mueve SCCs enteras**, de modo que tanto `R` como `U` son uniones de SCCs
completas. Con eso vale el invariante:

> En v2 no puede existir ninguna τ de `U` hacia `R`.

En efecto, sea un supuesto `u ∈ U` con `u —τ→ r`, `r ∈ R`. Si `u` y `r` están en
la misma SCC, es imposible, porque `R` y `U` no comparten SCCs. Si están en SCCs
distintas, esa τ es un predecesor inerte de `r`, así que el cierre hacia atrás
desde `r ∈ R` arrastra la SCC de `u` a `R` —contradiciendo `u ∈ U`—. En ambos
casos hay contradicción, luego `findNewNonInertTransitions(U, R)` **siempre da
vacío en v2**. Por lo tanto, las únicas τ que pueden pasar de inertes a no-inertes
son las de `R` hacia `U`. (Es el mismo argumento que da el paper para justificar
que alcanza con mirar `R-τ→U`.)

**Atención al supuesto:** este invariante depende crucialmente de que las SCCs
*no se partan*. En v0, `split` arranca `R` con estados sueltos y propaga estado
por estado, sin respetar la atomicidad de la SCC; puede dejar `r ∈ R` y `u ∈ U`
siendo de la misma SCC, y como la τ intra-SCC `u —τ→ r` está filtrada del cierre
hacia atrás, nada arrastra `u` a `R`. En ese escenario `(U, R)` **sí** podría dar
no vacío —pero es un artefacto del bug de partir SCCs (sección 3.3), no
comportamiento real del sistema—.

- **En v0** la restabilización está escrita *inline* dentro del bucle (≈80
  líneas): busca `R-τ→U`, crea el bunch, hace un segundo split de `R` en `N` y
  `R'`, le agrega `N-τ→R'` al bunch, y agenda en `splitterList` los splitters de
  restabilización de `N`. La cascada continúa porque esos splitters vuelven a
  entrar al mismo bloque inline, pero el flujo está entrelazado con el resto del
  while y es difícil seguir qué fronteras nuevas quedan efectivamente cubiertas.
- **En v2** la cascada se resuelve con una cola local `newFrontiers` que se drena
  por completo antes de pasar al siguiente splitter. El detalle se desarrolla a
  continuación.

*Cómo se forma y se procesa `newFrontiers` en v2.* Es un `Queue<Pair<Set<Long>,
Set<Long>>>`: cada elemento es un par ordenado de bloques `(src, tgt)` que
significa "revisar si hay τ que, de `src` hacia `tgt`, dejaron de ser inertes".

1. **Siembra.** Apenas se parte `B` en `R` y `U`, se encolan `(R, U)` y `(U, R)`.
   Por el invariante de arriba, en v2 `(U, R)` devolverá siempre vacío (lo
   garantiza la atomicidad por SCC del `split`); se lo encola igual de forma
   **defensiva/simétrica** —es barato y deja el código uniforme—. El trabajo real
   lo hace `(R, U)`.

2. **Por cada par `(src, tgt)` que se saca de la cola:**
   - **Guarda de vigencia.** Si `src` o `tgt` ya no están en `Pi_s` (los partió
     un par anterior de la misma cola), se descarta el par. Esto es necesario
     porque las fronteras realimentadas (paso 4) referencian bloques que pueden
     haberse subdividido mientras tanto.
   - `findNewNonInertTransitions(src, tgt)` devuelve las τ `src→tgt` recién
     no-inertes **agrupadas por etiqueta** (`Map<String, Set<Triple>>`), una
     entrada por acción.
   - Se procesa **un grupo de etiqueta por vez**, manteniendo una variable
     `currentSrc` (inicialmente `src`) que se va achicando: cada grupo se filtra
     a las transiciones cuyo origen sigue en `currentSrc` (`validTNew`).

3. **Para cada grupo `validTNew` no vacío:**
   - Se registra como bunch nuevo en `Pi_t`, `Pi_t_cola` y `targetStateToBunches`
     (es comportamiento observable que la Fase 2 deberá refinar).
   - Se vuelve a partir el bloque actual: `split(currentSrc, validTNew,
     validTNew)` → `(N, src')`, donde `N` son los estados de `currentSrc` que
     alcanzan `tgt` por esa τ (más su cierre) y `src' = currentSrc \ N`.
   - Si el split fue efectivo (`N` y `src'` no vacíos): se actualizan `Pi_s`,
     `stateToBlockMap`, `blockIdMap`, se llama a `refineSplitters` y a
     `enqueueAffectedBunches(N, ...)` (los mismos mecanismos (a) y (b) de antes),
     y se **realimenta** `newFrontiers` con los cuatro pares que el nuevo corte
     puede haber destapado: `(N, src')`, `(src', N)`, `(N, tgt)` y `(src', tgt)`.
   - Independientemente de eso, para `N` se calculan sus *bottom states* y, por
     cada bunch que tenga transiciones saliendo de `N`, se agenda en el
     `splitterList` global un splitter secundario que marca una transición por
     bottom (líneas 1.26–1.27 del paper): así `N` queda agendado para
     estabilizarse respecto de los bunches ya existentes.
   - Finalmente `currentSrc := N`, de modo que el próximo grupo de etiqueta se
     procesa contra la parte ya recortada del bloque.

4. **Terminación.** El bucle sigue sacando pares hasta que `newFrontiers` queda
   vacía. Recién ahí se vuelve al `while` de la Fase 1 a tomar el próximo
   splitter.

*Por qué este diseño (frente a v0).* Honestamente, la **dirección** no es donde
está la mejora: con el `split` atómico por SCC de v2, `(U, R)` siempre da vacío,
así que la simetría `(R,U)/(U,R)` es defensiva, no un arreglo. (Y los pares
inversos que se realimentan, como `(src', N)`, son vacíos por la misma razón.) Lo
que de verdad evita el caso patológico es la atomicidad por SCC del `split`, no el
chequeo bidireccional. La ventaja de la cola `newFrontiers` frente a la
restabilización inline de v0 es de **estructura y robustez**:

- la cascada queda expresada como un patrón uniforme y autocontenido ("encolar
  pares, drenar hasta vaciar"), iterativo (sin recursión ni riesgo de desbordar
  la pila) y con un invariante claro de terminación, en vez de las ≈80 líneas
  entrelazadas de v0;
- al realimentar explícitamente los cuatro pares tras *cada* split y revalidar la
  vigencia de cada bloque, es **manifiestamente** exhaustiva: no hay que razonar
  sobre el orden en que `splitterList` re-dispara el bloque inline para
  convencerse de que ninguna frontera nueva quedó sin cubrir.

El caso en el que v0 *sí* podría equivocarse no es de dirección sino de SCC: si
su `split` parte una SCC dejando estados en `R` y en `U`, las τ intra-SCC entre
esas dos partes aparecen como "cruzadas" pese a ser inertes. v2 mueve la SCC
entera (sección 3.3) y el problema no surge. Aun así, afirmar que v0 produce un
cociente incorrecto en algún caso requeriría un contraejemplo concreto verificado
con los tests; lo que sí podemos afirmar es que v2 es más fácil de auditar y de
dar por correcta.

Al terminar la Fase 1, `Pi_s` es estable respecto de todos los splitters que
entraron y `splitterList` está vacía.

#### Fase 2 — Refinar bunches

Saca **un** bunch `T` de `Pi_t_cola` y lo parte:

- Agrupa las transiciones de `T` en *slices* por `(acción, blockId del destino)`
  —acá entra el hashing barato vía `blockIdMap`.
- Si hay una sola slice, `T` es trivial: no hace nada.
- Elige `chosenTransitions`: la primera slice con `tamaño ≤ |T|/2` (la "mitad
  chica"); si ninguna cumple, la primera no vacía.
- Parte `T` en `chosenTransitions ∪ newBunch`, actualiza `Pi_t`, `Pi_t_cola` y
  `targetStateToBunches`.
- Por cada bloque *splittable* (con transiciones en `chosenTransitions`) encola
  un splitter **primario** (marca todas sus transiciones) y, si tiene
  transiciones secundarias, un splitter **secundario** (marca una transición por
  cada estado que sea origen de alguna primaria), ambos con el mismo `groupId`.

Estos splitters nuevos serán drenados por la Fase 1 de la próxima iteración.

**Termina** cuando `Pi_t_cola` y `splitterList` quedan ambas vacías. Se
devuelven `Pi_s`, `Pi_t` y el `timingMap` con la instrumentación.

### 4.5. Procedimientos auxiliares

- **`split(B, transitions, marks)`** — el corazón del refinamiento. Inicializa
  `R` con las **SCCs enteras** de los orígenes de las transiciones (atomicidad),
  arma el mapa de predecesores τ-inertes dentro de `B` (excluyendo aristas
  intra-SCC), y propaga `R` hacia atrás por esos predecesores **expandiendo por
  SCC completa**. `U = B \ R`.

- **`refineSplitters(oldBlock, R, U, ...)`** — recorre `splitterList`; por cada
  splitter que apuntaba a `oldBlock`, reparte sus transiciones en las que salen
  de `R` y las que salen de `U`, recalcula marcas (todas, si era primario; una
  por bottom state vía `findBottomStates`, si era secundario) y crea hasta dos
  splitters nuevos preservando `isPrimary` y `groupId`.

- **`enqueueAffectedBunches(R, ...)`** — re-encola en `Pi_t_cola` los bunches que
  tienen como destino algún estado de `R`, usando `targetStateToBunches`.

- **`findBottomStates(N, ...)`** — una SCC dentro de `N` es bottom si ninguno de
  sus estados tiene una τ saliente hacia otra SCC dentro de `N`; devuelve todos
  los estados de las SCCs bottom.

- **`findNewNonInertTransitions(src, tgt, ...)`** — devuelve, **agrupadas por
  etiqueta** (`Map<String, Set<Triple>>`), las τ de `src` a `tgt`, que tras el
  split pasan a ser no-inertes.

- **`findSplittableBlocks(chosenTransitions, ...)`** — bloques que tienen al
  menos una transición en `chosenTransitions` (los candidatos a partirse en la
  Fase 2).

- **`computeBvis`, `partitionIntoSCCWithTauLabels`, `buildReversedGraph`,
  `forwardDFSWithTauLabels`, `backwardsDFSWithTauLabels`** — preprocesamiento de
  SCCs y de la partición inicial visible/invisible (sección 4.2). Respecto de v0,
  los DFS llevan el `break` que los hace Tarjan iterativo correcto.

---

## 5. Cronología sugerida para leer el código

1. Leer `partitionIntoSCCWithTauLabels` y `computeBvis` (preprocesamiento, casi
   idéntico en ambas versiones salvo el `break` de Tarjan).
2. Leer **v0** entera: es el algoritmo en su forma más directa, traducción línea
   por línea del Algorithm 1.
3. Leer **v2** con la sección 4 al lado, prestando atención a (a) la separación
   en dos fases, (b) por qué hacen falta `IdentityQueue` y
   `targetStateToBunches`, (c) `split`/`findBottomStates` por SCC, y (d) las
   adaptaciones de dominio (fluents, bloque de error, `blockIdMap`).
