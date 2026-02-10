package co.fanki.domainmcp.container.application;

import co.fanki.domainmcp.container.domain.AnalysisContainer;
import co.fanki.domainmcp.container.domain.AnalysisOutput;
import co.fanki.domainmcp.container.domain.AnalysisOutput.ClassInfo;
import co.fanki.domainmcp.container.domain.AnalysisOutput.EndpointInfo;
import co.fanki.domainmcp.container.domain.AnalysisOutput.MethodInfo;
import co.fanki.domainmcp.container.domain.ContainerImage;
import co.fanki.domainmcp.project.domain.RepositoryUrl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Application service for container-based code analysis.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Service
public class ContainerAnalysisService {

    private static final Logger LOG = LoggerFactory.getLogger(
            ContainerAnalysisService.class);

    private static final String WORKSPACE_DIR = "/workspace/repo";
    private static final String README_PATH = WORKSPACE_DIR + "/README.md";
    private static final int MAX_README_LENGTH = 10_000;

    private static final String ANALYSIS_PROMPT = """
        IMPORTANT: Respond with ONLY valid JSON, no other text before or after.
        Analyze this codebase and extract information about classes and methods.
        Return a JSON object with: projectDescription (string summarizing what this \
        project/service does based on the README and code), summary (string), \
        classes (array), endpoints (array).
        Each class needs: fullClassName, classType (CONTROLLER/SERVICE/REPOSITORY/ENTITY/DTO/CONFIGURATION/LISTENER/UTILITY/EXCEPTION/OTHER), description, sourceFile, methods (array).
        Each method needs: methodName, description, businessLogic (array of steps), dependencies (array), exceptions (array), httpMethod (or null), httpPath (or null), lineNumber.
        Each endpoint needs: httpMethod, path, description, handlerMethod, handlerClass, requestBody, responseBody, businessLogicSummary (array).
        Focus on controllers, services, repositories and domain entities.
        """;

    private final ObjectMapper objectMapper;
    private final String claudeApiKey;
    private final String sshKeyPath;

    /**
     * Creates a new ContainerAnalysisService.
     *
     * @param theObjectMapper the JSON object mapper
     * @param theClaudeApiKey the Claude API key
     * @param theSshKeyPath the SSH key path for private repositories
     */
    public ContainerAnalysisService(
            final ObjectMapper theObjectMapper,
            @Value("${claude.api-key}") final String theClaudeApiKey,
            @Value("${git.ssh-key-path:~/.ssh/id_rsa}") final String theSshKeyPath) {
        this.objectMapper = theObjectMapper;
        this.claudeApiKey = theClaudeApiKey;
        this.sshKeyPath = theSshKeyPath;
    }

    /**
     * Analyzes a repository using Claude Code in a container.
     *
     * <p>Extracts detailed information about all classes and methods,
     * including business logic, dependencies, and HTTP endpoints.</p>
     *
     * @param repositoryUrl the repository URL
     * @param image the container image to use (defaults to GENERIC if null)
     * @return the analysis output with class information
     */
    public AnalysisOutput analyzeRepository(
            final RepositoryUrl repositoryUrl,
            final ContainerImage image) {

        LOG.info("Starting container analysis for: {}", repositoryUrl);

        final Instant startedAt = Instant.now();
        final ContainerImage containerImage = image != null
                ? image : ContainerImage.GENERIC;

        try (AnalysisContainer container = new AnalysisContainer(containerImage,
                claudeApiKey, sshKeyPath)) {

            container.start();

            final AnalysisContainer.ExecResult cloneResult =
                    container.cloneRepository(repositoryUrl.value(), WORKSPACE_DIR);

            if (!cloneResult.isSuccess()) {
                LOG.error("Failed to clone repository: {}", cloneResult.stderr());
                return AnalysisOutput.failure(
                        "Clone failed: " + cloneResult.stderr(),
                        startedAt, Instant.now());
            }

            final String prompt = buildPromptWithReadme(container);

            final AnalysisContainer.ExecResult analysisResult =
                    container.runClaudeAnalysis(WORKSPACE_DIR, prompt);

            if (!analysisResult.isSuccess()) {
                LOG.error("Claude analysis failed: {}", analysisResult.stderr());
                return AnalysisOutput.failure(
                        "Analysis failed: " + analysisResult.stderr(),
                        startedAt, Instant.now());
            }

            final String rawOutput = analysisResult.stdout();
            final AnalysisOutput output = parseAnalysisOutput(rawOutput, startedAt);

            LOG.info("Analysis completed. Found {} classes, {} endpoints",
                    output.classes().size(), output.endpoints().size());

            return output;

        } catch (IOException | InterruptedException e) {
            LOG.error("Container analysis failed", e);
            return AnalysisOutput.failure(
                    "Container error: " + e.getMessage(),
                    startedAt, Instant.now());
        }
    }

    private AnalysisOutput parseAnalysisOutput(final String rawOutput,
            final Instant startedAt) {
        try {
            final String jsonContent = extractJson(rawOutput);
            final JsonNode root = objectMapper.readTree(jsonContent);

            final String summary = root.has("summary")
                    ? root.get("summary").asText() : "";

            final String projectDescription = root.has("projectDescription")
                    ? root.get("projectDescription").asText() : null;

            final List<EndpointInfo> endpoints = new ArrayList<>();
            final JsonNode endpointsNode = root.get("endpoints");

            if (endpointsNode != null && endpointsNode.isArray()) {
                for (JsonNode node : endpointsNode) {
                    endpoints.add(parseEndpoint(node));
                }
            }

            final List<ClassInfo> classes = new ArrayList<>();
            final JsonNode classesNode = root.get("classes");

            if (classesNode != null && classesNode.isArray()) {
                for (JsonNode node : classesNode) {
                    classes.add(parseClassInfo(node));
                }
            }

            return AnalysisOutput.successWithClasses(rawOutput, summary,
                    projectDescription, endpoints, classes,
                    startedAt, Instant.now());

        } catch (Exception e) {
            LOG.warn("Failed to parse JSON output, returning raw", e);
            return AnalysisOutput.successWithClasses(rawOutput,
                    "Parse failed - see raw output", null, List.of(),
                    List.of(), startedAt, Instant.now());
        }
    }

    private EndpointInfo parseEndpoint(final JsonNode node) {
        return new EndpointInfo(
                getTextOrNull(node, "httpMethod"),
                getTextOrNull(node, "path"),
                getTextOrNull(node, "description"),
                getTextOrNull(node, "handlerMethod"),
                getTextOrNull(node, "handlerClass"),
                getTextOrNull(node, "requestBody"),
                getTextOrNull(node, "responseBody"),
                parseStringList(node.get("businessLogicSummary")));
    }

    private String getTextOrNull(final JsonNode node, final String field) {
        final JsonNode fieldNode = node.get(field);
        return fieldNode != null && !fieldNode.isNull()
                ? fieldNode.asText() : null;
    }

    private List<String> parseStringList(final JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        final List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            result.add(item.asText());
        }
        return result;
    }

    private String buildPromptWithReadme(final AnalysisContainer container) {
        try {
            final String readme = container.readFileFromContainer(README_PATH);
            if (readme != null && !readme.isBlank()) {
                final String truncated = readme.length() > MAX_README_LENGTH
                        ? readme.substring(0, MAX_README_LENGTH) + "\n...(truncated)"
                        : readme;

                LOG.info("README.md found ({} chars), including in prompt",
                        readme.length());

                return "Here is the project's README.md for additional context: "
                        + "--- " + truncated + " --- " + ANALYSIS_PROMPT;
            }
        } catch (Exception e) {
            LOG.warn("Failed to read README.md from container", e);
        }

        LOG.info("No README.md found, using base prompt");
        return ANALYSIS_PROMPT;
    }

    private String extractJson(final String output) {
        final int start = output.indexOf('{');
        final int end = output.lastIndexOf('}');

        if (start >= 0 && end > start) {
            return output.substring(start, end + 1);
        }
        return output;
    }

    private ClassInfo parseClassInfo(final JsonNode node) {
        final List<MethodInfo> methods = new ArrayList<>();
        final JsonNode methodsNode = node.get("methods");

        if (methodsNode != null && methodsNode.isArray()) {
            for (JsonNode methodNode : methodsNode) {
                methods.add(parseMethodInfo(methodNode));
            }
        }

        return new ClassInfo(
                getTextOrNull(node, "fullClassName"),
                getTextOrNull(node, "classType"),
                getTextOrNull(node, "description"),
                getTextOrNull(node, "sourceFile"),
                methods);
    }

    private MethodInfo parseMethodInfo(final JsonNode node) {
        final Integer lineNumber = node.has("lineNumber") && !node.get("lineNumber").isNull()
                ? node.get("lineNumber").asInt() : null;

        return new MethodInfo(
                getTextOrNull(node, "methodName"),
                getTextOrNull(node, "description"),
                parseStringList(node.get("businessLogic")),
                parseStringList(node.get("dependencies")),
                parseStringList(node.get("exceptions")),
                getTextOrNull(node, "httpMethod"),
                getTextOrNull(node, "httpPath"),
                lineNumber);
    }

}

