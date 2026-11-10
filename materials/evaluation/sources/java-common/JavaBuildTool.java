import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Shared Maven/Gradle project detection and test command construction. */
public final class JavaBuildTool {
  public static final Duration TEST_TIMEOUT = Duration.ofMinutes(15);
  private static final String MAVEN_QUALITY_CHECK_SKIP_OPTIONS =
      " -Dcheckstyle.skip=true -Dformatter.skip=true";
  private static final Pattern GRADLE_DISTRIBUTION_VERSION =
      Pattern.compile("gradle-([0-9]+(?:\\.[0-9]+)*)-(?:bin|all)\\.zip");
  private static final Pattern JDK_DIRECTORY_VERSION =
      Pattern.compile("(?:jdk|java)[-_]?([0-9]+)(?:[u._+\\-].*)?", Pattern.CASE_INSENSITIVE);
  private static final Pattern JAVA_VERSION_PROPERTY = Pattern.compile(
      "<([^>]*(?:java\\.version|maven\\.compiler\\.release|maven\\.compiler\\.source)[^>]*)>\\s*([^<]+)</");
  private static final Pattern ENFORCED_JAVA_VERSION = Pattern.compile(
      "<requireJavaVersion>.*?<version>\\s*([^<]+)\\s*</version>", Pattern.DOTALL);
  private static final Pattern JAVA_RELEASE_TAG = Pattern.compile(
      "<release>\\s*(?:1\\.)?(\\d+)\\s*</release>");
  private static final Pattern JAVA_SOURCE_TAG = Pattern.compile(
      "<source>\\s*(?:1\\.)?(\\d+)\\s*</source>");

  private JavaBuildTool() {
  }

  public static Optional<TestCommand> testCommand(
      Path repoRoot, Path testSource, String testClass, String testMethod) {
    Path module = findNearestBuildDirectory(repoRoot, testSource.getParent());
    if (module == null) return Optional.empty();

    if (Files.isRegularFile(module.resolve("pom.xml"))) {
      Path buildRoot = findOutermostMavenDirectory(repoRoot, module);
      String workdir = relativeDirectory(repoRoot, buildRoot);
      String selectedModule = relativeDirectory(buildRoot, module);
      String executable = Files.isRegularFile(buildRoot.resolve("mvnw"))
          ? "bash ./mvnw" : "mvn";
      String selection = selectedModule.equals(".") ? "" : " -pl " + selectedModule + " -am";
      return Optional.of(new TestCommand(
          workdir,
          executable + selection
              + " test -Dtest=" + testClass + "#" + testMethod
              + " -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false"
              + MAVEN_QUALITY_CHECK_SKIP_OPTIONS));
    }

    Path buildRoot = findGradleBuildRoot(repoRoot, module);
    if (buildRoot == null) return Optional.empty();
    String executable = Files.isRegularFile(buildRoot.resolve("gradlew"))
        ? "bash ./gradlew" : "gradle";
    String projectPath = gradleProjectPath(buildRoot, module);
    String task = projectPath.isEmpty() ? "test" : projectPath + ":test";
    return Optional.of(new TestCommand(
        relativeDirectory(repoRoot, buildRoot),
        executable + " " + task + " --tests \"" + testClass + "." + testMethod + "\""));
  }

  /**
   * Returns increasingly compatible no-test build commands for a stored test command.
   * Callers should stop after the first successful command.
   */
  public static List<String> precompileCommands(String testCommand) {
    ParsedCommand parsed = ParsedCommand.parse(testCommand);
    if (parsed.kind == Kind.MAVEN) {
      String prefix = parsed.executable + parsed.selection;
      return List.of(
          prefix + " test-compile -DskipTests" + MAVEN_QUALITY_CHECK_SKIP_OPTIONS,
          prefix + " package -DskipTests" + MAVEN_QUALITY_CHECK_SKIP_OPTIONS,
          prefix + " package -DskipTests -Dmaven.antrun.skip=true"
              + MAVEN_QUALITY_CHECK_SKIP_OPTIONS);
    }
    if (parsed.kind == Kind.GRADLE) {
      String task = parsed.task.substring(0, parsed.task.length() - "test".length()) + "testClasses";
      return List.of(parsed.executable + " " + task);
    }
    throw new IllegalArgumentException("Unsupported Java test command: " + testCommand);
  }

  public static boolean isGradleCommand(String command) {
    String trimmed = command.trim();
    return trimmed.equals("bash ./gradlew") || trimmed.startsWith("bash ./gradlew ")
        || trimmed.equals("./gradlew") || trimmed.startsWith("./gradlew ")
        || trimmed.equals("gradle") || trimmed.startsWith("gradle ");
  }

  /** Selects an already-installed JDK compatible with the project's declared Gradle version. */
  public static Optional<Path> configureJavaEnvironment(
      ProcessBuilder processBuilder, String command, Path workingDirectory) throws IOException {
    configureMavenEnvironment(processBuilder, command);
    configureMavenJavaEnvironment(processBuilder, command, workingDirectory);
    Optional<Integer> javaMajor = requiredJavaMajor(command, workingDirectory);
    if (javaMajor.isEmpty()) return Optional.empty();
    Optional<Path> javaHome = findInstalledJavaHome(javaMajor.get());
    if (javaHome.isEmpty()) return Optional.empty();
    String oldPath = processBuilder.environment().getOrDefault("PATH", "");
    processBuilder.environment().put("JAVA_HOME", javaHome.get().toString());
    processBuilder.environment().put("PATH", javaHome.get().resolve("bin") + ":" + oldPath);
    return javaHome;
  }

  private static void configureMavenJavaEnvironment(
      ProcessBuilder processBuilder, String command, Path workingDirectory) throws IOException {
    if (!isMavenCommand(command)) return;
    Optional<Integer> required = requiredMavenJavaMajor(mavenTargetDirectory(command, workingDirectory));
    if (required.isEmpty()) return;
    Optional<Path> javaHome = findInstalledJavaHome(required.get());
    if (javaHome.isEmpty()) return;
    String oldPath = processBuilder.environment().getOrDefault("PATH", "");
    processBuilder.environment().put("JAVA_HOME", javaHome.get().toString());
    processBuilder.environment().put("PATH", javaHome.get().resolve("bin") + ":" + oldPath);
  }

  private static Path mavenTargetDirectory(String command, Path workingDirectory) {
    Matcher module = Pattern.compile("(?:^|\\s)-pl\\s+([^\\s]+)").matcher(command);
    if (!module.find()) return workingDirectory;
    String value = module.group(1).replace(',', ' ').trim();
    if (value.isEmpty() || value.startsWith(":")) return workingDirectory;
    Path candidate = workingDirectory.resolve(value).normalize();
    return Files.isDirectory(candidate) ? candidate : workingDirectory;
  }

  private static boolean isMavenCommand(String command) {
    String trimmed = command.trim();
    return trimmed.equals("mvn") || trimmed.startsWith("mvn ")
        || trimmed.equals("./mvnw") || trimmed.startsWith("./mvnw ")
        || trimmed.equals("bash ./mvnw") || trimmed.startsWith("bash ./mvnw ");
  }

  static Optional<Integer> requiredMavenJavaMajor(Path workingDirectory) throws IOException {
    // Maven's runtime requirement applies to the whole reactor and must take
    // precedence over a child module's older compiler source/target setting.
    for (Path current = workingDirectory.toAbsolutePath().normalize(); current != null;
         current = current.getParent()) {
      Path pom = current.resolve("pom.xml");
      if (!Files.isRegularFile(pom)) continue;
      String xml = Files.readString(pom);
      Matcher enforced = ENFORCED_JAVA_VERSION.matcher(xml);
      if (enforced.find()) {
        Optional<Integer> version = parseJavaMajor(enforced.group(1));
        if (version.isPresent()) return version;
      }
    }
    for (Path current = workingDirectory.toAbsolutePath().normalize(); current != null;
         current = current.getParent()) {
      Path pom = current.resolve("pom.xml");
      if (!Files.isRegularFile(pom)) continue;
      Optional<Integer> result = requiredMavenJavaMajorFromPom(pom, new HashSet<>());
      if (result.isPresent()) return result;
    }
    return Optional.empty();
  }

  private static Optional<Integer> requiredMavenJavaMajorFromPom(
      Path pom, Set<Path> visited) throws IOException {
    Path normalized = pom.toAbsolutePath().normalize();
    if (!visited.add(normalized) || !Files.isRegularFile(normalized)) return Optional.empty();
      String xml = Files.readString(normalized);
      Matcher enforced = ENFORCED_JAVA_VERSION.matcher(xml);
      if (enforced.find()) {
      Optional<Integer> version = parseJavaMajor(enforced.group(1));
      if (version.isPresent()) return version;
      }
    Matcher release = JAVA_RELEASE_TAG.matcher(xml);
    if (release.find()) return Optional.of(Integer.parseInt(release.group(1)));
    Matcher source = JAVA_SOURCE_TAG.matcher(xml);
    if (source.find()) return Optional.of(Integer.parseInt(source.group(1)));
    Matcher matcher = JAVA_VERSION_PROPERTY.matcher(xml);
    while (matcher.find()) {
      String property = matcher.group(1);
      String value = matcher.group(2).trim();
      if (!property.contains("java.version")
          && !property.contains("maven.compiler.release")
          && !property.contains("maven.compiler.source")) continue;
        Optional<Integer> version = parseJavaMajor(value);
        if (version.isPresent()) return version;
    }
    Matcher relative = Pattern.compile(
        "<parent>.*?<relativePath>\\s*([^<]+?)\\s*</relativePath>.*?</parent>", Pattern.DOTALL)
        .matcher(xml);
    Path parent = normalized.getParent();
    if (parent == null) return Optional.empty();
    Path parentPom = parent.resolve("../pom.xml").normalize();
    if (relative.find() && !relative.group(1).trim().isEmpty()) {
      parentPom = parent.resolve(relative.group(1).trim()).normalize();
    }
    return requiredMavenJavaMajorFromPom(parentPom, visited);
  }

  private static Optional<Integer> parseJavaMajor(String value) {
    Matcher number = Pattern.compile("^[^0-9]*(?:1\\.)?(\\d+)").matcher(value.trim());
    return number.find() ? Optional.of(Integer.parseInt(number.group(1))) : Optional.empty();
  }

  /** Prefer an already-installed Maven under ~/apps for plain `mvn` commands. */
  private static void configureMavenEnvironment(ProcessBuilder processBuilder, String command)
      throws IOException {
    String trimmed = command.trim();
    if (!(trimmed.equals("mvn") || trimmed.startsWith("mvn "))) return;
    Path apps = Path.of(System.getProperty("user.home"), "apps");
    if (!Files.isDirectory(apps)) return;
    try (Stream<Path> paths = Files.list(apps)) {
      Optional<Path> maven = paths.filter(Files::isDirectory)
          .map(path -> path.resolve("bin/mvn"))
          .filter(Files::isExecutable)
          .filter(JavaBuildTool::isMaven39OrNewer)
          .sorted(Comparator.comparing(path -> path.getParent().getParent().getFileName().toString()))
          .findFirst();
      if (maven.isEmpty()) return;
      Path bin = maven.get().getParent();
      String oldPath = processBuilder.environment().getOrDefault("PATH", "");
      processBuilder.environment().put("PATH", bin + ":" + oldPath);
      processBuilder.environment().put("MAVEN_HOME", bin.getParent().toString());
    }
  }

  private static boolean isMaven39OrNewer(Path executable) {
    try {
      Process process = new ProcessBuilder(executable.toString(), "-version")
          .redirectErrorStream(true).start();
      String output = new String(process.getInputStream().readAllBytes());
      process.waitFor();
      Matcher matcher = Pattern.compile("Apache Maven (\\d+)\\.(\\d+)(?:\\.(\\d+))?")
          .matcher(output);
      if (!matcher.find()) return false;
      int major = Integer.parseInt(matcher.group(1));
      int minor = Integer.parseInt(matcher.group(2));
      return major > 3 || major == 3 && minor >= 9;
    } catch (Exception ignored) {
      return false;
    }
  }

  static Optional<Integer> requiredJavaMajor(String command, Path workingDirectory)
      throws IOException {
    if (!isGradleCommand(command)) return Optional.empty();
    Optional<GradleVersion> version = declaredGradleVersion(workingDirectory);
    if (version.isEmpty()) return Optional.empty();
    GradleVersion gradle = version.get();
    if (gradle.before(5, 0)) return Optional.of(8);
    if (gradle.before(7, 3)) return Optional.of(11);
    if (gradle.before(8, 5)) return Optional.of(17);
    if (!gradle.before(9, 6)) return Optional.of(25);
    return Optional.of(21);
  }

  static Optional<GradleVersion> declaredGradleVersion(Path workingDirectory) throws IOException {
    for (Path current = workingDirectory.toAbsolutePath().normalize(); current != null;
         current = current.getParent()) {
      Path propertiesPath = current.resolve("gradle/wrapper/gradle-wrapper.properties");
      if (!Files.isRegularFile(propertiesPath)) continue;
      Properties properties = new Properties();
      try (InputStream input = Files.newInputStream(propertiesPath)) {
        properties.load(input);
      }
      String distributionUrl = properties.getProperty("distributionUrl", "");
      Matcher matcher = GRADLE_DISTRIBUTION_VERSION.matcher(distributionUrl);
      if (matcher.find()) return Optional.of(GradleVersion.parse(matcher.group(1)));
      return Optional.empty();
    }
    return Optional.empty();
  }

  static Optional<Path> findInstalledJavaHome(int requiredMajor) throws IOException {
    Path apps = Path.of(System.getProperty("user.home"), "apps");
    if (!Files.isDirectory(apps)) return Optional.empty();
    try (Stream<Path> paths = Files.list(apps)) {
      return paths.filter(Files::isDirectory)
          .filter(path -> Files.isExecutable(path.resolve("bin/java")))
          .filter(path -> javaMajorFromDirectory(path.getFileName().toString()) == requiredMajor)
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .findFirst();
    }
  }

  private static int javaMajorFromDirectory(String directoryName) {
    Matcher matcher = JDK_DIRECTORY_VERSION.matcher(directoryName);
    return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
  }

  private static Path findNearestBuildDirectory(Path repoRoot, Path start) {
    for (Path current = start; current != null && current.startsWith(repoRoot);
         current = current.getParent()) {
      if (Files.isRegularFile(current.resolve("pom.xml"))
          || Files.isRegularFile(current.resolve("build.gradle"))
          || Files.isRegularFile(current.resolve("build.gradle.kts"))) {
        return current;
      }
    }
    return null;
  }

  private static Path findOutermostMavenDirectory(Path repoRoot, Path start) {
    Path result = start;
    for (Path current = start; current != null && current.startsWith(repoRoot);
         current = current.getParent()) {
      if (Files.isRegularFile(current.resolve("pom.xml"))) result = current;
    }
    return result;
  }

  private static Path findGradleBuildRoot(Path repoRoot, Path module) {
    Path outermostBuild = null;
    for (Path current = module; current != null && current.startsWith(repoRoot);
         current = current.getParent()) {
      if (Files.isRegularFile(current.resolve("settings.gradle"))
          || Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
        return current;
      }
      if (Files.isRegularFile(current.resolve("build.gradle"))
          || Files.isRegularFile(current.resolve("build.gradle.kts"))) {
        outermostBuild = current;
      }
    }
    return outermostBuild;
  }

  private static String gradleProjectPath(Path buildRoot, Path module) {
    if (buildRoot.equals(module)) return "";
    String joined = Stream.of(buildRoot.relativize(module).toString()
            .split(Pattern.quote(java.io.File.separator)))
        .filter(part -> !part.isBlank())
        .collect(Collectors.joining(":"));
    return joined.isBlank() ? "" : ":" + joined;
  }

  private static String relativeDirectory(Path root, Path directory) {
    if (root.toAbsolutePath().normalize().equals(directory.toAbsolutePath().normalize())) return ".";
    Path relative = root.relativize(directory);
    return relative.getNameCount() == 0 ? "." : relative.toString().replace('\\', '/');
  }

  public record TestCommand(String workdir, String command) {
  }

  record GradleVersion(int major, int minor) {
    static GradleVersion parse(String value) {
      String[] parts = value.split("\\.");
      return new GradleVersion(Integer.parseInt(parts[0]),
          parts.length > 1 ? Integer.parseInt(parts[1]) : 0);
    }

    boolean before(int otherMajor, int otherMinor) {
      return major < otherMajor || major == otherMajor && minor < otherMinor;
    }
  }

  private enum Kind { MAVEN, GRADLE, UNKNOWN }

  private static final class ParsedCommand {
    private static final List<String> MAVEN_EXECUTABLES = List.of("bash ./mvnw", "./mvnw", "mvn");
    private static final List<String> GRADLE_EXECUTABLES = List.of("bash ./gradlew", "./gradlew", "gradle");

    final Kind kind;
    final String executable;
    final String selection;
    final String task;

    private ParsedCommand(Kind kind, String executable, String selection, String task) {
      this.kind = kind;
      this.executable = executable;
      this.selection = selection;
      this.task = task;
    }

    static ParsedCommand parse(String rawCommand) {
      String command = rawCommand.trim();
      for (String executable : MAVEN_EXECUTABLES) {
        if (!startsWithExecutable(command, executable)) continue;
        String remainder = command.substring(executable.length()).trim();
        List<String> tokens = shellTokens(remainder);
        int goal = tokens.indexOf("test");
        if (goal < 0) return new ParsedCommand(Kind.UNKNOWN, executable, "", "");
        String selection = goal == 0 ? "" : " " + String.join(" ", tokens.subList(0, goal));
        return new ParsedCommand(Kind.MAVEN, executable, selection, "test");
      }
      for (String executable : GRADLE_EXECUTABLES) {
        if (!startsWithExecutable(command, executable)) continue;
        List<String> tokens = shellTokens(command.substring(executable.length()).trim());
        if (tokens.isEmpty()) return new ParsedCommand(Kind.UNKNOWN, executable, "", "");
        String task = tokens.get(0);
        if (!(task.equals("test") || task.endsWith(":test"))) {
          return new ParsedCommand(Kind.UNKNOWN, executable, "", task);
        }
        return new ParsedCommand(Kind.GRADLE, executable, "", task);
      }
      return new ParsedCommand(Kind.UNKNOWN, "", "", "");
    }

    private static boolean startsWithExecutable(String command, String executable) {
      return command.equals(executable) || command.startsWith(executable + " ");
    }

    private static List<String> shellTokens(String value) {
      List<String> result = new ArrayList<>();
      for (String token : value.split("\\s+")) {
        if (!token.isBlank()) result.add(token);
      }
      return result;
    }
  }
}
