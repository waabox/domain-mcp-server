package co.fanki.domainmcp.project.domain;

import co.fanki.domainmcp.shared.DomainException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for Project aggregate.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class ProjectTest {

    @Test
    void whenCreatingProject_givenValidData_shouldCreateWithPendingStatus() {
        final RepositoryUrl url = RepositoryUrl.of(
                "https://github.com/example/repo.git");

        final Project project = Project.create("Test Project", url);

        assertNotNull(project.id());
        assertEquals("Test Project", project.name());
        assertEquals(url, project.repositoryUrl());
        assertEquals("main", project.defaultBranch());
        assertEquals(ProjectStatus.PENDING, project.status());
        assertNotNull(project.createdAt());
    }

    @Test
    void whenCreatingProject_givenCustomBranch_shouldUseCustomBranch() {
        final RepositoryUrl url = RepositoryUrl.of(
                "https://github.com/example/repo.git");

        final Project project = Project.create("Test Project", url, "develop");

        assertEquals("develop", project.defaultBranch());
    }

    @Test
    void whenStartingAnalysis_givenPendingStatus_shouldTransitionToAnalyzing() {
        final Project project = createTestProject();

        project.startAnalysis();

        assertEquals(ProjectStatus.ANALYZING, project.status());
    }

    @Test
    void whenStartingAnalysis_givenAnalyzingStatus_shouldThrowException() {
        final Project project = createTestProject();
        project.startAnalysis();

        assertThrows(DomainException.class, project::startAnalysis);
    }

    @Test
    void whenCompletingAnalysis_givenAnalyzingStatus_shouldTransitionToAnalyzed() {
        final Project project = createTestProject();
        project.startAnalysis();

        project.analysisCompleted("abc123");

        assertEquals(ProjectStatus.ANALYZED, project.status());
        assertEquals("abc123", project.lastCommitHash());
        assertNotNull(project.lastAnalyzedAt());
    }

    @Test
    void whenStartingAnalysis_givenAnalyzedStatus_shouldTransitionToAnalyzing() {
        final Project project = createAnalyzedProject();

        project.startAnalysis();

        assertEquals(ProjectStatus.ANALYZING, project.status());
    }

    @Test
    void whenStartingAnalysis_givenErrorStatus_shouldTransitionToAnalyzing() {
        final Project project = createTestProject();
        project.startAnalysis();
        project.markError();

        project.startAnalysis();

        assertEquals(ProjectStatus.ANALYZING, project.status());
    }

    @Test
    void whenMarkingError_givenAnalyzingStatus_shouldTransitionToError() {
        final Project project = createTestProject();
        project.startAnalysis();

        project.markError();

        assertEquals(ProjectStatus.ERROR, project.status());
    }

    @Test
    void whenStartingSync_givenAnalyzingStatus_shouldThrowDomainException() {
        final Project project = createTestProject();
        project.startAnalysis();

        assertThrows(DomainException.class, project::startSync);
    }

    @Test
    void whenStartingSync_givenAnalyzedStatus_shouldTransitionToSyncing() {
        final Project project = createAnalyzedProject();

        project.startSync();

        assertEquals(ProjectStatus.SYNCING, project.status());
    }

    @Test
    void whenCompletingSync_givenSyncingStatus_shouldTransitionToAnalyzed() {
        final Project project = createAnalyzedProject();
        project.startSync();

        project.syncCompleted("def456");

        assertEquals(ProjectStatus.ANALYZED, project.status());
        assertEquals("def456", project.lastCommitHash());
        assertNotNull(project.lastAnalyzedAt());
    }

    @Test
    void whenMarkingError_givenSyncingStatus_shouldTransitionToError() {
        final Project project = createAnalyzedProject();
        project.startSync();

        project.markError();

        assertEquals(ProjectStatus.ERROR, project.status());
    }

    @Test
    void whenRenaming_givenValidName_shouldUpdateName() {
        final Project project = createTestProject();

        project.rename("New Name");

        assertEquals("New Name", project.name());
    }

    @Test
    void whenRenaming_givenBlankName_shouldThrowException() {
        final Project project = createTestProject();

        assertThrows(IllegalArgumentException.class,
                () -> project.rename("  "));
    }

    private Project createTestProject() {
        return Project.create("Test",
                RepositoryUrl.of("https://github.com/test/repo.git"));
    }

    private Project createAnalyzedProject() {
        final Project project = createTestProject();
        project.startAnalysis();
        project.analysisCompleted("abc123");
        return project;
    }

}
