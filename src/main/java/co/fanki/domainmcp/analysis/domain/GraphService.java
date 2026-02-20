package co.fanki.domainmcp.analysis.domain;

import co.fanki.domainmcp.project.domain.Project;
import co.fanki.domainmcp.project.domain.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of project dependency graphs.
 *
 * <p>Loads all project graphs at application startup and provides
 * fast lookup for query-time neighbor resolution. Graphs are keyed
 * by project ID, with a secondary index by project name.</p>
 *
 * <p>Graphs include full metadata (classType, descriptions, methods)
 * serialized in the JSON. Projects analyzed before metadata support
 * must be re-analyzed to populate the metadata.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Component
public class GraphService {

    private static final Logger LOG = LoggerFactory.getLogger(
            GraphService.class);

    private final Map<String, ProjectGraph> graphs =
            new ConcurrentHashMap<>();

    private final Map<String, String> nameToId =
            new ConcurrentHashMap<>();

    private final ProjectRepository projectRepository;

    /**
     * Creates a new GraphService.
     *
     * @param theProjectRepository the project repository
     */
    public GraphService(final ProjectRepository theProjectRepository) {
        this.projectRepository = theProjectRepository;
    }

    /**
     * Loads all project graphs from the database after the application
     * is fully initialized (including Flyway migrations).
     */
    @EventListener(ApplicationStartedEvent.class)
    public void loadAll() {
        LOG.info("Loading project graphs into cache");

        final List<Project> projects = projectRepository.findAllWithGraph();

        for (final Project project : projects) {
            try {
                final ProjectGraph graph = ProjectGraph.fromJson(
                        project.graphData());
                graphs.put(project.id(), graph);
                nameToId.put(project.name(), project.id());
                LOG.debug("Loaded graph for project {} ({} nodes)",
                        project.name(), graph.nodeCount());
            } catch (final Exception e) {
                LOG.warn("Failed to load graph for project {}: {}",
                        project.id(), e.getMessage());
            }
        }

        LOG.info("Loaded {} project graphs into cache", graphs.size());
    }

    /**
     * Returns the graph for a given project ID.
     *
     * @param projectId the project ID
     * @return the project graph, or null if not cached
     */
    public ProjectGraph getGraph(final String projectId) {
        return graphs.get(projectId);
    }

    /**
     * Reloads the graph for a specific project from the database.
     *
     * @param projectId the project ID
     */
    public void reload(final String projectId) {
        projectRepository.findById(projectId).ifPresent(project -> {
            if (project.graphData() != null) {
                try {
                    final ProjectGraph graph = ProjectGraph.fromJson(
                            project.graphData());
                    graphs.put(project.id(), graph);
                    nameToId.put(project.name(), project.id());
                    LOG.info("Reloaded graph for project {} ({} nodes)",
                            project.name(), graph.nodeCount());
                } catch (final Exception e) {
                    LOG.warn("Failed to reload graph for project {}: {}",
                            project.id(), e.getMessage());
                }
            }
        });
    }

    /**
     * Puts a graph directly into the cache (used after analysis).
     *
     * @param projectId the project ID
     * @param projectName the project name for name-based lookup
     * @param graph the project graph
     */
    public void put(final String projectId, final String projectName,
            final ProjectGraph graph) {
        graphs.put(projectId, graph);
        nameToId.put(projectName, projectId);
    }

    /**
     * Returns the graph for a given project name.
     *
     * @param projectName the project name
     * @return the project graph, or null if not cached
     */
    public ProjectGraph getGraphByProjectName(final String projectName) {
        final String projectId = nameToId.get(projectName);
        if (projectId == null) {
            return null;
        }
        return graphs.get(projectId);
    }

    /**
     * Returns the project ID for a given project name.
     *
     * @param projectName the project name
     * @return the project ID, or null if not found
     */
    public String getProjectIdByName(final String projectName) {
        return nameToId.get(projectName);
    }

}
