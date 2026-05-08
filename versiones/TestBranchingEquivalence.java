package IntegrationTests;

import FSP2MTS.ac.ic.doc.mtstools.test.util.TestLTSOuput;
import MTSSynthesis.ar.dc.uba.model.condition.Fluent;
import MTSSynthesis.ar.dc.uba.model.condition.FluentImpl;
import MTSSynthesis.ar.dc.uba.model.language.Symbol;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.BranchingEquivalence;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.GsonConfig;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.SyntEquivalence;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.TransitiveClosureUtils;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import ltsa.ac.ic.doc.mtstools.util.fsp.AutomataToMTSConverter;
import ltsa.lts.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jgrapht.alg.util.Triple;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.awt.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.*;
import java.util.List;

@RunWith(Parameterized.class)
public class TestBranchingEquivalence {

    private static final Log log = LogFactory.getLog(TestBranchingEquivalence.class);
    private File ltsFile;

    /** Default 10 (Jansen et al.); override con -Dnruns=N. */
    private static final int N_RUNS = Integer.parseInt(System.getProperty("nruns", "10"));

    /** Etiqueta de la versión que está compilada en MTSA. Setear con -Dalgo.version=v2. */
    private static final String ALGO_VERSION = System.getProperty("algo.version", "unknown");

    public TestBranchingEquivalence(File ltsFile) {
        this.ltsFile = ltsFile;
    }

    private static final String[] RESOURCE_FOLDERS = {"BranchingEquivalence"};
    private static final String FSP_NAME = "DEFAULT";

    @Parameterized.Parameters(name = "{index}: {0}")
    public static List<File> controllerFiles() throws IOException {

        List<File> allFiles = new ArrayList<>();
        for (String folder : RESOURCE_FOLDERS) {
            for (File f : LTSTestsUtils.getFiles(folder)) {
                allFiles.add(f);
            }
        }
        return allFiles;
    }

    // -------------------------------------------------------------------------
    // Gson instance shared across both tests, with all custom type adapters
    // including the BitSet fix.
    // -------------------------------------------------------------------------
    private static final Gson GSON = buildGson();

    private static Gson buildGson() {
        // Para Fluent necesitamos un factory que use getDelegateAdapter,
        // evitando el loop infinito que ocurre con registerTypeHierarchyAdapter + ctx.deserialize.
        TypeAdapterFactory fluentFactory = new TypeAdapterFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> typeToken) {
                if (!Fluent.class.isAssignableFrom(typeToken.getRawType())) return null;
                // Delegamos directamente al adapter concreto de FluentImpl
                TypeAdapter<FluentImpl> delegate = gson.getDelegateAdapter(this, TypeToken.get(FluentImpl.class));
                return (TypeAdapter<T>) delegate;
            }
        };

        return new GsonBuilder()
                .setPrettyPrinting()
                .registerTypeHierarchyAdapter(BitSet.class, (JsonSerializer<BitSet>)
                        (src, type, ctx) -> ctx.serialize(src.toLongArray()))
                .registerTypeHierarchyAdapter(BitSet.class, (JsonDeserializer<BitSet>)
                        (json, type, ctx) -> BitSet.valueOf(ctx.<long[]>deserialize(json, long[].class)))
                .registerTypeAdapterFactory(fluentFactory)
                .registerTypeHierarchyAdapter(Symbol.class, (JsonDeserializer<Symbol>) (json, type, ctx) -> {
                    if (json.isJsonObject()) {
                        JsonObject obj = json.getAsJsonObject();
                        int kind = obj.has("kind") ? obj.get("kind").getAsInt() : ltsa.lts.Symbol.UNKNOWN_TYPE;
                        String string = obj.has("strSymbol") ? obj.get("strSymbol").getAsString()
                                : obj.has("string") ? obj.get("string").getAsString()
                                : null;
                        return new SymbolAdapter(kind, string);
                    }
                    return new SymbolAdapter(ltsa.lts.Symbol.IDENTIFIER, json.getAsString());
                })
                .create();
    }

    // -------------------------------------------------------------------------
    // Type tokens
    // -------------------------------------------------------------------------
    private static final Type SET_STRING_TYPE =
            new TypeToken<Set<String>>() {}.getType();
    private static final Type MAP_STRING_STRING_TYPE =
            new TypeToken<Map<String, String>>() {}.getType();
    private static final Type SOURCE_WITH_LABEL_TYPE =
            new TypeToken<HashMap<Long, HashMap<String, Set<Long>>>>() {}.getType();
    private static final Type SOURCES_WITH_UNCONTROLLABLE_PATH_TYPE =
            new TypeToken<Map<Long, Set<Long>>>() {}.getType();
    private static final Type BITSET_ARRAY_TYPE =
            new TypeToken<BitSet[]>() {}.getType();
    private static final Type FLUENT_SET_TYPE =
            new TypeToken<Set<FluentImpl>>() {}.getType();
    private static final Type TOTAL_TRANSLATOR_TYPE =
            new TypeToken<Vector<HashMap<String, String>>>() {}.getType();
    private static final Type EXIST_UNCONTROLLABLE_LOCAL_PATH_TYPE =
            new TypeToken<Map<Long, Map<Long, Boolean>>>() {}.getType();
    private static final Type MAP_TYPE =
            new TypeToken<Map<String, Object>>() {}.getType();

    // =========================================================================
    // Helpers para medir memoria con JMX
    // =========================================================================

    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

    /**
     * Fuerza dos pasadas de GC para intentar limpiar el heap antes de medir.
     * Igual que la práctica estándar en papers académicos Java.
     */
    private void forceGC() {
        System.gc();
        System.gc();
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
    }

    private long getUsedHeapBytes() {
        return memoryBean.getHeapMemoryUsage().getUsed();
    }

    /**
     * Calcula la media de un array de doubles.
     */
    private double mean(double[] values) {
        double sum = 0;
        for (double v : values) sum += v;
        return sum / values.length;
    }

    /**
     * Calcula la desviación estándar estimada igual que Jansen:
     * std = sqrt( (sum(xi^2) - (sum(xi))^2 / N) / 8.5 )
     */
    private double stdDev(double[] values) {
        double sumSq = 0, sum = 0;
        for (double v : values) {
            sum += v;
            sumSq += v * v;
        }
        int n = values.length;
        return Math.sqrt((sumSq - (sum * sum) / n) / 8.5);
    }

    @Test
    public void testCompareAlgorithmsBasics_SinChecks() throws Exception {

        StringBuilder report = new StringBuilder();
        report.append("************************************************************\n");
        report.append("INICIANDO TEST: ").append(ltsFile.getName()).append("\n");
        report.append("************************************************************\n\n");

        try {
            FileInput lts = new FileInput(ltsFile);
            LTSOutput output = new TestLTSOuput();
            LTSCompiler compiled = new LTSCompiler(lts, output, ".");
            compiled.compile();
            CompositeState compositeState = compiled.continueCompilation(FSP_NAME);
            MTS<Long, String> originalMTS = AutomataToMTSConverter.getInstance().convert(compositeState.machines.get(0));

            Set<String> allActions = new HashSet<>(originalMTS.getActions());
            Set<String> tauLabels = new HashSet<>();
            Set<String> uncontrollableActions = new HashSet<>();

            final String TAU_PREFIX = "tau";

            log.info("Analizando acciones en [" + ltsFile.getName() + "] con la convención de prefijos:");

            for (String action : allActions) {
                boolean isTau = false, isLocal = false, isControllable = true;

                if (action.startsWith(TAU_PREFIX)) {
                    isTau = true;

                    tauLabels.add(action);

                } else {
                    uncontrollableActions.add(action);
                }

                log.info("  -> '" + action + "' [BB_Tau=" + (isTau || (isLocal && !isControllable)) + ", SOE_Local=" + isLocal + ", SOE_Ctrl=" + isControllable + "]");
            }
            Map<String, String> translatorControllable = new HashMap<>();

            report.append("\nEjecutando Branching Equivalence (BB)...\n");
            long startTimeBB = System.nanoTime();
            Pair<List<Set<Long>>, List<Set<Triple<Long, String, Long>>>> branchingPartition
                    = BranchingEquivalence.getPartitions(originalMTS, tauLabels, new Vector<>(), new HashSet<>()).getFirst(); // <-- UPDATED CALL
            // if minimization has a selfloop change, we have to use the new distinguisher, if null we use localControlerMTS
            MTS<Long, String> minimizedBB
                    = BranchingEquivalence.buildMinimisedMTSFromPartition(originalMTS, tauLabels, translatorControllable,  branchingPartition).getFirst();


            long endTimeBB = System.nanoTime();
            double durationBB_ms = (endTimeBB - startTimeBB) / 1_000_000.0;
            report.append(String.format("  -> BB minimizado. Estados: %d\n", minimizedBB.getStates().size()));

            report.append("\nEjecutando Synthesis Equivalence (SOE)...\n");
            Set<String> controllableActions = new HashSet<>();

            HashMap<Long, HashMap<String, Set<Long>>> sourceWithLabel = buildSourceWithLabel(originalMTS);
            Map<Long, Set<Long>> sourcesWithUncontrollablePath = buildSourcesWithUncontrollablePath(originalMTS, tauLabels, controllableActions);            long maxStateID = originalMTS.getStates().stream().max(Long::compareTo).orElse(0L);
            int stateCount = (int) maxStateID + 2;
            BitSet[] bitSet = new BitSet[stateCount];
            for(int i=0; i<stateCount; i++) bitSet[i] = new BitSet();
            SyntEquivalence syntEquivalence = new SyntEquivalence(new Vector<>(), sourceWithLabel, bitSet, sourcesWithUncontrollablePath, null, null);
            Pair<Set<String>, Set<String>> labelsSOE = Pair.create(allActions, tauLabels);
            Set<Fluent> fluents = new HashSet<>();

            long startTimeSOE = System.nanoTime();
            List<Pair<Set<Long>, Set<Long>>> partitionsSOE = syntEquivalence.WSOE(originalMTS, labelsSOE, controllableActions, fluents, output);
            MTS<Long, String> minimizedSOE = syntEquivalence.minimiseWithPartition(partitionsSOE, originalMTS, tauLabels, controllableActions, translatorControllable);
            long endTimeSOE = System.nanoTime();
            double durationSOE_ms = (endTimeSOE - startTimeSOE) / 1_000_000.0;
            report.append(String.format("  -> SOE minimizado. Estados: %d\n", minimizedSOE.getStates().size()));


        } catch (AssertionError e) {
            report.append("\nVEREDICTO: FALLÓ (").append(e.getMessage()).append(")\n");
        } catch (Exception e) {
            report.append("\nVEREDICTO: ERROR (").append(e.getMessage()).append(")\n");
        } finally {
            String originalName = ltsFile.getName().replace(".lts", "");
            String outputPath = "test_results/";

            File outputDir = new File(outputPath);
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }

            String reportPath = outputPath + originalName + "_report.txt";
            try (java.io.PrintWriter writer = new java.io.PrintWriter(new java.io.FileWriter(reportPath))) {
                writer.print(report.toString());
            } catch (java.io.IOException e) {
                System.err.println("¡Falló la escritura del reporte! " + e.getMessage());
                System.out.println(report.toString());
            }

            System.out.println(report.toString());
        }
    }

    /**
     * Construye el mapa de [Estado -> [Etiqueta -> {Estados origen}]]
     * necesario para el algoritmo SOE.
     */
    private HashMap<Long, HashMap<String, Set<Long>>> buildSourceWithLabel(MTS<Long, String> mts) {
        HashMap<Long, HashMap<String, Set<Long>>> sourceWithLabel = new HashMap<>();

        // Inicializar el mapa para todos los estados
        for (Long state : mts.getStates()) {
            sourceWithLabel.put(state, new HashMap<>());
        }

        // Llenar el mapa iterando sobre todas las transiciones
        for (Long sourceState : mts.getStates()) {
            for (Pair<String, Long> transition : mts.getTransitions(sourceState, MTS.TransitionType.REQUIRED)) {
                String label = transition.getFirst();
                Long targetState = transition.getSecond();

                sourceWithLabel.get(targetState)
                        .computeIfAbsent(label, k -> new HashSet<>())
                        .add(sourceState);
            }
        }
        return sourceWithLabel;
    }

    /**
     * Construye el mapa de [Estado -> {Estados que lo alcanzan por un camino de
     * acciones locales NO controlables (u*)}]
     * necesario para el algoritmo SOE.
     */
    private Map<Long, Set<Long>> buildSourcesWithUncontrollablePath(MTS<Long, String> mts, Set<String> localLabels, Set<String> controllableActions) {
        Map<Long, Set<Long>> sourcesMap = new HashMap<>();
        Set<String> uncontrollableLocal = new HashSet<>(localLabels);
        uncontrollableLocal.removeAll(controllableActions);

        // Mapa de predecesores SÓLO con acciones 'uncontrollableLocal'
        Map<Long, Set<Long>> predecessors = new HashMap<>();
        for (Long sourceState : mts.getStates()) {
            for (Pair<String, Long> transition : mts.getTransitions(sourceState, MTS.TransitionType.REQUIRED)) {
                if (uncontrollableLocal.contains(transition.getFirst())) {
                    Long targetState = transition.getSecond();
                    predecessors.computeIfAbsent(targetState, k -> new HashSet<>()).add(sourceState);
                }
            }
        }

        // Para cada estado, hacer una búsqueda hacia atrás (BFS)
        for (Long state : mts.getStates()) {
            Set<Long> reachableSources = new HashSet<>();
            Queue<Long> queue = new LinkedList<>();

            queue.add(state);
            reachableSources.add(state);

            while (!queue.isEmpty()) {
                Long currentState = queue.poll();
                Set<Long> preds = predecessors.getOrDefault(currentState, Collections.emptySet());

                for (Long pred : preds) {
                    if (reachableSources.add(pred)) { // Si no lo habíamos visitado
                        queue.add(pred);
                    }
                }
            }
            sourcesMap.put(state, reachableSources);
        }
        return sourcesMap;
    }


    @Test
    public void testCompareAlgorithms_SinChecks() throws Exception {

        StringBuilder report = new StringBuilder();
        report.append("************************************************************\n");
        report.append("INICIANDO TEST: ").append(ltsFile.getName()).append("\n");
        report.append("************************************************************\n\n");

        try {
            String baseName = ltsFile.getName().replace(".lts", "").replace(".fsp", "");
            File jsonFile = new File(ltsFile.getParentFile(), "configs_" + baseName + ".json");

            if (!jsonFile.exists()) {
                report.append("VEREDICTO: ERROR (No se encontró el JSON: ").append(jsonFile.getName()).append(")\n");
                return;
            }

            Map<String, Object> configDataRaw;
            try (FileReader reader = new FileReader(jsonFile)) {
                configDataRaw = GSON.fromJson(reader, MAP_TYPE);
            } catch (com.google.gson.JsonSyntaxException e) {
                report.append("VEREDICTO: ERROR (JSON Corrupto o mal formado)\n");
                return;
            }

            Set<String> localAlphabet = GSON.fromJson(
                    GSON.toJson(configDataRaw.get("localAlphabet")), SET_STRING_TYPE);
            Set<String> translatedControllableLabels = GSON.fromJson(
                    GSON.toJson(configDataRaw.get("translatedControllableLabels")), SET_STRING_TYPE);
            Set<String> subsysSet1 = GSON.fromJson(
                    GSON.toJson(configDataRaw.get("subsysSet1")), SET_STRING_TYPE);
            Set<String> subsysSet2 = GSON.fromJson(
                    GSON.toJson(configDataRaw.get("subsysSet2")), SET_STRING_TYPE);
            Pair<Set<String>, Set<String>> subsysSecond = Pair.create(subsysSet1, subsysSet2);
            Set<Fluent> goalFluents = (Set<Fluent>) (Set<?>) GSON.fromJson(
                    GSON.toJson(configDataRaw.get("goalFluents")), FLUENT_SET_TYPE);
            Map<String, String> translatorControllable = GSON.fromJson(
                    GSON.toJson(configDataRaw.get("translatorControllable")), MAP_STRING_STRING_TYPE);
            Vector<HashMap<String, String>> totalTranslator = GSON.fromJson(
                    GSON.toJson(configDataRaw.get("totalTranslator")), TOTAL_TRANSLATOR_TYPE);
            Set<String> localUncontrollableAndFormulaLabels = GSON.fromJson(
                    GSON.toJson(configDataRaw.get("localUncontrollableAndFormulaLabels")), SET_STRING_TYPE);

            Set<String> relevantLabelsFromFormula = GSON.fromJson(
                    GSON.toJson(configDataRaw.get("relevantLabelsFromFormula")), SET_STRING_TYPE);


            LTSOutput output;
            MTS<Long, String> mtsProbe;

            try {
                output = new TestLTSOuput();
                mtsProbe = loadMTS(output);
            } catch (ltsa.lts.LTSException e) {
                report.append("VEREDICTO: ERROR (Sintaxis LTS inválida: ").append(e.getMessage()).append(")\n");
                return;
            }

            int initialStates = mtsProbe.getStates().size();
            System.out.printf("  -> Initial States: %d%n", initialStates);

//            if (initialStates  > 15 || initialStates<= 2 ) {
//                report.append("VEREDICTO: NO SE CORRIO (Cant de estados de mas: ").append(initialStates).append(")\n");
//                return;
//            }

            Set<String> tauLabelsForBB = new HashSet<>(localUncontrollableAndFormulaLabels);

            System.out.println("=== LABEL SETS DEBUG ===");
            System.out.println("localAlphabet (" + localAlphabet.size() + "): " + localAlphabet);
            System.out.println("translatedControllableLabels (" +
                    (translatedControllableLabels != null ? translatedControllableLabels.size() : 0) + "): " + translatedControllableLabels);
            System.out.println("relevantLabelsFromFormula (" +
                    (relevantLabelsFromFormula != null ? relevantLabelsFromFormula.size() : 0) + "): " + relevantLabelsFromFormula);
            System.out.println("localUncontrollableAndFormulaLabels (" +
                    (localUncontrollableAndFormulaLabels != null ? localUncontrollableAndFormulaLabels.size() : 0) + "): " + localUncontrollableAndFormulaLabels);
            System.out.println("tauLabelsForBB [opción B: local - formula] (" + tauLabelsForBB.size() + "): " + tauLabelsForBB);

            // Intersecciones útiles
            Set<String> localControllable = new HashSet<>(localAlphabet);
            if (translatedControllableLabels != null) localControllable.retainAll(translatedControllableLabels);
            Set<String> localUncontrollable = new HashSet<>(localAlphabet);
            localUncontrollable.removeAll(localControllable);
            System.out.println("local ∩ controllable (" + localControllable.size() + "): " + localControllable);
            System.out.println("local \\ controllable [local uncontrollable] (" + localUncontrollable.size() + "): " + localUncontrollable);
            System.out.println("========================");

            // initCounts se mide sobre la instancia probe (no mutada)
            Pair<Integer, Integer> initCounts = countTotalAndTauTransitions(mtsProbe, tauLabelsForBB);

            Pair<Integer, Integer> allLocalCounts = countTotalAndTauTransitions(mtsProbe, localAlphabet);
            int allLocalTransitions = allLocalCounts.getSecond(); // transiciones con label en localAlphabet
            Pair<Integer, Integer> ctrlLocalCounts = countTotalAndTauTransitions(mtsProbe, localControllable);
            int ctrlLocalTransitions = ctrlLocalCounts.getSecond(); // transiciones con label controlable local

            double jvmHeapMaxMB = Runtime.getRuntime().maxMemory() / (1024.0 * 1024.0);
            String modelName = ltsFile.getName();

            int alreadyDone = 0;
            for (int run = 0; run < N_RUNS; run++) {
                if (COMPLETED_PAIRS.contains(modelName + "|" + run)) alreadyDone++;
            }
            if (alreadyDone == N_RUNS) {
                report.append("\n  -> Las ").append(N_RUNS)
                      .append(" iteraciones ya están en el CSV. Salteando.\n");
                System.out.println("[resume] " + modelName + " completo, skip.");
                return;
            }

            for (int run = 0; run < N_RUNS; run++) {
                String key = modelName + "|" + run;
                if (COMPLETED_PAIRS.contains(key)) {
                    System.out.println("[resume] skip " + key);
                    continue;
                }

                boolean warmup = (run == 0);
                String timestamp = Instant.now().toString();

                // ===== BB =====
                int bbStates = -1, bbTotalTrans = -1, bbTauTrans = -1;
                double timeBB_ms = -1, memAfterBB_MB = -1, memPeakBB_MB = -1;
                long gcBB_ms = -1;
                Map<String, Double> bbPartTimes = Collections.emptyMap();
                Map<String, Double> bbBuildTimes = Collections.emptyMap();
                String bbStatus = "OK";
                String bbErrMsg = "";

                forceGC();
                double memBeforeBB_MB = getUsedHeapBytes() / (1024.0 * 1024.0);
                MemorySampler bbSampler = MemorySampler.startNew("mem-bb");
                long bbGcStart = getTotalGCTimeMs();
                long bbStartNs = System.nanoTime();
                try {
                    MTS<Long, String> mtsForBB = loadMTS(output);
                    Pair<Pair<List<Set<Long>>, List<Set<Triple<Long, String, Long>>>>, Map<String, Double>> partitionResult =
                            BranchingEquivalence.getPartitions(mtsForBB, tauLabelsForBB, totalTranslator, goalFluents);
                    Pair<MTS<Long, String>, Map<String, Double>> minimizedBB =
                            BranchingEquivalence.buildMinimisedMTSFromPartition(
                                    mtsForBB, tauLabelsForBB,
                                    translatorControllable != null ? new HashMap<>(translatorControllable) : null,
                                    partitionResult.getFirst());
                    long bbEndNs = System.nanoTime();
                    bbSampler.stop();
                    long bbGcEnd = getTotalGCTimeMs();

                    timeBB_ms = (bbEndNs - bbStartNs) / 1_000_000.0;
                    gcBB_ms = bbGcEnd - bbGcStart;
                    memAfterBB_MB = getUsedHeapBytes() / (1024.0 * 1024.0);
                    memPeakBB_MB = bbSampler.getPeakBytes() / (1024.0 * 1024.0);
                    if (partitionResult.getSecond() != null) bbPartTimes = partitionResult.getSecond();
                    if (minimizedBB.getSecond() != null) bbBuildTimes = minimizedBB.getSecond();

                    bbStates = minimizedBB.getFirst().getStates().size();
                    Pair<Integer, Integer> bbCounts = countTotalAndTauTransitions(minimizedBB.getFirst(), tauLabelsForBB);
                    bbTotalTrans = bbCounts.getFirst();
                    bbTauTrans = bbCounts.getSecond();
                } catch (OutOfMemoryError e) {
                    bbSampler.stop();
                    bbStatus = "OOM";
                    bbErrMsg = "BB:OOM";
                } catch (Throwable e) {
                    bbSampler.stop();
                    bbStatus = "ERROR";
                    bbErrMsg = "BB:" + sanitize(e.toString());
                }

                // ===== WSOE =====
                int wsoeStates = -1, wsoeTotalTrans = -1, wsoeTauTrans = -1;
                double timeWSOE_ms = -1, memBeforeWSOE_MB = -1, memAfterWSOE_MB = -1, memPeakWSOE_MB = -1;
                long gcWSOE_ms = -1;
                String wsoeStatus = "OK";
                String wsoeErrMsg = "";

                forceGC();
                memBeforeWSOE_MB = getUsedHeapBytes() / (1024.0 * 1024.0);
                MemorySampler wsoeSampler = MemorySampler.startNew("mem-wsoe");
                long wsoeGcStart = getTotalGCTimeMs();
                long wsoeStartNs = System.nanoTime();
                try {
                    MTS<Long, String> mtsForWSOE = loadMTS(output);
                    HashMap<Long, HashMap<String, Set<Long>>> sourceWithLabel =
                            TransitiveClosureUtils.getPredecessors(mtsForWSOE);
                    BitSet[] existUncontrollableLocalPathMatrix =
                            TransitiveClosureUtils.searchPathsWithMatrix(mtsForWSOE, localUncontrollableAndFormulaLabels, output);
                    Map<Long, Set<Long>> sourcesWithUncontrollablePath =
                            buildSourcesWithUncontrollablePath(existUncontrollableLocalPathMatrix);

                    SyntEquivalence syntEquivalence = new SyntEquivalence(
                            totalTranslator, sourceWithLabel, existUncontrollableLocalPathMatrix,
                            sourcesWithUncontrollablePath, null, output);

                    List<Pair<Set<Long>, Set<Long>>> partitionsSOE = syntEquivalence.WSOE(
                            mtsForWSOE, subsysSecond, translatedControllableLabels, goalFluents, output);

                    MTS<Long, String> minimizedSOE = syntEquivalence.minimiseWithPartition(
                            partitionsSOE, mtsForWSOE, localAlphabet, translatedControllableLabels,
                            translatorControllable != null ? new HashMap<>(translatorControllable) : null);

                    long wsoeEndNs = System.nanoTime();
                    wsoeSampler.stop();
                    long wsoeGcEnd = getTotalGCTimeMs();

                    timeWSOE_ms = (wsoeEndNs - wsoeStartNs) / 1_000_000.0;
                    gcWSOE_ms = wsoeGcEnd - wsoeGcStart;
                    memAfterWSOE_MB = getUsedHeapBytes() / (1024.0 * 1024.0);
                    memPeakWSOE_MB = wsoeSampler.getPeakBytes() / (1024.0 * 1024.0);

                    wsoeStates = minimizedSOE.getStates().size();
                    Pair<Integer, Integer> wsoeCounts = countTotalAndTauTransitions(minimizedSOE, tauLabelsForBB);
                    wsoeTotalTrans = wsoeCounts.getFirst();
                    wsoeTauTrans = wsoeCounts.getSecond();
                } catch (OutOfMemoryError e) {
                    wsoeSampler.stop();
                    wsoeStatus = "OOM";
                    wsoeErrMsg = "WSOE:OOM";
                } catch (Throwable e) {
                    wsoeSampler.stop();
                    wsoeStatus = "ERROR";
                    wsoeErrMsg = "WSOE:" + sanitize(e.toString());
                }

                // ===== status agregado y verificación cruzada =====
                String rowStatus;
                if ("OOM".equals(bbStatus) || "OOM".equals(wsoeStatus)) rowStatus = "OOM";
                else if ("ERROR".equals(bbStatus) || "ERROR".equals(wsoeStatus)) rowStatus = "ERROR";
                else rowStatus = "OK";

                String errMsg = "";
                if (!bbErrMsg.isEmpty()) errMsg += bbErrMsg;
                if (!wsoeErrMsg.isEmpty()) errMsg += (errMsg.isEmpty() ? "" : "; ") + wsoeErrMsg;

                boolean equalsWSOE = bbStates >= 0 && wsoeStates >= 0
                        && bbStates == wsoeStates
                        && bbTotalTrans == wsoeTotalTrans;

                // ===== escribir fila CSV =====
                File csvFile = new File(CSV_OUTPUT_PATH);
                boolean writeHeader = !csvFile.exists();
                try (PrintWriter pw = new PrintWriter(new FileWriter(csvFile, true))) {
                    if (writeHeader) pw.println(CSV_HEADER);
                    pw.printf(Locale.US,
                            "%s,%s,%d,%s,%s,%s,%s," +
                            "%d,%d,%d,%d," +
                            "%d,%d,%d,%d," +
                            "%d,%d,%d,%.4f,%d," +
                            "%.4f,%.4f,%.4f," +
                            "%d,%d,%d,%.4f,%d," +
                            "%.4f,%.4f,%.4f," +
                            "%s,%.4f," +
                            "%.4f,%.4f,%.4f,%.4f,%.4f," +
                            "%.4f,%d,%.4f,%.4f,%.4f%n",
                            modelName, ALGO_VERSION, run, warmup, rowStatus, errMsg, timestamp,
                            initialStates, initCounts.getFirst(), initCounts.getSecond(), localAlphabet.size(),
                            localControllable.size(), tauLabelsForBB.size(), allLocalTransitions, ctrlLocalTransitions,
                            bbStates, bbTotalTrans, bbTauTrans, timeBB_ms, gcBB_ms,
                            memBeforeBB_MB, memAfterBB_MB, memPeakBB_MB,
                            wsoeStates, wsoeTotalTrans, wsoeTauTrans, timeWSOE_ms, gcWSOE_ms,
                            memBeforeWSOE_MB, memAfterWSOE_MB, memPeakWSOE_MB,
                            equalsWSOE, jvmHeapMaxMB,
                            bbPartTimes.getOrDefault("[getPartitions] partitionIntoSCCWithTauLabels",       -1.0),
                            bbPartTimes.getOrDefault("[getPartitions] computeBvis",                         -1.0),
                            bbPartTimes.getOrDefault("[getPartitions] initial bunch + splitter setup",      -1.0),
                            bbPartTimes.getOrDefault("[getPartitions] Phase 1 (stabilize states) total",    -1.0),
                            bbPartTimes.getOrDefault("[getPartitions] Phase 2 (refine bunches) total",      -1.0),
                            bbPartTimes.getOrDefault("[getPartitions] total",                               -1.0),
                            (long) bbPartTimes.getOrDefault("[getPartitions] main loop iterations",         -1.0).doubleValue(),
                            bbBuildTimes.getOrDefault("[buildMinimisedMTSFromPartition] build states",      -1.0),
                            bbBuildTimes.getOrDefault("[buildMinimisedMTSFromPartition] build transitions", -1.0),
                            bbBuildTimes.getOrDefault("[buildMinimisedMTSFromPartition] total",             -1.0)
                    );
                } catch (IOException e) {
                    report.append("\nError escribiendo en CSV: ").append(e.getMessage());
                }

                COMPLETED_PAIRS.add(key);
                report.append(String.format(
                        "%n  Run %d/%d [%s] BB=%d st/%.1fms WSOE=%d st/%.1fms eq=%s%s",
                        run, N_RUNS - 1, rowStatus, bbStates, timeBB_ms,
                        wsoeStates, timeWSOE_ms, equalsWSOE,
                        errMsg.isEmpty() ? "" : " errs=" + errMsg));
            }
            report.append("\n");
        } catch (AssertionError e) {
            report.append("\nVEREDICTO: FALLÓ (").append(e.getMessage()).append(")\n");
        } catch (Exception e) {
            report.append("\nVEREDICTO: ERROR (").append(e.getMessage()).append(")\n");
            e.printStackTrace();
        } finally {
            String reportDir = "test_results/" + ALGO_VERSION + "/";
            new File(reportDir).mkdirs();
            String reportPath = reportDir + ltsFile.getName().replace(".lts", "").replace(".fsp", "") + "_report.txt";
            try (PrintWriter writer = new PrintWriter(new FileWriter(reportPath))) {
                writer.print(report.toString());
            } catch (IOException e) {
                System.err.println("Error escribiendo reporte: " + e.getMessage());
            }
            System.out.println(report.toString());
        }
    }

    private Pair<Integer, Integer> countTotalAndTauTransitions(MTS<Long, String> mts, Set<String> tauLabels) {
        int total = 0, tauCount = 0;
        for (Long state : mts.getStates()) {
            for (Pair<String, Long> t : mts.getTransitions(state, MTS.TransitionType.REQUIRED)) {
                total++;
                if (tauLabels.contains(t.getFirst())) {
                    tauCount++;
                }
            }
        }
        return new Pair<>(total, tauCount);
    }

    private Map<Long, Set<Long>> buildSourcesWithUncontrollablePath(BitSet[] matrix) {
        Map<Long, Set<Long>> sources = new HashMap<>();
        int n = matrix.length;
        for (long dst = 0; dst < n; dst++) {
            sources.putIfAbsent(dst, new HashSet<>());
            for (long src = 0; src < n; src++) {
                if (matrix[(int) src].get((int) dst)) {
                    sources.get(dst).add(src);
                }
            }
        }
        return sources;
    }

    // =========================================================================
    // CSV de resultados (una fila por iteración) + soporte de resume
    // =========================================================================
    private static final String CSV_OUTPUT_PATH =
            "minimization_results_" + ALGO_VERSION + ".csv";

    private static final String CSV_HEADER =
            "Model,AlgoVersion,Run,Warmup,Status,ErrorMsg,Timestamp," +
            "InitialStates,InitialTransitions,InitialLocalTransitions,LocalAlphabetSize," +
            "LocalControllableSize,TauLabelsSize,AllLocalTransitions,CtrlLocalTransitions," +
            "FinalStatesBB,FinalTransitionsBB,FinalLocalTransitionsBB,TimeBB_ms,BB_GC_ms," +
            "MemBeforeBB_MB,MemAfterBB_MB,MemPeakBB_MB," +
            "FinalStatesWSOE,FinalTransitionsWSOE,FinalLocalTransitionsWSOE,TimeWSOE_ms,WSOE_GC_ms," +
            "MemBeforeWSOE_MB,MemAfterWSOE_MB,MemPeakWSOE_MB," +
            "EqualsWSOE,JVMHeapMax_MB," +
            "BB_SCC_ms,BB_Bvis_ms,BB_InitSplit_ms,BB_Phase1_ms,BB_Phase2_ms," +
            "BB_PartTotal_ms,BB_MainLoopIters,BB_BuildStates_ms,BB_BuildTrans_ms,BB_BuildTotal_ms";

    /** Pares "Model|Run" ya completados en el CSV de esta versión. Habilita resume. */
    private static final Set<String> COMPLETED_PAIRS = loadCompletedPairs();

    private static Set<String> loadCompletedPairs() {
        Set<String> set = new HashSet<>();
        File f = new File(CSV_OUTPUT_PATH);
        if (!f.exists()) return set;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String header = r.readLine();
            if (header == null) return set;
            if (!header.equals(CSV_HEADER)) {
                throw new IllegalStateException(
                        "El CSV existente tiene un header distinto al esperado.\n" +
                        "Renombrá " + CSV_OUTPUT_PATH + " antes de seguir, " +
                        "o borralo si querés empezar de cero.");
            }
            String line;
            while ((line = r.readLine()) != null) {
                String[] cols = line.split(",", -1);
                if (cols.length < 3) continue;
                set.add(cols[0] + "|" + cols[2]); // Model | Run
            }
            System.out.println("[resume] " + set.size() +
                    " (Model,Run) ya completados en " + CSV_OUTPUT_PATH);
        } catch (IOException e) {
            throw new RuntimeException("No pude leer el CSV existente: " + CSV_OUTPUT_PATH, e);
        }
        return set;
    }

    private static long getTotalGCTimeMs() {
        long total = 0;
        for (java.lang.management.GarbageCollectorMXBean gc :
                ManagementFactory.getGarbageCollectorMXBeans()) {
            long t = gc.getCollectionTime();
            if (t > 0) total += t;
        }
        return total;
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        String clean = s.replaceAll("[,\\n\\r]", " ");
        return clean.length() > 200 ? clean.substring(0, 200) : clean;
    }

    /** Muestrea heap usado en background (cada 100ms) para estimar el pico durante una corrida. */
    private static class MemorySampler implements Runnable {
        private final MemoryMXBean mbean = ManagementFactory.getMemoryMXBean();
        private volatile boolean running = true;
        private volatile long peakBytes = 0;
        private final Thread thread;

        private MemorySampler(String name) {
            this.thread = new Thread(this, name);
            this.thread.setDaemon(true);
        }

        static MemorySampler startNew(String name) {
            MemorySampler s = new MemorySampler(name);
            s.thread.start();
            return s;
        }

        @Override public void run() {
            while (running) {
                long used = mbean.getHeapMemoryUsage().getUsed();
                if (used > peakBytes) peakBytes = used;
                try { Thread.sleep(100); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }

        void stop() {
            running = false;
            thread.interrupt();
            try { thread.join(500); } catch (InterruptedException ignored) {}
        }

        long getPeakBytes() { return peakBytes; }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Carga el MTS desde un archivo .lts/.fsp.
     * Se extrae como método para poder llamarlo limpiamente en cada run.
     */
    private MTS<Long, String> loadMTS(LTSOutput output) throws Exception {
        FileInput lts = new FileInput(ltsFile);
        LTSCompiler compiled = new LTSCompiler(lts, output, ".");
        compiled.compile();
        CompositeState compositeState = compiled.continueCompilation("DEFAULT");
        return AutomataToMTSConverter.getInstance().convert(compositeState.machines.get(0));
    }

    private Pair<Integer, Integer> countTransitions(MTS<Long, String> mts, Set<String> local) {
        int total = 0, localCount = 0;
        for (Long state : mts.getStates()) {
            for (Pair<String, Long> t : mts.getTransitions(state, MTS.TransitionType.REQUIRED)) {
                total++;
                if (local.contains(t.getFirst())) localCount++;
            }
        }
        return new Pair<>(total, localCount);
    }

    private static class SymbolAdapter implements MTSSynthesis.ar.dc.uba.model.language.Symbol {
        private final int kind;
        private final String string;

        public SymbolAdapter(int kind, String string) {
            this.kind = kind;
            this.string = string;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SymbolAdapter)) return false;
            SymbolAdapter that = (SymbolAdapter) o;
            return kind == that.kind && Objects.equals(string, that.string);
        }

        @Override
        public int hashCode() {
            return Objects.hash(kind, string);
        }

        @Override
        public String toString() {
            return string != null ? string : String.valueOf(kind);
        }
    }
}
