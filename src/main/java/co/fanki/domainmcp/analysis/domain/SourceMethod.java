package co.fanki.domainmcp.analysis.domain;

import co.fanki.domainmcp.shared.Preconditions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Represents a method extracted from a source class during analysis.
 *
 * <p>Contains detailed information about the method including its
 * business logic, exceptions, and HTTP endpoint mapping if applicable.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class SourceMethod {

    private final String id;
    private final String classId;
    private final String methodName;
    private final String description;
    private final List<String> businessLogic;
    private final List<String> exceptions;
    private final String httpMethod;
    private final String httpPath;
    private final Integer lineNumber;
    private final Instant createdAt;

    private SourceMethod(
            final String theId,
            final String theClassId,
            final String theMethodName,
            final String theDescription,
            final List<String> theBusinessLogic,
            final List<String> theExceptions,
            final String theHttpMethod,
            final String theHttpPath,
            final Integer theLineNumber,
            final Instant theCreatedAt) {
        this.id = Preconditions.requireNonBlank(theId, "Method ID is required");
        this.classId = Preconditions.requireNonBlank(theClassId,
                "Class ID is required");
        this.methodName = Preconditions.requireNonBlank(theMethodName,
                "Method name is required");
        this.description = theDescription;
        this.businessLogic = theBusinessLogic != null
                ? Collections.unmodifiableList(new ArrayList<>(theBusinessLogic))
                : Collections.emptyList();
        this.exceptions = theExceptions != null
                ? Collections.unmodifiableList(new ArrayList<>(theExceptions))
                : Collections.emptyList();
        this.httpMethod = theHttpMethod;
        this.httpPath = theHttpPath;
        this.lineNumber = theLineNumber;
        this.createdAt = theCreatedAt != null ? theCreatedAt : Instant.now();
    }

    /**
     * Creates a new source method.
     *
     * @param classId the ID of the class this method belongs to
     * @param methodName the method name
     * @param description a description of what this method does
     * @param businessLogic list of business logic steps
     * @param exceptions list of exceptions this method may throw
     * @param httpMethod the HTTP method if this is a REST handler
     * @param httpPath the HTTP path if this is a REST handler
     * @param lineNumber the line number in the source file
     * @return a new SourceMethod instance
     */
    public static SourceMethod create(
            final String classId,
            final String methodName,
            final String description,
            final List<String> businessLogic,
            final List<String> exceptions,
            final String httpMethod,
            final String httpPath,
            final Integer lineNumber) {
        return new SourceMethod(
                UUID.randomUUID().toString(),
                classId,
                methodName,
                description,
                businessLogic,
                exceptions,
                httpMethod,
                httpPath,
                lineNumber,
                Instant.now());
    }

    /**
     * Reconstitutes a source method from persistence.
     *
     * @param id the method ID
     * @param classId the class ID
     * @param methodName the method name
     * @param description the description
     * @param businessLogic the business logic steps
     * @param exceptions the exceptions
     * @param httpMethod the HTTP method
     * @param httpPath the HTTP path
     * @param lineNumber the line number
     * @param createdAt when this record was created
     * @return the reconstituted SourceMethod
     */
    public static SourceMethod reconstitute(
            final String id,
            final String classId,
            final String methodName,
            final String description,
            final List<String> businessLogic,
            final List<String> exceptions,
            final String httpMethod,
            final String httpPath,
            final Integer lineNumber,
            final Instant createdAt) {
        return new SourceMethod(
                id, classId, methodName, description, businessLogic,
                exceptions, httpMethod, httpPath,
                lineNumber, createdAt);
    }

    /**
     * Checks if this method is a REST endpoint handler.
     *
     * @return true if this method has HTTP mapping
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

    public String id() {
        return id;
    }

    public String classId() {
        return classId;
    }

    public String methodName() {
        return methodName;
    }

    public String description() {
        return description;
    }

    public List<String> businessLogic() {
        return businessLogic;
    }

    public List<String> exceptions() {
        return exceptions;
    }

    public String httpMethod() {
        return httpMethod;
    }

    public String httpPath() {
        return httpPath;
    }

    public Integer lineNumber() {
        return lineNumber;
    }

    public Instant createdAt() {
        return createdAt;
    }

}
