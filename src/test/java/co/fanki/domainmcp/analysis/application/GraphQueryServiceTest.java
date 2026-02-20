package co.fanki.domainmcp.analysis.application;

import co.fanki.domainmcp.analysis.domain.GraphService;
import co.fanki.domainmcp.analysis.domain.ProjectGraph;
import co.fanki.domainmcp.analysis.application.GraphQueryService.GraphQueryResult;
import co.fanki.domainmcp.shared.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GraphQueryService}.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class GraphQueryServiceTest {

    private GraphService graphService;
    private GraphQueryService queryService;

    @BeforeEach
    void setUp() {
        graphService = mock(GraphService.class);
        queryService = new GraphQueryService(graphService);
    }

    private ProjectGraph buildTestGraph() {
        final ProjectGraph graph = new ProjectGraph();

        graph.addNode("co.fanki.UserController",
                "src/UserController.java");
        graph.addNode("co.fanki.UserService",
                "src/UserService.java");
        graph.addNode("co.fanki.UserRepository",
                "src/UserRepository.java");
        graph.addNode("co.fanki.UserDto",
                "src/UserDto.java");

        graph.addDependency("co.fanki.UserController",
                "co.fanki.UserService");
        graph.addDependency("co.fanki.UserService",
                "co.fanki.UserRepository");

        graph.markAsEntryPoint("co.fanki.UserController");

        // Node info
        graph.setNodeInfo("co.fanki.UserController",
                "CONTROLLER", "Handles user HTTP requests");
        graph.setNodeInfo("co.fanki.UserService",
                "SERVICE", "User business logic");
        graph.setNodeInfo("co.fanki.UserRepository",
                "REPOSITORY", "User data access");
        graph.setNodeInfo("co.fanki.UserDto",
                "DTO", "User data transfer object");

        // Method info
        graph.addMethodInfo("co.fanki.UserController",
                new ProjectGraph.MethodInfo(
                        "getUsers", "Lists all users",
                        List.of("Query users", "Map to DTOs"),
                        List.of(), "GET", "/api/users", 25));

        graph.addMethodInfo("co.fanki.UserController",
                new ProjectGraph.MethodInfo(
                        "createUser", "Creates a new user",
                        List.of("Validate input", "Save user"),
                        List.of("ValidationException"),
                        "POST", "/api/users", 40));

        graph.addMethodInfo("co.fanki.UserService",
                new ProjectGraph.MethodInfo(
                        "findAll", "Finds all users",
                        List.of("Delegate to repository"),
                        List.of(), null, null, 15));

        graph.addMethodInfo("co.fanki.UserService",
                new ProjectGraph.MethodInfo(
                        "create", "Creates a user",
                        List.of("Validate", "Persist"),
                        List.of("DuplicateUserException"),
                        null, null, 30));

        return graph;
    }

    // -- execute: project not found ----------------------------------------

    @Test
    void whenExecuting_givenUnknownProject_shouldThrow() {
        when(graphService.getGraphByProjectName("unknown"))
                .thenReturn(null);

        final GraphQuery query = GraphQueryParser.parse(
                "unknown:endpoints");

        assertThrows(DomainException.class,
                () -> queryService.execute(query));
    }

    // -- endpoints ---------------------------------------------------------

    @Test
    void whenQueryingEndpoints_shouldReturnAllHttpMethods() {
        final ProjectGraph graph = buildTestGraph();
        when(graphService.getGraphByProjectName("my-project"))
                .thenReturn(graph);

        final GraphQuery query =
                GraphQueryParser.parse("my-project:endpoints");
        final GraphQueryResult result = queryService.execute(query);

        assertEquals("endpoints", result.resultType());
        assertEquals("my-project", result.project());
        assertEquals(2, result.count());

        final Map<String, Object> first = result.results().get(0);
        assertNotNull(first.get("httpMethod"));
        assertNotNull(first.get("httpPath"));
    }

    @Test
    void whenQueryingEndpoints_givenLogicSegment_shouldIncludeBusinessLogic() {
        final ProjectGraph graph = buildTestGraph();
        when(graphService.getGraphByProjectName("my-project"))
                .thenReturn(graph);

        final GraphQuery query =
                GraphQueryParser.parse("my-project:endpoints:logic");
        final GraphQueryResult result = queryService.execute(query);

        assertEquals(2, result.count());
        final Map<String, Object> first = result.results().get(0);
        assertTrue(first.containsKey("businessLogic"));
    }

    // -- classes -----------------------------------------------------------

    @Test
    void whenQueryingClasses_shouldReturnAllNodes() {
        final ProjectGraph graph = buildTestGraph();
        when(graphService.getGraphByProjectName("my-project"))
                .thenReturn(graph);

        final GraphQuery query =
                GraphQueryParser.parse("my-project:classes");
        final GraphQueryResult result = queryService.execute(query);

        assertEquals("classes", result.resultType());
        assertEquals(4, result.count());
    }

    @Test
    void whenQueryingClasses_givenDependenciesSegment_shouldIncludeDeps() {
        final ProjectGraph graph = buildTestGraph();
        when(graphService.getGraphByProjectName("my-project"))
                .thenReturn(graph);

        final GraphQuery query =
                GraphQueryParser.parse("my-project:classes:dependencies");
        final GraphQueryResult result = queryService.execute(query);

        final Map<String, Object> controller = result.results().stream()
                .filter(m -> "co.fanki.UserController"
                        .equals(m.get("className")))
                .findFirst().orElseThrow();

        assertTrue(controller.containsKey("dependencies"));
        @SuppressWarnings("unchecked")
        final List<String> deps =
                (List<String>) controller.get("dependencies");
        assertTrue(deps.contains("co.fanki.UserService"));
    }

    // -- entrypoints -------------------------------------------------------

    @Test
    void whenQueryingEntrypoints_shouldReturnOnlyEntryPoints() {
        final ProjectGraph graph = buildTestGraph();
        when(graphService.getGraphByProjectName("my-project"))
                .thenReturn(graph);

        final GraphQuery query =
                GraphQueryParser.parse("my-project:entrypoints");
        final GraphQueryResult result = queryService.execute(query);

        assertEquals("entrypoints", result.resultType());
        assertEquals(1, result.count());
        assertEquals("co.fanki.UserController",
                result.results().get(0).get("className"));
    }

    // -- class navigation --------------------------------------------------

    @Test
    void whenQueryingClass_givenSimpleName_shouldResolve() {
        final ProjectGraph graph = buildTestGraph();
        when(graphService.getGraphByProjectName("my-project"))
                .thenReturn(graph);

        final GraphQuery query =
                GraphQueryParser.parse("my-project:class:UserService");
        final GraphQueryResult result = queryService.execute(query);

        assertEquals("class", result.resultType());
        assertEquals(1, result.count());
        assertEquals("co.fanki.UserService",
                result.results().get(0).get("className"));
    }

    @Test
    void whenQueryingClass_givenFullName_shouldResolve() {
        final ProjectGraph graph = buildTestGraph();
        when(graphService.getGraphByProjectName("my-project"))
                .thenReturn(graph);

        final GraphQuery query = GraphQueryParser.parse(
                "my-project:class:co.fanki.UserService");
        final GraphQueryResult result = queryService.execute(query);

        assertEquals("co.fanki.UserService",
                result.results().get(0).get("className"));
    }

    @Test
    void whenQueryingClass_givenUnknownClass_shouldThrow() {
        final ProjectGraph graph = buildTestGraph();
        when(graphService.getGraphByProjectName("my-project"))
                .thenReturn(graph);

        final GraphQuery query =
                GraphQueryParser.parse("my-project:class:Unknown");

        assertThrows(DomainException.class,
                () -> queryService.execute(query));
    }

    @Test
    void whenQueryingClassMethods_shouldReturnAllMethods() {
        final ProjectGraph graph = buildTestGraph();
        when(graphService.getGraphByProjectName("my-project"))
                .thenReturn(graph);

        final GraphQuery query = GraphQueryParser.parse(
                "my-project:class:UserService:methods");
        final GraphQueryResult result = queryService.execute(query);

        assertEquals("methods", result.resultType());
        assertEquals(2, result.count());
    }

    @Test
    void whenQueryingClassDependencies_shouldReturnOutgoing() {
        final ProjectGraph graph = buildTestGraph();
        when(graphService.getGraphByProjectName("my-project"))
                .thenReturn(graph);

        final GraphQuery query = GraphQueryParser.parse(
                "my-project:class:UserController:dependencies");
        final GraphQueryResult result = queryService.execute(query);

        assertEquals("dependencies", result.resultType());
        assertEquals(1, result.count());
        assertEquals("co.fanki.UserService",
                result.results().get(0).get("className"));
    }

    @Test
    void whenQueryingClassDependents_shouldReturnIncoming() {
        final ProjectGraph graph = buildTestGraph();
        when(graphService.getGraphByProjectName("my-project"))
                .thenReturn(graph);

        final GraphQuery query = GraphQueryParser.parse(
                "my-project:class:UserService:dependents");
        final GraphQueryResult result = queryService.execute(query);

        assertEquals("dependents", result.resultType());
        assertEquals(1, result.count());
        assertEquals("co.fanki.UserController",
                result.results().get(0).get("className"));
    }

    @Test
    void whenQueryingSingleMethod_shouldReturnMethodDetail() {
        final ProjectGraph graph = buildTestGraph();
        when(graphService.getGraphByProjectName("my-project"))
                .thenReturn(graph);

        final GraphQuery query = GraphQueryParser.parse(
                "my-project:class:UserController:method:createUser");
        final GraphQueryResult result = queryService.execute(query);

        assertEquals("method", result.resultType());
        assertEquals(1, result.count());
        assertEquals("createUser",
                result.results().get(0).get("methodName"));
        assertEquals("POST",
                result.results().get(0).get("httpMethod"));
    }

    @Test
    void whenQueryingSingleMethod_givenUnknownMethod_shouldThrow() {
        final ProjectGraph graph = buildTestGraph();
        when(graphService.getGraphByProjectName("my-project"))
                .thenReturn(graph);

        final GraphQuery query = GraphQueryParser.parse(
                "my-project:class:UserController:method:unknown");

        assertThrows(DomainException.class,
                () -> queryService.execute(query));
    }

    // -- class overview includes methods summary ---------------------------

    @Test
    void whenQueryingClassOverview_shouldIncludeMethodSummaries() {
        final ProjectGraph graph = buildTestGraph();
        when(graphService.getGraphByProjectName("my-project"))
                .thenReturn(graph);

        final GraphQuery query =
                GraphQueryParser.parse("my-project:class:UserController");
        final GraphQueryResult result = queryService.execute(query);

        final Map<String, Object> classInfo = result.results().get(0);
        assertTrue(classInfo.containsKey("methods"));
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> methods =
                (List<Map<String, Object>>) classInfo.get("methods");
        assertEquals(2, methods.size());
    }
}
