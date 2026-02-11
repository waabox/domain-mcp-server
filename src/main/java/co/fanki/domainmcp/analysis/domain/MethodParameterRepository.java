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

/**
 * Repository for persisting and retrieving method parameters.
 *
 * <p>Each method parameter links a {@link SourceMethod} to a
 * {@link SourceClass} that appears as a parameter type in the method
 * signature.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Repository
public class MethodParameterRepository {

    /** Find parameters by method ID. Uses: idx_method_params_method. */
    private static final String FIND_BY_METHOD_ID =
            "SELECT * FROM method_parameters WHERE method_id = :methodId"
                    + " ORDER BY position";

    private final Jdbi jdbi;

    /**
     * Creates a new MethodParameterRepository.
     *
     * @param theJdbi the JDBI instance
     */
    public MethodParameterRepository(final Jdbi theJdbi) {
        this.jdbi = theJdbi;
        this.jdbi.registerRowMapper(new MethodParameterRowMapper());
    }

    /**
     * Saves a single method parameter.
     *
     * @param param the method parameter to save
     */
    public void save(final MethodParameter param) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                INSERT INTO method_parameters (
                    id, method_id, position, class_id, created_at
                ) VALUES (
                    :id, :methodId, :position, :classId, :createdAt
                )
                """)
                .bind("id", param.id())
                .bind("methodId", param.methodId())
                .bind("position", param.position())
                .bind("classId", param.classId())
                .bind("createdAt", toTimestamp(param.createdAt()))
                .execute());
    }

    /**
     * Saves multiple method parameters in a batch.
     *
     * @param params the method parameters to save
     */
    public void saveAll(final List<MethodParameter> params) {
        if (params.isEmpty()) {
            return;
        }
        jdbi.useHandle(handle -> {
            final var batch = handle.prepareBatch("""
                    INSERT INTO method_parameters (
                        id, method_id, position, class_id, created_at
                    ) VALUES (
                        :id, :methodId, :position, :classId, :createdAt
                    )
                    """);
            for (final MethodParameter p : params) {
                batch.bind("id", p.id())
                        .bind("methodId", p.methodId())
                        .bind("position", p.position())
                        .bind("classId", p.classId())
                        .bind("createdAt", toTimestamp(p.createdAt()))
                        .add();
            }
            batch.execute();
        });
    }

    /**
     * Finds all parameters for a given method.
     *
     * @param methodId the method ID
     * @return list of method parameters ordered by position
     */
    public List<MethodParameter> findByMethodId(final String methodId) {
        return jdbi.withHandle(handle -> handle
                .createQuery(FIND_BY_METHOD_ID)
                .bind("methodId", methodId)
                .map(new MethodParameterRowMapper())
                .list());
    }

    /**
     * Deletes all parameters for a given method.
     *
     * @param methodId the method ID
     */
    public void deleteByMethodId(final String methodId) {
        jdbi.useHandle(handle -> handle
                .createUpdate("DELETE FROM method_parameters"
                        + " WHERE method_id = :methodId")
                .bind("methodId", methodId)
                .execute());
    }

    /**
     * Deletes all parameters referencing a given class.
     *
     * <p>Useful when re-analyzing a project and clearing old data.</p>
     *
     * @param classId the class ID
     */
    public void deleteByClassId(final String classId) {
        jdbi.useHandle(handle -> handle
                .createUpdate("DELETE FROM method_parameters"
                        + " WHERE class_id = :classId")
                .bind("classId", classId)
                .execute());
    }

    private Timestamp toTimestamp(final Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }

    private static final class MethodParameterRowMapper
            implements RowMapper<MethodParameter> {

        @Override
        public MethodParameter map(final ResultSet rs,
                final StatementContext ctx) throws SQLException {
            return MethodParameter.reconstitute(
                    rs.getString("id"),
                    rs.getString("method_id"),
                    rs.getInt("position"),
                    rs.getString("class_id"),
                    rs.getTimestamp("created_at").toInstant());
        }
    }

}
