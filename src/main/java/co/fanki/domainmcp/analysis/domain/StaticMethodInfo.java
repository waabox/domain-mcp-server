package co.fanki.domainmcp.analysis.domain;

import java.util.List;

/**
 * Carries method-level data extracted statically from source code,
 * without requiring any LLM analysis.
 *
 * <p>Used as a transport object between the {@link SourceParser} and
 * the application service during Phase 1 (static parse and persist).
 * Contains everything that can be determined from source code alone:
 * the method name, its line number, HTTP endpoint mapping (if any),
 * and declared exceptions.</p>
 *
 * @param methodName the method or function name
 * @param lineNumber the 1-based line number of the method declaration
 * @param httpMethod the HTTP method (GET, POST, etc.) or null
 * @param httpPath the HTTP path from the annotation or null
 * @param exceptions exception types from the throws clause, may be empty
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public record StaticMethodInfo(
        String methodName,
        int lineNumber,
        String httpMethod,
        String httpPath,
        List<String> exceptions
) {

    /**
     * Creates a StaticMethodInfo with no HTTP mapping and no exceptions.
     *
     * @param methodName the method name
     * @param lineNumber the line number
     * @return the static method info
     */
    public static StaticMethodInfo simple(final String methodName,
            final int lineNumber) {
        return new StaticMethodInfo(methodName, lineNumber, null, null,
                List.of());
    }

}
