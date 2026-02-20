package co.fanki.domainmcp.config;

import co.fanki.domainmcp.analysis.application.CodeContextService;
import co.fanki.domainmcp.analysis.application.GraphQueryService;
import co.fanki.domainmcp.analysis.domain.GraphService;
import co.fanki.domainmcp.analysis.domain.ProjectGraph;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests for the graph_query MCP tool.
 *
 * <p>Simulates an LLM client communicating with the MCP server via the
 * stdio transport using in-process pipes. Validates the full JSON-RPC
 * protocol: initialize handshake, tool listing, and tool invocation.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class GraphQueryMcpToolTest {

    private McpSyncServer server;
    private PrintWriter clientWriter;
    private BufferedReader clientReader;
    private PipedOutputStream clientToServer;

    @BeforeEach
    void setUp() throws Exception {
        final ObjectMapper objectMapper = new ObjectMapper();

        // Wire pipes: client writes → serverIn; server writes → serverToClient.
        clientToServer = new PipedOutputStream();
        final PipedInputStream serverIn =
                new PipedInputStream(clientToServer);
        final PipedOutputStream serverOut = new PipedOutputStream();
        final PipedInputStream serverToClient =
                new PipedInputStream(serverOut);

        // Seed the graph for "order-service"
        final GraphService graphService = mock(GraphService.class);
        when(graphService.getGraphByProjectName("order-service"))
                .thenReturn(buildOrderServiceGraph());

        final GraphQueryService queryService =
                new GraphQueryService(graphService);
        final CodeContextService codeContextService =
                mock(CodeContextService.class);

        final StdioServerTransportProvider transport =
                new StdioServerTransportProvider(
                        objectMapper, serverIn, serverOut);

        server = new McpStdioServerConfiguration()
                .mcpSyncServer(
                        transport, codeContextService,
                        queryService, objectMapper);

        clientWriter = new PrintWriter(
                new OutputStreamWriter(clientToServer));
        clientReader = new BufferedReader(
                new InputStreamReader(serverToClient));

        performHandshake(objectMapper);
    }

    @AfterEach
    void tearDown() {
        try {
            clientToServer.close();
        } catch (final Exception ignored) {}
        if (server != null) {
            server.close();
        }
    }

    @Test
    void whenListingTools_shouldIncludeGraphQueryTool() throws Exception {
        send("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/list\","
                + "\"params\":{}}");

        final JsonNode response = readJson();
        final JsonNode tools = response.path("result").path("tools");
        assertTrue(tools.isArray());

        boolean found = false;
        for (final JsonNode tool : tools) {
            if ("graph_query".equals(tool.path("name").asText())) {
                found = true;
                assertNotNull(tool.path("description").asText());
                final String queryType = tool.path("inputSchema")
                        .path("properties")
                        .path("query")
                        .path("type").asText();
                assertTrue(queryType.equals("string"),
                        "query parameter must be type string");
            }
        }
        assertTrue(found, "graph_query tool must be registered");
    }

    @Test
    void whenCallingGraphQuery_givenEndpointsQuery_shouldReturnEndpoints()
            throws Exception {
        callTool(20, "order-service:endpoints");

        final JsonNode result = readJson().path("result");
        assertFalse(result.path("isError").asBoolean());
        final String text = result.path("content").get(0)
                .path("text").asText();
        assertTrue(text.contains("POST"),
                "Result should contain HTTP methods");
        assertTrue(text.contains("/api/orders"),
                "Result should contain endpoint paths");
    }

    @Test
    void whenCallingGraphQuery_givenClassesQuery_shouldReturnAllClasses()
            throws Exception {
        callTool(21, "order-service:classes");

        final JsonNode result = readJson().path("result");
        assertFalse(result.path("isError").asBoolean());
        final String text = result.path("content").get(0)
                .path("text").asText();
        assertTrue(text.contains("OrderController"),
                "Result should list OrderController");
        assertTrue(text.contains("OrderService"),
                "Result should list OrderService");
    }

    @Test
    void whenCallingGraphQuery_givenMethodsNavigation_shouldReturnMethods()
            throws Exception {
        callTool(22, "order-service:OrderController:methods");

        final JsonNode result = readJson().path("result");
        assertFalse(result.path("isError").asBoolean());
        final String text = result.path("content").get(0)
                .path("text").asText();
        assertTrue(text.contains("createOrder"),
                "Result should include createOrder method");
        assertTrue(text.contains("getOrder"),
                "Result should include getOrder method");
    }

    @Test
    void whenCallingGraphQuery_givenExistenceCheck_shouldReturnTrue()
            throws Exception {
        callTool(23, "order-service:OrderController:?createOrder");

        final JsonNode result = readJson().path("result");
        assertFalse(result.path("isError").asBoolean());
        final String text = result.path("content").get(0)
                .path("text").asText();
        assertTrue(text.contains("true"),
                "createOrder should exist on OrderController");
    }

    @Test
    void whenCallingGraphQuery_givenUnknownProject_shouldReturnError()
            throws Exception {
        callTool(30, "nonexistent-service:endpoints");

        final JsonNode result = readJson().path("result");
        assertTrue(result.path("isError").asBoolean(),
                "Unknown project should return isError=true");
    }

    // --- private helpers ---

    private void callTool(final int id, final String query) {
        send("{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"graph_query\","
                + "\"arguments\":{\"query\":\""
                + query + "\"}}}");
    }

    private void performHandshake(final ObjectMapper objectMapper)
            throws Exception {
        send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2024-11-05\","
                + "\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"test-llm\","
                + "\"version\":\"1.0\"}}}");

        readJson(); // consume initialize response

        send("{\"jsonrpc\":\"2.0\","
                + "\"method\":\"notifications/initialized\","
                + "\"params\":{}}");

        // Brief pause to let the server register the initialized state
        Thread.sleep(50);
    }

    private void send(final String json) {
        clientWriter.println(json);
        clientWriter.flush();
    }

    private JsonNode readJson() throws Exception {
        final ObjectMapper objectMapper = new ObjectMapper();
        final CompletableFuture<String> future =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return clientReader.readLine();
                    } catch (final Exception e) {
                        throw new RuntimeException(e);
                    }
                });
        final String line = future.get(5, TimeUnit.SECONDS);
        assertNotNull(line, "Server did not respond within 5 seconds");
        return objectMapper.readTree(line);
    }

    private ProjectGraph buildOrderServiceGraph() {
        final ProjectGraph graph = new ProjectGraph();

        graph.addNode("co.fanki.OrderController",
                "src/OrderController.java");
        graph.addNode("co.fanki.OrderService",
                "src/OrderService.java");
        graph.addNode("co.fanki.OrderRepository",
                "src/OrderRepository.java");

        graph.addDependency("co.fanki.OrderController",
                "co.fanki.OrderService");
        graph.addDependency("co.fanki.OrderService",
                "co.fanki.OrderRepository");

        graph.markAsEntryPoint("co.fanki.OrderController");

        graph.setNodeInfo("co.fanki.OrderController",
                "CONTROLLER", "Handles order HTTP requests");
        graph.setNodeInfo("co.fanki.OrderService",
                "SERVICE", "Order business logic");
        graph.setNodeInfo("co.fanki.OrderRepository",
                "REPOSITORY", "Order data access");

        graph.addMethodInfo("co.fanki.OrderController",
                new ProjectGraph.MethodInfo(
                        "createOrder", "Creates a new order",
                        List.of("Validate input", "Delegate to service",
                                "Return 201"),
                        List.of("ValidationException"),
                        "POST", "/api/orders", 30));

        graph.addMethodInfo("co.fanki.OrderController",
                new ProjectGraph.MethodInfo(
                        "getOrder", "Retrieves an order by ID",
                        List.of("Lookup order", "Return order data"),
                        List.of("NotFoundException"),
                        "GET", "/api/orders/{id}", 45));

        return graph;
    }
}
