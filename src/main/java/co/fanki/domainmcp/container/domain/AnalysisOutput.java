package co.fanki.domainmcp.container.domain;

import co.fanki.domainmcp.shared.ValueObject;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Value object representing the result of a Claude Code analysis.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class AnalysisOutput implements ValueObject {

    private static final long serialVersionUID = 1L;

    private final boolean success;
    private final String rawOutput;
    private final String summary;
    private final List<EndpointInfo> endpoints;
    private final List<ClassInfo> classes;
    private final String errorMessage;
    private final Instant startedAt;
    private final Instant completedAt;

    private AnalysisOutput(
            final boolean theSuccess,
            final String theRawOutput,
            final String theSummary,
            final List<EndpointInfo> theEndpoints,
            final List<ClassInfo> theClasses,
            final String theErrorMessage,
            final Instant theStartedAt,
            final Instant theCompletedAt) {
        this.success = theSuccess;
        this.rawOutput = theRawOutput;
        this.summary = theSummary;
        this.endpoints = theEndpoints != null ? List.copyOf(theEndpoints) : List.of();
        this.classes = theClasses != null ? List.copyOf(theClasses) : List.of();
        this.errorMessage = theErrorMessage;
        this.startedAt = theStartedAt;
        this.completedAt = theCompletedAt;
    }

    /**
     * Creates a successful analysis output.
     *
     * @param rawOutput the raw Claude Code output
     * @param summary the analysis summary
     * @param endpoints the extracted endpoints
     * @param startedAt when analysis started
     * @param completedAt when analysis completed
     * @return successful output
     */
    public static AnalysisOutput success(
            final String rawOutput,
            final String summary,
            final List<EndpointInfo> endpoints,
            final Instant startedAt,
            final Instant completedAt) {
        return new AnalysisOutput(true, rawOutput, summary, endpoints, List.of(),
                null, startedAt, completedAt);
    }

    /**
     * Creates a successful analysis output with class information.
     *
     * @param rawOutput the raw Claude Code output
     * @param summary the analysis summary
     * @param endpoints the extracted endpoints
     * @param classes the extracted class information
     * @param startedAt when analysis started
     * @param completedAt when analysis completed
     * @return successful output
     */
    public static AnalysisOutput successWithClasses(
            final String rawOutput,
            final String summary,
            final List<EndpointInfo> endpoints,
            final List<ClassInfo> classes,
            final Instant startedAt,
            final Instant completedAt) {
        return new AnalysisOutput(true, rawOutput, summary, endpoints, classes,
                null, startedAt, completedAt);
    }

    /**
     * Creates a failed analysis output.
     *
     * @param errorMessage the error message
     * @param startedAt when analysis started
     * @param completedAt when it failed
     * @return failed output
     */
    public static AnalysisOutput failure(
            final String errorMessage,
            final Instant startedAt,
            final Instant completedAt) {
        return new AnalysisOutput(false, null, null, null, null, errorMessage,
                startedAt, completedAt);
    }

    public boolean isSuccess() {
        return success;
    }

    public String rawOutput() {
        return rawOutput;
    }

    public String summary() {
        return summary;
    }

    public List<EndpointInfo> endpoints() {
        return endpoints;
    }

    public List<ClassInfo> classes() {
        return classes;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public Duration duration() {
        return Duration.between(startedAt, completedAt);
    }

    /**
     * Information about an API endpoint.
     */
    public record EndpointInfo(
            String httpMethod,
            String path,
            String description,
            String handlerMethod,
            String handlerClass,
            String requestBody,
            String responseBody,
            List<String> businessLogicSummary
    ) implements ValueObject {
        private static final long serialVersionUID = 1L;
    }

    /**
     * Information about a class in the codebase.
     */
    public record ClassInfo(
            String fullClassName,
            String classType,
            String description,
            String sourceFile,
            List<MethodInfo> methods
    ) implements ValueObject {
        private static final long serialVersionUID = 1L;

        /**
         * Extracts the simple class name from the full class name.
         *
         * @return the simple class name
         */
        public String simpleName() {
            if (fullClassName == null) {
                return null;
            }
            final int lastDot = fullClassName.lastIndexOf('.');
            return lastDot < 0 ? fullClassName : fullClassName.substring(lastDot + 1);
        }

        /**
         * Extracts the package name from the full class name.
         *
         * @return the package name, or null if none
         */
        public String packageName() {
            if (fullClassName == null) {
                return null;
            }
            final int lastDot = fullClassName.lastIndexOf('.');
            return lastDot < 0 ? null : fullClassName.substring(0, lastDot);
        }
    }

    /**
     * Information about a method in a class.
     */
    public record MethodInfo(
            String methodName,
            String description,
            List<String> businessLogic,
            List<String> dependencies,
            List<String> exceptions,
            String httpMethod,
            String httpPath,
            Integer lineNumber
    ) implements ValueObject {
        private static final long serialVersionUID = 1L;

        /**
         * Checks if this method is an HTTP endpoint.
         *
         * @return true if HTTP method and path are set
         */
        public boolean isHttpEndpoint() {
            return httpMethod != null && httpPath != null;
        }

        /**
         * Returns the full HTTP endpoint string.
         *
         * @return the HTTP method and path combined, or null if not an endpoint
         */
        public String httpEndpoint() {
            if (!isHttpEndpoint()) {
                return null;
            }
            return httpMethod + " " + httpPath;
        }
    }

}
