package co.fanki.domainmcp.analysis.application;

import co.fanki.domainmcp.analysis.application.ProjectSyncService.SyncAllResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled component that runs daily incremental project sync.
 *
 * <p>Delegates to {@link ProjectSyncService#syncAllProjects()} which
 * iterates over all projects with status {@code ANALYZED} and syncs
 * each one. Errors are caught per project so one failure does not
 * block others.</p>
 *
 * <p>Opt-in via {@code sync.enabled=true}. Disabled by default.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Component
@ConditionalOnProperty(
        name = "sync.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class ProjectSyncScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(
            ProjectSyncScheduler.class);

    private final ProjectSyncService projectSyncService;

    /**
     * Creates a new ProjectSyncScheduler.
     *
     * @param theProjectSyncService the sync service
     */
    public ProjectSyncScheduler(
            final ProjectSyncService theProjectSyncService) {
        this.projectSyncService = theProjectSyncService;
    }

    /**
     * Runs the incremental sync for all eligible projects.
     *
     * <p>Only projects with status {@code ANALYZED} are synced.
     * Projects currently in {@code ANALYZING} or {@code SYNCING}
     * status are skipped to avoid concurrent operations.</p>
     */
    @Scheduled(cron = "${sync.cron:0 0 2 * * *}")
    public void syncAllProjects() {
        LOG.info("Starting scheduled project sync");

        final SyncAllResult result =
                projectSyncService.syncAllProjects();

        LOG.info("Scheduled sync complete. Success: {}, Failed: {}",
                result.successCount(), result.failureCount());
    }
}
