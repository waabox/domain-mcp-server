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
     * Checks if the project is in a processing state.
     *
     * @return true if currently processing
     */
    public boolean isProcessing() {
        return this == ANALYZING || this == SYNCING;
    }

}
