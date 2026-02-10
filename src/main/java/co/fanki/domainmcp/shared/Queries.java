package co.fanki.domainmcp.shared;

/**
 * Contains all SQL read queries used by repositories.
 *
 * <p>Centralizing queries enables index analysis and optimization.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class Queries {

    private Queries() {
    }

    // ========================================================================
    // SourceClassRepository queries
    // ========================================================================

    /** Find source class by ID. Uses: PK index. */
    public static final String SOURCE_CLASS_FIND_BY_ID =
            "SELECT * FROM source_classes WHERE id = :id";

    /** Find source classes by project ID. Uses: idx_source_classes_project. */
    public static final String SOURCE_CLASS_FIND_BY_PROJECT_ID = """
            SELECT * FROM source_classes
            WHERE project_id = :projectId
            ORDER BY full_class_name
            """;

    /** Find source class by full class name. Uses: idx_source_classes_full_name. */
    public static final String SOURCE_CLASS_FIND_BY_FULL_CLASS_NAME = """
            SELECT * FROM source_classes
            WHERE full_class_name = :fullClassName
            """;

    /** Find source classes by package prefix. Uses: idx_source_classes_package. */
    public static final String SOURCE_CLASS_FIND_BY_PACKAGE_PREFIX = """
            SELECT * FROM source_classes
            WHERE package_name = :packagePrefix
               OR package_name LIKE :packagePattern
            ORDER BY full_class_name
            """;

    /** Count source classes by project ID. Uses: idx_source_classes_project. */
    public static final String SOURCE_CLASS_COUNT_BY_PROJECT_ID = """
            SELECT COUNT(*) FROM source_classes
            WHERE project_id = :projectId
            """;

    // ========================================================================
    // SourceMethodRepository queries
    // ========================================================================

    /** Find source method by ID. Uses: PK index. */
    public static final String SOURCE_METHOD_FIND_BY_ID =
            "SELECT * FROM source_methods WHERE id = :id";

    /** Find source methods by class ID. Uses: idx_source_methods_class. */
    public static final String SOURCE_METHOD_FIND_BY_CLASS_ID = """
            SELECT * FROM source_methods
            WHERE class_id = :classId
            ORDER BY line_number NULLS LAST, method_name
            """;

    /** Find source methods by class name. Uses: idx_source_classes_full_name + idx_source_methods_class. */
    public static final String SOURCE_METHOD_FIND_BY_CLASS_NAME = """
            SELECT m.* FROM source_methods m
            JOIN source_classes c ON c.id = m.class_id
            WHERE c.full_class_name = :fullClassName
            ORDER BY m.line_number NULLS LAST, m.method_name
            """;

    /** Find source method by class name and method name. Uses: idx_source_classes_full_name + idx_source_methods_class_name. */
    public static final String SOURCE_METHOD_FIND_BY_CLASS_AND_METHOD_NAME = """
            SELECT m.* FROM source_methods m
            JOIN source_classes c ON c.id = m.class_id
            WHERE c.full_class_name = :fullClassName
              AND m.method_name = :methodName
            """;

    /** Find HTTP endpoints by project ID. Uses: idx_source_classes_project + idx_source_methods_class + idx_source_methods_http_path. */
    public static final String SOURCE_METHOD_FIND_HTTP_ENDPOINTS_BY_PROJECT_ID = """
            SELECT m.* FROM source_methods m
            JOIN source_classes c ON c.id = m.class_id
            WHERE c.project_id = :projectId
              AND m.http_method IS NOT NULL
              AND m.http_path IS NOT NULL
            ORDER BY m.http_path, m.http_method
            """;

    /** Count HTTP endpoints by project ID. Uses: idx_source_classes_project + idx_source_methods_class. */
    public static final String SOURCE_METHOD_COUNT_ENDPOINTS_BY_PROJECT_ID = """
            SELECT COUNT(*) FROM source_methods m
            JOIN source_classes c ON c.id = m.class_id
            WHERE c.project_id = :projectId
              AND m.http_method IS NOT NULL
              AND m.http_path IS NOT NULL
            """;

    // ========================================================================
    // ProjectRepository queries
    // ========================================================================

    /** Find project by ID. Uses: PK index. */
    public static final String PROJECT_FIND_BY_ID =
            "SELECT * FROM projects WHERE id = :id";

    /** Find project by repository URL. Uses: UNIQUE constraint on repository_url. */
    public static final String PROJECT_FIND_BY_REPOSITORY_URL =
            "SELECT * FROM projects WHERE repository_url = :url";

    /** Find all projects. Uses: seq scan (expected for small table). */
    public static final String PROJECT_FIND_ALL =
            "SELECT * FROM projects ORDER BY created_at DESC";

    /** Find projects by status. Uses: idx_projects_status. */
    public static final String PROJECT_FIND_BY_STATUS =
            "SELECT * FROM projects WHERE status = :status";

    /** Check if project exists by repository URL. Uses: UNIQUE constraint on repository_url. */
    public static final String PROJECT_EXISTS_BY_REPOSITORY_URL =
            "SELECT COUNT(*) FROM projects WHERE repository_url = :url";

}
