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
                    class_type, description, source_file, created_at
                ) VALUES (
                    :id, :projectId, :fullClassName, :simpleName, :packageName,
                    :classType, :description, :sourceFile, :createdAt
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
                        class_type, description, source_file, created_at
                    ) VALUES (
                        :id, :projectId, :fullClassName, :simpleName, :packageName,
                        :classType, :description, :sourceFile, :createdAt
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
                .createQuery("SELECT * FROM source_classes WHERE id = :id")
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
                .createQuery("""
                        SELECT * FROM source_classes
                        WHERE project_id = :projectId
                        ORDER BY full_class_name
                        """)
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
                .createQuery("""
                        SELECT * FROM source_classes
                        WHERE full_class_name = :fullClassName
                        """)
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
                .createQuery("""
                        SELECT * FROM source_classes
                        WHERE package_name = :packagePrefix
                           OR package_name LIKE :packagePattern
                        ORDER BY full_class_name
                        """)
                .bind("packagePrefix", packagePrefix)
                .bind("packagePattern", packagePrefix + ".%")
                .map(new SourceClassRowMapper())
                .list());
    }

    /**
     * Counts the number of source classes for a project.
     *
     * @param projectId the project ID
     * @return the count
     */
    public long countByProjectId(final String projectId) {
        return jdbi.withHandle(handle -> handle
                .createQuery("""
                        SELECT COUNT(*) FROM source_classes
                        WHERE project_id = :projectId
                        """)
                .bind("projectId", projectId)
                .mapTo(Long.class)
                .one());
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
            final Instant createdAt = rs.getTimestamp("created_at").toInstant();

            return SourceClass.reconstitute(
                    id, projectId, fullClassName, simpleName, packageName,
                    classType, description, sourceFile, createdAt);
        }
    }

}
