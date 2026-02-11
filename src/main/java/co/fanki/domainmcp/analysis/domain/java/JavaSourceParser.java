package co.fanki.domainmcp.analysis.domain.java;

import co.fanki.domainmcp.analysis.domain.ClassType;
import co.fanki.domainmcp.analysis.domain.SourceParser;
import co.fanki.domainmcp.analysis.domain.StaticMethodInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Java-specific implementation of {@link SourceParser}.
 *
 * <p>Discovers .java source files under src/main/java, extracts import
 * statements to build the dependency graph, and identifies entry points
 * based on Spring annotations.</p>
 *
 * <p>Entry point patterns:</p>
 * <ul>
 *   <li>{@code @RestController}, {@code @Controller} - HTTP endpoints</li>
 *   <li>{@code @KafkaListener} - Kafka consumers</li>
 *   <li>{@code @Scheduled}, {@code @EventListener} - Background jobs</li>
 *   <li>{@code @SpringBootApplication} - Application bootstrap</li>
 * </ul>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public class JavaSourceParser extends SourceParser {

    private static final Logger LOG = LoggerFactory.getLogger(
            JavaSourceParser.class);

    private static final String SOURCE_ROOT = "src/main/java";

    /** Matches a Java method declaration with its parameter list. */
    private static final Pattern METHOD_DECLARATION_PATTERN = Pattern.compile(
            "(?:public|protected|private)?\\s*"
                    + "(?:static\\s+)?(?:final\\s+)?(?:synchronized\\s+)?"
                    + "(?:<[^>]+>\\s+)?"
                    + "\\S+\\s+(\\w+)\\s*\\(([^)]*)\\)");

    private static final Set<String> ENTRY_POINT_ANNOTATIONS = Set.of(
            "@RestController",
            "@Controller",
            "@KafkaListener",
            "@Scheduled",
            "@EventListener",
            "@SpringBootApplication"
    );

    /** Maps Spring annotations to their corresponding ClassType. */
    private static final Map<String, ClassType> ANNOTATION_CLASS_TYPE_MAP =
            Map.ofEntries(
                    Map.entry("@RestController", ClassType.CONTROLLER),
                    Map.entry("@Controller", ClassType.CONTROLLER),
                    Map.entry("@Service", ClassType.SERVICE),
                    Map.entry("@Repository", ClassType.REPOSITORY),
                    Map.entry("@Configuration", ClassType.CONFIGURATION),
                    Map.entry("@Entity", ClassType.ENTITY),
                    Map.entry("@KafkaListener", ClassType.LISTENER),
                    Map.entry("@EventListener", ClassType.LISTENER)
            );

    /** Matches HTTP mapping annotations with optional path value. */
    private static final Pattern HTTP_ANNOTATION_PATTERN = Pattern.compile(
            "@(GetMapping|PostMapping|PutMapping|DeleteMapping"
                    + "|PatchMapping|RequestMapping)"
                    + "(?:\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\""
                    + ".*?\\))?");

    /** Maps annotation names to HTTP methods. */
    private static final Map<String, String> HTTP_METHOD_MAP = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH"
    );

    /** Matches a method declaration line to extract name + throws clause. */
    private static final Pattern METHOD_LINE_PATTERN = Pattern.compile(
            "(?:public|protected|private)?\\s*"
                    + "(?:static\\s+)?(?:final\\s+)?(?:synchronized\\s+)?"
                    + "(?:<[^>]+>\\s+)?"
                    + "\\S+\\s+(\\w+)\\s*\\(");

    /** Extracts the throws clause from a collapsed method signature. */
    private static final Pattern THROWS_PATTERN = Pattern.compile(
            "\\)\\s*throws\\s+([^{]+)\\{");

    /** {@inheritDoc} */
    @Override
    public String language() {
        return "java";
    }

    /** {@inheritDoc} */
    @Override
    public String sourceRoot() {
        return SOURCE_ROOT;
    }

    /**
     * Discovers all .java files under src/main/java.
     *
     * @param projectRoot the local project root directory
     * @return list of .java file paths
     * @throws IOException if file discovery fails
     */
    @Override
    protected List<Path> discoverFiles(final Path projectRoot)
            throws IOException {

        final Path sourceDir = projectRoot.resolve(SOURCE_ROOT);

        if (!Files.isDirectory(sourceDir)) {
            LOG.warn("Source root not found: {}", sourceDir);
            return List.of();
        }

        try (Stream<Path> walk = Files.walk(sourceDir)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Converts a .java file path to a fully-qualified class name (FQCN).
     *
     * <p>Strips the source root prefix and .java suffix, then replaces
     * path separators with dots.</p>
     *
     * @param file the .java file path
     * @param sourceRoot the resolved source root path
     * @return the FQCN (e.g., "co.fanki.checkout.Cart")
     */
    @Override
    protected String extractIdentifier(final Path file,
            final Path sourceRoot) {

        final Path relative = sourceRoot.relativize(file);
        String result = relative.toString();

        if (result.endsWith(".java")) {
            result = result.substring(0, result.length() - 5);
        }

        return result.replace('/', '.').replace('\\', '.');
    }

    /**
     * Extracts internal import dependencies from a Java source file.
     *
     * <p>Parses import statements and filters to only include imports
     * that match known identifiers within the project.</p>
     *
     * @param file the Java source file
     * @param knownIdentifiers all known identifiers in the project
     * @return set of dependency identifiers (FQCNs)
     * @throws IOException if file reading fails
     */
    @Override
    protected Set<String> extractDependencies(final Path file,
            final Set<String> knownIdentifiers) throws IOException {

        final Set<String> deps = new HashSet<>();
        final List<String> lines = Files.readAllLines(file);

        for (final String line : lines) {
            final String trimmed = line.trim();

            // Stop scanning after the class/interface declaration
            if (isClassDeclaration(trimmed)) {
                break;
            }

            final String importedClass = parseImportLine(trimmed);
            if (importedClass != null
                    && knownIdentifiers.contains(importedClass)) {
                deps.add(importedClass);
            }
        }

        return deps;
    }

    /**
     * Checks if a Java source file is an entry point by scanning for
     * Spring annotations that indicate controllers, listeners, or jobs.
     *
     * @param file the Java source file
     * @return true if the file contains entry point annotations
     * @throws IOException if file reading fails
     */
    @Override
    protected boolean isEntryPoint(final Path file) throws IOException {
        final List<String> lines = Files.readAllLines(file);

        for (final String line : lines) {
            final String trimmed = line.trim();
            for (final String annotation : ENTRY_POINT_ANNOTATIONS) {
                if (trimmed.startsWith(annotation)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Infers the {@link ClassType} from Spring annotations on the class.
     *
     * <p>Scans for Spring/Jakarta annotations before the class declaration
     * and maps them to the corresponding ClassType. If multiple annotations
     * match, the first one found (by annotation priority) wins.</p>
     *
     * @param file the Java source file to analyze
     * @return the inferred class type
     * @throws IOException if file reading fails
     */
    @Override
    public ClassType inferClassType(final Path file) throws IOException {
        final List<String> lines = Files.readAllLines(file);

        // First pass: scan class-level annotations (before class decl)
        for (final String line : lines) {
            final String trimmed = line.trim();

            if (isClassDeclaration(trimmed)) {
                break;
            }

            for (final Map.Entry<String, ClassType> entry
                    : ANNOTATION_CLASS_TYPE_MAP.entrySet()) {
                if (trimmed.startsWith(entry.getKey())) {
                    return entry.getValue();
                }
            }
        }

        // Second pass: scan for method-level annotations that indicate
        // class type (e.g. @KafkaListener, @EventListener inside body)
        for (final String line : lines) {
            final String trimmed = line.trim();
            if (trimmed.startsWith("@KafkaListener")) {
                return ClassType.LISTENER;
            }
            if (trimmed.startsWith("@EventListener")) {
                return ClassType.LISTENER;
            }
        }

        return ClassType.OTHER;
    }

    /**
     * Extracts method declarations from a Java source file with line
     * numbers, HTTP annotations, and throws clauses.
     *
     * <p>Iterates through the source lines tracking line numbers. When a
     * method declaration is found, records its line number, checks preceding
     * lines for HTTP mapping annotations, and parses throws clauses.</p>
     *
     * @param file the Java source file to analyze
     * @return list of statically extracted method information
     * @throws IOException if file reading fails
     */
    @Override
    public List<StaticMethodInfo> extractMethods(final Path file)
            throws IOException {

        final List<String> lines = Files.readAllLines(file);
        final List<StaticMethodInfo> result = new ArrayList<>();

        boolean inClassBody = false;
        int braceDepth = 0;
        int methodBraceDepth = -1;

        for (int i = 0; i < lines.size(); i++) {
            final String trimmed = lines.get(i).trim();

            if (!inClassBody && isClassDeclaration(trimmed)) {
                inClassBody = true;
                braceDepth += countChar(trimmed, '{')
                        - countChar(trimmed, '}');
                continue;
            }

            if (!inClassBody) {
                continue;
            }

            braceDepth += countChar(trimmed, '{') - countChar(trimmed, '}');

            // Skip lines inside method bodies (depth > 1 means inside a
            // method)
            if (methodBraceDepth >= 0 && braceDepth > methodBraceDepth) {
                continue;
            }
            methodBraceDepth = -1;

            // Try to match a method declaration
            final Matcher methodMatcher = METHOD_LINE_PATTERN.matcher(trimmed);
            if (!methodMatcher.find()) {
                continue;
            }

            final String methodName = methodMatcher.group(1);
            final int lineNumber = i + 1; // 1-based

            // Mark that we're now inside a method body
            if (trimmed.contains("{")) {
                methodBraceDepth = braceDepth - 1;
            }

            // Look for HTTP annotation in preceding lines (scan closest first)
            String httpMethod = null;
            String httpPath = null;

            for (int j = i - 1; j >= Math.max(0, i - 5); j--) {
                final String annotationLine = lines.get(j).trim();
                final Matcher httpMatcher = HTTP_ANNOTATION_PATTERN.matcher(
                        annotationLine);
                if (httpMatcher.find()) {
                    final String annotationName = httpMatcher.group(1);
                    httpPath = httpMatcher.group(2);

                    if ("RequestMapping".equals(annotationName)) {
                        // RequestMapping needs method= attribute; default GET
                        httpMethod = extractRequestMappingMethod(
                                annotationLine);
                    } else {
                        httpMethod = HTTP_METHOD_MAP.get(annotationName);
                    }
                    break;
                }
            }

            // Parse throws clause by collapsing from current line onward
            final List<String> exceptions = extractThrowsClause(lines, i);

            result.add(new StaticMethodInfo(
                    methodName, lineNumber, httpMethod, httpPath, exceptions));
        }

        return result;
    }

    /**
     * Extracts the HTTP method from a @RequestMapping annotation line.
     *
     * <p>Looks for method = RequestMethod.XXX in the annotation. Defaults
     * to GET if no method attribute is found.</p>
     */
    private String extractRequestMappingMethod(final String annotationLine) {
        final Pattern requestMethodPattern = Pattern.compile(
                "method\\s*=\\s*RequestMethod\\.(\\w+)");
        final Matcher matcher = requestMethodPattern.matcher(annotationLine);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "GET";
    }

    /**
     * Extracts the throws clause from a method declaration starting at the
     * given line index.
     *
     * <p>Collapses lines from the method declaration through the opening
     * brace to handle multiline signatures, then parses the throws clause.</p>
     */
    private List<String> extractThrowsClause(final List<String> lines,
            final int startLine) {

        final StringBuilder sig = new StringBuilder();
        for (int i = startLine;
                i < Math.min(startLine + 10, lines.size()); i++) {
            sig.append(lines.get(i).trim()).append(' ');
            if (lines.get(i).contains("{")) {
                break;
            }
        }

        final Matcher matcher = THROWS_PATTERN.matcher(sig);
        if (!matcher.find()) {
            return List.of();
        }

        final String throwsList = matcher.group(1).trim();
        final String[] parts = throwsList.split(",");
        final List<String> exceptions = new ArrayList<>();
        for (final String part : parts) {
            final String ex = part.trim();
            if (!ex.isEmpty()) {
                exceptions.add(ex);
            }
        }
        return exceptions;
    }

    private int countChar(final String s, final char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                count++;
            }
        }
        return count;
    }

    /**
     * Extracts method parameters from a Java source file.
     *
     * <p>Parses import statements to build a type resolution map, then
     * scans method declarations in the class body to match parameter types
     * against known project identifiers.</p>
     *
     * @param file the Java source file
     * @param sourceRoot the resolved source root path
     * @param knownIdentifiers all known identifiers in the project
     * @return map of method name to list of known parameter type identifiers
     * @throws IOException if file reading fails
     */
    @Override
    public Map<String, List<String>> extractMethodParameters(
            final Path file, final Path sourceRoot,
            final Set<String> knownIdentifiers) throws IOException {

        final List<String> lines = Files.readAllLines(file);
        final String filePackage = extractPackage(lines);
        final Map<String, String> importMap = buildImportMap(lines);

        final Map<String, List<String>> result = new HashMap<>();

        // Join lines into class body, collapsing multiline method signatures
        final String classBody = extractClassBody(lines);
        final String normalized = collapseMethodSignatures(classBody);

        final Matcher matcher = METHOD_DECLARATION_PATTERN.matcher(normalized);
        while (matcher.find()) {
            final String methodName = matcher.group(1);
            final String paramList = matcher.group(2).trim();

            if (paramList.isEmpty()) {
                continue;
            }

            final List<String> matchedParams =
                    resolveParameterTypes(paramList, importMap,
                            filePackage, knownIdentifiers);

            if (!matchedParams.isEmpty()) {
                result.put(methodName, matchedParams);
            }
        }

        return result;
    }

    /**
     * Extracts the class body from the source lines, skipping everything
     * before the first class/interface/enum/record declaration.
     */
    private String extractClassBody(final List<String> lines) {
        final StringBuilder body = new StringBuilder();
        boolean inClassBody = false;

        for (final String line : lines) {
            final String trimmed = line.trim();

            if (!inClassBody && isClassDeclaration(trimmed)) {
                inClassBody = true;
                continue;
            }

            if (inClassBody) {
                body.append(trimmed).append(' ');
            }
        }

        return body.toString();
    }

    /**
     * Collapses multiline method signatures into single lines.
     *
     * <p>Joins content between an opening parenthesis that is not closed
     * on the same line, by collapsing whitespace. This allows the method
     * declaration regex to match signatures that span multiple lines.</p>
     */
    private String collapseMethodSignatures(final String classBody) {
        final StringBuilder result = new StringBuilder();
        int depth = 0;

        for (int i = 0; i < classBody.length(); i++) {
            final char c = classBody.charAt(i);

            if (c == '(') {
                depth++;
                result.append(c);
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
                result.append(c);
            } else if (depth > 0 && (c == '\n' || c == '\r')) {
                // Inside parens, replace newlines with space
                result.append(' ');
            } else {
                result.append(c);
            }
        }

        // Collapse multiple whitespace into single spaces
        return result.toString().replaceAll("\\s+", " ");
    }

    /**
     * Resolves parameter types from a method's parameter list string.
     *
     * <p>Splits by comma, extracts the type name (handling generics and
     * annotations), resolves against imports and same-package, and
     * filters to known identifiers only.</p>
     */
    private List<String> resolveParameterTypes(
            final String paramList,
            final Map<String, String> importMap,
            final String filePackage,
            final Set<String> knownIdentifiers) {

        final List<String> matched = new ArrayList<>();
        final String[] params = paramList.split(",");

        for (final String param : params) {
            final String typeName = extractTypeName(param.trim());
            if (typeName == null || typeName.isEmpty()) {
                continue;
            }

            final String resolved = resolveType(typeName, importMap,
                    filePackage, knownIdentifiers);
            if (resolved != null) {
                matched.add(resolved);
            }
        }

        return matched;
    }

    /**
     * Extracts the simple type name from a parameter declaration.
     *
     * <p>Handles annotations, final modifier, generics (stripping them),
     * and varargs.</p>
     */
    private String extractTypeName(final String paramDecl) {
        if (paramDecl.isEmpty()) {
            return null;
        }

        // Split into tokens
        final String[] tokens = paramDecl.split("\\s+");
        String typeName = null;

        for (final String token : tokens) {
            // Skip annotations and 'final'
            if (token.startsWith("@") || "final".equals(token)) {
                continue;
            }
            // First non-annotation, non-final token is the type
            typeName = token;
            break;
        }

        if (typeName == null) {
            return null;
        }

        // Strip generics: List<Foo> -> List
        final int genericIdx = typeName.indexOf('<');
        if (genericIdx > 0) {
            typeName = typeName.substring(0, genericIdx);
        }

        // Strip varargs
        if (typeName.endsWith("...")) {
            typeName = typeName.substring(0, typeName.length() - 3);
        }

        // Strip array brackets
        if (typeName.endsWith("[]")) {
            typeName = typeName.substring(0, typeName.length() - 2);
        }

        return typeName;
    }

    /**
     * Resolves a simple type name to an FQCN using imports or same-package.
     */
    private String resolveType(final String simpleName,
            final Map<String, String> importMap,
            final String filePackage,
            final Set<String> knownIdentifiers) {

        // Already an FQCN?
        if (knownIdentifiers.contains(simpleName)) {
            return simpleName;
        }

        // Check imports
        final String fromImport = importMap.get(simpleName);
        if (fromImport != null && knownIdentifiers.contains(fromImport)) {
            return fromImport;
        }

        // Check same package
        if (filePackage != null && !filePackage.isEmpty()) {
            final String samePackage = filePackage + "." + simpleName;
            if (knownIdentifiers.contains(samePackage)) {
                return samePackage;
            }
        }

        return null;
    }

    /**
     * Extracts the package name from the source file lines.
     */
    private String extractPackage(final List<String> lines) {
        for (final String line : lines) {
            final String trimmed = line.trim();
            if (trimmed.startsWith("package ") && trimmed.endsWith(";")) {
                return trimmed.substring("package ".length(),
                        trimmed.length() - 1).trim();
            }
        }
        return null;
    }

    /**
     * Builds a map from simple class names to FQCNs based on import
     * statements in the source file.
     */
    private Map<String, String> buildImportMap(final List<String> lines) {
        final Map<String, String> importMap = new HashMap<>();

        for (final String line : lines) {
            final String trimmed = line.trim();

            if (isClassDeclaration(trimmed)) {
                break;
            }

            final String fqcn = parseImportLine(trimmed);
            if (fqcn != null) {
                final int lastDot = fqcn.lastIndexOf('.');
                if (lastDot > 0) {
                    final String simpleName = fqcn.substring(lastDot + 1);
                    importMap.put(simpleName, fqcn);
                }
            }
        }

        return importMap;
    }

    /**
     * Parses an import line to extract the fully-qualified class name.
     *
     * @param importLine the import statement
     * @return the FQCN, or null if not parseable or a wildcard import
     */
    String parseImportLine(final String importLine) {
        if (importLine == null || !importLine.startsWith("import ")) {
            return null;
        }

        String line = importLine.substring("import ".length()).trim();

        // Handle static imports
        final boolean isStatic = line.startsWith("static ");
        if (isStatic) {
            line = line.substring("static ".length()).trim();
        }

        // Remove trailing semicolon and whitespace
        if (line.endsWith(";")) {
            line = line.substring(0, line.length() - 1).trim();
        }

        // Skip wildcard imports
        if (line.endsWith(".*")) {
            return null;
        }

        // For static imports, extract the class part (before the last dot)
        if (isStatic) {
            final int lastDot = line.lastIndexOf('.');
            if (lastDot > 0) {
                line = line.substring(0, lastDot);
            }
        }

        return line.isBlank() ? null : line;
    }

    /**
     * Checks if a line starts a class, interface, enum, or record declaration.
     */
    private boolean isClassDeclaration(final String line) {
        if (line.startsWith("//") || line.startsWith("*")
                || line.startsWith("/*")) {
            return false;
        }
        return line.contains("class ")
                || line.contains("interface ")
                || line.contains("enum ")
                || line.contains("record ");
    }

}
