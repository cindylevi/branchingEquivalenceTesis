package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional;

import MTSTools.ac.ic.doc.commons.relations.Pair;
import org.jgrapht.alg.util.Triple;

import java.util.*;

/**
 * Refinable partition de transiciones (Jansen, Groote, Keiren, Wijs 2019,
 * §5.1). Reemplaza {@code Set<Triple<Long, String, Long>>} (bunches),
 * {@code IdentityHashMap} de Πt, {@code IdentityQueue} de bunches a refinar y
 * {@code Map<Long, Set<Set<Triple>>> targetStateToBunches}.
 *
 * <p>Cada transición es un {@link Transition} con identidad. La misma
 * instancia participa simultáneamente de cuatro vistas:
 * <ul>
 *   <li>Por bunch ({@link BunchSlice}) — el conjunto Πt.</li>
 *   <li>Por block-bunch-slice ({@link BlockBunchSlice}) — sub-grupo
 *       (bunch × bloque-fuente).</li>
 *   <li>Por estado fuente — {@link #outgoing(long)}.</li>
 *   <li>Por estado destino — {@link #incoming(long)}.</li>
 * </ul>
 *
 * <p>Las dos primeras son refinables: bunches se parten al refinar Πt;
 * block-bunch-slices se parten cuando se parte un bloque de Πs (vía
 * {@link #notifyBlockSplit(RefinablePartition.Block, RefinablePartition.Block,
 * RefinablePartition.Block)}). Las dos últimas son estáticas (las define el
 * LTS de entrada).
 *
 * <p>El detalle del diseño está en
 * {@code versiones/LinkedTransitionPartitions design notes.md}.
 */
public final class LinkedTransitionPartitions {

    public static final class Transition {
        public final long source;
        public final String action;
        public final long target;
        BunchSlice bunch;
        BlockBunchSlice blockBunch;
        /** Contador para la versión coroutine de {@code split} (Jansen §5.1). */
        int untested;

        Transition(long source, String action, long target) {
            this.source = source;
            this.action = action;
            this.target = target;
        }

        public BunchSlice bunch()             { return bunch; }
        public BlockBunchSlice blockBunch()   { return blockBunch; }

        @Override public String toString() {
            return "(" + source + "," + action + "," + target + ")";
        }
    }

    public static final class BunchSlice {
        public final int id;
        boolean alive;
        final Set<Transition> transitions;
        /** Las block-bunch-slices vivas dentro de este bunch, indexadas por
         *  identidad de bloque-fuente. */
        final Map<RefinablePartition.Block, BlockBunchSlice> blockSlices;

        BunchSlice(int id) {
            this.id = id;
            this.alive = true;
            this.transitions = Collections.newSetFromMap(new LinkedHashMap<>());
            this.blockSlices = new LinkedHashMap<>();
        }

        public int size()                                   { return transitions.size(); }
        public boolean isAlive()                            { return alive; }
        public Iterable<Transition> transitions()           { return transitions; }
        public Iterable<BlockBunchSlice> blockBunchSlices() { return blockSlices.values(); }
        public BlockBunchSlice blockSliceFor(RefinablePartition.Block sourceBlock) {
            return blockSlices.get(sourceBlock);
        }
    }

    public static final class BlockBunchSlice {
        public final int id;
        public final BunchSlice bunch;
        public final RefinablePartition.Block sourceBlock;
        boolean alive;
        boolean stable;
        int posInGlobalUnstable;
        final Set<Transition> transitions;

        BlockBunchSlice(int id, BunchSlice bunch, RefinablePartition.Block sourceBlock) {
            this.id = id;
            this.bunch = bunch;
            this.sourceBlock = sourceBlock;
            this.alive = true;
            this.stable = true;
            this.posInGlobalUnstable = -1;
            this.transitions = Collections.newSetFromMap(new LinkedHashMap<>());
        }

        public int size()                            { return transitions.size(); }
        public boolean isAlive()                     { return alive; }
        public boolean isStable()                    { return stable; }
        public Iterable<Transition> transitions()    { return transitions; }
    }

    private final RefinablePartition Pi_s;
    private final List<Transition> allTransitions;
    /** Dedupe lookup: (source, action, target) → Transition. Asegura que cada
     *  triple aparezca como una sola instancia, esencial para que rediscovery
     *  en {@code findNewNonInertTransitions} no genere objetos huérfanos. */
    private final Map<Triple<Long, String, Long>, Transition> transitionLookup;
    private final Map<Long, List<Transition>> bySource;
    private final Map<Long, List<Transition>> byTarget;
    private final List<BunchSlice> liveBunches;
    private final Map<RefinablePartition.Block, List<BlockBunchSlice>> slicesByBlock;
    private final List<BlockBunchSlice> globalUnstable;
    private int nextBunchId;
    private int nextBlockBunchId;

    public LinkedTransitionPartitions(RefinablePartition Pi_s) {
        this.Pi_s = Pi_s;
        this.allTransitions = new ArrayList<>();
        this.transitionLookup = new HashMap<>();
        this.bySource = new HashMap<>();
        this.byTarget = new HashMap<>();
        this.liveBunches = new ArrayList<>();
        this.slicesByBlock = new LinkedHashMap<>();
        this.globalUnstable = new ArrayList<>();
        this.nextBunchId = 0;
        this.nextBlockBunchId = 0;
    }

    public Transition addTransition(long source, String action, long target) {
        Triple<Long, String, Long> key = new Triple<>(source, action, target);
        Transition existing = transitionLookup.get(key);
        if (existing != null) return existing;
        Transition t = new Transition(source, action, target);
        allTransitions.add(t);
        transitionLookup.put(key, t);
        bySource.computeIfAbsent(source, k -> new ArrayList<>()).add(t);
        byTarget.computeIfAbsent(target, k -> new ArrayList<>()).add(t);
        return t;
    }

    public int totalTransitions()                  { return allTransitions.size(); }
    public Iterable<Transition> allTransitions()   { return allTransitions; }
    public List<BunchSlice> liveBunches()          { return Collections.unmodifiableList(liveBunches); }

    public Iterable<Transition> outgoing(long source) {
        List<Transition> ts = bySource.get(source);
        return ts == null ? Collections.emptyList() : ts;
    }

    public Iterable<Transition> incoming(long target) {
        List<Transition> ts = byTarget.get(target);
        return ts == null ? Collections.emptyList() : ts;
    }

    /**
     * Crea el bunch inicial Πt_0 con todas las {@code initial} transiciones.
     * Sub-divide automáticamente en block-bunch-slices según el bloque fuente
     * actual de cada transición ({@code Pi_s.blockOf(t.source)}). Las BBS
     * iniciales nacen estables (todavía no se refinó nada).
     */
    public BunchSlice seedSingleBunch(Iterable<Transition> initial) {
        BunchSlice bunch = new BunchSlice(nextBunchId++);
        for (Transition t : initial) {
            RefinablePartition.Block src = Pi_s.blockOf(t.source);
            if (src == null) continue;
            bunch.transitions.add(t);
            t.bunch = bunch;
            BlockBunchSlice bbs = bunch.blockSlices.get(src);
            if (bbs == null) {
                bbs = new BlockBunchSlice(nextBlockBunchId++, bunch, src);
                bunch.blockSlices.put(src, bbs);
                slicesByBlock.computeIfAbsent(src, k -> new ArrayList<>()).add(bbs);
            }
            bbs.transitions.add(t);
            t.blockBunch = bbs;
        }
        liveBunches.add(bunch);
        return bunch;
    }

    /**
     * Da de alta un bunch nuevo (Fase 1 cascada τ no-inerte). Las BBS del
     * bunch nuevo nacen marcadas como inestables (acaba de aparecer un
     * potencial splitter del que aún nadie estabilizó nada).
     */
    public BunchSlice newBunch(Iterable<Transition> initial) {
        BunchSlice bunch = new BunchSlice(nextBunchId++);
        for (Transition t : initial) {
            RefinablePartition.Block src = Pi_s.blockOf(t.source);
            if (src == null) continue;
            // Si la transición ya pertenecía a otro bunch, sacarla de allí.
            if (t.bunch != null) {
                t.bunch.transitions.remove(t);
                if (t.blockBunch != null) {
                    t.blockBunch.transitions.remove(t);
                    if (t.blockBunch.transitions.isEmpty()) {
                        retireBlockBunchSlice(t.blockBunch);
                    }
                }
            }
            bunch.transitions.add(t);
            t.bunch = bunch;
            BlockBunchSlice bbs = bunch.blockSlices.get(src);
            if (bbs == null) {
                bbs = new BlockBunchSlice(nextBlockBunchId++, bunch, src);
                bunch.blockSlices.put(src, bbs);
                slicesByBlock.computeIfAbsent(src, k -> new ArrayList<>()).add(bbs);
                markUnstable(bbs);
            }
            bbs.transitions.add(t);
            t.blockBunch = bbs;
        }
        if (!bunch.transitions.isEmpty()) {
            liveBunches.add(bunch);
        } else {
            bunch.alive = false;
        }
        return bunch;
    }

    /**
     * Separa una sub-slice de {@code bunch} a un bunch nuevo. La sub-slice se
     * elige agrupando por {@code (action, targetBlock)} y prefiriendo la
     * primera con tamaño ≤ {@code |bunch|/2}. Si {@code bunch} no es
     * refinable (todas sus transiciones tienen la misma {@code (action,
     * targetBlock)}), devuelve {@code null}.
     *
     * <p>Las block-bunch-slices del bunch viejo afectadas por el peel
     * (las que perdieron al menos una transición) se marcan como inestables.
     * Las BBS del bunch nuevo nacen también inestables.
     *
     * <p>Coste: {@code O(|bunch|)} para subagrupar + {@code O(|slice|)} para
     * mover. Amortizado sobre todas las refinaciones, suma O(|T| log |T|).
     */
    public BunchSlice peelSlice(BunchSlice bunch) {
        if (!bunch.alive || bunch.size() <= 1) return null;

        Map<Pair<String, Integer>, List<Transition>> sub = new HashMap<>();
        for (Transition t : bunch.transitions) {
            RefinablePartition.Block tgtB = Pi_s.blockOf(t.target);
            if (tgtB == null) continue;
            sub.computeIfAbsent(new Pair<>(t.action, tgtB.id), k -> new ArrayList<>()).add(t);
        }
        if (sub.size() <= 1) return null;

        int half = bunch.size() / 2;
        List<Transition> chosen = null;
        for (List<Transition> s : sub.values()) {
            if (!s.isEmpty() && s.size() <= half) { chosen = s; break; }
        }
        if (chosen == null) {
            // Fallback (caso degenerado): cualquier sub-slice no vacía.
            for (List<Transition> s : sub.values()) {
                if (!s.isEmpty()) { chosen = s; break; }
            }
        }
        if (chosen == null) return null;

        BunchSlice newBunch = new BunchSlice(nextBunchId++);

        // Mover las transiciones elegidas al bunch nuevo.
        LinkedHashMap<BlockBunchSlice, Boolean> oldBBSAffected = new LinkedHashMap<>();
        for (Transition t : chosen) {
            BlockBunchSlice oldBBS = t.blockBunch;
            oldBBSAffected.put(oldBBS, Boolean.TRUE);
            oldBBS.transitions.remove(t);

            bunch.transitions.remove(t);
            newBunch.transitions.add(t);
            t.bunch = newBunch;

            BlockBunchSlice newBBS = newBunch.blockSlices.get(oldBBS.sourceBlock);
            if (newBBS == null) {
                newBBS = new BlockBunchSlice(nextBlockBunchId++, newBunch, oldBBS.sourceBlock);
                newBunch.blockSlices.put(oldBBS.sourceBlock, newBBS);
                slicesByBlock.computeIfAbsent(oldBBS.sourceBlock, k -> new ArrayList<>()).add(newBBS);
                markUnstable(newBBS);
            }
            newBBS.transitions.add(t);
            t.blockBunch = newBBS;
        }

        // Las BBS del bunch viejo afectadas se vuelven inestables (perdieron
        // transiciones; el bloque fuente ya no es estable wrt el bunch viejo).
        // Las que quedaron vacías se retiran.
        for (BlockBunchSlice oldBBS : oldBBSAffected.keySet()) {
            if (oldBBS.transitions.isEmpty()) {
                bunch.blockSlices.remove(oldBBS.sourceBlock);
                retireBlockBunchSlice(oldBBS);
            } else {
                markUnstable(oldBBS);
            }
        }

        if (newBunch.transitions.isEmpty()) {
            newBunch.alive = false;
            return null;
        }
        liveBunches.add(newBunch);

        // Si el bunch viejo se quedó vacío (no debería si chosen ≤ |bunch|/2,
        // pero por seguridad), retirarlo.
        if (bunch.transitions.isEmpty()) {
            bunch.alive = false;
            liveBunches.remove(bunch);
        }
        return newBunch;
    }

    // -------------------------------------------------------------------- //
    //  Stability flag management                                           //
    // -------------------------------------------------------------------- //

    public boolean hasUnstable() {
        return !globalUnstable.isEmpty();
    }

    /** Devuelve una BlockBunchSlice viva e inestable, marcándola estable.
     *  Si la slice popeada estaba muerta (carrera con un block split previo)
     *  la descarta y devuelve la siguiente. */
    public BlockBunchSlice popUnstable() {
        while (!globalUnstable.isEmpty()) {
            int last = globalUnstable.size() - 1;
            BlockBunchSlice s = globalUnstable.remove(last);
            s.posInGlobalUnstable = -1;
            s.stable = true;
            if (s.alive) return s;
        }
        return null;
    }

    public void markUnstable(BlockBunchSlice s) {
        if (!s.alive || !s.stable) return;
        s.stable = false;
        s.posInGlobalUnstable = globalUnstable.size();
        globalUnstable.add(s);
    }

    public void markStable(BlockBunchSlice s) {
        if (s.stable) return;
        s.stable = true;
        removeFromUnstable(s);
    }

    /**
     * Una BlockBunchSlice es "divisible" (su bloque fuente NO es estable wrt su
     * bunch) sii sus transiciones abarcan más de un par (action, targetBlock)
     * distinto. Se usa para no marcar estable una BBS popeada que un peel de
     * <em>otro</em> bloque dejó sin refinar: {@code peelSlice} pela una slice
     * global del bunch, que puede no tocar el bloque de la BBS popeada.
     */
    public boolean isRefinable(BlockBunchSlice bbs) {
        if (bbs == null || !bbs.alive) return false;
        String firstAct = null;
        int firstBlk = 0;
        boolean have = false;
        for (Transition t : bbs.transitions) {
            RefinablePartition.Block tgtB = Pi_s.blockOf(t.target);
            if (tgtB == null) continue;
            if (!have) { firstAct = t.action; firstBlk = tgtB.id; have = true; }
            else if (!firstAct.equals(t.action) || firstBlk != tgtB.id) return true;
        }
        return false;
    }

    private void removeFromUnstable(BlockBunchSlice s) {
        int idx = s.posInGlobalUnstable;
        if (idx < 0) return;
        int last = globalUnstable.size() - 1;
        if (idx != last) {
            BlockBunchSlice moved = globalUnstable.get(last);
            globalUnstable.set(idx, moved);
            moved.posInGlobalUnstable = idx;
        }
        globalUnstable.remove(last);
        s.posInGlobalUnstable = -1;
    }

    private void retireBlockBunchSlice(BlockBunchSlice s) {
        if (!s.alive) return;
        s.alive = false;
        if (!s.stable) removeFromUnstable(s);
        // Sacar de slicesByBlock
        List<BlockBunchSlice> list = slicesByBlock.get(s.sourceBlock);
        if (list != null) list.remove(s);
    }

    // -------------------------------------------------------------------- //
    //  Hook con RefinablePartition                                         //
    // -------------------------------------------------------------------- //

    /**
     * A llamar después de cada {@code Pi_s.splitOffR(oldBlock)} con resultado
     * {@code (rBlock, uBlock)}. Hace dos trabajos en uno:
     *
     * <ol>
     *   <li><b>Source-side.</b> Para cada {@link BlockBunchSlice} {@code old}
     *       con {@code old.sourceBlock == oldBlock}, redistribuye sus
     *       transiciones en dos BBS nuevas (una para {@code uBlock}, otra
     *       para {@code rBlock}) según {@code Pi_s.blockOf(t.source)}. Las
     *       BBS nuevas nacen inestables; la vieja queda muerta.</li>
     *   <li><b>Target-side.</b> Las BBS de cualquier bunch con transiciones
     *       que <i>apuntaban</i> a estados ahora en {@code rBlock} se marcan
     *       inestables (reemplazo de {@code enqueueAffectedBunches} de v3-B).
     *       Iteramos solamente {@code rBlock} —no ambos— porque marcar
     *       cualquiera de los dos lados ya alcanza para forzar la
     *       re-examinación del bunch en Phase 2.</li>
     * </ol>
     *
     * <p>Cualquiera de los argumentos {@code uBlock}/{@code rBlock} puede ser
     * {@code null} si el split no produjo ese lado (el caller no debería
     * llamar a este hook en ese caso, pero por seguridad lo aceptamos).
     */
    public void notifyBlockSplit(RefinablePartition.Block oldBlock,
                                 RefinablePartition.Block uBlock,
                                 RefinablePartition.Block rBlock) {
        // 1. Source-side: redistribuir BBS de oldBlock entre uBlock y rBlock.
        List<BlockBunchSlice> oldSlices = slicesByBlock.remove(oldBlock);
        if (oldSlices != null && uBlock != null && rBlock != null) {
            for (BlockBunchSlice oldBBS : oldSlices) {
                if (!oldBBS.alive) continue;
                BunchSlice bunch = oldBBS.bunch;
                bunch.blockSlices.remove(oldBlock);

                BlockBunchSlice bbsU = null;
                BlockBunchSlice bbsR = null;
                for (Transition t : oldBBS.transitions) {
                    RefinablePartition.Block currentSrc = Pi_s.blockOf(t.source);
                    BlockBunchSlice target;
                    if (currentSrc == uBlock) {
                        if (bbsU == null) {
                            bbsU = new BlockBunchSlice(nextBlockBunchId++, bunch, uBlock);
                            bunch.blockSlices.put(uBlock, bbsU);
                            slicesByBlock.computeIfAbsent(uBlock, k -> new ArrayList<>()).add(bbsU);
                        }
                        target = bbsU;
                    } else if (currentSrc == rBlock) {
                        if (bbsR == null) {
                            bbsR = new BlockBunchSlice(nextBlockBunchId++, bunch, rBlock);
                            bunch.blockSlices.put(rBlock, bbsR);
                            slicesByBlock.computeIfAbsent(rBlock, k -> new ArrayList<>()).add(bbsR);
                        }
                        target = bbsR;
                    } else {
                        // No debería pasar: t.source seguía en oldBlock antes
                        // del split, así que después debe estar en uBlock o
                        // rBlock.
                        continue;
                    }
                    target.transitions.add(t);
                    t.blockBunch = target;
                }
                // Retirar la BBS vieja.
                oldBBS.transitions.clear();
                oldBBS.alive = false;
                if (!oldBBS.stable) removeFromUnstable(oldBBS);

                if (bbsU != null) markUnstable(bbsU);
                if (bbsR != null) markUnstable(bbsR);
            }
        }

        // 2. Target-side: bunches con transiciones que apuntaban a oldBlock
        // pueden ahora distinguir uBlock vs rBlock. Mark inestables las BBS
        // alcanzadas iterando solamente rBlock (suficiente para detectar
        // bunches refinables; ver javadoc).
        if (rBlock != null) {
            for (Long s : Pi_s.states(rBlock)) {
                List<Transition> incoming = byTarget.get(s);
                if (incoming == null) continue;
                for (Transition t : incoming) {
                    if (t.blockBunch != null && t.blockBunch.alive) {
                        markUnstable(t.blockBunch);
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------- //
    //  Conveniencia para BranchingEquivalence                              //
    // -------------------------------------------------------------------- //

    /**
     * Devuelve la lista (snapshot, posiblemente vacía) de block-bunch-slices
     * vivas cuyo source es {@code block}. Pensado para iterar splitters
     * candidatos al refinar bunches.
     */
    public Iterable<BlockBunchSlice> blockBunchSlicesOf(RefinablePartition.Block block) {
        List<BlockBunchSlice> ts = slicesByBlock.get(block);
        return ts == null ? Collections.emptyList() : ts;
    }

    /**
     * Vista legacy {@code List<Set<Triple<Long, String, Long>>>} de la
     * partición de transiciones, materializada a partir de los bunches vivos.
     * Pensada para retornar al consumidor histórico de
     * {@code BranchingEquivalence.getPartitions}.
     */
    public List<java.util.Set<org.jgrapht.alg.util.Triple<Long, String, Long>>> asLegacyView() {
        List<java.util.Set<org.jgrapht.alg.util.Triple<Long, String, Long>>> out = new ArrayList<>(liveBunches.size());
        for (BunchSlice bunch : liveBunches) {
            java.util.HashSet<org.jgrapht.alg.util.Triple<Long, String, Long>> view = new java.util.HashSet<>(bunch.size());
            for (Transition t : bunch.transitions) {
                view.add(new org.jgrapht.alg.util.Triple<>(t.source, t.action, t.target));
            }
            out.add(view);
        }
        return out;
    }

    /** Iterador inmutable para no exponer el {@code Set} interno. */
    @SuppressWarnings("unused")
    private static <T> Iterator<T> readOnly(final Iterator<T> it) {
        return new Iterator<T>() {
            @Override public boolean hasNext() { return it.hasNext(); }
            @Override public T next() {
                if (!it.hasNext()) throw new NoSuchElementException();
                return it.next();
            }
        };
    }
}
