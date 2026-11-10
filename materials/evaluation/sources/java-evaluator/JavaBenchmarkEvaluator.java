import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.IOException;
import java.net.URI;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/** End-to-end Java completion generation and test-based evaluation. */
public final class JavaBenchmarkEvaluator {
  private static final String MASK = "__DATASET_COMPLETION__";
  private static final String BASE_URL = "http://127.0.0.1:8000";
  private static final String MODEL = "aixcoder-7b-v2";
  private static final int MAX_TOKENS = 512;
  private static final int TASK3_MAX_TOKENS = 8192;
  private static final Duration MODEL_TIMEOUT = Duration.ofSeconds(600);
  private static final int MAX_EVALUATION_WORKERS = 8;
  private static final int MAX_BUILD_PROCESSES_PER_WORKER = 2;
  private static final DateTimeFormatter RUN_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
  private static final Set<String> COPY_EXCLUDES = Set.of(
      ".git", ".gradle", ".idea", "build", "target", "out", ".settings",
      ".lowcode-ast-testcase-coverage-cache-v1"
  );
  private static final Set<String> REPORT_DIRECTORIES = Set.of(
      "test-results", "surefire-reports", "failsafe-reports", "allure-results"
  );
  private static final String ANSI_GREEN = "\u001B[32m";
  private static final String ANSI_RED = "\u001B[31m";
  private static final String ANSI_YELLOW = "\u001B[33m";
  private static final String ANSI_RESET = "\u001B[0m";
  private static final List<String> REPOSITORY_DOMAINS = List.of(
      "企业信息化", "智慧物流", "智慧城市"
  );

  private static final Map<String, String> SUBTASK_LABELS = subtaskLabels();
  private static final String EVALUATION_RESULT = "evaluation_result";
  private static final Set<String> EVALUATION_STATUSES = Set.of("passed", "failed", "error");
  private static final ByteArrayOutputStream CONSOLE_OUTPUT = new ByteArrayOutputStream();

  private JavaBenchmarkEvaluator() {
  }

  public static void main(String[] args) throws Exception {
    Arguments arguments = Arguments.parse(args);
    Path root = arguments.root().toAbsolutePath().normalize();
    if (arguments.generateOnly()) {
      generateCompletions(root, arguments);
      return;
    }
    captureConsoleOutput();
    Path reposRoot = root.resolve("repos");
    Map<String, String> repositoryDomains = loadRepositoryDomains(
        root.resolve("repository_catalog.json"));
    Path samplesRoot = root.resolve("samples");
    // Path samplesRoot = root.resolve("sft_samples_origin"); // 记得改回来 stq
    OfflinePredictions offlinePredictions = arguments.predictionsFiles().isEmpty()
        ? null : loadOfflinePredictions(arguments.predictionsFiles());
    if (offlinePredictions != null) {
      System.err.println("Loaded " + offlinePredictions.size()
          + " pre-generated output(s) from " + offlinePredictions.source());
    }
    Path runsRoot = root.resolve("evaluation-runs");
    Files.createDirectories(runsRoot);
    String runSuffix = offlinePredictions == null ? "-aixcoder-v2" : "-sft-offline";
    Path runDir = runsRoot.resolve(RUN_FORMAT.format(Instant.now()) + runSuffix);
    Files.createDirectories(runDir);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        cleanupPrecompileLogs(runDir);
      } catch (IOException exception) {
        System.err.println("Failed to clean precompile logs from " + runDir + ": " + exception);
      }
    }, "cleanup-precompile-logs"));

    List<SampleOutcome> outcomes = new ArrayList<>();
    persistRunState(runDir, outcomes, "", repositoryDomains);
    long repositoryOrderBase = 0;
    for (Path sampleRepo : discoverSampleRepositories(samplesRoot)) {
      Map<Integer, List<Object>> samplesByTask = loadSamples(
          sampleRepo, arguments.maxSamplesPerTask(), arguments.tasks(), offlinePredictions);
      List<WorkItem> workItems = flattenSamples(samplesByTask, repositoryOrderBase);
      repositoryOrderBase += workItems.size();
      if (workItems.isEmpty()) continue;
      String repoDirectory = repositoryFromSamples(sampleRepo, workItems);
      List<SampleOutcome> repositoryOutcomes = new ArrayList<>();
      List<WorkItem> pendingItems = new ArrayList<>();
      List<WorkItem> task1Items = new ArrayList<>();
      for (WorkItem item : workItems) {
        if (item.task() == 1) {
          if (arguments.skip()) {
            // Task1 skip rows are rescored from their stored completion. Legacy build/test
            // statuses do not represent the current character metric.
            repositoryOutcomes.add(cachedOutcome(repoDirectory, item, offlinePredictions, ""));
          } else {
            task1Items.add(item);
          }
          continue;
        }
        String cachedStatus = arguments.skip()
            ? offlinePredictions.cachedStatusFor(repoDirectory, item.sample()) : "";
        if (cachedStatus.isEmpty()) {
          pendingItems.add(item);
        } else {
          repositoryOutcomes.add(cachedOutcome(repoDirectory, item, offlinePredictions, cachedStatus));
        }
      }
      if (!repositoryOutcomes.isEmpty()) {
        synchronizedPrintln("[" + repoDirectory + "] reused " + repositoryOutcomes.size()
            + " cached result(s); " + pendingItems.size() + " sample(s) require evaluation");
      }
      if (!task1Items.isEmpty()) {
        repositoryOutcomes.addAll(evaluateTask1Parallel(
            repoDirectory, task1Items, automaticWorkerCount(task1Items.size()), offlinePredictions));
      }
      // Cached-only repositories need neither a checkout nor baseline compilation.
      if (!pendingItems.isEmpty()) {
        Path repoRoot = reposRoot.resolve(repoDirectory).normalize();
        if (!repoRoot.startsWith(reposRoot) || !Files.isDirectory(repoRoot)) {
          throw new IOException("Repository named by sample does not exist: " + repoDirectory);
        }
        repositoryOutcomes.addAll(evaluateRepositoryParallel(
            repoRoot, runDir, repoDirectory, pendingItems, automaticWorkerCount(pendingItems.size()),
            offlinePredictions));
      }
      outcomes.addAll(repositoryOutcomes);
      persistRunState(runDir, outcomes, repoDirectory, repositoryDomains);
      if (offlinePredictions != null) offlinePredictions.persistResults(repositoryOutcomes);
      synchronizedPrintln("[" + repoDirectory + "] persisted " + repositoryOutcomes.size()
          + " repository result(s); cumulative=" + outcomes.size());
    }

    if (offlinePredictions != null && outcomes.isEmpty()) {
      throw new IOException("No Java samples matched the pre-generated outputs in "
          + offlinePredictions.source());
    }

    outcomes.sort(Comparator.comparingLong(SampleOutcome::order));
    List<Result> results = outcomes.stream().map(SampleOutcome::result).toList();
    long failed = results.stream().filter(result -> result.status().equals("failed")).count();
    long errors = results.stream().filter(result -> result.status().equals("error")).count();
    if (!arguments.noPrintResults()) {
      System.out.print(formatConsoleSummary(results, repositoryDomains));
    }
    // Persist reports and progress logs even when the terminal summary is disabled.
    writeReports(runDir, results, repositoryDomains);
    if (failed > 0 || errors > 0) {
      System.exit(1);
    }
  }

  /** Acceptance generation reuses the online prompts/API, without build or evaluation work. */
  private static void generateCompletions(Path root, Arguments arguments) throws Exception {
    Map<Integer, List<Map<String, Object>>> recordsByTask = new LinkedHashMap<>();
    Map<Integer, Set<String>> idsByTask = new LinkedHashMap<>();
    // Read and validate every selected destination before changing any of them.
    for (int task = 1; task <= 3; task++) {
      if (arguments.tasks().contains(task)) {
        Path destination = root.resolve("task" + task + "_completions.jsonl");
        List<Map<String, Object>> records = Files.exists(destination)
            ? readPredictionRecords(destination) : new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> record : records) {
          String id = Json.string(record.get("id"));
          if (!ids.add(id)) throw new IOException("Duplicate pre-generated output id "
              + id + " in " + destination);
        }
        recordsByTask.put(task, records);
        idsByTask.put(task, ids);
        System.err.println(destination.getFileName() + ": loaded " + records.size()
            + " existing output(s)");
      }
    }
    for (Path sampleRepo : discoverSampleRepositories(root.resolve("samples"))) {
      Map<Integer, List<Object>> samplesByTask = loadSamples(
          sampleRepo, arguments.maxSamplesPerTask(), arguments.tasks(), null);
      List<WorkItem> workItems = flattenSamples(samplesByTask, 0);
      if (workItems.isEmpty()) continue;
      String repository = repositoryFromSamples(sampleRepo, workItems);
      int selectedCount = workItems.size();
      workItems = workItems.stream().filter(item -> idsByTask.get(item.task()).add(
          OfflinePredictions.predictionId(repository, item.sample()))).toList();
      synchronizedPrintln("[" + repository + "] " + workItems.size()
          + " missing prediction(s); " + (selectedCount - workItems.size()) + " already present");
      if (workItems.isEmpty()) continue;
      ExecutorService executor = Executors.newFixedThreadPool(automaticWorkerCount(workItems.size()));
      Set<Integer> changedTasks = new LinkedHashSet<>();
      try {
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        Map<Integer, Integer> nextIndex = new HashMap<>();
        recordsByTask.forEach((task, records) -> {
          int next = records.size();
          for (Map<String, Object> record : records) {
            if (record.get("source_index") instanceof Number index) {
              next = Math.max(next, index.intValue() + 1);
            }
          }
          nextIndex.put(task, next);
        });
        for (WorkItem item : workItems) {
          int sourceIndex = nextIndex.get(item.task());
          nextIndex.put(item.task(), sourceIndex + 1);
          futures.add(executor.submit(() -> generateCompletionRecord(repository, item, sourceIndex)));
        }
        for (int index = 0; index < workItems.size(); index++) {
          int task = workItems.get(index).task();
          recordsByTask.get(task).add(futures.get(index).get());
          changedTasks.add(task);
        }
      } finally {
        executor.shutdownNow();
        executor.awaitTermination(30, TimeUnit.SECONDS);
      }
      for (int task : changedTasks) {
        writePredictionRecords(root.resolve("task" + task + "_completions.jsonl"),
            recordsByTask.get(task));
      }
      synchronizedPrintln("[" + repository + "] completions saved; evaluation skipped");
    }
    for (int task : recordsByTask.keySet()) {
      Path destination = root.resolve("task" + task + "_completions.jsonl");
      if (!Files.exists(destination)) writePredictionRecords(destination, recordsByTask.get(task));
    }
    recordsByTask.forEach((task, records) ->
      System.err.println("task" + task + "_completions.jsonl: " + records.size() + " output(s)"));
  }

  private static Map<String, Object> generateCompletionRecord(
      String repository, WorkItem item, int sourceIndex) {
    Map<String, Object> sample = item.sample();
    int maxTokens = item.task() == 3 ? TASK3_MAX_TOKENS : MAX_TOKENS;
    int skeletonCharacters = 0;
    // A failed generation is represented by the same complete record with an empty
    // completion. This lets the remaining samples and repositories continue.
    ModelOutput output = new ModelOutput("", MODEL, null, null, Map.of());
    Exception generationFailure = null;
    try {
      if (item.task() == 3) {
        List<Object> targets = Json.array(sample.get("target_files"));
        if (targets.size() != 1) {
          throw new IOException("SFT prediction format supports exactly one target file for "
              + Json.string(sample.get("id")) + ", found " + targets.size());
        }
        Map<String, Object> target = Json.object(targets.get(0));
        String skeleton = Json.string(target.get("skeleton"));
        skeletonCharacters = skeleton.codePointCount(0, skeleton.length());
        output = callModelOutput(buildTask3Prompt(sample, Json.string(target.get("source_path"))),
            maxTokens, List.of("</s>", "<|endoftext|>", "▁<AIX-SPAN-POST>"));
      } else {
        output = generateTask12Output(sample);
      }
    } catch (Exception exception) {
      generationFailure = exception;
    }
    // Match sft/predictions.jsonl exactly; keep raw text for the existing offline extractor.
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("source_index", sourceIndex);
    record.put("sample_index", 0);
    record.put("sample_count", 1);
    record.put("id", OfflinePredictions.predictionId(repository, sample));
    record.put("language", "java");
    record.put("namespace", repository);
    record.put("only_sft_train", Boolean.TRUE.equals(sample.get("only_sft_train")));
    record.put("completion", output.text());
    record.put("finish_reason", output.finishReason());
    record.put("stop_reason", output.stopReason());
    record.put("model", output.model());
    record.put("prompt_tokens", output.usage().get("prompt_tokens"));
    record.put("skeleton_characters", skeletonCharacters);
    // Online generation uses a fixed token limit, without SFT structure budgeting or trimming.
    record.put("structure_token_budget", null);
    record.put("requested_max_tokens", maxTokens);
    record.put("trimmed_trailing_characters", 0);
    record.put("usage", output.usage());
    if (generationFailure == null) {
      synchronizedPrintln("[" + repository + " task-" + item.task() + " "
          + item.taskIndex() + "/" + item.taskCount() + "] generated");
    } else {
      synchronizedPrintln("[" + repository + " task-" + item.task() + " "
          + item.taskIndex() + "/" + item.taskCount()
          + "] generation failed; writing empty completion: "
          + generationFailure.getClass().getSimpleName() + ": " + generationFailure.getMessage());
    }
    return record;
  }

  private static void persistRunState(
      Path runDir,
      List<SampleOutcome> outcomes,
      String lastCompletedRepository,
      Map<String, String> repositoryDomains) throws IOException {
    List<SampleOutcome> sortedOutcomes = outcomes.stream()
        .sorted(Comparator.comparingLong(SampleOutcome::order))
        .toList();
    List<Result> results = sortedOutcomes.stream().map(SampleOutcome::result).toList();
    List<Object> completionRecords = sortedOutcomes.stream()
        .filter(outcome -> outcome.completionRecord() != null)
        .map(SampleOutcome::completionRecord)
        .map(value -> (Object) value)
        .toList();
    StringBuilder completionsJsonl = new StringBuilder();
    for (Object completionRecord : completionRecords) {
      completionsJsonl.append(Json.stringify(completionRecord)).append('\n');
    }
    writeStringAtomically(runDir.resolve("completions.jsonl"), completionsJsonl.toString());
    writeStringAtomically(runDir.resolve("completions.json"),
        Json.stringify(completionRecords) + "\n");
    writeReports(runDir, results, repositoryDomains);

    Map<String, Object> checkpoint = new LinkedHashMap<>();
    checkpoint.put("updated_at", Instant.now().toString());
    checkpoint.put("last_completed_repository", lastCompletedRepository);
    checkpoint.put("completed_sample_count", results.size());
    writeStringAtomically(runDir.resolve("checkpoint.json"),
        Json.stringify(checkpoint) + "\n");
  }

  private static List<Path> discoverSampleRepositories(Path samplesRoot) throws IOException {
    try (Stream<Path> paths = Files.list(samplesRoot)) {
      return paths.filter(Files::isDirectory)
          .filter(path -> Files.isRegularFile(path.resolve("metadata.json")))
          .sorted()
          .toList();
    }
  }

  private static Map<Integer, List<Object>> loadSamples(
      Path sampleRepo,
      int maxSamplesPerTask,
      Set<Integer> selectedTasks,
      OfflinePredictions offlinePredictions)
      throws IOException {
    Map<Integer, List<Object>> samplesByTask = new LinkedHashMap<>();
    for (int task = 1; task <= 3; task++) {
      if (!selectedTasks.contains(task)) {
        samplesByTask.put(task, List.of());
        continue;
      }
      Path sampleFile = sampleRepo.resolve(task + ".json");
      if (!Files.isRegularFile(sampleFile)) {
        samplesByTask.put(task, List.of());
        continue;
      }
      List<Object> allSamples = Json.array(Json.parse(Files.readString(sampleFile)));
      if (task == 3) {
        allSamples = allSamples.stream()
            .filter(value -> !Boolean.TRUE.equals(
                Json.object(value).get("only_sft_train")))
            .toList();
      }
      if (offlinePredictions != null) {
        String repository = sampleRepo.getFileName().toString();
        allSamples = allSamples.stream()
            .filter(value -> offlinePredictions.contains(repository, Json.object(value)))
            .toList();
      }
      int count = Math.min(maxSamplesPerTask, allSamples.size());
      samplesByTask.put(task, List.copyOf(allSamples.subList(0, count)));
    }
    return samplesByTask;
  }

  private static List<WorkItem> flattenSamples(
      Map<Integer, List<Object>> samplesByTask,
      long orderBase
  ) {
    List<WorkItem> workItems = new ArrayList<>();
    long order = orderBase;
    for (Map.Entry<Integer, List<Object>> taskEntry : samplesByTask.entrySet()) {
      int task = taskEntry.getKey();
      List<Object> samples = taskEntry.getValue();
      for (int index = 0; index < samples.size(); index++) {
        workItems.add(new WorkItem(order++, task, index + 1, samples.size(),
            Json.object(samples.get(index))));
      }
    }
    return workItems;
  }

  private static String repositoryFromSamples(Path sampleRepo, List<WorkItem> workItems)
      throws IOException {
    String directory = sampleRepo.getFileName().toString();
    String repository = null;
    for (WorkItem workItem : workItems) {
      Map<String, Object> sample = workItem.sample();
      String current = Json.string(sample.get("repo"));
      if (current.isBlank() || Json.string(sample.get("repo_tag")).isBlank()) {
        throw new IOException("Sample must contain repo and repo_tag: "
            + Json.string(sample.get("id")));
      }
      if (repository == null) repository = current;
      if (!current.equals(repository) || !current.equals(directory)) {
        throw new IOException("Sample repository identity does not match samples directory: "
            + sampleRepo);
      }
    }
    return repository;
  }

  private static int automaticWorkerCount(int sampleCount) {
    int processors = Runtime.getRuntime().availableProcessors();
    int cpuBound = Math.max(1, processors / MAX_BUILD_PROCESSES_PER_WORKER);
    return Math.min(sampleCount, Math.min(MAX_EVALUATION_WORKERS, cpuBound));
  }

  /** Evaluate task1 predictions without copying, compiling, or mutating a repository. */
  private static List<SampleOutcome> evaluateTask1Parallel(
      String repoDirectory,
      List<WorkItem> workItems,
      int workerCount,
      OfflinePredictions offlinePredictions
  ) throws InterruptedException {
    int activeWorkerCount = Math.min(workerCount, workItems.size());
    ExecutorService executor = Executors.newFixedThreadPool(activeWorkerCount);
    List<Future<SampleOutcome>> futures = new ArrayList<>();
    try {
      for (int index = 0; index < workItems.size(); index++) {
        WorkItem item = workItems.get(index);
        int workerId = index % activeWorkerCount + 1;
        futures.add(executor.submit(
            () -> evaluateWorkItem(null, repoDirectory, workerId, item, offlinePredictions)));
      }
      List<SampleOutcome> outcomes = new ArrayList<>();
      for (int index = 0; index < futures.size(); index++) {
        try {
          outcomes.add(futures.get(index).get());
        } catch (ExecutionException exception) {
          Throwable cause = exception.getCause();
          outcomes.add(errorOutcome(workItems.get(index), cause));
          synchronizedPrintln("[" + repoDirectory + " task1] sample "
              + Json.string(workItems.get(index).sample().get("id"))
              + " failed; recorded as an evaluation error: " + cause);
        }
      }
      return outcomes;
    } finally {
      executor.shutdownNow();
      if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
        synchronizedPrintln("[" + repoDirectory
            + "] warning: task1 worker pool did not terminate promptly");
      }
    }
  }

  private static List<SampleOutcome> evaluateRepositoryParallel(
      Path repoRoot,
      Path runDir,
      String repoDirectory,
      List<WorkItem> workItems,
      int workerCount,
      OfflinePredictions offlinePredictions
  ) throws Exception {
    int activeWorkerCount = Math.min(workerCount, workItems.size());
    Queue<WorkItem> workQueue = new ConcurrentLinkedQueue<>(workItems);
    Queue<Throwable> workerFailures = new ConcurrentLinkedQueue<>();
    synchronizedPrintln(String.format(Locale.ROOT,
        "[%s] starting %d worker(s) for %d sample(s) with one shared queue",
        repoDirectory, activeWorkerCount, workItems.size()));
    ExecutorService executor = Executors.newFixedThreadPool(activeWorkerCount);
    List<Future<List<SampleOutcome>>> futures = new ArrayList<>();
    try {
      for (int workerIndex = 0; workerIndex < activeWorkerCount; workerIndex++) {
        int workerId = workerIndex + 1;
        futures.add(executor.submit(() -> evaluateWorker(
            repoRoot, runDir, repoDirectory, workerId, activeWorkerCount,
            workItems, workQueue, workerFailures, offlinePredictions)));
      }
      List<SampleOutcome> outcomes = new ArrayList<>();
      for (int index = 0; index < futures.size(); index++) {
        Future<List<SampleOutcome>> future = futures.get(index);
        try {
          outcomes.addAll(future.get());
        } catch (ExecutionException exception) {
          Throwable cause = exception.getCause();
          workerFailures.add(cause);
          synchronizedPrintln("[" + repoDirectory + " worker-" + (index + 1)
              + "] stopped; other workers will continue draining the shared queue: " + cause);
        }
      }
      if (!workQueue.isEmpty()) {
        Throwable failure = workerFailures.peek() == null
            ? new IllegalStateException("All evaluation workers stopped before the queue was drained")
            : workerFailures.peek();
        List<WorkItem> remainingItems = new ArrayList<>();
        WorkItem remaining;
        while ((remaining = workQueue.poll()) != null) remainingItems.add(remaining);
        synchronizedPrintln("[" + repoDirectory + "] no worker remains for "
            + remainingItems.size() + " queued sample(s); marking them as errors");
        outcomes.addAll(errorOutcomes(remainingItems, failure));
      }
      return outcomes;
    } finally {
      executor.shutdownNow();
      if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
        synchronizedPrintln("[" + repoDirectory + "] warning: worker pool did not terminate promptly");
      }
    }
  }

  private static List<SampleOutcome> evaluateWorker(
      Path repoRoot,
      Path runDir,
      String repoDirectory,
      int workerId,
      int workerCount,
      List<WorkItem> allWorkItems,
      Queue<WorkItem> workQueue,
      Queue<Throwable> workerFailures,
      OfflinePredictions offlinePredictions
  ) throws Exception {
    String workerLabel = repoDirectory + " worker-" + workerId + "/" + workerCount;
    List<SampleOutcome> outcomes = new ArrayList<>();
    int workspaceGeneration = 0;
    while (!workQueue.isEmpty()) {
      workspaceGeneration++;
      Path workspaceRoot = Files.createTempDirectory(
          "java-function-repo-" + safeName(repoDirectory) + "-worker-" + workerId + "-");
      Path checkout = workspaceRoot.resolve("repo");
      try {
        synchronizedPrintln("[" + workerLabel + "] preparing workspace #" + workspaceGeneration + "...");
        copyRepository(repoRoot, checkout);
        precompileWorkspace(checkout, runDir, repoDirectory, workerId, workspaceGeneration,
            allWorkItems);
        synchronizedPrintln("[" + workerLabel + "] workspace #" + workspaceGeneration + " ready");

        boolean restartWorkspace = false;
        while (true) {
          if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Evaluation worker interrupted");
          }
          WorkItem workItem = workQueue.poll();
          if (workItem == null) return outcomes;
          SampleOutcome outcome;
          try {
            outcome = evaluateWorkItem(
                checkout, repoDirectory, workerId, workItem, offlinePredictions);
          } catch (InterruptedException exception) {
            workQueue.add(workItem);
            Thread.currentThread().interrupt();
            throw exception;
          } catch (Throwable failure) {
            synchronizedPrintln("[" + workerLabel + "] sample "
                + Json.string(workItem.sample().get("id"))
                + " crashed; marking it as error and restarting the workspace: " + failure);
            outcome = errorOutcome(workItem, failure);
            restartWorkspace = true;
          }
          outcomes.add(outcome);
          if (restartWorkspace
              || outcome.result().tests().stream().anyMatch(TestRun::timedOut)) {
            synchronizedPrintln("[" + workerLabel
                + "] discarding workspace and continuing remaining samples in a fresh workspace");
            restartWorkspace = true;
            break;
          }
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw exception;
      } catch (Throwable failure) {
        workerFailures.add(failure);
        synchronizedPrintln("[" + workerLabel
            + "] workspace preparation failed; this worker is stopping while other workers "
            + "continue draining the shared queue: " + failure);
        return outcomes;
      } finally {
        try {
          deleteRecursively(workspaceRoot);
        } catch (IOException cleanupFailure) {
          synchronizedPrintln("[" + workerLabel
              + "] workspace cleanup failed but evaluation will continue: " + cleanupFailure);
        }
      }
    }
    return outcomes;
  }

  private static List<SampleOutcome> errorOutcomes(
      List<WorkItem> workItems, Throwable failure) {
    return workItems.stream().map(workItem -> errorOutcome(workItem, failure)).toList();
  }

  private static SampleOutcome errorOutcome(WorkItem workItem, Throwable failure) {
    return new SampleOutcome(
        workItem.order(), Result.error(workItem.sample(), "", "", failure), null);
  }

  /** Reconstruct report output from the prediction; never build or run tests for cached rows. */
  private static SampleOutcome cachedOutcome(
      String repository, WorkItem item, OfflinePredictions predictions, String status)
      throws IOException {
    Map<String, Object> sample = item.sample();
    String raw = predictions.completionFor(repository, sample);
    String completion = raw;
    String resultStatus = status;
    String resultError = "";
    if (item.task() == 1) {
      try {
        completion = task1Completion(sample, raw);
        resultStatus = "scored";
      } catch (RuntimeException exception) {
        completion = "";
        resultStatus = "error";
        resultError = exception.getClass().getSimpleName() + ": " + exception.getMessage();
      }
    } else {
      try {
        if (item.task() == 3) {
          completion = extractJavaFile(raw);
        } else {
          completion = extractTask2Completion(raw, sample);
        }
      } catch (IOException | RuntimeException ignored) {
        // Failed/error predictions may not be extractable. Their cached status is still valid.
      }
    }
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("id", Json.string(sample.get("id")));
    record.put("repo", Json.string(sample.get("repo")));
    record.put("repo_tag", Json.string(sample.get("repo_tag")));
    record.put("task", Json.string(sample.get("task")));
    if (item.task() == 3) {
      List<Map<String, Object>> files = new ArrayList<>();
      if (sample.get("target_files") instanceof List<?> targets && !targets.isEmpty()) {
        files.add(Map.of("source_path", Json.string(Json.object(targets.get(0)).get("source_path")),
            "completion", completion, "raw_output", raw));
      }
      record.put("files", files);
      completion = Json.stringify(files);
      raw = completion;
    } else {
      record.put("completion", completion);
      record.put("raw_output", raw);
    }
    Result result = new Result(Json.string(sample.get("id")), repository,
        Json.string(sample.get("repo_tag")), Json.string(sample.get("task")), resultStatus,
        completion, raw, sample, List.of(), resultError, true);
    return new SampleOutcome(item.order(), result, record);
  }

  private static SampleOutcome evaluateWorkItem(
      Path checkout,
      String repoDirectory,
      int workerId,
      WorkItem workItem,
      OfflinePredictions offlinePredictions
  ) throws Exception {
    if (workItem.task() == 3) {
      return evaluateTask3WorkItem(
          checkout, repoDirectory, workerId, workItem, offlinePredictions);
    }
    Map<String, Object> sample = workItem.sample();
    String id = Json.string(sample.get("id"));
    String prefix = String.format(Locale.ROOT, "[%s worker-%d task-%d %d/%d] ",
        repoDirectory, workerId, workItem.task(), workItem.taskIndex(), workItem.taskCount());
    String raw = "";
    String completion = "";
    String phase = "generation";
    Result result;
    Map<String, Object> completionRecord = null;
    synchronizedPrintln(prefix + (offlinePredictions == null
        ? "generating..." : "loading pre-generated output..."));
    try {
      raw = offlinePredictions == null
          ? generateTask12Output(sample).text()
          : offlinePredictions.completionFor(repoDirectory, sample);
      completion = workItem.task() == 1
          ? task1Completion(sample, raw)
          : extractTask2Completion(raw, sample);
      synchronizedPrintln(prefix + (offlinePredictions == null
          ? "generated; scoring..." : "loaded; scoring..."));
      phase = "evaluation";
      completionRecord = Map.of(
          "id", id,
          "repo", Json.string(sample.get("repo")),
          "repo_tag", Json.string(sample.get("repo_tag")),
          "task", Json.string(sample.get("task")),
          "completion", completion,
          "raw_output", raw
      );
      if (workItem.task() == 1) {
        result = new Result(id, Json.string(sample.get("repo")),
            Json.string(sample.get("repo_tag")), Json.string(sample.get("task")), "scored",
            completion, raw, sample, List.of(), "");
      } else {
        result = evaluate(checkout, sample, completion, raw);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw exception;
    } catch (Exception exception) {
      if (phase.equals("evaluation") && workItem.task() == 2) {
        try {
          verifyOriginalSource(checkout, sample);
        } catch (Exception verificationFailure) {
          throw new IOException("Reusable workspace is not pristine after sample " + id,
              verificationFailure);
        }
      }
      result = Result.error(sample, completion, raw, exception);
    }
    String finalProgress = result.status().equals("error")
        ? phase + " failed; "
        : (workItem.task() == 1 ? "scored; " : "evaluated and restored; ");
    printSampleProgress(prefix, finalProgress, result, id);
    return new SampleOutcome(workItem.order(), result, completionRecord);
  }

  private static SampleOutcome evaluateTask3WorkItem(
      Path checkout,
      String repoDirectory,
      int workerId,
      WorkItem workItem,
      OfflinePredictions offlinePredictions
  ) throws Exception {
    Map<String, Object> sample = workItem.sample();
    String id = Json.string(sample.get("id"));
    String prefix = String.format(Locale.ROOT, "[%s worker-%d task-3 %d/%d] ",
        repoDirectory, workerId, workItem.taskIndex(), workItem.taskCount());
    List<Map<String, Object>> generatedFiles = new ArrayList<>();
    Result result;
    Map<String, Object> completionRecord = null;
    String phase = "generation";
    synchronizedPrintln(prefix + (offlinePredictions == null
        ? "generating complete file(s)..." : "loading pre-generated complete file..."));
    try {
      List<Object> targetFiles = Json.array(sample.get("target_files"));
      if (offlinePredictions != null && targetFiles.size() != 1) {
        throw new IOException("Pre-generated output format supports exactly one target file for "
            + id + ", found " + targetFiles.size());
      }
      for (Object targetValue : targetFiles) {
        Map<String, Object> target = Json.object(targetValue);
        String sourcePath = Json.string(target.get("source_path"));
        String prompt = buildTask3Prompt(sample, sourcePath);
        String raw = generateOutput(offlinePredictions, repoDirectory, sample, prompt,
            TASK3_MAX_TOKENS,
            List.of("</s>", "<|endoftext|>", "▁<AIX-SPAN-POST>"));
        String completion = extractJavaFile(raw);
        Map<String, Object> generated = new LinkedHashMap<>();
        generated.put("source_path", sourcePath);
        generated.put("completion", completion);
        generated.put("raw_output", raw);
        generatedFiles.add(generated);
      }
      phase = "evaluation";
      synchronizedPrintln(prefix + (offlinePredictions == null
          ? "generated; evaluating complete file(s)..."
          : "loaded; evaluating complete file..."));
      completionRecord = new LinkedHashMap<>();
      completionRecord.put("id", id);
      completionRecord.put("repo", Json.string(sample.get("repo")));
      completionRecord.put("repo_tag", Json.string(sample.get("repo_tag")));
      completionRecord.put("task", "difficulty3");
      completionRecord.put("files", generatedFiles);
      result = evaluateTask3(checkout, sample, generatedFiles);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw exception;
    } catch (Exception exception) {
      if (phase.equals("evaluation")) {
        try {
          verifyTask3OriginalSources(checkout, sample);
        } catch (Exception verificationFailure) {
          throw new IOException("Reusable workspace is not pristine after task-3 sample " + id,
              verificationFailure);
        }
      }
      String generatedJson = Json.stringify(generatedFiles);
      result = Result.error(sample, generatedJson, generatedJson, exception);
    }
    String finalProgress = result.status().equals("error")
        ? phase + " failed; " : "evaluated and restored; ";
    printSampleProgress(prefix, finalProgress, result, id);
    return new SampleOutcome(workItem.order(), result, completionRecord);
  }

  private static void printSampleProgress(String prefix, String progress, Result result, String id) {
    if (result.task().equals("difficulty1")) {
      synchronizedPrintln(prefix + progress + String.format(Locale.ROOT,
          "CharacterAccuracy=%.2f%% %s",
          ((Number) result.characterMetrics().get("character_accuracy")).doubleValue() * 100.0, id));
      return;
    }
    String status = switch (result.status()) {
      case "passed" -> "PASS";
      case "failed" -> "FAIL";
      default -> "ERROR";
    };
    String color = switch (result.status()) {
      case "passed" -> ANSI_GREEN;
      case "failed" -> ANSI_RED;
      default -> ANSI_YELLOW;
    };
    synchronizedPrintln(prefix + progress + color + status + ANSI_RESET + " " + id);
  }

  private static void synchronizedPrintln(String value) {
    synchronized (System.err) {
      System.err.println(value);
      System.err.flush();
    }
  }

  private static void precompileWorkspace(
      Path checkout,
      Path runDir,
      String repoDirectory,
      int workerId,
      int workspaceGeneration,
      List<WorkItem> workItems
  ) throws Exception {
    Map<String, BuildCommand> uniqueCommands = new LinkedHashMap<>();
    for (WorkItem workItem : workItems) {
        Map<String, Object> sample = workItem.sample();
        List<Object> tests = Json.array(sample.get("tests"));
        for (Object testValue : tests) {
          Map<String, Object> test = Json.object(testValue);
          String workdir = Json.string(test.get("test_workdir"));
          String testCommand = Json.string(test.get("test_command"));
          String command = JavaBuildTool.precompileCommands(testCommand).get(0);
          uniqueCommands.putIfAbsent(workdir + "\u0000" + command,
              new BuildCommand(workdir, testCommand, false));
        }
        if (workItem.task() == 3 && tests.isEmpty()) {
          for (Object commandValue : Json.array(sample.get("build_commands"))) {
            Map<String, Object> build = Json.object(commandValue);
            String workdir = Json.string(build.get("workdir"));
            String command = Json.string(build.get("command"));
            uniqueCommands.putIfAbsent(workdir + "\u0000" + command,
                new BuildCommand(workdir, command, true));
          }
        }
    }
    List<BuildCommand> commands = new ArrayList<>(uniqueCommands.values());
    if (commands.isEmpty()) {
      throw new IOException("No test commands available for repository " + repoDirectory);
    }

    Path logPath = runDir.resolve(
        "precompile-" + safeName(repoDirectory) + "-worker-" + workerId
            + "-workspace-" + workspaceGeneration + ".txt");
    StringBuilder log = new StringBuilder();
    int index = 0;
    synchronizedPrintln(String.format(Locale.ROOT,
        "[%s worker-%d] precompiling %d target(s)...",
        repoDirectory, workerId, commands.size()));
    for (BuildCommand build : commands) {
      index++;
      Path cwd = resolveWorkdir(checkout, build.workdir());
      List<String> attempts = build.direct()
          ? List.of(build.command()) : JavaBuildTool.precompileCommands(build.command());
      TestRun run = null;
      for (String command : attempts) {
        run = runTest(command, cwd);
        log.append("$ ").append(command).append("\n")
            .append("cwd=").append(cwd).append("\n")
            .append("exit=").append(run.exitCode()).append(" timeout=").append(run.timedOut())
            .append(" duration=").append(run.durationSeconds()).append("s\n")
            .append(run.output()).append("\n\n");
        Files.writeString(logPath, log, StandardCharsets.UTF_8);
        if (run.passed()) break;
      }
      if (run == null || !run.passed()) {
        throw new IOException("Baseline precompilation failed for " + repoDirectory
            + " with commands derived from: " + build.command() + "; see " + logPath);
      }
      synchronizedPrintln(String.format(Locale.ROOT,
          "[%s worker-%d] precompiled %d/%d",
          repoDirectory, workerId, index, commands.size()));
    }
    cleanTestReports(checkout);
  }

  private static PromptParts splitContext(Map<String, Object> sample) {
    String context = Json.string(sample.get("context"));
    int marker = context.indexOf(MASK);
    if (marker < 0 || marker != context.lastIndexOf(MASK)) {
      throw new IllegalArgumentException("Expected exactly one completion marker");
    }
    String prefix = context.substring(0, marker);
    String requirement = Json.string(sample.get("requirement"));
    List<Object> tests = Json.array(sample.get("tests"));
    String testNames = tests.stream()
        .map(Json::object)
        .map(test -> Json.string(test.get("name")))
        .sorted()
        .reduce((left, right) -> left + ", " + right)
        .orElse("");
    String hints = "// Requirement: " + requirement.replace("\r", " ").replace("\n", " ") + "\n"
        + "// Relevant tests: " + testNames + "\n";
    int lineStart = Math.max(prefix.lastIndexOf('\n'), prefix.lastIndexOf('\r')) + 1;
    prefix = prefix.substring(0, lineStart) + hints + prefix.substring(lineStart);
    return new PromptParts(prefix, "");
  }

  /** Prefix-only continuation; keep the model's FIM tokens but send an empty suffix. */
  private static String buildPrompt(String prefix, String suffix, String path) {
    String cross = buildCrossFileContext(List.of(), List.of());
    String formattedPrefix = "// the file path is: " + path + "\n"
        + "// the code file is written by Java\n" + prefix;
    return buildFimPrompt("<s>Continue the Java code exactly from the end of the provided prefix. "
        + "Only context before the missing code is provided. Return code only.</s>\n", cross,
        "Code Continuation Task", formattedPrefix, "", "");
  }

  /** Full-file continuation prompt: every natural-language requirement line remains a Java comment. */
  private static String buildTask3Prompt(Map<String, Object> sample, String targetPath) {
    Set<String> skeletons = new LinkedHashSet<>();
    for (Object value : Json.array(sample.get("target_files"))) {
      Map<String, Object> target = Json.object(value);
      if (Json.string(target.get("source_path")).equals(targetPath)) {
        skeletons.add(Json.string(target.get("skeleton")));
      }
    }
    Json.array(sample.get("skeletons")).stream()
        .map(Json::string)
        .forEach(skeletons::add);
    String cross = buildCrossFileContext(List.of(), List.copyOf(skeletons));
    // .append(" role=").append(Json.string(skeleton.get("role"))).append('\n') 暂时去掉，不要直接删，也别直接加回来

    StringBuilder prefix = new StringBuilder()
        .append("// the file path is: ").append(targetPath).append('\n')
        .append("// the code file is written by Java\n")
        .append("// Full-file code completion. Produce the complete Java source for this file.\n")
        .append("// Natural-language requirement:\n");
    String requirement = Json.string(sample.get("requirement"));
    for (String line : requirement.split("\\R", -1)) {
      prefix.append("// ").append(line).append('\n');
    }
    prefix.append("// The complete Java source begins below:\n");
    return buildFimPrompt("", cross, "Full-file Completion Task",
        prefix.toString(), "", "");
  }

  private static List<String> commentSimilarCode(List<String> similarCode) {
    List<String> commentedCode = new ArrayList<>(similarCode.size());

    for (String code : similarCode) {
      String[] lines = code.split("\\R", -1);
      StringBuilder commented = new StringBuilder();

      boolean endsWithLineBreak =
          !code.isEmpty() && (code.endsWith("\n") || code.endsWith("\r"));
      int lineCount = endsWithLineBreak ? lines.length - 1 : lines.length;

      for (int index = 0; index < lineCount; index++) {
        if (index > 0) {
          commented.append('\n');
        }
        commented.append("// ").append(lines[index]);
      }

      if (endsWithLineBreak) {
        commented.append('\n');
      }

      commentedCode.add(commented.toString());
    }

    return commentedCode;
  }

  private static String buildCrossFileContext(
      List<String> declarations,
      List<String> similarCode
  ) {
    // similarCode  = List.of();
    List<String> commentedSimilarCode = commentSimilarCode(similarCode);
    StringBuilder cross = new StringBuilder("// Global Declarations:\n");
    if (declarations.isEmpty()) {
      cross.append("no related global declarations\n");
    } else {
      for (int index = 0; index < declarations.size(); index++) {
        cross.append("// skeleton ").append(index + 1).append(":\n")
            .append(declarations.get(index)).append("\n\n");
      }
    }
    cross.append("// Similar Code Snippets:\n");
    if (commentedSimilarCode.isEmpty()) {
      cross.append("no related similar code snippets\n");
    } else {
    //   for (int index = 0; index < commentedSimilarCode.size(); index++) {
      for (int index = 0; index < 1; index++) {
        cross.append("// skeleton ").append(index + 1).append(":\n")
            .append(commentedSimilarCode.get(index)).append("\n\n");
      }
    }
    return cross.toString();
  }

  private static String buildFimPrompt(
      String preamble,
      String crossFileContext,
      String taskHeading,
      String prefix,
      String suffix,
      String tail
  ) {
    return preamble + crossFileContext + "// " + taskHeading + "\n\n"
        + "<s>▁<AIX-SPAN-PRE>▁<AIX-SPAN-POST>" + suffix
        + "▁<AIX-SPAN-MIDDLE>" + prefix + tail;
  }

  private static OfflinePredictions loadOfflinePredictions(List<Path> inputs) throws IOException {
    Map<String, OfflinePrediction> predictions = new LinkedHashMap<>();
    List<Path> sources = new ArrayList<>();
    Map<Path, List<Map<String, Object>>> recordsBySource = new LinkedHashMap<>();
    for (Path input : inputs) {
      Path source = input.toAbsolutePath().normalize();
      if (recordsBySource.containsKey(source)) {
        throw new IOException("Duplicate pre-generated output file: " + source);
      }
      sources.add(source);
      List<Map<String, Object>> records = readPredictionRecords(source);
      recordsBySource.put(source, records);
      for (Map<String, Object> record : records) {
        String id = Json.string(record.get("id"));
        OfflinePrediction prediction = new OfflinePrediction(
            id, Json.string(record.get("namespace")), Json.string(record.get("language")),
            (String) record.get("completion"), source, record);
        if (predictions.putIfAbsent(id, prediction) != null) {
          throw new IOException("Duplicate pre-generated output id " + id + " in " + source);
        }
      }
    }
    if (predictions.isEmpty()) {
      throw new IOException("Pre-generated output files are empty: " + sources);
    }
    return new OfflinePredictions(List.copyOf(sources), Map.copyOf(predictions), recordsBySource);
  }

  private static List<Map<String, Object>> readPredictionRecords(Path source) throws IOException {
    if (!Files.isRegularFile(source)) {
      throw new IOException("Pre-generated output file does not exist: " + source);
    }
    List<Map<String, Object>> records = new ArrayList<>();
    try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
      String line;
      int lineNumber = 0;
      while ((line = reader.readLine()) != null) {
        lineNumber++;
        if (line.isBlank()) continue;
        Map<String, Object> record;
        try {
          record = Json.object(Json.parse(line));
        } catch (RuntimeException exception) {
          throw new IOException("Invalid JSON object in " + source + " at line " + lineNumber,
              exception);
        }
        if (Json.string(record.get("id")).isBlank()
            || Json.string(record.get("namespace")).isBlank()
            || !(record.get("completion") instanceof String)) {
          throw new IOException("Each pre-generated output must contain non-empty id and namespace "
              + "fields plus a string completion field; invalid record at " + source + ":"
              + lineNumber);
        }
        records.add(record);
      }
    }
    return records;
  }

  private static void writePredictionRecords(Path destination, List<Map<String, Object>> records)
      throws IOException {
    StringBuilder jsonl = new StringBuilder();
    for (Map<String, Object> record : records) {
      jsonl.append(Json.stringify(record)).append('\n');
    }
    writeStringAtomically(destination, jsonl.toString());
  }

  @FunctionalInterface
  private interface CompletionModel {
    ModelOutput generate(String prompt) throws Exception;
  }

  private record CompletionLine(String text, String ending) {}

  /** Preserve physical line endings for teacher forcing; no phantom line after a final newline. */
  private static List<CompletionLine> completionLines(String text) {
    List<CompletionLine> lines = new ArrayList<>();
    int start = 0;
    for (int index = 0; index < text.length(); index++) {
      char ch = text.charAt(index);
      if (ch != '\r' && ch != '\n') continue;
      int end = index + 1;
      if (ch == '\r' && end < text.length() && text.charAt(end) == '\n') end++;
      lines.add(new CompletionLine(text.substring(start, index), text.substring(index, end)));
      start = end;
      index = end - 1;
    }
    if (start < text.length()) lines.add(new CompletionLine(text.substring(start), ""));
    return lines;
  }

  private static boolean isTask1Lines(Map<String, Object> sample) {
    return Json.string(sample.get("task")).equals("difficulty1")
        && (Json.string(sample.get("completion_kind")).equals("lines")
            || Json.string(sample.get("ast_node_kind")).equals("LINE_RANGE"));
  }

  private static String firstCompletionLine(String text) {
    int end = 0;
    while (end < text.length() && text.charAt(end) != '\r' && text.charAt(end) != '\n') end++;
    return text.substring(0, end);
  }

  private static ModelOutput generateTask12Output(Map<String, Object> sample) throws Exception {
    return generateTask12Output(sample, prompt -> callModelOutput(prompt, MAX_TOKENS,
        List.of("\npublic ", "\nprivate ", "\nprotected ", "\nclass ", "\ninterface ", "\n@")));
  }

  private static ModelOutput generateTask12Output(
      Map<String, Object> sample, CompletionModel model) throws Exception {
    PromptParts parts = splitContext(sample);
    String path = Json.string(sample.get("source_path"));
    if (!isTask1Lines(sample)) return model.generate(buildPrompt(parts.prefix(), "", path));
    StringBuilder prefix = new StringBuilder(parts.prefix());
    StringBuilder predicted = new StringBuilder();
    Map<String, Object> usage = new LinkedHashMap<>();
    ModelOutput last = new ModelOutput("", MODEL, null, null, Map.of());
    for (CompletionLine line : completionLines(Json.string(sample.get("ground_truth")))) {
      last = model.generate(buildPrompt(prefix.toString(), "", path));
      predicted.append(firstCompletionLine(last.text())).append(line.ending());
      // Always feed the correct previous line back, even when its prediction was wrong.
      prefix.append(line.text()).append(line.ending());
      for (String key : List.of("prompt_tokens", "completion_tokens", "total_tokens")) {
        if (last.usage().get(key) instanceof Number value) {
          long before = usage.get(key) instanceof Number count ? count.longValue() : 0L;
          usage.put(key, before + value.longValue());
        }
      }
    }
    return new ModelOutput(predicted.toString(), last.model(), last.finishReason(),
        last.stopReason(), usage);
  }

  private static String task1Completion(Map<String, Object> sample, String raw) {
    if (isTask1Lines(sample)) return raw;
    // Include Unicode space characters and NEXT LINE, matching Python str.lstrip().
    int start = 0;
    while (start < raw.length()) {
      int codePoint = raw.codePointAt(start);
      if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)
          && codePoint != 0x85) break;
      start += Character.charCount(codePoint);
    }
    raw = raw.substring(start);
    String truth = Json.string(sample.get("ground_truth"));
    int limit = Math.min(truth.codePointCount(0, truth.length()),
        raw.codePointCount(0, raw.length()));
    return raw.substring(0, raw.offsetByCodePoints(0, limit));
  }

  private static long matchingCharacters(String expected, String predicted) {
    int[] truth = expected.codePoints().toArray();
    int[] actual = predicted.codePoints().toArray();
    long matched = 0;
    for (int index = 0; index < Math.min(truth.length, actual.length); index++) {
      if (truth[index] == actual[index]) matched++;
    }
    return matched;
  }

  /** Character positions are relative to each independently generated line/API span. */
  private static Map<String, Object> task1Metrics(Map<String, Object> sample, String completion) {
    String truth = Json.string(sample.get("ground_truth"));
    long total = 0;
    long matched = 0;
    List<Map<String, Object>> lineResults = new ArrayList<>();
    if (isTask1Lines(sample)) {
      List<CompletionLine> expected = completionLines(truth);
      List<CompletionLine> predicted = completionLines(completion);
      for (int index = 0; index < expected.size(); index++) {
        String target = expected.get(index).text();
        String actual = index < predicted.size() ? predicted.get(index).text() : "";
        long count = target.codePointCount(0, target.length());
        long correct = matchingCharacters(target, actual);
        total += count;
        matched += correct;
        lineResults.add(Map.of("line", index + 1, "ground_truth", target, "prediction", actual,
            "total_characters", count, "matched_characters", correct));
      }
    } else {
      String predicted = task1Completion(sample, completion);
      total = truth.codePointCount(0, truth.length());
      matched = matchingCharacters(truth, predicted);
    }
    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("total_characters", total);
    metrics.put("matched_characters", matched);
    metrics.put("character_accuracy", total == 0 ? 0.0 : (double) matched / total);
    metrics.put("line_results", lineResults);
    return metrics;
  }

  private static String generateOutput(
      OfflinePredictions offlinePredictions,
      String repoDirectory,
      Map<String, Object> sample,
      String prompt
  ) throws Exception {
    if (offlinePredictions != null) {
      return readPreGeneratedOutput(offlinePredictions, repoDirectory, sample);
    }
    return callModel(prompt);
  }

  private static String generateOutput(
      OfflinePredictions offlinePredictions,
      String repoDirectory,
      Map<String, Object> sample,
      String prompt,
      int maxTokens,
      List<String> stops
  ) throws Exception {
    if (offlinePredictions != null) {
      return readPreGeneratedOutput(offlinePredictions, repoDirectory, sample);
    }
    return callModel(prompt, maxTokens, stops);
  }

  private static String readPreGeneratedOutput(
      OfflinePredictions offlinePredictions,
      String repoDirectory,
      Map<String, Object> sample
  ) throws IOException {
    return offlinePredictions.completionFor(repoDirectory, sample);
  }

  private static String callModel(String prompt) throws Exception {
    return callModel(prompt, MAX_TOKENS,
        List.of("\npublic ", "\nprivate ", "\nprotected ", "\nclass ", "\ninterface ", "\n@"));
  }

  private static String callModel(String prompt, int maxTokens, List<String> stops)
      throws Exception {
    return callModelOutput(prompt, maxTokens, stops).text();
  }

  private static ModelOutput callModelOutput(String prompt, int maxTokens, List<String> stops)
      throws Exception {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("model", MODEL);
    payload.put("prompt", prompt);
    payload.put("max_tokens", maxTokens);
    payload.put("temperature", 0.0);
    payload.put("top_p", 0.95);
    payload.put("n", 1);
    payload.put("stop", stops);
    byte[] requestBody = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
    HttpURLConnection connection = (HttpURLConnection) URI.create(
        BASE_URL + "/v1/completions"
    ).toURL().openConnection();
    connection.setRequestMethod("POST");
    connection.setConnectTimeout(30_000);
    connection.setReadTimeout((int) MODEL_TIMEOUT.toMillis());
    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
    connection.setRequestProperty("Accept", "application/json");
    connection.setFixedLengthStreamingMode(requestBody.length);
    connection.setDoOutput(true);
    try (var output = connection.getOutputStream()) {
      output.write(requestBody);
    }
    int statusCode = connection.getResponseCode();
    var responseStream = statusCode / 100 == 2
        ? connection.getInputStream() : connection.getErrorStream();
    String responseBody = responseStream == null ? ""
        : new String(responseStream.readAllBytes(), StandardCharsets.UTF_8);
    connection.disconnect();
    if (statusCode / 100 != 2) {
      throw new IOException("Model returned HTTP " + statusCode + ": " + responseBody);
    }
    Map<String, Object> body = Json.object(Json.parse(responseBody));
    List<Object> choices = Json.array(body.get("choices"));
    if (choices.isEmpty()) {
      throw new IOException("Model response has no choices");
    }
    Map<String, Object> choice = Json.object(choices.get(0));
    Map<String, Object> usage = body.get("usage") instanceof Map<?, ?>
        ? new LinkedHashMap<>(Json.object(body.get("usage"))) : new LinkedHashMap<>();
    for (String key : List.of("prompt_tokens", "total_tokens", "completion_tokens")) {
      if (usage.get(key) instanceof Number value) usage.put(key, value.longValue());
    }
    String responseModel = Json.string(body.get("model"));
    return new ModelOutput(Json.string(choice.get("text")),
        responseModel.isBlank() ? MODEL : responseModel,
        choice.get("finish_reason"), choice.get("stop_reason"), usage);
  }

  private static String extractJavaFile(String raw) {
    String value = stripWrappers(raw);
    int start = -1;
    for (String marker : List.of("package ", "import ", "@", "public ", "protected ",
        "private ", "class ", "interface ", "enum ", "record ")) {
      int candidate = value.indexOf(marker);
      if (candidate >= 0 && (start < 0 || candidate < start)) start = candidate;
    }
    if (start > 0) value = value.substring(start);
    value = value.replaceFirst("(?s)\\s*</s>.*$", "").strip();
    int finalBrace = value.lastIndexOf('}');
    if (finalBrace >= 0) value = value.substring(0, finalBrace + 1).strip();
    if (value.isBlank() || !value.contains("{") || !value.contains("}")) {
      throw new IllegalArgumentException("Model did not return a complete Java source file");
    }
    return value + "\n";
  }

  /**
   * Locate the generated node in prefix + model output. Neither the hidden suffix nor the
   * ground truth participates in extraction. javac parses only; no dependency resolution,
   * class output, annotation processing or repository build is performed here.
   */
  private static String extractTask2Completion(String raw, Map<String, Object> sample)
      throws IOException {
    String text = stripWrappers(raw);
    if (text.isBlank()) throw new IllegalArgumentException("Model returned no Java code");
    String context = Json.string(sample.get("context"));
    int marker = context.indexOf(MASK);
    if (marker < 0 || marker != context.lastIndexOf(MASK)) {
      throw new IllegalArgumentException("Expected exactly one completion marker");
    }
    String prefix = context.substring(0, marker);
    String source = prefix + text;
    int nodeStart = prefix.length() + leadingJavaTrivia(text);
    String kind = Json.string(sample.get("completion_kind"));
    String expectedKind = Json.string(sample.get("ast_node_kind"));
    if (kind.equals("function_body") || expectedKind.equals("FUNCTION_BODY")) expectedKind = "BLOCK";
    final String targetKind = expectedKind;
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) throw new IllegalStateException("Task-2 extraction requires a JDK");
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    JavaFileObject input = new SimpleJavaFileObject(
        URI.create("string:///Completion.java"), JavaFileObject.Kind.SOURCE) {
      @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
    };
    long[] ends = {-1, -1}; // Exact AST type first; syntactically equivalent category second.
    try (var files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
      JavacTask parser = (JavacTask) compiler.getTask(null, files, diagnostics,
          List.of("-proc:none", "-Xlint:none"), null, List.of(input));
      for (CompilationUnitTree unit : parser.parse()) {
        SourcePositions positions = Trees.instance(parser).getSourcePositions();
        new TreeScanner<Void, Void>() {
          @Override public Void scan(Tree node, Void unused) {
            if (node == null) return null;
            long start = positions.getStartPosition(unit, node);
            long end = positions.getEndPosition(unit, node);
            if (start == nodeStart && end > start && end <= source.length()
                && !containsErroneousTree(node)) {
              if (node.getKind().name().equals(targetKind)) {
                // A prefix-only model commonly continues a receiver call with another call
                // (e.g. stack.get().add(x)). Keep the first complete invocation in that
                // ambiguous chain; other expression types retain their outermost node.
                ends[0] = targetKind.equals("METHOD_INVOCATION") && ends[0] >= 0
                    ? Math.min(ends[0], end) : Math.max(ends[0], end);
              }
              boolean category = switch (kind) {
                case "expression" -> node instanceof ExpressionTree
                    || node.getKind() == Tree.Kind.ARRAY_TYPE
                    || node.getKind() == Tree.Kind.PARAMETERIZED_TYPE;
                case "statement" -> node instanceof StatementTree;
                case "block", "function_body" -> node.getKind() == Tree.Kind.BLOCK;
                default -> false;
              };
              if (category) ends[1] = Math.max(ends[1], end);
            }
            return super.scan(node, unused);
          }
        }.scan(unit, null);
      }
    }
    long selectedEnd = ends[0] >= 0 ? ends[0] : ends[1];
    if (selectedEnd < 0) {
      throw new IllegalArgumentException("No complete " + kind + " node at the generated boundary ("
          + targetKind + ")");
    }
    for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
      // Truncated enclosing code after the chosen node is expected with prefix-only input.
      // Syntax errors inside the retained node are not a reliable extraction boundary.
      if (diagnostic.getKind() == Diagnostic.Kind.ERROR
          && diagnostic.getPosition() >= nodeStart && diagnostic.getPosition() < selectedEnd) {
        throw new IllegalArgumentException("Invalid generated Java node: "
            + diagnostic.getMessage(Locale.ROOT));
      }
    }
    String completion = text.substring(0, (int) selectedEnd - prefix.length());
    if (kind.equals("block") || kind.equals("function_body")) {
      int tokenStart = leadingJavaTrivia(completion);
      if (tokenStart >= completion.length() || completion.charAt(tokenStart) != '{'
          || balancedBlockEnd(completion, tokenStart) != completion.length()) {
        throw new IllegalArgumentException("Generated block is incomplete");
      }
    }
    return completion;
  }

  private static boolean containsErroneousTree(Tree tree) {
    return Boolean.TRUE.equals(new TreeScanner<Boolean, Void>() {
      @Override public Boolean scan(Tree node, Void unused) {
        if (node == null) return false;
        return node.getKind() == Tree.Kind.ERRONEOUS || Boolean.TRUE.equals(super.scan(node, unused));
      }
      @Override public Boolean reduce(Boolean left, Boolean right) {
        return Boolean.TRUE.equals(left) || Boolean.TRUE.equals(right);
      }
    }.scan(tree, null));
  }

  private static int leadingJavaTrivia(String text) {
    int index = 0;
    while (index < text.length()) {
      if (Character.isWhitespace(text.charAt(index))) { index++; continue; }
      if (text.startsWith("//", index)) {
        while (index < text.length() && text.charAt(index) != '\r'
            && text.charAt(index) != '\n') index++;
      } else if (text.startsWith("/*", index)) {
        int end = text.indexOf("*/", index + 2);
        if (end < 0) return text.length();
        index = end + 2;
      } else break;
    }
    return index;
  }

  /** Lexical completeness check for blocks, including Java text blocks and comments. */
  private static int balancedBlockEnd(String text, int start) {
    int depth = 0;
    for (int index = start; index < text.length(); index++) {
      if (text.startsWith("//", index) || text.startsWith("/*", index)) {
        int skipped = leadingJavaTrivia(text.substring(index));
        index += skipped - 1;
        continue;
      }
      char ch = text.charAt(index);
      if (ch == '"' || ch == '\'') {
        boolean textBlock = text.startsWith("\"\"\"", index);
        String delimiter = textBlock ? "\"\"\"" : Character.toString(ch);
        index += delimiter.length();
        boolean closed = false;
        while (index < text.length()) {
          if (text.charAt(index) == '\\') { index += 2; continue; }
          if (text.startsWith(delimiter, index)) {
            index += delimiter.length() - 1;
            closed = true;
            break;
          }
          index++;
        }
        if (!closed) return -1;
      } else if (ch == '{') depth++;
      else if (ch == '}' && --depth == 0) return index + 1;
    }
    return -1;
  }

  private static String stripWrappers(String text) {
    String value = text.trim();
    value = value.replaceFirst("(?s)^<think>.*?</think>\\s*", "");
    Matcher fenced = Pattern.compile("(?is)```(?:java)?\\s*\\R(.*?)```").matcher(value);
    if (fenced.find()) {
      value = fenced.group(1);
    }
    return value.strip();
  }

  private static Result evaluate(
      Path checkout,
      Map<String, Object> sample,
      String completion,
      String raw
  ) throws Exception {
    String id = Json.string(sample.get("id"));
    Path sourcePath = checkout.resolve(Json.string(sample.get("source_path"))).normalize();
    if (!sourcePath.startsWith(checkout) || !Files.isRegularFile(sourcePath)) {
      throw new IOException("Invalid source path: " + sourcePath);
    }
    byte[] originalBytes = Files.readAllBytes(sourcePath);
    String source = new String(originalBytes, StandardCharsets.UTF_8);
    String expectedHash = Json.string(sample.get("source_sha256"));
    if (!sha256(source).equals(expectedHash)) {
      throw new IOException("Source SHA-256 mismatch before sample " + id + ": " + sourcePath);
    }
    int start = Json.integer(sample.get("start_offset"));
    int end = Json.integer(sample.get("end_offset"));
    if (start < 0 || end < start || end > source.length()) {
      throw new IOException("Invalid source span " + start + ".." + end);
    }
    String maskedContent = Json.string(sample.get("context"));
    String expectedContext = source.substring(0, start) + MASK + source.substring(end);
    if (!maskedContent.equals(expectedContext)) {
      throw new IOException("Sample context/span does not match original source for " + id);
    }

    String mutated = source.substring(0, start) + completion + source.substring(end);
    try {
      Files.writeString(sourcePath, mutated, StandardCharsets.UTF_8);
      String written = Files.readString(sourcePath, StandardCharsets.UTF_8);
      if (!written.equals(mutated)) {
        throw new IOException("Completion write verification failed for " + id);
      }

      List<TestRun> testRuns = new ArrayList<>();
      Set<String> commands = new LinkedHashSet<>();
      for (Object value : Json.array(sample.get("tests"))) {
        Map<String, Object> test = Json.object(value);
        String command = Json.string(test.get("test_command"));
        String workdir = Json.string(test.get("test_workdir"));
        String key = workdir + "\u0000" + command;
        if (!commands.add(key)) continue;
        cleanTestReports(checkout);
        Path cwd = resolveWorkdir(checkout, workdir);
        testRuns.add(runTest(command, cwd));
      }
      boolean infrastructureError = testRuns.stream().anyMatch(TestRun::infrastructureError);
      boolean passed = !testRuns.isEmpty() && testRuns.stream().allMatch(TestRun::passed);
      return new Result(id, Json.string(sample.get("repo")),
          Json.string(sample.get("repo_tag")), Json.string(sample.get("task")),
          infrastructureError ? "error" : (passed ? "passed" : "failed"),
          completion, raw, sample, testRuns,
          infrastructureError ? "Test infrastructure failed; see test-output.txt" : "");
    } finally {
      Files.write(sourcePath, originalBytes);
      String restored = Files.readString(sourcePath, StandardCharsets.UTF_8);
      if (!sha256(restored).equals(expectedHash)) {
        throw new IOException("Source restoration verification failed after sample " + id
            + ": " + sourcePath);
      }
    }
  }

  private static Result evaluateTask3(
      Path checkout,
      Map<String, Object> sample,
      List<Map<String, Object>> generatedFiles
  ) throws Exception {
    String id = Json.string(sample.get("id"));
    Map<String, Map<String, Object>> generatedByPath = new LinkedHashMap<>();
    for (Map<String, Object> generated : generatedFiles) {
      generatedByPath.put(Json.string(generated.get("source_path")), generated);
    }
    Map<Path, byte[]> originalBytes = new LinkedHashMap<>();
    Map<Path, String> originalHashes = new LinkedHashMap<>();
    for (Object targetValue : Json.array(sample.get("target_files"))) {
      Map<String, Object> target = Json.object(targetValue);
      String relative = Json.string(target.get("source_path"));
      Path sourcePath = checkout.resolve(relative).normalize();
      if (!sourcePath.startsWith(checkout) || !Files.isRegularFile(sourcePath)) {
        throw new IOException("Invalid task-3 source path: " + sourcePath);
      }
      String expectedHash = Json.string(target.get("source_sha256"));
      byte[] bytes = Files.readAllBytes(sourcePath);
      if (!sha256(new String(bytes, StandardCharsets.UTF_8)).equals(expectedHash)) {
        throw new IOException("Task-3 source SHA-256 mismatch before sample " + id + ": " + relative);
      }
      if (!generatedByPath.containsKey(relative)) {
        throw new IOException("No generated file for task-3 target: " + relative);
      }
      originalBytes.put(sourcePath, bytes);
      originalHashes.put(sourcePath, expectedHash);
    }
    if (generatedByPath.size() != originalBytes.size()) {
      throw new IOException("Generated task-3 file set differs from target file set");
    }

    String completionJson = Json.stringify(generatedFiles);
    try {
      for (Map.Entry<Path, byte[]> entry : originalBytes.entrySet()) {
        String relative = checkout.relativize(entry.getKey()).toString().replace('\\', '/');
        String completion = Json.string(generatedByPath.get(relative).get("completion"));
        Files.writeString(entry.getKey(), completion, StandardCharsets.UTF_8);
        if (!Files.readString(entry.getKey(), StandardCharsets.UTF_8).equals(completion)) {
          throw new IOException("Task-3 file write verification failed: " + relative);
        }
      }

      List<TestRun> runs = new ArrayList<>();
      Set<String> commands = new LinkedHashSet<>();
      List<Object> tests = Json.array(sample.get("tests"));
      if (!tests.isEmpty()) {
        for (Object value : tests) {
          Map<String, Object> test = Json.object(value);
          String command = Json.string(test.get("test_command"));
          String workdir = Json.string(test.get("test_workdir"));
          if (!commands.add(workdir + "\u0000" + command)) continue;
          cleanTestReports(checkout);
          runs.add(runTest(command, resolveWorkdir(checkout, workdir)));
        }
      } else {
        for (Object value : Json.array(sample.get("build_commands"))) {
          Map<String, Object> build = Json.object(value);
          String command = Json.string(build.get("command"));
          String workdir = Json.string(build.get("workdir"));
          if (!commands.add(workdir + "\u0000" + command)) continue;
          runs.add(runTest(command, resolveWorkdir(checkout, workdir)));
        }
      }
      if (runs.isEmpty()) {
        throw new IOException("Task-3 sample has neither tests nor compile commands");
      }
      boolean infrastructureError = runs.stream().anyMatch(TestRun::infrastructureError);
      boolean passed = runs.stream().allMatch(TestRun::passed);
      return new Result(id, Json.string(sample.get("repo")),
          Json.string(sample.get("repo_tag")), "difficulty3",
          infrastructureError ? "error" : (passed ? "passed" : "failed"),
          completionJson, completionJson, sample, runs,
          infrastructureError ? "Build/test infrastructure failed; see test-output.txt" : "");
    } finally {
      for (Map.Entry<Path, byte[]> entry : originalBytes.entrySet()) {
        Files.write(entry.getKey(), entry.getValue());
      }
      for (Map.Entry<Path, String> entry : originalHashes.entrySet()) {
        String restored = Files.readString(entry.getKey(), StandardCharsets.UTF_8);
        if (!sha256(restored).equals(entry.getValue())) {
          throw new IOException("Task-3 source restoration verification failed after sample "
              + id + ": " + entry.getKey());
        }
      }
    }
  }

  private static Path resolveWorkdir(Path checkout, String workdir) throws IOException {
    Path cwd = workdir.equals(".") ? checkout : checkout.resolve(workdir).normalize();
    if (!cwd.startsWith(checkout) || !Files.isDirectory(cwd)) {
      throw new IOException("Invalid test workdir: " + workdir);
    }
    return cwd;
  }

  private static void verifyOriginalSource(Path checkout, Map<String, Object> sample)
      throws IOException {
    Path sourcePath = checkout.resolve(Json.string(sample.get("source_path"))).normalize();
    if (!sourcePath.startsWith(checkout) || !Files.isRegularFile(sourcePath)) {
      throw new IOException("Original source is missing: " + sourcePath);
    }
    String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
    if (!sha256(source).equals(Json.string(sample.get("source_sha256")))) {
      throw new IOException("Original source hash was not restored: " + sourcePath);
    }
  }

  private static void verifyTask3OriginalSources(Path checkout, Map<String, Object> sample)
      throws IOException {
    for (Object targetValue : Json.array(sample.get("target_files"))) {
      Map<String, Object> target = Json.object(targetValue);
      Path sourcePath = checkout.resolve(Json.string(target.get("source_path"))).normalize();
      if (!sourcePath.startsWith(checkout) || !Files.isRegularFile(sourcePath)) {
        throw new IOException("Original task-3 source is missing: " + sourcePath);
      }
      String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
      if (!sha256(source).equals(Json.string(target.get("source_sha256")))) {
        throw new IOException("Original task-3 source hash was not restored: " + sourcePath);
      }
    }
  }

  private static void copyRepository(Path source, Path destination) throws IOException {
    Files.walkFileTree(source, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
        if (!dir.equals(source) && shouldExcludeDirectory(source, dir)) {
          return FileVisitResult.SKIP_SUBTREE;
        }
        Path target = destination.resolve(source.relativize(dir));
        Files.createDirectories(target);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Files.copy(file, destination.resolve(source.relativize(file)),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        return FileVisitResult.CONTINUE;
      }
    });
    Path gradlew = destination.resolve("gradlew");
    if (Files.exists(gradlew)) {
      try {
        Set<PosixFilePermission> permissions = new HashSet<>(Files.getPosixFilePermissions(gradlew));
        permissions.add(PosixFilePermission.OWNER_EXECUTE);
        Files.setPosixFilePermissions(gradlew, permissions);
      } catch (UnsupportedOperationException ignored) {
        // Commands use bash ./gradlew, so execute permission is not required.
      }
    }
  }

  private static boolean shouldExcludeDirectory(Path source, Path directory) {
    String name = directory.getFileName().toString();
    if (!COPY_EXCLUDES.contains(name)) return false;
    // A source package can legitimately be named "build" (for example
    // org.apache.commons.io.build); never discard directories below src/.
    for (Path part : source.relativize(directory)) {
      if (part.toString().equals("src")) return false;
    }
    // Some Maven reactors use a real module named build. Keep it when it has
    // its own POM; ordinary generated build output has no POM and is excluded.
    if (name.equals("build") && Files.isRegularFile(directory.resolve("pom.xml"))) {
      return false;
    }
    return true;
  }

  private static void cleanTestReports(Path checkout) throws IOException {
    List<Path> targets = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(checkout)) {
      paths.filter(Files::isDirectory).forEach(path -> {
        String name = path.getFileName().toString();
        if (REPORT_DIRECTORIES.contains(name)
            || (name.equals("reports") && path.getParent() != null
                && path.getParent().getFileName().toString().equals("build"))) {
          targets.add(path);
        }
      });
    }
    targets.sort(Comparator.comparingInt(Path::getNameCount).reversed());
    for (Path target : targets) deleteRecursively(target);
  }

  private static TestRun runTest(String command, Path cwd) throws Exception {
    Instant started = Instant.now();
    Path outputFile = Files.createTempFile("java-evaluator-command-", ".log");
    Process process = null;
    try {
      ProcessBuilder processBuilder = new ProcessBuilder("bash", "-c", command)
          .directory(cwd.toFile())
          .redirectErrorStream(true)
          .redirectOutput(outputFile.toFile());
      JavaBuildTool.configureJavaEnvironment(processBuilder, command, cwd);
      String existingGradleOpts = processBuilder.environment().getOrDefault("GRADLE_OPTS", "").trim();
      processBuilder.environment().put("GRADLE_OPTS",
          (existingGradleOpts + " -Dorg.gradle.workers.max="
              + MAX_BUILD_PROCESSES_PER_WORKER).trim());
      processBuilder.environment().put("CMAKE_BUILD_PARALLEL_LEVEL",
          Integer.toString(MAX_BUILD_PROCESSES_PER_WORKER));
      processBuilder.environment().put("MAKEFLAGS",
          "-j" + MAX_BUILD_PROCESSES_PER_WORKER);
      String javaHome = processBuilder.environment().get("JAVA_HOME");
      if (javaHome != null && !javaHome.isBlank()) {
        processBuilder.environment().put("JAVA_HOME", javaHome);
        processBuilder.environment().put("PATH",
            Path.of(javaHome, "bin") + ":" + processBuilder.environment().getOrDefault("PATH", ""));
      }
      int javaMajor = javaHome == null ? 0 : javaMajor(javaHome);
      String existingMavenOpts = processBuilder.environment()
          .getOrDefault("MAVEN_OPTS", "").trim();
      existingMavenOpts = (existingMavenOpts
          + " -Dmaven.artifact.threads=" + MAX_BUILD_PROCESSES_PER_WORKER
          + " -DforkCount=1 -DreuseForks=true").trim();
      if (javaMajor >= 9) {
        existingMavenOpts = (existingMavenOpts
            + " --add-opens=java.base/java.lang=ALL-UNNAMED").trim();
      }
      processBuilder.environment().put("MAVEN_OPTS", existingMavenOpts);
      process = processBuilder.start();
      boolean finished = process.waitFor(JavaBuildTool.TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
      if (!finished) {
        terminateProcessTree(process);
      }
      String output = Files.readString(outputFile, StandardCharsets.UTF_8);
      int exitCode = finished ? process.exitValue() : -1;
      return new TestRun(command, cwd.toString(), exitCode,
          Duration.between(started, Instant.now()).toMillis() / 1000.0,
          !finished, tail(output, 20000));
    } finally {
      if (process != null && process.isAlive()) terminateProcessTree(process);
      Files.deleteIfExists(outputFile);
    }
  }

  private static int javaMajor(String javaHome) {
    try {
      Process process = new ProcessBuilder(Path.of(javaHome, "bin/java").toString(), "-version")
          .redirectErrorStream(true).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      process.waitFor();
      Matcher matcher = Pattern.compile("version \\\"(?:1\\.)?(\\d+)").matcher(output);
      return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    } catch (Exception ignored) {
      return 0;
    }
  }

  private static void terminateProcessTree(Process process) throws InterruptedException {
    List<ProcessHandle> descendants = process.descendants().toList();
    for (ProcessHandle handle : descendants.reversed()) {
      if (handle.isAlive()) handle.destroyForcibly();
    }
    if (process.isAlive()) process.destroyForcibly();
    process.waitFor(10, TimeUnit.SECONDS);
  }

  private static void writeReports(
      Path runDir, List<Result> results, Map<String, String> repositoryDomains) throws IOException {
    Map<String, Object> countsByTask = new LinkedHashMap<>();
    for (String task : List.of("difficulty1", "difficulty2", "difficulty3")) {
      if (task.equals("difficulty1")) {
        countsByTask.put(task, task1CharacterCounts(results));
        continue;
      }
      countsByTask.put(task, resultCounts(
          results.stream().filter(result -> result.task().equals(task)).toList()));
    }
    Map<String, Object> countsBySubtask = new LinkedHashMap<>();
    Map<String, Object> countsBySubtaskDomain = new LinkedHashMap<>();
    for (String subtask : SUBTASK_LABELS.keySet()) {
      List<Result> subtaskResults = resultsForSubtask(results, subtask);
      Map<String, Object> subtaskCounts = isTask1Subtask(subtask)
          ? new LinkedHashMap<>(Map.of("total", subtaskResults.size())) : resultCounts(subtaskResults);
      if (isTask1Subtask(subtask)) {
        subtaskCounts.put("character_metrics", task1CharacterCounts(subtaskResults));
      }
      countsBySubtask.put(subtask, subtaskCounts);
      Map<String, Object> domains = new LinkedHashMap<>();
      for (String domain : REPOSITORY_DOMAINS) {
        List<Result> domainResults = resultsForDomain(
            subtaskResults, repositoryDomains, domain);
        Map<String, Object> domainCounts = isTask1Subtask(subtask)
            ? new LinkedHashMap<>(Map.of("total", domainResults.size())) : resultCounts(domainResults);
        if (isTask1Subtask(subtask)) {
          domainCounts.put("character_metrics", task1CharacterCounts(domainResults));
        }
        domains.put(domain, domainCounts);
      }
      countsBySubtaskDomain.put(subtask, domains);
    }
    List<Object> resultJson = results.stream().map(Result::detailJson).map(value -> (Object) value).toList();
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("created_at", Instant.now().toString());
    report.put("model", MODEL);
    report.put("base_url", BASE_URL);
    report.put("total", results.size());
    report.put("by_task", countsByTask);
    report.put("by_subtask", countsBySubtask);
    report.put("by_subtask_domain", countsBySubtaskDomain);
    report.put("results", resultJson);
    writeStringAtomically(runDir.resolve("report.json"), Json.stringify(report) + "\n");

    Path failedRoot = runDir.resolve("failed-samples");
    for (Result result : results) {
      boolean task1 = result.task().equals("difficulty1");
      if (task1 ? result.error().isBlank() : result.status().equals("passed")) continue;
      Path directory = (task1 ? runDir.resolve("generation-errors") : failedRoot)
          .resolve(safeName(result.id()));
      Files.createDirectories(directory);
      writeStringAtomically(directory.resolve("sample.json"),
          Json.stringify(result.sample()) + "\n");
      writeStringAtomically(directory.resolve("ground-truth.txt"),
          groundTruthText(result.sample()));
      Object prompt = savedPrompt(result.sample());
      writeStringAtomically(directory.resolve("prompt.txt"),
          formatSavedValue("prompt", prompt));
      writeStringAtomically(directory.resolve("model-output.txt"),
          formatSavedValue("model_output", normalizedOutput(result, false)));
      writeStringAtomically(directory.resolve("raw-model-output.txt"),
          formatSavedValue("raw_model_output", normalizedOutput(result, true)));
      writeStringAtomically(directory.resolve(task1 ? "error.txt" : "test-output.txt"),
          task1 ? result.error() + "\n" : result.testOutput());
    }
    Files.deleteIfExists(runDir.resolve("failed-samples.json"));

    StringBuilder markdown = new StringBuilder("# Java Evaluation Report\n\n")
        .append("| Task | Domain | Metric | Rate | Matched characters | Total characters | Unmatched characters | Passed | Scored samples | Failed | Errors | Pending |\n")
        .append("| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
    for (String subtask : SUBTASK_LABELS.keySet()) {
      List<Result> subtaskResults = resultsForSubtask(results, subtask);
      for (String domain : REPOSITORY_DOMAINS) {
        appendSummaryRow(markdown, SUBTASK_LABELS.get(subtask), domain,
            resultsForDomain(subtaskResults, repositoryDomains, domain), subtask);
      }
      appendSummaryRow(markdown, SUBTASK_LABELS.get(subtask), "All domains",
          subtaskResults, subtask);
    }

    for (String subtask : SUBTASK_LABELS.keySet()) {
      List<Result> taskResults = resultsForSubtask(results, subtask);
      int passedTests = taskResults.stream().mapToInt(Result::passedTestCount).sum();
      int totalTests = taskResults.stream().mapToInt(Result::totalTestCount).sum();
      long taskPassed = taskResults.stream()
          .filter(result -> result.status().equals("passed")).count();
      long taskFailed = taskResults.stream()
          .filter(result -> result.status().equals("failed")).count();
      long taskErrors = taskResults.stream()
          .filter(result -> result.status().equals("error")).count();

      if (isTask1Subtask(subtask)) {
        Map<String, Object> characters = task1CharacterCounts(taskResults);
        markdown.append("\n## ").append(SUBTASK_LABELS.get(subtask)).append("\n\n")
            .append("| Sample ID | CharacterAccuracy |\n")
            .append("| --- | ---: |\n");
        for (Result result : taskResults) {
          Map<String, Object> metrics = result.characterMetrics();
          markdown.append("| ").append(markdownCell(result.id())).append(" | ")
              .append(characterAccuracyText(metrics)).append(" |\n");
        }
        markdown.append("| **Summary** | **")
            .append(characterAccuracyText(characters)).append("** |\n");
        continue;
      }
      if (taskResults.isEmpty()) continue;

      markdown.append("\n## ").append(SUBTASK_LABELS.get(subtask)).append("\n\n")
          .append("| Sample ID | Tests passed | Status |\n")
          .append("| --- | ---: | --- |\n");
      for (Result result : taskResults) {
        markdown.append("| ").append(markdownCell(result.id())).append(" | ")
            .append(result.reused() ? "cached" : result.passedTestCount() + "/" + result.totalTestCount())
            .append(" | ").append(displayStatus(result.status())).append(" |\n");
      }
      markdown.append("| **Summary (")
          .append(taskResults.size()).append(" samples)** | **")
          .append(passedTests).append("/").append(totalTests).append("** | ")
          .append("pass: ").append(taskPassed)
          .append(", fail: ").append(taskFailed)
          .append(", error: ").append(taskErrors).append(" |\n");
    }
    markdown.append("\n## Terminal output\n\n");
    String terminal = consoleOutput();
    // Choose a fence longer than any backtick sequence present in model/error output.
    String fence = "```";
    while (terminal.contains(fence)) fence += "`";
    markdown.append(fence).append("text\n").append(terminal);
    if (!terminal.endsWith("\n")) markdown.append('\n');
    markdown.append(fence).append('\n');
    writeStringAtomically(runDir.resolve("report.md"), markdown.toString());
  }

  private static Map<String, String> loadRepositoryDomains(Path catalogFile) throws IOException {
    if (!Files.isRegularFile(catalogFile)) {
      throw new IOException("Repository catalog does not exist: " + catalogFile);
    }
    Map<String, String> repositoryDomains = new LinkedHashMap<>();
    for (Object value : Json.array(Json.parse(Files.readString(catalogFile)))) {
      Map<String, Object> item = Json.object(value);
      String repository = Json.string(item.get("repository_full_name"));
      String domain = Json.string(item.get("domain"));
      if (repository.isBlank() || !REPOSITORY_DOMAINS.contains(domain)) {
        throw new IOException("Invalid repository catalog entry: " + Json.stringify(item));
      }
      String previous = repositoryDomains.putIfAbsent(repository, domain);
      if (previous != null && !previous.equals(domain)) {
        throw new IOException("Repository has conflicting domains in catalog: " + repository);
      }
    }
    return Map.copyOf(repositoryDomains);
  }

  private static List<Result> resultsForDomain(
      List<Result> results, Map<String, String> repositoryDomains, String domain) throws IOException {
    List<Result> domainResults = new ArrayList<>();
    for (Result result : results) {
      String resultDomain = repositoryDomains.get(result.repo());
      if (resultDomain == null) {
        throw new IOException("Repository is missing from repository_catalog.json: " + result.repo());
      }
      if (resultDomain.equals(domain)) domainResults.add(result);
    }
    return List.copyOf(domainResults);
  }

  private static List<Result> testBasedResults(List<Result> results) {
    return results.stream().filter(result -> !result.task().equals("difficulty1")).toList();
  }

  private static Map<String, Object> resultCounts(List<Result> results) {
    results = testBasedResults(results);
    long passed = results.stream().filter(result -> result.status().equals("passed")).count();
    long failed = results.stream().filter(result -> result.status().equals("failed")).count();
    long errors = results.stream().filter(result -> result.status().equals("error")).count();
    long pending = results.stream().filter(result -> result.status().equals("pending")).count();
    long scored = passed + failed + errors;
    Map<String, Object> counts = new LinkedHashMap<>();
    counts.put("total", scored);
    counts.put("passed", passed);
    counts.put("failed", failed);
    counts.put("errors", errors);
    counts.put("pending", pending);
    double samplePassRate = scored == 0 ? 0.0 : (double) passed / scored;
    counts.put("sample_pass_rate", samplePassRate);
    counts.put("pass_rate", samplePassRate);
    return counts;
  }

  private static Map<String, Object> task1CharacterCounts(List<Result> results) {
    long totalCharacters = 0;
    long matchedCharacters = 0;
    long samples = 0;
    for (Result result : results) {
      if (!result.task().equals("difficulty1")) continue;
      Map<String, Object> metrics = result.characterMetrics();
      totalCharacters += ((Number) metrics.get("total_characters")).longValue();
      matchedCharacters += ((Number) metrics.get("matched_characters")).longValue();
      samples++;
    }
    Map<String, Object> counts = new LinkedHashMap<>();
    counts.put("scored_samples", samples);
    counts.put("total_characters", totalCharacters);
    counts.put("matched_characters", matchedCharacters);
    counts.put("unmatched_characters", totalCharacters - matchedCharacters);
    counts.put("character_accuracy",
        totalCharacters == 0 ? 0.0 : (double) matchedCharacters / totalCharacters);
    return counts;
  }

  private static boolean isTask1Subtask(String subtask) {
    return subtask.equals("difficulty1_lines") || subtask.equals("difficulty1_api");
  }

  private static Map<String, String> subtaskLabels() {
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put("difficulty1_lines", "snippet");
    labels.put("difficulty1_api", "api");
    labels.put("difficulty2_ast", "completion");
    labels.put("difficulty2_function_body", "generation");
    labels.put("difficulty3_files", "synthesis");
    return java.util.Collections.unmodifiableMap(labels);
  }

  private static String subtask(String task, Map<String, Object> sample) {
    String completionKind = Json.string(sample.get("completion_kind"));
    String astNodeKind = Json.string(sample.get("ast_node_kind"));
    return switch (task) {
      case "difficulty1" -> completionKind.equals("lines") || astNodeKind.equals("LINE_RANGE")
          ? "difficulty1_lines" : "difficulty1_api";
      case "difficulty2" -> completionKind.equals("function_body") || astNodeKind.equals("FUNCTION_BODY")
          ? "difficulty2_function_body" : "difficulty2_ast";
      case "difficulty3" -> "difficulty3_files";
      default -> throw new IllegalArgumentException("Unknown task: " + task);
    };
  }

  private static List<Result> resultsForSubtask(List<Result> results, String subtask) {
    return results.stream()
        .filter(result -> subtask(result.task(), result.sample()).equals(subtask))
        .toList();
  }

  private static String formatConsoleSummary(
      List<Result> results, Map<String, String> repositoryDomains) throws IOException {
    StringBuilder summary = new StringBuilder();
    for (String subtask : SUBTASK_LABELS.keySet()) {
      List<Result> subtaskResults = resultsForSubtask(results, subtask);
      for (String domain : REPOSITORY_DOMAINS) {
        List<Result> domainResults = resultsForDomain(subtaskResults, repositoryDomains, domain);
        if (isTask1Subtask(subtask)) {
          summary.append(formatCharacterRow(
              SUBTASK_LABELS.get(subtask), domain, task1CharacterCounts(domainResults)));
        } else {
          summary.append(formatSampleRateRow(
              SUBTASK_LABELS.get(subtask), domain, resultCounts(domainResults)));
        }
      }
      if (isTask1Subtask(subtask)) {
        summary.append(formatCharacterRow(
            SUBTASK_LABELS.get(subtask), "All domains", task1CharacterCounts(subtaskResults)));
      } else {
        summary.append(formatSampleRateRow(
            SUBTASK_LABELS.get(subtask), "All domains", resultCounts(subtaskResults)));
      }
    }
    return summary.toString();
  }

  private static String formatSampleRateRow(
      String task, String domain, Map<String, Object> counts) {
    return String.format(Locale.ROOT, "%s / %s: SamplePassRate=%s\n",
        task, domain, percentageText(
            ((Number) counts.get("passed")).longValue(),
            ((Number) counts.get("total")).longValue()));
  }

  private static String formatCharacterRow(
      String task, String domain, Map<String, Object> counts) {
    return String.format(Locale.ROOT, "%s / %s: CharacterAccuracy=%s\n",
        task, domain, characterAccuracyText(counts));
  }

  private static String characterAccuracyText(Map<String, Object> counts) {
    long totalCharacters = ((Number) counts.get("total_characters")).longValue();
    if (totalCharacters == 0) return "N/A";
    return percentageText(
        ((Number) counts.get("matched_characters")).longValue(), totalCharacters);
  }

  private static String percentageText(long numerator, long denominator) {
    if (denominator == 0) return "0.00%";
    return BigDecimal.valueOf(numerator)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP)
        .toPlainString() + "%";
  }

  private static void appendSummaryRow(
      StringBuilder markdown,
      String task,
      String domain,
      List<Result> results,
      String subtaskKey
  ) {
    if (isTask1Subtask(subtaskKey)) {
      Map<String, Object> counts = task1CharacterCounts(results);
      long totalCharacters = ((Number) counts.get("total_characters")).longValue();
      long matchedCharacters = ((Number) counts.get("matched_characters")).longValue();
      markdown.append(String.format(Locale.ROOT,
          "| %s | %s | CharacterAccuracy | %s | %d | %d | %d |  |  |  |  |  |\n",
          task, domain, characterAccuracyText(counts), matchedCharacters, totalCharacters,
          totalCharacters - matchedCharacters));
      return;
    }
    Map<String, Object> counts = resultCounts(results);
    markdown.append(String.format(Locale.ROOT,
        "| %s | %s | SamplePassRate | %s |  |  |  | %d | %d | %d | %d | %d |\n",
        task, domain, percentageText(
            ((Number) counts.get("passed")).longValue(),
            ((Number) counts.get("total")).longValue()),
        counts.get("passed"), counts.get("total"), counts.get("failed"),
        counts.get("errors"), counts.get("pending")));
  }

  private static void captureConsoleOutput() {
    synchronized (CONSOLE_OUTPUT) {
      CONSOLE_OUTPUT.reset();
    }
    System.setOut(teeConsole(System.out));
    System.setErr(teeConsole(System.err));
  }

  private static PrintStream teeConsole(PrintStream original) {
    return new PrintStream(new OutputStream() {
      @Override
      public void write(int value) {
        synchronized (CONSOLE_OUTPUT) {
          original.write(value);
          CONSOLE_OUTPUT.write(value);
        }
      }

      @Override
      public void write(byte[] bytes, int offset, int length) {
        synchronized (CONSOLE_OUTPUT) {
          original.write(bytes, offset, length);
          CONSOLE_OUTPUT.write(bytes, offset, length);
        }
      }

      @Override
      public void flush() {
        original.flush();
      }
    }, true, StandardCharsets.UTF_8);
  }

  private static String consoleOutput() {
    synchronized (CONSOLE_OUTPUT) {
      return CONSOLE_OUTPUT.toString(StandardCharsets.UTF_8);
    }
  }

  private static String displayStatus(String status) {
    return switch (status) {
      case "passed" -> "pass";
      case "failed" -> "fail";
      default -> "error";
    };
  }

  private static String markdownCell(String value) {
    return value.replace("\\", "\\\\")
        .replace("|", "\\|")
        .replace("\r", " ")
        .replace("\n", " ");
  }

  private static int plannedTestCount(Map<String, Object> sample, String task) {
    Object tests = sample.get("tests");
    List<Object> planned = tests instanceof List<?> ? Json.array(tests) : List.of();
    String commandKey = "test_command";
    String workdirKey = "test_workdir";
    if (planned.isEmpty() && task.equals("difficulty3")) {
      Object buildCommands = sample.get("build_commands");
      planned = buildCommands instanceof List<?> ? Json.array(buildCommands) : List.of();
      commandKey = "command";
      workdirKey = "workdir";
    }
    Set<String> commands = new LinkedHashSet<>();
    for (Object value : planned) {
      Map<String, Object> command = Json.object(value);
      commands.add(Json.string(command.get(workdirKey)) + "\u0000"
          + Json.string(command.get(commandKey)));
    }
    return commands.size();
  }

  private static void writeStringAtomically(Path destination, String content) throws IOException {
    Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
    Files.writeString(temporary, content, StandardCharsets.UTF_8);
    try {
      Files.move(temporary, destination,
          StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException exception) {
      Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void cleanupPrecompileLogs(Path runDir) throws IOException {
    if (!Files.isDirectory(runDir)) return;
    List<Path> logs;
    try (Stream<Path> paths = Files.list(runDir)) {
      logs = paths.filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().startsWith("precompile-"))
          .filter(path -> path.getFileName().toString().endsWith(".txt"))
          .toList();
    }
    for (Path log : logs) Files.deleteIfExists(log);
  }

  private static String safeName(String value) {
    return value.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private static Object savedPrompt(Map<String, Object> sample) {
    try {
      if (Json.string(sample.get("task")).equals("difficulty3")) {
        List<String> prompts = new ArrayList<>();
        for (Object value : Json.array(sample.get("target_files"))) {
          Map<String, Object> target = Json.object(value);
          String sourcePath = Json.string(target.get("source_path"));
          prompts.add(buildTask3Prompt(sample, sourcePath));
        }
        return prompts.size() == 1 ? prompts.get(0) : prompts;
      }
      PromptParts parts = splitContext(sample);
      if (isTask1Lines(sample)) {
        List<String> prompts = new ArrayList<>();
        StringBuilder prefix = new StringBuilder(parts.prefix());
        for (CompletionLine line : completionLines(Json.string(sample.get("ground_truth")))) {
          prompts.add(buildPrompt(prefix.toString(), "", Json.string(sample.get("source_path"))));
          prefix.append(line.text()).append(line.ending());
        }
        return prompts;
      }
      return buildPrompt(parts.prefix(), parts.suffix(),
          Json.string(sample.get("source_path")));
    } catch (RuntimeException exception) {
      return "Prompt unavailable: " + exception.getClass().getSimpleName()
          + ": " + exception.getMessage();
    }
  }

  private static Object normalizedOutput(Result result, boolean raw) {
    String value = raw ? result.raw() : result.completion();
    if (!result.task().equals("difficulty3") || value.isBlank()) return value;
    try {
      List<String> outputs = new ArrayList<>();
      String field = raw ? "raw_output" : "completion";
      for (Object item : Json.array(Json.parse(value))) {
        outputs.add(Json.string(Json.object(item).get(field)));
      }
      return outputs.size() == 1 ? outputs.get(0) : outputs;
    } catch (RuntimeException exception) {
      return value;
    }
  }

  private static String formatSavedValue(String name, Object content) {
    StringBuilder value = new StringBuilder();
    appendReadableSections(value, name, content);
    return value.toString();
  }

  private static void appendReadableSections(
      StringBuilder destination, String name, Object content) {
    if (content instanceof List<?> items) {
      if (items.isEmpty()) {
        destination.append("===== ").append(name).append(" =====\n\n");
      } else {
        for (int index = 0; index < items.size(); index++) {
          destination.append("===== ").append(name).append(' ')
              .append(index + 1).append(" =====\n")
              .append(Json.string(items.get(index))).append("\n\n");
        }
      }
    } else {
      destination.append("===== ").append(name).append(" =====\n")
          .append(Json.string(content)).append("\n\n");
    }
  }

  private static String groundTruthText(Map<String, Object> sample) {
    String single = Json.string(sample.get("ground_truth"));
    if (!single.isBlank()) return single;
    Object targetFiles = sample.get("target_files");
    if (!(targetFiles instanceof List<?> files)) return "";
    StringBuilder output = new StringBuilder();
    for (Object value : files) {
      Map<String, Object> target = Json.object(value);
      output.append("// ===== ").append(Json.string(target.get("source_path")))
          .append(" =====\n").append(Json.string(target.get("ground_truth"))).append("\n\n");
    }
    return output.toString();
  }

  private static String tail(String value, int limit) {
    return value.length() <= limit ? value : value.substring(value.length() - limit);
  }

  private static String sha256(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder();
      for (byte item : digest) result.append(String.format("%02x", item));
      return result.toString();
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static void deleteRecursively(Path path) throws IOException {
    if (path == null || !Files.exists(path)) return;
    Files.walkFileTree(path, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Files.deleteIfExists(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path dir, IOException exception) throws IOException {
        if (exception != null) throw exception;
        Files.deleteIfExists(dir);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  private record Arguments(
      Path root,
      int maxSamplesPerTask,
      Set<Integer> tasks,
      List<Path> predictionsFiles,
      boolean generateOnly,
      boolean skip,
      boolean noPrintResults
  ) {
    static Arguments parse(String[] args) {
      Path root = Path.of(".");
      int max = Integer.MAX_VALUE;
      Set<Integer> tasks = new java.util.LinkedHashSet<>(List.of(1, 2, 3));
      List<Path> predictionsFiles = new ArrayList<>();
      boolean generateOnly = false;
      boolean skip = false;
      boolean noPrintResults = false;
      for (int index = 0; index < args.length; index++) {
        if (args[index].equals("--root") && index + 1 < args.length) {
          root = Path.of(args[++index]);
        } else if (args[index].equals("--max-samples") && index + 1 < args.length) {
          max = Integer.parseInt(args[++index]);
          if (max <= 0) throw new IllegalArgumentException("--max-samples must be positive");
        } else if (args[index].equals("--tasks") && index + 1 < args.length) {
          tasks = parseTasks(args[++index]);
        } else if (args[index].equals("--generate-only")) {
          generateOnly = true;
        } else if (args[index].equals("--skip")) {
          skip = true;
        } else if (args[index].equals("--no-print-results")) {
          noPrintResults = true;
        } else if (args[index].equals("--predictions") && index + 1 < args.length
            && !args[index + 1].startsWith("--")) {
          while (index + 1 < args.length && !args[index + 1].startsWith("--")) {
            predictionsFiles.add(Path.of(args[++index]));
          }
        } else {
          throw new IllegalArgumentException(
              "Usage: --root <LowCode> [--max-samples N] [--tasks 1,2,3] [--no-print-results] "
                  + "[--generate-only | --predictions <outputs.jsonl> [more.jsonl ...] [--skip]]");
        }
      }
      if (generateOnly && !predictionsFiles.isEmpty()) {
        throw new IllegalArgumentException("--generate-only cannot be combined with --predictions");
      }
      if (skip && predictionsFiles.isEmpty()) {
        throw new IllegalArgumentException("--skip requires --predictions");
      }
      return new Arguments(root, max, Set.copyOf(tasks), List.copyOf(predictionsFiles),
          generateOnly, skip, noPrintResults);
    }

    private static Set<Integer> parseTasks(String value) {
      Set<Integer> result = new java.util.LinkedHashSet<>();
      for (String part : value.split(",")) {
        String normalized = part.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("difficulty")) {
          normalized = normalized.substring("difficulty".length());
        }
        int task;
        try {
          task = Integer.parseInt(normalized);
        } catch (NumberFormatException exception) {
          throw new IllegalArgumentException(
              "Tasks must be a comma-separated subset of 1,2,3", exception);
        }
        if (task < 1 || task > 3) {
          throw new IllegalArgumentException("Tasks must be a comma-separated subset of 1,2,3");
        }
        result.add(task);
      }
      if (result.isEmpty()) {
        throw new IllegalArgumentException("At least one task must be selected");
      }
      return result;
    }
  }

  private record OfflinePrediction(
      String id,
      String namespace,
      String language,
      String completion,
      Path source,
      Map<String, Object> record
  ) {
  }

  private record ModelOutput(
      String text, String model, Object finishReason, Object stopReason, Map<String, Object> usage
  ) {
  }

  private record OfflinePredictions(
      List<Path> source,
      Map<String, OfflinePrediction> predictionsById,
      Map<Path, List<Map<String, Object>>> recordsBySource
  ) {
    int size() {
      return predictionsById.size();
    }

    boolean contains(String repository, Map<String, Object> sample) {
      OfflinePrediction prediction = predictionsById.get(predictionId(repository, sample));
      return prediction != null
          && prediction.namespace().equals(repository)
          && (prediction.language().isBlank()
              || prediction.language().equalsIgnoreCase("java"));
    }

    String cachedStatusFor(String repository, Map<String, Object> sample) {
      OfflinePrediction prediction = predictionsById.get(predictionId(repository, sample));
      Object status = prediction == null ? null : prediction.record().get(EVALUATION_RESULT);
      return status instanceof String text && EVALUATION_STATUSES.contains(text) ? text : "";
    }

    // Called only after the repository workers have joined, so no worker mutates input rows.
    void persistResults(List<SampleOutcome> outcomes) throws IOException {
      Set<Path> changedSources = new LinkedHashSet<>();
      for (SampleOutcome outcome : outcomes) {
        Result result = outcome.result();
        OfflinePrediction prediction = predictionsById.get(predictionId(result.repo(), result.sample()));
        if (prediction == null) throw new IOException("No input prediction for result " + result.id());
        if (result.task().equals("difficulty1")) {
          for (String key : List.of(EVALUATION_RESULT, "status", "exact_match", "test_count")) {
            if (prediction.record().containsKey(key)) {
              prediction.record().remove(key);
              changedSources.add(prediction.source());
            }
          }
          for (Map.Entry<String, Object> metric : result.characterMetrics().entrySet()) {
            if (!java.util.Objects.equals(prediction.record().get(metric.getKey()), metric.getValue())) {
              prediction.record().put(metric.getKey(), metric.getValue());
              changedSources.add(prediction.source());
            }
          }
        } else if (!result.status().equals(prediction.record().get(EVALUATION_RESULT))) {
          prediction.record().put(EVALUATION_RESULT, result.status());
          changedSources.add(prediction.source());
        }
      }
      for (Path source : changedSources) {
        writePredictionRecords(source, recordsBySource.get(source));
      }
    }

    String completionFor(String repository, Map<String, Object> sample) throws IOException {
      String id = predictionId(repository, sample);
      OfflinePrediction prediction = predictionsById.get(id);
      if (prediction == null) {
        throw new IOException("No pre-generated output for sample " + id + " in " + source);
      }
      if (!prediction.namespace().equals(repository)) {
        throw new IOException("Pre-generated output " + id + " names repository "
            + prediction.namespace() + " but the sample belongs to " + repository);
      }
      if (!prediction.language().isBlank()
          && !prediction.language().equalsIgnoreCase("java")) {
        throw new IOException("Pre-generated output " + id + " is not a Java record");
      }
      return prediction.completion();
    }

    private static String predictionId(String repository, Map<String, Object> sample) {
      return repository + "_" + Json.string(sample.get("id"));
    }
  }

  private record PromptParts(String prefix, String suffix) {
  }

  private record WorkItem(
      long order,
      int task,
      int taskIndex,
      int taskCount,
      Map<String, Object> sample
  ) {
  }

  private record SampleOutcome(
      long order,
      Result result,
      Map<String, Object> completionRecord
  ) {
  }

  private record BuildCommand(String workdir, String command, boolean direct) {
  }

  private record TestRun(
      String command, String cwd, int exitCode, double durationSeconds, boolean timedOut, String output
  ) {
    boolean passed() { return !timedOut && exitCode == 0; }

    boolean infrastructureError() {
      String lower = output.toLowerCase(Locale.ROOT);
      return timedOut
          || (lower.contains("fetching distribution") && lower.contains("no such file"))
          || lower.contains("could not resolve host")
          || lower.contains("could not find or load main class org.gradle.wrapper.gradlewrappermain")
          || lower.contains("maven command not found")
          || lower.contains("gradle command not found");
    }

    Map<String, Object> toJson() {
      return Map.of(
          "command", command, "cwd", cwd, "exit_code", exitCode,
          "duration_seconds", durationSeconds, "timed_out", timedOut, "output", output
      );
    }
  }

  private record Result(
      String id,
      String repo,
      String repoTag,
      String task,
      String status,
      String completion,
      String raw,
      Map<String, Object> sample,
      List<TestRun> tests,
      String error,
      boolean reused
  ) {
    Result(String id, String repo, String repoTag, String task, String status,
        String completion, String raw, Map<String, Object> sample, List<TestRun> tests, String error) {
      this(id, repo, repoTag, task, status, completion, raw, sample, tests, error, false);
    }

    int passedTestCount() {
      if (status.equals("error")) return 0;
      return (int) tests.stream().filter(TestRun::passed).count();
    }

    int totalTestCount() {
      if (task.equals("difficulty1")) return 0;
      if (reused) return 0;
      return tests.isEmpty() ? plannedTestCount(sample, task) : tests.size();
    }

    Map<String, Object> characterMetrics() {
      if (!task.equals("difficulty1")) return Map.of();
      // A failed evaluation has no usable prediction, but its ground truth remains the
      // character denominator for the aggregate.
      return task1Metrics(sample, status.equals("error") ? "" : completion);
    }

    static Result error(Map<String, Object> sample, String completion, String raw, Throwable exception) {
      return new Result(Json.string(sample.get("id")), Json.string(sample.get("repo")),
          Json.string(sample.get("repo_tag")),
          Json.string(sample.get("task")), "error", completion, raw, sample, List.of(),
          exception.getClass().getSimpleName() + ": " + exception.getMessage());
    }

    Map<String, Object> summaryJson() {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("id", id);
      value.put("repo", repo);
      value.put("repo_tag", repoTag);
      value.put("task", task);
      value.put("completion_kind", Json.string(sample.get("completion_kind")));
      value.put("ast_node_kind", Json.string(sample.get("ast_node_kind")));
      value.put("subtask", subtask(task, sample));
      if (task.equals("difficulty1")) {
        value.putAll(characterMetrics());
      } else {
        value.put("status", status);
        value.put("test_count", tests.size());
      }
      if (reused) value.put("reused", true);
      if (!error.isBlank()) value.put("error", error);
      return value;
    }

    Map<String, Object> detailJson() {
      Map<String, Object> value = new LinkedHashMap<>();
      value.putAll(summaryJson());
      value.put("ground_truth", groundTruthText(sample));
      value.put("model_output", normalizedOutput(this, false));
      value.put("raw_model_output", normalizedOutput(this, true));
      value.put("sample", sample);
      value.put("tests", tests.stream().map(TestRun::toJson).toList());
      return value;
    }

    String testOutput() {
      if (reused) return "Reused evaluation_result=" + status
          + "; no tests executed in this run. Previous test details are not stored in the prediction.\n";
      if (!error.isBlank()) return error + "\n";
      StringBuilder value = new StringBuilder();
      for (TestRun test : tests) {
        value.append("$ ").append(test.command()).append("\n")
            .append("exit=").append(test.exitCode()).append(" timeout=").append(test.timedOut())
            .append(" duration=").append(test.durationSeconds()).append("s\n")
            .append(test.output()).append("\n\n");
      }
      return value.toString();
    }
  }

  /** Minimal JSON implementation to keep the evaluator dependency-free. */
  private static final class Json {
    private Json() {
    }

    static Object parse(String text) {
      Parser parser = new Parser(text);
      Object value = parser.value();
      parser.space();
      if (!parser.end()) throw new IllegalArgumentException("Trailing JSON data at " + parser.index);
      return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
      if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("Expected JSON object");
      return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object value) {
      if (!(value instanceof List<?>)) throw new IllegalArgumentException("Expected JSON array");
      return (List<Object>) value;
    }

    static String string(Object value) {
      return value == null ? "" : String.valueOf(value);
    }

    static int integer(Object value) {
      if (value instanceof Number number) return number.intValue();
      return Integer.parseInt(string(value));
    }

    static String stringify(Object value) {
      if (value == null) return "null";
      if (value instanceof String string) return quote(string);
      if (value instanceof Number || value instanceof Boolean) return value.toString();
      if (value instanceof Map<?, ?> map) {
        StringBuilder result = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          if (!first) result.append(',');
          first = false;
          result.append(quote(String.valueOf(entry.getKey()))).append(':').append(stringify(entry.getValue()));
        }
        return result.append('}').toString();
      }
      if (value instanceof Iterable<?> iterable) {
        StringBuilder result = new StringBuilder("[");
        boolean first = true;
        for (Object item : iterable) {
          if (!first) result.append(',');
          first = false;
          result.append(stringify(item));
        }
        return result.append(']').toString();
      }
      throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
    }

    private static String quote(String value) {
      StringBuilder result = new StringBuilder("\"");
      for (int index = 0; index < value.length(); index++) {
        char ch = value.charAt(index);
        switch (ch) {
          case '"' -> result.append("\\\"");
          case '\\' -> result.append("\\\\");
          case '\b' -> result.append("\\b");
          case '\f' -> result.append("\\f");
          case '\n' -> result.append("\\n");
          case '\r' -> result.append("\\r");
          case '\t' -> result.append("\\t");
          default -> {
            if (ch < 0x20) result.append(String.format("\\u%04x", (int) ch));
            else result.append(ch);
          }
        }
      }
      return result.append('"').toString();
    }

    private static final class Parser {
      private final String text;
      private int index;

      Parser(String text) { this.text = text; }
      boolean end() { return index >= text.length(); }
      void space() { while (!end() && Character.isWhitespace(text.charAt(index))) index++; }

      Object value() {
        space();
        if (end()) throw new IllegalArgumentException("Unexpected end of JSON");
        return switch (text.charAt(index)) {
          case '{' -> object();
          case '[' -> array();
          case '"' -> string();
          case 't' -> literal("true", true);
          case 'f' -> literal("false", false);
          case 'n' -> literal("null", null);
          default -> number();
        };
      }

      Map<String, Object> object() {
        index++;
        Map<String, Object> value = new LinkedHashMap<>();
        space();
        if (!end() && text.charAt(index) == '}') { index++; return value; }
        while (true) {
          space();
          String key = string();
          space();
          expect(':');
          value.put(key, value());
          space();
          if (take('}')) return value;
          expect(',');
        }
      }

      List<Object> array() {
        index++;
        List<Object> value = new ArrayList<>();
        space();
        if (!end() && text.charAt(index) == ']') { index++; return value; }
        while (true) {
          value.add(value());
          space();
          if (take(']')) return value;
          expect(',');
        }
      }

      String string() {
        expect('"');
        StringBuilder value = new StringBuilder();
        while (!end()) {
          char ch = text.charAt(index++);
          if (ch == '"') return value.toString();
          if (ch != '\\') { value.append(ch); continue; }
          if (end()) throw new IllegalArgumentException("Invalid JSON escape");
          char escaped = text.charAt(index++);
          switch (escaped) {
            case '"', '\\', '/' -> value.append(escaped);
            case 'b' -> value.append('\b');
            case 'f' -> value.append('\f');
            case 'n' -> value.append('\n');
            case 'r' -> value.append('\r');
            case 't' -> value.append('\t');
            case 'u' -> {
              if (index + 4 > text.length()) throw new IllegalArgumentException("Invalid unicode escape");
              value.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
              index += 4;
            }
            default -> throw new IllegalArgumentException("Invalid JSON escape: " + escaped);
          }
        }
        throw new IllegalArgumentException("Unterminated JSON string");
      }

      Object number() {
        int start = index;
        while (!end() && "-+0123456789.eE".indexOf(text.charAt(index)) >= 0) index++;
        String value = text.substring(start, index);
        if (value.isEmpty()) throw new IllegalArgumentException("Invalid JSON value at " + index);
        // Prediction rows are now read and written back. Preserve numeric metadata exactly;
        // a double/long conditional would promote even integer tokens to lossy doubles.
        if (value.contains(".") || value.contains("e") || value.contains("E")) {
          return new java.math.BigDecimal(value);
        }
        return new java.math.BigInteger(value);
      }

      Object literal(String expected, Object value) {
        if (!text.startsWith(expected, index)) throw new IllegalArgumentException("Invalid JSON literal");
        index += expected.length();
        return value;
      }

      boolean take(char expected) {
        if (!end() && text.charAt(index) == expected) { index++; return true; }
        return false;
      }

      void expect(char expected) {
        if (end() || text.charAt(index++) != expected) {
          throw new IllegalArgumentException("Expected '" + expected + "' at " + (index - 1));
        }
      }
    }
  }
}
