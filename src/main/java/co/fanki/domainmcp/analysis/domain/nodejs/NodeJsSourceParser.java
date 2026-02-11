package co.fanki.domainmcp.analysis.domain.nodejs;

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
 * Node.js/TypeScript implementation of {@link SourceParser}.
 *
 * <p>Discovers .ts, .tsx, .js, and .jsx source files under src/,
 * extracts ES6 import and CommonJS require statements to build the
 * dependency graph, and identifies entry points based on framework
 * patterns (NestJS, Express) and well-known filenames.</p>
 *
 * <p>Entry point patterns:</p>
 * <ul>
 *   <li>NestJS: {@code @Controller(} annotation</li>
 *   <li>Express: {@code app.get(}, {@code router.post(}, etc.</li>
 *   <li>Well-known filenames: main.ts, index.ts, app.ts, server.ts
 *       and .js variants</li>
 * </ul>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public class NodeJsSourceParser extends SourceParser {

    private static final Logger LOG = LoggerFactory.getLogger(
            NodeJsSourceParser.class);

    private static final String SOURCE_ROOT = "src";

    private static final Set<String> EXTENSIONS = Set.of(
            ".ts", ".tsx", ".js", ".jsx");

    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "node_modules", "dist", ".next", "build", "coverage",
            "__tests__", "__mocks__");

    private static final Set<String> ENTRY_POINT_FILENAMES = Set.of(
            "main.ts", "main.js",
            "index.ts", "index.js",
            "app.ts", "app.js",
            "server.ts", "server.js");

    /** Matches ES6 import from relative path. */
    private static final Pattern ES6_IMPORT_PATTERN = Pattern.compile(
            "import\\s+.*?from\\s+['\"](\\..*?)['\"]");

    /** Matches CommonJS require with relative path. */
    private static final Pattern REQUIRE_PATTERN = Pattern.compile(
            "require\\s*\\(\\s*['\"](\\..*?)['\"]\\s*\\)");

    /** Matches NestJS @Controller annotation. */
    private static final Pattern NESTJS_CONTROLLER_PATTERN = Pattern.compile(
            "@Controller\\s*\\(");

    /** Matches Express-style route registrations. */
    private static final Pattern EXPRESS_ROUTE_PATTERN = Pattern.compile(
            "(app|router)\\.(get|post|put|delete|patch|all|use)\\s*\\(");

    /** Matches TypeScript function/method declarations with typed params. */
    private static final Pattern TS_FUNCTION_PATTERN = Pattern.compile(
            "(?:async\\s+)?(?:function\\s+)?(\\w+)\\s*\\(([^)]*)\\)");

    /** Matches NestJS @Injectable annotation. */
    private static final Pattern NESTJS_INJECTABLE_PATTERN = Pattern.compile(
            "@Injectable\\s*\\(");

    /** Matches NestJS HTTP decorators with optional path. */
    private static final Pattern NESTJS_HTTP_DECORATOR_PATTERN =
            Pattern.compile(
                    "@(Get|Post|Put|Delete|Patch)"
                            + "\\s*\\(\\s*(?:'([^']*)'|\"([^\"]*)\")?\\s*\\)");

    /** Maps NestJS decorator names to HTTP methods. */
    private static final Map<String, String> NESTJS_HTTP_METHOD_MAP = Map.of(
            "Get", "GET",
            "Post", "POST",
            "Put", "PUT",
            "Delete", "DELETE",
            "Patch", "PATCH"
    );

    /** Keywords that match TS_FUNCTION_PATTERN but are not methods. */
    private static final Set<String> KEYWORD_EXCLUSIONS = Set.of(
            "if", "for", "while", "switch", "catch", "require", "import");

    /** Matches ES6 named import to extract imported names and path. */
    private static final Pattern ES6_NAMED_IMPORT_PATTERN = Pattern.compile(
            "import\\s+\\{([^}]+)}\\s+from\\s+['\"](\\..*?)['\"]");

    /** {@inheritDoc} */
    @Override
    public String language() {
        return "typescript";
    }

    /** {@inheritDoc} */
    @Override
    public String sourceRoot() {
        return SOURCE_ROOT;
    }

    /**
     * Discovers all TypeScript and JavaScript source files under src/.
     *
     * <p>Excludes test files (*.spec.*, *.test.*), declaration files
     * (*.d.ts), and directories like node_modules, dist, .next, build,
     * coverage, __tests__, and __mocks__.</p>
     *
     * @param projectRoot the local project root directory
     * @return list of source file paths
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
                    .filter(this::hasValidExtension)
                    .filter(this::isNotInExcludedDir)
                    .filter(this::isNotTestFile)
                    .filter(this::isNotDeclarationFile)
                    .sorted()
                    .toList();
        }
    }

    /**
     * Converts a source file path to a dot-separated identifier.
     *
     * <p>Strips the source root prefix and file extension, then replaces
     * path separators with dots. This produces identifiers compatible
     * with {@code SourceClass.extractSimpleName()} which splits on the
     * last dot.</p>
     *
     * @param file the source file path
     * @param sourceRoot the resolved source root path
     * @return the identifier (e.g., "services.user.service")
     */
    @Override
    protected String extractIdentifier(final Path file,
            final Path sourceRoot) {

        final Path relative = sourceRoot.relativize(file);
        String result = relative.toString();

        // Strip the extension
        final int dotIndex = result.lastIndexOf('.');
        if (dotIndex > 0) {
            result = result.substring(0, dotIndex);
        }

        return result.replace('/', '.').replace('\\', '.');
    }

    /**
     * Extracts internal dependencies from a Node.js/TypeScript source file.
     *
     * <p>Parses ES6 import and CommonJS require statements with relative
     * paths, resolves them against the current file directory, and filters
     * to only include dependencies that match known identifiers within
     * the project.</p>
     *
     * @param file the source file to analyze
     * @param knownIdentifiers all known identifiers in the project
     * @return set of dependency identifiers
     * @throws IOException if file reading fails
     */
    @Override
    protected Set<String> extractDependencies(final Path file,
            final Set<String> knownIdentifiers) throws IOException {

        final Set<String> deps = new HashSet<>();
        final String content = Files.readString(file);
        final Path fileDir = file.getParent();

        extractImportPaths(content, ES6_IMPORT_PATTERN, fileDir,
                knownIdentifiers, deps);
        extractImportPaths(content, REQUIRE_PATTERN, fileDir,
                knownIdentifiers, deps);

        return deps;
    }

    /**
     * Checks if a source file is an entry point.
     *
     * <p>Entry points are detected by:</p>
     * <ul>
     *   <li>Well-known filenames (main, index, app, server)</li>
     *   <li>NestJS {@code @Controller(} annotation</li>
     *   <li>Express route registrations (app.get, router.post, etc.)</li>
     * </ul>
     *
     * @param file the source file to check
     * @return true if the file is an entry point
     * @throws IOException if file reading fails
     */
    @Override
    protected boolean isEntryPoint(final Path file) throws IOException {
        final String filename = file.getFileName().toString();

        if (ENTRY_POINT_FILENAMES.contains(filename)) {
            return true;
        }

        final String content = Files.readString(file);
        return NESTJS_CONTROLLER_PATTERN.matcher(content).find()
                || EXPRESS_ROUTE_PATTERN.matcher(content).find();
    }

    /**
     * Infers the {@link ClassType} from NestJS decorators, Express
     * patterns, or file naming conventions.
     *
     * <p>Detection priority:</p>
     * <ol>
     *   <li>NestJS {@code @Controller(} decorator -> CONTROLLER</li>
     *   <li>NestJS {@code @Injectable(} decorator -> SERVICE</li>
     *   <li>Express route patterns -> CONTROLLER</li>
     *   <li>File naming: *.controller.* -> CONTROLLER,
     *       *.service.* -> SERVICE, *.repository.* -> REPOSITORY,
     *       *.entity.* -> ENTITY</li>
     *   <li>Default: OTHER</li>
     * </ol>
     *
     * @param file the source file to analyze
     * @return the inferred class type
     * @throws IOException if file reading fails
     */
    @Override
    public ClassType inferClassType(final Path file) throws IOException {
        final String content = Files.readString(file);
        final String filename = file.getFileName().toString().toLowerCase();

        // NestJS decorators
        if (NESTJS_CONTROLLER_PATTERN.matcher(content).find()) {
            return ClassType.CONTROLLER;
        }
        if (NESTJS_INJECTABLE_PATTERN.matcher(content).find()) {
            return ClassType.SERVICE;
        }

        // Express patterns
        if (EXPRESS_ROUTE_PATTERN.matcher(content).find()) {
            return ClassType.CONTROLLER;
        }

        // File naming conventions
        if (filename.contains(".controller.")) {
            return ClassType.CONTROLLER;
        }
        if (filename.contains(".service.")) {
            return ClassType.SERVICE;
        }
        if (filename.contains(".repository.")) {
            return ClassType.REPOSITORY;
        }
        if (filename.contains(".entity.")) {
            return ClassType.ENTITY;
        }

        return ClassType.OTHER;
    }

    /**
     * Extracts method/function declarations from a TypeScript/JavaScript
     * source file with line numbers and NestJS HTTP decorators.
     *
     * <p>Iterates through the source lines, identifies function and
     * method declarations, tracks their line numbers, and checks
     * preceding lines for NestJS HTTP decorators.</p>
     *
     * @param file the source file to analyze
     * @return list of statically extracted method information
     * @throws IOException if file reading fails
     */
    @Override
    public List<StaticMethodInfo> extractMethods(final Path file)
            throws IOException {

        final List<String> lines = Files.readAllLines(file);
        final List<StaticMethodInfo> result = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            final String line = lines.get(i).trim();

            // Skip decorators, imports, and empty lines
            if (line.startsWith("@") || line.startsWith("import ")
                    || line.isEmpty()) {
                continue;
            }

            // Skip lines that are clearly not declarations
            if (line.startsWith("return ") || line.startsWith("const ")
                    || line.startsWith("let ") || line.startsWith("var ")
                    || line.startsWith("export default")
                    || line.startsWith("//") || line.startsWith("/*")
                    || line.startsWith("*")) {
                continue;
            }

            final Matcher funcMatcher = TS_FUNCTION_PATTERN.matcher(line);
            if (!funcMatcher.find()) {
                continue;
            }

            // Ensure the match is at a declaration position (starts at
            // or near the beginning of the line, not mid-expression)
            if (funcMatcher.start() > 0) {
                final String prefix = line.substring(0,
                        funcMatcher.start()).trim();
                if (!prefix.isEmpty()
                        && !prefix.matches("(?:export\\s+)?"
                                + "(?:async\\s+)?(?:static\\s+)?"
                                + "(?:public|private|protected)?\\s*")) {
                    continue;
                }
            }

            final String methodName = funcMatcher.group(1);
            if (KEYWORD_EXCLUSIONS.contains(methodName)) {
                continue;
            }

            final int lineNumber = i + 1; // 1-based

            // Look for NestJS HTTP decorators in preceding lines
            // (scan closest first)
            String httpMethod = null;
            String httpPath = null;

            for (int j = i - 1; j >= Math.max(0, i - 3); j--) {
                final String decoratorLine = lines.get(j).trim();
                final Matcher httpMatcher =
                        NESTJS_HTTP_DECORATOR_PATTERN.matcher(decoratorLine);
                if (httpMatcher.find()) {
                    final String decoratorName = httpMatcher.group(1);
                    httpMethod = NESTJS_HTTP_METHOD_MAP.get(decoratorName);
                    httpPath = httpMatcher.group(2) != null
                            ? httpMatcher.group(2) : httpMatcher.group(3);
                    break;
                }
            }

            // TypeScript/JavaScript doesn't have throws clauses
            result.add(new StaticMethodInfo(
                    methodName, lineNumber, httpMethod, httpPath, List.of()));
        }

        return result;
    }

    /**
     * Extracts method parameters from a TypeScript/JavaScript source file.
     *
     * <p>Parses import statements to build a type resolution map (imported
     * name to project identifier), then scans function/method declarations
     * for TypeScript type annotations and matches them against known
     * identifiers.</p>
     *
     * @param file the source file to analyze
     * @param sourceRoot the resolved source root path
     * @param knownIdentifiers all known identifiers in the project
     * @return map of method name to list of known parameter type identifiers
     * @throws IOException if file reading fails
     */
    @Override
    public Map<String, List<String>> extractMethodParameters(
            final Path file, final Path sourceRoot,
            final Set<String> knownIdentifiers) throws IOException {

        final String content = Files.readString(file);
        final Path fileDir = file.getParent();

        // Build a map from imported type name to project identifier
        final Map<String, String> typeMap = buildTypeImportMap(
                content, fileDir, knownIdentifiers);

        final Map<String, List<String>> result = new HashMap<>();

        final Matcher funcMatcher = TS_FUNCTION_PATTERN.matcher(content);
        while (funcMatcher.find()) {
            final String methodName = funcMatcher.group(1);
            final String paramList = funcMatcher.group(2).trim();

            // Skip common keywords that match the pattern
            if ("if".equals(methodName) || "for".equals(methodName)
                    || "while".equals(methodName)
                    || "switch".equals(methodName)
                    || "catch".equals(methodName)
                    || "require".equals(methodName)
                    || "import".equals(methodName)) {
                continue;
            }

            if (paramList.isEmpty()) {
                continue;
            }

            final List<String> matchedParams =
                    resolveTypescriptParams(paramList, typeMap);

            if (!matchedParams.isEmpty()) {
                result.put(methodName, matchedParams);
            }
        }

        return result;
    }

    /**
     * Resolves TypeScript parameter type annotations against known types.
     *
     * <p>Parses parameter declarations of the form
     * {@code paramName: TypeName} and matches TypeName against the
     * import-to-identifier map.</p>
     */
    private List<String> resolveTypescriptParams(
            final String paramList,
            final Map<String, String> typeMap) {

        final List<String> matched = new ArrayList<>();
        final String[] params = paramList.split(",");

        for (final String param : params) {
            final String trimmed = param.trim();

            // Look for `: TypeName` pattern
            final int colonIdx = trimmed.indexOf(':');
            if (colonIdx < 0) {
                continue;
            }

            String typeName = trimmed.substring(colonIdx + 1).trim();

            // Strip generics: Array<Foo> -> Array
            final int genericIdx = typeName.indexOf('<');
            if (genericIdx > 0) {
                typeName = typeName.substring(0, genericIdx);
            }

            // Strip array suffix
            if (typeName.endsWith("[]")) {
                typeName = typeName.substring(0, typeName.length() - 2);
            }

            typeName = typeName.trim();

            final String resolved = typeMap.get(typeName);
            if (resolved != null) {
                matched.add(resolved);
            }
        }

        return matched;
    }

    /**
     * Builds a map from imported type names to project identifiers by
     * parsing ES6 named imports with relative paths.
     */
    private Map<String, String> buildTypeImportMap(
            final String content, final Path fileDir,
            final Set<String> knownIdentifiers) {

        final Map<String, String> typeMap = new HashMap<>();

        final Matcher matcher = ES6_NAMED_IMPORT_PATTERN.matcher(content);
        while (matcher.find()) {
            final String importedNames = matcher.group(1);
            final String importPath = matcher.group(2);

            final String resolvedId = resolveImport(importPath, fileDir,
                    knownIdentifiers);
            if (resolvedId == null) {
                continue;
            }

            // Map each imported name to the resolved identifier
            for (final String name : importedNames.split(",")) {
                final String trimmed = name.trim();
                if (!trimmed.isEmpty()) {
                    // Handle `Name as Alias` - use the alias
                    final int asIdx = trimmed.indexOf(" as ");
                    final String key = asIdx >= 0
                            ? trimmed.substring(asIdx + 4).trim()
                            : trimmed;
                    typeMap.put(key, resolvedId);
                }
            }
        }

        return typeMap;
    }

    /**
     * Extracts import paths from content using the given regex pattern,
     * resolves them relative to the file directory, and adds matches
     * to the deps set.
     */
    private void extractImportPaths(final String content,
            final Pattern pattern, final Path fileDir,
            final Set<String> knownIdentifiers, final Set<String> deps) {

        final Matcher matcher = pattern.matcher(content);

        while (matcher.find()) {
            final String importPath = matcher.group(1);
            final String resolved = resolveImport(importPath, fileDir,
                    knownIdentifiers);
            if (resolved != null) {
                deps.add(resolved);
            }
        }
    }

    /**
     * Resolves a relative import path to a known project identifier.
     *
     * <p>Tries the direct path first, then with /index for directory
     * imports. The import path is resolved against the file's directory
     * and then converted to the dot-separated identifier format.</p>
     */
    private String resolveImport(final String importPath,
            final Path fileDir, final Set<String> knownIdentifiers) {

        final Path resolved = fileDir.resolve(importPath).normalize();

        // Try to find the source root in the path to extract identifier
        final String fullPath = resolved.toString()
                .replace('\\', '/');

        final int srcIndex = fullPath.indexOf("/" + SOURCE_ROOT + "/");
        if (srcIndex < 0) {
            return null;
        }

        final String relativePath = fullPath.substring(
                srcIndex + SOURCE_ROOT.length() + 2);

        final String candidate = relativePath.replace('/', '.');

        // Direct match
        if (knownIdentifiers.contains(candidate)) {
            return candidate;
        }

        // Try with /index for directory imports
        final String indexCandidate = candidate + ".index";
        if (knownIdentifiers.contains(indexCandidate)) {
            return indexCandidate;
        }

        return null;
    }

    private boolean hasValidExtension(final Path path) {
        final String name = path.getFileName().toString();
        for (final String ext : EXTENSIONS) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private boolean isNotInExcludedDir(final Path path) {
        for (final Path component : path) {
            if (EXCLUDED_DIRS.contains(component.toString())) {
                return false;
            }
        }
        return true;
    }

    private boolean isNotTestFile(final Path path) {
        final String name = path.getFileName().toString();
        return !name.contains(".spec.") && !name.contains(".test.");
    }

    private boolean isNotDeclarationFile(final Path path) {
        return !path.getFileName().toString().endsWith(".d.ts");
    }

}
