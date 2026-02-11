package co.fanki.domainmcp.analysis.domain;

import co.fanki.domainmcp.shared.Preconditions;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a parameter of a source method that references a known
 * project class.
 *
 * <p>Links a {@link SourceMethod} to a {@link SourceClass} through its
 * parameter list, enriching the dependency graph with method-level
 * connectivity. Only parameters whose type matches a known project class
 * are captured; external types (String, int, etc.) are skipped.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class MethodParameter {

    private final String id;
    private final String methodId;
    private final int position;
    private final String classId;
    private final Instant createdAt;

    private MethodParameter(
            final String theId,
            final String theMethodId,
            final int thePosition,
            final String theClassId,
            final Instant theCreatedAt) {
        this.id = Preconditions.requireNonBlank(theId,
                "Parameter ID is required");
        this.methodId = Preconditions.requireNonBlank(theMethodId,
                "Method ID is required");
        this.position = Preconditions.requireNonNegative(thePosition,
                "Position must be non-negative");
        this.classId = Preconditions.requireNonBlank(theClassId,
                "Class ID is required");
        this.createdAt = theCreatedAt != null ? theCreatedAt : Instant.now();
    }

    /**
     * Creates a new method parameter linking a method to a known class.
     *
     * @param methodId the ID of the method this parameter belongs to
     * @param position the zero-based position in the parameter list
     * @param classId the ID of the source class that is the parameter type
     * @return a new MethodParameter instance
     */
    public static MethodParameter create(
            final String methodId,
            final int position,
            final String classId) {
        return new MethodParameter(
                UUID.randomUUID().toString(),
                methodId,
                position,
                classId,
                Instant.now());
    }

    /**
     * Reconstitutes a method parameter from persistence.
     *
     * @param id the parameter ID
     * @param methodId the method ID
     * @param position the parameter position
     * @param classId the class ID
     * @param createdAt when this record was created
     * @return the reconstituted MethodParameter
     */
    public static MethodParameter reconstitute(
            final String id,
            final String methodId,
            final int position,
            final String classId,
            final Instant createdAt) {
        return new MethodParameter(id, methodId, position, classId, createdAt);
    }

    public String id() {
        return id;
    }

    public String methodId() {
        return methodId;
    }

    public int position() {
        return position;
    }

    public String classId() {
        return classId;
    }

    public Instant createdAt() {
        return createdAt;
    }

}
