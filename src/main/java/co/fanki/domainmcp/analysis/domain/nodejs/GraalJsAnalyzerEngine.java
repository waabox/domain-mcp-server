package co.fanki.domainmcp.analysis.domain.nodejs;

import co.fanki.domainmcp.shared.Preconditions;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes the Babel AST analyzer inside a GraalJS polyglot context.
 *
 * <p>Loads the pre-built analyzer-bundle.js from the classpath, creates
 * a sandboxed GraalJS context (no I/O access), and exposes per-file
 * analysis and framework detection functions.</p>
 *
 * <p>The engine processes files individually rather than all at once,
 * avoiding GraalJS statement count accumulation issues with large
 * projects. The context is created once and reused for all files,
 * then closed via {@link #close()}.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public class GraalJsAnalyzerEngine implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(
            GraalJsAnalyzerEngine.class);

    private static final String BUNDLE_RESOURCE =
            "js/analyzer/dist/analyzer-bundle.js";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Polyfill for Node.js globals that Babel expects at runtime. */
    private static final String PROCESS_POLYFILL = """
            if (typeof globalThis.process === 'undefined') {
                globalThis.process = { env: {} };
            }
            if (typeof globalThis.console === 'undefined') {
                globalThis.console = {
                    log: function() {},
                    warn: function() {},
                    error: function() {}
                };
            }
            """;

    private final Context context;
    private final Value analyzeFileFunction;
    private final Value detectFrameworkFunction;

    /**
     * Creates a new engine, loading the analyzer bundle from classpath
     * into a sandboxed GraalJS context.
     *
     * <p>The context is kept alive for the lifetime of this engine
     * to avoid reloading the bundle for each file. Call {@link #close()}
     * when done to release resources.</p>
     *
     * @throws IOException if the bundle resource cannot be loaded
     */
    public GraalJsAnalyzerEngine() throws IOException {
        final String bundleSource = loadBundleFromClasspath();
        LOG.info("GraalJS analyzer bundle loaded ({} bytes)",
                bundleSource.length());

        this.context = createContext();

        try {
            context.eval("js", PROCESS_POLYFILL);
            context.eval(Source.newBuilder("js", bundleSource,
                    "analyzer-bundle.js").build());

            this.analyzeFileFunction =
                    context.getBindings("js").getMember("analyzeFile");
            this.detectFrameworkFunction =
                    context.getBindings("js").getMember(
                            "detectFrameworkFromPackageJson");

            if (analyzeFileFunction == null
                    || !analyzeFileFunction.canExecute()) {
                throw new IllegalStateException(
                        "analyzeFile function not found in bundle");
            }
            if (detectFrameworkFunction == null
                    || !detectFrameworkFunction.canExecute()) {
                throw new IllegalStateException(
                        "detectFrameworkFromPackageJson function"
                                + " not found in bundle");
            }
        } catch (final Exception e) {
            context.close();
            throw e;
        }
    }

    /**
     * Detects the framework from the package.json content.
     *
     * @param packageJsonContent the raw content of package.json
     * @return the detected framework information
     * @throws IOException if JSON parsing fails
     */
    public FrameworkInfo detectFramework(
            final String packageJsonContent) throws IOException {

        Preconditions.requireNonNull(packageJsonContent,
                "package.json content is required");

        final Value result = detectFrameworkFunction.execute(
                packageJsonContent);
        final JsonNode node = MAPPER.readTree(result.asString());

        return new FrameworkInfo(
                node.get("name").asText(),
                node.get("sourceRoot").asText(),
                MAPPER.convertValue(node.get("features"),
                        new TypeReference<Map<String, String>>() {}));
    }

    /**
     * Analyzes a single file using Babel AST parsing.
     *
     * <p>Returns per-file extraction results (methods, raw imports,
     * class type, entry point). Cross-file resolution is handled
     * separately in Java.</p>
     *
     * @param content the file content to analyze
     * @param filePath the relative file path
     * @param frameworkName the detected framework name
     * @return the file analysis result
     * @throws IOException if JSON serialization or parsing fails
     */
    public FileAnalysisResult analyzeFile(
            final String content,
            final String filePath,
            final String frameworkName) throws IOException {

        Preconditions.requireNonNull(content, "File content is required");
        Preconditions.requireNonNull(filePath, "File path is required");
        Preconditions.requireNonNull(frameworkName,
                "Framework name is required");

        final Map<String, String> input = new HashMap<>();
        input.put("content", content);
        input.put("filePath", filePath);
        input.put("frameworkName", frameworkName);

        final String inputJson = MAPPER.writeValueAsString(input);
        final Value result = analyzeFileFunction.execute(inputJson);

        return parseFileResult(result.asString());
    }

    /** {@inheritDoc} */
    @Override
    public void close() {
        context.close();
    }

    /**
     * Creates a sandboxed GraalJS context with no I/O access.
     *
     * <p>No statement limit is applied because AST traversal is
     * inherently statement-heavy and the context is sandboxed
     * (no I/O, no network). CPU protection is provided by
     * HTTP timeouts at the controller level.</p>
     */
    private static Context createContext() {
        return Context.newBuilder("js")
                .allowExperimentalOptions(true)
                .option("js.ecmascript-version", "2022")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
    }

    /**
     * Parses a single file analysis result from the JSON output.
     */
    private FileAnalysisResult parseFileResult(final String json)
            throws IOException {

        final JsonNode node = MAPPER.readTree(json);

        final String classType = node.get("classType").asText();
        final boolean entryPoint = node.get("entryPoint").asBoolean();

        final List<MethodAnalysisResult> methods = new ArrayList<>();
        for (final JsonNode methodNode : node.get("methods")) {
            methods.add(new MethodAnalysisResult(
                    methodNode.get("name").asText(),
                    methodNode.get("lineNumber").asInt(),
                    methodNode.has("httpMethod")
                            && !methodNode.get("httpMethod").isNull()
                            ? methodNode.get("httpMethod").asText() : null,
                    methodNode.has("httpPath")
                            && !methodNode.get("httpPath").isNull()
                            ? methodNode.get("httpPath").asText() : null,
                    MAPPER.convertValue(methodNode.get("parameterTypes"),
                            new TypeReference<List<String>>() {})));
        }

        final List<RawImport> rawImports = new ArrayList<>();
        for (final JsonNode impNode : node.get("imports")) {
            rawImports.add(new RawImport(
                    impNode.get("importedName").asText(),
                    impNode.get("localName").asText(),
                    impNode.get("source").asText()));
        }

        return new FileAnalysisResult(
                null, classType, entryPoint, methods, rawImports);
    }

    /**
     * Loads the analyzer bundle JavaScript from the classpath.
     */
    private static String loadBundleFromClasspath() throws IOException {
        try (InputStream is = GraalJsAnalyzerEngine.class.getClassLoader()
                .getResourceAsStream(BUNDLE_RESOURCE)) {
            if (is == null) {
                throw new IOException(
                        "Analyzer bundle not found on classpath: "
                                + BUNDLE_RESOURCE);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
