package co.fanki.domainmcp.project.domain;

import co.fanki.domainmcp.shared.DomainException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Centralizes all valid project status transitions.
 *
 * <p>The state graph defines which status changes are permitted within
 * the project lifecycle. Any attempt to move a project to a status that
 * is not reachable from the current one is rejected with a
 * {@link DomainException}.</p>
 *
 * <p>Valid transitions:</p>
 * <pre>
 *   PENDING   → ANALYZING
 *   ANALYZING → ANALYZED, ERROR
 *   ANALYZED  → ANALYZING, SYNCING
 *   SYNCING   → ANALYZED, ERROR
 *   ERROR     → ANALYZING, SYNCING
 * </pre>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class ProjectStateMachine {

    private static final Map<ProjectStatus, Set<ProjectStatus>> TRANSITIONS;

    static {
        TRANSITIONS = new EnumMap<>(ProjectStatus.class);
        TRANSITIONS.put(ProjectStatus.PENDING,   EnumSet.of(ProjectStatus.ANALYZING));
        TRANSITIONS.put(ProjectStatus.ANALYZING, EnumSet.of(ProjectStatus.ANALYZED, ProjectStatus.ERROR));
        TRANSITIONS.put(ProjectStatus.ANALYZED,  EnumSet.of(ProjectStatus.ANALYZING, ProjectStatus.SYNCING));
        TRANSITIONS.put(ProjectStatus.SYNCING,   EnumSet.of(ProjectStatus.ANALYZED, ProjectStatus.ERROR));
        TRANSITIONS.put(ProjectStatus.ERROR,     EnumSet.of(ProjectStatus.ANALYZING, ProjectStatus.SYNCING));
    }

    private ProjectStateMachine() {
    }

    /**
     * Validates a status transition and returns the target status if it is permitted.
     *
     * <p>Consults the internal transition graph and throws a {@link DomainException}
     * when the requested move is not a valid edge in that graph.</p>
     *
     * @param from the current project status
     * @param to   the desired target status
     * @return {@code to} when the transition is valid
     * @throws DomainException      with code {@code PROJECT_INVALID_TRANSITION} when the
     *                              transition is not permitted
     * @throws NullPointerException if {@code from} or {@code to} is null
     */
    public static ProjectStatus transition(final ProjectStatus from, final ProjectStatus to) {
        if (from == null || to == null) {
            throw new NullPointerException("from and to must not be null");
        }
        final Set<ProjectStatus> allowed = TRANSITIONS.getOrDefault(from, EnumSet.noneOf(ProjectStatus.class));
        if (!allowed.contains(to)) {
            throw new DomainException(
                    "Invalid transition: " + from + " → " + to,
                    "PROJECT_INVALID_TRANSITION"
            );
        }
        return to;
    }

}
