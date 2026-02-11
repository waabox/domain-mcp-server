package co.fanki.domainmcp.analysis.domain;

import co.fanki.domainmcp.shared.Preconditions;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Rich domain object representing the dependency graph of a project.
 *
 * <p>Language-agnostic: knows nothing about Java, Node, or Python.
 * Only models relationships between source units (classes, modules, etc.)
 * in a project. The API is domain-driven.</p>
 *
 * <p>Supports neighbor resolution at query time, BFS-ordered analysis,
 * class ID binding after persistence, and JSON serialization for
 * database storage.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class ProjectGraph {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Maps identifier to source file path. */
    private final Map<String, String> nodes;

    /** Maps identifier to its direct dependencies (outgoing edges). */
    private final Map<String, Set<String>> edges;

    /** Identifiers marked as entry points (controllers, listeners, etc.). */
    private final Set<String> entryPoints;

    /** Maps identifier to the persisted source class ID in the database. */
    private final Map<String, String> classIdMapping;

    /**
     * Creates an empty project graph.
     */
    public ProjectGraph() {
        this.nodes = new LinkedHashMap<>();
        this.edges = new HashMap<>();
        this.entryPoints = new LinkedHashSet<>();
        this.classIdMapping = new HashMap<>();
    }

    private ProjectGraph(
            final Map<String, String> theNodes,
            final Map<String, Set<String>> theEdges,
            final Set<String> theEntryPoints,
            final Map<String, String> theClassIdMapping) {
        this.nodes = new LinkedHashMap<>(theNodes);
        this.edges = new HashMap<>();
        for (final Map.Entry<String, Set<String>> entry : theEdges.entrySet()) {
            this.edges.put(entry.getKey(),
                    new LinkedHashSet<>(entry.getValue()));
        }
        this.entryPoints = new LinkedHashSet<>(theEntryPoints);
        this.classIdMapping = new HashMap<>(theClassIdMapping);
    }

    /**
     * Adds a node to the graph.
     *
     * @param identifier the language-specific identifier (e.g., FQCN)
     * @param sourceFile the relative file path in the repository
     */
    public void addNode(final String identifier, final String sourceFile) {
        Preconditions.requireNonBlank(identifier, "Identifier is required");
        Preconditions.requireNonBlank(sourceFile, "Source file is required");
        nodes.put(identifier, sourceFile);
    }

    /**
     * Adds a dependency edge from one identifier to another.
     *
     * <p>Only adds the edge if both the source and target identifiers
     * exist as nodes in the graph.</p>
     *
     * @param from the dependent identifier
     * @param to the dependency identifier
     */
    public void addDependency(final String from, final String to) {
        Preconditions.requireNonBlank(from, "From identifier is required");
        Preconditions.requireNonBlank(to, "To identifier is required");

        if (!nodes.containsKey(from) || !nodes.containsKey(to)) {
            return;
        }
        edges.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);
    }

    /**
     * Marks an identifier as an entry point (BFS root for analysis order).
     *
     * @param identifier the entry point identifier
     */
    public void markAsEntryPoint(final String identifier) {
        Preconditions.requireNonBlank(identifier,
                "Entry point identifier is required");

        if (nodes.containsKey(identifier)) {
            entryPoints.add(identifier);
        }
    }

    /**
     * Resolves the set of directly connected classes for a given identifier.
     *
     * <p>Returns both direct dependencies (outgoing edges) and reverse
     * dependencies (classes that depend on this one). This provides the
     * full neighborhood context for stack trace correlation.</p>
     *
     * @param identifier the class identifier to resolve neighbors for
     * @return the set of neighbor identifiers, empty if not found
     */
    public Set<String> resolve(final String identifier) {
        if (identifier == null || !nodes.containsKey(identifier)) {
            return Set.of();
        }

        final Set<String> neighbors = new LinkedHashSet<>();

        // Direct dependencies (outgoing)
        final Set<String> deps = edges.getOrDefault(identifier, Set.of());
        neighbors.addAll(deps);

        // Reverse dependencies (incoming)
        for (final Map.Entry<String, Set<String>> entry : edges.entrySet()) {
            if (entry.getValue().contains(identifier)) {
                neighbors.add(entry.getKey());
            }
        }

        return Collections.unmodifiableSet(neighbors);
    }

    /**
     * Returns an ordered list of identifiers for class-by-class analysis.
     *
     * <p>Uses BFS from entry points to produce an analysis order where
     * controllers come first, followed by services, repositories, and
     * entities. Classes not reachable from any entry point are appended
     * at the end.</p>
     *
     * @return ordered list of identifiers for analysis
     */
    public List<String> analysisOrder() {
        if (nodes.isEmpty()) {
            return List.of();
        }

        final Set<String> visited = new LinkedHashSet<>();
        final Queue<String> queue = new ArrayDeque<>();

        // Start from entry points
        for (final String ep : entryPoints) {
            if (visited.add(ep)) {
                queue.add(ep);
            }
        }

        while (!queue.isEmpty()) {
            final String current = queue.poll();
            final Set<String> deps = edges.getOrDefault(current, Set.of());
            for (final String dep : deps) {
                if (visited.add(dep)) {
                    queue.add(dep);
                }
            }
        }

        // Add orphans not reachable from entry points
        for (final String identifier : nodes.keySet()) {
            visited.add(identifier);
        }

        return List.copyOf(visited);
    }

    /**
     * Binds a database class ID to a graph identifier.
     *
     * <p>Called during analysis after each class is persisted to the
     * database, enabling graph-based queries at runtime.</p>
     *
     * @param identifier the class identifier
     * @param classId the database class ID
     */
    public void bindClassId(final String identifier, final String classId) {
        Preconditions.requireNonBlank(identifier, "Identifier is required");
        Preconditions.requireNonBlank(classId, "Class ID is required");
        classIdMapping.put(identifier, classId);
    }

    /**
     * Returns the database class ID for a given identifier.
     *
     * @param identifier the class identifier
     * @return the class ID, or null if not bound
     */
    public String classId(final String identifier) {
        return classIdMapping.get(identifier);
    }

    /**
     * Returns the source file path for a given identifier.
     *
     * @param identifier the class identifier
     * @return the source file path, or null if not found
     */
    public String sourceFile(final String identifier) {
        return nodes.get(identifier);
    }

    /**
     * Checks if the graph contains a given identifier.
     *
     * @param identifier the identifier to check
     * @return true if the graph contains the identifier
     */
    public boolean contains(final String identifier) {
        return nodes.containsKey(identifier);
    }

    /**
     * Returns the number of nodes in the graph.
     *
     * @return the node count
     */
    public int nodeCount() {
        return nodes.size();
    }

    /**
     * Returns the number of entry points.
     *
     * @return the entry point count
     */
    public int entryPointCount() {
        return entryPoints.size();
    }

    /**
     * Returns all identifiers in the graph.
     *
     * @return unmodifiable set of identifiers
     */
    public Set<String> identifiers() {
        return Collections.unmodifiableSet(nodes.keySet());
    }

    /**
     * Serializes this graph to a JSON string for database persistence.
     *
     * @return the JSON representation
     */
    public String toJson() {
        final ObjectNode root = MAPPER.createObjectNode();

        final ObjectNode nodesObj = MAPPER.createObjectNode();
        for (final Map.Entry<String, String> entry : nodes.entrySet()) {
            final ObjectNode nodeObj = MAPPER.createObjectNode();
            nodeObj.put("sourceFile", entry.getValue());
            final String cId = classIdMapping.get(entry.getKey());
            if (cId != null) {
                nodeObj.put("classId", cId);
            }
            nodesObj.set(entry.getKey(), nodeObj);
        }
        root.set("nodes", nodesObj);

        final ObjectNode edgesObj = MAPPER.createObjectNode();
        for (final Map.Entry<String, Set<String>> entry : edges.entrySet()) {
            final ArrayNode depsArray = MAPPER.createArrayNode();
            for (final String dep : entry.getValue()) {
                depsArray.add(dep);
            }
            edgesObj.set(entry.getKey(), depsArray);
        }
        root.set("edges", edgesObj);

        final ArrayNode entryPointsArray = MAPPER.createArrayNode();
        for (final String ep : entryPoints) {
            entryPointsArray.add(ep);
        }
        root.set("entryPoints", entryPointsArray);

        try {
            return MAPPER.writeValueAsString(root);
        } catch (final JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize ProjectGraph", e);
        }
    }

    /**
     * Deserializes a ProjectGraph from a JSON string.
     *
     * @param json the JSON representation
     * @return the deserialized ProjectGraph
     */
    public static ProjectGraph fromJson(final String json) {
        Preconditions.requireNonBlank(json, "JSON is required");

        try {
            final JsonNode root = MAPPER.readTree(json);

            final Map<String, String> nodes = new LinkedHashMap<>();
            final Map<String, String> classIds = new HashMap<>();
            final JsonNode nodesNode = root.get("nodes");

            if (nodesNode != null && nodesNode.isObject()) {
                final var fields = nodesNode.fields();
                while (fields.hasNext()) {
                    final var entry = fields.next();
                    final String identifier = entry.getKey();
                    final JsonNode value = entry.getValue();
                    nodes.put(identifier, value.get("sourceFile").asText());
                    if (value.has("classId") && !value.get("classId").isNull()) {
                        classIds.put(identifier,
                                value.get("classId").asText());
                    }
                }
            }

            final Map<String, Set<String>> edges = new HashMap<>();
            final JsonNode edgesNode = root.get("edges");

            if (edgesNode != null && edgesNode.isObject()) {
                final var fields = edgesNode.fields();
                while (fields.hasNext()) {
                    final var entry = fields.next();
                    final Set<String> deps = new LinkedHashSet<>();
                    for (final JsonNode dep : entry.getValue()) {
                        deps.add(dep.asText());
                    }
                    edges.put(entry.getKey(), deps);
                }
            }

            final Set<String> entryPoints = new LinkedHashSet<>();
            final JsonNode epNode = root.get("entryPoints");

            if (epNode != null && epNode.isArray()) {
                for (final JsonNode ep : epNode) {
                    entryPoints.add(ep.asText());
                }
            }

            return new ProjectGraph(nodes, edges, entryPoints, classIds);

        } catch (final JsonProcessingException e) {
            throw new RuntimeException(
                    "Failed to deserialize ProjectGraph", e);
        }
    }

}
