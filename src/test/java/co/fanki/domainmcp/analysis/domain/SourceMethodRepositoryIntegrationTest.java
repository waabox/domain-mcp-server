package co.fanki.domainmcp.analysis.domain;

import co.fanki.domainmcp.project.domain.Project;
import co.fanki.domainmcp.project.domain.ProjectRepository;
import co.fanki.domainmcp.project.domain.RepositoryUrl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for SourceMethodRepository using TestContainers.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class SourceMethodRepositoryIntegrationTest {

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

    private SourceMethodRepository methodRepository;
    private SourceClassRepository classRepository;
    private ProjectRepository projectRepository;
    private Project testProject;
    private SourceClass testClass;

    @BeforeEach
    void setUp() {
        methodRepository = new SourceMethodRepository(jdbi, objectMapper);
        classRepository = new SourceClassRepository(jdbi);
        projectRepository = new ProjectRepository(jdbi);

        jdbi.useHandle(handle -> {
            handle.execute("DELETE FROM source_methods");
            handle.execute("DELETE FROM source_classes");
            handle.execute("DELETE FROM projects");
        });

        testProject = Project.create("Test Project",
                RepositoryUrl.of("https://github.com/test/repo.git"));
        projectRepository.save(testProject);

        testClass = SourceClass.create(
                testProject.id(),
                "co.fanki.user.UserService",
                ClassType.SERVICE,
                "User service",
                "src/main/java/co/fanki/user/UserService.java",
                "abc123");
        classRepository.save(testClass);
    }

    @Test
    void whenSavingMethod_givenValidMethod_shouldPersist() {
        final SourceMethod method = createTestMethod("createUser");

        methodRepository.save(method);

        final Optional<SourceMethod> found = methodRepository.findById(method.id());
        assertTrue(found.isPresent());
        assertEquals("createUser", found.get().methodName());
    }

    @Test
    void whenSavingMethod_givenBusinessLogicList_shouldPersistAndRetrieve() {
        final SourceMethod method = SourceMethod.create(
                testClass.id(),
                "createUser",
                "Creates a new user",
                List.of("Validates input", "Saves to DB", "Publishes event"),
                List.of("ValidationException"),
                "POST",
                "/api/users",
                45);

        methodRepository.save(method);

        final Optional<SourceMethod> found = methodRepository.findById(method.id());
        assertTrue(found.isPresent());
        assertEquals(3, found.get().businessLogic().size());
        assertEquals("Validates input", found.get().businessLogic().get(0));
        assertEquals(1, found.get().exceptions().size());
    }

    @Test
    void whenFindingByClassId_givenMethodsExist_shouldReturnAll() {
        methodRepository.save(createTestMethod("createUser"));
        methodRepository.save(createTestMethod("updateUser"));
        methodRepository.save(createTestMethod("deleteUser"));

        final List<SourceMethod> methods = methodRepository.findByClassId(
                testClass.id());

        assertEquals(3, methods.size());
    }

    @Test
    void whenFindingByClassName_givenMethodsExist_shouldReturnAll() {
        methodRepository.save(createTestMethod("createUser"));
        methodRepository.save(createTestMethod("updateUser"));

        final List<SourceMethod> methods = methodRepository.findByClassName(
                "co.fanki.user.UserService");

        assertEquals(2, methods.size());
    }

    @Test
    void whenFindingByClassNameAndMethodName_givenExists_shouldReturn() {
        methodRepository.save(createTestMethod("createUser"));

        final Optional<SourceMethod> found = methodRepository
                .findByClassNameAndMethodName(
                        "co.fanki.user.UserService", "createUser");

        assertTrue(found.isPresent());
        assertEquals("createUser", found.get().methodName());
    }

    @Test
    void whenFindingByClassNameAndMethodName_givenNotExists_shouldReturnEmpty() {
        final Optional<SourceMethod> found = methodRepository
                .findByClassNameAndMethodName(
                        "co.fanki.user.UserService", "nonExistent");

        assertFalse(found.isPresent());
    }

    @Test
    void whenFindingHttpEndpoints_givenEndpointsExist_shouldReturnFiltered() {
        methodRepository.save(createHttpMethod("create", "POST", "/api/users"));
        methodRepository.save(createHttpMethod("list", "GET", "/api/users"));
        methodRepository.save(createTestMethod("helper"));

        final List<SourceMethod> endpoints = methodRepository
                .findHttpEndpointsByProjectId(testProject.id());

        assertEquals(2, endpoints.size());
    }

    @Test
    void whenCountingEndpoints_givenEndpointsExist_shouldReturnCount() {
        methodRepository.save(createHttpMethod("create", "POST", "/api/users"));
        methodRepository.save(createHttpMethod("list", "GET", "/api/users"));
        methodRepository.save(createTestMethod("helper"));

        final long count = methodRepository.countEndpointsByProjectId(
                testProject.id());

        assertEquals(2, count);
    }

    @Test
    void whenSavingAll_givenMultipleMethods_shouldPersistAll() {
        final List<SourceMethod> methods = List.of(
                createTestMethod("createUser"),
                createTestMethod("updateUser"),
                createTestMethod("deleteUser"));

        methodRepository.saveAll(methods);

        final List<SourceMethod> found = methodRepository.findByClassId(
                testClass.id());
        assertEquals(3, found.size());
    }

    @Test
    void whenDeleting_givenExistingMethod_shouldRemove() {
        final SourceMethod method = createTestMethod("createUser");
        methodRepository.save(method);

        methodRepository.delete(method.id());

        assertFalse(methodRepository.findById(method.id()).isPresent());
    }

    @Test
    void whenDeletingByClassId_givenMethodsExist_shouldRemoveAll() {
        methodRepository.save(createTestMethod("createUser"));
        methodRepository.save(createTestMethod("updateUser"));

        methodRepository.deleteByClassId(testClass.id());

        final List<SourceMethod> methods = methodRepository.findByClassId(
                testClass.id());
        assertTrue(methods.isEmpty());
    }

    private SourceMethod createTestMethod(final String methodName) {
        return SourceMethod.create(
                testClass.id(),
                methodName,
                "Test method description",
                null, null,
                null, null, null);
    }

    private SourceMethod createHttpMethod(final String methodName,
            final String httpMethod, final String httpPath) {
        return SourceMethod.create(
                testClass.id(),
                methodName,
                "HTTP endpoint",
                null, null,
                httpMethod, httpPath, null);
    }

}
