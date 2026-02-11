package co.fanki.domainmcp.analysis.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the ProjectGraph domain object.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class ProjectGraphTest {

    // -- addNode -----------------------------------------------------------

    @Test
    void whenAddingNode_givenValidIdentifierAndFile_shouldIncreaseNodeCount() {
        final ProjectGraph graph = new ProjectGraph();

        graph.addNode("co.fanki.UserService", "src/UserService.java");

        assertEquals(1, graph.nodeCount());
        assertTrue(graph.contains("co.fanki.UserService"));
    }

    @Test
    void whenAddingNode_givenMultipleDistinctNodes_shouldTrackAll() {
        final ProjectGraph graph = new ProjectGraph();

        graph.addNode("co.fanki.UserService", "src/UserService.java");
        graph.addNode("co.fanki.OrderService", "src/OrderService.java");
        graph.addNode("co.fanki.User", "src/User.java");

        assertEquals(3, graph.nodeCount());
        assertEquals(
                Set.of("co.fanki.UserService",
                        "co.fanki.OrderService",
                        "co.fanki.User"),
                graph.identifiers());
    }

    @Test
    void whenAddingNode_givenDuplicateIdentifier_shouldOverwriteSourceFile() {
        final ProjectGraph graph = new ProjectGraph();

        graph.addNode("co.fanki.UserService", "src/old/UserService.java");
        graph.addNode("co.fanki.UserService", "src/new/UserService.java");

        assertEquals(1, graph.nodeCount());
        assertEquals("src/new/UserService.java",
                graph.sourceFile("co.fanki.UserService"));
    }

    @Test
    void whenAddingNode_givenNullIdentifier_shouldThrowException() {
        final ProjectGraph graph = new ProjectGraph();

        assertThrows(IllegalArgumentException.class,
                () -> graph.addNode(null, "src/Test.java"));
    }

    @Test
    void whenAddingNode_givenBlankIdentifier_shouldThrowException() {
        final ProjectGraph graph = new ProjectGraph();

        assertThrows(IllegalArgumentException.class,
                () -> graph.addNode("  ", "src/Test.java"));
    }

    @Test
    void whenAddingNode_givenNullSourceFile_shouldThrowException() {
        final ProjectGraph graph = new ProjectGraph();

        assertThrows(IllegalArgumentException.class,
                () -> graph.addNode("co.fanki.Test", null));
    }

    @Test
    void whenAddingNode_givenBlankSourceFile_shouldThrowException() {
        final ProjectGraph graph = new ProjectGraph();

        assertThrows(IllegalArgumentException.class,
                () -> graph.addNode("co.fanki.Test", ""));
    }

    // -- addDependency -----------------------------------------------------

    @Test
    void whenAddingDependency_givenBothNodesExist_shouldCreateEdge() {
        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.Controller", "src/Controller.java");
        graph.addNode("co.fanki.Service", "src/Service.java");

        graph.addDependency("co.fanki.Controller", "co.fanki.Service");

        final Set<String> neighbors = graph.resolve("co.fanki.Controller");
        assertTrue(neighbors.contains("co.fanki.Service"));
    }

    @Test
    void whenAddingDependency_givenFromNodeUnknown_shouldIgnoreSilently() {
        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.Service", "src/Service.java");

        graph.addDependency("co.fanki.Unknown", "co.fanki.Service");

        assertEquals(Set.of(), graph.resolve("co.fanki.Service"));
    }

    @Test
    void whenAddingDependency_givenToNodeUnknown_shouldIgnoreSilently() {
        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.Controller", "src/Controller.java");

        graph.addDependency("co.fanki.Controller", "co.fanki.Unknown");

        assertEquals(Set.of(), graph.resolve("co.fanki.Controller"));
    }

    @Test
    void whenAddingDependency_givenBothNodesUnknown_shouldIgnoreSilently() {
        final ProjectGraph graph = new ProjectGraph();

        graph.addDependency("co.fanki.A", "co.fanki.B");

        assertEquals(0, graph.nodeCount());
    }

    @Test
    void whenAddingDependency_givenNullFrom_shouldThrowException() {
        final ProjectGraph graph = new ProjectGraph();

        assertThrows(IllegalArgumentException.class,
                () -> graph.addDependency(null, "co.fanki.B"));
    }

    @Test
    void whenAddingDependency_givenBlankTo_shouldThrowException() {
        final ProjectGraph graph = new ProjectGraph();

        assertThrows(IllegalArgumentException.class,
                () -> graph.addDependency("co.fanki.A", "  "));
    }

    @Test
    void whenAddingDependency_givenDuplicate_shouldNotCreateMultipleEdges() {
        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.A", "src/A.java");
        graph.addNode("co.fanki.B", "src/B.java");

        graph.addDependency("co.fanki.A", "co.fanki.B");
        graph.addDependency("co.fanki.A", "co.fanki.B");

        final Set<String> neighbors = graph.resolve("co.fanki.A");
        assertEquals(1, neighbors.size());
    }

    // -- markAsEntryPoint --------------------------------------------------

    @Test
    void whenMarkingEntryPoint_givenKnownNode_shouldIncreaseEntryPointCount() {
        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.Controller", "src/Controller.java");

        graph.markAsEntryPoint("co.fanki.Controller");

        assertEquals(1, graph.entryPointCount());
    }

    @Test
    void whenMarkingEntryPoint_givenUnknownNode_shouldIgnoreSilently() {
        final ProjectGraph graph = new ProjectGraph();

        graph.markAsEntryPoint("co.fanki.Unknown");

        assertEquals(0, graph.entryPointCount());
    }

    @Test
    void whenMarkingEntryPoint_givenNullIdentifier_shouldThrowException() {
        final ProjectGraph graph = new ProjectGraph();

        assertThrows(IllegalArgumentException.class,
                () -> graph.markAsEntryPoint(null));
    }

    @Test
    void whenMarkingEntryPoint_givenBlankIdentifier_shouldThrowException() {
        final ProjectGraph graph = new ProjectGraph();

        assertThrows(IllegalArgumentException.class,
                () -> graph.markAsEntryPoint(""));
    }

    @Test
    void whenMarkingEntryPoint_givenAlreadyMarked_shouldNotDuplicate() {
        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.Controller", "src/Controller.java");

        graph.markAsEntryPoint("co.fanki.Controller");
        graph.markAsEntryPoint("co.fanki.Controller");

        assertEquals(1, graph.entryPointCount());
    }

    // -- resolve -----------------------------------------------------------

    @Test
    void whenResolving_givenDirectDependency_shouldReturnTarget() {
        final ProjectGraph graph = buildSimpleChainGraph();

        final Set<String> neighbors = graph.resolve("co.fanki.Controller");

        assertTrue(neighbors.contains("co.fanki.Service"));
    }

    @Test
    void whenResolving_givenReverseDependency_shouldReturnSource() {
        final ProjectGraph graph = buildSimpleChainGraph();

        final Set<String> neighbors = graph.resolve("co.fanki.Service");

        assertTrue(neighbors.contains("co.fanki.Controller"));
        assertTrue(neighbors.contains("co.fanki.Repository"));
    }

    @Test
    void whenResolving_givenBothDirections_shouldReturnAll() {
        final ProjectGraph graph = buildSimpleChainGraph();

        final Set<String> neighbors = graph.resolve("co.fanki.Service");

        assertEquals(Set.of("co.fanki.Controller", "co.fanki.Repository"),
                neighbors);
    }

    @Test
    void whenResolving_givenLeafNode_shouldReturnOnlyIncoming() {
        final ProjectGraph graph = buildSimpleChainGraph();

        final Set<String> neighbors = graph.resolve("co.fanki.Repository");

        assertEquals(Set.of("co.fanki.Service"), neighbors);
    }

    @Test
    void whenResolving_givenNullIdentifier_shouldReturnEmptySet() {
        final ProjectGraph graph = buildSimpleChainGraph();

        final Set<String> neighbors = graph.resolve(null);

        assertTrue(neighbors.isEmpty());
    }

    @Test
    void whenResolving_givenUnknownIdentifier_shouldReturnEmptySet() {
        final ProjectGraph graph = buildSimpleChainGraph();

        final Set<String> neighbors = graph.resolve("co.fanki.Unknown");

        assertTrue(neighbors.isEmpty());
    }

    @Test
    void whenResolving_givenIsolatedNode_shouldReturnEmptySet() {
        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.Isolated", "src/Isolated.java");

        final Set<String> neighbors = graph.resolve("co.fanki.Isolated");

        assertTrue(neighbors.isEmpty());
    }

    // -- analysisOrder -----------------------------------------------------

    @Test
    void whenComputingAnalysisOrder_givenEntryPointChain_shouldStartFromEntryPoint() {
        final ProjectGraph graph = buildSimpleChainGraph();
        graph.markAsEntryPoint("co.fanki.Controller");

        final List<String> order = graph.analysisOrder();

        assertEquals(0, order.indexOf("co.fanki.Controller"));
        assertTrue(order.indexOf("co.fanki.Service")
                < order.indexOf("co.fanki.Repository"));
    }

    @Test
    void whenComputingAnalysisOrder_givenMultipleEntryPoints_shouldVisitAllReachable() {
        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.ControllerA", "src/ControllerA.java");
        graph.addNode("co.fanki.ControllerB", "src/ControllerB.java");
        graph.addNode("co.fanki.ServiceA", "src/ServiceA.java");
        graph.addNode("co.fanki.ServiceB", "src/ServiceB.java");

        graph.addDependency("co.fanki.ControllerA", "co.fanki.ServiceA");
        graph.addDependency("co.fanki.ControllerB", "co.fanki.ServiceB");

        graph.markAsEntryPoint("co.fanki.ControllerA");
        graph.markAsEntryPoint("co.fanki.ControllerB");

        final List<String> order = graph.analysisOrder();

        assertEquals(4, order.size());
        assertTrue(order.indexOf("co.fanki.ControllerA")
                < order.indexOf("co.fanki.ServiceA"));
        assertTrue(order.indexOf("co.fanki.ControllerB")
                < order.indexOf("co.fanki.ServiceB"));
    }

    @Test
    void whenComputingAnalysisOrder_givenOrphans_shouldAppendAtEnd() {
        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.Controller", "src/Controller.java");
        graph.addNode("co.fanki.Service", "src/Service.java");
        graph.addNode("co.fanki.Orphan", "src/Orphan.java");

        graph.addDependency("co.fanki.Controller", "co.fanki.Service");
        graph.markAsEntryPoint("co.fanki.Controller");

        final List<String> order = graph.analysisOrder();

        assertEquals(3, order.size());
        assertEquals("co.fanki.Orphan", order.get(order.size() - 1));
    }

    @Test
    void whenComputingAnalysisOrder_givenNoEntryPoints_shouldReturnAllAsOrphans() {
        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.A", "src/A.java");
        graph.addNode("co.fanki.B", "src/B.java");

        final List<String> order = graph.analysisOrder();

        assertEquals(2, order.size());
        assertTrue(order.containsAll(List.of("co.fanki.A", "co.fanki.B")));
    }

    @Test
    void whenComputingAnalysisOrder_givenEmptyGraph_shouldReturnEmptyList() {
        final ProjectGraph graph = new ProjectGraph();

        final List<String> order = graph.analysisOrder();

        assertTrue(order.isEmpty());
    }

    @Test
    void whenComputingAnalysisOrder_givenDiamondDependency_shouldVisitEachNodeOnce() {
        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.Root", "src/Root.java");
        graph.addNode("co.fanki.Left", "src/Left.java");
        graph.addNode("co.fanki.Right", "src/Right.java");
        graph.addNode("co.fanki.Leaf", "src/Leaf.java");

        graph.addDependency("co.fanki.Root", "co.fanki.Left");
        graph.addDependency("co.fanki.Root", "co.fanki.Right");
        graph.addDependency("co.fanki.Left", "co.fanki.Leaf");
        graph.addDependency("co.fanki.Right", "co.fanki.Leaf");

        graph.markAsEntryPoint("co.fanki.Root");

        final List<String> order = graph.analysisOrder();

        assertEquals(4, order.size());
        assertEquals(4, Set.copyOf(order).size());
        assertEquals(0, order.indexOf("co.fanki.Root"));
    }

    // -- bindClassId / classId ---------------------------------------------

    @Test
    void whenBindingClassId_givenValidData_shouldBeRetrievable() {
        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.User", "src/User.java");

        graph.bindClassId("co.fanki.User", "class-uuid-123");

        assertEquals("class-uuid-123", graph.classId("co.fanki.User"));
    }

    @Test
    void whenRetrievingClassId_givenUnboundIdentifier_shouldReturnNull() {
        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.User", "src/User.java");

        assertNull(graph.classId("co.fanki.User"));
    }

    @Test
    void whenRetrievingClassId_givenUnknownIdentifier_shouldReturnNull() {
        final ProjectGraph graph = new ProjectGraph();

        assertNull(graph.classId("co.fanki.Unknown"));
    }

    @Test
    void whenBindingClassId_givenNullIdentifier_shouldThrowException() {
        final ProjectGraph graph = new ProjectGraph();

        assertThrows(IllegalArgumentException.class,
                () -> graph.bindClassId(null, "id-123"));
    }

    @Test
    void whenBindingClassId_givenBlankClassId_shouldThrowException() {
        final ProjectGraph graph = new ProjectGraph();

        assertThrows(IllegalArgumentException.class,
                () -> graph.bindClassId("co.fanki.User", ""));
    }

    @Test
    void whenBindingClassId_givenOverwrite_shouldReplaceOldValue() {
        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.User", "src/User.java");

        graph.bindClassId("co.fanki.User", "old-id");
        graph.bindClassId("co.fanki.User", "new-id");

        assertEquals("new-id", graph.classId("co.fanki.User"));
    }

    // -- sourceFile --------------------------------------------------------

    @Test
    void whenRetrievingSourceFile_givenKnownNode_shouldReturnFilePath() {
        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.User", "src/main/java/User.java");

        assertEquals("src/main/java/User.java",
                graph.sourceFile("co.fanki.User"));
    }

    @Test
    void whenRetrievingSourceFile_givenUnknownNode_shouldReturnNull() {
        final ProjectGraph graph = new ProjectGraph();

        assertNull(graph.sourceFile("co.fanki.Unknown"));
    }

    // -- contains ----------------------------------------------------------

    @Test
    void whenCheckingContains_givenExistingNode_shouldReturnTrue() {
        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.User", "src/User.java");

        assertTrue(graph.contains("co.fanki.User"));
    }

    @Test
    void whenCheckingContains_givenAbsentNode_shouldReturnFalse() {
        final ProjectGraph graph = new ProjectGraph();

        assertFalse(graph.contains("co.fanki.Unknown"));
    }

    @Test
    void whenCheckingContains_givenNull_shouldReturnFalse() {
        final ProjectGraph graph = new ProjectGraph();

        assertFalse(graph.contains(null));
    }

    // -- toJson / fromJson -------------------------------------------------

    @Test
    void whenSerializingToJson_givenPopulatedGraph_shouldRoundTrip() {
        final ProjectGraph original = buildSimpleChainGraph();
        original.markAsEntryPoint("co.fanki.Controller");
        original.bindClassId("co.fanki.Controller", "id-ctrl");
        original.bindClassId("co.fanki.Service", "id-svc");

        final String json = original.toJson();
        final ProjectGraph restored = ProjectGraph.fromJson(json);

        assertEquals(original.nodeCount(), restored.nodeCount());
        assertEquals(original.entryPointCount(), restored.entryPointCount());

        assertEquals(original.sourceFile("co.fanki.Controller"),
                restored.sourceFile("co.fanki.Controller"));
        assertEquals(original.sourceFile("co.fanki.Service"),
                restored.sourceFile("co.fanki.Service"));
        assertEquals(original.sourceFile("co.fanki.Repository"),
                restored.sourceFile("co.fanki.Repository"));

        assertEquals("id-ctrl", restored.classId("co.fanki.Controller"));
        assertEquals("id-svc", restored.classId("co.fanki.Service"));
        assertNull(restored.classId("co.fanki.Repository"));
    }

    @Test
    void whenSerializingToJson_givenEdges_shouldPreserveDependencies() {
        final ProjectGraph original = buildSimpleChainGraph();
        original.markAsEntryPoint("co.fanki.Controller");

        final ProjectGraph restored = ProjectGraph.fromJson(original.toJson());

        final Set<String> originalNeighbors =
                original.resolve("co.fanki.Service");
        final Set<String> restoredNeighbors =
                restored.resolve("co.fanki.Service");

        assertEquals(originalNeighbors, restoredNeighbors);
    }

    @Test
    void whenSerializingToJson_givenEmptyGraph_shouldRoundTrip() {
        final ProjectGraph original = new ProjectGraph();

        final String json = original.toJson();
        final ProjectGraph restored = ProjectGraph.fromJson(json);

        assertEquals(0, restored.nodeCount());
        assertEquals(0, restored.entryPointCount());
    }

    @Test
    void whenSerializingToJson_givenEntryPoints_shouldPreserveThem() {
        final ProjectGraph original = new ProjectGraph();
        original.addNode("co.fanki.A", "src/A.java");
        original.addNode("co.fanki.B", "src/B.java");
        original.markAsEntryPoint("co.fanki.A");
        original.markAsEntryPoint("co.fanki.B");

        final ProjectGraph restored = ProjectGraph.fromJson(original.toJson());

        assertEquals(2, restored.entryPointCount());
    }

    @Test
    void whenSerializingToJson_givenAnalysisOrder_shouldBePreserved() {
        final ProjectGraph original = buildSimpleChainGraph();
        original.markAsEntryPoint("co.fanki.Controller");

        final ProjectGraph restored = ProjectGraph.fromJson(original.toJson());

        assertEquals(original.analysisOrder(), restored.analysisOrder());
    }

    @Test
    void whenDeserializingFromJson_givenNullJson_shouldThrowException() {
        assertThrows(IllegalArgumentException.class,
                () -> ProjectGraph.fromJson(null));
    }

    @Test
    void whenDeserializingFromJson_givenBlankJson_shouldThrowException() {
        assertThrows(IllegalArgumentException.class,
                () -> ProjectGraph.fromJson("  "));
    }

    @Test
    void whenDeserializingFromJson_givenMalformedJson_shouldThrowException() {
        assertThrows(RuntimeException.class,
                () -> ProjectGraph.fromJson("{invalid-json"));
    }

    // -- identifiers -------------------------------------------------------

    @Test
    void whenGettingIdentifiers_givenPopulatedGraph_shouldReturnAllKeys() {
        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.A", "src/A.java");
        graph.addNode("co.fanki.B", "src/B.java");

        final Set<String> identifiers = graph.identifiers();

        assertEquals(Set.of("co.fanki.A", "co.fanki.B"), identifiers);
    }

    @Test
    void whenGettingIdentifiers_givenEmptyGraph_shouldReturnEmptySet() {
        final ProjectGraph graph = new ProjectGraph();

        assertTrue(graph.identifiers().isEmpty());
    }

    @Test
    void whenGettingIdentifiers_shouldBeUnmodifiable() {
        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.A", "src/A.java");

        final Set<String> identifiers = graph.identifiers();

        assertThrows(UnsupportedOperationException.class,
                () -> identifiers.add("co.fanki.B"));
    }

    // -- nodeCount / entryPointCount edge cases ----------------------------

    @Test
    void whenGettingNodeCount_givenEmptyGraph_shouldReturnZero() {
        final ProjectGraph graph = new ProjectGraph();

        assertEquals(0, graph.nodeCount());
    }

    @Test
    void whenGettingEntryPointCount_givenNoEntryPoints_shouldReturnZero() {
        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.A", "src/A.java");

        assertEquals(0, graph.entryPointCount());
    }

    // -- Complex graph scenarios -------------------------------------------

    @Test
    void whenResolving_givenCyclicDependency_shouldReturnCorrectNeighbors() {
        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.A", "src/A.java");
        graph.addNode("co.fanki.B", "src/B.java");

        graph.addDependency("co.fanki.A", "co.fanki.B");
        graph.addDependency("co.fanki.B", "co.fanki.A");

        final Set<String> neighborsOfA = graph.resolve("co.fanki.A");
        assertEquals(Set.of("co.fanki.B"), neighborsOfA);

        final Set<String> neighborsOfB = graph.resolve("co.fanki.B");
        assertEquals(Set.of("co.fanki.A"), neighborsOfB);
    }

    @Test
    void whenComputingAnalysisOrder_givenCyclicDependency_shouldNotLoop() {
        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.A", "src/A.java");
        graph.addNode("co.fanki.B", "src/B.java");

        graph.addDependency("co.fanki.A", "co.fanki.B");
        graph.addDependency("co.fanki.B", "co.fanki.A");
        graph.markAsEntryPoint("co.fanki.A");

        final List<String> order = graph.analysisOrder();

        assertEquals(2, order.size());
        assertEquals("co.fanki.A", order.get(0));
        assertEquals("co.fanki.B", order.get(1));
    }

    @Test
    void whenSerializingToJson_givenCyclicGraph_shouldRoundTrip() {
        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.A", "src/A.java");
        graph.addNode("co.fanki.B", "src/B.java");

        graph.addDependency("co.fanki.A", "co.fanki.B");
        graph.addDependency("co.fanki.B", "co.fanki.A");
        graph.markAsEntryPoint("co.fanki.A");

        final ProjectGraph restored = ProjectGraph.fromJson(graph.toJson());

        assertEquals(graph.resolve("co.fanki.A"),
                restored.resolve("co.fanki.A"));
        assertEquals(graph.resolve("co.fanki.B"),
                restored.resolve("co.fanki.B"));
        assertEquals(graph.analysisOrder(), restored.analysisOrder());
    }

    @Test
    void whenResolving_givenSelfDependency_shouldIncludeSelf() {
        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.A", "src/A.java");

        graph.addDependency("co.fanki.A", "co.fanki.A");

        final Set<String> neighbors = graph.resolve("co.fanki.A");
        assertEquals(Set.of("co.fanki.A"), neighbors);
    }

    // -- JSON output structure verification --------------------------------

    @Test
    void whenSerializingToJson_shouldProduceValidJsonString() {
        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.User", "src/User.java");

        final String json = graph.toJson();

        assertNotNull(json);
        assertTrue(json.contains("\"nodes\""));
        assertTrue(json.contains("\"edges\""));
        assertTrue(json.contains("\"entryPoints\""));
        assertTrue(json.contains("co.fanki.User"));
        assertTrue(json.contains("src/User.java"));
    }

    // -- helpers -----------------------------------------------------------

    /**
     * Builds a simple three-node chain graph representing a typical
     * Controller to Service to Repository dependency path.
     *
     * <p>Controller -> Service -> Repository</p>
     *
     * @return a populated ProjectGraph with three nodes and two edges
     */
    private ProjectGraph buildSimpleChainGraph() {
        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.Controller", "src/Controller.java");
        graph.addNode("co.fanki.Service", "src/Service.java");
        graph.addNode("co.fanki.Repository", "src/Repository.java");

        graph.addDependency("co.fanki.Controller", "co.fanki.Service");
        graph.addDependency("co.fanki.Service", "co.fanki.Repository");

        return graph;
    }
}
