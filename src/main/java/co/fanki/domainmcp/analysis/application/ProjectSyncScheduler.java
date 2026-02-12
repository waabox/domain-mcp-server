package co.fanki.domainmcp.analysis.application;

import co.fanki.domainmcp.project.domain.Project;
import co.fanki.domainmcp.project.domain.ProjectRepository;
import co.fanki.domainmcp.project.domain.ProjectStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scheduled component that runs daily incremental project sync.
 *
 * <p>Iterates over all projects with status {@code ANALYZED} and
 * delegates each to {@link ProjectSyncService#syncProject}. Errors
 * are caught per project so one failure does not block others.</p>
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

    private final ProjectRepository projectRepository;
    private final ProjectSyncService projectSyncService;

    /**
     * Creates a new ProjectSyncScheduler.
     *
     * @param theProjectRepository the project repository
     * @param theProjectSyncService the sync service
     */
    public ProjectSyncScheduler(
            final ProjectRepository theProjectRepository,
            final ProjectSyncService theProjectSyncService) {
        this.projectRepository = theProjectRepository;
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

        final List<Project> projects = projectRepository.findByStatus(
                ProjectStatus.ANALYZED);

        if (projects.isEmpty()) {
            LOG.info("No projects eligible for sync");
            return;
        }

        LOG.info("Found {} projects eligible for sync", projects.size());

        int successCount = 0;
        int failureCount = 0;
        int skippedCount = 0;

        for (final Project project : projects) {
            try {
                final ProjectSyncService.SyncResult result =
                        projectSyncService.syncProject(project);

                if (result.success()) {
                    successCount++;
                    LOG.info("Sync succeeded for {}: added={}, updated={},"
                                    + " deleted={}, unchanged={}",
                            project.name(), result.addedClasses(),
                            result.updatedClasses(), result.deletedClasses(),
                            result.unchangedClasses());
                } else {
                    failureCount++;
                    LOG.warn("Sync failed for {}: {}",
                            project.name(), result.errorMessage());
                }

            } catch (final Exception e) {
                failureCount++;
                LOG.error("Unexpected error syncing project {}: {}",
                        project.name(), e.getMessage(), e);
            }
        }

        LOG.info("Scheduled sync complete. Success: {}, Failed: {},"
                        + " Skipped: {}",
                successCount, failureCount, skippedCount);
    }
}
