package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional;

import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.MTSImpl;
import org.jgrapht.alg.util.Triple;

import java.util.*;
import java.util.stream.Collectors;

public class BranchingEquivalence {
    public static MTS<Long, String> buildMinimisedMTS(MTS<Long, String> toMinimise, Set<String> tauLabels, Map<String, String> translatorControllable) {
        MTS<Long, String> result = new MTSImpl<>(0L);
        result.removeUnreachableStates();
        result.addActions(toMinimise.getActions());
        long newStateIdCounter = 0;
        Long newInitialState = null;
        Long oldInitialState = toMinimise.getInitialState();
        Map<Long, Long> oldStateToNewStateId = new HashMap<>();
        Map<Set<Long>, Long> blockToNewStateId = new HashMap<>();

        Pair<List<Set<Long>>, Set<Triple<Long, String, Long>>> partitions = getPartitions(toMinimise, tauLabels);
        List<Set<Long>> pi_s = partitions.getFirst();
        for (Set<Long> block : pi_s) {
            Long newStateId = newStateIdCounter++;
            result.addState(newStateId);

            blockToNewStateId.put(block, newStateId);

            for (Long oldState : block) {
                if (oldState == null) {
                    continue;
                }

                oldStateToNewStateId.put(oldState, newStateId);

                if (oldState.equals(oldInitialState)) {
                    newInitialState = newStateId;
                }
            }
        }

        result.setInitialState(newInitialState);
        //crear transiciones
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

                    /*
                    if(!classIndex.equals(dstClass) || !localAlphabet.contains(label)){
                        result.addAction(label);
                        result.addTransition(classIndex, label, dstClass, MTS.TransitionType.REQUIRED);
                    }else {
                        String translatedLabel = "c_" + label;
                        if(translatorControllable.containsKey(label)){
                            translatedLabel = label;
                        }else{
                            translatorControllable.put(translatedLabel, label);
                        }
                        result.addAction(label);
                        result.addTransition(classIndex, label, dstClass, MTS.TransitionType.REQUIRED);
                    }
                     */
                }
            }
        }

        return result;
    }

    public static Pair<List<Set<Long>>, Set<Triple<Long, String, Long>>> getPartitions(MTS<Long, String> toMinimise, Set<String> tauLabels){
        // elimino componentes fuertemente conexas de ts
        List<Set<Long>> toMinimiseSCC = partitionIntoSCCWithTauLabels(toMinimise, tauLabels);
        Map<Long, Set<Long>> stateToSCCMap = new HashMap<>();
        for (Set<Long> scc : toMinimiseSCC) {
            for (Long state : scc) {
                stateToSCCMap.put(state, scc);
            }
        }
        // obtengo las particiones Bvis y Binvis
        Set<Long> Bvis = computeBvis(toMinimise, toMinimiseSCC, stateToSCCMap, tauLabels);
        Set<Long> Binvis = new HashSet<>(toMinimise.getStates());
        Binvis.removeAll(Bvis);

        List<Set<Long>> Pi_s = new ArrayList<>();
        if (!Bvis.isEmpty()) {
            Pi_s.add(Bvis);
        }
        if (!Binvis.isEmpty()) {
            Pi_s.add(Binvis);
        }

        // inicializo piT con todas las transiciones no-inertes
        Set<Triple<Long, String, Long>> initialBunch = new HashSet<>();
        for (Long s : toMinimise.getStates()) {
            for (Pair<String, Long> t : toMinimise.getTransitions(s, MTS.TransitionType.REQUIRED)) {
                String a = t.getFirst();
                Long sPrime = t.getSecond();
                if (!tauLabels.contains(a)) { // acción visible
                    initialBunch.add(new Triple<>(s, a, sPrime));
                } else if ((Bvis.contains(s) &&Binvis.contains(sPrime))) {
                    initialBunch.add(new Triple<>(s, a, sPrime));
                }
            }
        }

        List<Set<Triple<Long, String, Long>>> Pi_t = new ArrayList<>();
        Pi_t.add(initialBunch);
        Deque<Set<Triple<Long, String, Long>>> Pi_t_cola = new ArrayDeque<>(Pi_t);
        Deque<Splitter> splitterList = new ArrayDeque<>();
        Map<Splitter, Set<Triple<Long, String, Long>>> markings = new HashMap<>();
        Map<Long, Set<Long>> stateToBlockMap = new HashMap<>();

        for (Set<Long> block : Pi_s) {
            for (Long state : block) {
                stateToBlockMap.put(state, block);
            }
        }


        while (!Pi_t_cola.isEmpty()) {
            Set<Triple<Long,String,Long>> bunch = Pi_t_cola.pop();
            Map<Pair<String, Set<Long>>, Set<Triple<Long, String, Long>>> slices = new HashMap<>();
            for (Triple<Long, String, Long> t : bunch) {
                String action = t.getSecond();
                Long targetState = t.getThird();
                Set<Long> targetBlock = stateToBlockMap.get(targetState);

                if (targetBlock == null) continue;

                Pair<String, Set<Long>> key = new Pair<>(action, targetBlock);
                slices.computeIfAbsent(key, k -> new HashSet<>()).add(t);
            }

            //si el bunch es trivial
            if (slices.size() <= 1) {
                continue;
            }

            // Seleccionar una accion a y un B tq| T -a-> B| <= |T|/2
            Set<String> allLabels = bunch.stream()
                    .map(Triple::getSecond)
                    .collect(Collectors.toSet());
            String chosenA;
            Set<Long> chosenB;
            Set<Triple<Long, String, Long>> chosenTransitions = null;
            for (Map.Entry<Pair<String, Set<Long>>, Set<Triple<Long, String, Long>>> entry : slices.entrySet()) {
                Set<Triple<Long, String, Long>> slice = entry.getValue();
                long count = slice.size();

                if (count > 0 && count <= (bunch.size() / 2)) {

                    chosenA = entry.getKey().getFirst();
                    chosenB = entry.getKey().getSecond();
                    chosenTransitions = slice;
                    break;
                }
            }

            if (chosenTransitions == null) {
                for (Map.Entry<Pair<String, Set<Long>>, Set<Triple<Long, String, Long>>> entry : slices.entrySet()) {
                    if (!entry.getValue().isEmpty()) {
                        chosenA = entry.getKey().getFirst();
                        chosenB = entry.getKey().getSecond();
                        chosenTransitions = entry.getValue();
                        break;
                    }
                }
            }


            // Sacar a T de Pi_t y agregar l
            Pi_t.remove(bunch);
            Set<Triple<Long, String, Long>> newBunch = new HashSet<>(bunch);
            newBunch.removeAll(chosenTransitions);
            Pi_t_cola.push(newBunch);
            Pi_t_cola.push(chosenTransitions);
            Pi_t.add(newBunch);
            Pi_t.add(chosenTransitions);

            // preconfiguraciones de los structs
            for (Set<Long> block : findSplittableBlocks(chosenTransitions, newBunch, stateToBlockMap)){
                // T_B,a->B': Transiciones del splitter primario que salen de ESTE bloque
                Set<Triple<Long, String, Long>> primarySliceForBlock = chosenTransitions.stream()
                        .filter(t -> block.contains(t.getFirst()))
                        .collect(Collectors.toSet());

                // T_B-> \ T_B,a->B': Transiciones del splitter secundario que salen de ESTE bloque
                Set<Triple<Long, String, Long>> secondarySliceForBlock = newBunch.stream()
                        .filter(t -> block.contains(t.getFirst()))
                        .collect(Collectors.toSet());

                // Crear el splitter primario
                Splitter primarySplitter = new Splitter(block, primarySliceForBlock, Splitter.SplitterType.PRIMARY);

                // Crear el splitter secundario
                Splitter secondarySplitter = new Splitter(block, secondarySliceForBlock, Splitter.SplitterType.SECONDARY);

                // Agregarlos a la cola de trabajo en el orden correcto
                splitterList.addLast(primarySplitter);
                splitterList.addLast(secondarySplitter);

                //Marcamos todas las transiciones del slice primario.
                // Creamos un nuevo conjunto para las marcas del splitter primario
                Set<Triple<Long, String, Long>> primaryMarks = new HashSet<>(primarySliceForBlock);

                // Guardamos estas marcas en nuestro mapa
                markings.put(primarySplitter, primaryMarks);

                // Creamos un conjunto vacío para las marcas del splitter secundario
                Set<Triple<Long, String, Long>> secondaryMarks = new HashSet<>();

                // Para ser eficientes, primero agrupamos las transiciones secundarias por su estado de origen
                Map<Long, List<Triple<Long, String, Long>>> secondaryBySource = secondarySliceForBlock.stream()
                        .collect(Collectors.groupingBy(Triple::getFirst));

                // Obtenemos todos los estados que son origen de una transición primaria (y por ende, están "marcados")
                Set<Long> sourceStatesOfPrimary = primarySliceForBlock.stream()
                        .map(Triple::getFirst)
                        .collect(Collectors.toSet());

                // Iteramos sobre esos estados
                for (Long state : sourceStatesOfPrimary) {
                    // Si este estado también tiene transiciones secundarias...
                    if (secondaryBySource.containsKey(state)) {
                        // "...mark one such transition". Tomamos la primera que encontremos.
                        secondaryMarks.add(secondaryBySource.get(state).get(0));
                    }
                }

                // Guardamos estas marcas en nuestro mapa
                markings.put(secondarySplitter, secondaryMarks);
            }

            while (!splitterList.isEmpty()) {
                Splitter currentSplitter = splitterList.removeFirst();
                Set<Long> B = currentSplitter.sourceBlock();

                // Si el bloque de la tarea ya no existe en la partición (porque fue refinado), lo ignoro
                if (!Pi_s.contains(B)) {
                    continue;
                }

                Set<Triple<Long, String, Long>> currentMarks = markings.get(currentSplitter);

                Pair<Set<Long>, Set<Long>> splitResult = split(B, currentSplitter.transitions(), currentMarks, toMinimise, tauLabels, stateToSCCMap);
                Set<Long> R = splitResult.getFirst();
                Set<Long> U = splitResult.getSecond();

                if (R.isEmpty() || U.isEmpty()) {
                    continue;
                }

                Pi_s.remove(B);
                Pi_s.add(R);
                Pi_s.add(U);


                updateStateToBlockMap(stateToBlockMap, R, U);

                if (currentSplitter.type() == Splitter.SplitterType.PRIMARY) {
                    // La siguiente tarea en la cola DEBERÍA ser la secundaria para el bloque B original
                    Splitter secondaryForB = splitterList.peekFirst();

                    // Por las dudas chequeamos
                    if (secondaryForB != null && secondaryForB.sourceBlock().equals(B) && secondaryForB.type() == Splitter.SplitterType.SECONDARY) {

                        // La sacamos de la cola, porque la tarea original para B ya no es válida, estoy dividiendo ese bloque
                        splitterList.removeFirst();

                        // Como U ya es estable con respecto a newBunch, y no tiene transiciones en chosenBunch, creo una nueva tarea solo para la parte de R, que sí necesita ser procesada
                        Set<Triple<Long, String, Long>> secondarySliceForR = secondaryForB.transitions().stream()
                                .filter(t -> R.contains(t.getFirst()))
                                .collect(Collectors.toSet());

                        if (!secondarySliceForR.isEmpty()) {
                            Splitter newSecondaryForR = new Splitter(R, secondarySliceForR, Splitter.SplitterType.SECONDARY);

                            splitterList.addFirst(newSecondaryForR);

                            // Actualizo los markings
                            Set<Triple<Long, String, Long>> oldMarks = markings.get(secondaryForB);
                            Set<Triple<Long, String, Long>> newMarks = oldMarks.stream()
                                    .filter(t -> R.contains(t.getFirst()))
                                    .collect(Collectors.toSet());
                            markings.remove(secondaryForB); // Limpiamos la marca vieja
                            markings.put(newSecondaryForR, newMarks); // Añadimos la nueva
                        }
                    }
                }

                // Si quedaron transiciones inertes entre R y U
                Set<Triple<Long, String, Long>> newNonInert_R_to_U = findNewNonInertTransitions(R, U, toMinimise, tauLabels);

                if (!newNonInert_R_to_U.isEmpty()) {
                    // Línea 1.21: "Create a new bunch containing exactly R -> U..."
                    Pi_t.add(newNonInert_R_to_U);
                    // add them to the splitter list
                    Splitter splitterForR = new Splitter(R, newNonInert_R_to_U, Splitter.SplitterType.SECONDARY);
                    // Línea 1.21: "...and mark all its transitions"
                    Set<Triple<Long, String, Long>> marksForR = new HashSet<>(newNonInert_R_to_U);

                    Pair<Set<Long>, Set<Long>> secondSplitResult = split(R, splitterForR.transitions(), marksForR, toMinimise, tauLabels, stateToSCCMap);
                    Set<Long> N = secondSplitResult.getFirst();
                    Set<Long> R_prime = secondSplitResult.getSecond();

                    if (!N.isEmpty() && !R_prime.isEmpty()) {
                        Pi_s.remove(R);
                        Pi_s.add(N);
                        Pi_s.add(R_prime);
                        updateStateToBlockMap(stateToBlockMap, N, R_prime);

                        // Línea 1.25: Add N −τ→ R′ to the bunch containing R −τ→ U
                        Set<Triple<Long, String, Long>> newTransitions_N_to_R_prime =
                                findNewNonInertTransitions(N, R_prime, toMinimise, tauLabels);

                        // Las añadimos al mismo bunch que creamos para las transiciones R -> U.
                        // La variable 'newNonInert_R_to_U' viene del paso anterior.
                        newNonInert_R_to_U.addAll(newTransitions_N_to_R_prime);
                    }

                    if (!N.isEmpty()) { // N contiene los estados que alcanzaron la transición R->U
                        Set<Long> bottomStatesOfN = findBottomStates(N, toMinimise, tauLabels, stateToSCCMap);

                        for (Set<Triple<Long, String, Long>> b : Pi_t) {
                            Set<Triple<Long, String, Long>> sliceFromN = b.stream()
                                    .filter(t -> N.contains(t.getFirst()))
                                    .collect(Collectors.toSet());

                            // Si no hay transiciones de N a este bunch, no hay nada que hacer.
                            if (sliceFromN.isEmpty()) {
                                continue;
                            }

                            // --- Línea 1.26: Crear y añadir el nuevo splitter ---
                            Splitter restabilizationSplitter = new Splitter(N, sliceFromN, Splitter.SplitterType.SECONDARY);
                            splitterList.addLast(restabilizationSplitter);

                            // --- Línea 1.27: Preparar las marcas para la nueva tarea ---
                            Set<Triple<Long, String, Long>> marksForRestabilization = new HashSet<>();

                            // Para ser eficientes, agrupamos las transiciones del slice por su estado de origen.
                            Map<Long, List<Triple<Long, String, Long>>> sliceBySource = sliceFromN.stream()
                                    .collect(Collectors.groupingBy(Triple::getFirst));

                            // "For each bottom state, mark one of its outgoing transitions..."
                            for (Long bottomState : bottomStatesOfN) {
                                // Si el estado bottom tiene transiciones en este slice...
                                if (sliceBySource.containsKey(bottomState)) {
                                    // "...mark one". Marco la primer transition
                                    marksForRestabilization.add(sliceBySource.get(bottomState).get(0));
                                }
                            }

                            markings.put(restabilizationSplitter, marksForRestabilization);
                        }
                    }
                }
            }
        }


        return new Pair(Pi_s, Pi_t);
    }

    private static Pair<Set<Long>, Set<Long>> split(
            Set<Long> B,
            Set<Triple<Long, String, Long>> transitions,
            Set<Triple<Long, String, Long>> currentMarks,
            MTS<Long, String> toMinimise,
            Set<String> tauLabels,
            Map<Long, Set<Long>> stateToSCCMap) {

        Set<Long> R = new HashSet<>();

        // Línea 2.2: "Initially, all states with outgoing marked transitions are put in R."
        for (Triple<Long, String, Long> markedTransition : currentMarks) {
            R.add(markedTransition.getFirst());
        }


        // Propagacion de R
        // R := R U B --T\Marked(T)--> (the states in  B --T\Marked(T)--> are added that were not yet in R)
        for (Triple<Long, String, Long> transition : transitions) {
            R.add(transition.getFirst());
        }

        //Using backward reachability along inert transitions, R is extended until either R is stable (no states can be added), or R contains more than half the states in B.

        // Armo un mapa con para cada estado cuales son sus predecesores inertes
        Map<Long, Set<Long>> inertPredecessorsInB = new HashMap<>();
        for (Long s : B) {
            // NO ANDA !!!! if (R.size() > B.size() / 2) { break; }

            for (Pair<String, Long> trans : toMinimise.getTransitions(s, MTS.TransitionType.REQUIRED)) {
                String label = trans.getFirst();
                Long t = trans.getSecond();
                if (tauLabels.contains(label) && B.contains(t)) {
                    if (stateToSCCMap.get(s) != stateToSCCMap.get(t)) { // me fijo que no esten en la misma componente conexa
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
                if (R.add(t)) { // si el predecesor no estaba en R, lo agrego a la worlist
                    worklistR.add(t);
                }
            }
        }

        Set<Long> U = new HashSet<>(B);
        U.removeAll(R);

        return new Pair<>(R, U);
    }

    /**
     * Encuentra los estados "bottom" dentro de un bloque N.
     * [cite_start]Un estado 's' es bottom si no tiene transiciones τ salientes que terminen DENTRO de N[cite: 76].
     *
     * @param N          El bloque en el que buscar estados bottom.
     * @param toMinimise El MTS completo para obtener las transiciones.
     * @param tauLabels  El conjunto de etiquetas consideradas como τ.
     * @return Un conjunto de IDs de estados (Long) que son bottom en N.
     */
    private static Set<Long> findBottomStates(
            Set<Long> N,
            MTS<Long, String> toMinimise,
            Set<String> tauLabels,
            Map<Long, Set<Long>> stateToSCCMap) {

        Set<Long> bottomStates = new HashSet<>();

        // Itero sobre cada estado 's' y ve si es bottom
        for (Long s : N) {
            boolean hasInternalTauTransition = false;

            // Reviso todas las transiciones que salen de 's'
            for (Pair<String, Long> transition : toMinimise.getTransitions(s, MTS.TransitionType.REQUIRED)) {
                String label = transition.getFirst();
                Long destination = transition.getSecond();

                // ¿Es una transición τ que se queda DENTRO del bloque N?
                if (tauLabels.contains(label) && N.contains(destination)) { // me fijo q no esten dentro de la misma componente conexa
                    // Si encontramos al menos una, 's' NO es un estado bottom.
                    if (stateToSCCMap.get(s) != stateToSCCMap.get(destination)) {
                        hasInternalTauTransition = true;
                        break; // No necesitamos seguir buscando para este estado 's'.
                    }
                }
            }

            // Si después de revisar todas sus transiciones no encontramos ninguna interna...
            if (!hasInternalTauTransition) {
                // ...entonces 's' es un estado bottom.
                bottomStates.add(s);
            }
        }
        return bottomStates;
    }

    private static Set<Triple<Long, String, Long>> findNewNonInertTransitions(
            Set<Long> R,
            Set<Long> U,
            MTS<Long, String> toMinimise,
            Set<String> tauLabels) {

        Set<Triple<Long, String, Long>> result = new HashSet<>();

        // Itera sobre cada estado 's' en el bloque de origen R.
        for (Long s : R) {
            // Obtiene todas las transiciones que salen de 's'.
            for (Pair<String, Long> transition : toMinimise.getTransitions(s, MTS.TransitionType.REQUIRED)) {
                String label = transition.getFirst();
                Long destination = transition.getSecond();

                // Comprueba si la transición es una 'tau' Y si su destino está en U.
                if (tauLabels.contains(label) && U.contains(destination)) {
                    // Si cumple ambas condiciones, es una nueva transición no-inerte.
                    result.add(new Triple<>(s, label, destination));
                }
            }
        }
        return result;
    }


    private static void updateStateToBlockMap(Map<Long, Set<Long>> map, Set<Long> newBlock1, Set<Long> newBlock2) {
        for (Long state : newBlock1) map.put(state, newBlock1);
        for (Long state : newBlock2) map.put(state, newBlock2);
    }

    /**
     * Un bloque B es splittable si sus transiciones (del bunch original) se dividieron
     * entre el splitter primario (chosenTransitions) y el secundario (remainingBunch).
     *Un bloque es splittable si sus transiciones, que antes estaban juntas en el bunch original, ahora se han repartido entre los dos nuevos bunches.
     * Tiene al menos una transición en chosenTransitions (el splitter primario).
     * Y también tiene al menos una transición en remainingBunch (el splitter secundario, que es lo que queda del bunch original).
     * @param chosenTransitions El conjunto de transiciones del splitter primario (T_a->B').
     * @param remainingBunch    El conjunto de transiciones del splitter secundario (T \ T_a->B').
     * @param stateToBlockMap   El mapa del mappeo de estados a bloques
     * @return Un conjunto de bloques que son "splittable".
     */
    private static Set<Set<Long>> findSplittableBlocks(
            Set<Triple<Long, String, Long>> chosenTransitions,
            Set<Triple<Long, String, Long>> remainingBunch,
            Map<Long, Set<Long>> stateToBlockMap) {

        // Este conjunto contendrá todos los bloques que cumplen la primera parte de la condición (∅ ⊂ T_B,a→B′).
        Set<Set<Long>> blocksWithPrimaryTransitions = new HashSet<>();
        for (Triple<Long, String, Long> transition : chosenTransitions) {
            Set<Long> sourceBlock = stateToBlockMap.get(transition.getFirst());
            if (sourceBlock != null) {
                blocksWithPrimaryTransitions.add(sourceBlock);
            }
        }

        // Este es el resultado final. Un bloque solo entra aquí si ya estaba en el conjunto
        // anterior Y además tiene una transición en el splitter secundario.
        Set<Set<Long>> splittableBlocks = new HashSet<>();
        for (Triple<Long, String, Long> transition : remainingBunch) {
            Set<Long> sourceBlock = stateToBlockMap.get(transition.getFirst());
            // Si el bloque tiene transiciones secundarias Y ya sabemos que tiene primarias...
            if (sourceBlock != null && blocksWithPrimaryTransitions.contains(sourceBlock)) {
                // ... entonces es splittable.
                splittableBlocks.add(sourceBlock);
            }
        }

        return splittableBlocks;
    }
    private static Set<Long> computeBvis(MTS<Long, String> toMinimise, List<Set<Long>> tauSCCs, Map<Long, Set<Long>> stateToSCC, Set<String> tauLabels) {
        // 1. Map SCCs to unique IDs for performance (avoid Set hashing)
        Map<Set<Long>, Integer> sccToId = new HashMap<>();
        List<Set<Long>> idToScc = new ArrayList<>();
        for (int i = 0; i < tauSCCs.size(); i++) {
            sccToId.put(tauSCCs.get(i), i);
            idToScc.add(tauSCCs.get(i));
        }

        // 2. Build the Predecessor Graph (Reverse Graph)
        // predGraph[i] contains list of SCC IDs that have a tau-transition TO SCC i
        List<Set<Integer>> predGraph = new ArrayList<>();
        for (int i = 0; i < tauSCCs.size(); i++) predGraph.add(new HashSet<>());

        // 3. Identify initially visible SCCs
        Set<Integer> visibleSccIds = new HashSet<>();

        // Single pass to build graph and find visible transitions - O(m)
        for (int sccId = 0; sccId < tauSCCs.size(); sccId++) {
            Set<Long> scc = idToScc.get(sccId);
            boolean isVisible = false;

            for (Long state : scc) {
                for (Pair<String, Long> t : toMinimise.getTransitions(state, MTS.TransitionType.REQUIRED)) {
                    String label = t.getFirst();
                    Long target = t.getSecond();

                    if (!tauLabels.contains(label)) {
                        // Found a visible transition
                        isVisible = true;
                    } else {
                        // Found a tau transition: Add to REVERSE graph
                        Set<Long> targetSccSet = stateToSCC.get(target);
                        // Only record if it leaves the current SCC
                        if (targetSccSet != scc) {
                            Integer targetId = sccToId.get(targetSccSet);
                            // Add 'sccId' as a predecessor of 'targetId'
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

        // 4. Backward Propagation (BFS/DFS) - O(N_scc + E_scc)
        Deque<Integer> stack = new ArrayDeque<>(visibleSccIds);
        // Use a boolean array for fast "contains" check
        boolean[] isVis = new boolean[tauSCCs.size()];
        for(Integer id : visibleSccIds) isVis[id] = true;

        while (!stack.isEmpty()) {
            Integer currentId = stack.pop();

            // Efficiently retrieve only the specific predecessors
            for (Integer predId : predGraph.get(currentId)) {
                if (!isVis[predId]) {
                    isVis[predId] = true;
                    stack.push(predId);
                }
            }
        }

        // 5. Construct Result
        Set<Long> Bvis = new HashSet<>();
        for (int i = 0; i < isVis.length; i++) {
            if (isVis[i]) {
                Bvis.addAll(idToScc.get(i));
            }
        }

        return Bvis;
    }

    private static List<Set<Long>> partitionIntoSCCWithTauLabels(
            MTS<Long, String> toMinimise,
            Set<String> tauLabels) {

        Set<Long> states = toMinimise.getStates();

        Stack<Long> SCCorder = new Stack<>();
        Set<Long> visited = new HashSet<>();
        for (Long current : states) {
            if (!visited.contains(current)) {
                forwardDFSWithTauLabels(toMinimise, tauLabels, current, states, visited, SCCorder);
            }
        }

        Map<Long, Set<Long>> reversedGraph = buildReversedGraph(toMinimise, states, tauLabels);

        List<Set<Long>> result = new ArrayList<>();
        visited.clear();
        while (!SCCorder.isEmpty()) {
            Long current = SCCorder.pop();
            if (!visited.contains(current)) {
                Set<Long> currentSCC = new HashSet<>();
                backwardsDFSWithTauLabels(reversedGraph, current, states, visited, currentSCC);
                assert (!currentSCC.isEmpty());
                result.add(currentSCC);
            }
        }
        return result;
    }

    private static void forwardDFSWithTauLabels(MTS<Long, String> toMinimise,
                                                Set<String> tauLabels,
                                                Long current,
                                                Set<Long> states,
                                                Set<Long> visited,
                                                Stack<Long> SCCorder) {
        visited.add(current);
        Stack<Long> stack = new Stack<>();
        stack.add(current);
        while (!stack.empty()) {
            current = stack.peek();
            boolean hasUnvisitedChild = false;
            for (Pair<String, Long> child : toMinimise.getTransitions(current, MTS.TransitionType.REQUIRED)) {
                if (tauLabels.contains(child.getFirst()) && states.contains(child.getSecond())
                        && !visited.contains(child.getSecond())) {
                    hasUnvisitedChild = true;
                    visited.add(child.getSecond());
                    stack.push(child.getSecond());
                }
            }
            if (!hasUnvisitedChild) {
                stack.pop();
                SCCorder.add(current);
            }
        }
    }

    private static void backwardsDFSWithTauLabels(Map<Long, Set<Long>> reversedGraph,
                                                  Long current,
                                                  Set<Long> states,
                                                  Set<Long> visited,
                                                  Set<Long> currentSCC) {
        visited.add(current);
        Stack<Long> stack = new Stack<>();
        stack.add(current);
        while (!stack.empty()) {
            current = stack.peek();
            boolean hasUnvisitedChild = false;
            for (Long predecessor : reversedGraph.getOrDefault(current, Set.of())) {
                if (states.contains(predecessor) && !visited.contains(predecessor)) {
                    hasUnvisitedChild = true;
                    visited.add(predecessor);
                    stack.push(predecessor);
                }
            }
            if (!hasUnvisitedChild) {
                stack.pop();
                currentSCC.add(current);
            }
        }
    }

    private static Map<Long, Set<Long>> buildReversedGraph(MTS<Long, String> toMinimise,
                                                           Set<Long> states,
                                                           Set<String> tauLabels) {
        Map<Long, Set<Long>> reversedGraph = new HashMap<>();
        for (Long state : states) {
            for (Pair<String, Long> trans : toMinimise.getTransitions(state, MTS.TransitionType.REQUIRED)) {
                if (tauLabels.contains(trans.getFirst()) && states.contains(trans.getSecond())) {
                    reversedGraph
                            .computeIfAbsent(trans.getSecond(), k -> new HashSet<>())
                            .add(state);
                }
            }
        }
        return reversedGraph;
    }

}
