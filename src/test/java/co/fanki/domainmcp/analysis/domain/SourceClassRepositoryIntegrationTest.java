package co.fanki.domainmcp.analysis.domain;

import co.fanki.domainmcp.project.domain.Project;
import co.fanki.domainmcp.project.domain.ProjectRepository;
import co.fanki.domainmcp.project.domain.RepositoryUrl;
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
 * Integration tests for SourceClassRepository using TestContainers.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class SourceClassRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private Jdbi jdbi;

    private SourceClassRepository repository;
    private ProjectRepository projectRepository;
    private Project testProject;

    @BeforeEach
    void setUp() {
        repository = new SourceClassRepository(jdbi);
        projectRepository = new ProjectRepository(jdbi);

        jdbi.useHandle(handle -> {
            handle.execute("DELETE FROM source_methods");
            handle.execute("DELETE FROM source_classes");
            handle.execute("DELETE FROM projects");
        });

        testProject = Project.create("Test Project",
                RepositoryUrl.of("https://github.com/test/repo.git"));
        projectRepository.save(testProject);
    }

    @Test
    void whenSavingClass_givenValidClass_shouldPersist() {
        final SourceClass sourceClass = createTestClass(
                "co.fanki.user.UserService");

        repository.save(sourceClass);

        final Optional<SourceClass> found = repository.findById(sourceClass.id());
        assertTrue(found.isPresent());
        assertEquals(sourceClass.fullClassName(), found.get().fullClassName());
        assertEquals(ClassType.SERVICE, found.get().classType());
    }

    @Test
    void whenFindingByFullClassName_givenExistingClass_shouldReturn() {
        final SourceClass sourceClass = createTestClass(
                "co.fanki.user.UserService");
        repository.save(sourceClass);

        final Optional<SourceClass> found = repository.findByFullClassName(
                "co.fanki.user.UserService");

        assertTrue(found.isPresent());
        assertEquals(sourceClass.id(), found.get().id());
    }

    @Test
    void whenFindingByFullClassName_givenNonExisting_shouldReturnEmpty() {
        final Optional<SourceClass> found = repository.findByFullClassName(
                "co.fanki.nonexistent.Class");

        assertFalse(found.isPresent());
    }

    @Test
    void whenFindingByProjectId_givenProjectWithClasses_shouldReturnAll() {
        repository.save(createTestClass("co.fanki.user.UserService"));
        repository.save(createTestClass("co.fanki.user.UserController"));
        repository.save(createTestClass("co.fanki.order.OrderService"));

        final List<SourceClass> classes = repository.findByProjectId(
                testProject.id());

        assertEquals(3, classes.size());
    }

    @Test
    void whenFindingByPackagePrefix_givenMatchingPackage_shouldReturnFiltered() {
        repository.save(createTestClass("co.fanki.user.UserService"));
        repository.save(createTestClass("co.fanki.user.UserController"));
        repository.save(createTestClass("co.fanki.order.OrderService"));

        final List<SourceClass> userClasses = repository.findByPackagePrefix(
                "co.fanki.user");

        assertEquals(2, userClasses.size());
    }

    @Test
    void whenCountingByProjectId_givenClassesExist_shouldReturnCount() {
        repository.save(createTestClass("co.fanki.user.UserService"));
        repository.save(createTestClass("co.fanki.user.UserController"));

        final long count = repository.countByProjectId(testProject.id());

        assertEquals(2, count);
    }

    @Test
    void whenSavingAll_givenMultipleClasses_shouldPersistAll() {
        final List<SourceClass> classes = List.of(
                createTestClass("co.fanki.user.UserService"),
                createTestClass("co.fanki.user.UserController"),
                createTestClass("co.fanki.order.OrderService"));

        repository.saveAll(classes);

        final long count = repository.countByProjectId(testProject.id());
        assertEquals(3, count);
    }

    @Test
    void whenDeletingByProjectId_givenClassesExist_shouldRemoveAll() {
        repository.save(createTestClass("co.fanki.user.UserService"));
        repository.save(createTestClass("co.fanki.user.UserController"));

        repository.deleteByProjectId(testProject.id());

        final long count = repository.countByProjectId(testProject.id());
        assertEquals(0, count);
    }

    @Test
    void whenDeleting_givenExistingClass_shouldRemove() {
        final SourceClass sourceClass = createTestClass(
                "co.fanki.user.UserService");
        repository.save(sourceClass);

        repository.delete(sourceClass.id());

        assertFalse(repository.findById(sourceClass.id()).isPresent());
    }

    private SourceClass createTestClass(final String fullClassName) {
        return SourceClass.create(
                testProject.id(),
                fullClassName,
                ClassType.SERVICE,
                "Test class description",
                "src/main/java/" + fullClassName.replace('.', '/') + ".java");
    }

}
