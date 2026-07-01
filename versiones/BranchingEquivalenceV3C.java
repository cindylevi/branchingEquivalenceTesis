package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional;

import MTSSynthesis.ar.dc.uba.model.condition.Fluent;
import MTSSynthesis.ar.dc.uba.model.language.Symbol;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.MTSImpl;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.LinkedTransitionPartitions.BlockBunchSlice;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.LinkedTransitionPartitions.BunchSlice;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.LinkedTransitionPartitions.Transition;
import org.jgrapht.alg.util.Triple;

import java.util.*;

import static MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.Utils.translateFromOriginal;

/**
 * v3 (Phase C completa): la partición de transiciones Πt pasa a ser una
 * {@link LinkedTransitionPartitions} (Jansen et al. 2019, §5.1). Se reemplazan
 * cuatro estructuras de v3-B:
 * <ul>
 *   <li>{@code Pi_t} ({@code IdentityHashMap<Set<Triple>>}) → {@code Lt.liveBunches()}.</li>
 *   <li>{@code Pi_t_cola} ({@code IdentityQueue}) → {@code Lt.globalUnstable} (lista de
 *       block-bunch-slices con flag de estabilidad por slice; swap-remove O(1)).</li>
 *   <li>{@code targetStateToBunches} → vista estática {@code byTarget} +
 *       {@code Lt.notifyBlockSplit} target-side (marca BBS afectadas
 *       automáticamente al partir un bloque).</li>
 *   <li>{@code Set<Triple<Long,String,Long>>} (bunches y splitters) →
 *       {@link LinkedTransitionPartitions.Transition} con identidad +
 *       {@link LinkedTransitionPartitions.BunchSlice} +
 *       {@link LinkedTransitionPartitions.BlockBunchSlice}.</li>
 * </ul>
 *
 * <p>Cambios respecto de v3-B:
 * <ul>
 *   <li>Splitters portan {@code Set<Transition>} en lugar de {@code Set<Triple>};
 *       la identidad por referencia elimina rehashes en el hot path.</li>
 *   <li>{@code split} llama a {@link LinkedTransitionPartitions#notifyBlockSplit}
 *       inmediatamente después de {@link RefinablePartition#splitOffR}; eso
 *       redistribuye source-side BBS y marca target-side BBS (reemplazando
 *       {@code enqueueAffectedBunches}).</li>
 *   <li>El loop principal popea {@code BlockBunchSlice} (no bunch entero) de
 *       {@code Lt.globalUnstable} en Phase 2 y refina el bunch correspondiente
 *       con {@code Lt.peelSlice}.</li>
 *   <li>{@code split} ejecuta dos BFS duales en lockstep (Jansen et al. §5.1):
 *       una hacia atrás por τ-pred-SCCs sembrada por las SCCs de los splitter
 *       sources (lado R), y otra hacia atrás por τ-pred-SCCs propagando un
 *       contador {@code untestedSCC[σ]} (= número de τ-out-SCCs de σ dentro de
 *       B aún no clasificadas como U) sembrada por las SCCs sumidero del
 *       sub-DAG-en-B (lado U). El primero que cruza {@code |B|/2} estados se
 *       aborta; el otro se drena y los estados sin clasificar quedan asignados
 *       al lado abortado. Es la optimización que cierra la complejidad
 *       O(m log n) del paper.</li>
 *   <li>Las optimizaciones §5.2 #2 (saltar el bunch recién aplicado) y la
 *       fase D (Tarjan iterativo) se mantienen.</li>
 * </ul>
 *
 * <p>Lo que <b>NO</b> cambia respecto de v3-B:
 * <ul>
 *   <li>{@code findBottomStates} sigue mutando {@code Pi_s} para marcar bottoms.</li>
 *   <li>{@code refineSplitters} preserva la lógica de v3-B: separa los splitters
 *       pendientes que apuntaban al bloque viejo en dos splitters apuntando a R
 *       y a U respectivamente; secundarios marcan transiciones con bottoms.</li>
 *   <li>El contador {@code untested} se mantiene a nivel de SCC en un
 *       {@code IdentityHashMap<Set<Long>, Integer>} local. El campo
 *       {@link Transition#untested} queda disponible para una variante
 *       per-transición futura (densificación a array paralelo).</li>
 * </ul>
 */
public class BranchingEquivalenceV3C {

    static class Splitter {
        final RefinablePartition.Block block;
        final Set<Transition> transitions;
        final Set<Transition> marks;
        final boolean isPrimary;
        final long groupId;

        public Splitter(RefinablePartition.Block block,
                        Set<Transition> transitions,
                        Set<Transition> marks,
                        boolean isPrimary,
                        long groupId) {
            this.block = block;
            this.transitions = transitions;
            this.marks = marks;
            this.isPrimary = isPrimary;
            this.groupId = groupId;
        }
    }

    public static MTS<Long, String> buildMinimisedMTS(MTS<Long, String> toMinimise, Set<String> tauLabels, Map<String, String> translatorControllable, Vector<HashMap<String, String>> totalTranslator, Set<Fluent> fluents) {
        MTS<Long, String> result = new MTSImpl<>(0L);
        result.removeUnreachableStates();
        result.addActions(toMinimise.getActions());
        long newStateIdCounter = 0;
        Long newInitialState = null;
        Long oldInitialState = toMinimise.getInitialState();
        Map<Long, Long> oldStateToNewStateId = new HashMap<>();
        Map<Set<Long>, Long> blockToNewStateId = new HashMap<>();

        Pair<Pair<List<Set<Long>>, List<Set<Triple<Long, String, Long>>>>, Map<String, Double>> partitionResult = getPartitions(toMinimise, tauLabels, totalTranslator, fluents);
        Pair<List<Set<Long>>, List<Set<Triple<Long, String, Long>>>> partitions = partitionResult.getFirst();
        List<Set<Long>> pi_s = partitions.getFirst();

        for (Set<Long> block : pi_s) {
            Long newStateId = newStateIdCounter++;
            result.addState(newStateId);
            blockToNewStateId.put(block, newStateId);
            for (Long oldState : block) {
                if (oldState == null) continue;
                oldStateToNewStateId.put(oldState, newStateId);
                if (oldState.equals(oldInitialState)) newInitialState = newStateId;
            }
        }

        result.setInitialState(newInitialState);
        for (Set<Long> block : pi_s) {
            Long newStateFromId = blockToNewStateId.get(block);
            for (Long oldStateFrom : block) {
                for (Pair<String, Long> trans : toMinimise.getTransitions(oldStateFrom, MTS.TransitionType.REQUIRED)) {
                    String action = trans.getFirst();
                    Long oldStateTo = trans.getSecond();
                    Long newStateToId = oldStateToNewStateId.get(oldStateTo);

                    if (newStateToId != null) {
                        if(!newStateFromId.equals(newStateToId) || !tauLabels.contains(action)){
                            result.addAction(action);
                            result.addRequired(newStateFromId, action, newStateToId);
                        }else {
                            String translatedLabel = "c_" + action;
                            if(translatorControllable.containsKey(action)){
                                translatedLabel = action;
                            }else{
                                translatorControllable.put(translatedLabel, action);
                            }
                            result.addAction(action);
                            result.addRequired(newStateFromId, action, newStateToId);
                        }
                    }
                }
            }
        }
        return result;
    }

    public static Pair<MTS<Long, String>, Map<String, Double>> buildMinimisedMTSFromPartition(MTS<Long, String> toMinimise, Set<String> tauLabels, Map<String, String> translatorControllable, Pair<List<Set<Long>>, List<Set<Triple<Long, String, Long>>>> partitions) {
        long t0 = System.nanoTime();

        MTS<Long, String> result = new MTSImpl<>(0L);
        result.removeUnreachableStates();
        result.addActions(toMinimise.getActions());
        long newStateIdCounter = 0;
        Long newInitialState = null;
        Long oldInitialState = toMinimise.getInitialState();
        Map<Long, Long> oldStateToNewStateId = new HashMap<>();
        Map<Set<Long>, Long> blockToNewStateId = new HashMap<>();

        List<Set<Long>> pi_s = partitions.getFirst();
        for (Set<Long> block : pi_s) {
            Long newStateId = newStateIdCounter++;
            result.addState(newStateId);
            blockToNewStateId.put(block, newStateId);
            for (Long oldState : block) {
                if (oldState == null) continue;
                oldStateToNewStateId.put(oldState, newStateId);
                if (oldState.equals(oldInitialState)) newInitialState = newStateId;
            }
        }
        long t1 = System.nanoTime();

        result.setInitialState(newInitialState);
        for (Set<Long> block : pi_s) {
            Long newStateFromId = blockToNewStateId.get(block);
            for (Long oldStateFrom : block) {
                for (Pair<String, Long> trans : toMinimise.getTransitions(oldStateFrom, MTS.TransitionType.REQUIRED)) {
                    String action = trans.getFirst();
                    Long oldStateTo = trans.getSecond();
                    Long newStateToId = oldStateToNewStateId.get(oldStateTo);

                    if (newStateToId != null) {
                        if(!newStateFromId.equals(newStateToId) || !tauLabels.contains(action)){
                            result.addAction(action);
                            result.addRequired(newStateFromId, action, newStateToId);
                        }else {
                            String translatedLabel = "c_" + action;
                            if(translatorControllable.containsKey(action)){
                                translatedLabel = action;
                            }else{
                                translatorControllable.put(translatedLabel, action);
                            }
                            result.addAction(action);
                            result.addRequired(newStateFromId, action, newStateToId);
                        }
                    }
                }
            }
        }
        long t2 = System.nanoTime();

        Map<String, Double> timingMap = new LinkedHashMap<>();
        timingMap.put("[buildMinimisedMTSFromPartition] build states", (t1 - t0) / 1_000_000.0);
        timingMap.put("[buildMinimisedMTSFromPartition] build transitions", (t2 - t1) / 1_000_000.0);
        timingMap.put("[buildMinimisedMTSFromPartition] total", (t2 - t0) / 1_000_000.0);

        return new Pair<>(result, timingMap);
    }

    public static Pair<Pair<List<Set<Long>>, List<Set<Triple<Long, String, Long>>>>, Map<String, Double>> getPartitions(MTS<Long, String> toMinimise, Set<String> tauLabels, Vector<HashMap<String, String>> totalTranslator, Set<Fluent> fluents){
        Map<String, Double> timingMap = new LinkedHashMap<>();

        HashSet<String> allInitiatingActions = new HashSet<>();
        for(Fluent fluent : fluents) {
            for (Symbol initiatingAction : fluent.getInitiatingActions()) {
                allInitiatingActions.addAll(translateFromOriginal(initiatingAction.toString(), totalTranslator));
                String stringAction = initiatingAction.toString();
                allInitiatingActions.add(stringAction);
            }
        }
        tauLabels.removeAll(allInitiatingActions);

        long tTotal = System.nanoTime();

        long t0 = System.nanoTime();
        List<Set<Long>> toMinimiseSCC = partitionIntoSCCWithTauLabels(toMinimise, tauLabels);
        timingMap.put("[getPartitions] partitionIntoSCCWithTauLabels", (System.nanoTime() - t0) / 1_000_000.0);

        Map<Long, Set<Long>> stateToSCCMap = new HashMap<>();
        for (Set<Long> scc : toMinimiseSCC) {
            for (Long state : scc) {
                stateToSCCMap.put(state, scc);
            }
        }

        t0 = System.nanoTime();
        Set<Long> Bvis = computeBvis(toMinimise, toMinimiseSCC, stateToSCCMap, tauLabels);
        timingMap.put("[getPartitions] computeBvis", (System.nanoTime() - t0) / 1_000_000.0);

        Set<Long> Binvis = new HashSet<>(toMinimise.getStates());
        Binvis.removeAll(Bvis);

        Set<Long> errorBlock = new HashSet<>();
        if (toMinimise.getStates().contains(-1L)) {
            errorBlock.add(-1L);
            Bvis.remove(-1L);
            Binvis.remove(-1L);
        }

        // Pi_s: refinable partition de estados (Phase B).
        RefinablePartition Pi_s = new RefinablePartition(toMinimise.getStates());
        List<Set<Long>> initialBlocks = new ArrayList<>();
        if (!errorBlock.isEmpty()) initialBlocks.add(errorBlock);
        if (!Bvis.isEmpty()) initialBlocks.add(Bvis);
        if (!Binvis.isEmpty()) initialBlocks.add(Binvis);
        Pi_s.seedFromInitialBlocks(initialBlocks);

        // Lt: refinable partition de transiciones (Phase C).
        LinkedTransitionPartitions Lt = new LinkedTransitionPartitions(Pi_s);

        t0 = System.nanoTime();
        List<Transition> initialTransitions = new ArrayList<>();
        for (Long s : toMinimise.getStates()) {
            for (Pair<String, Long> tr : toMinimise.getTransitions(s, MTS.TransitionType.REQUIRED)) {
                String a = tr.getFirst();
                Long sPrime = tr.getSecond();

                if (!tauLabels.contains(a)) {
                    initialTransitions.add(Lt.addTransition(s, a, sPrime));
                } else {
                    RefinablePartition.Block sourceBlock = Pi_s.blockOf(s);
                    RefinablePartition.Block targetBlock = Pi_s.blockOf(sPrime);
                    if (sourceBlock != null && targetBlock != null && sourceBlock != targetBlock) {
                        initialTransitions.add(Lt.addTransition(s, a, sPrime));
                    }
                }
            }
        }

        BunchSlice initialBunch = null;
        if (!initialTransitions.isEmpty()) {
            initialBunch = Lt.seedSingleBunch(initialTransitions);
            // El bunch inicial se examina como candidato a peel en Phase 2:
            // marcamos sus BBS como inestables (mirror de v3-B Pi_t_cola.add).
            for (BlockBunchSlice bbs : initialBunch.blockBunchSlices()) {
                Lt.markUnstable(bbs);
            }
        }

        Deque<Splitter> splitterList = new ArrayDeque<>();
        long currentGroupId = 0;

        // Splitters iniciales: uno por block-bunch-slice del bunch inicial,
        // primaries con marks == transitions.
        if (initialBunch != null) {
            for (BlockBunchSlice bbs : initialBunch.blockBunchSlices()) {
                Set<Transition> slice = newIdentitySet();
                for (Transition t : bbs.transitions()) slice.add(t);
                splitterList.addLast(new Splitter(bbs.sourceBlock, slice, slice, true, currentGroupId++));
            }
        }
        timingMap.put("[getPartitions] initial bunch + splitter setup", (System.nanoTime() - t0) / 1_000_000.0);

        long tPhase1 = 0, tPhase2 = 0;
        int iterCount = 0;
        while (!splitterList.isEmpty() || Lt.hasUnstable()) {
            iterCount++;

            // FASE 1: ESTABILIZAR ESTADOS
            long tP1start = System.nanoTime();
            while (!splitterList.isEmpty()) {
                Splitter currentSplitter = splitterList.removeFirst();
                RefinablePartition.Block B = currentSplitter.block;

                if (!B.isAlive()) continue;

                Pair<RefinablePartition.Block, RefinablePartition.Block> splitResult = split(B, Pi_s, Lt, currentSplitter.transitions, currentSplitter.marks, toMinimise, tauLabels, stateToSCCMap);
                RefinablePartition.Block R = splitResult.getFirst();
                RefinablePartition.Block U = splitResult.getSecond();

                if (R == null || U == null) continue;

                if (currentSplitter.isPrimary) {
                    Iterator<Splitter> it = splitterList.iterator();
                    while (it.hasNext()) {
                        Splitter sec = it.next();
                        if (sec.groupId == currentSplitter.groupId && sec.block == B && !sec.isPrimary) {
                            it.remove();

                            Set<Transition> secTransForR = newIdentitySet();
                            for (Transition t : sec.transitions) {
                                if (Pi_s.blockOf(t.source) == R) secTransForR.add(t);
                            }

                            if (!secTransForR.isEmpty()) {
                                Set<Transition> secMarksForR = newIdentitySet();
                                for (Transition t : sec.marks) {
                                    if (Pi_s.blockOf(t.source) == R) secMarksForR.add(t);
                                }
                                if (secMarksForR.isEmpty()) secMarksForR.addAll(secTransForR);
                                splitterList.addFirst(new Splitter(R, secTransForR, secMarksForR, false, sec.groupId));
                            }
                            break;
                        }
                    }
                }

                refineSplitters(B, R, U, splitterList, Pi_s, toMinimise, tauLabels, stateToSCCMap);

                Queue<Pair<RefinablePartition.Block, RefinablePartition.Block>> newFrontiers = new ArrayDeque<>();
                newFrontiers.add(new Pair<>(R, U));
                newFrontiers.add(new Pair<>(U, R));

                while (!newFrontiers.isEmpty()) {
                    Pair<RefinablePartition.Block, RefinablePartition.Block> frontier = newFrontiers.poll();
                    RefinablePartition.Block src = frontier.getFirst();
                    RefinablePartition.Block tgt = frontier.getSecond();

                    if (!src.isAlive() || !tgt.isAlive()) continue;

                    Map<String, Set<Transition>> crossTaus = findNewNonInertTransitions(src, tgt, Pi_s, Lt, toMinimise, tauLabels);
                    RefinablePartition.Block currentSrc = src;

                    for (Set<Transition> tNew : crossTaus.values()) {
                        if (!currentSrc.isAlive()) break;

                        Set<Transition> validTNew = newIdentitySet();
                        for (Transition t : tNew) {
                            if (Pi_s.blockOf(t.source) == currentSrc) validTNew.add(t);
                        }
                        if (validTNew.isEmpty()) continue;

                        // Crea un bunch nuevo con las transiciones que recién
                        // pasaron a ser no-inertes. Las BBS del bunch nuevo
                        // nacen inestables (lógica de Lt.newBunch).
                        BunchSlice newBunchSlice = Lt.newBunch(validTNew);

                        Pair<RefinablePartition.Block, RefinablePartition.Block> splitRes = split(currentSrc, Pi_s, Lt, validTNew, validTNew, toMinimise, tauLabels, stateToSCCMap);
                        RefinablePartition.Block N = splitRes.getFirst();
                        RefinablePartition.Block src_prime = splitRes.getSecond();

                        if (N != null && src_prime != null) {
                            refineSplitters(currentSrc, N, src_prime, splitterList, Pi_s, toMinimise, tauLabels, stateToSCCMap);

                            newFrontiers.add(new Pair<>(N, src_prime));
                            newFrontiers.add(new Pair<>(src_prime, N));
                            newFrontiers.add(new Pair<>(N, tgt));
                            newFrontiers.add(new Pair<>(src_prime, tgt));
                        }

                        if (N != null) {
                            findBottomStates(N, Pi_s, toMinimise, tauLabels, stateToSCCMap);
                            for (BunchSlice b : Lt.liveBunches()) {
                                // Jansen et al. §5.2 opt 2: N ya es estable wrt newBunchSlice
                                // (es lo que partió currentSrc en N). Identidad por referencia.
                                if (b == newBunchSlice) continue;

                                BlockBunchSlice candidate = b.blockSliceFor(N);
                                if (candidate == null || candidate.size() == 0) continue;

                                Set<Transition> slice = newIdentitySet();
                                for (Transition t : candidate.transitions()) slice.add(t);

                                Set<Transition> marks = newIdentitySet();
                                Map<Long, List<Transition>> bySource = new HashMap<>();
                                for (Transition t : slice) bySource.computeIfAbsent(t.source, k -> new ArrayList<>()).add(t);

                                for (Long bottom : Pi_s.bottoms(N)) {
                                    if (bySource.containsKey(bottom)) marks.add(bySource.get(bottom).get(0));
                                }
                                if (!marks.isEmpty()) {
                                    splitterList.addLast(new Splitter(N, slice, marks, false, currentGroupId++));
                                }
                            }
                        }

                        // Si no hubo partición real (R==B o R==∅), cortar el for-loop:
                        // currentSrc no tiene a quién apuntar (mismo guard que v3-B).
                        if (N == null || src_prime == null) break;
                        currentSrc = N;
                    }
                }
            }

            tPhase1 += System.nanoTime() - tP1start;

            // FASE 2: REFINAR BUNCHES
            //
            // En v3-B esto popeaba un bunch entero de Pi_t_cola y buscaba una
            // sub-slice ≤ |bunch|/2 con un Map<Pair<String, Integer>, Set>.
            // Acá popeamos una BlockBunchSlice de Lt.globalUnstable y refinamos
            // su bunch con Lt.peelSlice (que hace el mismo trabajo de subagrupar
            // por (action, target_block.id) y elegir smaller-half).
            long tP2start = System.nanoTime();
            if (Lt.hasUnstable()) {
                BlockBunchSlice unstable = Lt.popUnstable();
                if (unstable == null || !unstable.isAlive()) {
                    tPhase2 += System.nanoTime() - tP2start;
                    continue;
                }
                BunchSlice bunch = unstable.bunch;
                if (!bunch.isAlive()) {
                    tPhase2 += System.nanoTime() - tP2start;
                    continue;
                }

                BunchSlice newBunch = Lt.peelSlice(bunch);
                if (newBunch == null) {
                    // Bunch trivial (única action-target-block-slice): no es refinable.
                    tPhase2 += System.nanoTime() - tP2start;
                    continue;
                }

                // peelSlice pela una slice GLOBAL del bunch, que puede no tocar el
                // bloque de la BBS que popeamos. Si esa BBS sigue viva y todavía es
                // divisible (su bloque no es estable wrt el bunch), la re-marcamos
                // inestable: si no, quedaría marcada estable sin haber sido refinada
                // y su bloque nunca se separaría (sub-fusión).
                if (unstable.isAlive() && Lt.isRefinable(unstable)) {
                    Lt.markUnstable(unstable);
                }

                // Bloques afectados por el peel: cualquiera con transiciones en el
                // bunch nuevo (= la "smaller half" elegida por peelSlice).
                Set<RefinablePartition.Block> affectedBlocks = newIdentityBlockSet();
                for (Transition t : newBunch.transitions()) {
                    RefinablePartition.Block sb = Pi_s.blockOf(t.source);
                    if (sb != null) affectedBlocks.add(sb);
                }

                for (RefinablePartition.Block block : affectedBlocks) {
                    Set<Transition> primaryTrans = newIdentitySet();
                    for (Transition t : newBunch.transitions()) {
                        if (Pi_s.blockOf(t.source) == block) primaryTrans.add(t);
                    }

                    Set<Transition> secondaryTrans = newIdentitySet();
                    for (Transition t : bunch.transitions()) {
                        if (Pi_s.blockOf(t.source) == block) secondaryTrans.add(t);
                    }

                    long groupId = currentGroupId++;

                    Set<Transition> primaryMarks = newIdentitySet();
                    primaryMarks.addAll(primaryTrans);
                    splitterList.addLast(new Splitter(block, primaryTrans, primaryMarks, true, groupId));

                    if (!secondaryTrans.isEmpty()) {
                        Set<Transition> secondaryMarks = newIdentitySet();
                        Map<Long, List<Transition>> secondaryBySource = new HashMap<>();
                        for (Transition t : secondaryTrans) {
                            secondaryBySource.computeIfAbsent(t.source, k -> new ArrayList<>()).add(t);
                        }

                        Set<Long> primarySources = new HashSet<>();
                        for (Transition t : primaryTrans) primarySources.add(t.source);

                        for (Long state : primarySources) {
                            if (secondaryBySource.containsKey(state)) {
                                secondaryMarks.add(secondaryBySource.get(state).get(0));
                            }
                        }
                        splitterList.addLast(new Splitter(block, secondaryTrans, secondaryMarks, false, groupId));
                    }
                }
            }
            tPhase2 += System.nanoTime() - tP2start;
        }

        timingMap.put("[getPartitions] main loop iterations", (double) iterCount);
        timingMap.put("[getPartitions] Phase 1 (stabilize states) total", tPhase1 / 1_000_000.0);
        timingMap.put("[getPartitions] Phase 2 (refine bunches) total", tPhase2 / 1_000_000.0);
        timingMap.put("[getPartitions] total", (System.nanoTime() - tTotal) / 1_000_000.0);

        // Vista legacy List<Set<Long>> + List<Set<Triple>> para el consumidor
        // externo (CompositionalApproach, buildMinimisedMTS).
        return new Pair<>(new Pair<>(Pi_s.asLegacyView(), Lt.asLegacyView()), timingMap);
    }

    private static void refineSplitters(
            RefinablePartition.Block oldBlock,
            RefinablePartition.Block R,
            RefinablePartition.Block U,
            Deque<Splitter> splitterList,
            RefinablePartition Pi_s,
            MTS<Long, String> toMinimise,
            Set<String> tauLabels,
            Map<Long, Set<Long>> stateToSCCMap) {

        List<Splitter> tasksToAdd = new ArrayList<>();
        Iterator<Splitter> iter = splitterList.iterator();

        boolean bottomsRComputed = false;
        boolean bottomsUComputed = false;

        while (iter.hasNext()) {
            Splitter pending = iter.next();
            if (pending.block == oldBlock) {
                iter.remove();

                Set<Transition> transR = newIdentitySet();
                Set<Transition> transU = newIdentitySet();
                for (Transition t : pending.transitions) {
                    if (Pi_s.blockOf(t.source) == R) transR.add(t);
                    else transU.add(t);
                }

                Set<Transition> marksR = newIdentitySet();
                Set<Transition> marksU = newIdentitySet();

                if (pending.isPrimary) {
                    marksR.addAll(transR);
                    marksU.addAll(transU);
                } else {
                    if (!bottomsRComputed) {
                        findBottomStates(R, Pi_s, toMinimise, tauLabels, stateToSCCMap);
                        bottomsRComputed = true;
                    }
                    if (!bottomsUComputed) {
                        findBottomStates(U, Pi_s, toMinimise, tauLabels, stateToSCCMap);
                        bottomsUComputed = true;
                    }

                    Map<Long, List<Transition>> bySourceR = new HashMap<>();
                    for (Transition t : transR) {
                        bySourceR.computeIfAbsent(t.source, k -> new ArrayList<>()).add(t);
                    }
                    for (Long b : Pi_s.bottoms(R)) {
                        if (bySourceR.containsKey(b)) marksR.add(bySourceR.get(b).get(0));
                    }
                    if (marksR.isEmpty() && !transR.isEmpty()) marksR.addAll(transR);

                    Map<Long, List<Transition>> bySourceU = new HashMap<>();
                    for (Transition t : transU) {
                        bySourceU.computeIfAbsent(t.source, k -> new ArrayList<>()).add(t);
                    }
                    for (Long b : Pi_s.bottoms(U)) {
                        if (bySourceU.containsKey(b)) marksU.add(bySourceU.get(b).get(0));
                    }
                    if (marksU.isEmpty() && !transU.isEmpty()) marksU.addAll(transU);
                }

                if (!transR.isEmpty()) tasksToAdd.add(new Splitter(R, transR, marksR, pending.isPrimary, pending.groupId));
                if (!transU.isEmpty()) tasksToAdd.add(new Splitter(U, transU, marksU, pending.isPrimary, pending.groupId));
            }
        }
        for (int i = tasksToAdd.size() - 1; i >= 0; i--) {
            splitterList.addFirst(tasksToAdd.get(i));
        }
    }

    /**
     * §5.1 split con abort-on-half. Dos BFS hacia atrás corren en lockstep
     * sobre el sub-DAG de SCCs τ-conexas dentro de B:
     * <ul>
     *   <li><b>forward (lado R):</b> sembrado con las SCCs de los splitter
     *       sources, expande por τ-pred-SCCs dentro de B. Equivalente a la
     *       BFS hacia atrás de v3-B pero a nivel de SCC en lugar de estado.</li>
     *   <li><b>reverse (lado U):</b> sembrado con las SCCs <i>sumidero</i> del
     *       sub-DAG (las que no tienen τ-outs dentro de B, que NO sean R₀).
     *       Mantiene un contador {@code untestedSCC[σ] = |τ-out-SCCs(σ) ∩ B|}.
     *       Cuando se clasifica una σ' como U, decrementa el contador de los
     *       predecesores de σ'; si el contador llega a 0 y σ' no es R, σ' es U.</li>
     * </ul>
     *
     * <p>Las SCCs (precomputadas en {@code partitionIntoSCCWithTauLabels})
     * actúan como super-estados: clasificar σ implica clasificar todos sus
     * estados. Es la regla que preserva la invariante de branching
     * bisimilarity sin trabajo adicional dentro del split.
     *
     * <p>Abort-on-half: cuando el tamaño <i>en estados</i> de un lado supera
     * {@code |B|/2}, ese lado es la "larger half" y se aborta. El otro continúa
     * hasta vaciar su queue (cota ≤ |B|/2) y los estados sin clasificar quedan
     * asignados al lado abortado. Esta es la optimización que cierra la
     * complejidad O(m log n) del paper (la BFS plana de v3-B era O(|B|) por
     * iteración).
     *
     * <p>Casos borde: si {@code R} queda vacío (ningún splitter source en B),
     * devuelve {@code (null, B)} sin tocar la partición. Si {@code R} cubre B,
     * devuelve {@code (B, null)}.
     *
     * <p>El parámetro {@code currentMarks} no se usa: el seed de R es
     * {@code transitions} (igual que v3-B). Se conserva por compatibilidad
     * con la firma de la rutina y con el patrón que usa {@code refineSplitters}.
     *
     * <p>{@code Lt.notifyBlockSplit} se invoca tras {@code Pi_s.splitOffR} para
     * redistribuir source-side BBS y marcar target-side BBS afectadas.
     */
    private static Pair<RefinablePartition.Block, RefinablePartition.Block> split(
            RefinablePartition.Block B,
            RefinablePartition Pi_s,
            LinkedTransitionPartitions Lt,
            Set<Transition> transitions,
            Set<Transition> currentMarks,
            MTS<Long, String> toMinimise,
            Set<String> tauLabels,
            Map<Long, Set<Long>> stateToSCCMap) {

        Pi_s.clearR(B);

        // ---- 1. Seed R-SCCs con las SCCs de los splitter sources --------
        Set<Set<Long>> rSCCs = newIdentitySCCSet();
        for (Transition t : transitions) {
            if (Pi_s.blockOf(t.source) == B) {
                rSCCs.add(stateToSCCMap.get(t.source));
            }
        }
        if (rSCCs.isEmpty()) {
            return new Pair<>(null, B);
        }

        // ---- 2. Relaciones SCC↔SCC y tamaños dentro de B -----------------
        // sccSizeInB[σ]   : # estados de σ que viven en B.
        // outSCCs[σ]      : SCCs σ' ≠ σ tales que ∃ s∈σ∩B, s'∈σ'∩B con (s,τ,s').
        // inSCCs[σ]       : la relación inversa de outSCCs.
        // No incluimos τ-edges intra-SCC: van quotientadas por la SCC.
        Map<Set<Long>, Integer> sccSizeInB = new LinkedHashMap<>();
        Map<Set<Long>, Set<Set<Long>>> outSCCs = new LinkedHashMap<>();
        Map<Set<Long>, Set<Set<Long>>> inSCCs = new LinkedHashMap<>();
        for (Long s : Pi_s.states(B)) {
            Set<Long> sccS = stateToSCCMap.get(s);
            sccSizeInB.merge(sccS, 1, Integer::sum);
            for (Pair<String, Long> trans : toMinimise.getTransitions(s, MTS.TransitionType.REQUIRED)) {
                if (!tauLabels.contains(trans.getFirst())) continue;
                Long target = trans.getSecond();
                if (Pi_s.blockOf(target) != B) continue;
                Set<Long> sccT = stateToSCCMap.get(target);
                if (sccS == sccT) continue;
                outSCCs.computeIfAbsent(sccS, k -> newIdentitySCCSet()).add(sccT);
                inSCCs.computeIfAbsent(sccT, k -> newIdentitySCCSet()).add(sccS);
            }
        }

        // untestedSCC[σ] = |outSCCs[σ]| inicialmente. Se decrementa cuando
        // alguna σ' ∈ outSCCs[σ] pasa a U; al llegar a 0 (y σ ∉ R), σ pasa a U.
        Map<Set<Long>, Integer> untestedSCC = new LinkedHashMap<>();
        for (Set<Long> scc : sccSizeInB.keySet()) {
            Set<Set<Long>> outs = outSCCs.get(scc);
            untestedSCC.put(scc, outs == null ? 0 : outs.size());
        }

        // ---- 3. Tamaños iniciales y queues ------------------------------
        int rSize = 0;
        Deque<Set<Long>> forwardQ = new ArrayDeque<>();
        for (Set<Long> scc : rSCCs) {
            Integer sz = sccSizeInB.get(scc);
            if (sz != null) rSize += sz;
            forwardQ.add(scc);
        }

        // U-leaves: SCCs sin τ-outs dentro de B (untested=0) que NO estén en R.
        // Por la propiedad DAG, siempre hay al menos una sumidero salvo que B
        // no tenga τ-edges internas; si todas las sumidero están en R, R cubre
        // B (cualquier σ ∈ B alcanza alguna sumidero ∈ R₀).
        Set<Set<Long>> uSCCs = newIdentitySCCSet();
        Deque<Set<Long>> reverseQ = new ArrayDeque<>();
        int uSize = 0;
        for (Map.Entry<Set<Long>, Integer> e : untestedSCC.entrySet()) {
            Set<Long> scc = e.getKey();
            if (rSCCs.contains(scc)) continue;
            if (e.getValue() == 0) {
                uSCCs.add(scc);
                reverseQ.add(scc);
                uSize += sccSizeInB.get(scc);
            }
        }

        int half = B.size() / 2;
        boolean forwardAbort = rSize > half;
        boolean reverseAbort = uSize > half;

        // ---- 4. Lockstep BFS con abort-on-half --------------------------
        while (!forwardAbort && !reverseAbort) {
            if (forwardQ.isEmpty()) break;
            rSize = forwardStep(forwardQ, rSCCs, inSCCs, sccSizeInB, rSize);
            if (rSize > half) { forwardAbort = true; break; }

            if (reverseQ.isEmpty()) break;
            uSize = reverseStep(reverseQ, uSCCs, rSCCs, untestedSCC, inSCCs, sccSizeInB, uSize);
            if (uSize > half) { reverseAbort = true; break; }
        }

        // ---- 5. Continuación del lado más chico + apply R ---------------
        // Cuatro casos según cómo salimos del lockstep:
        //   forwardAbort: R era más grande; drenar reverse y tomar R = B \ U.
        //   reverseAbort: U era más grande; drenar forward y tomar R = rSCCs.
        //   forwardQ vacía sin abortar: R drenó natural; R = rSCCs.
        //   reverseQ vacía sin abortar: U drenó natural; R = B \ U.
        if (forwardAbort) {
            while (!reverseQ.isEmpty()) {
                uSize = reverseStep(reverseQ, uSCCs, rSCCs, untestedSCC, inSCCs, sccSizeInB, uSize);
            }
            applyComplement(Pi_s, B, uSCCs, stateToSCCMap);
        } else if (reverseAbort) {
            while (!forwardQ.isEmpty()) {
                rSize = forwardStep(forwardQ, rSCCs, inSCCs, sccSizeInB, rSize);
            }
            applyExplicit(Pi_s, B, rSCCs, stateToSCCMap);
        } else if (forwardQ.isEmpty()) {
            applyExplicit(Pi_s, B, rSCCs, stateToSCCMap);
        } else {
            // reverseQ.isEmpty()
            applyComplement(Pi_s, B, uSCCs, stateToSCCMap);
        }

        // ---- 6. Materializar el split y notificar a Lt ------------------
        Pair<RefinablePartition.Block, RefinablePartition.Block> result = Pi_s.splitOffR(B);
        RefinablePartition.Block rBlock = result.getFirst();
        RefinablePartition.Block uBlock = result.getSecond();
        if (rBlock != null && uBlock != null) {
            Lt.notifyBlockSplit(B, uBlock, rBlock);
        }
        return result;
    }

    /**
     * Un step del lado R. Toma una SCC σ del frente de {@code forwardQ},
     * expande sus τ-pred-SCCs dentro de B, y agrega las nuevas a {@code rSCCs}
     * y a la queue. Devuelve el {@code rSize} actualizado (acumula los
     * estados-en-B de cada SCC nueva).
     */
    private static int forwardStep(
            Deque<Set<Long>> forwardQ,
            Set<Set<Long>> rSCCs,
            Map<Set<Long>, Set<Set<Long>>> inSCCs,
            Map<Set<Long>, Integer> sccSizeInB,
            int rSize) {
        Set<Long> sigma = forwardQ.poll();
        Set<Set<Long>> preds = inSCCs.get(sigma);
        if (preds == null) return rSize;
        for (Set<Long> pred : preds) {
            if (rSCCs.add(pred)) {
                forwardQ.add(pred);
                Integer sz = sccSizeInB.get(pred);
                if (sz != null) rSize += sz;
            }
        }
        return rSize;
    }

    /**
     * Un step del lado U. Toma una SCC σ' del frente de {@code reverseQ}
     * (recién clasificada como U) y, para cada τ-pred-SCC σ dentro de B,
     * decrementa {@code untestedSCC[σ]}. Las SCCs ya clasificadas R o U se
     * saltean. Si {@code untestedSCC[σ]} llega a 0, σ pasa a U y se encola.
     * Devuelve el {@code uSize} actualizado.
     */
    private static int reverseStep(
            Deque<Set<Long>> reverseQ,
            Set<Set<Long>> uSCCs,
            Set<Set<Long>> rSCCs,
            Map<Set<Long>, Integer> untestedSCC,
            Map<Set<Long>, Set<Set<Long>>> inSCCs,
            Map<Set<Long>, Integer> sccSizeInB,
            int uSize) {
        Set<Long> sigmaU = reverseQ.poll();
        Set<Set<Long>> uPreds = inSCCs.get(sigmaU);
        if (uPreds == null) return uSize;
        for (Set<Long> pred : uPreds) {
            if (rSCCs.contains(pred)) continue;
            if (uSCCs.contains(pred)) continue;
            int u = untestedSCC.get(pred) - 1;
            untestedSCC.put(pred, u);
            if (u == 0) {
                uSCCs.add(pred);
                reverseQ.add(pred);
                Integer sz = sccSizeInB.get(pred);
                if (sz != null) uSize += sz;
            }
        }
        return uSize;
    }

    /** Marca como R en {@code Pi_s} cada estado de B cuya SCC está en {@code rSCCs}.
     *  Snapshot previo: {@code addToR} mueve el estado a {@code rDestEnd-1} y trae
     *  al iterador el que estaba ahí, lo que hace que el iterador posicional de
     *  {@code Pi_s.states(B)} salte estados — en particular el último de B se
     *  pierde si el primero entra a R. */
    private static void applyExplicit(
            RefinablePartition Pi_s,
            RefinablePartition.Block B,
            Set<Set<Long>> rSCCs,
            Map<Long, Set<Long>> stateToSCCMap) {
        List<Long> snapshot = new ArrayList<>(B.size());
        for (Long s : Pi_s.states(B)) snapshot.add(s);
        for (Long s : snapshot) {
            if (rSCCs.contains(stateToSCCMap.get(s))) {
                Pi_s.addToR(s);
            }
        }
    }

    /** Marca como R en {@code Pi_s} cada estado de B cuya SCC NO está en
     *  {@code uSCCs} (R := B \ U). Útil cuando el lado U drenó completo y R
     *  quedó implícito. Snapshot previo por la misma razón que
     *  {@link #applyExplicit}. */
    private static void applyComplement(
            RefinablePartition Pi_s,
            RefinablePartition.Block B,
            Set<Set<Long>> uSCCs,
            Map<Long, Set<Long>> stateToSCCMap) {
        List<Long> snapshot = new ArrayList<>(B.size());
        for (Long s : Pi_s.states(B)) snapshot.add(s);
        for (Long s : snapshot) {
            if (!uSCCs.contains(stateToSCCMap.get(s))) {
                Pi_s.addToR(s);
            }
        }
    }

    /**
     * Marca como "bottom" los estados de {@code N} cuya SCC τ-conexa no tiene
     * salidas τ a otra SCC dentro de {@code N}. MUTA la partición: los bottoms
     * quedan en el prefijo {@code [start..bottomEnd)} del bloque, y se iteran
     * con {@code Pi_s.bottoms(N)}.
     */
    private static void findBottomStates(
            RefinablePartition.Block N,
            RefinablePartition Pi_s,
            MTS<Long, String> toMinimise,
            Set<String> tauLabels,
            Map<Long, Set<Long>> stateToSCCMap) {

        Pi_s.resetBottoms(N);

        Set<Set<Long>> uniqueSCCsInN = new HashSet<>();
        for (Long state : Pi_s.states(N)) {
            uniqueSCCsInN.add(stateToSCCMap.get(state));
        }

        for (Set<Long> scc : uniqueSCCsInN) {
            boolean sccHasOutgoingTauInN = false;
            for (Long s : scc) {
                for (Pair<String, Long> transition : toMinimise.getTransitions(s, MTS.TransitionType.REQUIRED)) {
                    String label = transition.getFirst();
                    Long destination = transition.getSecond();
                    if (tauLabels.contains(label) && Pi_s.blockOf(destination) == N) {
                        if (!stateToSCCMap.get(s).equals(stateToSCCMap.get(destination))) {
                            sccHasOutgoingTauInN = true;
                            break;
                        }
                    }
                }
                if (sccHasOutgoingTauInN) break;
            }
            if (!sccHasOutgoingTauInN) {
                for (Long s : scc) {
                    if (Pi_s.blockOf(s) == N) {
                        Pi_s.markAsBottom(s);
                    }
                }
            }
        }
    }

    /**
     * Descubre transiciones τ que cruzan {@code srcBlock → tgtBlock} (recién
     * dejaron de ser inertes por un split previo). Las registra en {@code Lt}
     * (silently dedupe si ya existían) y las devuelve agrupadas por etiqueta.
     */
    private static Map<String, Set<Transition>> findNewNonInertTransitions(
            RefinablePartition.Block srcBlock,
            RefinablePartition.Block tgtBlock,
            RefinablePartition Pi_s,
            LinkedTransitionPartitions Lt,
            MTS<Long, String> toMinimise,
            Set<String> tauLabels) {

        Map<String, Set<Transition>> result = new HashMap<>();
        for (Long s : Pi_s.states(srcBlock)) {
            for (Pair<String, Long> transition : toMinimise.getTransitions(s, MTS.TransitionType.REQUIRED)) {
                String label = transition.getFirst();
                Long destination = transition.getSecond();
                if (tauLabels.contains(label) && Pi_s.blockOf(destination) == tgtBlock) {
                    Transition tr = Lt.addTransition(s, label, destination);
                    result.computeIfAbsent(label, k -> newIdentitySet()).add(tr);
                }
            }
        }
        return result;
    }

    private static Set<Transition> newIdentitySet() {
        return Collections.newSetFromMap(new LinkedHashMap<>());
    }

    private static Set<RefinablePartition.Block> newIdentityBlockSet() {
        return Collections.newSetFromMap(new LinkedHashMap<>());
    }

    /**
     * Set por identidad para SCCs (los {@code Set<Long>} canónicos que
     * devuelve {@code stateToSCCMap}). Usar identidad evita el costo
     * O(|σ|) de hashear un {@code Set<Long>} por estructura.
     */
    private static Set<Set<Long>> newIdentitySCCSet() {
        return Collections.newSetFromMap(new LinkedHashMap<>());
    }

    private static Set<Long> computeBvis(MTS<Long, String> toMinimise, List<Set<Long>> tauSCCs, Map<Long, Set<Long>> stateToSCC, Set<String> tauLabels) {
        Map<Set<Long>, Integer> sccToId = new HashMap<>();
        List<Set<Long>> idToScc = new ArrayList<>();
        for (int i = 0; i < tauSCCs.size(); i++) {
            sccToId.put(tauSCCs.get(i), i);
            idToScc.add(tauSCCs.get(i));
        }

        List<Set<Integer>> predGraph = new ArrayList<>();
        for (int i = 0; i < tauSCCs.size(); i++) predGraph.add(new HashSet<>());

        Set<Integer> visibleSccIds = new HashSet<>();

        for (int sccId = 0; sccId < tauSCCs.size(); sccId++) {
            Set<Long> scc = idToScc.get(sccId);
            boolean isVisible = false;

            for (Long state : scc) {
                for (Pair<String, Long> t : toMinimise.getTransitions(state, MTS.TransitionType.REQUIRED)) {
                    String label = t.getFirst();
                    Long target = t.getSecond();

                    if (!tauLabels.contains(label)) {
                        isVisible = true;
                    } else {
                        Set<Long> targetSccSet = stateToSCC.get(target);
                        if (!targetSccSet.equals(scc)) {
                            Integer targetId = sccToId.get(targetSccSet);
                            if (targetId != null) {
                                predGraph.get(targetId).add(sccId);
                            }
                        }
                    }
                }
            }
            if (isVisible) {
                visibleSccIds.add(sccId);
            }
        }

        Deque<Integer> stack = new ArrayDeque<>(visibleSccIds);
        boolean[] isVis = new boolean[tauSCCs.size()];
        for(Integer id : visibleSccIds) isVis[id] = true;

        while (!stack.isEmpty()) {
            Integer currentId = stack.pop();
            for (Integer predId : predGraph.get(currentId)) {
                if (!isVis[predId]) {
                    isVis[predId] = true;
                    stack.push(predId);
                }
            }
        }

        Set<Long> Bvis = new HashSet<>();
        for (int i = 0; i < isVis.length; i++) {
            if (isVis[i]) {
                Bvis.addAll(idToScc.get(i));
            }
        }

        return Bvis;
    }

    /**
     * Iterative Tarjan's algorithm for finding strongly connected components
     * in the tau-subgraph. Single DFS pass, no reversed graph needed.
     * Replaces the old Kosaraju-based implementation (two DFS passes + reversed graph).
     */
    private static List<Set<Long>> partitionIntoSCCWithTauLabels(
            MTS<Long, String> toMinimise,
            Set<String> tauLabels) {

        Set<Long> states = toMinimise.getStates();
        int n = states.size();

        Map<Long, Integer> stateToId = new HashMap<>(n * 2);
        Long[] byId = new Long[n];
        int idx = 0;
        for (Long s : states) {
            stateToId.put(s, idx);
            byId[idx] = s;
            idx++;
        }

        int[] index = new int[n];
        int[] lowlink = new int[n];
        boolean[] onStack = new boolean[n];
        Arrays.fill(index, -1);

        Deque<Integer> tarjanStack = new ArrayDeque<>();
        List<Set<Long>> sccs = new ArrayList<>();
        int[] indexCounter = {0};

        @SuppressWarnings("unchecked")
        List<Integer>[] tauChildren = new List[n];
        for (int i = 0; i < n; i++) {
            tauChildren[i] = new ArrayList<>();
            for (Pair<String, Long> t : toMinimise.getTransitions(byId[i], MTS.TransitionType.REQUIRED)) {
                if (tauLabels.contains(t.getFirst())) {
                    Integer targetId = stateToId.get(t.getSecond());
                    if (targetId != null) {
                        tauChildren[i].add(targetId);
                    }
                }
            }
        }

        for (int root = 0; root < n; root++) {
            if (index[root] != -1) continue;

            Deque<int[]> callStack = new ArrayDeque<>();

            index[root] = indexCounter[0];
            lowlink[root] = indexCounter[0];
            indexCounter[0]++;
            tarjanStack.push(root);
            onStack[root] = true;
            callStack.push(new int[]{root, 0});

            while (!callStack.isEmpty()) {
                int[] frame = callStack.peek();
                int v = frame[0];
                List<Integer> children = tauChildren[v];

                if (frame[1] < children.size()) {
                    int w = children.get(frame[1]);
                    frame[1]++;

                    if (index[w] == -1) {
                        index[w] = indexCounter[0];
                        lowlink[w] = indexCounter[0];
                        indexCounter[0]++;
                        tarjanStack.push(w);
                        onStack[w] = true;
                        callStack.push(new int[]{w, 0});
                    } else if (onStack[w]) {
                        if (index[w] < lowlink[v]) {
                            lowlink[v] = index[w];
                        }
                    }
                } else {
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
                        int parent = callStack.peek()[0];
                        if (lowlink[v] < lowlink[parent]) {
                            lowlink[parent] = lowlink[v];
                        }
                    }
                }
            }
        }
        return sccs;
    }
}
