package IntegrationTests;

import FSP2MTS.ac.ic.doc.mtstools.test.util.TestLTSOuput;
import MTSSynthesis.ar.dc.uba.model.condition.Fluent;
import MTSSynthesis.ar.dc.uba.model.condition.FluentImpl;
import MTSSynthesis.ar.dc.uba.model.language.Symbol;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.BranchingEquivalence;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.BranchingEquivalenceV2;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.BranchingEquivalenceV3C;
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
    private static final int N_RUNS = Integer.parseInt(System.getProperty("nruns", "3"));

    /** Etiqueta de la campaña: un único build de MTSA mide todas las versiones de branching. */
    private static final String CAMPAIGN = "branching_v2_v3c";

    /** Versiones de branching a medir en la misma corrida (comparten el setup del modelo). */
    private enum Algo {
        V2("v2"), V3C("v3c");
        final String label;
        Algo(String label) { this.label = label; }
    }
    private static final Algo[] ALGOS = { Algo.V2, Algo.V3C };

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

            Set<String> tauLabelsForBB = new HashSet<>(localAlphabet);
            tauLabelsForBB.addAll(localAlphabet);
            if (translatedControllableLabels != null) tauLabelsForBB.removeAll(translatedControllableLabels);

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

            int expectedRows = N_RUNS * ALGOS.length;
            int alreadyDone = 0;
            for (int run = 0; run < N_RUNS; run++) {
                for (Algo algo : ALGOS) {
                    if (COMPLETED_PAIRS.contains(modelName + "|" + run + "|" + algo.label)) alreadyDone++;
                }
            }
            if (alreadyDone == expectedRows) {
                report.append("\n  -> Las ").append(expectedRows)
                        .append(" filas (").append(N_RUNS).append(" runs x ")
                        .append(ALGOS.length).append(" algoritmos) ya están en el CSV. Salteando.\n");
                System.out.println("[resume] " + modelName + " completo, skip.");
                return;
            }

            for (int run = 0; run < N_RUNS; run++) {
                boolean warmup = (run == 0);

                for (Algo algo : ALGOS) {
                    String key = modelName + "|" + run + "|" + algo.label;
                    if (COMPLETED_PAIRS.contains(key)) {
                        System.out.println("[resume] skip " + key);
                        continue;
                    }

                    String timestamp = Instant.now().toString();

                    AlgoResult r = runAlgo(algo, output, tauLabelsForBB, totalTranslator,
                            goalFluents, translatorControllable);

                    // ===== escribir fila CSV (formato largo: una fila por algoritmo) =====
                    File csvFile = new File(CSV_OUTPUT_PATH);
                    boolean writeHeader = !csvFile.exists();
                    try (PrintWriter pw = new PrintWriter(new FileWriter(csvFile, true))) {
                        if (writeHeader) pw.println(CSV_HEADER);
                        pw.printf(Locale.US,
                                "%s,%s,%d,%s,%s,%s,%s," +
                                        "%d,%d,%d,%d," +
                                        "%d,%d,%d,%d," +
                                        "%d,%d,%d,%.4f,%d," +
                                        "%.4f,%.4f,%.4f,%.4f," +
                                        "%.4f,%.4f,%.4f,%.4f,%.4f," +
                                        "%.4f,%d,%.4f,%.4f,%.4f%n",
                                modelName, algo.label, run, warmup, r.status, r.errMsg, timestamp,
                                initialStates, initCounts.getFirst(), initCounts.getSecond(), localAlphabet.size(),
                                localControllable.size(), tauLabelsForBB.size(), allLocalTransitions, ctrlLocalTransitions,
                                r.states, r.totalTrans, r.tauTrans, r.time_ms, r.gc_ms,
                                r.memBefore_MB, r.memAfter_MB, r.memPeak_MB, jvmHeapMaxMB,
                                r.partTimes.getOrDefault("[getPartitions] partitionIntoSCCWithTauLabels",       -1.0),
                                r.partTimes.getOrDefault("[getPartitions] computeBvis",                         -1.0),
                                r.partTimes.getOrDefault("[getPartitions] initial bunch + splitter setup",      -1.0),
                                r.partTimes.getOrDefault("[getPartitions] Phase 1 (stabilize states) total",    -1.0),
                                r.partTimes.getOrDefault("[getPartitions] Phase 2 (refine bunches) total",      -1.0),
                                r.partTimes.getOrDefault("[getPartitions] total",                               -1.0),
                                (long) r.partTimes.getOrDefault("[getPartitions] main loop iterations",         -1.0).doubleValue(),
                                r.buildTimes.getOrDefault("[buildMinimisedMTSFromPartition] build states",      -1.0),
                                r.buildTimes.getOrDefault("[buildMinimisedMTSFromPartition] build transitions", -1.0),
                                r.buildTimes.getOrDefault("[buildMinimisedMTSFromPartition] total",             -1.0)
                        );
                    } catch (IOException e) {
                        report.append("\nError escribiendo en CSV: ").append(e.getMessage());
                    }

                    COMPLETED_PAIRS.add(key);
                    report.append(String.format(
                            "%n  Run %d/%d [%s] %s=%d st/%.1fms%s",
                            run, N_RUNS - 1, r.status, algo.label, r.states, r.time_ms,
                            r.errMsg.isEmpty() ? "" : " err=" + r.errMsg));
                }
            }
            report.append("\n");
        } catch (AssertionError e) {
            report.append("\nVEREDICTO: FALLÓ (").append(e.getMessage()).append(")\n");
        } catch (Exception e) {
            report.append("\nVEREDICTO: ERROR (").append(e.getMessage()).append(")\n");
            e.printStackTrace();
        } finally {
            String reportDir = "test_results/" + CAMPAIGN + "/";
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


    private static final String CSV_OUTPUT_PATH =
            "minimization_results_" + CAMPAIGN + ".csv";

    private static final String CSV_HEADER =
            "Model,AlgoVersion,Run,Warmup,Status,ErrorMsg,Timestamp," +
                    "InitialStates,InitialTransitions,InitialLocalTransitions,LocalAlphabetSize," +
                    "LocalControllableSize,TauLabelsSize,AllLocalTransitions,CtrlLocalTransitions," +
                    "FinalStates,FinalTransitions,FinalLocalTransitions,Time_ms,GC_ms," +
                    "MemBefore_MB,MemAfter_MB,MemPeak_MB,JVMHeapMax_MB," +
                    "SCC_ms,Bvis_ms,InitSplit_ms,Phase1_ms,Phase2_ms," +
                    "PartTotal_ms,MainLoopIters,BuildStates_ms,BuildTrans_ms,BuildTotal_ms";

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
                set.add(cols[0] + "|" + cols[2] + "|" + cols[1]); // Model | Run | AlgoVersion
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

    /** Resultado de una corrida de una versión de branching sobre un modelo. */
    private static class AlgoResult {
        int states = -1, totalTrans = -1, tauTrans = -1;
        double time_ms = -1, memBefore_MB = -1, memAfter_MB = -1, memPeak_MB = -1;
        long gc_ms = -1;
        Map<String, Double> partTimes = Collections.emptyMap();
        Map<String, Double> buildTimes = Collections.emptyMap();
        String status = "OK";
        String errMsg = "";
    }

    /**
     * Corre una versión de branching de punta a punta (getPartitions +
     * buildMinimisedMTSFromPartition) midiendo tiempo, memoria y GC.
     * Carga un MTS fresco por algoritmo: la minimización muta el MTS de entrada,
     * y la compilación queda fuera de la región cronometrada, así que no contamina
     * los tiempos pero permite reusar el mismo build de MTSA para todas las versiones.
     */
    private AlgoResult runAlgo(Algo algo, LTSOutput output, Set<String> tauLabels,
                               Vector<HashMap<String, String>> totalTranslator, Set<Fluent> goalFluents,
                               Map<String, String> translatorControllable) {
        AlgoResult r = new AlgoResult();

        forceGC();
        r.memBefore_MB = getUsedHeapBytes() / (1024.0 * 1024.0);
        MemorySampler sampler = MemorySampler.startNew("mem-" + algo.label);
        long gcStart = getTotalGCTimeMs();
        long startNs = System.nanoTime();
        try {
            MTS<Long, String> mts = loadMTS(output);
            Map<String, String> transl =
                    translatorControllable != null ? new HashMap<>(translatorControllable) : null;

            Pair<Pair<List<Set<Long>>, List<Set<Triple<Long, String, Long>>>>, Map<String, Double>> partitionResult;
            Pair<MTS<Long, String>, Map<String, Double>> minimized;
            switch (algo) {
                case V2:
                    partitionResult = BranchingEquivalenceV2.getPartitions(mts, tauLabels, totalTranslator, goalFluents);
                    minimized = BranchingEquivalenceV2.buildMinimisedMTSFromPartition(
                            mts, tauLabels, transl, partitionResult.getFirst());
                    break;
                case V3C:
                    partitionResult = BranchingEquivalenceV3C.getPartitions(mts, tauLabels, totalTranslator, goalFluents);
                    minimized = BranchingEquivalenceV3C.buildMinimisedMTSFromPartition(
                            mts, tauLabels, transl, partitionResult.getFirst());
                    break;
                default:
                    throw new IllegalStateException("Algoritmo desconocido: " + algo);
            }

            long endNs = System.nanoTime();
            sampler.stop();
            long gcEnd = getTotalGCTimeMs();

            r.time_ms = (endNs - startNs) / 1_000_000.0;
            r.gc_ms = gcEnd - gcStart;
            r.memAfter_MB = getUsedHeapBytes() / (1024.0 * 1024.0);
            r.memPeak_MB = sampler.getPeakBytes() / (1024.0 * 1024.0);
            if (partitionResult.getSecond() != null) r.partTimes = partitionResult.getSecond();
            if (minimized.getSecond() != null) r.buildTimes = minimized.getSecond();

            r.states = minimized.getFirst().getStates().size();
            Pair<Integer, Integer> counts = countTotalAndTauTransitions(minimized.getFirst(), tauLabels);
            r.totalTrans = counts.getFirst();
            r.tauTrans = counts.getSecond();
        } catch (OutOfMemoryError e) {
            sampler.stop();
            r.status = "OOM";
            r.errMsg = algo.label + ":OOM";
        } catch (Throwable e) {
            sampler.stop();
            r.status = "ERROR";
            r.errMsg = algo.label + ":" + sanitize(e.toString());
        }
        return r;
    }

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
