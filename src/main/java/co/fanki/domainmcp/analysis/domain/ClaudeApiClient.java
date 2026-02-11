package co.fanki.domainmcp.analysis.domain;

import co.fanki.domainmcp.shared.Preconditions;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Client for analyzing individual source classes using the Claude API.
 *
 * <p>Uses the official Anthropic Java SDK with claude-sonnet-4-5-20250929
 * for single-class analysis. Sonnet provides the optimal balance of
 * cost and quality for focused, per-class analysis tasks.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public class ClaudeApiClient {

    private static final Logger LOG = LoggerFactory.getLogger(
            ClaudeApiClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final long MAX_TOKENS = 4096L;

    private static final String ANALYSIS_PROMPT_TEMPLATE = """
            You are analyzing a single %s source file from a project.

            Project context (from README):
            %s

            Source code of %s:
            ```%s
            %s
            ```

            Return a JSON object with:
            {
              "fullClassName": "fully.qualified.ClassName",
              "classType": "CONTROLLER|SERVICE|REPOSITORY|ENTITY|DTO|\
            CONFIGURATION|LISTENER|UTILITY|EXCEPTION|OTHER",
              "description": "Business-oriented description of what this \
            class/module does",
              "sourceFile": "relative/path/to/File",
              "methods": [
                {
                  "methodName": "methodOrFunctionName",
                  "description": "Business meaning, not code mechanics",
                  "businessLogic": ["Business operation summary"],
                  "dependencies": ["ClassName1", "ClassName2"],
                  "exceptions": ["ExceptionName"],
                  "httpMethod": "GET|POST|PUT|DELETE|PATCH or null",
                  "httpPath": "/api/path or null",
                  "lineNumber": 42
                }
              ]
            }

            RULES:
            1. Focus on BUSINESS meaning, not technical implementation.
            BAD: "Receives request, delegates to service, returns response"
            GOOD: "Searches tickets by date, event, section, fan and status"
            2. Every exported function/public method must be included. \
            No skipping.
            3. Keep descriptions concise: 1 sentence of business meaning.
            4. YOUR RESPONSE MUST BE ONLY A SINGLE RAW JSON OBJECT. \
            The first character must be { and the last must be }.
            """;

    private static final String ENRICHMENT_PROMPT_TEMPLATE = """
            You are enriching a pre-analyzed %s source file with business \
            context.

            Project context (from README):
            %s

            Source code of %s:
            ```%s
            %s
            ```

            I already extracted this structural data:
            - Class type: %s
            - Methods: %s

            Provide ONLY the business meaning. Return JSON:
            {
              "description": "Business-oriented description of this class",
              "classTypeCorrection": "CORRECTED_TYPE or null if my \
            inference is correct",
              "methods": [
                {
                  "methodName": "existingMethodName",
                  "description": "Business meaning, 1 sentence",
                  "businessLogic": ["Step 1", "Step 2"]
                }
              ]
            }

            RULES:
            1. Focus on BUSINESS meaning, not technical implementation.
            2. Every method listed above must be included.
            3. Only correct classType if my inference is clearly wrong.
            4. YOUR RESPONSE MUST BE ONLY RAW JSON. First char { last \
            char }.
            """;

    private final AnthropicClient client;

    /**
     * Creates a new ClaudeApiClient with the given API key.
     *
     * @param apiKey the Anthropic API key
     */
    public ClaudeApiClient(final String apiKey) {
        Preconditions.requireNonBlank(apiKey, "API key is required");
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build();
    }

    /**
     * Analyzes a single class/module and returns structured analysis results.
     *
     * @param sourceCode the full source code of the class/module
     * @param fullClassName the fully-qualified class/module name
     * @param sourceFile the relative source file path
     * @param readmeContext the README content for project context
     * @param language the programming language (e.g., "java", "typescript")
     * @return the analysis result
     */
    public ClassAnalysisResult analyzeClass(
            final String sourceCode,
            final String fullClassName,
            final String sourceFile,
            final String readmeContext,
            final String language) {

        Preconditions.requireNonBlank(sourceCode, "Source code is required");
        Preconditions.requireNonBlank(fullClassName,
                "Full class name is required");

        final String readme = readmeContext != null && !readmeContext.isBlank()
                ? readmeContext : "No README available.";

        final String lang = language != null && !language.isBlank()
                ? language : "java";

        final String prompt = String.format(ANALYSIS_PROMPT_TEMPLATE,
                lang, readme, fullClassName, lang, sourceCode);

        LOG.debug("Analyzing class: {}", fullClassName);

        try {
            final MessageCreateParams params = MessageCreateParams.builder()
                    .maxTokens(MAX_TOKENS)
                    .addUserMessage(prompt)
                    .model(Model.CLAUDE_SONNET_4_5_20250929)
                    .build();

            final Message response = client.messages().create(params);

            final String rawContent = response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(textBlock -> textBlock.text())
                    .reduce("", String::concat);

            return parseResponse(rawContent, fullClassName, sourceFile);

        } catch (final Exception e) {
            LOG.error("Claude API call failed for {}: {}",
                    fullClassName, e.getMessage());
            return ClassAnalysisResult.failure(fullClassName, sourceFile,
                    e.getMessage());
        }
    }

    /**
     * Analyzes a batch of classes concurrently using virtual threads.
     *
     * <p>Fires one virtual thread per class, up to the batch size.
     * Each thread calls {@link #analyzeClass} independently. Failures
     * in one class do not affect others — they return individual
     * {@link ClassAnalysisResult#failure} results.</p>
     *
     * @param inputs the batch of class inputs to analyze
     * @param readmeContext the README content for project context
     * @return the list of analysis results (one per input, same order)
     */
    public List<ClassAnalysisResult> analyzeBatch(
            final List<BatchClassInput> inputs,
            final String readmeContext) {

        Preconditions.requireNonNull(inputs, "Inputs list is required");

        if (inputs.isEmpty()) {
            return List.of();
        }

        LOG.info("Analyzing batch of {} classes concurrently", inputs.size());

        final List<ClassAnalysisResult> results = new ArrayList<>(
                inputs.size());

        try (final ExecutorService executor =
                     Executors.newVirtualThreadPerTaskExecutor()) {

            final List<Future<ClassAnalysisResult>> futures = new ArrayList<>(
                    inputs.size());

            for (final BatchClassInput input : inputs) {
                futures.add(executor.submit(() -> analyzeClass(
                        input.sourceCode(),
                        input.fullClassName(),
                        input.sourceFile(),
                        readmeContext,
                        input.language())));
            }

            for (final Future<ClassAnalysisResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (final Exception e) {
                    LOG.error("Unexpected error in batch analysis: {}",
                            e.getMessage());
                    results.add(ClassAnalysisResult.failure(
                            "unknown", "unknown", e.getMessage()));
                }
            }
        }

        return results;
    }

    /**
     * Enriches a single pre-analyzed class with business context from Claude.
     *
     * <p>Sends the source code along with the already-extracted structural
     * data (class type, method names) and asks Claude only for business
     * descriptions and logic steps.</p>
     *
     * @param input the enrichment input
     * @param readmeContext the README content for project context
     * @return the enrichment result
     */
    public EnrichmentResult enrichClass(
            final EnrichmentInput input,
            final String readmeContext) {

        Preconditions.requireNonNull(input, "Enrichment input is required");

        final String readme = readmeContext != null && !readmeContext.isBlank()
                ? readmeContext : "No README available.";

        final String methodNames = String.join(", ", input.methodNames());

        final String prompt = String.format(ENRICHMENT_PROMPT_TEMPLATE,
                input.language(), readme, input.fullClassName(),
                input.language(), input.sourceCode(),
                input.classType(), methodNames);

        LOG.debug("Enriching class: {}", input.fullClassName());

        try {
            final MessageCreateParams params = MessageCreateParams.builder()
                    .maxTokens(MAX_TOKENS)
                    .addUserMessage(prompt)
                    .model(Model.CLAUDE_SONNET_4_5_20250929)
                    .build();

            final Message response = client.messages().create(params);

            final String rawContent = response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(textBlock -> textBlock.text())
                    .reduce("", String::concat);

            return parseEnrichmentResponse(rawContent, input.fullClassName());

        } catch (final Exception e) {
            LOG.error("Claude API enrichment failed for {}: {}",
                    input.fullClassName(), e.getMessage());
            return EnrichmentResult.failure(input.fullClassName(),
                    e.getMessage());
        }
    }

    /**
     * Enriches a batch of pre-analyzed classes concurrently using virtual
     * threads.
     *
     * <p>Same concurrency pattern as {@link #analyzeBatch}: one virtual
     * thread per class, failures are isolated per class.</p>
     *
     * @param inputs the batch of enrichment inputs
     * @param readmeContext the README content for project context
     * @return the list of enrichment results (one per input, same order)
     */
    public List<EnrichmentResult> enrichBatch(
            final List<EnrichmentInput> inputs,
            final String readmeContext) {

        Preconditions.requireNonNull(inputs, "Inputs list is required");

        if (inputs.isEmpty()) {
            return List.of();
        }

        LOG.info("Enriching batch of {} classes concurrently", inputs.size());

        final List<EnrichmentResult> results = new ArrayList<>(inputs.size());

        try (final ExecutorService executor =
                     Executors.newVirtualThreadPerTaskExecutor()) {

            final List<Future<EnrichmentResult>> futures = new ArrayList<>(
                    inputs.size());

            for (final EnrichmentInput input : inputs) {
                futures.add(executor.submit(
                        () -> enrichClass(input, readmeContext)));
            }

            for (final Future<EnrichmentResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (final Exception e) {
                    LOG.error("Unexpected error in enrichment batch: {}",
                            e.getMessage());
                    results.add(EnrichmentResult.failure(
                            "unknown", e.getMessage()));
                }
            }
        }

        return results;
    }

    /**
     * Parses the Claude API enrichment response into a structured result.
     */
    private EnrichmentResult parseEnrichmentResponse(
            final String rawContent,
            final String fullClassName) {

        try {
            final String json = extractJson(rawContent);
            final JsonNode root = MAPPER.readTree(json);

            final String description = getTextOrDefault(root,
                    "description", "");
            final String classTypeCorrection = getTextOrNull(root,
                    "classTypeCorrection");

            final List<MethodEnrichment> methods = new ArrayList<>();
            final JsonNode methodsNode = root.get("methods");

            if (methodsNode != null && methodsNode.isArray()) {
                for (final JsonNode methodNode : methodsNode) {
                    methods.add(new MethodEnrichment(
                            getTextOrDefault(methodNode,
                                    "methodName", "unknown"),
                            getTextOrDefault(methodNode,
                                    "description", ""),
                            parseStringList(methodNode.get("businessLogic"))
                    ));
                }
            }

            return EnrichmentResult.success(fullClassName, description,
                    classTypeCorrection, methods);

        } catch (final JsonProcessingException e) {
            LOG.warn("Failed to parse enrichment response for {}: {}",
                    fullClassName, e.getMessage());
            return EnrichmentResult.failure(fullClassName,
                    "JSON parse error: " + e.getMessage());
        }
    }

    /**
     * Input for a single class enrichment.
     *
     * @param sourceCode the full source code
     * @param fullClassName the fully-qualified class name
     * @param language the programming language
     * @param classType the statically inferred class type
     * @param methodNames the method names extracted statically
     */
    public record EnrichmentInput(
            String sourceCode,
            String fullClassName,
            String language,
            String classType,
            List<String> methodNames
    ) {}

    /**
     * Result of enriching a single class with business context.
     */
    public record EnrichmentResult(
            boolean success,
            String fullClassName,
            String description,
            String classTypeCorrection,
            List<MethodEnrichment> methods,
            String errorMessage
    ) {
        /** Creates a successful enrichment result. */
        public static EnrichmentResult success(
                final String fullClassName,
                final String description,
                final String classTypeCorrection,
                final List<MethodEnrichment> methods) {
            return new EnrichmentResult(true, fullClassName, description,
                    classTypeCorrection, methods, null);
        }

        /** Creates a failed enrichment result. */
        public static EnrichmentResult failure(
                final String fullClassName,
                final String errorMessage) {
            return new EnrichmentResult(false, fullClassName, null,
                    null, List.of(), errorMessage);
        }
    }

    /**
     * Enrichment data for a single method.
     */
    public record MethodEnrichment(
            String methodName,
            String description,
            List<String> businessLogic
    ) {}

    /**
     * Input for a single class in a batch analysis.
     *
     * @param sourceCode the full source code
     * @param fullClassName the fully-qualified class name
     * @param sourceFile the relative source file path
     * @param language the programming language (e.g., "java", "typescript")
     */
    public record BatchClassInput(
            String sourceCode,
            String fullClassName,
            String sourceFile,
            String language
    ) {}

    /**
     * Parses the Claude API response into a structured result.
     */
    private ClassAnalysisResult parseResponse(
            final String rawContent,
            final String fullClassName,
            final String sourceFile) {

        try {
            final String json = extractJson(rawContent);
            final JsonNode root = MAPPER.readTree(json);

            final String classType = getTextOrDefault(root,
                    "classType", "OTHER");
            final String description = getTextOrDefault(root,
                    "description", "");

            final List<MethodAnalysisResult> methods = new ArrayList<>();
            final JsonNode methodsNode = root.get("methods");

            if (methodsNode != null && methodsNode.isArray()) {
                for (final JsonNode methodNode : methodsNode) {
                    methods.add(parseMethod(methodNode));
                }
            }

            return ClassAnalysisResult.success(
                    fullClassName, classType, description, sourceFile, methods);

        } catch (final JsonProcessingException e) {
            LOG.warn("Failed to parse response for {}: {}",
                    fullClassName, e.getMessage());
            return ClassAnalysisResult.failure(fullClassName, sourceFile,
                    "JSON parse error: " + e.getMessage());
        }
    }

    private MethodAnalysisResult parseMethod(final JsonNode node) {
        return new MethodAnalysisResult(
                getTextOrDefault(node, "methodName", "unknown"),
                getTextOrDefault(node, "description", ""),
                parseStringList(node.get("businessLogic")),
                parseStringList(node.get("dependencies")),
                parseStringList(node.get("exceptions")),
                getTextOrNull(node, "httpMethod"),
                getTextOrNull(node, "httpPath"),
                getIntOrNull(node, "lineNumber")
        );
    }

    private String extractJson(final String output) {
        if (output == null || output.isBlank()) {
            return "{}";
        }

        String cleaned = output.strip();

        // Strip markdown code fences if present
        if (cleaned.startsWith("```")) {
            final int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.lastIndexOf("```"));
            }
            cleaned = cleaned.strip();
        }

        final int start = cleaned.indexOf('{');
        final int end = cleaned.lastIndexOf('}');

        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }

        return cleaned;
    }

    private String getTextOrDefault(final JsonNode node, final String field,
            final String defaultValue) {
        final JsonNode fieldNode = node.get(field);
        return fieldNode != null && !fieldNode.isNull()
                ? fieldNode.asText() : defaultValue;
    }

    private String getTextOrNull(final JsonNode node, final String field) {
        final JsonNode fieldNode = node.get(field);
        return fieldNode != null && !fieldNode.isNull()
                ? fieldNode.asText() : null;
    }

    private Integer getIntOrNull(final JsonNode node, final String field) {
        final JsonNode fieldNode = node.get(field);
        return fieldNode != null && !fieldNode.isNull() && fieldNode.isNumber()
                ? fieldNode.asInt() : null;
    }

    private List<String> parseStringList(final JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isTextual()) {
            final String text = node.asText().trim();
            return text.isEmpty() ? List.of() : List.of(text);
        }
        if (!node.isArray()) {
            return List.of();
        }
        final List<String> result = new ArrayList<>();
        for (final JsonNode item : node) {
            result.add(item.asText());
        }
        return result;
    }

    /**
     * Result of analyzing a single class.
     */
    public record ClassAnalysisResult(
            boolean success,
            String fullClassName,
            String classType,
            String description,
            String sourceFile,
            List<MethodAnalysisResult> methods,
            String errorMessage
    ) {

        /**
         * Creates a successful analysis result.
         *
         * @param fullClassName the FQCN
         * @param classType the class type
         * @param description the description
         * @param sourceFile the source file path
         * @param methods the method analysis results
         * @return the success result
         */
        public static ClassAnalysisResult success(
                final String fullClassName,
                final String classType,
                final String description,
                final String sourceFile,
                final List<MethodAnalysisResult> methods) {
            return new ClassAnalysisResult(
                    true, fullClassName, classType, description,
                    sourceFile, methods, null);
        }

        /**
         * Creates a failed analysis result.
         *
         * @param fullClassName the FQCN
         * @param sourceFile the source file path
         * @param errorMessage the error message
         * @return the failure result
         */
        public static ClassAnalysisResult failure(
                final String fullClassName,
                final String sourceFile,
                final String errorMessage) {
            return new ClassAnalysisResult(
                    false, fullClassName, "OTHER", null,
                    sourceFile, List.of(), errorMessage);
        }
    }

    /**
     * Result of analyzing a single method within a class.
     */
    public record MethodAnalysisResult(
            String methodName,
            String description,
            List<String> businessLogic,
            List<String> dependencies,
            List<String> exceptions,
            String httpMethod,
            String httpPath,
            Integer lineNumber
    ) {}

}
