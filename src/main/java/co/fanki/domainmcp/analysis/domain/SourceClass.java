package co.fanki.domainmcp.analysis.domain;

import co.fanki.domainmcp.shared.Preconditions;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a source class extracted from a project during analysis.
 *
 * <p>Contains metadata about a class including its type, description,
 * and location in the source tree. Used for correlating stack traces
 * with source code context.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class SourceClass {

    private final String id;
    private final String projectId;
    private final String fullClassName;
    private final String simpleName;
    private final String packageName;
    private final ClassType classType;
    private final String description;
    private final String sourceFile;
    private final Instant createdAt;

    private SourceClass(
            final String theId,
            final String theProjectId,
            final String theFullClassName,
            final String theSimpleName,
            final String thePackageName,
            final ClassType theClassType,
            final String theDescription,
            final String theSourceFile,
            final Instant theCreatedAt) {
        this.id = Preconditions.requireNonBlank(theId, "Class ID is required");
        this.projectId = Preconditions.requireNonBlank(theProjectId,
                "Project ID is required");
        this.fullClassName = Preconditions.requireNonBlank(theFullClassName,
                "Full class name is required");
        this.simpleName = Preconditions.requireNonBlank(theSimpleName,
                "Simple name is required");
        this.packageName = thePackageName;
        this.classType = Preconditions.requireNonNull(theClassType,
                "Class type is required");
        this.description = theDescription;
        this.sourceFile = theSourceFile;
        this.createdAt = theCreatedAt != null ? theCreatedAt : Instant.now();
    }

    /**
     * Creates a new source class for the given project.
     *
     * @param projectId the project this class belongs to
     * @param fullClassName the fully qualified class name
     * @param classType the type of this class
     * @param description a description of what this class does
     * @param sourceFile the path to the source file
     * @return a new SourceClass instance
     */
    public static SourceClass create(
            final String projectId,
            final String fullClassName,
            final ClassType classType,
            final String description,
            final String sourceFile) {
        final String simpleName = extractSimpleName(fullClassName);
        final String packageName = extractPackageName(fullClassName);

        return new SourceClass(
                UUID.randomUUID().toString(),
                projectId,
                fullClassName,
                simpleName,
                packageName,
                classType,
                description,
                sourceFile,
                Instant.now());
    }

    /**
     * Reconstitutes a source class from persistence.
     *
     * @param id the class ID
     * @param projectId the project ID
     * @param fullClassName the fully qualified class name
     * @param simpleName the simple class name
     * @param packageName the package name
     * @param classType the class type
     * @param description the description
     * @param sourceFile the source file path
     * @param createdAt when this record was created
     * @return the reconstituted SourceClass
     */
    public static SourceClass reconstitute(
            final String id,
            final String projectId,
            final String fullClassName,
            final String simpleName,
            final String packageName,
            final ClassType classType,
            final String description,
            final String sourceFile,
            final Instant createdAt) {
        return new SourceClass(
                id, projectId, fullClassName, simpleName, packageName,
                classType, description, sourceFile, createdAt);
    }

    /**
     * Extracts the simple class name from a fully qualified name.
     *
     * @param fullClassName the fully qualified class name
     * @return the simple class name
     */
    private static String extractSimpleName(final String fullClassName) {
        final int lastDotIndex = fullClassName.lastIndexOf('.');
        if (lastDotIndex < 0) {
            return fullClassName;
        }
        return fullClassName.substring(lastDotIndex + 1);
    }

    /**
     * Extracts the package name from a fully qualified class name.
     *
     * @param fullClassName the fully qualified class name
     * @return the package name, or null if no package
     */
    private static String extractPackageName(final String fullClassName) {
        final int lastDotIndex = fullClassName.lastIndexOf('.');
        if (lastDotIndex < 0) {
            return null;
        }
        return fullClassName.substring(0, lastDotIndex);
    }

    /**
     * Checks if this class belongs to the given package.
     *
     * @param pkg the package to check
     * @return true if this class is in the specified package or a subpackage
     */
    public boolean belongsToPackage(final String pkg) {
        if (packageName == null || pkg == null) {
            return false;
        }
        return packageName.equals(pkg) || packageName.startsWith(pkg + ".");
    }

    public String id() {
        return id;
    }

    public String projectId() {
        return projectId;
    }

    public String fullClassName() {
        return fullClassName;
    }

    public String simpleName() {
        return simpleName;
    }

    public String packageName() {
        return packageName;
    }

    public ClassType classType() {
        return classType;
    }

    public String description() {
        return description;
    }

    public String sourceFile() {
        return sourceFile;
    }

    public Instant createdAt() {
        return createdAt;
    }

}
