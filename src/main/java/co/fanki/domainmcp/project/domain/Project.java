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
    private String description;
    private ProjectStatus status;
    private Instant lastAnalyzedAt;
    private String lastCommitHash;
    private String graphData;
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
     *
     * @param id the project ID
     * @param name the project name
     * @param repositoryUrl the repository URL
     * @param defaultBranch the default branch
     * @param description the project description derived from README
     * @param status the project status
     * @param lastAnalyzedAt when last analyzed
     * @param lastCommitHash the last commit hash analyzed
     * @param graphData the serialized project graph JSON
     * @param createdAt when created
     * @param updatedAt when last updated
     * @return the reconstituted Project
     */
    public static Project reconstitute(
            final String id,
            final String name,
            final RepositoryUrl repositoryUrl,
            final String defaultBranch,
            final String description,
            final ProjectStatus status,
            final Instant lastAnalyzedAt,
            final String lastCommitHash,
            final String graphData,
            final Instant createdAt,
            final Instant updatedAt) {

        final Project project = new Project(id, name, repositoryUrl,
                defaultBranch, createdAt);
        project.description = description;
        project.status = status;
        project.lastAnalyzedAt = lastAnalyzedAt;
        project.lastCommitHash = lastCommitHash;
        project.graphData = graphData;
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
     * Marks the project as currently being synced (incremental update).
     *
     * @throws DomainException if the project cannot be synced
     */
    public void startSync() {
        if (!status.canSync()) {
            throw new DomainException(
                    "Cannot sync project in status: " + status,
                    "PROJECT_CANNOT_SYNC");
        }
        this.status = ProjectStatus.SYNCING;
        this.updatedAt = Instant.now();
    }

    /**
     * Marks the incremental sync as complete.
     *
     * @param commitHash the new HEAD commit hash
     */
    public void syncCompleted(final String commitHash) {
        Preconditions.requireNonBlank(commitHash, "Commit hash is required");
        this.status = ProjectStatus.ANALYZED;
        this.lastAnalyzedAt = Instant.now();
        this.lastCommitHash = commitHash;
        this.updatedAt = Instant.now();
    }

    /**
     * Updates the project description derived from README analysis.
     *
     * @param theDescription the project description, may be null
     */
    public void updateDescription(final String theDescription) {
        this.description = theDescription;
        this.updatedAt = Instant.now();
    }

    /**
     * Updates the serialized project dependency graph.
     *
     * @param theGraphData the JSON representation of the project graph
     */
    public void updateGraphData(final String theGraphData) {
        this.graphData = theGraphData;
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

    public String description() {
        return description;
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

    public String graphData() {
        return graphData;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

}
