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

    /**
     * Links a method parameter to its target class in the project.
     *
     * @param position the parameter position (0-based) in the method signature
     * @param targetIdentifier the FQCN of the parameter type
     */
    public record MethodParameterLink(int position, String targetIdentifier) {}

    /** Maps identifier to source file path. */
    private final Map<String, String> nodes;

    /** Maps identifier to its direct dependencies (outgoing edges). */
    private final Map<String, Set<String>> edges;

    /** Identifiers marked as entry points (controllers, listeners, etc.). */
    private final Set<String> entryPoints;

    /** Maps identifier to the persisted source class ID in the database. */
    private final Map<String, String> classIdMapping;

    /**
     * Maps classIdentifier to methodName to parameter links.
     *
     * <p>Structure: classIdentifier -> methodName -> [MethodParameterLink]</p>
     */
    private final Map<String, Map<String, List<MethodParameterLink>>>
            methodParameters;

    /**
     * Creates an empty project graph.
     */
    public ProjectGraph() {
        this.nodes = new LinkedHashMap<>();
        this.edges = new HashMap<>();
        this.entryPoints = new LinkedHashSet<>();
        this.classIdMapping = new HashMap<>();
        this.methodParameters = new HashMap<>();
    }

    private ProjectGraph(
            final Map<String, String> theNodes,
            final Map<String, Set<String>> theEdges,
            final Set<String> theEntryPoints,
            final Map<String, String> theClassIdMapping,
            final Map<String, Map<String, List<MethodParameterLink>>>
                    theMethodParameters) {
        this.nodes = new LinkedHashMap<>(theNodes);
        this.edges = new HashMap<>();
        for (final Map.Entry<String, Set<String>> entry : theEdges.entrySet()) {
            this.edges.put(entry.getKey(),
                    new LinkedHashSet<>(entry.getValue()));
        }
        this.entryPoints = new LinkedHashSet<>(theEntryPoints);
        this.classIdMapping = new HashMap<>(theClassIdMapping);
        this.methodParameters = deepCopyMethodParameters(theMethodParameters);
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
     * Adds a method parameter link to the graph.
     *
     * <p>Records that a method in classIdentifier accepts a parameter
     * of type parameterTypeIdentifier at the given position. Silently
     * ignored if either class is not a known node in the graph (same
     * pattern as {@link #addDependency}).</p>
     *
     * @param classIdentifier the owning class FQCN
     * @param methodName the method name
     * @param position the 0-based parameter position
     * @param parameterTypeIdentifier the parameter type FQCN
     */
    public void addMethodParameter(final String classIdentifier,
            final String methodName, final int position,
            final String parameterTypeIdentifier) {

        Preconditions.requireNonBlank(classIdentifier,
                "Class identifier is required");
        Preconditions.requireNonBlank(methodName,
                "Method name is required");
        Preconditions.requireNonNegative(position,
                "Position must be non-negative");
        Preconditions.requireNonBlank(parameterTypeIdentifier,
                "Parameter type identifier is required");

        if (!nodes.containsKey(classIdentifier)
                || !nodes.containsKey(parameterTypeIdentifier)) {
            return;
        }

        methodParameters
                .computeIfAbsent(classIdentifier, k -> new HashMap<>())
                .computeIfAbsent(methodName, k -> new ArrayList<>())
                .add(new MethodParameterLink(position,
                        parameterTypeIdentifier));
    }

    /**
     * Returns the method parameter links for a given class.
     *
     * @param classIdentifier the class FQCN
     * @return unmodifiable map of methodName to parameter links,
     *         empty if unknown
     */
    public Map<String, List<MethodParameterLink>> methodParameters(
            final String classIdentifier) {

        final Map<String, List<MethodParameterLink>> perMethod =
                methodParameters.get(classIdentifier);

        if (perMethod == null) {
            return Map.of();
        }

        final Map<String, List<MethodParameterLink>> result =
                new LinkedHashMap<>();
        for (final Map.Entry<String, List<MethodParameterLink>> entry
                : perMethod.entrySet()) {
            result.put(entry.getKey(),
                    Collections.unmodifiableList(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns all target identifiers referenced via method parameters
     * for a given class.
     *
     * @param classIdentifier the class FQCN
     * @return unmodifiable set of target identifiers, empty if none
     */
    public Set<String> methodParameterTargets(
            final String classIdentifier) {

        final Map<String, List<MethodParameterLink>> perMethod =
                methodParameters.get(classIdentifier);

        if (perMethod == null) {
            return Set.of();
        }

        final Set<String> targets = new LinkedHashSet<>();
        for (final List<MethodParameterLink> links : perMethod.values()) {
            for (final MethodParameterLink link : links) {
                targets.add(link.targetIdentifier());
            }
        }
        return Collections.unmodifiableSet(targets);
    }

    /**
     * Returns the outgoing dependencies for a given identifier.
     *
     * <p>These are the classes that this identifier imports or depends on
     * (outgoing edges in the graph).</p>
     *
     * @param identifier the class identifier to query
     * @return unmodifiable set of dependency identifiers, empty if not found
     */
    public Set<String> dependencies(final String identifier) {
        if (identifier == null || !nodes.containsKey(identifier)) {
            return Set.of();
        }
        return Collections.unmodifiableSet(
                edges.getOrDefault(identifier, Set.of()));
    }

    /**
     * Returns the incoming dependents for a given identifier.
     *
     * <p>These are the classes that import or depend on this identifier
     * (reverse edges in the graph).</p>
     *
     * @param identifier the class identifier to query
     * @return unmodifiable set of dependent identifiers, empty if not found
     */
    public Set<String> dependents(final String identifier) {
        if (identifier == null || !nodes.containsKey(identifier)) {
            return Set.of();
        }

        final Set<String> result = new LinkedHashSet<>();
        for (final Map.Entry<String, Set<String>> entry : edges.entrySet()) {
            if (entry.getValue().contains(identifier)) {
                result.add(entry.getKey());
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * Checks if the given identifier is marked as an entry point.
     *
     * @param identifier the identifier to check
     * @return true if the identifier is an entry point
     */
    public boolean isEntryPoint(final String identifier) {
        return identifier != null && entryPoints.contains(identifier);
    }

    /**
     * Returns all entry point identifiers.
     *
     * @return unmodifiable set of entry point identifiers
     */
    public Set<String> entryPoints() {
        return Collections.unmodifiableSet(entryPoints);
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

        final ObjectNode methodParamsObj = MAPPER.createObjectNode();
        for (final Map.Entry<String, Map<String, List<MethodParameterLink>>>
                classEntry : methodParameters.entrySet()) {

            final ObjectNode methodsObj = MAPPER.createObjectNode();
            for (final Map.Entry<String, List<MethodParameterLink>>
                    methodEntry : classEntry.getValue().entrySet()) {

                final ArrayNode paramsArray = MAPPER.createArrayNode();
                for (final MethodParameterLink link
                        : methodEntry.getValue()) {
                    final ObjectNode paramObj = MAPPER.createObjectNode();
                    paramObj.put("position", link.position());
                    paramObj.put("target", link.targetIdentifier());
                    paramsArray.add(paramObj);
                }
                methodsObj.set(methodEntry.getKey(), paramsArray);
            }
            methodParamsObj.set(classEntry.getKey(), methodsObj);
        }
        root.set("methodParameters", methodParamsObj);

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

            final Map<String, Map<String, List<MethodParameterLink>>>
                    methodParams = new HashMap<>();
            final JsonNode mpNode = root.get("methodParameters");

            if (mpNode != null && mpNode.isObject()) {
                final var classFields = mpNode.fields();
                while (classFields.hasNext()) {
                    final var classEntry = classFields.next();
                    final String classId = classEntry.getKey();
                    final Map<String, List<MethodParameterLink>> perMethod =
                            new HashMap<>();

                    final var methodFields = classEntry.getValue().fields();
                    while (methodFields.hasNext()) {
                        final var methodEntry = methodFields.next();
                        final String methodName = methodEntry.getKey();
                        final List<MethodParameterLink> links =
                                new ArrayList<>();

                        for (final JsonNode paramNode
                                : methodEntry.getValue()) {
                            links.add(new MethodParameterLink(
                                    paramNode.get("position").asInt(),
                                    paramNode.get("target").asText()));
                        }
                        perMethod.put(methodName, links);
                    }
                    methodParams.put(classId, perMethod);
                }
            }

            return new ProjectGraph(nodes, edges, entryPoints, classIds,
                    methodParams);

        } catch (final JsonProcessingException e) {
            throw new RuntimeException(
                    "Failed to deserialize ProjectGraph", e);
        }
    }

    /**
     * Deep-copies the nested method parameters structure for defensive
     * copying in the private constructor.
     *
     * @param source the source map to copy
     * @return a deep copy of the method parameters map
     */
    private static Map<String, Map<String, List<MethodParameterLink>>>
            deepCopyMethodParameters(
                    final Map<String, Map<String, List<MethodParameterLink>>>
                            source) {

        final Map<String, Map<String, List<MethodParameterLink>>> copy =
                new HashMap<>();

        for (final Map.Entry<String, Map<String, List<MethodParameterLink>>>
                classEntry : source.entrySet()) {

            final Map<String, List<MethodParameterLink>> methodsCopy =
                    new HashMap<>();

            for (final Map.Entry<String, List<MethodParameterLink>>
                    methodEntry : classEntry.getValue().entrySet()) {
                methodsCopy.put(methodEntry.getKey(),
                        new ArrayList<>(methodEntry.getValue()));
            }
            copy.put(classEntry.getKey(), methodsCopy);
        }
        return copy;
    }

}
