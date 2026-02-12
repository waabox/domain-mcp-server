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
import java.util.stream.Stream;

/**
 * Node.js/TypeScript parser using GraalJS + Babel AST for accurate
 * source code analysis.
 *
 * <p>Unlike the regex-based {@link NodeJsSourceParser}, this parser
 * delegates per-file AST parsing to {@link GraalJsAnalyzerEngine}
 * which runs Babel inside the JVM. Each file is analyzed individually
 * to avoid statement count accumulation in GraalJS. Cross-file
 * resolution (dependencies, parameter types) is performed in Java.</p>
 *
 * <p>The analysis flow is:</p>
 * <ol>
 *   <li>Walk filesystem to find .ts/.tsx/.js/.jsx files</li>
 *   <li>Detect framework from package.json (single GraalJS call)</li>
 *   <li>Analyze each file individually (one GraalJS call per file)</li>
 *   <li>Cache per-file results (methods, raw imports, class type)</li>
 *   <li>Resolve dependencies and parameters in Java using cached
 *       raw imports and path resolution</li>
 * </ol>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public class NodeJsGraalParser extends SourceParser {

    private static final Logger LOG = LoggerFactory.getLogger(
            NodeJsGraalParser.class);

    private static final String SOURCE_ROOT = "src";

    private static final Set<String> EXTENSIONS = Set.of(
            ".ts", ".tsx", ".js", ".jsx");

    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "node_modules", "dist", ".next", "build", "coverage",
            "__tests__", "__mocks__");

    /** Maximum file size to read (5 MB). Larger files are skipped. */
    private static final long MAX_FILE_SIZE_BYTES = 5L * 1024 * 1024;

    /** Cached analysis results keyed by absolute file path. */
    private final Map<String, FileAnalysisResult> cache =
            new HashMap<>();

    /** Source root detected by framework analysis. */
    private String detectedSourceRoot = SOURCE_ROOT;

    /** Detected framework name for per-file analysis. */
    private String detectedFrameworkName = "unknown";

    /** {@inheritDoc} */
    @Override
    public String language() {
        return "typescript";
    }

    /**
     * Returns the source root directory.
     *
     * <p><b>Note:</b> This value is only accurate after
     * {@link #discoverFiles(Path)} has been called, which triggers
     * framework detection. Before that, returns the default "src".</p>
     *
     * @return the source root path (e.g. "src", "app")
     */
    @Override
    public String sourceRoot() {
        return detectedSourceRoot;
    }

    /**
     * Discovers all source files and runs per-file Babel AST analysis.
     *
     * <p>Creates a single GraalJS engine, detects the framework from
     * package.json, then analyzes each file individually. Results are
     * cached for subsequent lookups. Cross-file resolution is deferred
     * to {@link #extractDependencies} and
     * {@link #extractMethodParameters}.</p>
     *
     * @param projectRoot the local project root directory
     * @return list of discovered source file paths
     * @throws IOException if file reading or analysis fails
     */
    @Override
    protected List<Path> discoverFiles(final Path projectRoot)
            throws IOException {

        // Read package.json
        final Path packageJsonPath = projectRoot.resolve("package.json");
        final String packageJsonContent;
        if (Files.exists(packageJsonPath)) {
            packageJsonContent = Files.readString(packageJsonPath);
        } else {
            packageJsonContent = "{}";
        }

        // Discover all source files across the project
        final List<Path> sourceFiles = walkSourceFiles(projectRoot);

        if (sourceFiles.isEmpty()) {
            LOG.warn("No source files found in {}", projectRoot);
            return List.of();
        }

        LOG.info("Found {} source files to analyze", sourceFiles.size());

        final long startTime = System.currentTimeMillis();

        try (GraalJsAnalyzerEngine engine = new GraalJsAnalyzerEngine()) {
            // Detect framework
            final FrameworkInfo framework = engine.detectFramework(
                    packageJsonContent);
            this.detectedFrameworkName = framework.name();

            // Validate and update source root
            final String candidateRoot = framework.sourceRoot();
            if (Files.isDirectory(projectRoot.resolve(candidateRoot))) {
                this.detectedSourceRoot = candidateRoot;
            } else {
                LOG.warn("Detected source root '{}' does not exist,"
                        + " using default 'src'", candidateRoot);
                this.detectedSourceRoot = SOURCE_ROOT;
            }

            LOG.info("Framework: {} (sourceRoot: {})",
                    detectedFrameworkName, detectedSourceRoot);

            // Analyze each file individually
            int analyzed = 0;
            int skipped = 0;

            for (final Path file : sourceFiles) {
                if (Files.size(file) > MAX_FILE_SIZE_BYTES) {
                    LOG.warn("Skipping large file: {} ({} bytes)",
                            file, Files.size(file));
                    skipped++;
                    continue;
                }

                final String relativePath = projectRoot.relativize(file)
                        .toString().replace('\\', '/');
                final String content = Files.readString(file);

                try {
                    final FileAnalysisResult result =
                            engine.analyzeFile(content, relativePath,
                                    detectedFrameworkName);

                    cache.put(file.normalize().toString(), result);
                    analyzed++;
                } catch (final Exception e) {
                    LOG.warn("Failed to analyze file: {}",
                            relativePath, e);
                    skipped++;
                }
            }

            final long elapsed = System.currentTimeMillis() - startTime;
            LOG.info("Analyzed {} files, skipped {} in {}ms",
                    analyzed, skipped, elapsed);
        }

        // Return only files under the detected source root
        final Path sourceRootPath = projectRoot.resolve(
                detectedSourceRoot);

        return sourceFiles.stream()
                .filter(f -> f.startsWith(sourceRootPath))
                .filter(f -> cache.containsKey(f.normalize().toString()))
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    protected String extractIdentifier(final Path file,
            final Path sourceRoot) {

        final Path relative = sourceRoot.relativize(file);
        String result = relative.toString();
        final int dotIndex = result.lastIndexOf('.');
        if (dotIndex > 0) {
            result = result.substring(0, dotIndex);
        }
        return result.replace('/', '.').replace('\\', '.');
    }

    /**
     * Extracts internal dependencies from cached raw imports.
     *
     * <p>Resolves relative import paths (starting with ".") against
     * the file's directory and matches them to known project
     * identifiers.</p>
     *
     * @param file the source file to analyze
     * @param knownIdentifiers all known identifiers in the project
     * @return set of dependency identifiers
     * @throws IOException if file reading fails
     */
    @Override
    protected Set<String> extractDependencies(final Path file,
            final Set<String> knownIdentifiers) throws IOException {

        final FileAnalysisResult cached = cache.get(
                file.normalize().toString());
        if (cached == null) {
            return Set.of();
        }

        final Set<String> deps = new HashSet<>();
        final Path fileDir = file.getParent();

        for (final RawImport imp : cached.rawImports()) {
            if (!imp.source().startsWith(".")) {
                continue;
            }

            final String resolved = resolveImport(
                    imp.source(), fileDir, knownIdentifiers);
            if (resolved != null) {
                deps.add(resolved);
            }
        }

        return deps;
    }

    /** {@inheritDoc} */
    @Override
    protected boolean isEntryPoint(final Path file) throws IOException {
        final FileAnalysisResult cached = cache.get(
                file.normalize().toString());
        return cached != null && cached.entryPoint();
    }

    /** {@inheritDoc} */
    @Override
    public ClassType inferClassType(final Path file) throws IOException {
        final FileAnalysisResult cached = cache.get(
                file.normalize().toString());
        if (cached == null) {
            return ClassType.OTHER;
        }
        return ClassType.fromString(cached.classType());
    }

    /** {@inheritDoc} */
    @Override
    public List<StaticMethodInfo> extractMethods(final Path file)
            throws IOException {

        final FileAnalysisResult cached = cache.get(
                file.normalize().toString());
        if (cached == null) {
            return List.of();
        }

        final List<StaticMethodInfo> result = new ArrayList<>();
        for (final MethodAnalysisResult method : cached.methods()) {
            result.add(new StaticMethodInfo(
                    method.name(),
                    method.lineNumber(),
                    method.httpMethod(),
                    method.httpPath(),
                    List.of()));
        }
        return result;
    }

    /**
     * Extracts method parameters using cached raw imports for type
     * resolution.
     *
     * <p>Builds a type map from raw imports (local name to project
     * identifier), then matches method parameter TypeScript type
     * names against the map.</p>
     *
     * @param file the source file to analyze
     * @param sourceRoot the resolved source root path
     * @param knownIdentifiers all known identifiers in the project
     * @return map of method name to list of known parameter type
     *         identifiers
     * @throws IOException if file reading fails
     */
    @Override
    public Map<String, List<String>> extractMethodParameters(
            final Path file, final Path sourceRoot,
            final Set<String> knownIdentifiers) throws IOException {

        final FileAnalysisResult cached = cache.get(
                file.normalize().toString());
        if (cached == null) {
            return Map.of();
        }

        // Build type map: local import name -> project identifier
        final Map<String, String> typeMap = buildTypeImportMap(
                cached.rawImports(), file.getParent(), knownIdentifiers);

        final Map<String, List<String>> result = new HashMap<>();

        for (final MethodAnalysisResult method : cached.methods()) {
            final List<String> matched = new ArrayList<>();

            for (final String typeName : method.parameterTypes()) {
                final String resolved = typeMap.get(typeName);
                if (resolved != null
                        && knownIdentifiers.contains(resolved)) {
                    matched.add(resolved);
                }
            }

            if (!matched.isEmpty()) {
                result.put(method.name(), matched);
            }
        }

        return result;
    }

    /**
     * Builds a map from imported type names to project identifiers
     * by resolving relative import paths.
     */
    private Map<String, String> buildTypeImportMap(
            final List<RawImport> rawImports,
            final Path fileDir,
            final Set<String> knownIdentifiers) {

        final Map<String, String> typeMap = new HashMap<>();

        for (final RawImport imp : rawImports) {
            if (!imp.source().startsWith(".")) {
                continue;
            }

            final String resolved = resolveImport(
                    imp.source(), fileDir, knownIdentifiers);
            if (resolved != null) {
                typeMap.put(imp.localName(), resolved);
            }
        }

        return typeMap;
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
        final String fullPath = resolved.toString()
                .replace('\\', '/');

        final int srcIndex = fullPath.indexOf(
                "/" + detectedSourceRoot + "/");
        if (srcIndex < 0) {
            return null;
        }

        final String relativePath = fullPath.substring(
                srcIndex + detectedSourceRoot.length() + 2);

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

    /**
     * Walks the project root to find all source files, excluding
     * test files, declaration files, and excluded directories.
     */
    private List<Path> walkSourceFiles(final Path projectRoot)
            throws IOException {

        try (Stream<Path> walk = Files.walk(projectRoot)) {
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
