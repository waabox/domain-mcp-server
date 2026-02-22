package co.fanki.domainmcp.project.domain;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for persisting and retrieving projects.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Repository
public class ProjectRepository {

    /** Find project by ID. Uses: PK index. */
    public static final String FIND_BY_ID =
            "SELECT * FROM projects WHERE id = :id";

    /** Find project by repository URL. Uses: UNIQUE constraint on repository_url. */
    public static final String FIND_BY_REPOSITORY_URL =
            "SELECT * FROM projects WHERE repository_url = :url";

    /** Find project by name. Uses: seq scan (names are unique by convention). */
    public static final String FIND_BY_NAME =
            "SELECT * FROM projects WHERE name = :name";

    /** Find all projects. Uses: seq scan (expected for small table). */
    public static final String FIND_ALL =
            "SELECT * FROM projects ORDER BY created_at DESC";

    /** Find projects by status. Uses: idx_projects_status. */
    public static final String FIND_BY_STATUS =
            "SELECT * FROM projects WHERE status = :status";

    /** Find projects with non-null graph data. Uses: seq scan. */
    public static final String FIND_ALL_WITH_GRAPH =
            "SELECT * FROM projects WHERE graph_data IS NOT NULL";

    /** Check if project exists by repository URL. Uses: UNIQUE constraint on repository_url. */
    public static final String EXISTS_BY_REPOSITORY_URL =
            "SELECT COUNT(*) FROM projects WHERE repository_url = :url";

    private final Jdbi jdbi;

    /**
     * Creates a new ProjectRepository.
     *
     * @param theJdbi the JDBI instance
     */
    public ProjectRepository(final Jdbi theJdbi) {
        this.jdbi = theJdbi;
        this.jdbi.registerRowMapper(new ProjectRowMapper());
    }

    /**
     * Saves a new project.
     *
     * @param project the project to save
     */
    public void save(final Project project) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                INSERT INTO projects (
                    id, name, repository_url, default_branch, description,
                    status, last_analyzed_at, last_commit_hash, graph_data,
                    created_at, updated_at
                ) VALUES (
                    :id, :name, :repositoryUrl, :defaultBranch, :description,
                    :status, :lastAnalyzedAt, :lastCommitHash,
                    CAST(:graphData AS JSONB), :createdAt, :updatedAt
                )
                """)
                .bind("id", project.id())
                .bind("name", project.name())
                .bind("repositoryUrl", project.repositoryUrl().value())
                .bind("defaultBranch", project.defaultBranch())
                .bind("description", project.description())
                .bind("status", project.status().name())
                .bind("lastAnalyzedAt", toTimestamp(project.lastAnalyzedAt()))
                .bind("lastCommitHash", project.lastCommitHash())
                .bind("graphData", project.graphData())
                .bind("createdAt", toTimestamp(project.createdAt()))
                .bind("updatedAt", toTimestamp(project.updatedAt()))
                .execute());
    }

    /**
     * Updates an existing project.
     *
     * @param project the project to update
     */
    public void update(final Project project) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                UPDATE projects SET
                    name = :name,
                    default_branch = :defaultBranch,
                    description = :description,
                    status = :status,
                    last_analyzed_at = :lastAnalyzedAt,
                    last_commit_hash = :lastCommitHash,
                    graph_data = CAST(:graphData AS JSONB),
                    updated_at = :updatedAt
                WHERE id = :id
                """)
                .bind("id", project.id())
                .bind("name", project.name())
                .bind("defaultBranch", project.defaultBranch())
                .bind("description", project.description())
                .bind("status", project.status().name())
                .bind("lastAnalyzedAt", toTimestamp(project.lastAnalyzedAt()))
                .bind("lastCommitHash", project.lastCommitHash())
                .bind("graphData", project.graphData())
                .bind("updatedAt", toTimestamp(project.updatedAt()))
                .execute());
    }

    /**
     * Finds a project by its ID.
     *
     * @param id the project ID
     * @return the project if found
     */
    public Optional<Project> findById(final String id) {
        return jdbi.withHandle(handle -> handle
                .createQuery(FIND_BY_ID)
                .bind("id", id)
                .map(new ProjectRowMapper())
                .findOne());
    }

    /**
     * Finds a project by its name.
     *
     * @param name the project name
     * @return the project if found
     */
    public Optional<Project> findByName(final String name) {
        return jdbi.withHandle(handle -> handle
                .createQuery(FIND_BY_NAME)
                .bind("name", name)
                .map(new ProjectRowMapper())
                .findOne());
    }

    /**
     * Finds a project by its repository URL.
     *
     * @param url the repository URL
     * @return the project if found
     */
    public Optional<Project> findByRepositoryUrl(final RepositoryUrl url) {
        return jdbi.withHandle(handle -> handle
                .createQuery(FIND_BY_REPOSITORY_URL)
                .bind("url", url.value())
                .map(new ProjectRowMapper())
                .findOne());
    }

    /**
     * Finds all projects.
     *
     * @return list of all projects
     */
    public List<Project> findAll() {
        return jdbi.withHandle(handle -> handle
                .createQuery(FIND_ALL)
                .map(new ProjectRowMapper())
                .list());
    }

    /**
     * Finds projects by status.
     *
     * @param status the status to filter by
     * @return list of projects with the given status
     */
    public List<Project> findByStatus(final ProjectStatus status) {
        return jdbi.withHandle(handle -> handle
                .createQuery(FIND_BY_STATUS)
                .bind("status", status.name())
                .map(new ProjectRowMapper())
                .list());
    }

    /**
     * Finds projects matching any of the given statuses.
     *
     * @param statuses the list of statuses to include
     * @return list of projects whose status is in the given list
     */
    public List<Project> findByStatuses(final List<ProjectStatus> statuses) {
        final List<String> statusNames = statuses.stream()
                .map(ProjectStatus::name)
                .toList();
        return jdbi.withHandle(handle -> handle
                .createQuery(
                        "SELECT * FROM projects WHERE status = ANY(:statuses)")
                .bindArray("statuses", String.class,
                        statusNames.toArray(new String[0]))
                .map(new ProjectRowMapper())
                .list());
    }

    /**
     * Finds all projects with non-null graph data.
     *
     * @return list of projects that have graph data
     */
    public List<Project> findAllWithGraph() {
        return jdbi.withHandle(handle -> handle
                .createQuery(FIND_ALL_WITH_GRAPH)
                .map(new ProjectRowMapper())
                .list());
    }

    /**
     * Deletes a project.
     *
     * @param id the project ID
     */
    public void delete(final String id) {
        jdbi.useHandle(handle -> handle
                .createUpdate("DELETE FROM projects WHERE id = :id")
                .bind("id", id)
                .execute());
    }

    /**
     * Checks if a project exists with the given repository URL.
     *
     * @param url the repository URL
     * @return true if a project exists
     */
    public boolean existsByRepositoryUrl(final RepositoryUrl url) {
        return jdbi.withHandle(handle -> handle
                .createQuery(EXISTS_BY_REPOSITORY_URL)
                .bind("url", url.value())
                .mapTo(Long.class)
                .one() > 0);
    }

    private Timestamp toTimestamp(final Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }

    private static final class ProjectRowMapper implements RowMapper<Project> {

        @Override
        public Project map(final ResultSet rs, final StatementContext ctx)
                throws SQLException {
            final String id = rs.getString("id");
            final String name = rs.getString("name");
            final RepositoryUrl repositoryUrl = RepositoryUrl.of(
                    rs.getString("repository_url"));
            final String defaultBranch = rs.getString("default_branch");
            final String description = rs.getString("description");
            final ProjectStatus status = ProjectStatus.valueOf(
                    rs.getString("status"));

            final Timestamp lastAnalyzedTs = rs.getTimestamp("last_analyzed_at");
            final Instant lastAnalyzedAt = lastAnalyzedTs != null
                    ? lastAnalyzedTs.toInstant() : null;

            final String lastCommitHash = rs.getString("last_commit_hash");
            final String graphData = rs.getString("graph_data");
            final Instant createdAt = rs.getTimestamp("created_at").toInstant();
            final Instant updatedAt = rs.getTimestamp("updated_at").toInstant();

            return Project.reconstitute(
                    id, name, repositoryUrl, defaultBranch, description,
                    status, lastAnalyzedAt, lastCommitHash, graphData,
                    createdAt, updatedAt);
        }
    }

}
