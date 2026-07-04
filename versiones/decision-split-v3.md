# Cómo se decide implementar `split` en v3: la corrutina del paper sin corrutinas de Java

Este documento profundiza la §3.3 de
[`comparacion-v2-v3c.md`](comparacion-v2-v3c.md), que describe el `split` de v3
"por arriba" (lo llama *coroutine dual con abort-on-half* y cuenta qué hace cada
lado). Acá se explica la **decisión de diseño de fondo**: el paper de Jansen,
Groote, Keiren y Wijs (2019) —`Papers/jansen.pdf`, Algorithm 2— **prescribe el
`split` como dos corrutinas ejecutándose en lockstep**, y **Java no tiene
corrutinas**. Este documento explica cómo se decidió realizar ese `split` en v3
respetando las instrucciones del paper pese a esa ausencia.

Se asume leída la §3.3 de `comparacion-v2-v3c.md` y conviene tener a mano las
*design notes* de [`LinkedTransitionPartitions`](LinkedTransitionPartitions%20design%20notes.md)
y [`RefinablePartition`](RefinablePartition%20design%20notes.md).

Todas las referencias de línea son a `versiones/BranchingEquivalenceV3C.java`.

---

## 1. Qué prescribe el paper (Algorithm 2)

`split(B, T)` refina un bloque `B` en dos subbloques:

- **`R`**: los estados de `B` que **pueden alcanzar inertemente** (por τ-inertes)
  una transición del splitter `T`.
- **`U`**: los que **no** pueden.

El paper no computa `R` y `U` con dos recorridos secuenciales. Los computa con
**dos corrutinas ejecutándose en lockstep** (Algorithm 2, `begin coroutines …
end coroutines`):

- La **corrutina de R** extiende `R` hacia atrás por transiciones τ-inertes
  (backward reachability): un estado está en `R` si alcanza inertemente algún
  source del splitter.
- La **corrutina de U** identifica los estados que *no* pueden. Usa un contador
  `untested[t]` por estado, igual al número de τ-sucesores de `t` todavía no
  clasificados como `U`. Cuando `untested[t]` llega a 0 (todos los sucesores
  inertes de `t` están en `U`) y `t` no tiene una transición en `T`, entonces `t`
  también es `U`.

**Por qué lockstep y no secuencial.** El paper es explícito (§4, "Splitting
blocks"): las dos corrutinas *arrancan la misma cantidad de iteraciones del
loop*, de modo que el overhead es a lo sumo proporcional a **la más rápida** de
las dos, y **todo el trabajo se atribuye al subbloque más chico** (`R` o `U`). En
cuanto una corrutina supera ½|B| estados, su subbloque es "el grande" y **se
aborta** (Lines 2.21–2.24 / columna derecha). La otra, que ya terminó con
≤ ½|B| estados, deja calculado el subbloque chico. **Ese abort-on-half en
lockstep es la razón por la que el `split` amortiza a O(m log n)**: sin él, cada
`split` costaría O(|B|) independientemente de qué tan chico sea el lado que se
separa (era exactamente la BFS plana de v3-B; ver §3.3 de `comparacion-v2-v3c.md`).

Es importante notar que Algorithm 2 describe **intención**, no código Java: "dos
corrutinas en lockstep" es una especificación de *cómo debe distribuirse el
trabajo*, no una API que Java provea.

---

## 2. Por qué corrutina, y por qué Java no la ofrece

Una corrutina encapsula un patrón muy concreto: *"avanzá un paso de tu cómputo,
cedé el control, y la próxima vez retomá exactamente donde ibas"*. Es la
abstracción natural para el lockstep del paper: dos cómputos que avanzan de a un
paso, alternándose, cada uno recordando su propio progreso.

Java **no tiene** ese mecanismo a nivel de lenguaje: no hay `yield` de generador,
no hay green threads ni corrutinas suspendibles en la biblioteca estándar. La
decisión de diseño de v3 es entonces:

> **Reificar cada corrutina como una máquina de estados con worklist explícita.**

Es decir: en vez de pedirle al runtime que "recuerde dónde iba" cada corrutina,
se materializa ese "dónde iba" en estructuras de datos ordinarias, y un loop
manual hace de scheduler alternando los dos cómputos.

---

## 3. La traducción: de corrutina a máquina de estados con worklist

Este es el corazón de la decisión. Cada ingrediente de una corrutina tiene una
contraparte explícita en v3:

| En la corrutina del paper | Reificación en v3 (`split`, líneas 628–753) |
|---|---|
| Estado local: "dónde iba mi BFS" | Worklist explícita `Deque` FIFO (`rQueue` / `uQueue`) + conjunto acumulado (`rSCCs` / `uSCCs`) + contador de tamaño (`rSize` / `uSize`) |
| Cuerpo de la corrutina (una iteración del `for`) | Un método `step` puro: `rStep` (761) / `uStep` (787) que consume **un** elemento de su worklist y devuelve el tamaño actualizado |
| `yield` / ceder el control | Simplemente `return` del método `step` |
| "ambas corren en lockstep" | El **driver loop** (712–720) llama alternadamente a un `rStep` y a un `uStep` por vuelta |
| "Abort this coroutine" | Dejar de llamar ese `step` y drenar el otro (728–743) |

La suspensión y reanudación que en una corrutina son mágicas acá son triviales:
como el progreso de cada lado vive en su `Deque` y su set, "retomar donde iba" es
sólo volver a llamar a `rStep`/`uStep`, que sacará el próximo elemento
de la cola.

**Por qué esta reificación sale barata acá.** La travesía de cada lado es una
**BFS** (backward reachability por τ-inertes). La "pila" implícita de una
corrutina que hace BFS es una **cola**: la frontera de estados por visitar. No
hay que reificar un *call stack* recursivo —sólo la frontera—, y por eso una
simple `Deque` alcanza. Si el recorrido fuera un DFS recursivo, reificar la
corrutina obligaría a materializar a mano la pila de llamadas (más frames, más
código sutil); no es el caso.

---

## 4. Cómo queda el `split` de v3, paso a paso

v3 sigue Algorithm 2 con una diferencia transversal: **trabaja a nivel de SCC
τ-conexa, no de estado individual**.

### 4.1. Super-estados SCC

Las SCCs τ-conexas (precomputadas en `partitionIntoSCCWithTauLabels`,
`stateToSCCMap`) actúan como **super-estados**: clasificar una SCC σ clasifica de
un saque todos sus estados. Esto es lo que preserva la invariante de branching
bisimilarity sin trabajo adicional dentro del `split` —una SCC τ es atómica y se
mueve entera—. Por eso `rSCCs`, `uSCCs`, `rQueue`, `uQueue` y `untestedSCC`
están indexados por SCC (`Set<Long>`), no por estado.

### 4.2. Seed (↔ Line 2.2 del paper)

- `rSCCs` (640–649): las SCCs de **todos** los sources del splitter que caen en
  `B`. Si queda vacío no hubo qué separar → se devuelve `(null, B)`.
- `uSCCs` / `uQueue` (694–705): las SCCs **sumidero** del sub-DAG dentro de `B`
  (las que tienen `untestedSCC == 0`, o sea sin τ-outs dentro de `B`) que **no**
  estén ya en `R`.

### 4.3. `untestedSCC[σ]` (↔ `untested[t]` per-estado del paper)

`untestedSCC[σ]` (673–679) = número de τ-out-SCCs de σ dentro de `B` todavía sin
clasificar como `U`. `uStep` (787–811) lo **decrementa** cuando una SCC
sucesora pasa a `U`; al llegar a 0 (y si σ ∉ `rSCCs`), σ pasa a `U` y se encola.
Es la versión per-SCC del contador per-estado del paper.

### 4.4. abort-on-half (↔ Lines 2.5/2.21 del paper)

`half = B.size() / 2` (707). Después de **cada** `step` se chequea `rSize > half`
/ `uSize > half` (715, 719); el primero en superar la mitad es la "larger half"
y su lado se marca abortado.

### 4.5. Cuatro casos de salida (728–743)

Según cómo se sale del lockstep, `R` se materializa de forma explícita o por
complemento:

- `rAbort` (R era el grande): se drena `uQueue` hasta vaciarla y
  `applyComplement` → `R = B ∖ U`.
- `uAbort` (U era el grande): se drena `rQueue` y `applyExplicit` →
  `R = rSCCs`.
- `rQueue` vacía sin abortar (R drenó natural, era el chico): `applyExplicit`.
- `uQueue` vacía sin abortar (U drenó natural, era el chico): `applyComplement`.

`applyExplicit` (818) marca como `R` los estados cuya SCC está en `rSCCs`;
`applyComplement` (836) marca los cuya SCC **no** está en `uSCCs`. Ambos toman un
**snapshot** del bloque antes de mover, porque `Pi_s.addToR` reordena el array
maestro y desincronizaría un iterador posicional.

### 4.6. Materializar y notificar

`Pi_s.splitOffR(B)` (746) corta el bloque, y `Lt.notifyBlockSplit` (750) propaga
el efecto a la partición de transiciones (redistribuye las block-bunch-slices del
lado fuente y marca inestables las del lado destino afectadas).

---

## 5. Por qué el lockstep manual tiene que alternar de verdad

La tentación al reescribir sin corrutinas sería drenar un lado entero y después
el otro. **Eso rompe la cota.** La garantía "todo el trabajo se atribuye al
subbloque más chico" del paper depende de que ambos lados avancen *a la par* y de
que el chequeo de ½|B| ocurra **después de cada paso**, no al final: así, apenas
un lado cruza la mitad, se lo aborta antes de que gaste O(|B|).

Por eso el driver loop (712–720) intercala explícitamente:

```
while (!rAbort && !uAbort) {
    if (rQueue.isEmpty()) break;
    rSize = rStep(...);   if (rSize > half) { rAbort = true; break; }
    if (uQueue.isEmpty()) break;
    uSize = uStep(...);   if (uSize > half) { uAbort = true; break; }
}
```

Un `rStep`, un chequeo, un `uStep`, un chequeo. Ese intercalado
paso-a-paso **es** el lockstep del paper; no es un detalle de estilo sino la
condición que sostiene el O(m log n).

---

## 6. En qué se aparta esta realización del paper (honestidad)

La reificación no es una transcripción *paper-pure*. Tres diferencias que conviene
dejar explícitas:

1. **Nivel SCC vs estado, y `untestedSCC` con boxing.** v3 clasifica SCCs, no
   estados, y guarda `untestedSCC` en un `IdentityHashMap<Set<Long>, Integer>`
   local al `split` en vez del array `untested` per-transición del paper. Es una
   **concesión de factor constante** (boxing de `Integer`, un lookup extra), ya
   anotada en las *design notes* de `LinkedTransitionPartitions` (sección "Cosas
   que esta estructura NO resuelve", punto 3): el campo `Transition.untested`
   existe pero queda sin uso; una variante per-transición densificada a un array
   paralelo ahorraría el boxing. Se pierde constante, no orden.

2. **El chequeo de la columna izquierda del paper se subsume.** Lines 2.13ℓ–2.17ℓ
   de Algorithm 2 revisan, para un candidato a `U`, si tiene alguna transición en
   `T` (lo que lo volvería `R`). v3 no necesita ese chequeo: siembra `rSCCs` con
   **todos** los sources del splitter de entrada (642–646) y en `uStep`
   saltea las SCCs que ya están en `rSCCs` (799). Como consecuencia, el parámetro
   `currentMarks` no se usa (ver el comentario en 621–623): el seed de `R` es el
   conjunto `transitions` completo, igual que en v3-B.

3. **Los new-bottom states se manejan aparte.** El paper contabiliza estados que
   se vuelven bottom dentro del razonamiento de la corrutina de `U`. En v3 eso no
   vive en `uStep`: los bottoms se recalculan en `findBottomStates`
   (856–893), que muta `Pi_s` marcándolos en el prefijo del bloque.

---

## 7. Punteros de lectura

- **v3:** `split` 628–753, `rStep` 761–778, `uStep` 787–811,
  `applyExplicit` / `applyComplement` 818–848, `findBottomStates` 856–893 de
  `BranchingEquivalenceV3C.java`. El Javadoc de `split` (591–627) resume el mismo
  diseño.
- **Paper:** Algorithm 2 y §4 "Splitting blocks" de `Papers/jansen.pdf`.
- **Contexto de este repo:** §3.3 de [`comparacion-v2-v3c.md`](comparacion-v2-v3c.md)
  (que este documento profundiza) y la sección "Cosas que esta estructura NO
  resuelve" de las *design notes* de
  [`LinkedTransitionPartitions`](LinkedTransitionPartitions%20design%20notes.md).
