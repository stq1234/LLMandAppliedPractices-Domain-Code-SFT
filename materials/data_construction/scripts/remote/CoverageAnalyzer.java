import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Runs direct test cases and caches their JaCoCo line maps per repository. */
public final class CoverageAnalyzer {
  /** Set false to restore sampling without a line-coverage eligibility filter. */
  public static final boolean ENABLE_LINE_COVERAGE_FILTER = true;

  /** The union of related tests must cover strictly more than this fraction of target lines. */
  public static final double MINIMUM_LINE_COVERAGE = 0.50;

  /** Re-run tests whose current fingerprint has a cached failed status. */
  public static final boolean RETRY_FAILED_TASKS = false;

  /** When true, skip cache misses instead of running their test cases. */
  public static final boolean READ_ONLY_COVERAGE_CACHE = true;

  /** Intentionally distinctive: this cache belongs to the LowCode sampler, not the build tool. */
  public static final String CACHE_DIRECTORY = ".lowcode-ast-testcase-coverage-cache-v1";

  private static final String CACHE_SCHEMA = "1";
  private static final String JACOCO_VERSION = "0.8.14";
  private static final Set<String> BUILD_INPUT_NAMES = Set.of(
      "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
      "settings.gradle.kts", "gradle.properties", "gradle-wrapper.properties",
      "maven-wrapper.properties", "mvnw", "gradlew"
  );

  private CoverageAnalyzer() {
  }

  public record TestSpec(
      String testPath,
      String testClass,
      String testMethod,
      String testWorkdir,
      String testCommand
  ) {
    public String key() {
      return testWorkdir + "\u0000" + coverageCacheCommandIdentity(testCommand);
    }
  }

  private static String coverageCacheCommandIdentity(String command) {
    return command
        .replace(" -Dcheckstyle.skip=true", "")
        .replace(" -Dformatter.skip=true", "");
  }

  public record Target(String key, String sourcePath, int firstLine, int lastLine) {
  }

  public record Coverage(Set<Integer> coveredLineNumbers, Set<Integer> executableLineNumbers) {
    public Coverage {
      coveredLineNumbers = Set.copyOf(coveredLineNumbers);
      executableLineNumbers = Set.copyOf(executableLineNumbers);
    }

    public int coveredLines() {
      return coveredLineNumbers.size();
    }

    public int totalLines() {
      return executableLineNumbers.size();
    }

    public double ratio() {
      return totalLines() == 0 ? 0.0 : (double) coveredLines() / totalLines();
    }

    public boolean passesThreshold() {
      return ratio() > MINIMUM_LINE_COVERAGE;
    }

    public static Coverage empty() {
      return new Coverage(Set.of(), Set.of());
    }

    public static Coverage merge(Iterable<Coverage> values) {
      Set<Integer> covered = new HashSet<>();
      Set<Integer> executable = new HashSet<>();
      for (Coverage value : values) {
        if (value == null) continue;
        covered.addAll(value.coveredLineNumbers());
        executable.addAll(value.executableLineNumbers());
      }
      covered.retainAll(executable);
      return new Coverage(covered, executable);
    }
  }

  /**
   * Measures every statically related test case at most once and returns its per-target line sets.
   * Callers merge covered line-number sets across tests before applying the threshold.
   */
  public static Map<String, Map<String, Coverage>> measure(
      Path repoRoot,
      Path workingRoot,
      Map<TestSpec, List<Target>> targetsByTest,
      Map<TestSpec, List<Target>> legacyTargetsByTest
  ) throws IOException {
    if (targetsByTest.isEmpty()) return Map.of();
    Files.createDirectories(workingRoot);
    Path cacheRoot = repoRoot.resolve(CACHE_DIRECTORY);
    Files.createDirectories(cacheRoot);
    writeCacheReadme(cacheRoot);
    Path gradleInit = workingRoot.resolve("jacoco.init.gradle");
    Files.writeString(gradleInit, gradleInitScript(), StandardCharsets.UTF_8);

    String buildFingerprint = buildFingerprint(repoRoot);
    List<Measurement> measurements = new ArrayList<>();
    for (Map.Entry<TestSpec, List<Target>> entry : targetsByTest.entrySet()) {
      TestSpec test = entry.getKey();
      List<Target> originalTargets = legacyTargetsByTest.getOrDefault(test, List.of());
      List<Target> fingerprintTargets = originalTargets.isEmpty()
          ? entry.getValue() : originalTargets;
      String fingerprint = fingerprint(
          repoRoot, buildFingerprint, test, fingerprintTargets);
      Path cacheFile = cacheRoot.resolve("test-" + sha256(test.key()) + ".coverage.tsv");
      CacheEntry cache = readCache(cacheFile, fingerprint);
      measurements.add(new Measurement(
          test, List.copyOf(new LinkedHashSet<>(entry.getValue())),
          fingerprint, cacheFile, cache));
    }
    Map<String, Map<String, Coverage>> result = new LinkedHashMap<>();
    Set<String> brokenBuildRoots = new HashSet<>();
    Set<String> precompiledCommands = new HashSet<>();
    Map<String, Integer> consecutiveNoReport = new HashMap<>();
    int cacheHits = 0;
    int failedCacheHits = 0;
    int circuitSkipped = 0;
    int executed = 0;
    int index = 0;
    int totalMeasurements = measurements.size();
    while (!measurements.isEmpty()) {
      Measurement measurement = chooseNextMeasurement(measurements);
      measurements.remove(measurement);
      index++;
      TestSpec test = measurement.test();
      String buildRoot = test.testWorkdir();
      if (brokenBuildRoots.contains(buildRoot)) {
        circuitSkipped++;
        continue;
      }

      CacheEntry cache = measurement.cache();
      if (cache == null && READ_ONLY_COVERAGE_CACHE) {
        System.out.printf(Locale.ROOT, "  coverage %d/%d cache miss skipped: %s.%s%n",
            index, totalMeasurements, test.testClass(), test.testMethod());
        continue;
      }
      if (cache != null && cache.failed() && !RETRY_FAILED_TASKS) {
        failedCacheHits++;
        System.out.printf(Locale.ROOT, "  coverage %d/%d cached failure: %s.%s%n",
            index, totalMeasurements, test.testClass(), test.testMethod());
        continue;
      }
      Map<String, Map<Integer, Boolean>> lineMap =
          cache != null && !cache.failed() ? cache.lineMap() : null;
      if (lineMap != null) {
        cacheHits++;
        System.out.printf(Locale.ROOT, "  coverage %d/%d cache hit: %s.%s%n",
            index, totalMeasurements, test.testClass(), test.testMethod());
      } else {
        executed++;
        Path cwd = resolveWorkdir(repoRoot, test.testWorkdir());
        String precompileKey = test.testWorkdir() + "\u0000"
            + JavaBuildTool.precompileCommands(test.testCommand()).get(0);
        if (precompiledCommands.add(precompileKey)) {
          boolean prepared = false;
          int attempt = 0;
          for (String precompile : JavaBuildTool.precompileCommands(test.testCommand())) {
            attempt++;
            CommandResult build;
            try {
              build = run(precompile, cwd);
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
              throw new IOException("Coverage precompilation interrupted", exception);
            }
            Path buildLog = workingRoot.resolve(
                "precompile-" + index + "-attempt-" + attempt + ".log");
            Files.writeString(buildLog,
                "$ " + precompile + "\nexit=" + build.exitCode() + " timeout=" + build.timedOut()
                    + " duration=" + build.durationSeconds() + "s\n" + build.output(),
                StandardCharsets.UTF_8);
            if (build.passed()) {
              prepared = true;
              break;
            }
          }
          if (!prepared) {
            writeFailureCache(measurement.cacheFile(), measurement.fingerprint(), test,
                "precompilation failed");
            brokenBuildRoots.add(buildRoot);
            System.err.println("  build-root precompilation failed: " + buildRoot);
            continue;
          }
        }
        cleanReports(cwd);
        String command;
        try {
          command = coverageCommand(test.testCommand(), gradleInit);
        } catch (IOException exception) {
          writeFailureCache(measurement.cacheFile(), measurement.fingerprint(), test,
              exception.getMessage());
          System.err.println("  dropping unsupported coverage command: " + test.testCommand());
          continue;
        }
        System.out.printf(Locale.ROOT, "  coverage %d/%d running: %s.%s%n",
            index, totalMeasurements, test.testClass(), test.testMethod());
        CommandResult run;
        try {
          run = run(command, cwd);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new IOException("Coverage run interrupted", exception);
        }
        Path log = workingRoot.resolve("coverage-" + index + ".log");
        Files.writeString(log,
            "$ " + command + "\nexit=" + run.exitCode() + " timeout=" + run.timedOut()
                + " duration=" + run.durationSeconds() + "s\n" + run.output(),
            StandardCharsets.UTF_8);
        List<Path> reports = findReports(cwd);
        if (!run.passed()) {
          writeFailureCache(measurement.cacheFile(), measurement.fingerprint(), test,
              "coverage command failed with exit " + run.exitCode());
          System.err.println("  dropping test because coverage execution failed: " + test.testCommand());
          if (run.infrastructureFailure() || run.buildFailedWithoutCoverage()) {
            brokenBuildRoots.add(buildRoot);
            System.err.println("  build-root circuit breaker opened for: " + buildRoot);
          } else if (reports.isEmpty()) {
            recordMissingReport(buildRoot, consecutiveNoReport, brokenBuildRoots);
          }
          continue;
        }
        if (reports.isEmpty()) {
          writeFailureCache(measurement.cacheFile(), measurement.fingerprint(), test,
              "no JaCoCo XML report");
          System.err.println("  dropping test because no JaCoCo XML report was produced: "
              + test.testCommand());
          recordMissingReport(buildRoot, consecutiveNoReport, brokenBuildRoots);
          continue;
        }
        lineMap = readLineMap(reports);
        if (lineMap.isEmpty()) {
          writeFailureCache(measurement.cacheFile(), measurement.fingerprint(), test,
              "empty JaCoCo line map");
          recordMissingReport(buildRoot, consecutiveNoReport, brokenBuildRoots);
          continue;
        }
        consecutiveNoReport.remove(buildRoot);
        writeSuccessCache(measurement.cacheFile(), measurement.fingerprint(), test, lineMap);
      }

      Map<String, Coverage> coverageByTarget = new HashMap<>();
      for (Target target : measurement.targets()) {
        Coverage coverage = coverageFor(lineMap, target);
        if (coverage.totalLines() > 0) {
          coverageByTarget.put(target.key(), coverage);
        }
      }
      if (!coverageByTarget.isEmpty()) result.put(test.key(), coverageByTarget);
    }
    System.out.printf(Locale.ROOT,
        "  coverage selection: %d success cache hit(s), %d failed cache hit(s), "
            + "%d executed, %d circuit-skipped%n",
        cacheHits, failedCacheHits, executed, circuitSkipped);
    return result;
  }

  private static void recordMissingReport(
      String buildRoot,
      Map<String, Integer> consecutiveNoReport,
      Set<String> brokenBuildRoots
  ) {
    int failures = consecutiveNoReport.merge(buildRoot, 1, Integer::sum);
    if (failures >= 3 && brokenBuildRoots.add(buildRoot)) {
      System.err.println("  build-root circuit breaker opened after " + failures
          + " consecutive runs without usable coverage: " + buildRoot);
    }
  }

  private static Measurement chooseNextMeasurement(List<Measurement> measurements) {
    return measurements.stream().min(Comparator
        .comparingInt((Measurement measurement) -> cachePriority(measurement.cache()))
        .thenComparing(measurement -> measurement.test().key()))
        .orElseThrow();
  }

  private static int cachePriority(CacheEntry cache) {
    if (cache == null) return 2;
    return cache.failed() ? 1 : 0;
  }

  private record Measurement(
      TestSpec test,
      List<Target> targets,
      String fingerprint,
      Path cacheFile,
      CacheEntry cache
  ) {
  }

  private record CacheEntry(
      boolean failed,
      Map<String, Map<Integer, Boolean>> lineMap
  ) {
  }

  private static Coverage coverageFor(
      Map<String, Map<Integer, Boolean>> lineMap,
      Target target
  ) {
    String sourceKey = sourceKey(target.sourcePath());
    Map<Integer, Boolean> lines = lineMap.get(sourceKey);
    if (lines == null) {
      lines = lineMap.entrySet().stream()
          .filter(entry -> target.sourcePath().replace('\\', '/').endsWith(entry.getKey()))
          .map(Map.Entry::getValue)
          .findFirst().orElse(Map.of());
    }
    Set<Integer> executable = new HashSet<>();
    Set<Integer> covered = new HashSet<>();
    for (Map.Entry<Integer, Boolean> line : lines.entrySet()) {
      if (line.getKey() < target.firstLine() || line.getKey() > target.lastLine()) continue;
      executable.add(line.getKey());
      if (line.getValue()) covered.add(line.getKey());
    }
    return new Coverage(covered, executable);
  }

  private static Map<String, Map<Integer, Boolean>> readLineMap(List<Path> reports)
      throws IOException {
    Map<String, Map<Integer, Boolean>> result = new LinkedHashMap<>();
    for (Path report : reports) {
      try {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        var document = factory.newDocumentBuilder().parse(report.toFile());
        NodeList packages = document.getElementsByTagName("package");
        for (int packageIndex = 0; packageIndex < packages.getLength(); packageIndex++) {
          Element packageElement = (Element) packages.item(packageIndex);
          String packageName = packageElement.getAttribute("name");
          NodeList sources = packageElement.getElementsByTagName("sourcefile");
          for (int sourceIndex = 0; sourceIndex < sources.getLength(); sourceIndex++) {
            Element source = (Element) sources.item(sourceIndex);
            String key = packageName.isBlank()
                ? source.getAttribute("name")
                : packageName + "/" + source.getAttribute("name");
            Map<Integer, Boolean> lines = result.computeIfAbsent(key, ignored -> new HashMap<>());
            NodeList lineElements = source.getElementsByTagName("line");
            for (int lineIndex = 0; lineIndex < lineElements.getLength(); lineIndex++) {
              Element line = (Element) lineElements.item(lineIndex);
              int number = Integer.parseInt(line.getAttribute("nr"));
              int missed = Integer.parseInt(line.getAttribute("mi"));
              int covered = Integer.parseInt(line.getAttribute("ci"));
              if (missed + covered > 0) lines.merge(number, covered > 0, Boolean::logicalOr);
            }
          }
        }
      } catch (Exception exception) {
        System.err.println("  ignoring unreadable JaCoCo report: " + report
            + " (" + exception.getMessage() + ")");
      }
    }
    return result;
  }

  private static CacheEntry readCache(
      Path cacheFile,
      String expectedFingerprint
  ) {
    if (!Files.isRegularFile(cacheFile)) return null;
    try {
      List<String> rows = Files.readAllLines(cacheFile, StandardCharsets.UTF_8);
      if (rows.isEmpty()) return null;
      String actualFingerprint = rows.get(0).startsWith("fingerprint\t")
          ? rows.get(0).substring("fingerprint\t".length()) : "";
      if (!actualFingerprint.equals(expectedFingerprint)) return null;
      if (rows.stream().anyMatch(row -> row.equals("status\tfailed"))) {
        return new CacheEntry(true, Map.of());
      }
      if (rows.stream().anyMatch(row -> row.startsWith("status\t")
          && !row.equals("status\tsuccess"))) {
        return new CacheEntry(true, Map.of());
      }
      Map<String, Map<Integer, Boolean>> result = new LinkedHashMap<>();
      for (String row : rows) {
        if (!row.startsWith("line\t")) continue;
        String[] values = row.split("\t", -1);
        if (values.length != 4) continue;
        String source = new String(Base64.getUrlDecoder().decode(values[1]), StandardCharsets.UTF_8);
        result.computeIfAbsent(source, ignored -> new HashMap<>())
            .put(Integer.parseInt(values[2]), values[3].equals("1"));
      }
      return result.isEmpty() ? new CacheEntry(true, Map.of()) : new CacheEntry(false, result);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static void writeSuccessCache(
      Path cacheFile,
      String fingerprint,
      TestSpec test,
      Map<String, Map<Integer, Boolean>> lineMap
  ) throws IOException {
    StringBuilder value = new StringBuilder()
        .append("fingerprint\t").append(fingerprint).append('\n')
        .append("schema\t").append(CACHE_SCHEMA).append('\n')
        .append("test\t").append(test.testClass()).append('.').append(test.testMethod()).append('\n')
        .append("command_base64\t")
        .append(Base64.getUrlEncoder().withoutPadding().encodeToString(
            test.testCommand().getBytes(StandardCharsets.UTF_8))).append('\n');
    for (Map.Entry<String, Map<Integer, Boolean>> source : lineMap.entrySet()) {
      String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
          source.getKey().getBytes(StandardCharsets.UTF_8));
      source.getValue().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(line ->
          value.append("line\t").append(encoded).append('\t').append(line.getKey()).append('\t')
              .append(line.getValue() ? '1' : '0').append('\n'));
    }
    writeCacheFile(cacheFile, value.toString());
  }

  private static void writeFailureCache(
      Path cacheFile,
      String fingerprint,
      TestSpec test,
      String reason
  ) throws IOException {
    String encodedReason = Base64.getUrlEncoder().withoutPadding().encodeToString(
        String.valueOf(reason).getBytes(StandardCharsets.UTF_8));
    String value = new StringBuilder()
        .append("fingerprint\t").append(fingerprint).append('\n')
        .append("schema\t").append(CACHE_SCHEMA).append('\n')
        .append("status\tfailed\n")
        .append("test\t").append(test.testClass()).append('.').append(test.testMethod()).append('\n')
        .append("reason_base64\t").append(encodedReason).append('\n')
        .append("command_base64\t")
        .append(Base64.getUrlEncoder().withoutPadding().encodeToString(
            test.testCommand().getBytes(StandardCharsets.UTF_8))).append('\n')
        .toString();
    writeCacheFile(cacheFile, value);
  }

  private static void writeCacheFile(Path cacheFile, String value) throws IOException {
    Path temporary = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
    Files.writeString(temporary, value, StandardCharsets.UTF_8);
    try {
      Files.move(temporary, cacheFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
      Files.move(temporary, cacheFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static String buildFingerprint(Path repoRoot) throws IOException {
    MessageDigest digest = sha256Digest();
    update(digest, CACHE_SCHEMA);
    update(digest, JACOCO_VERSION);
    update(digest, System.getProperty("java.version", ""));
    List<Path> inputs;
    try (Stream<Path> paths = Files.walk(repoRoot)) {
      inputs = paths.filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".java")
              || BUILD_INPUT_NAMES.contains(path.getFileName().toString()))
          .filter(path -> !containsDirectory(path, CACHE_DIRECTORY))
          .filter(path -> !containsDirectory(path, "target"))
          .filter(path -> !containsDirectory(path, "build"))
          .sorted().toList();
    }
    for (Path input : inputs) {
      update(digest, repoRoot.relativize(input).toString());
      digest.update(Files.readAllBytes(input));
    }
    return hex(digest.digest());
  }

  private static String fingerprint(
      Path repoRoot,
      String buildFingerprint,
      TestSpec test,
      List<Target> targets
  )
      throws IOException {
    MessageDigest digest = sha256Digest();
    update(digest, buildFingerprint);
    update(digest, test.key());
    List<Path> inputs = new ArrayList<>();
    Path testSource = repoRoot.resolve(test.testPath()).normalize();
    if (Files.isRegularFile(testSource)) inputs.add(testSource);
    for (Target target : targets) {
      Path source = repoRoot.resolve(target.sourcePath()).normalize();
      if (Files.isRegularFile(source)) inputs.add(source);
    }
    for (Path input : inputs.stream().distinct().sorted().toList()) {
      update(digest, repoRoot.relativize(input).toString());
      digest.update(Files.readAllBytes(input));
    }
    return hex(digest.digest());
  }

  private static String coverageCommand(String original, Path gradleInit) throws IOException {
    String command = original.trim();
    if (command.startsWith("mvn ") || command.startsWith("bash ./mvnw ")
        || command.startsWith("./mvnw ")) {
      String executable = command.startsWith("bash ./mvnw ") ? "bash ./mvnw"
          : command.startsWith("./mvnw ") ? "./mvnw" : "mvn";
      String arguments = command.substring(executable.length()).trim();
      return executable
          + " -q org.jacoco:jacoco-maven-plugin:" + JACOCO_VERSION + ":prepare-agent "
          + arguments
          + " org.jacoco:jacoco-maven-plugin:" + JACOCO_VERSION + ":report";
    }
    if (command.startsWith("bash ./gradlew ") || command.startsWith("./gradlew ")
        || command.startsWith("gradle ")) {
      return command + " --init-script " + shellQuote(gradleInit.toAbsolutePath().toString())
          + " --no-build-cache";
    }
    throw new IOException("Unsupported build command: " + original);
  }

  private static String gradleInitScript() {
    return "allprojects { p ->\n"
        + "  p.pluginManager.apply('jacoco')\n"
        + "  p.tasks.withType(Test).configureEach { t ->\n"
        + "    t.outputs.upToDateWhen { false }\n"
        + "    t.finalizedBy(p.tasks.named('jacocoTestReport'))\n"
        + "  }\n"
        + "}\n"
        + "gradle.projectsEvaluated {\n"
        + "  allprojects { p ->\n"
        + "    p.tasks.withType(JacocoReport).configureEach { r ->\n"
        + "      r.reports.xml.required = true\n"
        + "      r.reports.html.required = false\n"
        + "      r.reports.csv.required = false\n"
        + "    }\n"
        + "  }\n"
        + "}\n";
  }

  private static List<Path> findReports(Path cwd) throws IOException {
    try (Stream<Path> paths = Files.walk(cwd)) {
      return paths.filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().equals("jacoco.xml")
              || path.getFileName().toString().equals("jacocoTestReport.xml"))
          .sorted().toList();
    }
  }

  private static void cleanReports(Path cwd) throws IOException {
    List<Path> reports;
    try (Stream<Path> paths = Files.walk(cwd)) {
      reports = paths.filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().equals("jacoco.xml")
              || path.getFileName().toString().equals("jacocoTestReport.xml")
              || path.getFileName().toString().equals("jacoco.exec"))
          .sorted(Comparator.reverseOrder()).toList();
    }
    for (Path report : reports) Files.deleteIfExists(report);
  }

  private static Path resolveWorkdir(Path repoRoot, String workdir) throws IOException {
    Path cwd = workdir.equals(".") ? repoRoot : repoRoot.resolve(workdir).normalize();
    if (!cwd.startsWith(repoRoot) || !Files.isDirectory(cwd)) {
      throw new IOException("Invalid test workdir: " + workdir);
    }
    return cwd;
  }

  private static CommandResult run(String command, Path cwd)
      throws IOException, InterruptedException {
    Instant started = Instant.now();
    Path output = Files.createTempFile(cwd, ".coverage-", ".log");
    Process process = null;
    try {
      ProcessBuilder builder = new ProcessBuilder("bash", "-c", command)
          .directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
      JavaBuildTool.configureJavaEnvironment(builder, command, cwd);
      String javaHome = builder.environment().get("JAVA_HOME");
      if (javaHome != null && !javaHome.isBlank()) {
        builder.environment().put("JAVA_HOME", javaHome);
        builder.environment().put("PATH", Path.of(javaHome, "bin") + ":"
            + builder.environment().getOrDefault("PATH", ""));
      }
      if (javaHome != null && javaMajor(javaHome) >= 9) {
        String mavenOpts = builder.environment().getOrDefault("MAVEN_OPTS", "").trim();
        builder.environment().put("MAVEN_OPTS",
            (mavenOpts + " --add-opens=java.base/java.lang=ALL-UNNAMED").trim());
      }
      process = builder.start();
      boolean finished = process.waitFor(JavaBuildTool.TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
      if (!finished) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
      }
      String text = Files.readString(output, StandardCharsets.UTF_8);
      return new CommandResult(finished ? process.exitValue() : -1,
          Duration.between(started, Instant.now()).toMillis() / 1000.0,
          !finished, text.length() <= 20000 ? text : text.substring(text.length() - 20000));
    } finally {
      if (process != null && process.isAlive()) process.destroyForcibly();
      Files.deleteIfExists(output);
    }
  }

  private static int javaMajor(String javaHome) {
    try {
      Process process = new ProcessBuilder(Path.of(javaHome, "bin/java").toString(), "-version")
          .redirectErrorStream(true).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      process.waitFor();
      java.util.regex.Matcher matcher = java.util.regex.Pattern
          .compile("version \\\"(?:1\\.)?(\\d+)").matcher(output);
      return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    } catch (Exception ignored) {
      return 0;
    }
  }

  private static boolean infrastructureFailure(String output, boolean timedOut) {
    String lower = output.toLowerCase(Locale.ROOT);
    return timedOut
        || lower.contains("non-resolvable")
        || lower.contains("could not resolve")
        || lower.contains("could not transfer artifact")
        || lower.contains("could not find or load main class")
        || lower.contains("could not create the java virtual machine")
        || lower.contains("unrecognized option:")
        || lower.contains("compilation error")
        || lower.contains("source option") && lower.contains("no longer supported")
        || lower.contains("not in the allowed range")
        || lower.contains("requires java")
        || lower.contains("permission denied") && lower.contains("gradlew")
        || lower.contains("no such file") && (lower.contains("mvnw") || lower.contains("gradlew"));
  }

  private static String sourceKey(String path) {
    String normalized = path.replace('\\', '/');
    int marker = normalized.lastIndexOf("/java/");
    return marker >= 0 ? normalized.substring(marker + 6) : normalized;
  }

  private static boolean containsDirectory(Path path, String name) {
    for (Path part : path) if (part.toString().equals(name)) return true;
    return false;
  }

  private static void writeCacheReadme(Path cacheRoot) throws IOException {
    Path readme = cacheRoot.resolve("README.txt");
    if (Files.exists(readme)) return;
    Files.writeString(readme,
        "LowCode sampler per-test JaCoCo line coverage cache.\n"
            + "Delete this directory to force coverage recomputation.\n",
        StandardCharsets.UTF_8);
  }

  private static void update(MessageDigest digest, String value) {
    digest.update(value.getBytes(StandardCharsets.UTF_8));
    digest.update((byte) 0);
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String sha256(String value) {
    MessageDigest digest = sha256Digest();
    update(digest, value);
    return hex(digest.digest());
  }

  private static String hex(byte[] bytes) {
    StringBuilder result = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) result.append(String.format("%02x", value));
    return result.toString();
  }

  private static String shellQuote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  private record CommandResult(int exitCode, double durationSeconds, boolean timedOut, String output) {
    boolean passed() {
      return !timedOut && exitCode == 0;
    }

    boolean infrastructureFailure() {
      return CoverageAnalyzer.infrastructureFailure(output, timedOut);
    }

    boolean buildFailedWithoutCoverage() {
      String lower = output.toLowerCase(Locale.ROOT);
      return !passed() && (lower.contains("build failed")
          || lower.contains("failed to execute goal")
          || lower.contains("exception is:")
          || lower.contains("startup failed"));
    }
  }
}
