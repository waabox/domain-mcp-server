package co.fanki.domainmcp.analysis.application;

import co.fanki.domainmcp.analysis.domain.GraphService;
import co.fanki.domainmcp.analysis.domain.ProjectGraph;
import co.fanki.domainmcp.shared.DomainException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes graph queries entirely from the in-memory graph cache.
 *
 * <p>No database access at query time. All metadata (classType,
 * descriptions, methods, endpoints) is resolved from the
 * {@link ProjectGraph} held by {@link GraphService}.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Service
public class GraphQueryService {

    private final GraphService graphService;

    /**
     * Creates a new GraphQueryService.
     *
     * @param theGraphService the graph service cache
     */
    public GraphQueryService(final GraphService theGraphService) {
        this.graphService = theGraphService;
    }

    /**
     * Executes a parsed graph query and returns results.
     *
     * @param query the parsed graph query
     * @return the query result
     */
    public GraphQueryResult execute(final GraphQuery query) {
        final ProjectGraph graph =
                graphService.getGraphByProjectName(query.project());

        if (graph == null) {
            throw new DomainException(
                    "Project not found: " + query.project()
                            + ". Use list_projects to see available projects.",
                    "PROJECT_NOT_FOUND");
        }

        return switch (query.category()) {
            case ENDPOINTS -> executeEndpoints(query, graph);
            case CLASSES -> executeClasses(query, graph);
            case ENTRYPOINTS -> executeEntrypoints(query, graph);
            case CLASS -> executeClass(query, graph);
        };
    }

    private GraphQueryResult executeEndpoints(final GraphQuery query,
            final ProjectGraph graph) {

        final boolean includeLogic = query.hasSegment("logic");
        final List<Map.Entry<String, ProjectGraph.MethodInfo>> endpoints =
                graph.allEndpoints();

        final List<Map<String, Object>> results = new ArrayList<>();
        for (final Map.Entry<String, ProjectGraph.MethodInfo> entry
                : endpoints) {

            final String identifier = entry.getKey();
            final ProjectGraph.MethodInfo mi = entry.getValue();
            final ProjectGraph.NodeInfo ni = graph.nodeInfo(identifier);

            final Map<String, Object> item = new LinkedHashMap<>();
            item.put("className", identifier);
            item.put("classType", ni != null ? ni.classType() : null);
            item.put("methodName", mi.methodName());
            item.put("httpMethod", mi.httpMethod());
            item.put("httpPath", mi.httpPath());
            item.put("description", mi.description());

            if (includeLogic) {
                item.put("businessLogic", mi.businessLogic());
            }

            results.add(item);
        }

        return new GraphQueryResult("endpoints", query.project(),
                results.size(), results);
    }

    private GraphQueryResult executeClasses(final GraphQuery query,
            final ProjectGraph graph) {

        final boolean includeDeps = query.hasSegment("dependencies");
        final boolean includeDependents = query.hasSegment("dependents");

        final Set<String> identifiers = graph.identifiers();
        final List<Map<String, Object>> results = new ArrayList<>();

        for (final String identifier : identifiers) {
            final ProjectGraph.NodeInfo ni = graph.nodeInfo(identifier);

            final Map<String, Object> item = new LinkedHashMap<>();
            item.put("className", identifier);
            item.put("classType", ni != null ? ni.classType() : null);
            item.put("description", ni != null ? ni.description() : null);
            item.put("sourceFile", graph.sourceFile(identifier));
            item.put("entryPoint", graph.isEntryPoint(identifier));

            if (includeDeps) {
                item.put("dependencies",
                        List.copyOf(graph.dependencies(identifier)));
            }
            if (includeDependents) {
                item.put("dependents",
                        List.copyOf(graph.dependents(identifier)));
            }

            results.add(item);
        }

        return new GraphQueryResult("classes", query.project(),
                results.size(), results);
    }

    private GraphQueryResult executeEntrypoints(final GraphQuery query,
            final ProjectGraph graph) {

        final boolean includeLogic = query.hasSegment("logic");
        final Set<String> entryPoints = graph.entryPoints();
        final List<Map<String, Object>> results = new ArrayList<>();

        for (final String ep : entryPoints) {
            final ProjectGraph.NodeInfo ni = graph.nodeInfo(ep);
            final List<ProjectGraph.MethodInfo> methods = graph.methods(ep);

            final Map<String, Object> item = new LinkedHashMap<>();
            item.put("className", ep);
            item.put("classType", ni != null ? ni.classType() : null);
            item.put("description", ni != null ? ni.description() : null);

            final List<Map<String, Object>> endpointMethods =
                    new ArrayList<>();
            for (final ProjectGraph.MethodInfo mi : methods) {
                if (mi.isHttpEndpoint()) {
                    final Map<String, Object> mItem = new LinkedHashMap<>();
                    mItem.put("methodName", mi.methodName());
                    mItem.put("httpEndpoint", mi.httpEndpoint());
                    mItem.put("description", mi.description());
                    if (includeLogic) {
                        mItem.put("businessLogic", mi.businessLogic());
                    }
                    endpointMethods.add(mItem);
                }
            }
            item.put("endpoints", endpointMethods);

            results.add(item);
        }

        return new GraphQueryResult("entrypoints", query.project(),
                results.size(), results);
    }

    private GraphQueryResult executeClass(final GraphQuery query,
            final ProjectGraph graph) {

        final String className = query.firstSegment();
        final String identifier = resolveClassName(className, graph);

        if (identifier == null) {
            throw new DomainException(
                    "Class not found: " + className
                            + " in project " + query.project(),
                    "CLASS_NOT_FOUND");
        }

        final List<String> subSegments = query.segmentsFrom(1);
        final ProjectGraph.NodeInfo ni = graph.nodeInfo(identifier);

        // sub-navigation: methods, dependencies, dependents, method
        if (!subSegments.isEmpty()) {
            final String nav = subSegments.get(0).toLowerCase();
            return switch (nav) {
                case "methods" -> classMethodsResult(
                        identifier, ni, graph, query,
                        subSegments.size() > 1 && "logic".equals(
                                subSegments.get(1).toLowerCase()));
                case "dependencies" -> classDepsResult(
                        identifier, ni, graph, query, true);
                case "dependents" -> classDepsResult(
                        identifier, ni, graph, query, false);
                case "method" -> {
                    if (subSegments.size() < 2) {
                        throw new DomainException(
                                "Method name required."
                                        + " Example: project:class:Foo"
                                        + ":method:bar",
                                "INVALID_QUERY");
                    }
                    yield singleMethodResult(identifier, ni, graph,
                            query, subSegments.get(1));
                }
                default -> classOverviewResult(
                        identifier, ni, graph, query);
            };
        }

        return classOverviewResult(identifier, ni, graph, query);
    }

    private GraphQueryResult classOverviewResult(
            final String identifier, final ProjectGraph.NodeInfo ni,
            final ProjectGraph graph, final GraphQuery query) {

        final Map<String, Object> item = new LinkedHashMap<>();
        item.put("className", identifier);
        item.put("classType", ni != null ? ni.classType() : null);
        item.put("description", ni != null ? ni.description() : null);
        item.put("sourceFile", graph.sourceFile(identifier));
        item.put("entryPoint", graph.isEntryPoint(identifier));
        item.put("dependencies",
                List.copyOf(graph.dependencies(identifier)));
        item.put("dependents",
                List.copyOf(graph.dependents(identifier)));

        final List<Map<String, Object>> methodSummaries =
                new ArrayList<>();
        for (final ProjectGraph.MethodInfo mi : graph.methods(identifier)) {
            final Map<String, Object> mItem = new LinkedHashMap<>();
            mItem.put("methodName", mi.methodName());
            mItem.put("description", mi.description());
            if (mi.isHttpEndpoint()) {
                mItem.put("httpEndpoint", mi.httpEndpoint());
            }
            methodSummaries.add(mItem);
        }
        item.put("methods", methodSummaries);

        return new GraphQueryResult("class", query.project(),
                1, List.of(item));
    }

    private GraphQueryResult classMethodsResult(
            final String identifier, final ProjectGraph.NodeInfo ni,
            final ProjectGraph graph, final GraphQuery query,
            final boolean includeLogic) {

        final List<Map<String, Object>> results = new ArrayList<>();
        for (final ProjectGraph.MethodInfo mi : graph.methods(identifier)) {
            final Map<String, Object> item = new LinkedHashMap<>();
            item.put("methodName", mi.methodName());
            item.put("description", mi.description());
            item.put("businessLogic", mi.businessLogic());
            item.put("exceptions", mi.exceptions());
            if (mi.isHttpEndpoint()) {
                item.put("httpMethod", mi.httpMethod());
                item.put("httpPath", mi.httpPath());
            }
            if (mi.lineNumber() != null) {
                item.put("lineNumber", mi.lineNumber());
            }
            results.add(item);
        }

        return new GraphQueryResult("methods", query.project(),
                results.size(), results);
    }

    private GraphQueryResult classDepsResult(
            final String identifier, final ProjectGraph.NodeInfo ni,
            final ProjectGraph graph, final GraphQuery query,
            final boolean outgoing) {

        final Set<String> related = outgoing
                ? graph.dependencies(identifier)
                : graph.dependents(identifier);

        final List<Map<String, Object>> results = new ArrayList<>();
        for (final String dep : related) {
            final ProjectGraph.NodeInfo depNi = graph.nodeInfo(dep);
            final Map<String, Object> item = new LinkedHashMap<>();
            item.put("className", dep);
            item.put("classType",
                    depNi != null ? depNi.classType() : null);
            item.put("description",
                    depNi != null ? depNi.description() : null);
            item.put("sourceFile", graph.sourceFile(dep));
            results.add(item);
        }

        final String type = outgoing ? "dependencies" : "dependents";
        return new GraphQueryResult(type, query.project(),
                results.size(), results);
    }

    private GraphQueryResult singleMethodResult(
            final String identifier, final ProjectGraph.NodeInfo ni,
            final ProjectGraph graph, final GraphQuery query,
            final String methodName) {

        final List<ProjectGraph.MethodInfo> methods =
                graph.methods(identifier);

        final String normalizedName = methodName.toLowerCase();

        for (final ProjectGraph.MethodInfo mi : methods) {
            if (mi.methodName().toLowerCase().equals(normalizedName)) {
                final Map<String, Object> item = new LinkedHashMap<>();
                item.put("className", identifier);
                item.put("methodName", mi.methodName());
                item.put("description", mi.description());
                item.put("businessLogic", mi.businessLogic());
                item.put("exceptions", mi.exceptions());
                if (mi.isHttpEndpoint()) {
                    item.put("httpMethod", mi.httpMethod());
                    item.put("httpPath", mi.httpPath());
                }
                if (mi.lineNumber() != null) {
                    item.put("lineNumber", mi.lineNumber());
                }

                return new GraphQueryResult("method", query.project(),
                        1, List.of(item));
            }
        }

        throw new DomainException(
                "Method not found: " + methodName
                        + " in class " + identifier,
                "METHOD_NOT_FOUND");
    }

    /**
     * Resolves a simple or partial class name to a full identifier
     * in the graph. Tries exact match first, then suffix match.
     */
    private String resolveClassName(final String className,
            final ProjectGraph graph) {

        if (graph.contains(className)) {
            return className;
        }

        // Try suffix match (simple name)
        final String lowerName = className.toLowerCase();
        String bestMatch = null;

        for (final String id : graph.identifiers()) {
            final String simpleName = extractSimpleName(id);
            if (simpleName.toLowerCase().equals(lowerName)) {
                if (bestMatch != null) {
                    // Ambiguous — return first match
                    return bestMatch;
                }
                bestMatch = id;
            }
        }

        if (bestMatch != null) {
            return bestMatch;
        }

        // Try contains match
        for (final String id : graph.identifiers()) {
            if (id.toLowerCase().contains(lowerName)) {
                return id;
            }
        }

        return null;
    }

    private String extractSimpleName(final String fqcn) {
        final int lastDot = fqcn.lastIndexOf('.');
        return lastDot >= 0 ? fqcn.substring(lastDot + 1) : fqcn;
    }

    /**
     * Result of a graph query.
     *
     * @param resultType the type of results returned
     * @param project the project name
     * @param count the number of results
     * @param results the result data
     */
    public record GraphQueryResult(
            String resultType,
            String project,
            int count,
            List<Map<String, Object>> results) {}
}
