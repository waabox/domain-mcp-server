package co.fanki.domainmcp.container.application;

import co.fanki.domainmcp.container.domain.AnalysisContainer;
import co.fanki.domainmcp.container.domain.AnalysisOutput;
import co.fanki.domainmcp.container.domain.AnalysisOutput.ClassInfo;
import co.fanki.domainmcp.container.domain.AnalysisOutput.EndpointInfo;
import co.fanki.domainmcp.container.domain.AnalysisOutput.MethodInfo;
import co.fanki.domainmcp.container.domain.ContainerImage;
import co.fanki.domainmcp.project.domain.RepositoryUrl;
import co.fanki.domainmcp.shared.DomainException;
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

    private static final String ANALYSIS_PROMPT = """
        Analyze this codebase and provide a detailed description of the API.

        For each API endpoint found, provide:
        1. HTTP method and path
        2. A clear description of what it does
        3. The handler method and class
        4. Request body structure (if any)
        5. Response body structure
        6. A summary of the business logic executed

        Format your response as JSON with this structure:
        {
          "summary": "Overall API summary",
          "endpoints": [
            {
              "httpMethod": "GET",
              "path": "/api/users/{id}",
              "description": "Retrieves a user by ID",
              "handlerMethod": "getUserById",
              "handlerClass": "UserController",
              "requestBody": null,
              "responseBody": "UserDTO with id, name, email fields",
              "businessLogicSummary": [
                "Validates user ID",
                "Queries database for user",
                "Maps entity to DTO"
              ]
            }
          ]
        }

        Be thorough and analyze all controllers, routes, and handlers.
        """;

    private static final String DETAILED_ANALYSIS_PROMPT = """
        Analyze this codebase and extract detailed information about all classes and methods.

        For each class, provide:
        1. Full class name (e.g., com.example.UserService)
        2. Class type: CONTROLLER, SERVICE, REPOSITORY, ENTITY, DTO, CONFIGURATION, LISTENER, UTILITY, EXCEPTION, OTHER
        3. Description of what this class does
        4. Source file path relative to project root

        For each method in each class, provide:
        1. Method name
        2. Description of what it does
        3. Business logic steps (ordered list of what the method does)
        4. Dependencies (other classes it uses/injects)
        5. Exceptions it may throw
        6. HTTP endpoint (method + path) if it's a REST handler
        7. Line number in source file (approximate if exact unknown)

        Format your response as JSON with this structure:
        {
          "summary": "Overall codebase summary",
          "classes": [
            {
              "fullClassName": "co.fanki.user.UserService",
              "classType": "SERVICE",
              "description": "Manages user lifecycle operations",
              "sourceFile": "src/main/java/co/fanki/user/UserService.java",
              "methods": [
                {
                  "methodName": "createUser",
                  "description": "Creates a new user with validation",
                  "businessLogic": [
                    "Validates email uniqueness",
                    "Hashes password with BCrypt",
                    "Saves to UserRepository",
                    "Publishes UserCreatedEvent"
                  ],
                  "dependencies": ["UserRepository", "PasswordEncoder", "EventPublisher"],
                  "exceptions": ["DuplicateEmailException", "WeakPasswordException"],
                  "httpMethod": null,
                  "httpPath": null,
                  "lineNumber": 45
                }
              ]
            },
            {
              "fullClassName": "co.fanki.user.UserController",
              "classType": "CONTROLLER",
              "description": "REST controller for user operations",
              "sourceFile": "src/main/java/co/fanki/user/UserController.java",
              "methods": [
                {
                  "methodName": "create",
                  "description": "REST endpoint for user creation",
                  "businessLogic": [
                    "Validates request body",
                    "Calls UserService.createUser",
                    "Returns created user DTO"
                  ],
                  "dependencies": ["UserService"],
                  "exceptions": ["ValidationException"],
                  "httpMethod": "POST",
                  "httpPath": "/api/users",
                  "lineNumber": 32
                }
              ]
            }
          ],
          "endpoints": [
            {
              "httpMethod": "POST",
              "path": "/api/users",
              "description": "Creates a new user",
              "handlerMethod": "create",
              "handlerClass": "UserController",
              "requestBody": "CreateUserRequest",
              "responseBody": "UserDTO",
              "businessLogicSummary": ["Validates input", "Creates user", "Returns DTO"]
            }
          ]
        }

        Be thorough and analyze all classes. Focus on classes with business logic.
        Include controllers, services, repositories, and domain entities.
        """;

    private final ObjectMapper objectMapper;
    private final String claudeApiKey;

    /**
     * Creates a new ContainerAnalysisService.
     *
     * @param theObjectMapper the JSON object mapper
     * @param theClaudeApiKey the Claude API key
     */
    public ContainerAnalysisService(
            final ObjectMapper theObjectMapper,
            @Value("${claude.api-key}") final String theClaudeApiKey) {
        this.objectMapper = theObjectMapper;
        this.claudeApiKey = theClaudeApiKey;
    }

    /**
     * Analyzes a repository using Claude Code in a container.
     *
     * @param repositoryUrl the repository URL
     * @param image the container image to use (defaults to GENERIC if null)
     * @return the analysis output
     */
    public AnalysisOutput analyzeRepository(
            final RepositoryUrl repositoryUrl,
            final ContainerImage image) {

        LOG.info("Starting container analysis for: {}", repositoryUrl);

        final Instant startedAt = Instant.now();
        final ContainerImage containerImage = image != null
                ? image : ContainerImage.GENERIC;

        try (AnalysisContainer container = new AnalysisContainer(containerImage,
                claudeApiKey)) {

            container.start();

            final AnalysisContainer.ExecResult cloneResult =
                    container.cloneRepository(repositoryUrl.value(), WORKSPACE_DIR);

            if (!cloneResult.isSuccess()) {
                LOG.error("Failed to clone repository: {}", cloneResult.stderr());
                return AnalysisOutput.failure(
                        "Clone failed: " + cloneResult.stderr(),
                        startedAt, Instant.now());
            }

            final AnalysisContainer.ExecResult analysisResult =
                    container.runClaudeAnalysis(WORKSPACE_DIR, ANALYSIS_PROMPT);

            if (!analysisResult.isSuccess()) {
                LOG.error("Claude analysis failed: {}", analysisResult.stderr());
                return AnalysisOutput.failure(
                        "Analysis failed: " + analysisResult.stderr(),
                        startedAt, Instant.now());
            }

            final String rawOutput = analysisResult.stdout();
            final AnalysisOutput output = parseAnalysisOutput(rawOutput, startedAt);

            LOG.info("Analysis completed successfully. Found {} endpoints",
                    output.endpoints().size());

            return output;

        } catch (IOException | InterruptedException e) {
            LOG.error("Container analysis failed", e);
            return AnalysisOutput.failure(
                    "Container error: " + e.getMessage(),
                    startedAt, Instant.now());
        }
    }

    /**
     * Analyzes a repository with detailed class and method extraction.
     *
     * <p>This method uses an enhanced prompt to extract detailed information
     * about all classes and methods, including business logic, dependencies,
     * and HTTP endpoints.</p>
     *
     * @param repositoryUrl the repository URL
     * @param image the container image to use (defaults to GENERIC if null)
     * @return the analysis output with class information
     */
    public AnalysisOutput analyzeRepositoryDetailed(
            final RepositoryUrl repositoryUrl,
            final ContainerImage image) {

        LOG.info("Starting detailed container analysis for: {}", repositoryUrl);

        final Instant startedAt = Instant.now();
        final ContainerImage containerImage = image != null
                ? image : ContainerImage.GENERIC;

        try (AnalysisContainer container = new AnalysisContainer(containerImage,
                claudeApiKey)) {

            container.start();

            final AnalysisContainer.ExecResult cloneResult =
                    container.cloneRepository(repositoryUrl.value(), WORKSPACE_DIR);

            if (!cloneResult.isSuccess()) {
                LOG.error("Failed to clone repository: {}", cloneResult.stderr());
                return AnalysisOutput.failure(
                        "Clone failed: " + cloneResult.stderr(),
                        startedAt, Instant.now());
            }

            final AnalysisContainer.ExecResult analysisResult =
                    container.runClaudeAnalysis(WORKSPACE_DIR, DETAILED_ANALYSIS_PROMPT);

            if (!analysisResult.isSuccess()) {
                LOG.error("Claude analysis failed: {}", analysisResult.stderr());
                return AnalysisOutput.failure(
                        "Analysis failed: " + analysisResult.stderr(),
                        startedAt, Instant.now());
            }

            final String rawOutput = analysisResult.stdout();
            final AnalysisOutput output = parseDetailedAnalysisOutput(
                    rawOutput, startedAt);

            LOG.info("Detailed analysis completed. Found {} classes, {} endpoints",
                    output.classes().size(), output.endpoints().size());

            return output;

        } catch (IOException | InterruptedException e) {
            LOG.error("Container analysis failed", e);
            return AnalysisOutput.failure(
                    "Container error: " + e.getMessage(),
                    startedAt, Instant.now());
        }
    }

    /**
     * Analyzes with a custom prompt.
     *
     * @param repositoryUrl the repository URL
     * @param customPrompt the custom analysis prompt
     * @return the raw Claude output
     */
    public String analyzeWithCustomPrompt(
            final RepositoryUrl repositoryUrl,
            final String customPrompt) {

        LOG.info("Starting custom analysis for: {}", repositoryUrl);

        try (AnalysisContainer container = new AnalysisContainer(
                ContainerImage.GENERIC, claudeApiKey)) {

            container.start();

            final AnalysisContainer.ExecResult cloneResult =
                    container.cloneRepository(repositoryUrl.value(), WORKSPACE_DIR);

            if (!cloneResult.isSuccess()) {
                throw new DomainException("Clone failed: " + cloneResult.stderr(),
                        "CLONE_FAILED");
            }

            final AnalysisContainer.ExecResult analysisResult =
                    container.runClaudeAnalysis(WORKSPACE_DIR, customPrompt);

            if (!analysisResult.isSuccess()) {
                throw new DomainException(
                        "Analysis failed: " + analysisResult.stderr(),
                        "ANALYSIS_FAILED");
            }

            return analysisResult.stdout();

        } catch (IOException | InterruptedException e) {
            throw new DomainException("Container error: " + e.getMessage(),
                    "CONTAINER_ERROR", e);
        }
    }

    private AnalysisOutput parseAnalysisOutput(final String rawOutput,
            final Instant startedAt) {
        try {
            final String jsonContent = extractJson(rawOutput);
            final JsonNode root = objectMapper.readTree(jsonContent);

            final String summary = root.has("summary")
                    ? root.get("summary").asText() : "";

            final List<EndpointInfo> endpoints = new ArrayList<>();
            final JsonNode endpointsNode = root.get("endpoints");

            if (endpointsNode != null && endpointsNode.isArray()) {
                for (JsonNode node : endpointsNode) {
                    endpoints.add(parseEndpoint(node));
                }
            }

            return AnalysisOutput.success(rawOutput, summary, endpoints,
                    startedAt, Instant.now());

        } catch (Exception e) {
            LOG.warn("Failed to parse JSON output, returning raw", e);
            return AnalysisOutput.success(rawOutput,
                    "Parse failed - see raw output", List.of(),
                    startedAt, Instant.now());
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

    private String extractJson(final String output) {
        final int start = output.indexOf('{');
        final int end = output.lastIndexOf('}');

        if (start >= 0 && end > start) {
            return output.substring(start, end + 1);
        }
        return output;
    }

    private AnalysisOutput parseDetailedAnalysisOutput(final String rawOutput,
            final Instant startedAt) {
        try {
            final String jsonContent = extractJson(rawOutput);
            final JsonNode root = objectMapper.readTree(jsonContent);

            final String summary = root.has("summary")
                    ? root.get("summary").asText() : "";

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

            return AnalysisOutput.successWithClasses(rawOutput, summary, endpoints,
                    classes, startedAt, Instant.now());

        } catch (Exception e) {
            LOG.warn("Failed to parse detailed JSON output, returning raw", e);
            return AnalysisOutput.successWithClasses(rawOutput,
                    "Parse failed - see raw output", List.of(), List.of(),
                    startedAt, Instant.now());
        }
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

