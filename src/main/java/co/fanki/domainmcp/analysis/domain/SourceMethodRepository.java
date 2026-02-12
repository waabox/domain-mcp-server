package co.fanki.domainmcp.analysis.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Repository for persisting and retrieving source methods.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Repository
public class SourceMethodRepository {

    /** Find source method by ID. Uses: PK index. */
    public static final String FIND_BY_ID =
            "SELECT * FROM source_methods WHERE id = :id";

    /** Find source methods by class ID. Uses: idx_source_methods_class. */
    public static final String FIND_BY_CLASS_ID = """
            SELECT * FROM source_methods
            WHERE class_id = :classId
            ORDER BY line_number NULLS LAST, method_name
            """;

    /** Find source methods by class name. Uses: idx_source_classes_full_name + idx_source_methods_class. */
    public static final String FIND_BY_CLASS_NAME = """
            SELECT m.* FROM source_methods m
            JOIN source_classes c ON c.id = m.class_id
            WHERE c.full_class_name = :fullClassName
            ORDER BY m.line_number NULLS LAST, m.method_name
            """;

    /** Find source method by class name and method name. Uses: idx_source_classes_full_name + idx_source_methods_class_name. */
    public static final String FIND_BY_CLASS_AND_METHOD_NAME = """
            SELECT m.* FROM source_methods m
            JOIN source_classes c ON c.id = m.class_id
            WHERE c.full_class_name = :fullClassName
              AND m.method_name = :methodName
            """;

    /** Find HTTP endpoints by project ID. Uses: idx_source_classes_project + idx_source_methods_class + idx_source_methods_http_path. */
    public static final String FIND_HTTP_ENDPOINTS_BY_PROJECT_ID = """
            SELECT m.* FROM source_methods m
            JOIN source_classes c ON c.id = m.class_id
            WHERE c.project_id = :projectId
              AND m.http_method IS NOT NULL
              AND m.http_path IS NOT NULL
            ORDER BY m.http_path, m.http_method
            """;

    /** Count HTTP endpoints by project ID. Uses: idx_source_classes_project + idx_source_methods_class. */
    public static final String COUNT_ENDPOINTS_BY_PROJECT_ID = """
            SELECT COUNT(*) FROM source_methods m
            JOIN source_classes c ON c.id = m.class_id
            WHERE c.project_id = :projectId
              AND m.http_method IS NOT NULL
              AND m.http_path IS NOT NULL
            """;

    private final Jdbi jdbi;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new SourceMethodRepository.
     *
     * @param theJdbi the JDBI instance
     * @param theObjectMapper the Jackson ObjectMapper
     */
    public SourceMethodRepository(final Jdbi theJdbi,
            final ObjectMapper theObjectMapper) {
        this.jdbi = theJdbi;
        this.objectMapper = theObjectMapper;
        this.jdbi.registerRowMapper(new SourceMethodRowMapper(objectMapper));
    }

    /**
     * Saves a new source method.
     *
     * @param method the source method to save
     */
    public void save(final SourceMethod method) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                INSERT INTO source_methods (
                    id, class_id, method_name, description, business_logic,
                    exceptions, http_method, http_path,
                    line_number, created_at
                ) VALUES (
                    :id, :classId, :methodName, :description, :businessLogic,
                    :exceptions, :httpMethod, :httpPath,
                    :lineNumber, :createdAt
                )
                """)
                .bind("id", method.id())
                .bind("classId", method.classId())
                .bind("methodName", method.methodName())
                .bind("description", method.description())
                .bind("businessLogic", toJson(method.businessLogic()))
                .bind("exceptions", toJson(method.exceptions()))
                .bind("httpMethod", method.httpMethod())
                .bind("httpPath", method.httpPath())
                .bind("lineNumber", method.lineNumber())
                .bind("createdAt", toTimestamp(method.createdAt()))
                .execute());
    }

    /**
     * Saves multiple source methods in a batch.
     *
     * @param methods the source methods to save
     */
    public void saveAll(final List<SourceMethod> methods) {
        if (methods.isEmpty()) {
            return;
        }
        jdbi.useHandle(handle -> {
            final var batch = handle.prepareBatch("""
                    INSERT INTO source_methods (
                        id, class_id, method_name, description, business_logic,
                        exceptions, http_method, http_path,
                        line_number, created_at
                    ) VALUES (
                        :id, :classId, :methodName, :description, :businessLogic,
                        :exceptions, :httpMethod, :httpPath,
                        :lineNumber, :createdAt
                    )
                    """);
            for (final SourceMethod m : methods) {
                batch.bind("id", m.id())
                        .bind("classId", m.classId())
                        .bind("methodName", m.methodName())
                        .bind("description", m.description())
                        .bind("businessLogic", toJson(m.businessLogic()))
                        .bind("exceptions", toJson(m.exceptions()))
                        .bind("httpMethod", m.httpMethod())
                        .bind("httpPath", m.httpPath())
                        .bind("lineNumber", m.lineNumber())
                        .bind("createdAt", toTimestamp(m.createdAt()))
                        .add();
            }
            batch.execute();
        });
    }

    /**
     * Finds a source method by its ID.
     *
     * @param id the method ID
     * @return the source method if found
     */
    public Optional<SourceMethod> findById(final String id) {
        return jdbi.withHandle(handle -> handle
                .createQuery(FIND_BY_ID)
                .bind("id", id)
                .map(new SourceMethodRowMapper(objectMapper))
                .findOne());
    }

    /**
     * Finds all source methods for a class.
     *
     * @param classId the class ID
     * @return list of source methods
     */
    public List<SourceMethod> findByClassId(final String classId) {
        return jdbi.withHandle(handle -> handle
                .createQuery(FIND_BY_CLASS_ID)
                .bind("classId", classId)
                .map(new SourceMethodRowMapper(objectMapper))
                .list());
    }

    /**
     * Finds methods by class name (requires joining with source_classes).
     *
     * @param fullClassName the fully qualified class name
     * @return list of source methods for that class
     */
    public List<SourceMethod> findByClassName(final String fullClassName) {
        return jdbi.withHandle(handle -> handle
                .createQuery(FIND_BY_CLASS_NAME)
                .bind("fullClassName", fullClassName)
                .map(new SourceMethodRowMapper(objectMapper))
                .list());
    }

    /**
     * Finds a specific method by class name and method name.
     *
     * @param fullClassName the fully qualified class name
     * @param methodName the method name
     * @return the source method if found
     */
    public Optional<SourceMethod> findByClassNameAndMethodName(
            final String fullClassName, final String methodName) {
        return jdbi.withHandle(handle -> handle
                .createQuery(FIND_BY_CLASS_AND_METHOD_NAME)
                .bind("fullClassName", fullClassName)
                .bind("methodName", methodName)
                .map(new SourceMethodRowMapper(objectMapper))
                .findOne());
    }

    /**
     * Finds all HTTP endpoint methods for a project.
     *
     * @param projectId the project ID
     * @return list of methods that are HTTP endpoints
     */
    public List<SourceMethod> findHttpEndpointsByProjectId(final String projectId) {
        return jdbi.withHandle(handle -> handle
                .createQuery(FIND_HTTP_ENDPOINTS_BY_PROJECT_ID)
                .bind("projectId", projectId)
                .map(new SourceMethodRowMapper(objectMapper))
                .list());
    }

    /**
     * Counts the number of HTTP endpoints for a project.
     *
     * @param projectId the project ID
     * @return the count
     */
    public long countEndpointsByProjectId(final String projectId) {
        return jdbi.withHandle(handle -> handle
                .createQuery(COUNT_ENDPOINTS_BY_PROJECT_ID)
                .bind("projectId", projectId)
                .mapTo(Long.class)
                .one());
    }

    /**
     * Updates the enrichment data for a source method.
     *
     * <p>Used during Phase 2 of analysis to update the method with
     * Claude-provided business descriptions, logic steps, and
     * exceptions.</p>
     *
     * @param id the source method ID
     * @param description the business description from Claude
     * @param businessLogic the business logic steps
     * @param exceptions the exceptions
     */
    public void updateEnrichment(final String id,
            final String description,
            final List<String> businessLogic,
            final List<String> exceptions) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                UPDATE source_methods
                SET description = :description,
                    business_logic = :businessLogic,
                    exceptions = :exceptions
                WHERE id = :id
                """)
                .bind("id", id)
                .bind("description", description)
                .bind("businessLogic", toJson(businessLogic))
                .bind("exceptions", toJson(exceptions))
                .execute());
    }

    /**
     * Finds all source methods for a class by class ID and method name.
     *
     * @param classId the class ID
     * @param methodName the method name
     * @return the source method if found
     */
    public Optional<SourceMethod> findByClassIdAndMethodName(
            final String classId, final String methodName) {
        return jdbi.withHandle(handle -> handle
                .createQuery("""
                        SELECT * FROM source_methods
                        WHERE class_id = :classId
                          AND method_name = :methodName
                        LIMIT 1
                        """)
                .bind("classId", classId)
                .bind("methodName", methodName)
                .map(new SourceMethodRowMapper(objectMapper))
                .findFirst());
    }

    /**
     * Deletes a source method by ID.
     *
     * @param id the method ID
     */
    public void delete(final String id) {
        jdbi.useHandle(handle -> handle
                .createUpdate("DELETE FROM source_methods WHERE id = :id")
                .bind("id", id)
                .execute());
    }

    /**
     * Deletes all methods for a class.
     *
     * @param classId the class ID
     */
    public void deleteByClassId(final String classId) {
        jdbi.useHandle(handle -> handle
                .createUpdate("DELETE FROM source_methods WHERE class_id = :classId")
                .bind("classId", classId)
                .execute());
    }

    /**
     * Deletes all methods for multiple classes.
     *
     * <p>Used during incremental sync to clear methods for classes
     * that will be re-parsed.</p>
     *
     * @param classIds the class IDs whose methods should be deleted
     */
    public void deleteByClassIds(final List<String> classIds) {
        if (classIds.isEmpty()) {
            return;
        }
        jdbi.useHandle(handle -> handle
                .createUpdate(
                        "DELETE FROM source_methods WHERE class_id IN (<classIds>)")
                .bindList("classIds", classIds)
                .execute());
    }

    private Timestamp toTimestamp(final Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }

    private String toJson(final List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (final JsonProcessingException e) {
            return null;
        }
    }

    private static final class SourceMethodRowMapper
            implements RowMapper<SourceMethod> {

        private static final TypeReference<List<String>> LIST_TYPE =
                new TypeReference<>() {};

        private final ObjectMapper objectMapper;

        SourceMethodRowMapper(final ObjectMapper theObjectMapper) {
            this.objectMapper = theObjectMapper;
        }

        @Override
        public SourceMethod map(final ResultSet rs, final StatementContext ctx)
                throws SQLException {
            final String id = rs.getString("id");
            final String classId = rs.getString("class_id");
            final String methodName = rs.getString("method_name");
            final String description = rs.getString("description");
            final List<String> businessLogic = fromJson(
                    rs.getString("business_logic"));
            final List<String> exceptions = fromJson(
                    rs.getString("exceptions"));
            final String httpMethod = rs.getString("http_method");
            final String httpPath = rs.getString("http_path");

            final int lineNum = rs.getInt("line_number");
            final Integer lineNumber = rs.wasNull() ? null : lineNum;

            final Instant createdAt = rs.getTimestamp("created_at").toInstant();

            return SourceMethod.reconstitute(
                    id, classId, methodName, description, businessLogic,
                    exceptions, httpMethod, httpPath,
                    lineNumber, createdAt);
        }

        private List<String> fromJson(final String json) {
            if (json == null || json.isBlank()) {
                return Collections.emptyList();
            }
            try {
                return objectMapper.readValue(json, LIST_TYPE);
            } catch (final JsonProcessingException e) {
                return Collections.emptyList();
            }
        }
    }

}
