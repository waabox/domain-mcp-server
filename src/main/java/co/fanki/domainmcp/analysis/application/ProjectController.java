package co.fanki.domainmcp.analysis.application;

import co.fanki.domainmcp.analysis.application.CodeContextService.ProjectSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * REST controller for project listing operations.
 *
 * <p>Provides the list_projects MCP tool functionality.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private static final Logger LOG = LoggerFactory.getLogger(
            ProjectController.class);

    private final CodeContextService codeContextService;

    /**
     * Creates a new ProjectController.
     *
     * @param theCodeContextService the code context service
     */
    public ProjectController(final CodeContextService theCodeContextService) {
        this.codeContextService = theCodeContextService;
    }

    /**
     * Lists all analyzed projects.
     *
     * <p>This endpoint implements the list_projects MCP tool.</p>
     *
     * @return the list of projects
     */
    @GetMapping
    public ResponseEntity<ProjectListResponse> listProjects() {
        LOG.debug("Listing all projects");

        final List<ProjectSummary> projects = codeContextService.listProjects();

        final List<ProjectResponse> projectResponses = projects.stream()
                .map(p -> new ProjectResponse(
                        p.id(),
                        p.name(),
                        p.repositoryUrl(),
                        p.basePackage(),
                        p.status(),
                        p.lastAnalyzedAt(),
                        p.classCount(),
                        p.endpointCount()))
                .toList();

        return ResponseEntity.ok(new ProjectListResponse(projectResponses));
    }

    /**
     * Response containing list of projects.
     */
    public record ProjectListResponse(
            List<ProjectResponse> projects
    ) {}

    /**
     * Response for a single project.
     */
    public record ProjectResponse(
            String id,
            String name,
            String repositoryUrl,
            String basePackage,
            String status,
            Instant lastAnalyzedAt,
            long classCount,
            long endpointCount
    ) {}

}
