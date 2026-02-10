package co.fanki.domainmcp.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Simple health check endpoint.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@RestController
public class HealthController {

    /**
     * Returns health status.
     *
     * @return "up" if the service is running
     */
    @GetMapping("/health")
    public String health() {
        return "up";
    }

}
