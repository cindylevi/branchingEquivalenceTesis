package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional;

import MTSSynthesis.ar.dc.uba.model.condition.*;
import MTSSynthesis.ar.dc.uba.model.language.Symbol;
import MTSSynthesis.controller.model.ControllerGoal;
import MTSTools.ac.ic.doc.commons.relations.BinaryRelation;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.MTSImpl;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.LogFormatter;
import ltsa.ac.ic.doc.mtstools.util.fsp.AutomataToMTSConverter;
import ltsa.ac.ic.doc.mtstools.util.fsp.MTSToAutomataConverter;
import ltsa.dispatcher.TransitionSystemDispatcher;
import ltsa.lts.CompactState;
import ltsa.lts.CompositeState;
import ltsa.lts.LTSOutput;
import ltsa.lts.PrintTransitions;
import org.javatuples.Triplet;

import com.google.gson.GsonBuilder;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import static MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.NonDeterministicUtils.translateLabelsAllLast;
import static MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.NonDeterministicUtils.translateLabelsLastOnly;
import static MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.OutputUtils.printIterationNumber;
import static MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.OutputUtils.printMachinesInformation;
import static MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.SOEUtils.getLocalUncontrollableAndFormulaLabels;
import static MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.TransitiveClosureUtils.searchPathsWithMatrix;
import static MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.Utils.selfLoopRemoval;
import static MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.Utils.translateFromOriginal;
import static org.junit.Assert.*;



public class CompositionalApproach<State, Action> {

    public static Boolean justCSV = false;
    public static boolean justExperimentation = false;

    public static String plantSenser;
    private  Map<Long, Set<Long>> sourcesOf = new HashMap<>();
    private  HashSet<Long> alreadyCheckedSource = new HashSet<>();
    private final Map<Long, List<List<String>>> labelTraces = new HashMap<>();
    /** List of LTS that compose the environment. Note that the marked ltss are a prefix of the list! */
    private Set<Action> controllable;

    /** mapping path in composed plant where we have to insert minimised local strategy **/
    private HashMap<Vector<String>, Vector<String>> strategyByPath = new HashMap<>();
    private HashMap<Long, HashSet<Vector<String>>> ancestorsPathByState = new HashMap<>();
    private Map<Pair<Long, Long>, List<List<String>>> pathsMap;
    private HashMap<Long, Set<Long>> splitList;
    private HashMap<Long, Long> referenceStateToClass;


    /** this field indicates if we do Weak SOE or just SOE **/
    private HashSet<Pair<Long, String>> nonDeterministicTransitionsAfterReduce;
    private HashMap<Long, Set<String>> statesToBeFixed;
    private Vector<HashMap<String, String>> totalTranslator = new Vector<>();
    private Set<Triplet> preventDuplicates = new HashSet();

    private HashMap<Long, HashMap<String, Set<Long>>> sourceWithLabel;
    private Map<Long, Map<Long, Boolean>> existUncontrollableLocalPath;
    private BitSet[] existUncontrollableLocalPathMatrix;
    private Map<String, String> translatorControllable = new HashMap<>();
    private boolean checkRealizability = false;

    private static CandidateSelectionHeuristic candidateSelectionHeuristic = CandidateSelectionHeuristic.MinT;
    private static CompositionOrderHeuristic compositionOrderHeuristic = CompositionOrderHeuristic.MaxC;
    public static String sensePlantArchitecture = null;
    public static boolean spectraEngine = false;
    public static boolean strixEngine = false;
    public static boolean monolithicTranslationToRS = false;

    public static boolean enabledWarmupMinimization = false;
    private Map<Long, Set<Long>> sourcesWithUncontrollablePath;

    public static void setCandidateSelectionHeuristic(String heuristic) {
        candidateSelectionHeuristic = CandidateSelectionHeuristic.valueOf(heuristic);
    }

    public static void setCompositionOrderHeuristic(String heuristic) {
        compositionOrderHeuristic = CompositionOrderHeuristic.valueOf(heuristic);
    }

    /** Logger */
    private final Logger logger = Logger.getLogger(CompositionalApproach.class.getName());

    public void synthesize(CompositeState compositeState, LTSOutput output) throws Exception {
        //LOGGER
        //set logger formatter for more control over logs
        logger.setUseParentHandlers(false);
        ConsoleHandler handler = new ConsoleHandler();
        LogFormatter formatter = new LogFormatter();
        handler.setFormatter(formatter);
        logger.addHandler(handler);
        //Sets the minimum level required for messages to be logged
        // SEVERE > WARNING > INFO > CONFIG > FINE > FINER > FINEST
        // we use fine/finer/finest to log exploration info
        logger.setLevel(Level.FINEST);
        handler.setLevel(Level.FINEST);


        assertFalse("Spectra and Strix engines cannot be set simultaneously.", spectraEngine && strixEngine);

        Statistics statistics = new Statistics();
        statistics.start();

        ControllerGoal<String> goal = compositeState.goal;
        ControllerGoal<String> originalGoal = (ControllerGoal<String>) goal.clone();

        // translate controllable goal events
        Set<String> controllableLabels = originalGoal.getControllableActions();

        if (Utils.checkCorrectGoal(goal)) {
            System.err.print("Not correct goal." + "\n");
            return;
        }
        ;

        /* Statistics */
        if (sensePlantArchitecture != null) {
            FileWriter plantArchitecture = new FileWriter(sensePlantArchitecture);
            String graphPlantArchitecture = Utils.PlantSensers.sensePlantArchitecture(plantSenser, compositeState);
            plantArchitecture.write(graphPlantArchitecture);
            plantArchitecture.close();
            return;
        }

        /* Statistics */
        Vector<CompactState> machines = new Vector<>(compositeState.getMachines());
        Vector<CompactState> originalMachines = new Vector<>(compositeState.getMachines());

        if (machines.size() == 1) {
            CompositeState composedSubSys = new CompositeState("LivenesController", machines);
            composedSubSys.compose(output);
            CompactState livenessController = TransitionSystemDispatcher.synthesise(composedSubSys, goal, output);
            TransitionSystemDispatcher.synthesiseWithAnotherEngine(composedSubSys, spectraEngine, strixEngine, monolithicTranslationToRS, output, statistics);
            compositeState.setComposition(livenessController);
            return;
        }

        Vector<CompactState> controlled = new Vector<>();
        HashSet<String> totalAlphabet = new HashSet<>();
        machines.forEach(machine -> {
            totalAlphabet.addAll(machine.getAlphabetV());
        });

        Map<Integer, Pair<Vector<CompactState>, Vector<CompactState>>>
            machinesAtEachIteration = new HashMap<>();

        int k=0;
        int originalNumberOfMachines = originalMachines.size();
        while(true){
            machinesAtEachIteration.putIfAbsent(k,  new Pair<>(new Vector<>(machines), new Vector<>(controlled)));
            k++;
            if(!justCSV)
                printIterationNumber(k);

            statistics.startNewIteration(k);
            /* Statistics  */
            selfLoopRemoval(machines);

            /** Selecting sybsystem
             // subsys will return  < subsys, <alphabetOfMachines, localAlphabetOfMachines> >
             // subsys is the total LTS list to Compose
             // alphabetOfMachines is the total alphabet of the LTS list
             // localAlphabetOfMachines is the local alphabet of the LTS list
             **/
            Pair<Vector<CompactState>, Pair<Set<String>, Set<String>>> subsys =
                    subsystemSelection(k, originalNumberOfMachines, machines, originalGoal, originalMachines, statistics);

            if(!justCSV)
                printMachinesInformation(subsys, statistics);

            // G = G \ subsys
            Vector<CompactState> selectedSubsystem = subsys.getFirst();
            machines.removeAll(selectedSubsystem);

            boolean lastIteration = machines.isEmpty();

            /** Composing the selected machines **/
            long compStart = System.currentTimeMillis();
            CompositeState composedSubSys = Utils.getCompositeState(subsys, output);
            CompactState subsSystemComposition = composedSubSys.composition;
            long compEnd = System.currentTimeMillis();
            statistics.registerCompositionMS(compEnd-compStart);

            subsSystemComposition.name = "CompositionIteration" + k;
            statistics.registerComposition(composedSubSys, k);
            Set<String> subSystemAlphabet = subsys.getSecond().getFirst();
            Set<String> localAlphabet = subsys.getSecond().getSecond();

            /* Statistics */
            if (!justCSV)
                System.out.print("Composition: " + subsSystemComposition.maxStates + " states" + "\n");
            statistics.registerCompositionStates(subsSystemComposition.maxStates);
            /* Statistics */

            /** Applying Local Synthesis **/
            // translate controllable goal events
            Set<String> translatedControllableLabels = translateLabelsAllLast(controllableLabels, totalTranslator);

            // translatorControllable is the uncontrollable self-loop renaming
            translatedControllableLabels.addAll(translatorControllable.keySet());
            ControllerGoal<String> adaptedGoal =
                    FormulaUtils.adaptGoal(subsSystemComposition,
                                           goal,
                                           controllableLabels,
                                           totalTranslator,
                                           translatorControllable,
                                           localAlphabet);

            // LAST ITERATION
            if(lastIteration) {
                CompactState livenessController;
                if(spectraEngine || strixEngine){
                    composedSubSys.goal = adaptedGoal;
                    TransitionSystemDispatcher.synthesiseWithAnotherEngine(composedSubSys, spectraEngine, strixEngine, monolithicTranslationToRS, output, statistics);
                    statistics.end();
                    if (!justCSV)
                        System.out.print(statistics.toString() + "\n");
                    statistics.saveCSV();
                    statistics.saveGraph();
                    return;
                }

                for(CompactState machine : composedSubSys.machines){
                    isNonDeterministic(AutomataToMTSConverter.getInstance().convert(machine));
                }

                statistics.startLastIteration();
                composedSubSys.compose(output);

                // I have to change the self-loop local transitions to "c"_{label}
                MTS<Long,String> composedSubSysLTS = AutomataToMTSConverter.getInstance().convert(composedSubSys.composition);
                for(Long envState : composedSubSysLTS.getStates()){
                    Iterator<Pair<String, Long>> transitionsFromEnvIterator = composedSubSysLTS.getTransitions(envState, MTS.TransitionType.REQUIRED).iterator();
                    while(transitionsFromEnvIterator.hasNext()){
                        Pair<String, Long> transition = transitionsFromEnvIterator.next();
                        if(transition.getFirst().contains("r_") && envState.equals(transition.getSecond()))
                            System.currentTimeMillis();
                        boolean isFakeSelfLoop =
                                Utils.isControllableThroughTranslations(transition.getFirst(), totalTranslator, translatorControllable)
                                        && envState.equals(transition.getSecond());
//                        boolean isFakeSelfLoop = translatorControllable.values().contains(transition.getFirst()) && envState.equals(transition.getSecond());
                        if(isFakeSelfLoop){
                            composedSubSysLTS.addAction("c_"+transition.getFirst());
                            translatorControllable.putIfAbsent("c_"+transition.getFirst(), transition.getFirst());
                            composedSubSysLTS.addRequired(envState, "c_"+transition.getFirst(), transition.getSecond());
                            transitionsFromEnvIterator.remove();
                        }
                    }
                }
                composedSubSys.composition = MTSToAutomataConverter.getInstance().convert(composedSubSysLTS, "Composition", false);
//                composedSubSys.compose(output);

                HashSet<String> newControllables = new HashSet<>(originalGoal.getControllableActions());
                newControllables.addAll(translatorControllable.keySet());
                newControllables.addAll(translatedControllableLabels);
                adaptedGoal.setControllableActions(newControllables);

                Set<String> selfLoopUnc = new HashSet<>(translatorControllable.keySet());
                adaptedGoal.setSelfLoopsUncontrollable(selfLoopUnc);

                if(!spectraEngine && !strixEngine){
                    long mtsaStartTime = System.currentTimeMillis();
                    livenessController = TransitionSystemDispatcher.synthesise(composedSubSys, adaptedGoal, output);

                    MTS<Long, String> livenessControllerMTS = AutomataToMTSConverter.getInstance().convert(livenessController);

                    if(livenessController==null) {
                        System.out.println("There is no live controller");
                        statistics.end();
                        if (!justCSV)
                            System.out.print(statistics.toString() + "\n");
                        statistics.saveCSV();
                        statistics.saveGraph();
                        return;
                    }
                    controlled.add(livenessController);
                    long mtsaEndTime = System.currentTimeMillis();
                }

                if(justExperimentation){
                    statistics.end();
                    statistics.saveCSV();
                    statistics.saveGraph();
                    return;
                }
                // Spectra/Strix engines
                if(spectraEngine || strixEngine){
                    // if the goal is not controllable, we have to use the new distinguisher
                    assertFalse("Spectra and Strix engines cannot be set simultaneously.", spectraEngine && strixEngine);
                    composedSubSys.goal = adaptedGoal;
                    TransitionSystemDispatcher.synthesiseWithAnotherEngine(composedSubSys, spectraEngine, strixEngine, monolithicTranslationToRS, output, statistics);
                }

                statistics.endLastIteration();
                statistics.end();
                if (!justCSV)
                    System.out.print(statistics.toString() + "\n");
                statistics.saveCSV();
                statistics.saveGraph();


                // compositeState will be new composed controller
                compositeState.setMachines(controlled); //
                compositeState.compose(output);
                CompactState controller = compositeState.composition;
                controller = removeFakeControllableLoops(new Vector<>(Collections.singleton(controller))).get(0);

                isNonDeterministic(AutomataToMTSConverter.getInstance().convert(controller));

                controller = removeControllableLoopsFromFinalController(controller, controllableLabels, translatorControllable);
                controller = directRenaming(controller, totalTranslator, false);
                compositeState.setMachines(new Vector<>(Collections.singleton(controller)));
                compositeState.compose(output);
                return;
            }

            long mtsaStartTime = System.currentTimeMillis();
            CompactState localController
                    = LocalSynt.localSynthesis(composedSubSys,
                                               adaptedGoal, output, statistics, translatorControllable, spectraEngine, strixEngine, monolithicTranslationToRS, totalTranslator);
            long mtsaEndTime = System.currentTimeMillis();
            statistics.registerLocalControllerTime(mtsaEndTime-mtsaStartTime);

            if(spectraEngine || strixEngine)
                statistics.registerTimeToReduce(mtsaEndTime-mtsaStartTime);

            if (checkLocalControllerNotNull(localController, statistics)) {

                return;
            }

            /* Statistics */
            if (!justCSV)
                System.out.print("Local Controller: " + localController.maxStates + " states" + "\n");
            statistics.registerLocalControllerStates(localController.maxStates);

            goal = (ControllerGoal<String>) originalGoal.clone();

            MTS<Long, String> localControllerMTS = AutomataToMTSConverter.getInstance().convert(localController);
            int countTransitions = Utils.countTransitions(localControllerMTS);

            // PREPROCESS
            /*long WSOEPreprocessBeginTime = System.currentTimeMillis();
            localControllerMTS = SyntEquivalence.WSOEPreprocess(localControllerMTS,subsys.getSecond(),translatedControllableLabels,goal.getFluents(),totalTranslator);
            long WSOEPreprocessEndTime = System.currentTimeMillis();
            statistics.registerPreprocessMinimizationStates(localControllerMTS.getStates().size(),WSOEPreprocessEndTime-WSOEPreprocessBeginTime);
            */
            statistics.startTransitiveClosure();
            int t = localControllerMTS.getStates().size();
            if (!justCSV)
                System.out.print("controllable path paths method with " + t + " states and " + countTransitions + " transitions." + "\n");

            statistics.registerControlablePathStates(t);
            statistics.registerCountTransitions(countTransitions);

            long started = System.currentTimeMillis();
            Set<String> relevantLabelsFromFormula = FormulaUtils.getRelevantLabelsFrom(adaptedGoal);

            // sourceWithLabel has one key for each lts state
            // if one state has not predecessor, it will have an empty hashmap as value
            sourceWithLabel = TransitiveClosureUtils.getPredecessors(localControllerMTS);
            long end = System.currentTimeMillis();
            if (!justCSV)
                System.out.print("controllable path method finishes in " + (end - started) + "ms" + "\n");
            statistics.registerControllablePathms(end-started);

            if (!justCSV)
                System.out.print("Starting uncontrollable paths method with " + localControllerMTS.getStates().size() + " states." + "\n");

            started = System.currentTimeMillis();

            // i want to check uncontrollable and local paths between states
            Set<String> localUncontrollableAndFormulaLabels
                    = getLocalUncontrollableAndFormulaLabels(localAlphabet, translatedControllableLabels, relevantLabelsFromFormula);
            calculatePathsForWSOE(output, localControllerMTS, localUncontrollableAndFormulaLabels);
            end = System.currentTimeMillis();

            if (!justCSV)
                System.out.print("findPaths method finishes in " + (end - started) + "ms" + "\n");
            statistics.endTransitiveClosure();
            statistics.registerFindPathMS(end-started);

            var currentTimestamp = System.currentTimeMillis();
            var baseName =  "states_" + localControllerMTS.getStates().size() + "_iter_" + k +  "_" + currentTimestamp;

            var timestampFolder = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
            var dir = new File("casos_minimizacion/" + timestampFolder);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            PrintTransitions printer = new PrintTransitions(MTSToAutomataConverter.getInstance().convert(localControllerMTS, "DEFAULT"));
            String automataAminimizar = printer.getFSP(99999);

            var ltsFile = new File(dir, baseName + ".lts");
            try (var out = new PrintWriter(new BufferedWriter(new FileWriter(ltsFile)))) {
                out.print(automataAminimizar);
            } catch (IOException e) {
                e.printStackTrace();
            }

            var configData = new HashMap<String, Object>();

            configData.put("localAlphabet", localAlphabet);
            configData.put("translatedControllableLabels", translatedControllableLabels);
            configData.put("localUncontrollableAndFormulaLabels", localUncontrollableAndFormulaLabels);
            configData.put("relevantLabelsFromFormula", relevantLabelsFromFormula);

            var subsysPair = subsys.getSecond();
            configData.put("subsysSet1", subsysPair.getFirst());
            configData.put("subsysSet2", subsysPair.getSecond());

            configData.put("goalFluents", goal.getFluents());

            configData.put("translatorControllable", translatorControllable);
            configData.put("totalTranslator", totalTranslator);

            configData.put("sourceWithLabel", sourceWithLabel);
            configData.put("existUncontrollableLocalPathMatrix", existUncontrollableLocalPathMatrix);
            configData.put("sourcesWithUncontrollablePath", sourcesWithUncontrollablePath);
            configData.put("existUncontrollableLocalPath", existUncontrollableLocalPath);

            var jsonFile = new File(dir, "configs_" + baseName + ".json");
            try (var writer = new FileWriter(jsonFile)) {
                var gson = GsonConfig.INSTANCE;
                gson.toJson(configData, writer);
            } catch (IOException e) {
                e.printStackTrace();
            }

            started = System.currentTimeMillis();
            SyntEquivalence syntEq = new SyntEquivalence(totalTranslator, sourceWithLabel, existUncontrollableLocalPathMatrix, sourcesWithUncontrollablePath, existUncontrollableLocalPath, output);
            List<Pair<Set<Long>, Set<Long>>> partition = syntEq.WSOE(localControllerMTS,
                    subsys.getSecond(),
                    translatedControllableLabels,
                    goal.getFluents(),
                    output);
            referenceStateToClass = syntEq.getReferenceStateToClass();
            end = System.currentTimeMillis();
            if (!justCSV)
                System.out.print("WSOE method finishes in " + (end - started) + "ms" + "\n");
            statistics.registerWsoeTime(end-started);

            // if minimization has a selfloop change, we have to use the new distinguisher, if null we use localControlerMTS
            MTS<Long, String> minimizationResult
                    = syntEq.minimiseWithPartition(partition, localControllerMTS, localAlphabet, translatedControllableLabels, translatorControllable);

            minimizationResult.removeUnreachableStates();
            statesToBeFixed = NonDeterministicUtils.checkStatesToBeFixedForNonDeterminism(minimizationResult, translatorControllable);


            if (!justCSV)
                System.out.print("Minimized by local events: " + minimizationResult.getStates().size() + " states" + "\n");
            statistics.registerMinimizedStates(minimizationResult.getStates().size());

            if(!statesToBeFixed.isEmpty()){
                //non-deterministic reduction
                Triplet<HashMap<String, String>, MTS<Long, String>, MTS<Long, String>> distinguisher
                        = NonDeterministicUtils.makeDistinguisher(minimizationResult, localControllerMTS, statesToBeFixed, totalTranslator, referenceStateToClass);


                // apply inverse renaming to machines and controlled set
                machines = inverseRenaming(machines, distinguisher.getValue0());
                controlled = inverseRenaming(controlled, distinguisher.getValue0());

                machines.add(0, MTSToAutomataConverter.getInstance().convert(distinguisher.getValue1(), "Composition"+k, false));
                controlled.add(MTSToAutomataConverter.getInstance().convert(distinguisher.getValue2(), "distinguisher", false));

                for(Set<String> renamedLabels : statesToBeFixed.values()){
                    for(String labelToCheck : renamedLabels){
                        assertFalse("Label " + labelToCheck + " should not be included in renamed LTS.", distinguisher.getValue1().getActions().contains(labelToCheck));
                    }
                }
            }else{
                //deterministic reduction
                machines.add(0, MTSToAutomataConverter.getInstance().convert(minimizationResult, "Composition"+k, false));
                controlled.add(localController);
            }
        }
    }


    private boolean isNonDeterministic(MTS<Long, String> mts) {
        for (Long state : mts.getStates()) {
            // Map: etiqueta -> conjunto de destinos
            Map<String, Set<Long>> labelTargets = new HashMap<>();

            BinaryRelation<String, Long> transitions =
                    mts.getTransitions(state, MTS.TransitionType.REQUIRED);

            for (Pair<String, Long> t : transitions) {
                String label = normalizeLabel(t.getFirst());
                Long target = t.getSecond();

                labelTargets.putIfAbsent(label, new HashSet<>());
                Set<Long> targets = labelTargets.get(label);

                targets.add(target);

                // Si hay más de un destino para la misma etiqueta => no determinístico
                if (targets.size() > 1) {
                    return true;
                }
            }
        }
        return false; // determinístico
    }

    private String normalizeLabel(String label) {
        if(label.startsWith("c_")){
            return label.substring(2);
        }
        return label;
    }


    private CompactState directRenaming(CompactState machine, Vector<HashMap<String, String>> totalTranslator, boolean inverse) {
        MTS<Long, String> machineMTS = AutomataToMTSConverter.getInstance().convert(machine);
        MTSImpl<Long, String> renamed = new MTSImpl<>(machineMTS.getInitialState());

        // Traducir todas las acciones
        Set<String> renamedActions = new HashSet<>();
        for (String action : machineMTS.getActions()) {
            renamedActions.add(applyChain(action, totalTranslator, inverse));
        }
        renamed.addActions(renamedActions);

        // Copiar estados
        renamed.addStates(machineMTS.getStates());

        // Traducir todas las transiciones
        for (Long originalState : machineMTS.getStates()) {
            BinaryRelation<String, Long> transitions =
                    machineMTS.getTransitions(originalState, MTS.TransitionType.REQUIRED);

            for (Pair<String, Long> transition : transitions) {
                String label = transition.getFirst();
                Long dstState = transition.getSecond();

                String newLabel = applyChain(label, totalTranslator, inverse);
                renamed.addTransition(originalState, newLabel, dstState, MTS.TransitionType.REQUIRED);
            }
        }

        String prefix = inverse ? "inv_" : "dir_";
        return MTSToAutomataConverter.getInstance().convert(renamed, prefix + machine.getName(), false);
    }

    /**
     * Aplica todas las traducciones encadenadas.
     * Si inverse=true, busca por valores y reemplaza por keys (ρ⁻¹).
     * Si inverse=false, busca por keys y reemplaza por valores (ρ).
     */
    private String applyChain(String label,
                              Vector<HashMap<String, String>> totalTranslator,
                              boolean inverse) {

        String result = label;
        for (HashMap<String, String> translator : totalTranslator) {
            if (inverse) {
                // inversa: value → key
                for (Map.Entry<String, String> e : translator.entrySet()) {
                    if (e.getValue().equals(result)) {
                        result = e.getKey();
                        break;
                    }
                }
            } else {
                // directa: key → value
                if (translator.containsKey(result)) {
                    result = translator.get(result);
                }
            }
        }
        return result;
    }



    private static Pair<Vector<CompactState>, Pair<Set<String>, Set<String>>> subsystemSelection(int k, int originalNumberOfMachines, Vector<CompactState> machines, ControllerGoal<String> originalGoal, Vector<CompactState> originalMachines, Statistics statistics) {
        long startHeuristic = System.currentTimeMillis();
        Pair<Vector<CompactState>, Pair<Set<String>, Set<String>>> subsys;
        if (k > originalNumberOfMachines || !enabledWarmupMinimization) {
             subsys = SubSystemSelectionHeuristics.selectSubSystemWithHeuristic(machines, candidateSelectionHeuristic, compositionOrderHeuristic, originalGoal);
        } else {
            // First iterations are warmup minimizing initial machines
            Set<CompactState> selection = new HashSet<>();
            selection.add(originalMachines.get(k -1));
            subsys = SubSystemSelectionHeuristics.postprocessSelectedCandidate(selection, machines);
        }
        long endHeuristic = System.currentTimeMillis();
        statistics.registerHeuristicRunningMS(endHeuristic-startHeuristic);
        return subsys;
    }


    private static boolean checkLocalControllerNotNull(CompactState localController, Statistics statistics) {
        if(localController ==null){
            System.err.print("There is no solution for local Controller" + "\n");
            statistics.end();
            statistics.saveCSV();
            statistics.saveGraph();
            return true;
        }
        return false;
    }

    private CompactState removeControllableLoopsFromFinalController(CompactState controllerWithoutFakeSelfLoop,
                                                                    Set<String> controllableLabels,
                                                                    Map<String, String> translatorControllable) {

        Set<String> controllableFakeSelfLoops = new HashSet<>();
        for(String label : translatorControllable.keySet()){
            if (label.startsWith("c_")) {
                controllableFakeSelfLoops.add(label.substring(2));
            }
        }
        controllableFakeSelfLoops.retainAll(controllableLabels);

        MTS<Long, String> toRemoveTransitions = AutomataToMTSConverter.getInstance().convert(controllerWithoutFakeSelfLoop);
        Map<Long, BinaryRelation<String, Long>> transitions = toRemoveTransitions.getTransitions(MTS.TransitionType.REQUIRED);
        for(Map.Entry<Long, BinaryRelation<String, Long>> transition : transitions.entrySet()) {
            Long stateFrom = transition.getKey();
            if(transition.getValue().size()>1){
                boolean hasNonLocalTransition = false;
                for (Pair<String, Long> stringLongPair : transition.getValue()) {
                    if (!controllableFakeSelfLoops.contains(stringLongPair.getFirst())) {
                        hasNonLocalTransition = true;
                        break;
                    }
                }
                if(hasNonLocalTransition){
                    Iterator<Pair<String, Long>> iterator = transition.getValue().iterator();
                    while(iterator.hasNext()){
                        Pair<String, Long> pair = iterator.next();
                        if(controllableFakeSelfLoops.contains(pair.getFirst())){
                            iterator.remove();
                        }
                    }
                }
            }
        }
        toRemoveTransitions.removeUnreachableStates();
        return MTSToAutomataConverter.getInstance().convert(toRemoveTransitions, "controller", true);
    }

    private void calculatePathsForWSOE(LTSOutput output, MTS<Long, String> localControllerMTS, Set<String> uncontrollableLocalLabels) throws InterruptedException {
        existUncontrollableLocalPathMatrix = searchPathsWithMatrix(localControllerMTS, uncontrollableLocalLabels, output);
        sourcesWithUncontrollablePath = getSourcesWithUncontrollablePath(existUncontrollableLocalPathMatrix);
    }

    private Map<Long, Set<Long>> getSourcesWithUncontrollablePath(BitSet[] existUncontrollableLocalPathMatrix) {
        Map<Long, Set<Long>> sources = new HashMap<Long, Set<Long>>();
        int n = existUncontrollableLocalPathMatrix.length;

        for(long possibleDst = 0; possibleDst<n; possibleDst++){
            sources.putIfAbsent(possibleDst, new HashSet<>());
            for (long possibleSource = 0; possibleSource < n; possibleSource++) {
                if (existUncontrollableLocalPathMatrix[Math.toIntExact(possibleSource)].get((int) possibleDst)) {
                    sources.get(possibleDst).add(possibleSource);
                }
            }
        }
        return sources;

    }

    private void prepareNewGoalForFinalController(CompositeState compositeState, ControllerGoal<String> goal, ControllerGoal<String> originalGoal, Set<String> controllableLabels, HashSet<String> translatedControllableLabels) {
        translatedControllableLabels.clear();
        translatedControllableLabels.addAll(controllableLabels);
        for(HashMap<String, String> translatorDict : totalTranslator){
            translatedControllableLabels.addAll(NonDeterministicUtils.translateActions(translatedControllableLabels, translatorDict));
        }
        Set<String> alphabet = new HashSet<>();
        for(CompactState lts : compositeState.getMachines()){
            alphabet.addAll(lts.getAlphabetV());
        }
        translatedControllableLabels.retainAll(alphabet);
        goal.setControllableActions(translatedControllableLabels);
        renameGoal(goal, originalGoal, alphabet);
    }

    /**
     *
     * @param controlled: vector of controlled automatas
     * @return controlled automatas without fake controllable loops
     *
     */
    public Vector<CompactState> removeFakeControllableLoops(Vector<CompactState> controlled) {
        HashMap<Long, Set<String>> specialSelfLoop = new HashMap<Long, Set<String>>();
        Vector<CompactState> newControlled = new Vector<CompactState>();

        for(CompactState controlledAutomata : controlled){
            specialSelfLoop.clear();
            MTS<Long, String> toRemoveTransitions = AutomataToMTSConverter.getInstance().convert(controlledAutomata);
            for(String actionToRename : translatorControllable.keySet()){
                if(toRemoveTransitions.getActions().contains(actionToRename)){ // if lts has a renamed self-loop
                    String renamedAction = translatorControllable.get(actionToRename);
                    Set<String> renamedActions = translateFromOriginal(renamedAction, totalTranslator);
                    if(renamedActions.size()>1){
                        renamedActions.remove(renamedAction);
                    }
                    toRemoveTransitions.addActions(renamedActions);
                    Map<Long, BinaryRelation<String, Long>> transitions = toRemoveTransitions.getTransitions(MTS.TransitionType.REQUIRED);
                    for(Map.Entry<Long, BinaryRelation<String, Long>> transition : transitions.entrySet()){
                        Long stateFrom = transition.getKey();
                        Iterator<Pair<String, Long>> transitionsFromState = transition.getValue().iterator();
                        while(transitionsFromState.hasNext()){
                            Pair<String, Long> transitionFromState = transitionsFromState.next();
                            Long stateDst = transitionFromState.getSecond();
                            if(transitionFromState.getFirst().equals(actionToRename)){ // we have to rename to original label
                                for(String actionToAdd : renamedActions){
                                    if(!ltsHasOutgoingTransition(stateFrom, actionToAdd, toRemoveTransitions)){
                                        toRemoveTransitions.addTransition(stateFrom, actionToAdd, stateDst, MTS.TransitionType.REQUIRED);
                                    }else{
                                        specialSelfLoop.putIfAbsent(stateFrom, new HashSet<>());
                                        specialSelfLoop.get(stateFrom).add(actionToRename);
                                    }
                                }
                                transitionsFromState.remove();
                            }
                        }
                    }
                    toRemoveTransitions.removeAction(actionToRename);
                }
                assertFalse("There are remaining self loop "+actionToRename+" transitions", toRemoveTransitions.getActions().contains(actionToRename));
            }

            newControlled.add(MTSToAutomataConverter.getInstance().convert(toRemoveTransitions, "machineComposed", false));
        }
        return newControlled;
    }

    private void checkTranslationsOf(Vector<CompactState> controlled) {
        for(CompactState controlledAutomata : controlled){
            for(HashMap<String, String> translator : totalTranslator){
                for(String alphabetElem : controlledAutomata.getAlphabetV()){
                    assertFalse("Controlled Automata has non-translated action in alphabet.", translator.containsValue(alphabetElem));
                }
            }
        }
    }

    private boolean ltsHasOutgoingTransition(Long state, String label, MTS<Long, String> env) {

        BinaryRelation<String, Long> stateEnvTransitions = env.getTransitions(state, MTS.TransitionType.REQUIRED);
        for(Pair<String, Long> transition : stateEnvTransitions){
            String labelTransition = transition.getFirst();
            Long dstState = transition.getSecond();
            if((labelTransition.equals(label) || labelTransition.equals("c_"+label)) && !state.equals(dstState))
                return true;
            }
        return false;
    }

    private void translateControllableLabels(
            Set<String> controllableLabels,
            Set<String> translatedControllableLabels) {

        translatedControllableLabels.clear();
        translatedControllableLabels.addAll(controllableLabels);

        Queue<String> toProcess = new LinkedList<>(controllableLabels);

        while (!toProcess.isEmpty()) {
            String label = toProcess.poll();

            for (HashMap<String, String> translatorDict : totalTranslator) {
                for (Map.Entry<String, String> entry : translatorDict.entrySet()) {
                    if (label.equals(entry.getValue()) && translatedControllableLabels.add(entry.getKey())) {
                        // only add to queue if it's new
                        toProcess.add(entry.getKey());
                    }
                }
            }
        }
    }

    private void renameGoal(ControllerGoal<String> goal, ControllerGoal<String> originalGoal, Set<String> alphabet) {
        List<Formula> projectedGuarantees = new ArrayList<>();
        Set<Fluent> projectedFluents = new HashSet<>();

        for(Fluent fluent : goal.getFluents()){

            Set<String> translatedInitiatingActions = new HashSet<String>();
            Set<String> initiatingActions = symbolToString(fluent.getInitiatingActions());
            for(String initAction : initiatingActions){
                translatedInitiatingActions.addAll(translateFromOriginal(initAction.toString(), totalTranslator));
            }
            translatedInitiatingActions.retainAll(alphabet);

            Set<String> translatedTerminantingActions = new HashSet<String>();
            Set<String> terminatingActions = symbolToString(fluent.getTerminatingActions());
            for(String termAction : terminatingActions){
                translatedTerminantingActions.addAll(translateFromOriginal(termAction.toString(), totalTranslator));
            }
            translatedTerminantingActions.retainAll(alphabet);
            translatedTerminantingActions.remove("tau");

            if(translatedTerminantingActions.isEmpty() && translatedTerminantingActions.isEmpty()){
                projectedFluents.add(fluent);
            }else{
                fluent.setInitiatingActions(Utils.stringToSymbol(translatedInitiatingActions));
                fluent.setTerminatingActions(Utils.stringToSymbol(translatedTerminantingActions));
                Fluent translatedFluent = new FluentPropositionalVariable(
                        new FluentImpl(fluent.getName(),
                                       Utils.stringToSymbol(translatedInitiatingActions),
                                       Utils.stringToSymbol(translatedTerminantingActions),
                             false)).getFluent();
                projectedFluents.add(translatedFluent);
            }

        }
        // originalGoal.setFluents(projectedFluents);

/*
        for(Formula guarantee : goal.getGuarantees()){
            Fluent fluentOfGuarantee = ((FluentPropositionalVariable) guarantee).getFluent();


            Set<String> translatedInitiatingActions = new HashSet<String>();
            Set<String> initiatingActions = symbolToString(fluentOfGuarantee.getInitiatingActions());
            for(String initAction : initiatingActions){
                translatedInitiatingActions.addAll(translateFromOriginal(initAction.toString(), totalTranslator));

            }
            translatedInitiatingActions.retainAll(alphabet);

            Set<String> translatedTerminantingActions = new HashSet<String>();
            Set<String> terminatingActions = symbolToString(fluentOfGuarantee.getTerminatingActions());
            for(String termAction : terminatingActions){
                translatedTerminantingActions.addAll(translateFromOriginal(termAction.toString(), totalTranslator));

            }
            translatedTerminantingActions.retainAll(alphabet);

            if(translatedTerminantingActions.isEmpty() && translatedTerminantingActions.isEmpty()){
                projectedGuarantees.add(guarantee);
            }else{
                Formula translatedGuarantee = new FluentPropositionalVariable(new FluentImpl(fluentOfGuarantee.getName(), Utils.stringToSymbol(translatedInitiatingActions), Utils.stringToSymbol(translatedTerminantingActions), false));
                projectedGuarantees.add(translatedGuarantee);
            }
        }
        originalGoal.setGuarantees(projectedGuarantees); */
    }


    private Set<String> symbolToString(Set<Symbol> actions) {

        Set<String> result = new HashSet<>();
        for(Symbol initAction : actions){
            result.add(initAction.toString());
        }
        return result;
    }





    // this method translate from Sigma_1 to Sigma_2
    // implements this function ρ−1(→) = { (x, σ, y) | x ρ(σ) −−−→ y }.
    private Vector<CompactState> inverseRenaming(Vector<CompactState> machines,
                                                 HashMap<String, String> translator) {

        // MTS<Long, String> env = AutomataToMTSConverter.getInstance().convert(machineToControl);
        Vector<CompactState> renamedMachines = new Vector<>();
        for(CompactState machine : machines){
            MTS<Long, String> machineMTS = AutomataToMTSConverter.getInstance().convert(machine);

            MTSImpl<Long, String> renamed = new MTSImpl<>(machineMTS.getInitialState());
            Set<String> renamedActions = NonDeterministicUtils.translateActions(machineMTS.getActions(), translator);
            renamed.addActions(renamedActions);

            renamed.addStates(machineMTS.getStates());
            for(Long originalState : machineMTS.getStates()){
                BinaryRelation<String, Long> transitionsOfMachineMTS = machineMTS.getTransitions(originalState, MTS.TransitionType.REQUIRED);
                for(Pair<String,Long> transition : transitionsOfMachineMTS){
                    String label = transition.getFirst();
                    Long dstState = transition.getSecond();
                    if(translator.containsValue(label)){
                        for(Map.Entry<String, String> elemTranslator : translator.entrySet()){
                            if(elemTranslator.getValue().equals(label)){
                                renamed.addTransition(originalState, elemTranslator.getKey(), dstState, MTS.TransitionType.REQUIRED);
                            }
                        }
                    }else{
                        renamed.addTransition(originalState, label, dstState, MTS.TransitionType.REQUIRED);
                    }
                }
            }

            renamedMachines.add(MTSToAutomataConverter.getInstance().convert(renamed, "r_"+machine.getName(), false));
        }

        return renamedMachines;
    }



    // this method translates original LTS alphabet Sigma_1 to renamed alphabet Sigma_2
    private MTS<Long, String> translate(MTS<Long, String> original, HashMap<String, String> translator, MTSImpl<Long, String> renamedMinimised) {
        MTSImpl<Long, String> translated = new MTSImpl<>(original.getInitialState());
        Set<Long> originalStates = original.getStates();
        Collection<String> translatedValues = translator.values();

        Set<String> translatedActions = translateActions(original.getActions(), translator);

        translated.addActions(translatedActions);
        translated.addStates(originalStates);

        Map<Long, BinaryRelation<String, Long>> originalTransitions = original.getTransitions(MTS.TransitionType.REQUIRED);

        for(Long originalState : originalStates){
            for(Pair<String, Long> originalTransition : original.getTransitions(originalState, MTS.TransitionType.REQUIRED)){
                String label = originalTransition.getFirst();
                Long dstState = originalTransition.getSecond();

                if(translatedValues.contains(label)){
                    Set<String> setOfTranslationLabel = inverseTranslateLabel(label, translator);
                    String translatedDistinguisherLabel = getTranslatedDistinguisherLabel(originalState, dstState, setOfTranslationLabel, renamedMinimised);
                    if(translatedDistinguisherLabel==null) {
                        translated.addAction(label);
                        translatedDistinguisherLabel=label;
                    }
                    translated.addRequired(originalState, translatedDistinguisherLabel, dstState);
                }else{
                    translated.addRequired(originalState, label, dstState);
                }
            }
        }
        return translated;
    }

    private String getTranslatedDistinguisherLabel(Long originalState, Long dstState, Set<String> setOfTranslationLabel, MTSImpl<Long, String> renamedMinimised) {

        Long originalPartitionSrcState = referenceStateToClass.get(originalState);
        Long originalPartitionDstState = referenceStateToClass.get(dstState);

        BinaryRelation<String, Long> originalStateTransitions = renamedMinimised.getTransitions(originalPartitionSrcState, MTS.TransitionType.REQUIRED);

        for(Pair<String,Long> transition : originalStateTransitions){
            String label = transition.getFirst();
            Long dstReducedState = transition.getSecond();

            if(setOfTranslationLabel.contains(label) && dstReducedState.equals(originalPartitionDstState)){
                // its the reduced label
                return label;
            }

        }
        return null;
    }

    private Set<String> inverseTranslateLabel(String label, HashMap<String, String> translator) {
        Set<String> result = new HashSet<>();
        for(String indexTranslator : translator.keySet()){
            if(translator.get(indexTranslator).equals(label)){
                result.add(indexTranslator);
            }
        }
        return result;
    }

    private Set<String> translateActions(Set<String> actions, HashMap<String, String> translator) {
        Set<String> translatedActions = new HashSet<>();

        for(String action : actions){
            if(translator.containsValue(action)){
                translatedActions.addAll(inverseTranslateLabel(action, translator));
            }else{
                translatedActions.add(action);
            }
        }

        return translatedActions;
    }

    private HashMap<String, String> fixNonDeterministic(Long stateOfMinimised, MTS<Long, String> minimised, MTS<Long, String> renamed, HashMap<String, String> translator) {

        BinaryRelation<String, Long> transitionsFromState = minimised.getTransitions(stateOfMinimised, MTS.TransitionType.REQUIRED);
        boolean stateHasNoDeterministicTransition = statesToBeFixed.keySet().contains(stateOfMinimised);

        if(stateHasNoDeterministicTransition) {
            Set<String> transitionsToRename = statesToBeFixed.get(stateOfMinimised);
            Integer k=0;

            for(Pair<String, Long> transitionToRename : transitionsFromState){

                String label = transitionToRename.getFirst();
                Long dstState = transitionToRename.getSecond();

                if(transitionsToRename.contains(label)){
                    String renamedLabel = "r_" + k + label;
                    renamed.addAction(renamedLabel);
                    renamed.addTransition(stateOfMinimised, renamedLabel, dstState, MTS.TransitionType.REQUIRED);
                    translator.put(renamedLabel, label);
                    k++;
                }else{
                    renamed.addAction(label);
                    renamed.addTransition(stateOfMinimised, label, dstState, MTS.TransitionType.REQUIRED);
                }
            }
        }

        return translator;
    }




    private void checkStatesToBeFixedForNonDeterminism(MTS<Long, String> env) {
        nonDeterministicTransitionsAfterReduce = new HashSet<Pair<Long,String>>();
        statesToBeFixed = new HashMap<Long, Set<String>>();
        Set<String> repeatedTransitions = new HashSet<>();

        for( Long state : env.getStates() ){
            repeatedTransitions.clear();
            for(Pair<String, Long> transition : env.getTransitions(state, MTS.TransitionType.REQUIRED)){
                String transitionLabel = transition.getFirst();
                if (repeatedTransitions.contains(transitionLabel)) {
                    statesToBeFixed.computeIfAbsent(state, k -> new HashSet<>()).add(transitionLabel);
                    nonDeterministicTransitionsAfterReduce.add(new Pair<>(state, transitionLabel));
                }
                repeatedTransitions.add(transitionLabel);
            }
        }
    }

    private Set<String> getLabelsOfActiveTransitions(MTS<Long, String> env) {

        Set<String> labels = new HashSet<String>();
        Set<Long> toConvertStates = env.getStates();

        for(Long state : toConvertStates){
            BinaryRelation<String, Long> transitions = env.getTransitions(state, MTS.TransitionType.REQUIRED);
            for(Pair<String, Long> transition : transitions){
                labels.add(transition.getFirst());
            }
        }

        return labels;
    }

    // This method returns label -string- translation from newest alphabet to original one
    private String getTranslationOf(String label, Vector<HashMap<String, String>> totalTranslator) {

        String actualSearchString = label;
        for (int index = totalTranslator.size() - 1; index >= 0; index--) {
            HashMap<String, String> translator = totalTranslator.get(index);
            actualSearchString = translator.get(actualSearchString);
        }
        return (actualSearchString==null) ? label : actualSearchString;
    }

    private Set<String> getTranslationsOf(String label, HashMap<String, String> translator) {

        Set<String> translations = new HashSet<String>();

        for(Map.Entry<String, String> eachTranslation : translator.entrySet()){
            if(eachTranslation.getValue().equals(label)){
                translations.add(eachTranslation.getKey());
            }
        }

        return translations;

    }

}
