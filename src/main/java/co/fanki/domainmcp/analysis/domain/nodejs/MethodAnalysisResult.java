package co.fanki.domainmcp.analysis.domain.nodejs;

import java.util.List;

/**
 * Carries method-level data extracted from Babel AST analysis.
 *
 * <p>Contains the method name, line number, HTTP mapping information
 * from framework decorators, and TypeScript parameter type names.</p>
 *
 * @param name the method or function name
 * @param lineNumber the 1-based line number of the declaration
 * @param httpMethod the HTTP method (GET, POST, etc.) or null
 * @param httpPath the HTTP path from the decorator or null
 * @param parameterTypes TypeScript type names of parameters
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public record MethodAnalysisResult(
        String name,
        int lineNumber,
        String httpMethod,
        String httpPath,
        List<String> parameterTypes
) {
}
