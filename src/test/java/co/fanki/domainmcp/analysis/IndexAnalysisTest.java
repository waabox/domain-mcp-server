package co.fanki.domainmcp.analysis;

import co.fanki.domainmcp.analysis.domain.ClassType;
import co.fanki.domainmcp.analysis.domain.SourceClass;
import co.fanki.domainmcp.analysis.domain.SourceClassRepository;
import co.fanki.domainmcp.analysis.domain.SourceMethod;
import co.fanki.domainmcp.analysis.domain.SourceMethodRepository;
import co.fanki.domainmcp.project.domain.Project;
import co.fanki.domainmcp.project.domain.ProjectRepository;
import co.fanki.domainmcp.project.domain.RepositoryUrl;
import co.fanki.domainmcp.shared.Queries;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Index analysis test that validates query execution plans.
 *
 * <p>Run with: mvn test -DAnalizeIndexes=true -Dtest=IndexAnalysisTest</p>
 *
 * <p>This test generates 10,000 entries and validates that all read queries
 * use indexes instead of sequential scans.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "AnalizeIndexes", matches = "true")
class IndexAnalysisTest {

    private static final int TOTAL_CLASSES = 10_000;
    private static final int METHODS_PER_CLASS = 5;
    private static final Random RANDOM = new Random(42);

    private static final String[] PACKAGES = {
            "co.fanki.user", "co.fanki.order", "co.fanki.payment",
            "co.fanki.product", "co.fanki.inventory", "co.fanki.shipping",
            "co.fanki.notification", "co.fanki.auth", "co.fanki.report"
    };

    private static final String[] CLASS_SUFFIXES = {
            "Service", "Controller", "Repository", "Entity", "Dto",
            "Mapper", "Config", "Listener", "Handler", "Factory"
    };

    private static final String[] METHOD_NAMES = {
            "create", "update", "delete", "find", "findAll", "save",
            "process", "validate", "transform", "execute", "handle"
    };

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> postgres.getJdbcUrl() + "?currentSchema=domain_mcp");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private Jdbi jdbi;

    @Autowired
    private ObjectMapper objectMapper;

    private static boolean dataGenerated = false;
    private static String testProjectId;
    private static String testClassId;
    private static String testClassName;

    @BeforeAll
    static void generateData(@Autowired Jdbi jdbi, @Autowired ObjectMapper objectMapper) {
        if (dataGenerated) {
            return;
        }

        System.out.println("\n=== Generating test data ===");
        System.out.println("Classes: " + TOTAL_CLASSES);
        System.out.println("Methods per class: " + METHODS_PER_CLASS);
        System.out.println("Total methods: " + (TOTAL_CLASSES * METHODS_PER_CLASS));

        final ProjectRepository projectRepository = new ProjectRepository(jdbi);
        final SourceClassRepository classRepository = new SourceClassRepository(jdbi);
        final SourceMethodRepository methodRepository = new SourceMethodRepository(jdbi, objectMapper);

        // Clean existing data
        jdbi.useHandle(handle -> {
            handle.execute("DELETE FROM source_methods");
            handle.execute("DELETE FROM source_classes");
            handle.execute("DELETE FROM projects");
        });

        // Create test project
        final Project project = Project.create("IndexAnalysisProject",
                RepositoryUrl.of("https://github.com/test/index-analysis.git"));
        projectRepository.save(project);
        testProjectId = project.id();

        // Generate classes in batches
        final int batchSize = 1000;
        final List<SourceClass> classBatch = new ArrayList<>(batchSize);
        final List<SourceMethod> methodBatch = new ArrayList<>(batchSize * METHODS_PER_CLASS);

        for (int i = 0; i < TOTAL_CLASSES; i++) {
            final String pkg = PACKAGES[RANDOM.nextInt(PACKAGES.length)];
            final String suffix = CLASS_SUFFIXES[RANDOM.nextInt(CLASS_SUFFIXES.length)];
            final String simpleName = "Generated" + i + suffix;
            final String fullClassName = pkg + "." + simpleName;
            final ClassType classType = ClassType.values()[RANDOM.nextInt(ClassType.values().length)];

            final SourceClass sourceClass = SourceClass.create(
                    project.id(),
                    fullClassName,
                    classType,
                    "Generated class for index testing #" + i,
                    "src/main/java/" + fullClassName.replace('.', '/') + ".java"
            );

            classBatch.add(sourceClass);

            // Save first class info for queries
            if (i == 0) {
                testClassId = sourceClass.id();
                testClassName = fullClassName;
            }

            // Generate methods for this class
            for (int j = 0; j < METHODS_PER_CLASS; j++) {
                final String methodName = METHOD_NAMES[RANDOM.nextInt(METHOD_NAMES.length)] + j;
                final boolean isEndpoint = RANDOM.nextBoolean() && classType == ClassType.CONTROLLER;

                final SourceMethod method = SourceMethod.create(
                        sourceClass.id(),
                        methodName,
                        "Generated method " + methodName,
                        List.of("Step 1", "Step 2"),
                        List.of("Dependency1"),
                        List.of(),
                        isEndpoint ? "GET" : null,
                        isEndpoint ? "/api/generated/" + i + "/" + j : null,
                        10 + j * 10
                );
                methodBatch.add(method);
            }

            // Save batch
            if (classBatch.size() >= batchSize) {
                classRepository.saveAll(classBatch);
                methodRepository.saveAll(methodBatch);
                System.out.println("  Generated " + (i + 1) + " classes...");
                classBatch.clear();
                methodBatch.clear();
            }
        }

        // Save remaining
        if (!classBatch.isEmpty()) {
            classRepository.saveAll(classBatch);
            methodRepository.saveAll(methodBatch);
        }

        // Analyze tables for accurate query plans
        jdbi.useHandle(handle -> {
            handle.execute("ANALYZE source_classes");
            handle.execute("ANALYZE source_methods");
            handle.execute("ANALYZE projects");
        });

        dataGenerated = true;
        System.out.println("=== Data generation complete ===\n");
    }

    @Test
    void analyzeAllQueries() throws Exception {
        System.out.println("\n========================================");
        System.out.println("       INDEX ANALYSIS REPORT");
        System.out.println("========================================\n");

        final List<String> issues = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        // Get all query constants from Queries class
        for (final Field field : Queries.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers())
                    && Modifier.isStatic(field.getModifiers())
                    && field.getType() == String.class) {

                final String queryName = field.getName();
                final String query = (String) field.get(null);

                // Skip write queries
                if (query.trim().toUpperCase().startsWith("INSERT")
                        || query.trim().toUpperCase().startsWith("UPDATE")
                        || query.trim().toUpperCase().startsWith("DELETE")) {
                    continue;
                }

                final String preparedQuery = prepareQuery(queryName, query);
                final String explainResult = runExplainAnalyze(preparedQuery);

                System.out.println("Query: " + queryName);
                System.out.println("-".repeat(60));
                System.out.println(explainResult);

                // Check for index usage (including Bitmap scans)
                final boolean usesIndex = explainResult.contains("Index Scan")
                        || explainResult.contains("Index Only Scan")
                        || explainResult.contains("Bitmap Index Scan")
                        || explainResult.contains("Bitmap Heap Scan");

                final boolean isSeqScan = explainResult.contains("Seq Scan");
                final boolean isProjectQuery = queryName.startsWith("PROJECT_");
                final boolean isFindAll = queryName.contains("FIND_ALL");
                // Queries that filter by project_id will seq scan if all rows match
                final boolean isProjectIdFilter = queryName.contains("BY_PROJECT_ID")
                        || queryName.contains("BY_PROJECT");

                if (usesIndex) {
                    System.out.println("✓ Uses index scan\n");
                } else if (isFindAll) {
                    System.out.println("○ Sequential scan expected for FIND_ALL\n");
                } else if (isProjectQuery && isSeqScan) {
                    // Projects table is small, seq scan is OK
                    System.out.println("○ Sequential scan OK for small table (projects)\n");
                    warnings.add(queryName + ": Uses seq scan (OK for small table)");
                } else if (isProjectIdFilter && isSeqScan) {
                    // All test data belongs to same project, seq scan is correct
                    System.out.println("○ Sequential scan OK (all rows match project_id filter)\n");
                    warnings.add(queryName + ": Uses seq scan (all rows match filter)");
                } else if (queryName.contains("PACKAGE_PREFIX") && isSeqScan) {
                    // Package prefix with LIKE may scan many rows
                    System.out.println("○ Sequential scan OK (LIKE pattern may match many rows)\n");
                    warnings.add(queryName + ": Uses seq scan (LIKE pattern)");
                } else if (isSeqScan) {
                    System.out.println("✗ WARNING: Sequential scan on large table\n");
                    issues.add(queryName + ": Uses sequential scan - needs index optimization");
                } else {
                    System.out.println("? Unknown scan type\n");
                }
            }
        }

        System.out.println("========================================");
        System.out.println("             SUMMARY");
        System.out.println("========================================");

        if (issues.isEmpty() && warnings.isEmpty()) {
            System.out.println("✓ All queries are using indexes properly!");
        } else {
            if (!issues.isEmpty()) {
                System.out.println("ISSUES (need optimization):");
                issues.forEach(issue -> System.out.println("  ✗ " + issue));
            }
            if (!warnings.isEmpty()) {
                System.out.println("\nWARNINGS (acceptable):");
                warnings.forEach(warning -> System.out.println("  ○ " + warning));
            }
        }

        System.out.println("========================================\n");

        // Only fail for source_classes and source_methods issues
        final List<String> criticalIssues = issues.stream()
                .filter(i -> i.contains("SOURCE_CLASS") || i.contains("SOURCE_METHOD"))
                .toList();

        assertTrue(criticalIssues.isEmpty(),
                "Critical queries with missing indexes: " + criticalIssues);
    }

    @Test
    void verifySourceClassIndexes() {
        // Test specific query that should use idx_source_classes_full_name
        final String explainResult = runExplainAnalyze(
                "SELECT * FROM source_classes WHERE full_class_name = '" + testClassName + "'");

        System.out.println("SOURCE_CLASS_FIND_BY_FULL_CLASS_NAME:");
        System.out.println(explainResult);

        assertTrue(explainResult.contains("Index"),
                "Query should use index on full_class_name");
        assertFalse(explainResult.contains("Seq Scan"),
                "Query should not use sequential scan");
    }

    @Test
    void verifySourceMethodJoinIndexes() {
        // Test JOIN query that should use indexes on both tables
        final String explainResult = runExplainAnalyze("""
                SELECT m.* FROM source_methods m
                JOIN source_classes c ON c.id = m.class_id
                WHERE c.full_class_name = '%s'
                  AND m.method_name = 'create0'
                """.formatted(testClassName));

        System.out.println("SOURCE_METHOD_FIND_BY_CLASS_AND_METHOD_NAME:");
        System.out.println(explainResult);

        assertTrue(explainResult.contains("Index"),
                "Query should use indexes for JOIN");
    }

    @Test
    void verifyAllIndexesExist() {
        System.out.println("\n=== Verifying indexes exist ===\n");

        final List<String> expectedIndexes = List.of(
                // source_classes indexes
                "idx_source_classes_full_name",
                "idx_source_classes_package",
                "idx_source_classes_project",
                "idx_source_classes_simple_name",
                "idx_source_classes_type",
                "idx_source_classes_project_package",
                // source_methods indexes
                "idx_source_methods_class",
                "idx_source_methods_name",
                "idx_source_methods_http_path",
                "idx_source_methods_class_name",
                "idx_source_methods_line",
                // projects indexes
                "idx_projects_status",
                "idx_projects_name"
        );

        final List<String> missingIndexes = new ArrayList<>();

        for (final String indexName : expectedIndexes) {
            final boolean exists = jdbi.withHandle(handle -> handle
                    .createQuery("SELECT 1 FROM pg_indexes WHERE indexname = :name")
                    .bind("name", indexName)
                    .mapTo(Integer.class)
                    .findOne()
                    .isPresent());

            if (exists) {
                System.out.println("✓ " + indexName);
            } else {
                System.out.println("✗ " + indexName + " - MISSING");
                missingIndexes.add(indexName);
            }
        }

        System.out.println();
        assertTrue(missingIndexes.isEmpty(),
                "Missing indexes: " + missingIndexes);
    }

    @Test
    void verifyProjectIdFilterIndexes() {
        // Test query filtering by project_id
        final String explainResult = runExplainAnalyze(
                "SELECT * FROM source_classes WHERE project_id = '" + testProjectId + "'");

        System.out.println("SOURCE_CLASS_FIND_BY_PROJECT_ID:");
        System.out.println(explainResult);

        // With 10k rows all belonging to same project, PostgreSQL may use seq scan
        // because it would return all rows anyway. Check that index exists.
        final boolean hasIndex = jdbi.withHandle(handle -> handle
                .createQuery("""
                        SELECT 1 FROM pg_indexes
                        WHERE tablename = 'source_classes'
                        AND indexname = 'idx_source_classes_project'
                        """)
                .mapTo(Integer.class)
                .findOne()
                .isPresent());

        assertTrue(hasIndex, "Index idx_source_classes_project should exist");
    }

    private String prepareQuery(final String queryName, final String query) {
        String prepared = query;

        // Replace bind parameters with test values
        if (prepared.contains(":id")) {
            prepared = prepared.replace(":id", "'" + testClassId + "'");
        }
        if (prepared.contains(":projectId")) {
            prepared = prepared.replace(":projectId", "'" + testProjectId + "'");
        }
        if (prepared.contains(":fullClassName")) {
            prepared = prepared.replace(":fullClassName", "'" + testClassName + "'");
        }
        if (prepared.contains(":methodName")) {
            prepared = prepared.replace(":methodName", "'create0'");
        }
        if (prepared.contains(":packagePrefix")) {
            prepared = prepared.replace(":packagePrefix", "'co.fanki.user'");
        }
        if (prepared.contains(":packagePattern")) {
            prepared = prepared.replace(":packagePattern", "'co.fanki.user.%'");
        }
        if (prepared.contains(":classId")) {
            prepared = prepared.replace(":classId", "'" + testClassId + "'");
        }
        if (prepared.contains(":url")) {
            prepared = prepared.replace(":url", "'https://github.com/test/index-analysis.git'");
        }
        if (prepared.contains(":status")) {
            prepared = prepared.replace(":status", "'PENDING'");
        }

        return prepared;
    }

    private String runExplainAnalyze(final String query) {
        return jdbi.withHandle(handle -> {
            final StringBuilder result = new StringBuilder();
            handle.createQuery("EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " + query)
                    .mapToMap()
                    .forEach(row -> {
                        for (final Map.Entry<String, Object> entry : row.entrySet()) {
                            result.append(entry.getValue()).append("\n");
                        }
                    });
            return result.toString();
        });
    }

}
