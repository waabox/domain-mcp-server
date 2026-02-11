package co.fanki.domainmcp.analysis.application;

import co.fanki.domainmcp.analysis.application.CodeContextService.AnalysisResult;
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

    /**
     * Creates a new AnalyzeProjectController.
     *
     * @param theCodeContextService the code context service
     */
    public AnalyzeProjectController(
            final CodeContextService theCodeContextService) {
        this.codeContextService = theCodeContextService;
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

}
