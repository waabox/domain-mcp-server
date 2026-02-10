package co.fanki.domainmcp.project.domain;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for ProjectRepository using TestContainers.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class ProjectRepositoryIntegrationTest {

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

    private ProjectRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ProjectRepository(jdbi);
        jdbi.useHandle(handle -> handle.execute("DELETE FROM projects"));
    }

    @Test
    void whenSavingProject_givenValidProject_shouldPersist() {
        final Project project = createTestProject();

        repository.save(project);

        final Optional<Project> found = repository.findById(project.id());
        assertTrue(found.isPresent());
        assertEquals(project.name(), found.get().name());
    }

    @Test
    void whenFindingByUrl_givenExistingUrl_shouldReturnProject() {
        final Project project = createTestProject();
        repository.save(project);

        final Optional<Project> found = repository.findByRepositoryUrl(
                project.repositoryUrl());

        assertTrue(found.isPresent());
        assertEquals(project.id(), found.get().id());
    }

    @Test
    void whenFindingByUrl_givenNonExistingUrl_shouldReturnEmpty() {
        final RepositoryUrl url = RepositoryUrl.of(
                "https://github.com/nonexistent/repo.git");

        final Optional<Project> found = repository.findByRepositoryUrl(url);

        assertFalse(found.isPresent());
    }

    @Test
    void whenUpdatingProject_givenExistingProject_shouldUpdateFields() {
        final Project project = createTestProject();
        repository.save(project);

        project.startAnalysis();
        project.analysisCompleted("abc123");
        repository.update(project);

        final Optional<Project> found = repository.findById(project.id());
        assertTrue(found.isPresent());
        assertEquals(ProjectStatus.ANALYZED, found.get().status());
        assertNotNull(found.get().lastAnalyzedAt());
        assertEquals("abc123", found.get().lastCommitHash());
    }

    @Test
    void whenFindingAll_givenMultipleProjects_shouldReturnAll() {
        repository.save(createTestProject());
        repository.save(Project.create("Project 2",
                RepositoryUrl.of("https://github.com/test/repo2.git")));

        final List<Project> projects = repository.findAll();

        assertEquals(2, projects.size());
    }

    @Test
    void whenFindingByStatus_givenMatchingStatus_shouldReturnFiltered() {
        final Project pending = createTestProject();
        repository.save(pending);

        final Project analyzed = Project.create("Analyzed",
                RepositoryUrl.of("https://github.com/test/analyzed.git"));
        analyzed.startAnalysis();
        analyzed.analysisCompleted("def456");
        repository.save(analyzed);

        final List<Project> pendingProjects = repository.findByStatus(
                ProjectStatus.PENDING);

        assertEquals(1, pendingProjects.size());
        assertEquals(pending.id(), pendingProjects.get(0).id());
    }

    @Test
    void whenDeletingProject_givenExistingProject_shouldRemove() {
        final Project project = createTestProject();
        repository.save(project);

        repository.delete(project.id());

        assertFalse(repository.findById(project.id()).isPresent());
    }

    @Test
    void whenCheckingExists_givenExistingUrl_shouldReturnTrue() {
        final Project project = createTestProject();
        repository.save(project);

        assertTrue(repository.existsByRepositoryUrl(project.repositoryUrl()));
    }

    @Test
    void whenCheckingExists_givenNonExistingUrl_shouldReturnFalse() {
        final RepositoryUrl url = RepositoryUrl.of(
                "https://github.com/nonexistent/repo.git");

        assertFalse(repository.existsByRepositoryUrl(url));
    }

    private Project createTestProject() {
        return Project.create("Test Project",
                RepositoryUrl.of("https://github.com/test/repo.git"));
    }

}
