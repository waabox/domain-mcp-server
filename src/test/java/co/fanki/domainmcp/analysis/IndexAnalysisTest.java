package co.fanki.domainmcp.analysis;

import co.fanki.domainmcp.analysis.domain.SourceClassRepository;
import co.fanki.domainmcp.analysis.domain.SourceMethodRepository;
import co.fanki.domainmcp.project.domain.ProjectRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Index analysis test that validates query execution plans.
 *
 * <p>Run with: mvn test -DAnalizeIndexes=true -Dtest=IndexAnalysisTest</p>
 *
 * <p>Data is loaded from index-analysis-data.sql (100 projects, 200k classes, 1M methods)</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "AnalizeIndexes", matches = "true")
class IndexAnalysisTest {

    private static final String TEST_PROJECT_ID = "5677b1e8-da1b-4dda-a5a5-71668d09f5f4";
    private static final String TEST_CLASS_ID = "b8b003b3-ebfe-9846-fc8e-8c66696648fa";
    private static final String TEST_CLASS_NAME = "co.fanki.project0.controller.Api0Controller";
    private static final String TEST_METHOD_ID = "4f62547f-b341-7a5f-3456-f25ed63fe8e6";

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

    private static boolean dataLoaded = false;

    @BeforeAll
    static void loadData(@Autowired Jdbi jdbi) throws Exception {
        if (dataLoaded) {
            return;
        }

        System.out.println("\n=== Loading test data from SQL file ===");
        final long start = System.currentTimeMillis();

        final ClassPathResource resource = new ClassPathResource("index-analysis-data.sql");

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {

            final StringBuilder statement = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                // Skip comments
                if (line.startsWith("--") || line.trim().isEmpty()) {
                    continue;
                }

                statement.append(line).append("\n");

                // Execute when we hit a semicolon
                if (line.trim().endsWith(";")) {
                    final String sql = statement.toString().trim();
                    if (!sql.isEmpty()) {
                        jdbi.useHandle(handle -> handle.execute(sql));
                    }
                    statement.setLength(0);
                }
            }
        }

        final long elapsed = System.currentTimeMillis() - start;
        System.out.println("=== Data loaded in " + elapsed + "ms ===\n");

        dataLoaded = true;
    }

    @Test
    void analyzeAllQueries() {
        final StringBuilder output = new StringBuilder();

        output.append("\n");
        output.append("=".repeat(80)).append("\n");
        output.append("POSTGRESQL QUERY EXECUTION PLAN ANALYSIS\n");
        output.append("=".repeat(80)).append("\n\n");

        appendTableStats(output);

        output.append("-".repeat(80)).append("\n");
        output.append("## SourceClassRepository Queries\n");
        output.append("-".repeat(80)).append("\n\n");

        appendQueryAnalysis(output, "FIND_BY_ID",
                SourceClassRepository.FIND_BY_ID.replace(":id", "'" + TEST_CLASS_ID + "'"));

        appendQueryAnalysis(output, "FIND_BY_PROJECT_ID",
                SourceClassRepository.FIND_BY_PROJECT_ID
                        .replace(":projectId", "'" + TEST_PROJECT_ID + "'"));

        appendQueryAnalysis(output, "FIND_BY_FULL_CLASS_NAME",
                SourceClassRepository.FIND_BY_FULL_CLASS_NAME
                        .replace(":fullClassName", "'" + TEST_CLASS_NAME + "'"));

        appendQueryAnalysis(output, "FIND_BY_PACKAGE_PREFIX",
                SourceClassRepository.FIND_BY_PACKAGE_PREFIX
                        .replace(":packagePrefix", "'co.fanki.user'")
                        .replace(":packagePattern", "'co.fanki.user.%'"));

        appendQueryAnalysis(output, "COUNT_BY_PROJECT_ID",
                SourceClassRepository.COUNT_BY_PROJECT_ID
                        .replace(":projectId", "'" + TEST_PROJECT_ID + "'"));

        output.append("-".repeat(80)).append("\n");
        output.append("## SourceMethodRepository Queries\n");
        output.append("-".repeat(80)).append("\n\n");

        appendQueryAnalysis(output, "FIND_BY_ID",
                SourceMethodRepository.FIND_BY_ID.replace(":id", "'" + TEST_METHOD_ID + "'"));

        appendQueryAnalysis(output, "FIND_BY_CLASS_ID",
                SourceMethodRepository.FIND_BY_CLASS_ID
                        .replace(":classId", "'" + TEST_CLASS_ID + "'"));

        appendQueryAnalysis(output, "FIND_BY_CLASS_NAME",
                SourceMethodRepository.FIND_BY_CLASS_NAME
                        .replace(":fullClassName", "'" + TEST_CLASS_NAME + "'"));

        appendQueryAnalysis(output, "FIND_BY_CLASS_AND_METHOD_NAME",
                SourceMethodRepository.FIND_BY_CLASS_AND_METHOD_NAME
                        .replace(":fullClassName", "'" + TEST_CLASS_NAME + "'")
                        .replace(":methodName", "'create0'"));

        appendQueryAnalysis(output, "FIND_HTTP_ENDPOINTS_BY_PROJECT_ID",
                SourceMethodRepository.FIND_HTTP_ENDPOINTS_BY_PROJECT_ID
                        .replace(":projectId", "'" + TEST_PROJECT_ID + "'"));

        appendQueryAnalysis(output, "COUNT_ENDPOINTS_BY_PROJECT_ID",
                SourceMethodRepository.COUNT_ENDPOINTS_BY_PROJECT_ID
                        .replace(":projectId", "'" + TEST_PROJECT_ID + "'"));

        output.append("-".repeat(80)).append("\n");
        output.append("## ProjectRepository Queries\n");
        output.append("-".repeat(80)).append("\n\n");

        appendQueryAnalysis(output, "FIND_BY_ID",
                ProjectRepository.FIND_BY_ID.replace(":id", "'" + TEST_PROJECT_ID + "'"));

        appendQueryAnalysis(output, "FIND_BY_REPOSITORY_URL",
                ProjectRepository.FIND_BY_REPOSITORY_URL
                        .replace(":url", "'https://github.com/test/project-0.git'"));

        appendQueryAnalysis(output, "FIND_ALL", ProjectRepository.FIND_ALL);

        appendQueryAnalysis(output, "FIND_BY_STATUS",
                ProjectRepository.FIND_BY_STATUS.replace(":status", "'PENDING'"));

        appendQueryAnalysis(output, "EXISTS_BY_REPOSITORY_URL",
                ProjectRepository.EXISTS_BY_REPOSITORY_URL
                        .replace(":url", "'https://github.com/test/project-0.git'"));

        output.append("-".repeat(80)).append("\n");
        output.append("## Current Indexes\n");
        output.append("-".repeat(80)).append("\n\n");

        appendIndexList(output);

        output.append("\n");
        output.append("=".repeat(80)).append("\n");
        output.append("END OF ANALYSIS\n");
        output.append("=".repeat(80)).append("\n");

        System.out.println(output);
    }

    private void appendTableStats(final StringBuilder output) {
        output.append("## Dataset Statistics\n\n");

        final List<Map<String, Object>> stats = jdbi.withHandle(handle -> handle
                .createQuery("""
                        SELECT
                            relname as table_name,
                            n_live_tup as row_count,
                            pg_size_pretty(pg_total_relation_size(relid)) as total_size,
                            pg_size_pretty(pg_indexes_size(relid)) as index_size
                        FROM pg_stat_user_tables
                        WHERE schemaname = 'domain_mcp'
                        ORDER BY n_live_tup DESC
                        """)
                .mapToMap()
                .list());

        output.append("| Table | Rows | Total Size | Index Size |\n");
        output.append("|-------|------|------------|------------|\n");
        for (final Map<String, Object> stat : stats) {
            output.append("| ").append(stat.get("table_name"));
            output.append(" | ").append(stat.get("row_count"));
            output.append(" | ").append(stat.get("total_size"));
            output.append(" | ").append(stat.get("index_size"));
            output.append(" |\n");
        }
        output.append("\n");
    }

    private void appendQueryAnalysis(final StringBuilder output,
            final String queryName, final String query) {
        output.append("### ").append(queryName).append("\n\n");
        output.append("```sql\n").append(query.trim()).append("\n```\n\n");
        output.append("```\n");
        output.append(runExplainAnalyze(query));
        output.append("```\n\n");
    }

    private void appendIndexList(final StringBuilder output) {
        final List<Map<String, Object>> indexes = jdbi.withHandle(handle -> handle
                .createQuery("""
                        SELECT indexname, tablename, indexdef
                        FROM pg_indexes
                        WHERE schemaname = 'domain_mcp'
                          AND tablename IN ('projects', 'source_classes', 'source_methods')
                        ORDER BY tablename, indexname
                        """)
                .mapToMap()
                .list());

        for (final Map<String, Object> idx : indexes) {
            output.append("- **").append(idx.get("indexname")).append("**\n");
            output.append("  `").append(idx.get("indexdef")).append("`\n\n");
        }
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
