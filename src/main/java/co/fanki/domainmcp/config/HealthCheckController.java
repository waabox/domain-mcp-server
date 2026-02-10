package co.fanki.domainmcp.config;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

/**
 * Health check controller providing endpoints for liveness and readiness probes.
 *
 * <p>Provides /health for basic liveness check and /ready for readiness
 * check that verifies database connectivity.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@RestController
public class HealthCheckController {

    private final DataSource dataSource;

    /**
     * Creates a new HealthCheckController.
     *
     * @param theDataSource the data source for database connectivity checks
     */
    public HealthCheckController(final DataSource theDataSource) {
        this.dataSource = theDataSource;
    }

    /**
     * Liveness probe endpoint.
     *
     * <p>Returns a simple "ok" response to indicate the application is running.</p>
     *
     * @return "ok" string
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("ok");
    }

    /**
     * Readiness probe endpoint.
     *
     * <p>Verifies the application is ready to serve requests by checking
     * database connectivity.</p>
     *
     * @return status map with component health information
     */
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        final boolean databaseHealthy = checkDatabaseHealth();

        final Map<String, Object> status = Map.of(
            "status", databaseHealthy ? "ready" : "not_ready",
            "database", databaseHealthy ? "connected" : "disconnected"
        );

        if (databaseHealthy) {
            return ResponseEntity.ok(status);
        }
        return ResponseEntity.status(503).body(status);
    }

    private boolean checkDatabaseHealth() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(5);
        } catch (Exception e) {
            return false;
        }
    }

}
