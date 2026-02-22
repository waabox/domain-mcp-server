package co.fanki.domainmcp.project.domain;

import co.fanki.domainmcp.shared.DomainException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link ProjectStateMachine}.
 *
 * <p>Verifies all valid transitions in the project state graph and that
 * invalid transitions are rejected with a {@link DomainException}.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class ProjectStateMachineTest {

    @Test
    void whenTransitioning_givenPendingToAnalyzing_shouldReturnAnalyzing() {
        final ProjectStatus result = ProjectStateMachine.transition(ProjectStatus.PENDING, ProjectStatus.ANALYZING);
        assertEquals(ProjectStatus.ANALYZING, result);
    }

    @Test
    void whenTransitioning_givenAnalyzingToAnalyzed_shouldReturnAnalyzed() {
        final ProjectStatus result = ProjectStateMachine.transition(ProjectStatus.ANALYZING, ProjectStatus.ANALYZED);
        assertEquals(ProjectStatus.ANALYZED, result);
    }

    @Test
    void whenTransitioning_givenAnalyzingToError_shouldReturnError() {
        final ProjectStatus result = ProjectStateMachine.transition(ProjectStatus.ANALYZING, ProjectStatus.ERROR);
        assertEquals(ProjectStatus.ERROR, result);
    }

    @Test
    void whenTransitioning_givenAnalyzedToAnalyzing_shouldReturnAnalyzing() {
        final ProjectStatus result = ProjectStateMachine.transition(ProjectStatus.ANALYZED, ProjectStatus.ANALYZING);
        assertEquals(ProjectStatus.ANALYZING, result);
    }

    @Test
    void whenTransitioning_givenAnalyzedToSyncing_shouldReturnSyncing() {
        final ProjectStatus result = ProjectStateMachine.transition(ProjectStatus.ANALYZED, ProjectStatus.SYNCING);
        assertEquals(ProjectStatus.SYNCING, result);
    }

    @Test
    void whenTransitioning_givenSyncingToAnalyzed_shouldReturnAnalyzed() {
        final ProjectStatus result = ProjectStateMachine.transition(ProjectStatus.SYNCING, ProjectStatus.ANALYZED);
        assertEquals(ProjectStatus.ANALYZED, result);
    }

    @Test
    void whenTransitioning_givenSyncingToError_shouldReturnError() {
        final ProjectStatus result = ProjectStateMachine.transition(ProjectStatus.SYNCING, ProjectStatus.ERROR);
        assertEquals(ProjectStatus.ERROR, result);
    }

    @Test
    void whenTransitioning_givenErrorToAnalyzing_shouldReturnAnalyzing() {
        final ProjectStatus result = ProjectStateMachine.transition(ProjectStatus.ERROR, ProjectStatus.ANALYZING);
        assertEquals(ProjectStatus.ANALYZING, result);
    }

    @Test
    void whenTransitioning_givenErrorToSyncing_shouldReturnSyncing() {
        final ProjectStatus result = ProjectStateMachine.transition(ProjectStatus.ERROR, ProjectStatus.SYNCING);
        assertEquals(ProjectStatus.SYNCING, result);
    }

    @Test
    void whenTransitioning_givenPendingToAnalyzed_shouldThrowDomainException() {
        final DomainException exception = assertThrows(DomainException.class, () ->
                ProjectStateMachine.transition(ProjectStatus.PENDING, ProjectStatus.ANALYZED));
        assertEquals("PROJECT_INVALID_TRANSITION", exception.getErrorCode());
    }

    @Test
    void whenTransitioning_givenAnalyzedToError_shouldThrowDomainException() {
        final DomainException exception = assertThrows(DomainException.class, () ->
                ProjectStateMachine.transition(ProjectStatus.ANALYZED, ProjectStatus.ERROR));
        assertEquals("PROJECT_INVALID_TRANSITION", exception.getErrorCode());
    }

    @Test
    void whenTransitioning_givenErrorToAnalyzed_shouldThrowDomainException() {
        final DomainException exception = assertThrows(DomainException.class, () ->
                ProjectStateMachine.transition(ProjectStatus.ERROR, ProjectStatus.ANALYZED));
        assertEquals("PROJECT_INVALID_TRANSITION", exception.getErrorCode());
    }

    @Test
    void whenTransitioning_givenPendingToSyncing_shouldThrowDomainException() {
        final DomainException exception = assertThrows(DomainException.class, () ->
                ProjectStateMachine.transition(ProjectStatus.PENDING, ProjectStatus.SYNCING));
        assertEquals("PROJECT_INVALID_TRANSITION", exception.getErrorCode());
    }

    @Test
    void whenTransitioning_givenNullFrom_shouldThrowNullPointerException() {
        assertThrows(NullPointerException.class, () ->
                ProjectStateMachine.transition(null, ProjectStatus.ANALYZING));
    }

}
