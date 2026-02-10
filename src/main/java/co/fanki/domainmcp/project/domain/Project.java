package co.fanki.domainmcp.project.domain;

import co.fanki.domainmcp.shared.DomainException;
import co.fanki.domainmcp.shared.Preconditions;

import java.time.Instant;
import java.util.UUID;

/**
 * Aggregate root representing a project (git repository) being analyzed.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class Project {

    private final String id;
    private String name;
    private final RepositoryUrl repositoryUrl;
    private String defaultBranch;
    private ProjectStatus status;
    private Instant lastAnalyzedAt;
    private String lastCommitHash;
    private final Instant createdAt;
    private Instant updatedAt;

    private Project(
            final String theId,
            final String theName,
            final RepositoryUrl theRepositoryUrl,
            final String theDefaultBranch,
            final Instant theCreatedAt) {
        this.id = Preconditions.requireNonBlank(theId, "Project ID is required");
        this.name = Preconditions.requireNonBlank(theName, "Project name is required");
        this.repositoryUrl = Preconditions.requireNonNull(theRepositoryUrl,
                "Repository URL is required");
        this.defaultBranch = theDefaultBranch != null ? theDefaultBranch : "main";
        this.status = ProjectStatus.PENDING;
        this.createdAt = theCreatedAt != null ? theCreatedAt : Instant.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Creates a new project for a repository.
     *
     * @param name the project name
     * @param repositoryUrl the git repository URL
     * @return a new Project instance
     */
    public static Project create(final String name,
            final RepositoryUrl repositoryUrl) {
        return new Project(
                UUID.randomUUID().toString(),
                name,
                repositoryUrl,
                "main",
                Instant.now());
    }

    /**
     * Creates a new project with a specified default branch.
     *
     * @param name the project name
     * @param repositoryUrl the git repository URL
     * @param defaultBranch the default branch name
     * @return a new Project instance
     */
    public static Project create(final String name,
            final RepositoryUrl repositoryUrl,
            final String defaultBranch) {
        return new Project(
                UUID.randomUUID().toString(),
                name,
                repositoryUrl,
                defaultBranch,
                Instant.now());
    }

    /**
     * Reconstitutes a project from persistence.
     */
    public static Project reconstitute(
            final String id,
            final String name,
            final RepositoryUrl repositoryUrl,
            final String defaultBranch,
            final ProjectStatus status,
            final Instant lastAnalyzedAt,
            final String lastCommitHash,
            final Instant createdAt,
            final Instant updatedAt) {

        final Project project = new Project(id, name, repositoryUrl,
                defaultBranch, createdAt);
        project.status = status;
        project.lastAnalyzedAt = lastAnalyzedAt;
        project.lastCommitHash = lastCommitHash;
        project.updatedAt = updatedAt;
        return project;
    }

    /**
     * Marks the project as currently being analyzed.
     *
     * @throws DomainException if the project cannot be analyzed
     */
    public void startAnalysis() {
        if (!status.canAnalyze()) {
            throw new DomainException(
                    "Cannot analyze project in status: " + status,
                    "PROJECT_CANNOT_ANALYZE");
        }
        this.status = ProjectStatus.ANALYZING;
        this.updatedAt = Instant.now();
    }

    /**
     * Marks the analysis as complete.
     *
     * @param commitHash the commit hash that was analyzed
     */
    public void analysisCompleted(final String commitHash) {
        Preconditions.requireNonBlank(commitHash, "Commit hash is required");
        this.status = ProjectStatus.ANALYZED;
        this.lastAnalyzedAt = Instant.now();
        this.lastCommitHash = commitHash;
        this.updatedAt = Instant.now();
    }

    /**
     * Marks the project as having an error.
     */
    public void markError() {
        this.status = ProjectStatus.ERROR;
        this.updatedAt = Instant.now();
    }

    /**
     * Updates the project name.
     *
     * @param newName the new name
     */
    public void rename(final String newName) {
        this.name = Preconditions.requireNonBlank(newName,
                "Project name is required");
        this.updatedAt = Instant.now();
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public RepositoryUrl repositoryUrl() {
        return repositoryUrl;
    }

    public String defaultBranch() {
        return defaultBranch;
    }

    public ProjectStatus status() {
        return status;
    }

    public Instant lastAnalyzedAt() {
        return lastAnalyzedAt;
    }

    public String lastCommitHash() {
        return lastCommitHash;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

}
