import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;

import javax.lang.model.element.Modifier;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Extracts compact, body-free Java source skeletons as one JSON object per source file. */
public final class JavaSkeletonExtractor {
  private static final int PARSE_BATCH_SIZE = 250;
  private static final Pattern TYPE_TOKEN = Pattern.compile("\\b[A-Z][A-Za-z0-9_$]*\\b");

  private JavaSkeletonExtractor() {
  }

  public static void main(String[] args) throws Exception {
    Arguments arguments = Arguments.parse(args);
    Path repo = arguments.repo().toAbsolutePath().normalize();
    Path output = arguments.output().toAbsolutePath().normalize();
    String repoTag = GitHubLabel.fromRepository(repo);
    if (!Files.isDirectory(repo)) {
      throw new IllegalArgumentException("Repository is not a directory: " + repo);
    }
    Path parent = output.getParent();
    if (parent != null) Files.createDirectories(parent);
    Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
    List<Path> sources = discoverMainSources(repo);
    int written = 0;
    try (BufferedWriter writer = Files.newBufferedWriter(
        temporary, StandardCharsets.UTF_8)) {
      for (int start = 0; start < sources.size(); start += PARSE_BATCH_SIZE) {
        int end = Math.min(sources.size(), start + PARSE_BATCH_SIZE);
        written += parseBatch(repo, repoTag, sources.subList(start, end), writer);
      }
    }
    Files.move(temporary, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    System.out.printf(Locale.ROOT, "%s: %d skeleton(s) from %d Java source file(s)%n",
        repo.getFileName(), written, sources.size());
  }

  private static List<Path> discoverMainSources(Path repo) throws IOException {
    try (Stream<Path> paths = Files.walk(repo, FileVisitOption.FOLLOW_LINKS)) {
      return paths.filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".java"))
          .filter(path -> isMainSource(repo.relativize(path)))
          .sorted()
          .toList();
    }
  }

  private static boolean isMainSource(Path relative) {
    boolean belowSrc = false;
    for (Path part : relative) {
      String name = part.toString().toLowerCase(Locale.ROOT);
      if (Set.of(".git", "target", "build", "out", "generated", "generated-sources")
          .contains(name)) return false;
      if (name.equals("src")) belowSrc = true;
      if (belowSrc && (name.equals("test") || name.equals("tests")
          || name.equals("testfixtures") || name.equals("it") || name.equals("integrationtest"))) {
        return false;
      }
    }
    return belowSrc;
  }

  private static int parseBatch(
      Path repo, String repoTag, List<Path> files, BufferedWriter writer)
      throws IOException {
    if (files.isEmpty()) return 0;
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) throw new IllegalStateException("A full JDK is required");
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    int written = 0;
    try (StandardJavaFileManager manager = compiler.getStandardFileManager(
        diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
      Iterable<? extends JavaFileObject> objects = manager.getJavaFileObjectsFromPaths(files);
      JavacTask task = (JavacTask) compiler.getTask(
          null, manager, diagnostics, List.of("-proc:none", "-Xlint:none"), null, objects);
      for (CompilationUnitTree unit : task.parse()) {
        Path sourcePath = Paths.get(unit.getSourceFile().toUri()).toAbsolutePath().normalize();
        String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
        String packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
        List<String> imports = unit.getImports().stream()
            .map(ImportTree::toString)
            .map(value -> value.replaceFirst("^import\\s+", "").replaceFirst(";\\s*$", "").trim())
            .sorted().toList();
        List<ClassTree> topLevelTypes = unit.getTypeDecls().stream()
            .filter(ClassTree.class::isInstance).map(ClassTree.class::cast).toList();
        if (topLevelTypes.isEmpty()) continue;
        SkeletonRenderer renderer = new SkeletonRenderer(packageName, imports);
        String skeleton = renderer.render(topLevelTypes);
        Set<String> declaredTypes = new LinkedHashSet<>();
        for (ClassTree type : topLevelTypes) collectDeclaredTypes(type, packageName, declaredTypes);
        Set<String> referencedTypes = referencedTypes(source, imports, declaredTypes);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("repo", repo.getFileName().toString());
        record.put("repo_tag", repoTag);
        record.put("source_path", forwardSlashes(repo.relativize(sourcePath)));
        record.put("package", packageName);
        record.put("declared_types", List.copyOf(declaredTypes));
        record.put("imports", imports);
        record.put("referenced_types", List.copyOf(referencedTypes));
        record.put("source_sha256", sha256(source));
        record.put("skeleton", skeleton);
        writer.write(Json.stringify(record));
        writer.newLine();
        written++;
      }
    }
    return written;
  }

  private static void collectDeclaredTypes(
      ClassTree tree, String enclosingName, Set<String> output) {
    String simple = tree.getSimpleName().toString();
    String qualified = enclosingName.isBlank() ? simple : enclosingName + "." + simple;
    if (!simple.isBlank()) output.add(qualified);
    for (Tree member : tree.getMembers()) {
      if (member instanceof ClassTree nested) collectDeclaredTypes(nested, qualified, output);
    }
  }

  private static Set<String> referencedTypes(
      String source, List<String> imports, Set<String> declaredTypes) {
    Set<String> declaredSimple = new LinkedHashSet<>();
    for (String declared : declaredTypes) {
      int dot = declared.lastIndexOf('.');
      declaredSimple.add(dot < 0 ? declared : declared.substring(dot + 1));
    }
    Set<String> result = new LinkedHashSet<>();
    for (String imported : imports) {
      String value = imported.startsWith("static ") ? imported.substring(7) : imported;
      if (!value.endsWith(".*")) result.add(value);
    }
    // Scan the original source, not only the rendered skeleton. This retains project class
    // references made inside method bodies, which are required for task-3 transitive context.
    Matcher matcher = TYPE_TOKEN.matcher(source);
    while (matcher.find()) {
      String token = matcher.group();
      if (!declaredSimple.contains(token)) result.add(token);
    }
    return result.stream().sorted().collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
  }

  private static String forwardSlashes(Path path) {
    return path.toString().replace('\\', '/');
  }

  private static String sha256(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder output = new StringBuilder(digest.length * 2);
      for (byte item : digest) output.append(String.format("%02x", item));
      return output.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static final class GitHubLabel {
    private static final Pattern GITHUB_URL =
        Pattern.compile("github\\.com[/:]([^/]+)/([^/\\s]+)");

    private GitHubLabel() {
    }

    static String fromRepository(Path repoRoot) {
      Path config = resolveGitConfig(repoRoot.resolve(".git"));
      if (config != null && Files.isRegularFile(config)) {
        try {
          Matcher matcher = GITHUB_URL.matcher(Files.readString(config, StandardCharsets.UTF_8));
          if (matcher.find()) {
            return matcher.group(1) + "/" + matcher.group(2).replaceAll("\\.git$", "");
          }
        } catch (IOException ignored) {
          // Fall back to the repository directory name.
        }
      }
      return repoRoot.getFileName().toString();
    }

    private static Path resolveGitConfig(Path gitPath) {
      if (Files.isDirectory(gitPath)) return gitPath.resolve("config");
      if (!Files.isRegularFile(gitPath)) return null;
      try {
        String value = Files.readString(gitPath, StandardCharsets.UTF_8).trim();
        if (!value.startsWith("gitdir:")) return null;
        return gitPath.getParent().resolve(value.substring("gitdir:".length()).trim())
            .normalize().resolve("config");
      } catch (IOException ignored) {
        return null;
      }
    }
  }

  private record Arguments(Path repo, Path output) {
    static Arguments parse(String[] args) {
      Path repo = null;
      Path output = null;
      for (int index = 0; index < args.length; index++) {
        if (args[index].equals("--repo") && index + 1 < args.length) {
          repo = Paths.get(args[++index]);
        } else if (args[index].equals("--output") && index + 1 < args.length) {
          output = Paths.get(args[++index]);
        } else {
          throw new IllegalArgumentException(
              "Usage: JavaSkeletonExtractor --repo <repo> --output <jsonl>");
        }
      }
      if (repo == null || output == null) {
        throw new IllegalArgumentException(
            "Usage: JavaSkeletonExtractor --repo <repo> --output <jsonl>");
      }
      return new Arguments(repo, output);
    }
  }

  private static final class SkeletonRenderer {
    private final String packageName;
    private final List<String> imports;
    private final StringBuilder output = new StringBuilder();

    SkeletonRenderer(String packageName, List<String> imports) {
      this.packageName = packageName;
      this.imports = imports;
    }

    String render(List<ClassTree> types) {
      if (!packageName.isBlank()) output.append("package ").append(packageName).append(";\n\n");
      for (String imported : imports) output.append("import ").append(imported).append(";\n");
      if (!imports.isEmpty()) output.append('\n');
      for (int index = 0; index < types.size(); index++) {
        renderClass(types.get(index), 0);
        if (index + 1 < types.size()) output.append('\n');
      }
      return output.toString();
    }

    private void renderClass(ClassTree tree, int indent) {
      appendIndent(indent);
      appendModifiers(tree.getModifiers().getFlags(), tree.getModifiers().getAnnotations().stream()
          .map(Object::toString).toList());
      output.append(classKeyword(tree.getKind())).append(' ').append(tree.getSimpleName());
      appendTypeParameters(tree.getTypeParameters());
      if (tree.getExtendsClause() != null) output.append(" extends ").append(tree.getExtendsClause());
      if (!tree.getImplementsClause().isEmpty()) {
        output.append(tree.getKind() == Tree.Kind.INTERFACE ? " extends " : " implements ")
            .append(joinTrees(tree.getImplementsClause()));
      }
      if (!tree.getPermitsClause().isEmpty()) {
        output.append(" permits ").append(joinTrees(tree.getPermitsClause()));
      }
      output.append(" {\n");
      for (Tree member : tree.getMembers()) {
        if (member instanceof VariableTree variable) renderField(variable, indent + 1);
        else if (member instanceof MethodTree method) renderMethod(method, tree, indent + 1);
        else if (member instanceof ClassTree nested) renderClass(nested, indent + 1);
      }
      appendIndent(indent);
      output.append("}\n");
    }

    private void renderField(VariableTree tree, int indent) {
      appendIndent(indent);
      appendModifiers(tree.getModifiers().getFlags(), tree.getModifiers().getAnnotations().stream()
          .map(Object::toString).toList());
      if (tree.getType() != null) output.append(tree.getType()).append(' ');
      output.append(tree.getName()).append(";\n");
    }

    private void renderMethod(MethodTree tree, ClassTree owner, int indent) {
      appendIndent(indent);
      appendModifiers(tree.getModifiers().getFlags(), tree.getModifiers().getAnnotations().stream()
          .map(Object::toString).toList());
      appendTypeParameters(tree.getTypeParameters());
      if (!tree.getTypeParameters().isEmpty()) output.append(' ');
      if (tree.getReturnType() == null) output.append(owner.getSimpleName());
      else output.append(tree.getReturnType()).append(' ').append(tree.getName());
      output.append('(');
      for (int index = 0; index < tree.getParameters().size(); index++) {
        if (index > 0) output.append(", ");
        VariableTree parameter = tree.getParameters().get(index);
        appendModifiers(parameter.getModifiers().getFlags(), parameter.getModifiers().getAnnotations().stream()
            .map(Object::toString).toList());
        output.append(parameter.getType()).append(' ').append(parameter.getName());
      }
      output.append(')');
      if (!tree.getThrows().isEmpty()) output.append(" throws ").append(joinTrees(tree.getThrows()));
      output.append(";\n");
    }

    private void appendTypeParameters(List<? extends TypeParameterTree> parameters) {
      if (parameters.isEmpty()) return;
      output.append('<');
      for (int index = 0; index < parameters.size(); index++) {
        if (index > 0) output.append(", ");
        output.append(parameters.get(index));
      }
      output.append('>');
    }

    private void appendModifiers(Set<Modifier> modifiers, List<String> annotations) {
      for (String annotation : annotations) output.append(annotation).append(' ');
      List<Modifier> order = new ArrayList<>(EnumSet.copyOf(
          modifiers.isEmpty() ? EnumSet.noneOf(Modifier.class) : modifiers));
      order.sort(Comparator.comparingInt(Enum::ordinal));
      for (Modifier modifier : order) output.append(modifier.toString()).append(' ');
    }

    private void appendIndent(int indent) {
      output.append("  ".repeat(indent));
    }

    private static String classKeyword(Tree.Kind kind) {
      return switch (kind) {
        case INTERFACE -> "interface";
        case ENUM -> "enum";
        case ANNOTATION_TYPE -> "@interface";
        case RECORD -> "record";
        default -> "class";
      };
    }

    private static String joinTrees(List<? extends Tree> trees) {
      return trees.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(", "));
    }
  }

  private static final class Json {
    static String stringify(Object value) {
      if (value == null) return "null";
      if (value instanceof String string) return quote(string);
      if (value instanceof Number || value instanceof Boolean) return value.toString();
      if (value instanceof Map<?, ?> map) {
        StringBuilder output = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          if (!first) output.append(',');
          first = false;
          output.append(quote(String.valueOf(entry.getKey()))).append(':')
              .append(stringify(entry.getValue()));
        }
        return output.append('}').toString();
      }
      if (value instanceof Iterable<?> values) {
        StringBuilder output = new StringBuilder("[");
        boolean first = true;
        for (Object item : values) {
          if (!first) output.append(',');
          first = false;
          output.append(stringify(item));
        }
        return output.append(']').toString();
      }
      throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
    }

    private static String quote(String value) {
      StringBuilder output = new StringBuilder("\"");
      for (int index = 0; index < value.length(); index++) {
        char ch = value.charAt(index);
        switch (ch) {
          case '\"' -> output.append("\\\"");
          case '\\' -> output.append("\\\\");
          case '\b' -> output.append("\\b");
          case '\f' -> output.append("\\f");
          case '\n' -> output.append("\\n");
          case '\r' -> output.append("\\r");
          case '\t' -> output.append("\\t");
          default -> {
            if (ch < 0x20) output.append(String.format("\\u%04x", (int) ch));
            else output.append(ch);
          }
        }
      }
      return output.append('\"').toString();
    }
  }
}
