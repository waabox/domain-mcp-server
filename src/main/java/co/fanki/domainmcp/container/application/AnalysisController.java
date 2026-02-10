package co.fanki.domainmcp.container.application;

import co.fanki.domainmcp.container.domain.AnalysisOutput;
import co.fanki.domainmcp.container.domain.ContainerImage;
import co.fanki.domainmcp.project.domain.RepositoryUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller for code analysis operations.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private static final Logger LOG = LoggerFactory.getLogger(
            AnalysisController.class);

    private final ContainerAnalysisService analysisService;

    /**
     * Creates a new AnalysisController.
     *
     * @param theAnalysisService the analysis service
     */
    public AnalysisController(
            final ContainerAnalysisService theAnalysisService) {
        this.analysisService = theAnalysisService;
    }

    /**
     * Analyzes a repository for API endpoints and business logic.
     *
     * @param request the analysis request
     * @return the analysis result
     */
    @PostMapping("/repository")
    public ResponseEntity<AnalysisResponse> analyzeRepository(
            @RequestBody final AnalysisRequest request) {

        LOG.info("Received analysis request for: {}", request.repositoryUrl());

        try {
            final RepositoryUrl url = RepositoryUrl.of(request.repositoryUrl());
            final ContainerImage image = request.language() != null
                    ? ContainerImage.valueOf(request.language().toUpperCase())
                    : null;

            final AnalysisOutput output = analysisService.analyzeRepository(
                    url, image);

            if (output.isSuccess()) {
                return ResponseEntity.ok(AnalysisResponse.success(
                        output.summary(),
                        output.endpoints(),
                        output.duration().toSeconds()));
            } else {
                return ResponseEntity.internalServerError()
                        .body(AnalysisResponse.failure(output.errorMessage()));
            }

        } catch (IllegalArgumentException e) {
            LOG.warn("Invalid request", e);
            return ResponseEntity.badRequest()
                    .body(AnalysisResponse.failure("Invalid request: "
                            + e.getMessage()));
        } catch (Exception e) {
            LOG.error("Analysis failed", e);
            return ResponseEntity.internalServerError()
                    .body(AnalysisResponse.failure("Analysis failed: "
                            + e.getMessage()));
        }
    }

    /**
     * Analyzes a repository with a custom prompt.
     *
     * @param request the custom analysis request
     * @return the raw analysis output
     */
    @PostMapping("/custom")
    public ResponseEntity<Map<String, String>> analyzeWithCustomPrompt(
            @RequestBody final CustomAnalysisRequest request) {

        LOG.info("Received custom analysis request for: {}",
                request.repositoryUrl());

        try {
            final RepositoryUrl url = RepositoryUrl.of(request.repositoryUrl());

            final String output = analysisService.analyzeWithCustomPrompt(
                    url, request.prompt());

            return ResponseEntity.ok(Map.of("output", output));

        } catch (Exception e) {
            LOG.error("Custom analysis failed", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Request for repository analysis.
     */
    public record AnalysisRequest(
            String repositoryUrl,
            String language
    ) {}

    /**
     * Request for custom prompt analysis.
     */
    public record CustomAnalysisRequest(
            String repositoryUrl,
            String prompt
    ) {}

    /**
     * Response from analysis.
     */
    public record AnalysisResponse(
            boolean success,
            String summary,
            java.util.List<AnalysisOutput.EndpointInfo> endpoints,
            Long durationSeconds,
            String error
    ) {
        public static AnalysisResponse success(
                final String summary,
                final java.util.List<AnalysisOutput.EndpointInfo> endpoints,
                final long durationSeconds) {
            return new AnalysisResponse(true, summary, endpoints,
                    durationSeconds, null);
        }

        public static AnalysisResponse failure(final String error) {
            return new AnalysisResponse(false, null, null, null, error);
        }
    }

}
