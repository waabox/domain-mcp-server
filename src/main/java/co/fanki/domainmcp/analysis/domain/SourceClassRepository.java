package co.fanki.domainmcp.analysis.domain;

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
 * Repository for persisting and retrieving source classes.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Repository
public class SourceClassRepository {

    /** Find source class by ID. Uses: PK index. */
    public static final String FIND_BY_ID =
            "SELECT * FROM source_classes WHERE id = :id";

    /** Find source classes by project ID. Uses: idx_source_classes_project. */
    public static final String FIND_BY_PROJECT_ID = """
            SELECT * FROM source_classes
            WHERE project_id = :projectId
            ORDER BY full_class_name
            """;

    /** Find source class by full class name. Uses: idx_source_classes_full_name. */
    public static final String FIND_BY_FULL_CLASS_NAME = """
            SELECT * FROM source_classes
            WHERE full_class_name = :fullClassName
            """;

    /** Find source classes by package prefix. Uses: idx_source_classes_package. */
    public static final String FIND_BY_PACKAGE_PREFIX = """
            SELECT * FROM source_classes
            WHERE package_name = :packagePrefix
               OR package_name LIKE :packagePattern
            ORDER BY full_class_name
            """;

    /** Count source classes by project ID. Uses: idx_source_classes_project. */
    public static final String COUNT_BY_PROJECT_ID = """
            SELECT COUNT(*) FROM source_classes
            WHERE project_id = :projectId
            """;

    /** Find source class by project ID and full class name. */
    public static final String FIND_BY_PROJECT_ID_AND_FULL_CLASS_NAME = """
            SELECT * FROM source_classes
            WHERE project_id = :projectId
              AND full_class_name = :fullClassName
            """;

    private final Jdbi jdbi;

    /**
     * Creates a new SourceClassRepository.
     *
     * @param theJdbi the JDBI instance
     */
    public SourceClassRepository(final Jdbi theJdbi) {
        this.jdbi = theJdbi;
        this.jdbi.registerRowMapper(new SourceClassRowMapper());
    }

    /**
     * Saves a new source class.
     *
     * @param sourceClass the source class to save
     */
    public void save(final SourceClass sourceClass) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                INSERT INTO source_classes (
                    id, project_id, full_class_name, simple_name, package_name,
                    class_type, description, source_file, commit_hash, created_at
                ) VALUES (
                    :id, :projectId, :fullClassName, :simpleName, :packageName,
                    :classType, :description, :sourceFile, :commitHash, :createdAt
                )
                """)
                .bind("id", sourceClass.id())
                .bind("projectId", sourceClass.projectId())
                .bind("fullClassName", sourceClass.fullClassName())
                .bind("simpleName", sourceClass.simpleName())
                .bind("packageName", sourceClass.packageName())
                .bind("classType", sourceClass.classType().name())
                .bind("description", sourceClass.description())
                .bind("sourceFile", sourceClass.sourceFile())
                .bind("commitHash", sourceClass.commitHash())
                .bind("createdAt", toTimestamp(sourceClass.createdAt()))
                .execute());
    }

    /**
     * Saves multiple source classes in a batch.
     *
     * @param sourceClasses the source classes to save
     */
    public void saveAll(final List<SourceClass> sourceClasses) {
        if (sourceClasses.isEmpty()) {
            return;
        }
        jdbi.useHandle(handle -> {
            final var batch = handle.prepareBatch("""
                    INSERT INTO source_classes (
                        id, project_id, full_class_name, simple_name, package_name,
                        class_type, description, source_file, commit_hash, created_at
                    ) VALUES (
                        :id, :projectId, :fullClassName, :simpleName, :packageName,
                        :classType, :description, :sourceFile, :commitHash, :createdAt
                    )
                    """);
            for (final SourceClass sc : sourceClasses) {
                batch.bind("id", sc.id())
                        .bind("projectId", sc.projectId())
                        .bind("fullClassName", sc.fullClassName())
                        .bind("simpleName", sc.simpleName())
                        .bind("packageName", sc.packageName())
                        .bind("classType", sc.classType().name())
                        .bind("description", sc.description())
                        .bind("sourceFile", sc.sourceFile())
                        .bind("commitHash", sc.commitHash())
                        .bind("createdAt", toTimestamp(sc.createdAt()))
                        .add();
            }
            batch.execute();
        });
    }

    /**
     * Finds a source class by its ID.
     *
     * @param id the class ID
     * @return the source class if found
     */
    public Optional<SourceClass> findById(final String id) {
        return jdbi.withHandle(handle -> handle
                .createQuery(FIND_BY_ID)
                .bind("id", id)
                .map(new SourceClassRowMapper())
                .findOne());
    }

    /**
     * Finds all source classes for a project.
     *
     * @param projectId the project ID
     * @return list of source classes
     */
    public List<SourceClass> findByProjectId(final String projectId) {
        return jdbi.withHandle(handle -> handle
                .createQuery(FIND_BY_PROJECT_ID)
                .bind("projectId", projectId)
                .map(new SourceClassRowMapper())
                .list());
    }

    /**
     * Finds a source class by its full class name.
     *
     * @param fullClassName the fully qualified class name
     * @return the source class if found
     */
    public Optional<SourceClass> findByFullClassName(final String fullClassName) {
        return jdbi.withHandle(handle -> handle
                .createQuery(FIND_BY_FULL_CLASS_NAME)
                .bind("fullClassName", fullClassName)
                .map(new SourceClassRowMapper())
                .findOne());
    }

    /**
     * Finds source classes by package name prefix.
     *
     * @param packagePrefix the package name prefix
     * @return list of source classes in matching packages
     */
    public List<SourceClass> findByPackagePrefix(final String packagePrefix) {
        return jdbi.withHandle(handle -> handle
                .createQuery(FIND_BY_PACKAGE_PREFIX)
                .bind("packagePrefix", packagePrefix)
                .bind("packagePattern", packagePrefix + ".%")
                .map(new SourceClassRowMapper())
                .list());
    }

    /**
     * Finds a source class by project ID and full class name.
     *
     * @param projectId the project ID
     * @param fullClassName the fully qualified class name
     * @return the source class if found
     */
    public Optional<SourceClass> findByProjectIdAndFullClassName(
            final String projectId, final String fullClassName) {
        return jdbi.withHandle(handle -> handle
                .createQuery(FIND_BY_PROJECT_ID_AND_FULL_CLASS_NAME)
                .bind("projectId", projectId)
                .bind("fullClassName", fullClassName)
                .map(new SourceClassRowMapper())
                .findOne());
    }

    /**
     * Counts the number of source classes for a project.
     *
     * @param projectId the project ID
     * @return the count
     */
    public long countByProjectId(final String projectId) {
        return jdbi.withHandle(handle -> handle
                .createQuery(COUNT_BY_PROJECT_ID)
                .bind("projectId", projectId)
                .mapTo(Long.class)
                .one());
    }

    /**
     * Updates the enrichment data for a source class.
     *
     * <p>Used during Phase 2 of analysis to update the class with
     * Claude-provided business descriptions and optionally correct
     * the class type inferred during Phase 1.</p>
     *
     * @param id the source class ID
     * @param classType the corrected class type
     * @param description the business description from Claude
     */
    public void updateEnrichment(final String id,
            final ClassType classType,
            final String description) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                UPDATE source_classes
                SET class_type = :classType, description = :description
                WHERE id = :id
                """)
                .bind("id", id)
                .bind("classType", classType.name())
                .bind("description", description)
                .execute());
    }

    /**
     * Updates the commit hash for a source class.
     *
     * <p>Used during graph rebuild to backfill commit hashes for
     * classes that were analyzed before commit hash tracking was added.</p>
     *
     * @param id the source class ID
     * @param commitHash the git commit hash
     */
    public void updateCommitHash(final String id,
            final String commitHash) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                UPDATE source_classes
                SET commit_hash = :commitHash
                WHERE id = :id
                """)
                .bind("id", id)
                .bind("commitHash", commitHash)
                .execute());
    }

    /**
     * Deletes all source classes for a project.
     *
     * @param projectId the project ID
     */
    public void deleteByProjectId(final String projectId) {
        jdbi.useHandle(handle -> handle
                .createUpdate("""
                        DELETE FROM source_classes
                        WHERE project_id = :projectId
                        """)
                .bind("projectId", projectId)
                .execute());
    }

    /**
     * Finds source classes with missing enrichment (no description) for
     * a project.
     *
     * <p>Used by Phase 3 recovery to identify classes where Claude
     * enrichment failed or was skipped.</p>
     *
     * @param projectId the project ID
     * @return list of unenriched source classes
     */
    public List<SourceClass> findUnenrichedByProjectId(
            final String projectId) {
        return jdbi.withHandle(handle -> handle
                .createQuery("""
                        SELECT * FROM source_classes
                        WHERE project_id = :projectId
                          AND description IS NULL
                        ORDER BY full_class_name
                        """)
                .bind("projectId", projectId)
                .map(new SourceClassRowMapper())
                .list());
    }

    /**
     * Deletes a source class by ID.
     *
     * @param id the class ID
     */
    public void delete(final String id) {
        jdbi.useHandle(handle -> handle
                .createUpdate("DELETE FROM source_classes WHERE id = :id")
                .bind("id", id)
                .execute());
    }

    private Timestamp toTimestamp(final Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }

    private static final class SourceClassRowMapper implements RowMapper<SourceClass> {

        @Override
        public SourceClass map(final ResultSet rs, final StatementContext ctx)
                throws SQLException {
            final String id = rs.getString("id");
            final String projectId = rs.getString("project_id");
            final String fullClassName = rs.getString("full_class_name");
            final String simpleName = rs.getString("simple_name");
            final String packageName = rs.getString("package_name");
            final ClassType classType = ClassType.valueOf(rs.getString("class_type"));
            final String description = rs.getString("description");
            final String sourceFile = rs.getString("source_file");
            final String commitHash = rs.getString("commit_hash");
            final Instant createdAt = rs.getTimestamp("created_at").toInstant();

            return SourceClass.reconstitute(
                    id, projectId, fullClassName, simpleName, packageName,
                    classType, description, sourceFile, commitHash, createdAt);
        }
    }

}
