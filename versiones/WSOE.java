package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional;

import MTSSynthesis.ar.dc.uba.model.condition.Fluent;
import MTSSynthesis.ar.dc.uba.model.language.Symbol;
import MTSTools.ac.ic.doc.commons.relations.BinaryRelation;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.MTSImpl;
import ltsa.lts.LTSOutput;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;


import java.util.*;

import static MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.Utils.translateFromOriginal;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SyntEquivalence {

    private final Vector<HashMap<String, String>> totalTranslator;
    private final Map<Long, Set<Long>> sourcesWithUncontrollablePath;
    private HashMap<Long, Long> referenceStateToClass;
    private HashMap<Long, HashMap<String, Set<Long>>> sourceWithLabel;
    private BitSet[] existUncontrollableLocalPathMatrix;
    // private Set<Long> repeatedTriplets = new HashSet<Long>();
    LongOpenHashSet repeatedTriplets = new LongOpenHashSet(16384);

    private boolean weak = false;
    private Map<Long, Map<Long, Boolean>>  existUncontrollableLocalPath;


    public SyntEquivalence(Vector<HashMap<String, String>> totalTranslator,
                           HashMap<Long, HashMap<String, Set<Long>>> sourceWithLabel,
                           BitSet[] existUncontrollableLocalPathMatrix, Map<Long, Set<Long>> sourcesWithUncontrollablePath, Map<Long, Map<Long, Boolean>> existUncontrollableLocalPath, LTSOutput output) {
        this.totalTranslator = totalTranslator;
        this.sourceWithLabel = sourceWithLabel;
        this.existUncontrollableLocalPath = existUncontrollableLocalPath;
        this.sourcesWithUncontrollablePath = sourcesWithUncontrollablePath;
        this.existUncontrollableLocalPathMatrix = existUncontrollableLocalPathMatrix;
    }

    public HashMap<Long, Long> getReferenceStateToClass() {
        return referenceStateToClass;
    }

    public static MTS<Long, String> WSOEPreprocess(MTS<Long, String> env,
                                                   Pair<Set<String>, Set<String>> labels,
                                                   Set<String> controllableActions,
                                                   Set<Fluent> fluents,Vector<HashMap<String, String>> totalTranslator
                                                   ) {
        Set<String> allLabels = labels.getFirst();
        Set<String> localLabels = labels.getSecond();

        Set<String> uncontrollableLabels = new HashSet<>(allLabels);
        uncontrollableLabels.removeAll(controllableActions);

        HashSet<String> allInitiatingActions = new HashSet<>();
        for(Fluent fluent : fluents) {
            for (Symbol initiatingAction : fluent.getInitiatingActions()) {
                allInitiatingActions.addAll(translateFromOriginal(initiatingAction.toString(), totalTranslator));
                String stringAction = initiatingAction.toString();
                allInitiatingActions.add(stringAction);
            }
        }
        localLabels.removeAll(allInitiatingActions);

        Set<String> uncontrollableLocal = new HashSet<>(uncontrollableLabels);
        uncontrollableLocal.retainAll(localLabels);
        org.jgrapht.graph.DirectedPseudograph<Long, Utils.LabeledEdge> lts = Utils.MTSToLTSGraphRestricted(env, uncontrollableLocal);
        org.jgrapht.alg.connectivity.GabowStrongConnectivityInspector<Long, Utils.LabeledEdge> connected_components = new org.jgrapht.alg.connectivity.GabowStrongConnectivityInspector<>(lts);

        List<Set<Long>> components = connected_components.stronglyConnectedSets();
        if (components.size() == lts.vertexSet().size()) { // Do not reconstruct if no minimization, avoid paying the cost
            return env;
        }
        Map<Long, Long> repMap = new HashMap<>();
        long newName = 0;
        for (Set<Long> comp : components) {
            for (Long v : comp) {
                if (v == -1){
                    repMap.put(v,-1L);
                }
                repMap.put(v, newName);
            }
            newName++;
        }

        // 3. Crea el grafo condensado y añade los vértices representantes
        MTS<Long, String> condensed =
                new MTSImpl<>(repMap.get(env.getInitialState()));
        condensed.addStates(new HashSet<>(repMap.values()));
        condensed.setInitialState(repMap.get(env.getInitialState()));
        // 4. Recorre cada arista original y la transfiere
        //    al grafo condensado, preservando la etiqueta
        for (Utils.LabeledEdge e : lts.edgeSet()) {
            Long srcRep = repMap.get(lts.getEdgeSource(e));
            Long tgtRep = repMap.get(lts.getEdgeTarget(e));
            // crear una nueva arista con la misma etiqueta
            condensed.addTransition(srcRep, e.getLabel() ,tgtRep, MTS.TransitionType.REQUIRED);
        }

        return condensed;
        }

    /**
     *
     * @param env
     * @param labels
     * @param controllableActions
     * @param fluents
     * @return
     * @throws Exception
     */
    public List<Pair<Set<Long>, Set<Long>>> WSOE(MTS<Long, String> env,
                                                 Pair<Set<String>, Set<String>> labels,
                                                 Set<String> controllableActions,
                                                 Set<Fluent> fluents, LTSOutput output) throws Exception {
        Set<String> allLabels = labels.getFirst();
        Set<String> localLabels = labels.getSecond();

        Set<String> uncontrollableLabels = new HashSet<>(allLabels);
        uncontrollableLabels.removeAll(controllableActions);

        HashSet<String> allInitiatingActions = new HashSet<>();
        for(Fluent fluent : fluents) {
            for (Symbol initiatingAction : fluent.getInitiatingActions()) {
                allInitiatingActions.addAll(translateFromOriginal(initiatingAction.toString(), totalTranslator));
                String stringAction = initiatingAction.toString();
                allInitiatingActions.add(stringAction);
            }
        }
        localLabels.removeAll(allInitiatingActions);

        //env = WSOEPreprocess(env, localLabels, uncontrollableLabels);

        // partition = List < (splitlist, states) >
        List<Pair<Set<Long>, Set<Long>>> partition
                = new ArrayList(Collections.singleton(new Pair<Set<Long>, Set<Long>>(new HashSet<>(), (new HashSet<>(env.getStates())))));
        Set<String> alphabet = new HashSet<>(env.getActions());
        boolean stablePartitionReached = false;

        // map state -> equivalence class
        referenceStateToClass = new HashMap<>();
        // initializing
        for(Long state : env.getStates()){
            referenceStateToClass.put(state, 0L);
        }
        alphabet.removeIf(s -> s.startsWith("c_"));
        boolean partitionHappens = false;
        while(true){
            partitionHappens = false;
            for(Pair<Set<Long>, Set<Long>> splitter : new ArrayList<>(partition)){
                for(String action : alphabet){
                    stablePartitionReached
                            = SplitOn(env,
                                    partition,
                                    splitter.getSecond(),
                                    action,
                                    alphabet,
                                    controllableActions,
                                    localLabels,
                                    allInitiatingActions);
                    partitionHappens = (stablePartitionReached || partitionHappens);
                }
            }
            if(!partitionHappens)
                break;
        }

        return partition;
    }

    private boolean SplitOn(MTS<Long, String> env, List<Pair<Set<Long>, Set<Long>>> partition, Set<Long> splitter, String action, Set<String> envActions, Set<String> controllableActions, Set<String> localLabels, HashSet<String> allInitiatingActions) throws Exception {

        Set<String> uncontrollableActions = new HashSet<>(envActions);
        uncontrollableActions.removeAll(controllableActions);

        boolean isLocalAction = localLabels.contains(action);
        boolean isUncontrollableAction = uncontrollableActions.contains(action);

        if(isUncontrollableAction){
            for(Long end : splitter){
                // lines 3 to 5 algorithm2
                //Set<Long> sourcesByUncontrollableLocalPath = sourceWithLabel.get(end).getOrDefault(action, new HashSet<>());
                Set<Long> sourcesByUncontrollableLocalPath = getSourcesByUncontrollableLocalPath(end, action, isLocalAction);
                for(Long source : sourcesByUncontrollableLocalPath){
                    // line 4 of algorithm
                    // 4: move src to split list in [src]
                    Pair<Set<Long>, Set<Long>> equivalenceClassOfSrc = partition.get(Math.toIntExact(referenceStateToClass.get(source)));
                    equivalenceClassOfSrc = partition.get(Math.toIntExact(referenceStateToClass.get(source)));
                    equivalenceClassOfSrc.getFirst().add(source);
                }
            }
        }else{
//             lines 8 to 11
            repeatedTriplets.clear();

            for(Long end : splitter){
                BS(action, end, partition, localLabels, env, controllableActions, allInitiatingActions);
            }
        }


        /** lines 12-16 of SplitOn paper's algorithm **/
        int idx = 0;
        boolean partitionChangeHappens = false;
        int partitionSize = partition.size();

        for (; idx < partitionSize; idx++) {
            Pair<Set<Long>, Set<Long>> eqClass = partition.get(idx);
            Set<Long> splitList = eqClass.getFirst();
            Set<Long> stateSet = eqClass.getSecond();

            int splitSize = splitList.size();
            if (splitSize == 0 || splitSize == stateSet.size()) {
                continue; // trivial case, skip
            }

            // Non-trivial split — perform partitioning
            Pair<Set<Long>, Set<Long>> newClass = new Pair<>(new HashSet<>(), new HashSet<>(splitList));

            for (Long s : splitList) {
                referenceStateToClass.put(s, (long) partition.size());
            }

            // remove split states from original class
            stateSet.removeAll(splitList);
            splitList.clear();

            partition.add(newClass);
            partitionChangeHappens = true;
        }

// Always clear splitLists after — keep memory footprint low
        for (int i = 0; i < partition.size(); i++) {
            partition.get(i).getFirst().clear();
        }

        return partitionChangeHappens;
        /* boolean partitionChangeHappens = false;

        for( Pair<Set<Long>, Set<Long>> equivalenceClass : new ArrayList<>(partition) ){

            Set<Long> equivalenceSplitList = equivalenceClass.getFirst();
            Set<Long> equivalenceStates = equivalenceClass.getSecond();

            boolean emptySplitList = equivalenceSplitList.isEmpty();
            boolean fullSplitList = (equivalenceSplitList.size()== equivalenceStates.size());
            boolean hasTrivialSplitList = ( emptySplitList | fullSplitList);

            if(!hasTrivialSplitList){
                // line 14 of algorithm
                Pair<Set<Long>, Set<Long>> actualPartition = partition.get(idx);
                Pair<Set<Long>, Set<Long>> newClass = new Pair<>(new HashSet<>(), new HashSet<>(actualPartition.getFirst()));
                for(Long statesToChangeClass : actualPartition.getFirst()){
                    referenceStateToClass.put(statesToChangeClass, Long.valueOf(partition.size()));
                }
                Set<Long> splittedActualStates = actualPartition.getSecond();
                splittedActualStates.removeAll(actualPartition.getFirst());
                actualPartition.getFirst().clear();
                partition.add(newClass);
                partitionChangeHappens=true;
            }
            idx++;
        }

        for(Pair<Set<Long>, Set<Long>> classPartition : partition){
            classPartition.getFirst().clear();
        }

        return partitionChangeHappens; */

    }

    private static class StateTriple {
        final long state;
        final boolean inSecondPart;
        final long startClass;

        StateTriple(long state, boolean inSecondPart, long startClass) {
            this.state = state;
            this.inSecondPart = inSecondPart;
            this.startClass = startClass;
        }

        public long getValue0(){
            return state;
        }
        public boolean getValue1(){
            return inSecondPart;
        }
        public long getValue2(){
            return startClass;
        }

        public void print(){
                   System.out.println("state: " + state + " inSecondPart: " + inSecondPart + " startClass: " + startClass);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            StateTriple that = (StateTriple) o;
            return state == that.state &&
                    inSecondPart == that.inSecondPart &&
                    startClass == that.startClass;
        }
        @Override
        public int hashCode() {
            return Objects.hash(state, inSecondPart, startClass);
        }
    }

    // Bit layout:
// [ state (31 bits) | inSecondPart (1 bit) | startClass (32 bits) ]
    private static final long OFFSET = 1_000_000_000L;
    private static final long NONE = -1L + OFFSET;  // Will be used when value is -1

    private static final int START_CLASS_BITS = 32;
    private static final int IN_SECOND_PART_BITS = 1;
    private static final int STATE_BITS = 31;

    private static final int START_CLASS_SHIFT = 0;
    private static final int IN_SECOND_PART_SHIFT = START_CLASS_SHIFT + START_CLASS_BITS;
    private static final int STATE_SHIFT = IN_SECOND_PART_SHIFT + IN_SECOND_PART_BITS;

    // Masks
    private static final long START_CLASS_MASK = (1L << START_CLASS_BITS) - 1;
    private static final long IN_SECOND_PART_MASK = 1L;
    private static final long STATE_MASK = (1L << STATE_BITS) - 1;

    private long encodeTriple(long state, boolean inSecondPart, long startClass) {
        long s = (state == -1) ? OFFSET - 1 : state + OFFSET;
        long sc = (startClass == -1) ? OFFSET - 1 : startClass + OFFSET;
        return (s << 33) | (inSecondPart ? 1L << 32 : 0) | sc;
    }

    private long getState(long packed) {
        return ((packed >>> 33) & 0x1FFFFFFFFL) - OFFSET;
    }

    private boolean isInSecondPart(long packed) {
        return ((packed >>> 32) & 1L) == 1;
    }

    private long getStartClass(long packed) {
        return (packed & 0xFFFFFFFFL) - OFFSET;
    }

    ArrayDeque<Long> queue = new ArrayDeque<>();

    private void BS(String action, Long end, List<Pair<Set<Long>, Set<Long>>> partition, Set<String> localLabels, MTS<Long, String> env, Set<String> controllableActions, HashSet<String> allInitiatingActions) {

        long OFFSET = 1_000_000_000L; // offset to make signed longs non-negative (31 bits safe)

        long noneStartClass = -1;

        long encoded = encodeTriple(end, true, noneStartClass);
        queue.add(encoded);
        while(!queue.isEmpty()){ // main loop 3rd line of algorithm

            // StateTriple currentTriple = queue.pop();
            long packedTriple = queue.pop();
            long currentState = getState(packedTriple);
            boolean searchInSecondPartOfThePath = isInSecondPart(packedTriple);
            long startClass = getStartClass(packedTriple);

            if(!searchInSecondPartOfThePath){ // line 5 of algorithm

                boolean startClassIsNone = (startClass == noneStartClass);
                long currentStateClass = referenceStateToClass.get(currentState);
                boolean startClassEqualsCurrentStateClass = (startClass==currentStateClass);

                if(startClassIsNone || startClassEqualsCurrentStateClass){ // line 6 of BS Algorithm
                    // Line7: move currentState to split list in [currentState]
                    Pair<Set<Long>, Set<Long>> equivalenceClassOfSrc = partition.get(Math.toIntExact(currentStateClass));
                    equivalenceClassOfSrc.getFirst().add(currentState);
                }

                // for of line9
                HashMap<String, Set<Long>> sourcesOfCurrent = sourceWithLabel.computeIfAbsent(currentState, k -> null);
                assertNotNull(sourcesOfCurrent);
                for(Map.Entry<String, Set<Long>> source : sourcesOfCurrent.entrySet()){
                    String sourceLabel = source.getKey();
                    if(localLabels.contains(sourceLabel)){
                        long addedTriple;
                        Set<Long> sourceStates = source.getValue();
                        //sourceStates.remove(currentState); // REMOVE SELF LOOPS
                        if(!controllableActions.contains(sourceLabel)){
                            // line 11
                            for(Long sourceState : sourceStates){
                                addedTriple = encodeTriple (sourceState, false, startClass);
                                // addedTriple = new StateTriple(sourceState, false, startClass);
                                if (repeatedTriplets.add(addedTriple)) {
                                    queue.add(addedTriple);
                                }
                            }

                        }else if(startClassIsNone || startClassEqualsCurrentStateClass){
                            // line 13
                            for(Long sourceState : sourceStates) {
                                addedTriple = encodeTriple (sourceState, false, referenceStateToClass.get(sourceState));
                                if (repeatedTriplets.add(addedTriple)) {
                                    queue.add(addedTriple);
                                }
                            }
                        }

                    }
                }

            }else{ // line 16 of algorithm

                if(localLabels.contains(action)){ // line 34
                    // StateTriple addedTriple = new StateTriple(currentState, false, noneStartClass);
                    // long addedTriple = ((currentState+OFFSET) << 33) | 0L | (noneStartClass+OFFSET);
                    long addedTriple = encodeTriple(currentState, false, noneStartClass);
                    if (repeatedTriplets.add(addedTriple)) {
                        queue.add(addedTriple);
                    }
                }else{
                    //lines 34-39
                    HashMap<String, Set<Long>> sourcesOfCurrent = sourceWithLabel.getOrDefault(currentState, null);
                            // .computeIfAbsent(currentState, k -> null);
                    if(sourcesOfCurrent != null){
                        for(Map.Entry<String, Set<Long>> source : sourcesOfCurrent.entrySet()){
                            if(source.getKey().equals(action)){
                                for(Long sourceState : source.getValue()){
                                    // StateTriple addedTriple = new StateTriple(sourceState, false, noneStartClass);
                                    // long addedTriple = ((sourceState+OFFSET) << 33) | 0 | (noneStartClass+OFFSET);
                                    long addedTriple = encodeTriple(sourceState, false, noneStartClass);
                                    if (repeatedTriplets.add(addedTriple)) {
                                        queue.add(addedTriple);
                                    }
                                }
                            }
                        }
                    }

                } // end if line 40
            } // end if line 41
        } // end while line 42
    }

    private Set<Long> getSourcesByUncontrollableLocalPath(Long end, String action, boolean isLocalAction) {

        /** we have two options:
         * 1) if isLocalAction, the sources will be all local/uncontrollable paths
         * 2) if is not a local action, the sources will have  unc/loc paths before and after action.
         */

        int n = existUncontrollableLocalPathMatrix.length;
        Long adjustedEnd = (end == -1) ? Long.valueOf(n-1) : end;

        Set<Long> sources = new HashSet<>();

        if(isLocalAction){
            // * 1) if isLocalAction, the sources will be all local/uncontrollable paths
            sources = sourcesWithUncontrollablePath.get(adjustedEnd);
        }else{
            // * 2) if is not a local action, the sources will have  unc/loc paths before and after action.
            Set<Long> intermediateSources = sourcesWithUncontrollablePath.get(adjustedEnd);
            // Step: Find states with 'action' leading to intermediateSources
            for (Long intermediateSource : intermediateSources) {
                if (sourceWithLabel.containsKey(intermediateSource)) {
                    Map<String, Set<Long>> labelMap = sourceWithLabel.get(intermediateSource);
                    if (labelMap.containsKey(action)) {
                        sources.addAll(labelMap.get(action));
                    }
                }
            }

            // Step: Add all states that can reach sources via unc/loc paths
            Set<Long> finalSources = new HashSet<>();
            for (Long source : sources) {
                finalSources.addAll(sourcesWithUncontrollablePath.get(source));
            }

            sources.addAll(finalSources);
        }

        sources.remove(Long.valueOf(n-1));
        return sources;
    }


    public MTS<Long, String> minimiseWithPartition(List<Pair<Set<Long>, Set<Long>>> partition,
                                                   MTS<Long, String> env, Set<String> localAlphabet, Set<String> translatedControllableLabels, Map<String, String> translatorControllable) {


        MTS<Long, String> result = new MTSImpl<Long, String>(0L);
        for(int i=0; i<partition.size(); i++ ) result.addState((long) i);

        Long classIndex= 0L;
        Long initialState = (long) -1;
        result.addActions(env.getActions());

        for(Pair<Set<Long>, Set<Long>> equivalenceClass : partition){

            Set<Long> statesInClass = equivalenceClass.getSecond();
            if(statesInClass.contains(0L)) initialState = classIndex;

            for(Long state : statesInClass){
                BinaryRelation<String, Long> stateEnvTransitions = env.getTransitions(state, MTS.TransitionType.REQUIRED);
                for(Pair<String, Long> transition : stateEnvTransitions){
                    String label = transition.getFirst();
                    Long dstState = transition.getSecond();
                    Long dstClass = referenceStateToClass.get(dstState);

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
                }
            }
            classIndex++;
        }

        if(initialState!=-1) result.setInitialState(initialState);
        return result;
    }

    private boolean classHasOutgoingTransition(Set<Long> statesInClass, Long srcClass, String label, MTS<Long, String> env) {

        for(Long state : statesInClass){
            BinaryRelation<String, Long> stateEnvTransitions = env.getTransitions(state, MTS.TransitionType.REQUIRED);
            for(Pair<String, Long> transition : stateEnvTransitions){
                String labelTransition = transition.getFirst();
                Long dstState = transition.getSecond();
                Long dstClass = referenceStateToClass.get(dstState);
                if(labelTransition.equals(label) && !dstClass.equals(srcClass))
                    return true;
            }
        }
        return false;
    }

    private Set<Long> getUncontrollableLocalsDstOf(Long source, Set<String> controllableActions, Vector<String> localLabels) {

        Set<Long> succ = new HashSet<>();
        // for all src u → succ with u ∈ (Σu ∩ Υ)∗ do


        return succ;
    }
    private boolean classGetsAnotherThroughUncontrollable(Long end, Long succStateSuccessor, Vector<String> localLabels, MTS<Long, String> env, List<Pair<Set<Long>, Set<Long>>> partition, Set<String> controllableActions) {

        Long classOfSucc = referenceStateToClass.get(succStateSuccessor);
        Long classOfEnd = referenceStateToClass.get(end);

        Set<Long> statesOfClassSucc = partition.get(Math.toIntExact(classOfSucc)).getSecond();
        Set<Long> statesOfClassEnd = partition.get(Math.toIntExact(classOfEnd)).getSecond();

        for(Long stateOfClassSucc : statesOfClassSucc){
            Set<Long> sources = new HashSet<>();
            sources.retainAll(statesOfClassEnd);
            for(Long source : sources){
                for(Pair<String, Long> transitionsOfSource : env.getTransitions(source, MTS.TransitionType.REQUIRED)){
                    String label = transitionsOfSource.getFirst();
                    Long dstState = transitionsOfSource.getSecond();
                    if(localLabels.contains(label) && !controllableActions.contains(label) && statesOfClassSucc.contains(dstState)){
                        return true;
                    }
                }
            }
        }

        return false;

    }

}
