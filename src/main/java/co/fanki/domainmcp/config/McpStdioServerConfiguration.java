package co.fanki.domainmcp.config;

import co.fanki.domainmcp.analysis.application.CodeContextService;
import co.fanki.domainmcp.analysis.application.CodeContextService.StackFrame;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Configures the MCP stdio server transport for Claude Code integration.
 *
 * <p>When the {@code mcp.server.stdio} property is set to {@code true},
 * this configuration disables the web server and starts an MCP server
 * that communicates via stdin/stdout using the JSON-RPC protocol.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Configuration
@ConditionalOnProperty(name = "mcp.server.stdio", havingValue = "true")
public class McpStdioServerConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(
            McpStdioServerConfiguration.class);

    private static final String ANALYZE_PROJECT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "repositoryUrl": {
                  "type": "string",
                  "description": "The git repository URL to analyze"
                },
                "branch": {
                  "type": "string",
                  "description": "The branch to analyze (defaults to main)"
                }
              },
              "required": ["repositoryUrl"]
            }
            """;

    private static final String LIST_PROJECTS_SCHEMA = """
            {
              "type": "object",
              "properties": {}
            }
            """;

    private static final String GET_CLASS_CONTEXT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "className": {
                  "type": "string",
                  "description": "The fully qualified class name"
                }
              },
              "required": ["className"]
            }
            """;

    private static final String GET_METHOD_CONTEXT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "className": {
                  "type": "string",
                  "description": "The fully qualified class name"
                },
                "methodName": {
                  "type": "string",
                  "description": "The method name"
                }
              },
              "required": ["className", "methodName"]
            }
            """;

    private static final String GET_STACK_TRACE_CONTEXT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "stackTrace": {
                  "type": "array",
                  "description": "The stack trace frames",
                  "items": {
                    "type": "object",
                    "properties": {
                      "className": {
                        "type": "string",
                        "description": "The fully qualified class name"
                      },
                      "methodName": {
                        "type": "string",
                        "description": "The method name"
                      },
                      "lineNumber": {
                        "type": "integer",
                        "description": "The line number in the source file"
                      }
                    },
                    "required": ["className", "methodName"]
                  }
                }
              },
              "required": ["stackTrace"]
            }
            """;

    /**
     * Creates the stdio transport provider for MCP communication.
     *
     * @param objectMapper the Jackson ObjectMapper for JSON serialization
     * @return the stdio server transport provider
     */
    @Bean
    StdioServerTransportProvider stdioServerTransportProvider(
            final ObjectMapper objectMapper) {
        return new StdioServerTransportProvider(objectMapper);
    }

    /**
     * Creates and configures the MCP synchronous server with all tools.
     *
     * @param transportProvider the stdio transport provider
     * @param codeContextService the service that handles all tool operations
     * @param objectMapper the Jackson ObjectMapper for response serialization
     * @return the configured MCP sync server
     */
    @Bean
    McpSyncServer mcpSyncServer(
            final StdioServerTransportProvider transportProvider,
            final CodeContextService codeContextService,
            final ObjectMapper objectMapper) {

        final McpSyncServer server = McpServer.sync(transportProvider)
                .serverInfo("domain-mcp-server", "1.0.1")
                .capabilities(ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .build();

        server.addTool(analyzeProjectTool(codeContextService, objectMapper));
        server.addTool(listProjectsTool(codeContextService, objectMapper));
        server.addTool(getClassContextTool(codeContextService, objectMapper));
        server.addTool(getMethodContextTool(codeContextService, objectMapper));
        server.addTool(getStackTraceContextTool(codeContextService, objectMapper));

        LOG.info("MCP stdio server initialized with 5 tools");

        return server;
    }

    /**
     * Keeps the JVM alive while the MCP server is running.
     *
     * @return the command line runner that blocks on a latch
     */
    @Bean
    CommandLineRunner mcpServerRunner() {
        return args -> {
            LOG.info("MCP stdio server is running. Waiting for input...");
            new CountDownLatch(1).await();
        };
    }

    private McpServerFeatures.SyncToolSpecification analyzeProjectTool(
            final CodeContextService codeContextService,
            final ObjectMapper objectMapper) {

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("analyze_project",
                        "Analyze a git repository and extract class/method"
                                + " information. Run this first to index a"
                                + " project before using get_class_context,"
                                + " get_method_context, or"
                                + " get_stack_trace_context. Required for"
                                + " Datadog error correlation.",
                        ANALYZE_PROJECT_SCHEMA),
                (exchange, arguments) -> {
                    final String repositoryUrl =
                            (String) arguments.get("repositoryUrl");
                    final String branch =
                            (String) arguments.get("branch");

                    try {
                        final var result = codeContextService
                                .analyzeProject(repositoryUrl, branch);
                        return toCallToolResult(objectMapper, result);
                    } catch (final Exception e) {
                        return errorResult(e);
                    }
                }
        );
    }

    private McpServerFeatures.SyncToolSpecification listProjectsTool(
            final CodeContextService codeContextService,
            final ObjectMapper objectMapper) {

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("list_projects",
                        "List all indexed projects. Use this to check"
                                + " which repositories have been analyzed"
                                + " and are available for Datadog stack"
                                + " trace correlation.",
                        LIST_PROJECTS_SCHEMA),
                (exchange, arguments) -> {
                    try {
                        final var result = codeContextService.listProjects();
                        return toCallToolResult(objectMapper, result);
                    } catch (final Exception e) {
                        return errorResult(e);
                    }
                }
        );
    }

    private McpServerFeatures.SyncToolSpecification getClassContextTool(
            final CodeContextService codeContextService,
            final ObjectMapper objectMapper) {

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("get_class_context",
                        "Get business context for a Java class by its"
                                + " fully qualified name. Returns class"
                                + " type, description, and all methods."
                                + " Use this when Datadog shows an error"
                                + " in a specific class to understand its"
                                + " purpose and behavior.",
                        GET_CLASS_CONTEXT_SCHEMA),
                (exchange, arguments) -> {
                    final String className =
                            (String) arguments.get("className");

                    try {
                        final var result = codeContextService
                                .getClassContext(className);
                        return toCallToolResult(objectMapper, result);
                    } catch (final Exception e) {
                        return errorResult(e);
                    }
                }
        );
    }

    private McpServerFeatures.SyncToolSpecification getMethodContextTool(
            final CodeContextService codeContextService,
            final ObjectMapper objectMapper) {

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("get_method_context",
                        "Get detailed context for a specific method,"
                                + " including business logic, dependencies,"
                                + " exceptions, and HTTP endpoint info."
                                + " Use this when Datadog shows an error"
                                + " in a specific method to understand"
                                + " what it does and why it might fail.",
                        GET_METHOD_CONTEXT_SCHEMA),
                (exchange, arguments) -> {
                    final String className =
                            (String) arguments.get("className");
                    final String methodName =
                            (String) arguments.get("methodName");

                    try {
                        final var result = codeContextService
                                .getMethodContext(className, methodName);
                        return toCallToolResult(objectMapper, result);
                    } catch (final Exception e) {
                        return errorResult(e);
                    }
                }
        );
    }

    @SuppressWarnings("unchecked")
    private McpServerFeatures.SyncToolSpecification getStackTraceContextTool(
            final CodeContextService codeContextService,
            final ObjectMapper objectMapper) {

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("get_stack_trace_context",
                        "PRIMARY TOOL for Datadog error correlation."
                                + " Takes a full stack trace (array of"
                                + " className/methodName/lineNumber frames)"
                                + " and returns business context for each"
                                + " frame. Use this IMMEDIATELY after"
                                + " getting error traces or stack traces"
                                + " from Datadog to understand the"
                                + " execution path and root cause.",
                        GET_STACK_TRACE_CONTEXT_SCHEMA),
                (exchange, arguments) -> {
                    try {
                        final List<Map<String, Object>> rawFrames =
                                (List<Map<String, Object>>)
                                        arguments.get("stackTrace");

                        final List<StackFrame> frames = new ArrayList<>();
                        for (final Map<String, Object> raw : rawFrames) {
                            final String className =
                                    (String) raw.get("className");
                            final String methodName =
                                    (String) raw.get("methodName");
                            final Integer lineNumber =
                                    raw.get("lineNumber") instanceof Number n
                                            ? n.intValue() : null;

                            frames.add(new StackFrame(
                                    className, methodName, lineNumber));
                        }

                        final var result = codeContextService
                                .getStackTraceContext(frames);
                        return toCallToolResult(objectMapper, result);
                    } catch (final Exception e) {
                        return errorResult(e);
                    }
                }
        );
    }

    private CallToolResult toCallToolResult(final ObjectMapper objectMapper,
            final Object result) {
        try {
            final String json = objectMapper.writeValueAsString(result);
            return new CallToolResult(
                    List.of(new McpSchema.TextContent(json)), false);
        } catch (final Exception e) {
            return errorResult(e);
        }
    }

    private CallToolResult errorResult(final Exception e) {
        LOG.error("Tool execution error", e);
        return new CallToolResult(
                List.of(new McpSchema.TextContent(
                        "Error: " + e.getMessage())), true);
    }

}
