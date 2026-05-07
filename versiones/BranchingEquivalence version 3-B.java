package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional;

import MTSSynthesis.ar.dc.uba.model.condition.Fluent;
import MTSSynthesis.ar.dc.uba.model.language.Symbol;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.MTSImpl;
import org.jgrapht.alg.util.Triple;

import java.util.*;
import java.util.stream.Collectors;

import static MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.Utils.translateFromOriginal;

/**
 * v3-B: la partición de estados Pi_s pasa a ser una RefinablePartition (Jansen
 * et al. 2019, §5.1 opt #4). Reemplaza el viejo {@code List<Set<Long>> Pi_s}
 * + {@code Map<Long, Set<Long>> stateToBlockMap} + {@code IdentityHashMap<Set<Long>, Integer> blockIdMap}
 * por un único array maestro de estados con bloques como slices contiguos.
 *
 * <p>Cambios estructurales respecto de v3 (versión "3-A1 y D"):
 * <ul>
 *   <li>{@code Splitter.block} ahora es {@link RefinablePartition.Block} (identidad por referencia).</li>
 *   <li>{@code stateToBlockMap.get(s)} → {@code Pi_s.blockOf(s)}, O(1) por array.</li>
 *   <li>{@code Pi_s.contains(B)} → {@code B.isAlive()}, O(1) por flag.</li>
 *   <li>{@code blockIdMap} eliminado: cada Block tiene su id propio.</li>
 *   <li>{@code findBottomStates} ahora muta la partición: marca los bottoms en el
 *       prefijo {@code [start..bottomEnd)} del bloque y los callers iteran con
 *       {@link RefinablePartition#bottoms(RefinablePartition.Block)}.</li>
 * </ul>
 *
 * <p>Las optimizaciones §5.2 #2 (saltar el bunch recién aplicado) y la fase D
 * (Tarjan iterativo) se mantienen tal cual de v3-A1.
 */
public class BranchingEquivalence {

    static class Splitter {
        final RefinablePartition.Block block;
        final Set<Triple<Long, String, Long>> transitions;
        final Set<Triple<Long, String, Long>> marks;
        final boolean isPrimary;
        final long groupId;

        public Splitter(RefinablePartition.Block block, Set<Triple<Long, String, Long>> transitions, Set<Triple<Long, String, Long>> marks, boolean isPrimary, long groupId) {
            this.block = block;
            this.transitions = transitions;
            this.marks = marks;
            this.isPrimary = isPrimary;
            this.groupId = groupId;
        }
    }

    static class IdentityQueue<T> {
        private Deque<T> queue = new ArrayDeque<>();
        private Set<T> set = Collections.newSetFromMap(new IdentityHashMap<>());

        public void add(T item) {
            if (set.add(item)) {
                queue.addLast(item);
            }
        }
        public T pop() {
            T item = queue.removeFirst();
            set.remove(item);
            return item;
        }
        public boolean isEmpty() { return queue.isEmpty(); }
        public boolean contains(T item) { return set.contains(item); }
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

        // Pi_s ahora es una RefinablePartition. Cada bloque inicial es un slice
        // contiguo del array maestro; blockOf(s) es O(1) y elimina la necesidad
        // de stateToBlockMap y blockIdMap.
        RefinablePartition Pi_s = new RefinablePartition(toMinimise.getStates());
        List<Set<Long>> initialBlocks = new ArrayList<>();
        if (!errorBlock.isEmpty()) initialBlocks.add(errorBlock);
        if (!Bvis.isEmpty()) initialBlocks.add(Bvis);
        if (!Binvis.isEmpty()) initialBlocks.add(Binvis);
        Pi_s.seedFromInitialBlocks(initialBlocks);

        t0 = System.nanoTime();
        Set<Triple<Long, String, Long>> initialBunch = new HashSet<>();

        for (Long s : toMinimise.getStates()) {
            for (Pair<String, Long> t : toMinimise.getTransitions(s, MTS.TransitionType.REQUIRED)) {
                String a = t.getFirst();
                Long sPrime = t.getSecond();

                if (!tauLabels.contains(a)) {
                    initialBunch.add(new Triple<>(s, a, sPrime));
                } else {
                    RefinablePartition.Block sourceBlock = Pi_s.blockOf(s);
                    RefinablePartition.Block targetBlock = Pi_s.blockOf(sPrime);
                    if (sourceBlock != null && targetBlock != null && sourceBlock != targetBlock) {
                        initialBunch.add(new Triple<>(s, a, sPrime));
                    }
                }
            }
        }

        Set<Set<Triple<Long, String, Long>>> Pi_t = Collections.newSetFromMap(new IdentityHashMap<>());
        if (!initialBunch.isEmpty()) {
            Pi_t.add(initialBunch);
        }

        IdentityQueue<Set<Triple<Long, String, Long>>> Pi_t_cola = new IdentityQueue<>();
        for (Set<Triple<Long, String, Long>> bunch : Pi_t) {
            Pi_t_cola.add(bunch);
        }

        Deque<Splitter> splitterList = new ArrayDeque<>();
        long currentGroupId = 0;

        Map<Long, Set<Set<Triple<Long, String, Long>>>> targetStateToBunches = new HashMap<>();
        for (Set<Triple<Long, String, Long>> bunch : Pi_t) {
            for (Triple<Long, String, Long> t : bunch) {
                targetStateToBunches.computeIfAbsent(t.getThird(), k -> Collections.newSetFromMap(new IdentityHashMap<>())).add(bunch);
            }
        }

        for (Set<Triple<Long, String, Long>> bunch : Pi_t) {
            // Agrupar por bloque de origen vía identidad de Block (Block no
            // overridea hashCode/equals, así que un HashMap<Block, ...> ya usa
            // identidad por defecto).
            Map<RefinablePartition.Block, List<Triple<Long, String, Long>>> bunchBySource = bunch.stream()
                    .filter(t -> Pi_s.blockOf(t.getFirst()) != null)
                    .collect(Collectors.groupingBy(t -> Pi_s.blockOf(t.getFirst())));

            for (Map.Entry<RefinablePartition.Block, List<Triple<Long, String, Long>>> entry : bunchBySource.entrySet()) {
                RefinablePartition.Block block = entry.getKey();
                Set<Triple<Long, String, Long>> slice = new HashSet<>(entry.getValue());
                splitterList.addLast(new Splitter(block, slice, slice, true, currentGroupId++));
            }
        }
        timingMap.put("[getPartitions] initial bunch + splitter setup", (System.nanoTime() - t0) / 1_000_000.0);

        long tPhase1 = 0, tPhase2 = 0;
        int iterCount = 0;
        while (!Pi_t_cola.isEmpty() || !splitterList.isEmpty()) {
            iterCount++;

            // FASE 1: ESTABILIZAR ESTADOS
            long tP1start = System.nanoTime();
            while (!splitterList.isEmpty()) {
                Splitter currentSplitter = splitterList.removeFirst();
                RefinablePartition.Block B = currentSplitter.block;

                if (!B.isAlive()) continue;

                Pair<RefinablePartition.Block, RefinablePartition.Block> splitResult = split(B, Pi_s, currentSplitter.transitions, currentSplitter.marks, toMinimise, tauLabels, stateToSCCMap);
                RefinablePartition.Block R = splitResult.getFirst();
                RefinablePartition.Block U = splitResult.getSecond();

                if (R == null || U == null) continue;

                if (currentSplitter.isPrimary) {
                    Iterator<Splitter> it = splitterList.iterator();
                    while (it.hasNext()) {
                        Splitter sec = it.next();
                        if (sec.groupId == currentSplitter.groupId && sec.block == B && !sec.isPrimary) {
                            it.remove();

                            Set<Triple<Long, String, Long>> secTransForR = new HashSet<>();
                            for (Triple<Long, String, Long> t : sec.transitions) {
                                if (Pi_s.blockOf(t.getFirst()) == R) secTransForR.add(t);
                            }

                            if (!secTransForR.isEmpty()) {
                                Set<Triple<Long, String, Long>> secMarksForR = new HashSet<>();
                                for (Triple<Long, String, Long> t : sec.marks) {
                                    if (Pi_s.blockOf(t.getFirst()) == R) secMarksForR.add(t);
                                }
                                if (secMarksForR.isEmpty()) secMarksForR.addAll(secTransForR);
                                splitterList.addFirst(new Splitter(R, secTransForR, secMarksForR, false, sec.groupId));
                            }
                            break;
                        }
                    }
                }

                refineSplitters(B, R, U, splitterList, Pi_s, toMinimise, tauLabels, stateToSCCMap);
                enqueueAffectedBunches(R, Pi_s, targetStateToBunches, Pi_t_cola);

                Queue<Pair<RefinablePartition.Block, RefinablePartition.Block>> newFrontiers = new ArrayDeque<>();
                newFrontiers.add(new Pair<>(R, U));
                newFrontiers.add(new Pair<>(U, R));

                while (!newFrontiers.isEmpty()) {
                    Pair<RefinablePartition.Block, RefinablePartition.Block> frontier = newFrontiers.poll();
                    RefinablePartition.Block src = frontier.getFirst();
                    RefinablePartition.Block tgt = frontier.getSecond();

                    if (!src.isAlive() || !tgt.isAlive()) continue;

                    Map<String, Set<Triple<Long, String, Long>>> crossTaus = findNewNonInertTransitions(src, tgt, Pi_s, toMinimise, tauLabels);
                    RefinablePartition.Block currentSrc = src;

                    for (Set<Triple<Long, String, Long>> tNew : crossTaus.values()) {
                        if (!currentSrc.isAlive()) break;

                        Set<Triple<Long, String, Long>> validTNew = new HashSet<>();
                        for (Triple<Long, String, Long> t : tNew) {
                            if (Pi_s.blockOf(t.getFirst()) == currentSrc) validTNew.add(t);
                        }
                        if (validTNew.isEmpty()) continue;

                        Pi_t.add(validTNew);
                        Pi_t_cola.add(validTNew);
                        for (Triple<Long, String, Long> t : validTNew) {
                            targetStateToBunches.computeIfAbsent(t.getThird(), k -> Collections.newSetFromMap(new IdentityHashMap<>())).add(validTNew);
                        }

                        Pair<RefinablePartition.Block, RefinablePartition.Block> splitRes = split(currentSrc, Pi_s, validTNew, validTNew, toMinimise, tauLabels, stateToSCCMap);
                        RefinablePartition.Block N = splitRes.getFirst();
                        RefinablePartition.Block src_prime = splitRes.getSecond();

                        if (N != null && src_prime != null) {
                            refineSplitters(currentSrc, N, src_prime, splitterList, Pi_s, toMinimise, tauLabels, stateToSCCMap);
                            enqueueAffectedBunches(N, Pi_s, targetStateToBunches, Pi_t_cola);

                            newFrontiers.add(new Pair<>(N, src_prime));
                            newFrontiers.add(new Pair<>(src_prime, N));
                            newFrontiers.add(new Pair<>(N, tgt));
                            newFrontiers.add(new Pair<>(src_prime, tgt));
                        }

                        if (N != null) {
                            findBottomStates(N, Pi_s, toMinimise, tauLabels, stateToSCCMap);
                            for (Set<Triple<Long, String, Long>> b : Pi_t) {
                                // Jansen et al. §5.2 opt 2: N ya es estable wrt validTNew (es lo que
                                // partió currentSrc en N). Pi_t usa IdentityHashMap, así que la
                                // comparación correcta es identidad.
                                if (b == validTNew) continue;

                                Set<Triple<Long, String, Long>> slice = new HashSet<>();
                                for (Triple<Long, String, Long> t : b) {
                                    if (Pi_s.blockOf(t.getFirst()) == N) slice.add(t);
                                }
                                if (slice.isEmpty()) continue;

                                Set<Triple<Long, String, Long>> marks = new HashSet<>();
                                Map<Long, List<Triple<Long, String, Long>>> bySource = new HashMap<>();
                                for (Triple<Long, String, Long> t : slice) bySource.computeIfAbsent(t.getFirst(), k -> new ArrayList<>()).add(t);

                                for (Long bottom : Pi_s.bottoms(N)) {
                                    if (bySource.containsKey(bottom)) marks.add(bySource.get(bottom).get(0));
                                }
                                if (!marks.isEmpty()) {
                                    splitterList.addLast(new Splitter(N, slice, marks, false, currentGroupId++));
                                }
                            }
                        }

                        // Preservar la semántica de v3: si no hubo partición real (R==B o R==∅),
                        // cortar el for-loop. En v3 esto sucedía porque `currentSrc = N` apuntaba
                        // a un HashSet nuevo no presente en Pi_s, y la próxima iter rompía con
                        // !Pi_s.contains(currentSrc).
                        if (N == null || src_prime == null) break;
                        currentSrc = N;
                    }
                }
            }

            tPhase1 += System.nanoTime() - tP1start;

            // FASE 2: REFINAR BUNCHES
            long tP2start = System.nanoTime();
            if (!Pi_t_cola.isEmpty()) {
                Set<Triple<Long,String,Long>> bunch = Pi_t_cola.pop();
                Map<Pair<String, Integer>, Set<Triple<Long, String, Long>>> slices = new HashMap<>();

                for (Triple<Long, String, Long> t : bunch) {
                    Long targetState = t.getThird();
                    RefinablePartition.Block targetBlock = Pi_s.blockOf(targetState);
                    if (targetBlock == null) continue;

                    Pair<String, Integer> key = new Pair<>(t.getSecond(), targetBlock.id);
                    slices.computeIfAbsent(key, k -> new HashSet<>()).add(t);
                }

                if (slices.size() <= 1) continue;

                Set<Triple<Long, String, Long>> chosenTransitions = null;
                for (Map.Entry<Pair<String, Integer>, Set<Triple<Long, String, Long>>> entry : slices.entrySet()) {
                    long count = entry.getValue().size();
                    if (count > 0 && count <= (bunch.size() / 2)) {
                        chosenTransitions = entry.getValue();
                        break;
                    }
                }

                if (chosenTransitions == null) {
                    for (Map.Entry<Pair<String, Integer>, Set<Triple<Long, String, Long>>> entry : slices.entrySet()) {
                        if (!entry.getValue().isEmpty()) {
                            chosenTransitions = entry.getValue();
                            break;
                        }
                    }
                }

                Pi_t.remove(bunch);
                Set<Triple<Long, String, Long>> newBunch = new HashSet<>(bunch);
                newBunch.removeAll(chosenTransitions);

                Pi_t_cola.add(newBunch);
                Pi_t_cola.add(chosenTransitions);
                Pi_t.add(newBunch);
                Pi_t.add(chosenTransitions);

                for (Triple<Long, String, Long> t : chosenTransitions) {
                    Set<Set<Triple<Long, String, Long>>> set = targetStateToBunches.get(t.getThird());
                    if (set != null) { set.remove(bunch); set.add(chosenTransitions); }
                }
                for (Triple<Long, String, Long> t : newBunch) {
                    Set<Set<Triple<Long, String, Long>>> set = targetStateToBunches.get(t.getThird());
                    if (set != null) { set.remove(bunch); set.add(newBunch); }
                }

                for (RefinablePartition.Block block : findSplittableBlocks(chosenTransitions, Pi_s)) {

                    Set<Triple<Long, String, Long>> primaryTrans = new HashSet<>();
                    for (Triple<Long, String, Long> t : chosenTransitions) {
                        if (Pi_s.blockOf(t.getFirst()) == block) primaryTrans.add(t);
                    }

                    Set<Triple<Long, String, Long>> secondaryTrans = new HashSet<>();
                    for (Triple<Long, String, Long> t : newBunch) {
                        if (Pi_s.blockOf(t.getFirst()) == block) secondaryTrans.add(t);
                    }

                    long groupId = currentGroupId++;

                    Set<Triple<Long, String, Long>> primaryMarks = new HashSet<>(primaryTrans);
                    splitterList.addLast(new Splitter(block, primaryTrans, primaryMarks, true, groupId));

                    if (!secondaryTrans.isEmpty()) {
                        Set<Triple<Long, String, Long>> secondaryMarks = new HashSet<>();
                        Map<Long, List<Triple<Long, String, Long>>> secondaryBySource = new HashMap<>();
                        for (Triple<Long, String, Long> t : secondaryTrans) {
                            secondaryBySource.computeIfAbsent(t.getFirst(), k -> new ArrayList<>()).add(t);
                        }

                        Set<Long> primarySources = new HashSet<>();
                        for (Triple<Long, String, Long> t : primaryTrans) {
                            primarySources.add(t.getFirst());
                        }

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

        // Devolver vista legacy List<Set<Long>> al consumidor (CompositionalApproach
        // y buildMinimisedMTS esperan ese tipo).
        return new Pair<>(new Pair<>(Pi_s.asLegacyView(), new ArrayList<>(Pi_t)), timingMap);
    }

    private static void refineSplitters(
            RefinablePartition.Block oldBlock, RefinablePartition.Block R, RefinablePartition.Block U, Deque<Splitter> splitterList,
            RefinablePartition Pi_s, MTS<Long, String> toMinimise, Set<String> tauLabels, Map<Long, Set<Long>> stateToSCCMap) {

        List<Splitter> tasksToAdd = new ArrayList<>();
        Iterator<Splitter> iter = splitterList.iterator();

        boolean bottomsRComputed = false;
        boolean bottomsUComputed = false;

        while (iter.hasNext()) {
            Splitter pending = iter.next();
            if (pending.block == oldBlock) {
                iter.remove();

                Set<Triple<Long, String, Long>> transR = new HashSet<>();
                Set<Triple<Long, String, Long>> transU = new HashSet<>();
                for (Triple<Long, String, Long> t : pending.transitions) {
                    if (Pi_s.blockOf(t.getFirst()) == R) transR.add(t);
                    else transU.add(t);
                }

                Set<Triple<Long, String, Long>> marksR = new HashSet<>();
                Set<Triple<Long, String, Long>> marksU = new HashSet<>();

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

                    Map<Long, List<Triple<Long, String, Long>>> bySourceR = new HashMap<>();
                    for (Triple<Long, String, Long> t : transR) {
                        bySourceR.computeIfAbsent(t.getFirst(), k -> new ArrayList<>()).add(t);
                    }
                    for (Long b : Pi_s.bottoms(R)) {
                        if (bySourceR.containsKey(b)) marksR.add(bySourceR.get(b).get(0));
                    }
                    if (marksR.isEmpty() && !transR.isEmpty()) marksR.addAll(transR);

                    Map<Long, List<Triple<Long, String, Long>>> bySourceU = new HashMap<>();
                    for (Triple<Long, String, Long> t : transU) {
                        bySourceU.computeIfAbsent(t.getFirst(), k -> new ArrayList<>()).add(t);
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

    private static void enqueueAffectedBunches(RefinablePartition.Block R,
                                               RefinablePartition Pi_s,
                                               Map<Long, Set<Set<Triple<Long, String, Long>>>> targetStateToBunches,
                                               IdentityQueue<Set<Triple<Long, String, Long>>> Pi_t_cola) {
        for (Long stateInR : Pi_s.states(R)) {
            Set<Set<Triple<Long, String, Long>>> affectedBunches = targetStateToBunches.get(stateInR);
            if (affectedBunches != null) {
                for (Set<Triple<Long, String, Long>> affectedBunch : affectedBunches) {
                    Pi_t_cola.add(affectedBunch);
                }
            }
        }
    }

    /**
     * Parte el bloque B en R (estados que alcanzan via tau* alguna transición
     * en {@code transitions}) y U = B \ R. La partición se materializa en la
     * RefinablePartition usando {@code addToR} + {@code splitOffR}.
     *
     * <p>Devuelve (rBlock, uBlock); cualquiera puede ser null si esa parte
     * quedó vacía, indicando que B no se partió.
     *
     * <p>{@code currentMarks} actualmente no se usa en el cuerpo (se mantiene
     * por consistencia con la firma histórica); R se siembra desde
     * {@code transitions} expandiendo por SCCs τ-conexas.
     */
    private static Pair<RefinablePartition.Block, RefinablePartition.Block> split(
            RefinablePartition.Block B,
            RefinablePartition Pi_s,
            Set<Triple<Long, String, Long>> transitions,
            Set<Triple<Long, String, Long>> currentMarks,
            MTS<Long, String> toMinimise,
            Set<String> tauLabels,
            Map<Long, Set<Long>> stateToSCCMap) {

        Pi_s.clearR(B);
        Set<Long> R = new HashSet<>();

        for (Triple<Long, String, Long> t : transitions) {
            if (Pi_s.blockOf(t.getFirst()) == B) {
                R.addAll(stateToSCCMap.get(t.getFirst()));
            }
        }

        // inertPredecessorsInB[t] = SCCs distintas de t que tienen alguna
        // transición τ que termina en t y vive enteramente dentro de B.
        Map<Long, Set<Long>> inertPredecessorsInB = new HashMap<>();
        for (Long s : Pi_s.states(B)) {
            for (Pair<String, Long> trans : toMinimise.getTransitions(s, MTS.TransitionType.REQUIRED)) {
                String label = trans.getFirst();
                Long t = trans.getSecond();
                if (tauLabels.contains(label) && Pi_s.blockOf(t) == B) {
                    if (!stateToSCCMap.get(s).equals(stateToSCCMap.get(t))) {
                        inertPredecessorsInB.computeIfAbsent(t, k -> new HashSet<>()).add(s);
                    }
                }
            }
        }

        Deque<Long> worklistR = new ArrayDeque<>(R);
        while (!worklistR.isEmpty()) {
            Long s = worklistR.pop();
            Set<Long> predecessors = inertPredecessorsInB.getOrDefault(s, Collections.emptySet());
            for (Long t : predecessors) {
                for (Long t_scc_state : stateToSCCMap.get(t)) {
                    if (R.add(t_scc_state)) {
                        worklistR.add(t_scc_state);
                    }
                }
            }
        }

        for (Long s : R) {
            Pi_s.addToR(s);
        }

        return Pi_s.splitOffR(B);
    }

    /**
     * Marca como "bottom" los estados de {@code N} cuya SCC τ-conexa no tiene
     * salidas τ a otra SCC dentro de {@code N}. Cambio de semántica respecto
     * de v3: este método ahora MUTA la partición (mueve los bottoms al prefijo
     * {@code [start..bottomEnd)} del bloque). El caller los itera con
     * {@code Pi_s.bottoms(N)}.
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

    private static Map<String, Set<Triple<Long, String, Long>>> findNewNonInertTransitions(
            RefinablePartition.Block srcBlock,
            RefinablePartition.Block tgtBlock,
            RefinablePartition Pi_s,
            MTS<Long, String> toMinimise,
            Set<String> tauLabels) {

        Map<String, Set<Triple<Long, String, Long>>> result = new HashMap<>();
        for (Long s : Pi_s.states(srcBlock)) {
            for (Pair<String, Long> transition : toMinimise.getTransitions(s, MTS.TransitionType.REQUIRED)) {
                String label = transition.getFirst();
                Long destination = transition.getSecond();
                if (tauLabels.contains(label) && Pi_s.blockOf(destination) == tgtBlock) {
                    result.computeIfAbsent(label, k -> new HashSet<>()).add(new Triple<>(s, label, destination));
                }
            }
        }
        return result;
    }

    private static Set<RefinablePartition.Block> findSplittableBlocks(
            Set<Triple<Long, String, Long>> chosenTransitions,
            RefinablePartition Pi_s) {

        Set<RefinablePartition.Block> blocksWithPrimaryTransitions = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Triple<Long, String, Long> transition : chosenTransitions) {
            RefinablePartition.Block sourceBlock = Pi_s.blockOf(transition.getFirst());
            if (sourceBlock != null) {
                blocksWithPrimaryTransitions.add(sourceBlock);
            }
        }
        return blocksWithPrimaryTransitions;
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

        // Map states to dense integer IDs for array-based access
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
        Arrays.fill(index, -1); // -1 means unvisited

        Deque<Integer> tarjanStack = new ArrayDeque<>();
        List<Set<Long>> sccs = new ArrayList<>();
        int[] indexCounter = {0}; // mutable counter via array

        // Precompute tau-children for each state (dense int IDs)
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

        // Iterative Tarjan using an explicit call stack
        // Each frame: (node, childIteratorIndex, isFirstVisit)
        for (int root = 0; root < n; root++) {
            if (index[root] != -1) continue;

            // Call stack: int[0]=node, int[1]=child iterator position
            Deque<int[]> callStack = new ArrayDeque<>();

            // Push initial frame
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
                    frame[1]++; // advance iterator

                    if (index[w] == -1) {
                        // Recurse: push new frame
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
                    // Post-visit: all children processed
                    callStack.pop();

                    if (lowlink[v] == index[v]) {
                        // v is root of an SCC
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
