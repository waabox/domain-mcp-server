package co.fanki.domainmcp.analysis.domain.golang;

import co.fanki.domainmcp.analysis.domain.ClassType;
import co.fanki.domainmcp.analysis.domain.SourceParser;
import co.fanki.domainmcp.analysis.domain.StaticMethodInfo;
import co.fanki.domainmcp.analysis.domain.golang.GoAnalysisResult.GoFunctionInfo;
import co.fanki.domainmcp.analysis.domain.golang.GoAnalysisResult.GoPackageInfo;
import co.fanki.domainmcp.analysis.domain.golang.GoAnalysisResult.GoParamInfo;
import co.fanki.domainmcp.analysis.domain.golang.GoAnalysisResult.GoStructInfo;

import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.concurrent.TimeUnit;

/**
 * Go-specific implementation of {@link SourceParser}.
 *
 * <p>Uses an external Go AST analyzer tool ({@code go-analyzer}) to perform
 * deep analysis of Go source code. The tool uses Go's standard library
 * {@code go/ast} and {@code go/parser} packages for accurate parsing of
 * structs, interfaces, functions, methods, and import relationships.</p>
 *
 * <h3>Architecture</h3>
 * <p>The analysis is performed in two phases:</p>
 * <ol>
 *   <li><b>External analysis:</b> The Go binary is invoked via
 *       {@link ProcessBuilder} and produces a JSON file with the full
 *       project analysis (packages, structs, functions, imports).</li>
 *   <li><b>Graph mapping:</b> The {@link SourceParser#parse(Path)} template
 *       method calls our abstract method implementations which read from
 *       the cached analysis result.</li>
 * </ol>
 *
 * <h3>Identifier strategy</h3>
 * <p>Go organizes code by packages, not classes. Each file is mapped to its
 * fully-qualified package path (e.g., "github.com/user/repo/internal/service").
 * Multiple files in the same directory share the same package identifier.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public class GoSourceParser extends SourceParser {

    private static final Logger LOG = LoggerFactory.getLogger(
            GoSourceParser.class);

    /** Source root for Go is the project root itself. */
    private static final String SOURCE_ROOT = ".";

    /** Timeout for the Go analyzer process in seconds. */
    private static final int ANALYZER_TIMEOUT_SECONDS = 120;

    /** Directories to exclude from file discovery. */
    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "vendor", "testdata", ".git", "node_modules",
            "third_party", "tools");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Cached analysis result from the Go AST tool. */
    private GoAnalysisResult analysisResult;

    /** Maps package path to its analysis info for fast lookup. */
    private Map<String, GoPackageInfo> packageIndex;

    /** Maps file path (relative to project root) to its package path. */
    private Map<String, String> fileToPackage;

    /** {@inheritDoc} */
    @Override
    public String language() {
        return "go";
    }

    /** {@inheritDoc} */
    @Override
    public String sourceRoot() {
        return SOURCE_ROOT;
    }

    /**
     * Discovers all .go source files in the project.
     *
     * <p>Also triggers the external Go analyzer to produce the full
     * analysis result, which is cached for subsequent method calls.</p>
     *
     * @param projectRoot the local project root directory
     * @return list of .go file paths
     * @throws IOException if file discovery or analysis fails
     */
    @Override
    protected List<Path> discoverFiles(final Path projectRoot)
            throws IOException {

        // Run the Go analyzer first and cache the result
        runGoAnalyzer(projectRoot);

        // Build indexes from the analysis result
        buildIndexes(projectRoot);

        // Return all discovered .go files (same as what the Go tool found)
        final List<Path> files = new ArrayList<>();
        if (analysisResult != null && analysisResult.packages() != null) {
            for (final GoPackageInfo pkg : analysisResult.packages()) {
                if (pkg.files() == null) {
                    continue;
                }
                for (final String fileName : pkg.files()) {
                    final String dir = pkg.dir();
                    final Path filePath;
                    if (dir == null || dir.isEmpty()) {
                        filePath = projectRoot.resolve(fileName);
                    } else {
                        filePath = projectRoot.resolve(dir).resolve(fileName);
                    }
                    if (Files.exists(filePath)) {
                        files.add(filePath);
                    }
                }
            }
        }

        files.sort(Path::compareTo);
        return files;
    }

    /**
     * Extracts a fully-qualified package path as the identifier.
     *
     * <p>In Go, files in the same directory belong to the same package.
     * The identifier is the module path + relative directory path.</p>
     *
     * @param file the .go file path
     * @param sourceRoot the resolved source root path (project root)
     * @return the fully-qualified package path
     */
    @Override
    protected String extractIdentifier(final Path file,
            final Path sourceRoot) {

        final String relativePath = sourceRoot.relativize(file).toString()
                .replace('\\', '/');

        final String pkgPath = fileToPackage.get(relativePath);
        if (pkgPath != null) {
            return pkgPath;
        }

        // Fallback: derive from directory structure
        final Path parent = sourceRoot.relativize(file).getParent();
        if (parent == null) {
            return analysisResult != null ? analysisResult.module() : "unknown";
        }
        return (analysisResult != null ? analysisResult.module() : "unknown")
                + "/" + parent.toString().replace('\\', '/');
    }

    /**
     * Extracts internal import dependencies from a Go source file.
     *
     * <p>Uses the pre-computed analysis result to look up the package
     * this file belongs to, then returns its internal imports.</p>
     *
     * @param file the Go source file
     * @param knownIdentifiers all known identifiers in the project
     * @return set of dependency identifiers (package paths)
     * @throws IOException if lookup fails
     */
    @Override
    protected Set<String> extractDependencies(final Path file,
            final Set<String> knownIdentifiers) throws IOException {

        final Set<String> deps = new HashSet<>();
        final GoPackageInfo pkg = findPackageForFile(file);

        if (pkg != null && pkg.imports() != null) {
            for (final String imp : pkg.imports()) {
                if (knownIdentifiers.contains(imp)) {
                    deps.add(imp);
                }
            }
        }

        return deps;
    }

    /**
     * Checks if a Go source file is an entry point.
     *
     * @param file the Go source file to check
     * @return true if the file's package is an entry point
     * @throws IOException if lookup fails
     */
    @Override
    protected boolean isEntryPoint(final Path file) throws IOException {
        final GoPackageInfo pkg = findPackageForFile(file);
        return pkg != null && pkg.isEntryPoint();
    }

    /**
     * Infers the {@link ClassType} from the Go analyzer's classification.
     *
     * @param file the Go source file to analyze
     * @return the inferred class type
     * @throws IOException if lookup fails
     */
    @Override
    public ClassType inferClassType(final Path file) throws IOException {
        final GoPackageInfo pkg = findPackageForFile(file);
        if (pkg != null && pkg.classType() != null) {
            return ClassType.fromString(pkg.classType());
        }
        return ClassType.OTHER;
    }

    /**
     * Extracts function and method declarations from a Go source file.
     *
     * <p>Uses the pre-computed analysis result which includes all functions,
     * struct methods, line numbers, and HTTP handler information.</p>
     *
     * @param file the Go source file to analyze
     * @return list of statically extracted method information
     * @throws IOException if lookup fails
     */
    @Override
    public List<StaticMethodInfo> extractMethods(final Path file)
            throws IOException {

        final List<StaticMethodInfo> result = new ArrayList<>();
        final GoPackageInfo pkg = findPackageForFile(file);

        if (pkg == null) {
            return result;
        }

        final String baseName = file.getFileName().toString();

        // Package-level functions in this file
        if (pkg.functions() != null) {
            for (final GoFunctionInfo func : pkg.functions()) {
                if (baseName.equals(func.file())) {
                    result.add(toStaticMethodInfo(func));
                }
            }
        }

        // Struct methods in this file
        if (pkg.structs() != null) {
            for (final GoStructInfo struct_ : pkg.structs()) {
                if (struct_.methods() == null) {
                    continue;
                }
                for (final GoFunctionInfo method : struct_.methods()) {
                    if (baseName.equals(method.file())) {
                        result.add(toStaticMethodInfo(method));
                    }
                }
            }
        }

        return result;
    }

    /**
     * Extracts method parameters from a Go source file, returning only
     * parameters whose type matches a known project identifier.
     *
     * @param file the Go source file to analyze
     * @param sourceRoot the resolved source root path
     * @param knownIdentifiers all known identifiers in the project
     * @return map of method name to list of known parameter type identifiers
     * @throws IOException if lookup fails
     */
    @Override
    public Map<String, List<String>> extractMethodParameters(
            final Path file, final Path sourceRoot,
            final Set<String> knownIdentifiers) throws IOException {

        final Map<String, List<String>> result = new HashMap<>();
        final GoPackageInfo pkg = findPackageForFile(file);

        if (pkg == null) {
            return result;
        }

        final String baseName = file.getFileName().toString();

        // Process package-level functions
        if (pkg.functions() != null) {
            for (final GoFunctionInfo func : pkg.functions()) {
                if (baseName.equals(func.file())) {
                    processParamsForMethod(func, knownIdentifiers, result);
                }
            }
        }

        // Process struct methods
        if (pkg.structs() != null) {
            for (final GoStructInfo struct_ : pkg.structs()) {
                if (struct_.methods() == null) {
                    continue;
                }
                for (final GoFunctionInfo method : struct_.methods()) {
                    if (baseName.equals(method.file())) {
                        processParamsForMethod(method, knownIdentifiers,
                                result);
                    }
                }
            }
        }

        return result;
    }

    // ---------------------------------------------------------------
    // Go analyzer invocation
    // ---------------------------------------------------------------

    /**
     * Runs the external Go AST analyzer tool and parses its JSON output.
     *
     * <p>The tool is expected to be available at
     * {@code tools/go-analyzer/cmd/analyzer/} relative to the application
     * working directory. If not available, falls back to a pre-built
     * binary or skips analysis gracefully.</p>
     */
    private void runGoAnalyzer(final Path projectRoot) throws IOException {
        final Path outputFile = Files.createTempFile("go-analysis-", ".json");

        try {
            final String goAnalyzerBin = findGoAnalyzerBinary();

            if (goAnalyzerBin == null) {
                LOG.warn("Go analyzer binary not found; "
                        + "running 'go run' directly");
                runGoAnalyzerViaGoRun(projectRoot, outputFile);
            } else {
                runGoAnalyzerBinary(goAnalyzerBin, projectRoot, outputFile);
            }

            // Parse the JSON output
            if (Files.exists(outputFile) && Files.size(outputFile) > 0) {
                analysisResult = OBJECT_MAPPER.readValue(
                        outputFile.toFile(), GoAnalysisResult.class);
                LOG.info("Go analysis complete: {} packages, module={}",
                        analysisResult.packages() != null
                                ? analysisResult.packages().size() : 0,
                        analysisResult.module());
            } else {
                LOG.warn("Go analyzer produced no output");
                analysisResult = new GoAnalysisResult("unknown", List.of());
            }

        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Go analyzer interrupted", e);
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    /**
     * Runs the Go analyzer via 'go run' command (development mode).
     */
    private void runGoAnalyzerViaGoRun(
            final Path projectRoot,
            final Path outputFile
    ) throws IOException, InterruptedException {

        // Find the go-analyzer source
        final Path analyzerDir = findGoAnalyzerSource();
        if (analyzerDir == null) {
            LOG.warn("Go analyzer source not found; "
                    + "analysis will be limited");
            analysisResult = new GoAnalysisResult("unknown", List.of());
            return;
        }

        final ProcessBuilder pb = new ProcessBuilder(
                "go", "run", "./cmd/analyzer",
                "-o", outputFile.toAbsolutePath().toString(),
                projectRoot.toAbsolutePath().toString()
        );
        pb.directory(analyzerDir.toFile());
        pb.redirectErrorStream(false);
        pb.environment().put("GOFLAGS", "-mod=mod");

        LOG.info("Running go-analyzer via 'go run' on: {}", projectRoot);

        final Process process = pb.start();
        final boolean finished = process.waitFor(
                ANALYZER_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Go analyzer timed out after "
                    + ANALYZER_TIMEOUT_SECONDS + " seconds");
        }

        if (process.exitValue() != 0) {
            final String stderr = new String(
                    process.getErrorStream().readAllBytes());
            LOG.warn("Go analyzer exited with code {}: {}",
                    process.exitValue(), stderr);
        }
    }

    /**
     * Runs a pre-built Go analyzer binary.
     */
    private void runGoAnalyzerBinary(
            final String binaryPath,
            final Path projectRoot,
            final Path outputFile
    ) throws IOException, InterruptedException {

        final ProcessBuilder pb = new ProcessBuilder(
                binaryPath,
                "-o", outputFile.toAbsolutePath().toString(),
                projectRoot.toAbsolutePath().toString()
        );
        pb.redirectErrorStream(false);

        LOG.info("Running go-analyzer binary on: {}", projectRoot);

        final Process process = pb.start();
        final boolean finished = process.waitFor(
                ANALYZER_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Go analyzer timed out after "
                    + ANALYZER_TIMEOUT_SECONDS + " seconds");
        }

        if (process.exitValue() != 0) {
            final String stderr = new String(
                    process.getErrorStream().readAllBytes());
            LOG.warn("Go analyzer exited with code {}: {}",
                    process.exitValue(), stderr);
        }
    }

    /**
     * Attempts to find a pre-built go-analyzer binary on the PATH
     * or in well-known locations.
     */
    private String findGoAnalyzerBinary() {
        // Check well-known locations
        final String[] candidates = {
                "go-analyzer",
                "/usr/local/bin/go-analyzer",
                System.getProperty("user.home") + "/go/bin/go-analyzer"
        };

        for (final String candidate : candidates) {
            try {
                final ProcessBuilder pb = new ProcessBuilder(
                        "which", candidate);
                final Process p = pb.start();
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return candidate;
                }
            } catch (final Exception ignored) {
                // binary not found, try next
            }
        }

        return null;
    }

    /**
     * Finds the go-analyzer source directory, checking multiple
     * possible locations.
     */
    private Path findGoAnalyzerSource() {
        final String[] candidates = {
                "tools/go-analyzer",
                "../tools/go-analyzer",
                System.getProperty("user.dir") + "/tools/go-analyzer"
        };

        for (final String candidate : candidates) {
            final Path dir = Path.of(candidate);
            if (Files.exists(dir.resolve("go.mod"))
                    && Files.exists(dir.resolve("cmd/analyzer/main.go"))) {
                return dir;
            }
        }

        return null;
    }

    // ---------------------------------------------------------------
    // Index building
    // ---------------------------------------------------------------

    /**
     * Builds lookup indexes from the analysis result.
     */
    private void buildIndexes(final Path projectRoot) {
        packageIndex = new HashMap<>();
        fileToPackage = new HashMap<>();

        if (analysisResult == null || analysisResult.packages() == null) {
            return;
        }

        for (final GoPackageInfo pkg : analysisResult.packages()) {
            packageIndex.put(pkg.path(), pkg);

            if (pkg.files() != null) {
                for (final String fileName : pkg.files()) {
                    final String dir = pkg.dir();
                    final String relativePath;
                    if (dir == null || dir.isEmpty()) {
                        relativePath = fileName;
                    } else {
                        relativePath = dir + "/" + fileName;
                    }
                    fileToPackage.put(relativePath, pkg.path());
                }
            }
        }
    }

    /**
     * Finds the package info for a given file path.
     */
    private GoPackageInfo findPackageForFile(final Path file) {
        if (packageIndex == null) {
            return null;
        }

        // Try to find via the file index
        for (final Map.Entry<String, String> entry
                : fileToPackage.entrySet()) {
            if (file.toString().replace('\\', '/').endsWith(
                    entry.getKey())) {
                return packageIndex.get(entry.getValue());
            }
        }

        return null;
    }

    // ---------------------------------------------------------------
    // Conversion helpers
    // ---------------------------------------------------------------

    /**
     * Converts a Go function info to a StaticMethodInfo.
     */
    private StaticMethodInfo toStaticMethodInfo(final GoFunctionInfo func) {
        final String methodName;
        if (func.receiver() != null && !func.receiver().isEmpty()) {
            final String cleanReceiver = func.receiver().startsWith("*")
                    ? func.receiver().substring(1)
                    : func.receiver();
            methodName = cleanReceiver + "." + func.name();
        } else {
            methodName = func.name();
        }

        final List<String> exceptions = new ArrayList<>();
        if (func.hasPanic()) {
            exceptions.add("panic");
        }

        return new StaticMethodInfo(
                methodName,
                func.line(),
                func.httpMethod(),
                func.httpPath(),
                exceptions
        );
    }

    /**
     * Processes parameters for a function, adding matched types to result.
     */
    private void processParamsForMethod(
            final GoFunctionInfo func,
            final Set<String> knownIdentifiers,
            final Map<String, List<String>> result) {

        if (func.params() == null || func.params().isEmpty()) {
            return;
        }

        final String methodName;
        if (func.receiver() != null && !func.receiver().isEmpty()) {
            final String cleanReceiver = func.receiver().startsWith("*")
                    ? func.receiver().substring(1)
                    : func.receiver();
            methodName = cleanReceiver + "." + func.name();
        } else {
            methodName = func.name();
        }

        final List<String> matched = new ArrayList<>();
        for (final GoParamInfo param : func.params()) {
            if (param.packagePath() != null
                    && !param.packagePath().isEmpty()
                    && knownIdentifiers.contains(param.packagePath())) {
                matched.add(param.packagePath());
            }
        }

        if (!matched.isEmpty()) {
            result.put(methodName, matched);
        }
    }

}
