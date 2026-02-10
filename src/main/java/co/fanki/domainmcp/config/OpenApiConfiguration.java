package co.fanki.domainmcp.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI/Swagger configuration for the Domain MCP Server.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Configuration
public class OpenApiConfiguration {

    @Value("${server.port:8080}")
    private int serverPort;

    /**
     * Configures the OpenAPI specification.
     *
     * @return the OpenAPI configuration
     */
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Domain MCP Server API")
                        .description("""
                                Domain MCP Server - A Model Context Protocol server that analyzes
                                git repositories and extracts business information for AI assistants.

                                ## Features
                                - **Project Analysis**: Analyze git repositories to extract class/method information
                                - **Code Context**: Retrieve context for classes and methods
                                - **Stack Trace Correlation**: Correlate Datadog stack traces with source code

                                ## MCP Tools
                                - `analyze_project` - Analyze a git repository
                                - `get_class_context` - Get context for a class
                                - `get_method_context` - Get context for a method
                                - `get_stack_trace_context` - Get context for a stack trace
                                - `list_projects` - List all analyzed projects
                                """)
                        .version("0.0.1")
                        .contact(new Contact()
                                .name("Fanki")
                                .email("emiliano@fanki.co")
                                .url("https://fanki.co"))
                        .license(new License()
                                .name("Proprietary")
                                .url("https://fanki.co")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:" + serverPort)
                                .description("Local development server")));
    }

}
