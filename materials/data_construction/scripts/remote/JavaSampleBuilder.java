import com.sun.source.doctree.DocCommentTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds Java code-generation samples using the JDK compiler AST.
 *
 * <p>Source repositories remain read-only. Maven/Gradle tests run only to measure coverage before
 * a sample is accepted.</p>
 */
public final class JavaSampleBuilder {

  private static final String MASK = "__DATASET_COMPLETION__";
  private static final int MAX_TASK3_SAMPLES_PER_REPOSITORY = 500000000;
  private static final int ENTERPRISE_IT_TASK3_ONLY_SFT_TRAIN_MULTIPLIER = 1;
  private static final int SMART_CITY_TASK3_ONLY_SFT_TRAIN_MULTIPLIER = 4;
  private static final int SMART_LOGISTICS_TASK3_ONLY_SFT_TRAIN_MULTIPLIER = 1;
  private static final int MAX_TASK3_SOURCE_BYTES = 100_00;

  private JavaSampleBuilder() {
  }

  public static void main(String[] args) throws Exception {
    Arguments arguments = Arguments.parse(args);
    Path reposDirectory = arguments.reposDirectory().toAbsolutePath().normalize();
    Path samplesDirectory = arguments.samplesDirectory().toAbsolutePath().normalize();
    Path skeletonsDirectory = arguments.skeletonsDirectory() == null ? null
        : arguments.skeletonsDirectory().toAbsolutePath().normalize();
    Path repositoryCatalog = arguments.repositoryCatalog() == null ? null
        : arguments.repositoryCatalog().toAbsolutePath().normalize();
    if (!Files.isDirectory(reposDirectory)) {
      throw new IllegalArgumentException("Repository directory is not a directory: " + reposDirectory);
    }
    Files.createDirectories(samplesDirectory);
    if (arguments.tasks().contains(3) && (skeletonsDirectory == null
        || !Files.isDirectory(skeletonsDirectory) || arguments.archive() == null
        || repositoryCatalog == null || !Files.isRegularFile(repositoryCatalog))) {
      throw new IllegalArgumentException(
          "Task 3 requires --skeletons, --archive, and --repository-catalog");
    }

    List<Path> repositories = discoverRepositories(reposDirectory);
    Map<String, String> repositoryDomains = arguments.tasks().contains(3)
        ? loadRepositoryDomains(repositoryCatalog) : Map.of();
    if (arguments.tasks().contains(3)) {
      for (Path repository : repositories) {
        String repositoryName = repository.getFileName().toString();
        if (!repositoryDomains.containsKey(repositoryName)) {
          throw new IllegalArgumentException(
              "Repository is missing from repository catalog: " + repositoryName);
        }
      }
    }
    int workers = Math.min(6, repositories.size());
    ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, workers));
    List<Future<RepositoryResult>> futures = new ArrayList<>();
    for (Path repoRoot : repositories) {
        futures.add(executor.submit(() -> buildRepository(
            repoRoot, samplesDirectory, skeletonsDirectory, arguments.tasks(), arguments.mix(),
            arguments.cover(), repositoryDomains.get(repoRoot.getFileName().toString()))));
    }
    executor.shutdown();
    List<RepositoryResult> results = new ArrayList<>();
    for (int index = 0; index < futures.size(); index++) {
      Path repoRoot = repositories.get(index);
      try {
        RepositoryResult result = futures.get(index).get();
        results.add(result);
        System.out.println("Repository: " + result.repoLabel());
        System.out.println("Output: " + result.outputDirectory());
        System.out.println("Samples difficulty1: " + result.difficulty1Count());
        System.out.println("Samples difficulty2: " + result.difficulty2Count());
        System.out.println("Samples difficulty3: " + result.difficulty3Count());
      } catch (ExecutionException exception) {
        Throwable cause = exception.getCause();
        System.err.println("Failed to build samples for " + repoRoot + ": " + cause);
        cause.printStackTrace(System.err);
      }
    }
    Files.deleteIfExists(samplesDirectory.resolve("summary.json"));
    System.out.println("Repositories processed: " + results.size());
    if (results.size() != repositories.size()) {
      throw new IllegalStateException("One or more repositories failed; refusing a partial rebuild");
    }
    int totalSamples = results.stream().mapToInt(RepositoryResult::totalCount).sum();
    System.out.println("Samples built: " + totalSamples);
    if (results.isEmpty() || totalSamples == 0) {
      throw new IllegalStateException("No valid samples were built; refusing an empty rebuild");
    }
    validateSamples(repositories, samplesDirectory);
    if (arguments.tasks().contains(3)) {
      createTask3Archive(samplesDirectory, arguments.archive().toAbsolutePath().normalize());
    }
  }

  private static RepositoryResult buildRepository(
      Path repoRoot,
      Path samplesDirectory,
      Path skeletonsDirectory,
      Set<Integer> selectedTasks,
      boolean mixTask3,
      boolean coverTask3,
      String repositoryDomain)
      throws IOException {
    String repoName = repoRoot.getFileName().toString();
    String repoLabel = GitHubLabel.fromRepository(repoRoot);
    Path outputDirectory = samplesDirectory.resolve(sanitizeDirectoryName(repoName));

    List<Path> mainFiles = findJavaFiles(repoRoot, List.of("src", "main", "java"));
    List<Path> testFiles = findJavaFiles(repoRoot, List.of("src", "test", "java"));
    List<SourceUnit> mainUnits = parseSources(mainFiles, repoRoot, SourceKind.MAIN);
    List<SourceUnit> testUnits = parseSources(testFiles, repoRoot, SourceKind.TEST);

    List<MethodInfo> allMethods = mainUnits.stream()
        .flatMap(unit -> unit.methods().stream())
        .filter(MethodInfo::hasBody)
        .filter(MethodInfo::hasUsableSpan)
        .filter(MethodInfo::isOrdinaryMethod)
        .toList();
    List<MethodInfo> sampleMethods = allMethods.stream()
        .filter(method -> !method.javadoc().isBlank())
        .toList();
    List<ClassInfo> classes = mainUnits.stream()
        .flatMap(unit -> unit.classes().stream())
        .filter(ClassInfo::hasUsableSpan)
        .toList();
    List<TestCall> testCalls = testUnits.stream()
        .flatMap(unit -> unit.testCalls().stream())
        .toList();

    boolean buildMethodTasks = selectedTasks.contains(1) || selectedTasks.contains(2);
    boolean buildClassTask = selectedTasks.contains(3);
    Map<MethodInfo, List<TestDescriptor>> methodTests = new LinkedHashMap<>();
    for (MethodInfo method : sampleMethods) {
      List<TestDescriptor> matches = testCalls.stream()
          .filter(call -> call.directlyCalls(method))
          .map(call -> TestDescriptor.create(repoRoot, call))
          .filter(Optional::isPresent)
          .map(Optional::get)
          .distinct()
          .toList();
      if (!matches.isEmpty()) {
        methodTests.put(method, matches);
      }
    }
    Map<ClassInfo, List<TestDescriptor>> classTests = new LinkedHashMap<>();
    for (ClassInfo classInfo : buildClassTask ? classes : List.<ClassInfo>of()) {
      List<TestDescriptor> matches = testCalls.stream()
          .filter(call -> call.callsClass(classInfo))
          .map(call -> TestDescriptor.create(repoRoot, call))
          .filter(Optional::isPresent)
          .map(Optional::get)
          .distinct()
          .toList();
      if (!matches.isEmpty()) {
        classTests.put(classInfo, matches);
      }
    }

    Map<CoverageAnalyzer.TestSpec, List<CoverageAnalyzer.Target>> targetsByTest =
        new LinkedHashMap<>();
    if (buildMethodTasks) {
      for (Map.Entry<MethodInfo, List<TestDescriptor>> entry : methodTests.entrySet()) {
        MethodInfo method = entry.getKey();
        for (TestDescriptor test : entry.getValue()) {
          targetsByTest.computeIfAbsent(test.asCoverageSpec(), ignored -> new ArrayList<>())
              .add(method.asCoverageTarget());
        }
      }
    }
    for (Map.Entry<ClassInfo, List<TestDescriptor>> entry : classTests.entrySet()) {
      for (TestDescriptor test : entry.getValue()) {
        targetsByTest.computeIfAbsent(test.asCoverageSpec(), ignored -> new ArrayList<>())
            .add(entry.getKey().asCoverageTarget());
      }
    }
    Map<CoverageAnalyzer.TestSpec, List<CoverageAnalyzer.Target>> legacyTargetsByTest =
        new LinkedHashMap<>();
    for (MethodInfo method : sampleMethods) {
      for (TestDescriptor test : methodTests.getOrDefault(method, List.of())) {
        legacyTargetsByTest.computeIfAbsent(test.asCoverageSpec(), ignored -> new ArrayList<>())
            .add(method.asCoverageTarget());
      }
    }
    Path coverageDirectory = samplesDirectory.resolve(".coverage-work")
        .resolve(sanitizeDirectoryName(repoName));
    Map<String, Map<String, CoverageAnalyzer.Coverage>> measured =
        CoverageAnalyzer.ENABLE_LINE_COVERAGE_FILTER
            ? CoverageAnalyzer.measure(
                repoRoot, coverageDirectory, targetsByTest, legacyTargetsByTest)
            : Map.of();
    Map<MethodInfo, EligibleTarget> eligibleMethods = new LinkedHashMap<>();
    for (MethodInfo method : buildMethodTasks ? sampleMethods : List.<MethodInfo>of()) {
      EligibleTarget eligible = eligibleTarget(
          methodTests.getOrDefault(method, List.of()), method.coverageKey(), measured);
      if (eligible != null) eligibleMethods.put(method, eligible);
    }
    Map<ClassInfo, EligibleTarget> eligibleClasses = new LinkedHashMap<>();
    Map<ClassInfo, CoverageAnalyzer.Coverage> classCoverages = new LinkedHashMap<>();
    for (ClassInfo classInfo : buildClassTask ? classes : List.<ClassInfo>of()) {
      List<TestDescriptor> tests = classTests.getOrDefault(classInfo, List.of());
      classCoverages.put(classInfo, coverageForTarget(tests, classInfo.coverageKey(), measured));
      EligibleTarget eligible = eligibleTarget(
          tests, classInfo.coverageKey(), measured);
      if (eligible != null) eligibleClasses.put(classInfo, eligible);
    }

    Files.createDirectories(outputDirectory);

    if (selectedTasks.contains(1)) Files.deleteIfExists(outputDirectory.resolve("1.json"));
    if (selectedTasks.contains(2)) Files.deleteIfExists(outputDirectory.resolve("2.json"));
    int difficulty1Count = selectedTasks.contains(1)
        ? writeDifficulty1(
            outputDirectory.resolve("1.json"), repoName, repoLabel, sampleMethods, eligibleMethods)
        : countJsonArrayEntries(outputDirectory.resolve("1.json"));
    int difficulty2Count = selectedTasks.contains(2)
        ? writeDifficulty2(
            outputDirectory.resolve("2.json"), repoName, repoLabel, sampleMethods, eligibleMethods)
        : countJsonArrayEntries(outputDirectory.resolve("2.json"));
    int difficulty3Count = selectedTasks.contains(3)
        ? writeDifficulty3(
            outputDirectory.resolve("3.json"), repoRoot, repoName, repoLabel,
            skeletonsDirectory.resolve(repoName).resolve("skeletons.jsonl"), classes,
            eligibleClasses, classCoverages, mixTask3, coverTask3, repositoryDomain)
        : countJsonArrayEntries(outputDirectory.resolve("3.json"));
    writeRepositoryMetadata(
        outputDirectory.resolve("metadata.json"), difficulty1Count, difficulty2Count, difficulty3Count
    );
    return new RepositoryResult(
        repoName, repoLabel, outputDirectory,
        difficulty1Count, difficulty2Count, difficulty3Count
    );
  }

  private static List<Path> discoverRepositories(Path reposDirectory) throws IOException {
    try (Stream<Path> paths = Files.list(reposDirectory)) {
      return paths
          .filter(Files::isDirectory)
          .filter(path -> !path.getFileName().toString().startsWith("."))
          .filter(JavaSampleBuilder::isUsableRepository)
          .sorted()
          .toList();
    }
  }

  private static Map<String, String> loadRepositoryDomains(Path repositoryCatalog)
      throws IOException {
    Object parsed = Json.parse(Files.readString(repositoryCatalog, StandardCharsets.UTF_8));
    if (!(parsed instanceof List<?> entries)) {
      throw new IOException("Repository catalog must be a JSON array: " + repositoryCatalog);
    }
    Map<String, String> domains = new LinkedHashMap<>();
    for (int index = 0; index < entries.size(); index++) {
      if (!(entries.get(index) instanceof Map<?, ?> entry)
          || !(entry.get("repository_full_name") instanceof String repositoryName)
          || repositoryName.isBlank()
          || !(entry.get("domain") instanceof String domain)
          || domain.isBlank()) {
        throw new IOException(
            "Invalid repository catalog entry at index " + index + ": " + repositoryCatalog);
      }
      task3OnlySftTrainMultiplier(domain);
      String previous = domains.putIfAbsent(repositoryName, domain);
      if (previous != null) {
        throw new IOException("Duplicate repository in repository catalog: " + repositoryName);
      }
    }
    return domains;
  }

  private static int task3OnlySftTrainMultiplier(String domain) {
    return switch (domain) {
      case "企业信息化" -> ENTERPRISE_IT_TASK3_ONLY_SFT_TRAIN_MULTIPLIER;
      case "智慧城市" -> SMART_CITY_TASK3_ONLY_SFT_TRAIN_MULTIPLIER;
      case "智慧物流" -> SMART_LOGISTICS_TASK3_ONLY_SFT_TRAIN_MULTIPLIER;
      default -> throw new IllegalArgumentException("Unsupported repository domain: " + domain);
    };
  }

  private static boolean isUsableRepository(Path path) {
    return Files.exists(path.resolve("pom.xml"))
        || Files.exists(path.resolve("build.gradle"))
        || Files.exists(path.resolve("build.gradle.kts"));
  }

  private static int writeDifficulty1(
      Path destination,
      String repoName,
      String repoLabel,
      List<MethodInfo> methods,
      Map<MethodInfo, EligibleTarget> eligibleMethods
  )
      throws IOException {
    int count = 0;
    try (JsonArrayWriter writer = new JsonArrayWriter(destination)) {
      for (MethodInfo method : methods) {
        EligibleTarget eligible = eligibleMethods.get(method);
        if (eligible == null) {
          continue;
        }
        Span call = chooseRandomCall(method);
        if (call != null) {
          String context = maskWithinFile(method, call);
          String groundTruth = method.unit().source().substring(call.start(), call.end());
          writer.writeObject(sampleJson(
              "difficulty1", "expression", repoName, repoLabel, method, call,
              context, groundTruth, eligible.tests(), eligible.coverage(),
              Tree.Kind.METHOD_INVOCATION.name()
          ));
          count++;
        }

        Span lineRange = chooseRandomLineRange(method);
        if (lineRange != null) {
          String lineContext = maskWithinFile(method, lineRange);
          String lineGroundTruth = method.unit().source().substring(
              lineRange.start(), lineRange.end());
          writer.writeObject(sampleJson(
              "difficulty1", "lines", repoName, repoLabel, method, lineRange,
              lineContext, lineGroundTruth, eligible.tests(), eligible.coverage(),
              "LINE_RANGE", "lines"
          ));
          count++;
        }
      }
    }
    return count;
  }

  private static int writeDifficulty2(
      Path destination,
      String repoName,
      String repoLabel,
      List<MethodInfo> methods,
      Map<MethodInfo, EligibleTarget> eligibleMethods
  ) throws IOException {
    int count = 0;
    try (JsonArrayWriter writer = new JsonArrayWriter(destination)) {
      for (MethodInfo method : methods) {
        EligibleTarget eligible = eligibleMethods.get(method);
        if (eligible == null) {
          continue;
        }
        AstCandidate candidate = chooseRandomAstCandidate(method);
        if (candidate != null) {
          Span span = candidate.span();
          String context = maskWithinFile(method, span);
          String groundTruth = method.unit().source().substring(span.start(), span.end());
          writer.writeObject(sampleJson(
              "difficulty2", candidate.completionKind(), repoName, repoLabel, method, span,
              context, groundTruth, eligible.tests(), eligible.coverage(),
              candidate.treeKind().name()
          ));
          count++;
        }

        Span body = method.bodySpan();
        if (body != null && body.isValid()) {
          String bodyContext = maskWithinFile(method, body);
          String bodyGroundTruth = method.unit().source().substring(body.start(), body.end());
          writer.writeObject(sampleJson(
              "difficulty2", "function_body", repoName, repoLabel, method, body,
              bodyContext, bodyGroundTruth, eligible.tests(), eligible.coverage(),
              "FUNCTION_BODY"
          ));
          count++;
        }
      }
    }
    return count;
  }

  private static EligibleTarget eligibleTarget(
      List<TestDescriptor> tests,
      String targetKey,
      Map<String, Map<String, CoverageAnalyzer.Coverage>> measured
  ) {
    if (tests.isEmpty()) return null;
    if (!CoverageAnalyzer.ENABLE_LINE_COVERAGE_FILTER) {
      return new EligibleTarget(
          tests.stream().map(TestDescriptor::withoutCoverage).toList(),
          CoverageAnalyzer.Coverage.empty());
    }
    List<TestDescriptor> successful = new ArrayList<>();
    List<CoverageAnalyzer.Coverage> coverages = new ArrayList<>();
    for (TestDescriptor test : tests) {
      CoverageAnalyzer.Coverage coverage = measured
          .getOrDefault(test.coverageKey(), Map.of())
          .get(targetKey);
      if (coverage == null) continue;
      coverages.add(coverage);
      if (coverage.coveredLines() > 0) {
        test.withCoverage(coverage).ifPresent(successful::add);
      }
    }
    CoverageAnalyzer.Coverage merged = CoverageAnalyzer.Coverage.merge(coverages);
    if (!merged.passesThreshold() || successful.isEmpty()) return null;
    return new EligibleTarget(List.copyOf(successful), merged);
  }

  private static CoverageAnalyzer.Coverage coverageForTarget(
      List<TestDescriptor> tests,
      String targetKey,
      Map<String, Map<String, CoverageAnalyzer.Coverage>> measured
  ) {
    if (!CoverageAnalyzer.ENABLE_LINE_COVERAGE_FILTER || tests.isEmpty()) {
      return CoverageAnalyzer.Coverage.empty();
    }
    List<CoverageAnalyzer.Coverage> coverages = new ArrayList<>();
    for (TestDescriptor test : tests) {
      CoverageAnalyzer.Coverage coverage = measured
          .getOrDefault(test.coverageKey(), Map.of())
          .get(targetKey);
      if (coverage != null) coverages.add(coverage);
    }
    return CoverageAnalyzer.Coverage.merge(coverages);
  }

  private static int writeDifficulty3(
      Path destination,
      Path repoRoot,
      String repoName,
      String repoLabel,
      Path skeletonFile,
      List<ClassInfo> classes,
      Map<ClassInfo, EligibleTarget> eligibleClasses,
      Map<ClassInfo, CoverageAnalyzer.Coverage> classCoverages,
      boolean mixTask3,
      boolean coverTask3,
      String repositoryDomain
  ) throws IOException {
    List<SkeletonRecord> skeletons = loadSkeletons(skeletonFile, repoName, repoLabel);
    Map<String, SkeletonRecord> skeletonByPath = skeletons.stream().collect(Collectors.toMap(
        SkeletonRecord::sourcePath, record -> record, (first, ignored) -> first,
        LinkedHashMap::new));

    Map<String, Task3Candidate> candidateByPath = new LinkedHashMap<>();
    for (Map.Entry<ClassInfo, EligibleTarget> entry : eligibleClasses.entrySet()) {
      String path = entry.getKey().unit().relativePath();
      if (!skeletonByPath.containsKey(path)) continue;
      Task3Candidate current = candidateByPath.get(path);
      if (current == null || entry.getValue().coverage().ratio() > current.coverage().ratio()) {
        candidateByPath.put(path, new Task3Candidate(
            entry.getKey(), entry.getValue(), entry.getValue().coverage(), false));
      }
    }
    if (mixTask3) {
      for (ClassInfo classInfo : classes) {
        String path = classInfo.unit().relativePath();
        if (!skeletonByPath.containsKey(path)) continue;
        CoverageAnalyzer.Coverage coverage = classCoverages.getOrDefault(
            classInfo, CoverageAnalyzer.Coverage.empty());
        Task3Candidate current = candidateByPath.get(path);
        if (current == null || (current.onlySftTrain()
            && coverage.ratio() > current.coverage().ratio())) {
          candidateByPath.put(path, new Task3Candidate(classInfo, null, coverage, true));
        }
      }
    }
    List<Task3Candidate> candidates = new ArrayList<>(candidateByPath.values());
    Comparator<Task3Candidate> ranking;
    if (CoverageAnalyzer.ENABLE_LINE_COVERAGE_FILTER) {
      ranking = Comparator.comparingDouble(
              (Task3Candidate candidate) -> candidate.coverage().ratio()).reversed()
          .thenComparing(candidate -> candidate.classInfo().unit().relativePath());
    } else {
      ranking = Comparator.comparing(candidate ->
          sha256(repoName + "|" + candidate.classInfo().unit().relativePath()));
    }
    if (mixTask3) {
      ranking = Comparator.comparing(Task3Candidate::onlySftTrain).thenComparing(ranking);
    }
    candidates.sort(ranking);

    int onlySftTrainMultiplier = task3OnlySftTrainMultiplier(repositoryDomain);
    Map<String, String> existingRequirements = coverTask3
        ? Map.of() : loadExistingTask3Requirements(destination, repoName);
    Set<String> sampleIds = new LinkedHashSet<>();
    int count = 0;
    int testableSampleCount = 0;
    int onlySftTrainSampleCount = 0;
    try (JsonArrayWriter writer = new JsonArrayWriter(destination)) {
      for (Task3Candidate candidate : candidates) {
        if (count >= MAX_TASK3_SAMPLES_PER_REPOSITORY) break;
        ClassInfo classInfo = candidate.classInfo();
        EligibleTarget eligible = candidate.eligible();
        String relativePath = classInfo.unit().relativePath();
        String sampleId = "difficulty3_"
            + sha256(repoName + "|" + relativePath).substring(0, 16);
        if (sampleIds.contains(sampleId)) continue;
        if (candidate.onlySftTrain()
            && onlySftTrainSampleCount
                >= (long) testableSampleCount * onlySftTrainMultiplier) break;
        Path relativeSourcePath = Path.of(relativePath);
        if (!containsPathSequence(relativeSourcePath, List.of("src", "main"))
            || containsPathSequence(relativeSourcePath, List.of("src", "test"))) continue;
        Path sourcePath = repoRoot.resolve(relativePath).normalize();
        if (!sourcePath.startsWith(repoRoot) || !Files.isRegularFile(sourcePath)
            || Files.size(sourcePath) > MAX_TASK3_SOURCE_BYTES) continue;
        byte[] sourceBytes = Files.readAllBytes(sourcePath);
        String sourceHash = sha256(sourceBytes);
        SkeletonRecord target = skeletonByPath.get(relativePath);
        if (!sourceHash.equals(target.sourceSha256())) {
          throw new IOException("Source hash differs from skeleton for "
              + repoName + "/" + relativePath);
        }
        List<SkeletonRecord> closure = dependencyClosure(target, skeletons);
        Map<String, Object> targetFile = new LinkedHashMap<>();
        targetFile.put("source_path", relativePath);
        targetFile.put("source_sha256", sourceHash);
        targetFile.put("ground_truth", new String(sourceBytes, StandardCharsets.UTF_8));
        targetFile.put("skeleton", target.skeleton());

        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("id", sampleId);
        sample.put("tag", "difficulty3");
        sample.put("task", "difficulty3");
        sample.put("repo", repoName);
        sample.put("repo_tag", repoLabel);
        sample.put("target_paths", List.of(relativePath));
        sample.put("requirement", existingRequirements.getOrDefault(sampleId, ""));
        sample.put("requirement_model", "gpt-5.6-sol");
        sample.put("completion_kind", "files");
        sample.put("target_files", List.of(targetFile));
        sample.put("skeletons", closure.stream().map(SkeletonRecord::skeleton).toList());
        sample.put("line_coverage", CoverageAnalyzer.ENABLE_LINE_COVERAGE_FILTER
            ? candidate.coverage().ratio() : 0.0);
        sample.put("line_coverage_filter_enabled", CoverageAnalyzer.ENABLE_LINE_COVERAGE_FILTER);
        sample.put("line_coverage_threshold", CoverageAnalyzer.MINIMUM_LINE_COVERAGE);
        sample.put("only_sft_train", candidate.onlySftTrain());
        sample.put("tests", candidate.onlySftTrain()
            ? List.of() : new RawJson(jsonTests(eligible.tests())));
        sample.put("build_commands", List.of());
        sample.put("evaluation_mode", "tests");
        sample.put("schema_version", 2);
        writer.writeObject(jsonObject(sample));
        sampleIds.add(sampleId);
        count++;
        if (candidate.onlySftTrain()) {
          onlySftTrainSampleCount++;
        } else {
          testableSampleCount++;
        }
      }
    }
    return count;
  }

  private static Map<String, String> loadExistingTask3Requirements(
      Path taskFile, String expectedRepository) throws IOException {
    if (!Files.isRegularFile(taskFile)) return Map.of();
    Object parsed = Json.parse(Files.readString(taskFile, StandardCharsets.UTF_8));
    if (!(parsed instanceof List<?> samples)) {
      throw new IOException("Expected a JSON array in existing task-3 file: " + taskFile);
    }
    Map<String, String> result = new LinkedHashMap<>();
    Set<String> ids = new HashSet<>();
    for (int index = 0; index < samples.size(); index++) {
      if (!(samples.get(index) instanceof Map<?, ?> sample)
          || !(sample.get("id") instanceof String id) || id.isBlank()
          || !expectedRepository.equals(sample.get("repo"))) {
        throw new IOException(
            "Invalid existing task-3 sample in " + taskFile + " at index " + index);
      }
      if (!ids.add(id)) {
        throw new IOException("Duplicate existing task-3 sample id in " + taskFile + ": " + id);
      }
      if (sample.get("requirement") instanceof String requirement) {
        result.put(id, requirement);
      }
    }
    return Map.copyOf(result);
  }

  private static List<SkeletonRecord> loadSkeletons(
      Path skeletonFile, String expectedRepo, String expectedRepoTag) throws IOException {
    if (!Files.isRegularFile(skeletonFile)) {
      throw new IOException("Missing skeleton file: " + skeletonFile);
    }
    List<SkeletonRecord> records = new ArrayList<>();
    int lineNumber = 0;
    for (String line : Files.readAllLines(skeletonFile, StandardCharsets.UTF_8)) {
      lineNumber++;
      if (line.isBlank()) continue;
      Object parsed;
      try {
        parsed = Json.parse(line);
      } catch (RuntimeException exception) {
        throw new IOException("Invalid skeleton JSON at " + skeletonFile + ":" + lineNumber,
            exception);
      }
      if (!(parsed instanceof Map<?, ?> map)) {
        throw new IOException("Skeleton line is not an object: " + skeletonFile + ":" + lineNumber);
      }
      SkeletonRecord record = SkeletonRecord.from(map);
      if (!expectedRepo.equals(record.repo()) || !expectedRepoTag.equals(record.repoTag())) {
        throw new IOException("Skeleton repository identity mismatch: " + skeletonFile + ":" + lineNumber);
      }
      records.add(record);
    }
    return List.copyOf(records);
  }

  private static List<SkeletonRecord> dependencyClosure(
      SkeletonRecord target, List<SkeletonRecord> records) {
    Map<String, SkeletonRecord> byFqn = new HashMap<>();
    for (SkeletonRecord record : records) {
      for (String declared : record.declaredTypes()) byFqn.put(declared, record);
    }
    Map<String, SkeletonRecord> selected = new LinkedHashMap<>();
    selected.put(target.sourcePath(), target);
    Deque<SkeletonRecord> queue = new ArrayDeque<>();
    queue.add(target);
    while (!queue.isEmpty()) {
      SkeletonRecord record = queue.remove();
      for (SkeletonRecord dependency : skeletonDependencies(record, byFqn)) {
        if (selected.putIfAbsent(dependency.sourcePath(), dependency) == null) {
          queue.add(dependency);
        }
      }
    }
    List<SkeletonRecord> result = new ArrayList<>();
    result.add(target);
    selected.entrySet().stream()
        .filter(entry -> !entry.getKey().equals(target.sourcePath()))
        .sorted(Map.Entry.comparingByKey())
        .map(Map.Entry::getValue)
        .forEach(result::add);
    return List.copyOf(result);
  }

  private static List<SkeletonRecord> skeletonDependencies(
      SkeletonRecord record, Map<String, SkeletonRecord> byFqn) {
    Map<String, String> explicitImports = new HashMap<>();
    List<String> wildcardPackages = new ArrayList<>();
    Map<String, SkeletonRecord> resolved = new HashMap<>();
    for (String imported : record.imports()) {
      String raw = imported.startsWith("static ") ? imported.substring(7) : imported;
      if (raw.endsWith(".*")) {
        wildcardPackages.add(raw.substring(0, raw.length() - 2));
      } else {
        explicitImports.put(raw.substring(raw.lastIndexOf('.') + 1), raw);
        SkeletonRecord dependency = resolveImportedType(raw, byFqn);
        if (dependency != null) resolved.put(dependency.sourcePath(), dependency);
      }
    }
    for (String reference : record.referencedTypes()) {
      String simple = reference.substring(reference.lastIndexOf('.') + 1);
      SkeletonRecord dependency = byFqn.get(reference);
      if (dependency == null && explicitImports.containsKey(simple)) {
        dependency = resolveImportedType(explicitImports.get(simple), byFqn);
      }
      if (dependency == null) {
        String samePackage = record.packageName().isBlank()
            ? simple : record.packageName() + "." + simple;
        dependency = byFqn.get(samePackage);
      }
      if (dependency == null) {
        for (String wildcard : wildcardPackages) {
          dependency = byFqn.get(wildcard + "." + simple);
          if (dependency != null) break;
        }
      }
      if (dependency != null) resolved.put(dependency.sourcePath(), dependency);
    }
    resolved.remove(record.sourcePath());
    return resolved.entrySet().stream().sorted(Map.Entry.comparingByKey())
        .map(Map.Entry::getValue).toList();
  }

  private static SkeletonRecord resolveImportedType(
      String imported, Map<String, SkeletonRecord> byFqn) {
    String candidate = imported;
    while (candidate.contains(".")) {
      SkeletonRecord resolved = byFqn.get(candidate);
      if (resolved != null) return resolved;
      candidate = candidate.substring(0, candidate.lastIndexOf('.'));
    }
    return byFqn.get(candidate);
  }

  private static String maskWithinFile(MethodInfo method, Span target) {
    String source = method.unit().source();
    return source.substring(0, target.start())
        + MASK
        + source.substring(target.end());
  }

  private static AstCandidate chooseRandomAstCandidate(MethodInfo method) {
    Span body = method.bodySpan();
    if (body == null || !body.isValid()) return null;
    List<AstCandidate> candidates = new ArrayList<>(method.astCandidates().stream()
        .filter(candidate -> method.contains(candidate.span()))
        .filter(candidate -> body.start() < candidate.span().start()
            && candidate.span().end() < body.end())
        .filter(candidate -> !candidate.span().equals(body))
        .filter(candidate -> candidate.span().end() > candidate.span().start())
        .distinct()
        .toList());
    if (candidates.isEmpty()) return null;
    List<AstCandidate> nonApiCandidates = candidates.stream()
        .filter(candidate -> !candidate.apiCall())
        .toList();
    if (!nonApiCandidates.isEmpty()) {
      candidates = new ArrayList<>(nonApiCandidates);
    }
    List<AstCandidate> preferredLengthCandidates = candidates.stream()
        .filter(candidate -> candidate.span().end() - candidate.span().start() >= 8)
        .toList();
    if (!preferredLengthCandidates.isEmpty()) {
      candidates = new ArrayList<>(preferredLengthCandidates);
    }
    long seed = Long.parseUnsignedLong(
        sha256("difficulty2|" + method.unit().relativePath() + "|" + method.symbol())
            .substring(0, 16), 16);
    return candidates.get(new Random(seed).nextInt(candidates.size()));
  }

  private static Span chooseRandomLineRange(MethodInfo method) {
    Span body = method.bodySpan();
    if (body == null || !body.isValid()) return null;
    List<PhysicalLine> lines = physicalLines(method.unit().source());
    List<Span> candidates = new ArrayList<>();
    for (int start = 0; start < lines.size(); start++) {
      for (int length = 1; length <= 3 && start + length <= lines.size(); length++) {
        boolean valid = true;
        for (int offset = 0; offset < length; offset++) {
          PhysicalLine line = lines.get(start + offset);
          if (!line.eligible()
              || line.span().start() <= body.start()
              || line.span().end() >= body.end()) {
            valid = false;
            break;
          }
        }
        if (valid) {
          candidates.add(new Span(
              lines.get(start).span().start(),
              lines.get(start + length - 1).span().end()));
        }
      }
    }
    if (candidates.isEmpty()) return null;
    long seed = Long.parseUnsignedLong(
        sha256("difficulty1-lines|" + method.unit().relativePath() + "|" + method.symbol())
            .substring(0, 16), 16);
    return candidates.get(new Random(seed).nextInt(candidates.size()));
  }

  private static List<PhysicalLine> physicalLines(String source) {
    List<PhysicalLine> lines = new ArrayList<>();
    int lineStart = 0;
    int index = 0;
    while (index < source.length()) {
      char character = source.charAt(index);
      if (character == '\n' || character == '\r') {
        int contentEnd = index;
        int lineEnd = index + 1;
        if (character == '\r' && lineEnd < source.length()
            && source.charAt(lineEnd) == '\n') {
          lineEnd++;
        }
        String content = source.substring(lineStart, contentEnd);
        lines.add(new PhysicalLine(
            new Span(lineStart, lineEnd), visibleCodePointCount(content) > 3));
        lineStart = lineEnd;
        index = lineEnd;
      } else {
        index++;
      }
    }
    if (lineStart < source.length()) {
      lines.add(new PhysicalLine(
          new Span(lineStart, source.length()),
          visibleCodePointCount(source.substring(lineStart)) > 3));
    }
    return lines;
  }

  private static long visibleCodePointCount(String source) {
    return source.codePoints()
        .filter(codePoint -> !Character.isWhitespace(codePoint)
            && !Character.isSpaceChar(codePoint))
        .count();
  }

  private static int countJsonArrayEntries(Path path) throws IOException {
    if (!Files.isRegularFile(path)) return 0;
    String json = Files.readString(path, StandardCharsets.UTF_8);
    int depth = 0;
    int count = 0;
    boolean string = false;
    boolean escape = false;
    for (int index = 0; index < json.length(); index++) {
      char ch = json.charAt(index);
      if (string) {
        if (escape) escape = false;
        else if (ch == '\\') escape = true;
        else if (ch == '"') string = false;
        continue;
      }
      if (ch == '"') string = true;
      else if (ch == '{') {
        if (depth == 1) count++;
        depth++;
      } else if (ch == '}' || ch == ']') depth--;
      else if (ch == '[') depth++;
    }
    return count;
  }

  private static Span chooseRandomCall(MethodInfo method) {
    List<Span> calls = method.methodCalls().stream()
        .filter(method::contains)
        .distinct()
        .toList();
    if (calls.isEmpty()) return null;
    long seed = Long.parseUnsignedLong(
        sha256("difficulty1|" + method.unit().relativePath() + "|" + method.symbol())
            .substring(0, 16), 16);
    return calls.get(new Random(seed).nextInt(calls.size()));
  }

  private static String sampleJson(
      String task,
      String completionKind,
      String repoName,
      String repoLabel,
      MethodInfo method,
      Span target,
      String context,
      String groundTruth,
      List<TestDescriptor> tests,
      CoverageAnalyzer.Coverage mergedCoverage,
      String astNodeKind
  ) {
    return sampleJson(
        task, completionKind, repoName, repoLabel, method, target, context, groundTruth,
        tests, mergedCoverage, astNodeKind, null);
  }

  private static String sampleJson(
      String task,
      String completionKind,
      String repoName,
      String repoLabel,
      MethodInfo method,
      Span target,
      String context,
      String groundTruth,
      List<TestDescriptor> tests,
      CoverageAnalyzer.Coverage mergedCoverage,
      String astNodeKind,
      String idSalt
  ) {
    Map<String, Object> values = new HashMap<>();
    values.put("id", sampleId(task, method, target, idSalt));
    values.put("tag", task);
    values.put("task", task);
    values.put("repo", repoName);
    values.put("repo_tag", repoLabel);
    values.put("source_path", method.unit().relativePath());
    values.put("source_sha256", sha256(method.unit().source()));
    values.put("symbol", method.symbol());
    values.put("completion_kind", completionKind);
    values.put("context", context);
    values.put("requirement", method.javadoc());
    values.put("ground_truth", groundTruth);
    values.put("start_offset", target.start());
    values.put("end_offset", target.end());
    if (astNodeKind != null) values.put("ast_node_kind", astNodeKind);
    values.put("line_coverage", CoverageAnalyzer.ENABLE_LINE_COVERAGE_FILTER
        ? mergedCoverage.ratio() : 0.0);
    values.put("line_coverage_filter_enabled", CoverageAnalyzer.ENABLE_LINE_COVERAGE_FILTER);
    values.put("line_coverage_threshold", CoverageAnalyzer.MINIMUM_LINE_COVERAGE);
    values.put("tests", new RawJson(jsonTests(tests)));
    return jsonObject(values);
  }

  private static String jsonTests(List<TestDescriptor> tests) {
    return tests.stream()
        .sorted(
            Comparator.comparing(TestDescriptor::testPath)
                .thenComparing(TestDescriptor::testClass)
                .thenComparing(TestDescriptor::testMethod)
        )
        .map(test -> {
          Map<String, Object> value = new HashMap<>();
          value.put("name", test.testClass() + "." + test.testMethod());
          value.put("test_workdir", test.testWorkdir());
          value.put("test_command", test.testCommand());
          value.put("covered_lines", test.coverage().coveredLines());
          value.put("total_lines", test.coverage().totalLines());
          value.put("line_coverage", test.coverage().ratio());
          return jsonObject(value);
        })
        .collect(Collectors.joining(",", "[", "]"));
  }

  private static void writeRepositoryMetadata(
      Path destination,
      int difficulty1Count,
      int difficulty2Count,
      int difficulty3Count
  )
      throws IOException {
    String metadata = String.format(Locale.ROOT,
        "{\n  \"task1\": %d,\n  \"task2\": %d,\n  \"task3\": %d,\n  \"total\": %d\n}\n",
        difficulty1Count,
        difficulty2Count,
        difficulty3Count,
        difficulty1Count + difficulty2Count + difficulty3Count);
    Files.writeString(destination, metadata, StandardCharsets.UTF_8);
  }

  private static void validateSamples(List<Path> repositories, Path samplesDirectory)
      throws IOException {
    Set<String> expected = repositories.stream()
        .map(path -> sanitizeDirectoryName(path.getFileName().toString()))
        .collect(Collectors.toCollection(java.util.TreeSet::new));
    Set<String> actual;
    try (Stream<Path> paths = Files.list(samplesDirectory)) {
      actual = paths.filter(Files::isDirectory)
          .filter(path -> Files.isRegularFile(path.resolve("metadata.json")))
          .map(path -> path.getFileName().toString())
          .collect(Collectors.toCollection(java.util.TreeSet::new));
    }
    if (!expected.equals(actual)) {
      throw new IOException("Repository/sample directory mismatch: repos="
          + expected + ", samples=" + actual);
    }
    int total = 0;
    for (String repo : actual) {
      Path sampleDirectory = samplesDirectory.resolve(repo);
      Set<String> sampleIds = new HashSet<>();
      Map<String, Long> counts = new LinkedHashMap<>();
      for (int task = 1; task <= 3; task++) {
        Path taskFile = sampleDirectory.resolve(task + ".json");
        Object value = Files.isRegularFile(taskFile)
            ? Json.parse(Files.readString(taskFile, StandardCharsets.UTF_8)) : List.of();
        if (!(value instanceof List<?> samples)) {
          throw new IOException("Expected JSON array: " + taskFile);
        }
        for (int index = 0; index < samples.size(); index++) {
          if (!(samples.get(index) instanceof Map<?, ?> sample)) {
            throw new IOException("Expected sample object: " + taskFile + " index " + index);
          }
          validateSampleIdentityAndKinds(sample, task, taskFile, index, sampleIds);
          if (!repo.equals(sample.get("repo"))
              || !(sample.get("repo_tag") instanceof String tag) || tag.isBlank()) {
            throw new IOException("Invalid repository identity: " + taskFile + " index " + index);
          }
          validateRelativePath(sample.get("source_path"), taskFile, index);
          validateRelativePath(sample.get("target_path"), taskFile, index);
          validateRelativePaths(sample.get("target_paths"), taskFile, index);
          Object targetFiles = sample.get("target_files");
          if (targetFiles instanceof List<?> files) {
            for (Object file : files) {
              if (file instanceof Map<?, ?> targetFile) {
                validateRelativePath(targetFile.get("source_path"), taskFile, index);
              }
            }
          }
        }
        counts.put("task" + task, (long) samples.size());
      }
      long repositoryTotal = counts.values().stream().mapToLong(Long::longValue).sum();
      counts.put("total", repositoryTotal);
      Object metadataValue = Json.parse(Files.readString(
          sampleDirectory.resolve("metadata.json"), StandardCharsets.UTF_8));
      if (!(metadataValue instanceof Map<?, ?> metadata) || metadata.size() != 4) {
        throw new IOException("Invalid metadata: " + sampleDirectory.resolve("metadata.json"));
      }
      for (Map.Entry<String, Long> entry : counts.entrySet()) {
        Object value = metadata.get(entry.getKey());
        if (!(value instanceof Number number) || number.longValue() != entry.getValue()) {
          throw new IOException("Metadata mismatch: " + sampleDirectory.resolve("metadata.json"));
        }
      }
      total += Math.toIntExact(repositoryTotal);
    }
    if (actual.isEmpty() || total <= 0) {
      throw new IOException("Refusing to replace samples: rebuild produced no valid samples");
    }
    System.out.println("Validated " + actual.size() + " repositories and " + total + " samples");
  }

  private static void validateSampleIdentityAndKinds(
      Map<?, ?> sample,
      int task,
      Path file,
      int index,
      Set<String> sampleIds
  ) throws IOException {
    String expectedTask = "difficulty" + task;
    if (!expectedTask.equals(sample.get("tag")) || !expectedTask.equals(sample.get("task"))) {
      throw new IOException("Invalid sample task identity: " + file + " index " + index);
    }
    Object idValue = sample.get("id");
    if (!(idValue instanceof String id) || id.isBlank() || !sampleIds.add(id)) {
      throw new IOException("Missing or duplicate sample id: " + file + " index " + index);
    }
    Object completionKind = sample.get("completion_kind");
    Object astNodeKind = sample.get("ast_node_kind");
    if (task == 1 && "lines".equals(completionKind)
        && !"LINE_RANGE".equals(astNodeKind)) {
      throw new IOException("Line sample must use LINE_RANGE: " + file + " index " + index);
    }
    if (task == 2 && "function_body".equals(completionKind)
        && !"FUNCTION_BODY".equals(astNodeKind)) {
      throw new IOException(
          "Function-body sample must use FUNCTION_BODY: " + file + " index " + index);
    }
  }

  private static void validateRelativePaths(Object value, Path file, int index) throws IOException {
    if (!(value instanceof List<?> paths)) return;
    for (Object path : paths) validateRelativePath(path, file, index);
  }

  private static void validateRelativePath(Object value, Path file, int index) throws IOException {
    if (!(value instanceof String string) || string.isBlank()) return;
    Path relative = Path.of(string.replace('/', java.io.File.separatorChar));
    if (relative.isAbsolute()) {
      throw new IOException("Path must be relative to repository root: " + file + " index " + index);
    }
    for (Path part : relative) {
      if (part.toString().equals("..")) {
        throw new IOException("Path escapes repository root: " + file + " index " + index);
      }
    }
  }

  private static void createTask3Archive(Path samplesDirectory, Path archive) throws IOException {
    Files.createDirectories(archive.getParent());
    Path temporary = archive.resolveSibling(archive.getFileName() + ".tmp");
    Files.deleteIfExists(temporary);
    int entries = 0;
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(temporary))) {
      List<Path> sampleDirectories;
      try (Stream<Path> paths = Files.list(samplesDirectory)) {
        sampleDirectories = paths.filter(Files::isDirectory).sorted().toList();
      }
      for (Path sampleDirectory : sampleDirectories) {
        Path taskFile = sampleDirectory.resolve("3.json");
        if (!Files.isRegularFile(taskFile)) continue;
        String name = sampleDirectory.getFileName() + "/3.json";
        output.putNextEntry(new ZipEntry(name));
        Files.copy(taskFile, output);
        output.closeEntry();
        entries++;
      }
    } catch (IOException exception) {
      Files.deleteIfExists(temporary);
      throw exception;
    }
    if (entries == 0 || Files.size(temporary) == 0) {
      Files.deleteIfExists(temporary);
      throw new IOException("Task-3 draft archive has no entries");
    }
    try {
      Files.move(temporary, archive, StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
      Files.move(temporary, archive, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static String gitHead(Path repoRoot) {
    try {
      Process process = new ProcessBuilder("git", "-C", repoRoot.toString(), "rev-parse", "HEAD")
          .redirectErrorStream(true)
          .start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      return process.waitFor() == 0 ? output : "";
    } catch (IOException exception) {
      return "";
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return "";
    }
  }

  private static List<Path> findJavaFiles(Path repoRoot, List<String> marker) throws IOException {
    try (Stream<Path> paths = Files.walk(repoRoot, FileVisitOption.FOLLOW_LINKS)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".java"))
          .filter(path -> containsPathSequence(repoRoot.relativize(path), marker))
          .filter(path -> !containsDirectory(path, ".git"))
          .filter(path -> !containsDirectory(path, "target"))
          .filter(path -> !containsDirectory(path, "build"))
          .sorted()
          .toList();
    }
  }

  private static boolean containsPathSequence(Path path, List<String> sequence) {
    List<String> names = new ArrayList<>();
    for (Path part : path) {
      names.add(part.toString());
    }
    for (int start = 0; start <= names.size() - sequence.size(); start++) {
      boolean matches = true;
      for (int index = 0; index < sequence.size(); index++) {
        if (!names.get(start + index).equals(sequence.get(index))) {
          matches = false;
          break;
        }
      }
      if (matches) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsDirectory(Path path, String directoryName) {
    for (Path part : path) {
      if (part.toString().equals(directoryName)) {
        return true;
      }
    }
    return false;
  }

  private static List<SourceUnit> parseSources(List<Path> files, Path repoRoot, SourceKind kind)
      throws IOException {
    if (files.isEmpty()) {
      return List.of();
    }
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException("No JDK compiler found. Run this tool with a JDK, not a JRE.");
    }

    Map<Path, String> sourceByPath = new HashMap<>();
    for (Path file : files) {
      sourceByPath.put(file.toAbsolutePath().normalize(), Files.readString(file, StandardCharsets.UTF_8));
    }

    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
        diagnostics, Locale.ROOT, StandardCharsets.UTF_8
    )) {
      Iterable<? extends JavaFileObject> javaFileObjects = fileManager.getJavaFileObjectsFromPaths(files);
      List<String> options = List.of("-proc:none", "-Xlint:none");
      JavacTask task = (JavacTask) compiler.getTask(
          null, fileManager, diagnostics, options, null, javaFileObjects
      );
      Iterable<? extends CompilationUnitTree> compilationUnits = task.parse();
      Trees trees = Trees.instance(task);
      DocTrees docTrees = DocTrees.instance(task);
      SourcePositions positions = trees.getSourcePositions();

      List<SourceUnit> units = new ArrayList<>();
      for (CompilationUnitTree unitTree : compilationUnits) {
        Path sourcePath = pathFromUri(unitTree.getSourceFile().toUri()).toAbsolutePath().normalize();
        String source = sourceByPath.get(sourcePath);
        if (source == null) {
          continue;
        }
        String relativePath = toForwardSlashes(repoRoot.relativize(sourcePath));
        String packageName = unitTree.getPackageName() == null ? "" : unitTree.getPackageName().toString();
        SourceUnit unit = new SourceUnit(sourcePath, relativePath, source, packageName, kind);
        if (kind == SourceKind.MAIN) {
          MainCollector collector = new MainCollector(unit, unitTree, positions, docTrees);
          collector.scan(unitTree, null);
        } else {
          TestCollector collector = new TestCollector(unit, unitTree, positions);
          collector.scan(unitTree, null);
        }
        units.add(unit);
      }
      printParseWarnings(diagnostics, kind);
      return units;
    }
  }

  private static Path pathFromUri(URI uri) {
    return Paths.get(uri);
  }

  private static void printParseWarnings(
      DiagnosticCollector<JavaFileObject> diagnostics,
      SourceKind kind
  ) {
    long errors = diagnostics.getDiagnostics().stream()
        .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
        .count();
    if (errors > 0) {
      System.err.println("Parsed " + kind + " sources with " + errors + " syntax diagnostic(s).");
    }
  }

  private static String sampleId(String task, MethodInfo method, Span span) {
    String seed = task + "|" + method.unit().relativePath() + "|" + method.symbol()
        + "|" + span.start() + "|" + span.end();
    return task.replace(".", "_") + "_" + sha256(seed).substring(0, 16);
  }

  private static String sampleId(String task, MethodInfo method, Span span, String idSalt) {
    if (idSalt == null || idSalt.isBlank()) {
      return sampleId(task, method, span);
    }
    String seed = task + "|" + method.unit().relativePath() + "|" + method.symbol()
        + "|" + span.start() + "|" + span.end() + "|" + idSalt;
    return task.replace(".", "_") + "_" + sha256(seed).substring(0, 16);
  }

  private static String sha256(String value) {
    return sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256(byte[] value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(value);
      StringBuilder builder = new StringBuilder(bytes.length * 2);
      for (byte aByte : bytes) {
        builder.append(String.format("%02x", aByte));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String jsonObject(Map<String, ?> values) {
    return values.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> Json.quote(entry.getKey()) + ":" + jsonValue(entry.getValue()))
        .collect(Collectors.joining(",", "{", "}"));
  }

  private static String jsonValue(Object value) {
    if (value == null) return "null";
    if (value instanceof RawJson rawJson) {
      return rawJson.value();
    }
    if (value instanceof String stringValue) {
      return Json.quote(stringValue);
    }
    if (value instanceof Number || value instanceof Boolean) {
      return String.valueOf(value);
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> normalized = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        normalized.put(String.valueOf(entry.getKey()), entry.getValue());
      }
      return jsonObject(normalized);
    }
    if (value instanceof Iterable<?> iterable) {
      List<String> items = new ArrayList<>();
      for (Object item : iterable) items.add(jsonValue(item));
      return items.stream().collect(Collectors.joining(",", "[", "]"));
    }
    throw new IllegalArgumentException("Unsupported JSON value: " + value);
  }

  private static String toForwardSlashes(Path path) {
    return path.toString().replace('\\', '/');
  }

  private static String sanitizeDirectoryName(String label) {
    return label.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private enum SourceKind {
    MAIN,
    TEST
  }

  private record RawJson(String value) {
  }

  private record RepositoryResult(
      String repoName,
      String repoLabel,
      Path outputDirectory,
      int difficulty1Count,
      int difficulty2Count,
      int difficulty3Count
  ) {
    int totalCount() {
      return difficulty1Count + difficulty2Count + difficulty3Count;
    }
  }

  private record EligibleTarget(
      List<TestDescriptor> tests,
      CoverageAnalyzer.Coverage coverage
  ) {
  }

  private record Task3Candidate(
      ClassInfo classInfo,
      EligibleTarget eligible,
      CoverageAnalyzer.Coverage coverage,
      boolean onlySftTrain
  ) {
  }

  private record SkeletonRecord(
      String repo,
      String repoTag,
      String sourcePath,
      String packageName,
      List<String> declaredTypes,
      List<String> imports,
      List<String> referencedTypes,
      String sourceSha256,
      String skeleton
  ) {
    static SkeletonRecord from(Map<?, ?> map) {
      return new SkeletonRecord(
          requiredString(map, "repo"), requiredString(map, "repo_tag"),
          requiredString(map, "source_path"), requiredString(map, "package"),
          stringList(map.get("declared_types")), stringList(map.get("imports")),
          stringList(map.get("referenced_types")), requiredString(map, "source_sha256"),
          requiredString(map, "skeleton"));
    }

    private static String requiredString(Map<?, ?> map, String key) {
      Object value = map.get(key);
      if (!(value instanceof String string)) {
        throw new IllegalArgumentException("Missing skeleton string field: " + key);
      }
      return string;
    }

    private static List<String> stringList(Object value) {
      if (!(value instanceof List<?> list)) return List.of();
      List<String> result = new ArrayList<>();
      for (Object item : list) {
        if (item instanceof String string) result.add(string);
      }
      return List.copyOf(result);
    }
  }

  private record Arguments(
      Path reposDirectory,
      Path samplesDirectory,
      Path skeletonsDirectory,
      Path archive,
      Path repositoryCatalog,
      Set<Integer> tasks,
      boolean mix,
      boolean cover
  ) {
    static Arguments parse(String[] args) {
      Path reposDirectory = null;
      Path samplesDirectory = null;
      Path skeletonsDirectory = null;
      Path archive = null;
      Path repositoryCatalog = null;
      Set<Integer> tasks = new LinkedHashSet<>(List.of(1, 2, 3));
      boolean mix = false;
      boolean cover = false;
      for (int index = 0; index < args.length; index++) {
        String argument = args[index];
        if ("--repos".equals(argument) && index + 1 < args.length) {
          reposDirectory = Paths.get(args[++index]);
        } else if ("--samples".equals(argument) && index + 1 < args.length) {
          samplesDirectory = Paths.get(args[++index]);
        } else if ("--skeletons".equals(argument) && index + 1 < args.length) {
          skeletonsDirectory = Paths.get(args[++index]);
        } else if ("--archive".equals(argument) && index + 1 < args.length) {
          archive = Paths.get(args[++index]);
        } else if ("--repository-catalog".equals(argument) && index + 1 < args.length) {
          repositoryCatalog = Paths.get(args[++index]);
        } else if ("--tasks".equals(argument) && index + 1 < args.length) {
          tasks = parseTasks(args[++index]);
        } else if ("--mix".equals(argument)) {
          mix = true;
        } else if ("--cover".equals(argument)) {
          cover = true;
        } else {
          throw new IllegalArgumentException("Unknown or incomplete argument: " + argument);
        }
      }
      if (reposDirectory == null || samplesDirectory == null) {
        throw new IllegalArgumentException(
                "Usage: --repos <repositories-directory> --samples <samples-directory> "
                + "[--skeletons <skeletons-directory> --archive <zip> "
                + "--repository-catalog <json>] [--tasks 1,2,3] [--mix] [--cover]"
        );
      }
      return new Arguments(
          reposDirectory, samplesDirectory, skeletonsDirectory, archive, repositoryCatalog,
          tasks, mix, cover);
    }

    private static Set<Integer> parseTasks(String value) {
      Set<Integer> result = new LinkedHashSet<>();
      for (String part : value.split(",")) {
        String normalized = part.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("difficulty")) normalized = normalized.substring("difficulty".length());
        int task;
        try {
          task = Integer.parseInt(normalized);
        } catch (NumberFormatException exception) {
          throw new IllegalArgumentException("Tasks must be a comma-separated subset of 1,2,3", exception);
        }
        if (task < 1 || task > 3) {
          throw new IllegalArgumentException("Tasks must be a comma-separated subset of 1,2,3");
        }
        result.add(task);
      }
      if (result.isEmpty()) throw new IllegalArgumentException("At least one task must be selected");
      return Set.copyOf(result);
    }
  }

  private record Span(int start, int end) {
    boolean isValid() {
      return start >= 0 && end >= start;
    }
  }

  private record PhysicalLine(Span span, boolean eligible) {
  }

  private record AstCandidate(
      Span span, Tree.Kind treeKind, String completionKind, boolean apiCall) {
  }

  private record SourceUnit(
      Path sourcePath,
      String relativePath,
      String source,
      String packageName,
      SourceKind kind,
      List<ClassInfo> classes,
      List<MethodInfo> methods,
      List<TestCall> testCalls
  ) {
    SourceUnit(Path sourcePath, String relativePath, String source, String packageName, SourceKind kind) {
      this(sourcePath, relativePath, source, packageName, kind,
          new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }
  }

  /** Identity-based class metadata; record equality would recurse through SourceUnit.classes(). */
  private static final class ClassInfo {
    private final SourceUnit unit;
    private final String name;
    private final String fqn;
    private final int start;
    private final int end;

    ClassInfo(SourceUnit unit, String name, String fqn, int start, int end) {
      this.unit = unit;
      this.name = name;
      this.fqn = fqn;
      this.start = start;
      this.end = end;
    }

    SourceUnit unit() {
      return unit;
    }

    String name() {
      return name;
    }

    String fqn() {
      return fqn;
    }

    boolean hasUsableSpan() {
      return !name.isBlank() && start >= 0 && end >= start && end <= unit.source().length();
    }

    String coverageKey() {
      return unit.relativePath() + "|" + start + "|" + end;
    }

    CoverageAnalyzer.Target asCoverageTarget() {
      return new CoverageAnalyzer.Target(
          coverageKey(), unit.relativePath(),
          lineNumber(unit.source(), start), lineNumber(unit.source(), Math.max(start, end - 1)));
    }
  }

  private static final class MethodInfo {
    private final SourceUnit unit;
    private final String ownerName;
    private final String ownerFqn;
    private final String methodName;
    private final int start;
    private final int end;
    private final Span bodySpan;
    private final String javadoc;
    private final boolean ordinaryMethod;
    private final int parameterCount;
    private final List<Span> methodCalls = new ArrayList<>();
    private final List<AstCandidate> astCandidates = new ArrayList<>();

    MethodInfo(
        SourceUnit unit,
        String ownerName,
        String ownerFqn,
        String methodName,
        int start,
        int end,
        Span bodySpan,
        String javadoc,
        boolean ordinaryMethod,
        int parameterCount
    ) {
      this.unit = unit;
      this.ownerName = ownerName;
      this.ownerFqn = ownerFqn;
      this.methodName = methodName;
      this.start = start;
      this.end = end;
      this.bodySpan = bodySpan;
      this.javadoc = javadoc;
      this.ordinaryMethod = ordinaryMethod;
      this.parameterCount = parameterCount;
    }

    SourceUnit unit() {
      return unit;
    }

    String ownerName() {
      return ownerName;
    }

    String ownerFqn() {
      return ownerFqn;
    }

    String methodName() {
      return methodName;
    }

    int start() {
      return start;
    }

    int end() {
      return end;
    }

    Span bodySpan() {
      return bodySpan;
    }

    String javadoc() {
      return javadoc;
    }

    List<Span> methodCalls() {
      return methodCalls;
    }

    List<AstCandidate> astCandidates() {
      return astCandidates;
    }

    boolean hasBody() {
      return bodySpan != null;
    }

    boolean hasUsableSpan() {
      return start >= 0 && end >= start && end <= unit.source().length();
    }

    boolean isOrdinaryMethod() {
      return ordinaryMethod;
    }

    int parameterCount() {
      return parameterCount;
    }

    boolean contains(Span span) {
      return span.isValid() && start <= span.start() && span.end() <= end;
    }

    String symbol() {
      return ownerFqn + "#" + methodName;
    }

    String coverageKey() {
      return unit.relativePath() + "|" + start + "|" + end;
    }

    CoverageAnalyzer.Target asCoverageTarget() {
      int firstLine = lineNumber(unit.source(), bodySpan == null ? start : bodySpan.start());
      int lastLine = lineNumber(unit.source(), bodySpan == null ? end : bodySpan.end());
      return new CoverageAnalyzer.Target(coverageKey(), unit.relativePath(), firstLine, lastLine);
    }
  }

  private static final class MainCollector extends TreePathScanner<Void, Void> {
    private final SourceUnit unit;
    private final CompilationUnitTree compilationUnit;
    private final SourcePositions positions;
    private final DocTrees docTrees;
    private final Deque<String> classNames = new ArrayDeque<>();
    private final Deque<MethodInfo> methodStack = new ArrayDeque<>();

    MainCollector(
        SourceUnit unit,
        CompilationUnitTree compilationUnit,
        SourcePositions positions,
        DocTrees docTrees
    ) {
      this.unit = unit;
      this.compilationUnit = compilationUnit;
      this.positions = positions;
      this.docTrees = docTrees;
    }

    @Override
    public Void visitClass(ClassTree node, Void unused) {
      String name = node.getSimpleName().toString();
      if (classNames.isEmpty() && !name.isBlank()) {
        int start = safePosition(positions.getStartPosition(compilationUnit, node));
        int end = safePosition(positions.getEndPosition(compilationUnit, node));
        String fqn = unit.packageName().isBlank() ? name : unit.packageName() + "." + name;
        unit.classes().add(new ClassInfo(unit, name, fqn, start, end));
      }
      classNames.push(name.isBlank() ? "Anonymous" : name);
      try {
        return super.visitClass(node, unused);
      } finally {
        classNames.pop();
      }
    }

    @Override
    public Void visitMethod(MethodTree node, Void unused) {
      BlockTree body = node.getBody();
      long start = positions.getStartPosition(compilationUnit, node);
      long end = positions.getEndPosition(compilationUnit, node);
      Span bodySpan = spanOf(body);
      String ownerName = classNames.isEmpty() ? "" : classNames.peek();
      String ownerFqn = ownerFqn();
      DocCommentTree docComment = docTrees.getDocCommentTree(getCurrentPath());
      String javadoc = docComment == null ? "" : docComment.toString().trim();
      boolean ordinaryMethod = node.getReturnType() != null && !ownerName.equals("Anonymous");
      MethodInfo method = new MethodInfo(
          unit,
          ownerName,
          ownerFqn,
          node.getName().toString(),
          safePosition(start),
          safePosition(end),
          bodySpan,
          javadoc,
          ordinaryMethod,
          node.getParameters().size()
      );
      unit.methods().add(method);
      methodStack.push(method);
      try {
        return super.visitMethod(node, unused);
      } finally {
        methodStack.pop();
      }
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
      if (!methodStack.isEmpty()) {
        Span span = spanOf(node);
        if (span != null) {
          methodStack.peek().methodCalls().add(span);
        }
      }
      return super.visitMethodInvocation(node, unused);
    }

    @Override
    public Void scan(Tree node, Void unused) {
      if (node != null && !methodStack.isEmpty() && isTask2Candidate(node)) {
        Span span = spanOf(node);
        if (span != null) {
          methodStack.peek().astCandidates().add(new AstCandidate(
              span, node.getKind(), completionKind(node), isDirectApiCall(node)));
        }
      }
      return super.scan(node, unused);
    }

    private boolean isDirectApiCall(Tree node) {
      if (node instanceof MethodInvocationTree) return true;
      if (!(node instanceof ExpressionStatementTree statement)) return false;
      Tree expression = statement.getExpression();
      while (expression instanceof ParenthesizedTree parenthesized) {
        expression = parenthesized.getExpression();
      }
      return expression instanceof MethodInvocationTree;
    }

    private boolean isTask2Candidate(Tree node) {
      if (node instanceof BlockTree) return true;
      if (node instanceof StatementTree) {
        return switch (node.getKind()) {
          case ASSERT, BREAK, CONTINUE, EMPTY_STATEMENT, EXPRESSION_STATEMENT,
              RETURN, THROW, VARIABLE, YIELD -> true;
          default -> false;
        };
      }
      return node instanceof ExpressionTree
          && !(node instanceof IdentifierTree)
          && !(node instanceof MemberSelectTree);
    }

    private String completionKind(Tree node) {
      if (node instanceof BlockTree) return "block";
      if (node instanceof StatementTree) return "statement";
      return "expression";
    }

    private String ownerFqn() {
      if (classNames.isEmpty()) {
        return unit.packageName();
      }
      List<String> names = new ArrayList<>(classNames);
      java.util.Collections.reverse(names);
      String classPart = String.join(".", names);
      return unit.packageName().isBlank() ? classPart : unit.packageName() + "." + classPart;
    }

    private Span spanOf(Tree tree) {
      if (tree == null) {
        return null;
      }
      long start = positions.getStartPosition(compilationUnit, tree);
      long end = positions.getEndPosition(compilationUnit, tree);
      int safeStart = safePosition(start);
      int safeEnd = safePosition(end);
      if (safeStart < 0 || safeEnd < safeStart || safeEnd > unit.source().length()) {
        return null;
      }
      return new Span(safeStart, safeEnd);
    }
  }

  private static final class TestCollector extends TreePathScanner<Void, Void> {
    private final SourceUnit unit;
    private final CompilationUnitTree compilationUnit;
    private final SourcePositions positions;
    private final Deque<String> classNames = new ArrayDeque<>();
    private final Deque<String> testMethodStack = new ArrayDeque<>();
    private final Map<String, String> variableTypes = new HashMap<>();
    private final Set<String> imports = new LinkedHashSet<>();

    TestCollector(SourceUnit unit, CompilationUnitTree compilationUnit, SourcePositions positions) {
      this.unit = unit;
      this.compilationUnit = compilationUnit;
      this.positions = positions;
      compilationUnit.getImports().forEach(importTree -> imports.add(importTree.getQualifiedIdentifier().toString()));
    }

    @Override
    public Void visitClass(ClassTree node, Void unused) {
      String name = node.getSimpleName().toString();
      classNames.push(name.isBlank() ? "Anonymous" : name);
      try {
        return super.visitClass(node, unused);
      } finally {
        classNames.pop();
      }
    }

    @Override
    public Void visitVariable(VariableTree node, Void unused) {
      if (node.getType() != null) {
        variableTypes.put(node.getName().toString(), simpleTypeName(node.getType().toString()));
      }
      return super.visitVariable(node, unused);
    }

    @Override
    public Void visitAssignment(AssignmentTree node, Void unused) {
      if (node.getVariable() instanceof IdentifierTree identifier && node.getExpression() instanceof NewClassTree created) {
        variableTypes.put(identifier.getName().toString(), simpleTypeName(created.getIdentifier().toString()));
      }
      return super.visitAssignment(node, unused);
    }

    @Override
    public Void visitMethod(MethodTree node, Void unused) {
      String testMethodName = hasTestAnnotation(node) ? node.getName().toString() : "";
      testMethodStack.push(testMethodName);
      try {
        return super.visitMethod(node, unused);
      } finally {
        testMethodStack.pop();
      }
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
      if (!testMethodStack.isEmpty() && !testMethodStack.peek().isBlank()) {
        TestCall call = asTestCall(node);
        if (call != null) {
          unit.testCalls().add(call);
        }
      }
      return super.visitMethodInvocation(node, unused);
    }

    @Override
    public Void visitNewClass(NewClassTree node, Void unused) {
      if (!testMethodStack.isEmpty() && !testMethodStack.peek().isBlank()) {
        String receiverType = simpleTypeName(node.getIdentifier().toString());
        if (!receiverType.isBlank()) {
          unit.testCalls().add(new TestCall(
              unit.relativePath(), testClassFqn(), testMethodStack.peek(),
              unit.packageName(), imports, "<init>", receiverType,
              node.getArguments().size()));
        }
      }
      return super.visitNewClass(node, unused);
    }

    private TestCall asTestCall(MethodInvocationTree node) {
      Tree select = node.getMethodSelect();
      String methodName;
      String receiverType;
      if (select instanceof MemberSelectTree memberSelect) {
        methodName = memberSelect.getIdentifier().toString();
        receiverType = resolveReceiverType(memberSelect.getExpression());
      } else if (select instanceof IdentifierTree identifier) {
        methodName = identifier.getName().toString();
        receiverType = classNames.isEmpty() ? "" : classNames.peek();
      } else {
        return null;
      }
      if (receiverType.isBlank()) {
        return null;
      }
      return new TestCall(
          unit.relativePath(),
          testClassFqn(),
          testMethodStack.peek(),
          unit.packageName(),
          imports,
          methodName,
          receiverType,
          node.getArguments().size()
      );
    }

    private String resolveReceiverType(ExpressionTree receiver) {
      if (receiver instanceof IdentifierTree identifier) {
        String name = identifier.getName().toString();
        return variableTypes.getOrDefault(name, simpleTypeName(name));
      }
      if (receiver instanceof NewClassTree created) {
        return simpleTypeName(created.getIdentifier().toString());
      }
      if (receiver instanceof MemberSelectTree memberSelect) {
        return simpleTypeName(memberSelect.toString());
      }
      return "";
    }

    private boolean hasTestAnnotation(MethodTree method) {
      for (AnnotationTree annotation : method.getModifiers().getAnnotations()) {
        String annotationName = annotation.getAnnotationType().toString();
        if (annotationName.endsWith("Test")) {
          return true;
        }
      }
      return false;
    }

    private String testClassFqn() {
      if (classNames.isEmpty()) {
        return unit.packageName();
      }
      List<String> names = new ArrayList<>(classNames);
      java.util.Collections.reverse(names);
      String binaryClassName = String.join("$", names);
      return unit.packageName().isBlank() ? binaryClassName : unit.packageName() + "." + binaryClassName;
    }
  }

  private record TestCall(
      String testPath,
      String testClass,
      String testMethod,
      String testPackage,
      Set<String> imports,
      String methodName,
      String receiverType,
      int argumentCount
  ) {
    boolean directlyCalls(MethodInfo method) {
      if (!methodName.equals(method.methodName())
          || !receiverType.equals(method.ownerName())
          || argumentCount != method.parameterCount()) {
        return false;
      }
      if (testPackage.equals(packageNameOf(method.ownerFqn()))) {
        return true;
      }
      return imports.contains(method.ownerFqn())
          || imports.contains(packageNameOf(method.ownerFqn()) + ".*");
    }

    boolean callsClass(ClassInfo classInfo) {
      if (!receiverType.equals(classInfo.name())) return false;
      if (testPackage.equals(packageNameOf(classInfo.fqn()))) return true;
      return imports.contains(classInfo.fqn())
          || imports.contains(packageNameOf(classInfo.fqn()) + ".*");
    }
  }

  private record TestDescriptor(
      String testPath,
      String testClass,
      String testMethod,
      String testWorkdir,
      String testCommand,
      CoverageAnalyzer.Coverage coverage
  ) {
    static Optional<TestDescriptor> create(Path repoRoot, TestCall call) {
      Path testPath = repoRoot.resolve(call.testPath()).normalize();
      return JavaBuildTool.testCommand(
              repoRoot, testPath, call.testClass(), call.testMethod())
          .map(build -> new TestDescriptor(
              call.testPath(), call.testClass(), call.testMethod(),
              build.workdir(), build.command(), null));
    }

    CoverageAnalyzer.TestSpec asCoverageSpec() {
      return new CoverageAnalyzer.TestSpec(
          testPath, testClass, testMethod, testWorkdir, testCommand);
    }

    String coverageKey() {
      return asCoverageSpec().key();
    }

    Optional<TestDescriptor> withCoverage(CoverageAnalyzer.Coverage measured) {
      if (measured == null) return Optional.empty();
      return Optional.of(new TestDescriptor(
          testPath, testClass, testMethod, testWorkdir, testCommand, measured));
    }

    TestDescriptor withoutCoverage() {
      return new TestDescriptor(testPath, testClass, testMethod, testWorkdir, testCommand,
          CoverageAnalyzer.Coverage.empty());
    }

  }

  private static String packageNameOf(String typeFqn) {
    int lastDot = typeFqn.lastIndexOf('.');
    return lastDot < 0 ? "" : typeFqn.substring(0, lastDot);
  }

  private static String simpleTypeName(String type) {
    String withoutGenerics = type.replaceAll("<.*>", "");
    int lastDot = withoutGenerics.lastIndexOf('.');
    String lastPart = lastDot >= 0 ? withoutGenerics.substring(lastDot + 1) : withoutGenerics;
    return lastPart.replace("[]", "").trim();
  }

  private static int safePosition(long position) {
    return position < 0 || position > Integer.MAX_VALUE ? -1 : (int) position;
  }

  private static int lineNumber(String source, int offset) {
    int safeOffset = Math.max(0, Math.min(offset, source.length()));
    int line = 1;
    for (int index = 0; index < safeOffset; index++) {
      if (source.charAt(index) == '\n') line++;
    }
    return line;
  }

  private static final class JsonArrayWriter implements AutoCloseable {
    private final java.io.BufferedWriter writer;
    private boolean first = true;

    JsonArrayWriter(Path destination) throws IOException {
      writer = Files.newBufferedWriter(destination, StandardCharsets.UTF_8);
      writer.write("[\n");
    }

    void writeObject(String object) throws IOException {
      if (!first) {
        writer.write(",\n");
      }
      writer.write(object);
      first = false;
    }

    @Override
    public void close() throws IOException {
      writer.write("\n]\n");
      writer.close();
    }
  }

  private static final class Json {
    private Json() {
    }

    static String quote(String value) {
      StringBuilder escaped = new StringBuilder(value.length() + 16);
      escaped.append('"');
      for (int index = 0; index < value.length(); index++) {
        char character = value.charAt(index);
        switch (character) {
          case '"' -> escaped.append("\\\"");
          case '\\' -> escaped.append("\\\\");
          case '\b' -> escaped.append("\\b");
          case '\f' -> escaped.append("\\f");
          case '\n' -> escaped.append("\\n");
          case '\r' -> escaped.append("\\r");
          case '\t' -> escaped.append("\\t");
          default -> {
            if (character < 0x20) {
              escaped.append(String.format("\\u%04x", (int) character));
            } else {
              escaped.append(character);
            }
          }
        }
      }
      escaped.append('"');
      return escaped.toString();
    }

    static Object parse(String value) {
      Parser parser = new Parser(value);
      Object result = parser.value();
      parser.whitespace();
      if (!parser.atEnd()) throw parser.error("Trailing JSON content");
      return result;
    }

    private static final class Parser {
      private final String text;
      private int index;

      Parser(String text) {
        this.text = text;
      }

      Object value() {
        whitespace();
        if (atEnd()) throw error("Expected a JSON value");
        return switch (text.charAt(index)) {
          case '{' -> object();
          case '[' -> array();
          case '"' -> string();
          case 't' -> literal("true", Boolean.TRUE);
          case 'f' -> literal("false", Boolean.FALSE);
          case 'n' -> literal("null", null);
          default -> number();
        };
      }

      Map<String, Object> object() {
        expect('{');
        Map<String, Object> result = new LinkedHashMap<>();
        whitespace();
        if (consume('}')) return result;
        while (true) {
          whitespace();
          if (atEnd() || text.charAt(index) != '"') throw error("Expected object key");
          String key = string();
          whitespace();
          expect(':');
          result.put(key, value());
          whitespace();
          if (consume('}')) return result;
          expect(',');
        }
      }

      List<Object> array() {
        expect('[');
        List<Object> result = new ArrayList<>();
        whitespace();
        if (consume(']')) return result;
        while (true) {
          result.add(value());
          whitespace();
          if (consume(']')) return result;
          expect(',');
        }
      }

      String string() {
        expect('"');
        StringBuilder result = new StringBuilder();
        while (!atEnd()) {
          char character = text.charAt(index++);
          if (character == '"') return result.toString();
          if (character != '\\') {
            if (character < 0x20) throw error("Control character in string");
            result.append(character);
            continue;
          }
          if (atEnd()) throw error("Incomplete string escape");
          char escaped = text.charAt(index++);
          switch (escaped) {
            case '"', '\\', '/' -> result.append(escaped);
            case 'b' -> result.append('\b');
            case 'f' -> result.append('\f');
            case 'n' -> result.append('\n');
            case 'r' -> result.append('\r');
            case 't' -> result.append('\t');
            case 'u' -> result.append(unicode());
            default -> throw error("Invalid string escape");
          }
        }
        throw error("Unterminated string");
      }

      private char unicode() {
        if (index + 4 > text.length()) throw error("Incomplete unicode escape");
        int value = 0;
        for (int offset = 0; offset < 4; offset++) {
          int digit = Character.digit(text.charAt(index++), 16);
          if (digit < 0) throw error("Invalid unicode escape");
          value = value * 16 + digit;
        }
        return (char) value;
      }

      private Object number() {
        int start = index;
        if (consume('-')) {
          if (atEnd()) throw error("Invalid number");
        }
        if (consume('0')) {
          // A leading zero must stand alone before any fraction or exponent.
        } else {
          digits();
        }
        boolean floating = false;
        if (consume('.')) {
          floating = true;
          digits();
        }
        if (!atEnd() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
          floating = true;
          index++;
          if (!atEnd() && (text.charAt(index) == '+' || text.charAt(index) == '-')) index++;
          digits();
        }
        if (start == index) throw error("Expected a JSON value");
        String token = text.substring(start, index);
        try {
          return floating ? Double.parseDouble(token) : Long.parseLong(token);
        } catch (NumberFormatException exception) {
          throw error("Invalid number");
        }
      }

      private void digits() {
        int start = index;
        while (!atEnd() && Character.isDigit(text.charAt(index))) index++;
        if (start == index) throw error("Expected digit");
      }

      private Object literal(String expected, Object value) {
        if (!text.startsWith(expected, index)) throw error("Invalid JSON literal");
        index += expected.length();
        return value;
      }

      void whitespace() {
        while (!atEnd() && Character.isWhitespace(text.charAt(index))) index++;
      }

      boolean consume(char expected) {
        if (!atEnd() && text.charAt(index) == expected) {
          index++;
          return true;
        }
        return false;
      }

      void expect(char expected) {
        if (!consume(expected)) throw error("Expected '" + expected + "'");
      }

      boolean atEnd() {
        return index >= text.length();
      }

      IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at character " + index);
      }
    }
  }

  private static final class GitHubLabel {
    private static final Pattern HTTPS_URL = Pattern.compile("github\\.com[/:]([^/]+)/([^/\\s]+)");

    private GitHubLabel() {
    }

    static String fromRepository(Path repoRoot) {
      Path gitPath = repoRoot.resolve(".git");
      Path config = resolveGitConfig(gitPath);
      if (config != null && Files.isRegularFile(config)) {
        try {
          String text = Files.readString(config, StandardCharsets.UTF_8);
          Matcher matcher = HTTPS_URL.matcher(text);
          if (matcher.find()) {
            String owner = matcher.group(1);
            String repository = matcher.group(2).replaceAll("\\.git$", "");
            return owner + "/" + repository;
          }
        } catch (IOException ignored) {
          // Fall back to the local directory name.
        }
      }
      return repoRoot.getFileName().toString();
    }

    private static Path resolveGitConfig(Path gitPath) {
      if (Files.isDirectory(gitPath)) {
        return gitPath.resolve("config");
      }
      if (Files.isRegularFile(gitPath)) {
        try {
          String value = Files.readString(gitPath, StandardCharsets.UTF_8).trim();
          if (value.startsWith("gitdir:")) {
            Path gitDirectory = gitPath.getParent().resolve(value.substring("gitdir:".length()).trim())
                .normalize();
            return gitDirectory.resolve("config");
          }
        } catch (IOException ignored) {
          return null;
        }
      }
      return null;
    }
  }
}
