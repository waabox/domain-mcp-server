package co.fanki.domainmcp.project.domain;

/**
 * Enumeration of possible project statuses.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public enum ProjectStatus {

    /**
     * Project is registered but not yet analyzed.
     */
    PENDING,

    /**
     * Project is currently being analyzed.
     */
    ANALYZING,

    /**
     * Analysis has completed successfully.
     */
    ANALYZED,

    /**
     * An error occurred during analysis.
     */
    ERROR,

    /**
     * Project is currently being synced (incremental update).
     */
    SYNCING;

    /**
     * Checks if the project is in a state where it can be analyzed.
     *
     * @return true if analysis can be started
     */
    public boolean canAnalyze() {
        return this == PENDING || this == ANALYZED || this == ERROR;
    }

    /**
     * Checks if the project is in a state where it can be synced.
     *
     * <p>Only projects that have been fully analyzed at least once
     * can be incrementally synced.</p>
     *
     * @return true if sync can be started
     */
    public boolean canSync() {
        return this == ANALYZED;
    }

    /**
     * Checks if the project is in a processing state.
     *
     * @return true if currently processing
     */
    public boolean isProcessing() {
        return this == ANALYZING || this == SYNCING;
    }

}
