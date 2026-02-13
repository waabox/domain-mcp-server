package co.fanki.domainmcp.analysis.application;

import co.fanki.domainmcp.analysis.application.CodeContextService.AnalysisResult;
import co.fanki.domainmcp.analysis.application.ProjectSyncService.SyncAllResult;
import co.fanki.domainmcp.analysis.application.ProjectSyncService.SyncResult;
import co.fanki.domainmcp.shared.DomainException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for project analysis operations.
 *
 * <p>Provides the analyze_project MCP tool functionality.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@RestController
@RequestMapping("/api/projects")
@Tag(name = "Project Analysis", description = "Analyze git repositories and extract class/method information")
public class AnalyzeProjectController {

    private static final Logger LOG = LoggerFactory.getLogger(
            AnalyzeProjectController.class);

    private final CodeContextService codeContextService;
    private final ProjectSyncService projectSyncService;

    /**
     * Creates a new AnalyzeProjectController.
     *
     * @param theCodeContextService the code context service
     * @param theProjectSyncService the project sync service
     */
    public AnalyzeProjectController(
            final CodeContextService theCodeContextService,
            final ProjectSyncService theProjectSyncService) {
        this.codeContextService = theCodeContextService;
        this.projectSyncService = theProjectSyncService;
    }

    /**
     * Analyzes a project and stores the extracted class/method information.
     *
     * <p>This endpoint implements the analyze_project MCP tool.</p>
     *
     * @param request the analysis request
     * @return the analysis result
     */
    @Operation(
            summary = "Analyze a git repository",
            description = "Clones and analyzes a git repository using Claude Code to extract " +
                    "class and method information including business logic, dependencies, and HTTP endpoints."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Analysis completed",
                    content = @Content(schema = @Schema(implementation = AnalyzeResponse.class))),
            @ApiResponse(responseCode = "500", description = "Analysis failed")
    })
    @PostMapping("/analyze")
    public ResponseEntity<AnalyzeResponse> analyzeProject(
            @RequestBody final AnalyzeRequest request) {

        LOG.info("Received analyze request for: {}", request.repositoryUrl());

        try {
            final boolean fixMissed = request.fixMissed() == null
                    || request.fixMissed();
            final AnalysisResult result = codeContextService.analyzeProject(
                    request.repositoryUrl(), request.branch(), fixMissed);

            return ResponseEntity.ok(new AnalyzeResponse(
                    result.success(),
                    result.projectId(),
                    result.classesAnalyzed(),
                    result.endpointsFound(),
                    result.message()));

        } catch (final DomainException e) {
            LOG.error("Analysis failed: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(new AnalyzeResponse(
                            false, null, 0, 0, e.getMessage()));
        } catch (final Exception e) {
            LOG.error("Unexpected error during analysis", e);
            return ResponseEntity.internalServerError()
                    .body(new AnalyzeResponse(
                            false, null, 0, 0, "Internal error: " + e.getMessage()));
        }
    }

    /**
     * Rebuilds the project graph without re-running Claude enrichment.
     *
     * <p>Re-clones the repository, re-parses source code with the current
     * parser, and rebuilds the structural graph. Preserves all existing
     * enrichment data (descriptions, business logic).</p>
     *
     * @param projectId the project ID to rebuild the graph for
     * @return the rebuild result
     */
    @Operation(
            summary = "Rebuild project graph",
            description = "Re-clones and re-parses the repository to rebuild the structural graph " +
                    "without re-running Claude enrichment. Useful after parser improvements."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Graph rebuilt",
                    content = @Content(schema = @Schema(
                            implementation = RebuildGraphResponse.class))),
            @ApiResponse(responseCode = "500", description = "Rebuild failed")
    })
    @PostMapping("/{id}/rebuild-graph")
    public ResponseEntity<RebuildGraphResponse> rebuildGraph(
            @PathVariable("id") final String projectId) {

        LOG.info("Received rebuild-graph request for project: {}", projectId);

        try {
            codeContextService.rebuildGraph(projectId);
            return ResponseEntity.ok(new RebuildGraphResponse(
                    true, projectId, "Graph rebuilt successfully"));
        } catch (final DomainException e) {
            LOG.error("Rebuild-graph failed: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(new RebuildGraphResponse(
                            false, projectId, e.getMessage()));
        } catch (final Exception e) {
            LOG.error("Unexpected error during rebuild-graph", e);
            return ResponseEntity.internalServerError()
                    .body(new RebuildGraphResponse(
                            false, projectId,
                            "Internal error: " + e.getMessage()));
        }
    }

    /**
     * Manually triggers incremental sync for all eligible projects.
     *
     * <p>Replicates the same logic as the scheduled cron task: fetches
     * all projects with {@code ANALYZED} status and syncs each one
     * incrementally using git diffs.</p>
     *
     * @return the sync results for each project
     */
    @Operation(
            summary = "Trigger manual sync for all projects",
            description = "Runs the same incremental sync that the scheduled cron "
                    + "task performs. Syncs all projects with ANALYZED status, "
                    + "re-enriching only changed or new classes."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sync completed",
                    content = @Content(schema = @Schema(
                            implementation = SyncAllResponse.class))),
            @ApiResponse(responseCode = "500", description = "Sync failed")
    })
    @PostMapping("/sync")
    public ResponseEntity<SyncAllResponse> syncAllProjects() {

        LOG.info("Received manual sync request");

        final SyncAllResult result = projectSyncService.syncAllProjects();

        final List<ProjectSyncSummary> summaries = result.results().stream()
                .map(r -> new ProjectSyncSummary(
                        r.projectName(),
                        r.success(),
                        r.addedClasses(),
                        r.updatedClasses(),
                        r.deletedClasses(),
                        r.unchangedClasses(),
                        r.errorMessage()))
                .toList();

        return ResponseEntity.ok(new SyncAllResponse(
                result.success(),
                result.totalProjects(),
                result.successCount(),
                result.failureCount(),
                summaries,
                "Sync completed"));
    }

    /**
     * Request for project analysis.
     *
     * @param repositoryUrl the git repository URL
     * @param branch the branch to analyze (optional, defaults to main)
     * @param fixMissed whether to run Phase 3 recovery for unenriched
     *     classes (optional, defaults to true)
     */
    public record AnalyzeRequest(
            String repositoryUrl,
            String branch,
            Boolean fixMissed
    ) {}

    /**
     * Response from project analysis.
     */
    public record AnalyzeResponse(
            boolean success,
            String projectId,
            int classesAnalyzed,
            int endpointsFound,
            String message
    ) {}

    /**
     * Response from graph rebuild.
     *
     * @param success whether the rebuild succeeded
     * @param projectId the project ID
     * @param message a human-readable result message
     */
    public record RebuildGraphResponse(
            boolean success,
            String projectId,
            String message
    ) {}

    /**
     * Response from sync-all operation.
     *
     * @param success true if all projects synced without errors
     * @param totalProjects number of eligible projects
     * @param successCount number of successful syncs
     * @param failureCount number of failed syncs
     * @param projects per-project sync summaries
     * @param message a human-readable result message
     */
    public record SyncAllResponse(
            boolean success,
            int totalProjects,
            int successCount,
            int failureCount,
            List<ProjectSyncSummary> projects,
            String message
    ) {}

    /**
     * Per-project sync summary.
     *
     * @param projectName the project name
     * @param success whether this project synced successfully
     * @param addedClasses number of new classes found
     * @param updatedClasses number of updated classes
     * @param deletedClasses number of deleted classes
     * @param unchangedClasses number of unchanged classes
     * @param errorMessage error message if sync failed, null otherwise
     */
    public record ProjectSyncSummary(
            String projectName,
            boolean success,
            int addedClasses,
            int updatedClasses,
            int deletedClasses,
            int unchangedClasses,
            String errorMessage
    ) {}

}
