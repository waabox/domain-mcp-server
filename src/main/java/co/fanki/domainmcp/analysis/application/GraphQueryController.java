package co.fanki.domainmcp.analysis.application;

import co.fanki.domainmcp.analysis.application.GraphQueryService.GraphQueryResult;
import co.fanki.domainmcp.shared.DomainException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller for querying the project graph.
 *
 * <p>Accepts a colon-separated query string and resolves it entirely
 * from the in-memory graph cache. No database access at query time.</p>
 *
 * <p>Query syntax: {@code project:category[:segment1[:segment2[...]]]}</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@RestController
@RequestMapping("/api/graph")
@Tag(name = "Graph Query",
        description = "Query the project dependency graph")
public class GraphQueryController {

    private static final Logger LOG = LoggerFactory.getLogger(
            GraphQueryController.class);

    private final GraphQueryService graphQueryService;

    /**
     * Creates a new GraphQueryController.
     *
     * @param theGraphQueryService the graph query service
     */
    public GraphQueryController(
            final GraphQueryService theGraphQueryService) {
        this.graphQueryService = theGraphQueryService;
    }

    /**
     * Executes a graph query.
     *
     * @param request the query request containing the query string
     * @return the query results
     */
    @PostMapping("/query")
    @Operation(summary = "Query the project graph",
            description = "Executes a colon-separated query against"
                    + " the in-memory project graph."
                    + " Examples: stadium-service:endpoints,"
                    + " stadium-service:class:UserService:methods")
    public ResponseEntity<?> query(
            @RequestBody final QueryRequest request) {

        LOG.info("Graph query: {}", request.query());

        try {
            final GraphQuery parsed =
                    GraphQueryParser.parse(request.query());

            final GraphQueryResult result =
                    graphQueryService.execute(parsed);

            return ResponseEntity.ok(result);
        } catch (final DomainException e) {
            LOG.warn("Graph query failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(
                    Map.of("error", e.getMessage(),
                            "errorCode", e.getErrorCode()));
        }
    }

    /**
     * Request body for graph query.
     *
     * @param query the colon-separated query string
     */
    public record QueryRequest(String query) {}
}
