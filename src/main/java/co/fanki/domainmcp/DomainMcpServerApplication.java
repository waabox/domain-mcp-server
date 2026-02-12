package co.fanki.domainmcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Domain MCP Server Application.
 *
 * <p>This is the main entry point for the Domain MCP Server, which provides
 * Model Context Protocol (MCP) tools for analyzing git repositories and
 * extracting business information such as rules, API contracts, and database
 * schemas.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@SpringBootApplication
@EnableScheduling
public class DomainMcpServerApplication {

    /**
     * Main entry point for the application.
     *
     * @param args command line arguments
     */
    public static void main(final String[] args) {
        SpringApplication.run(DomainMcpServerApplication.class, args);
    }

}
