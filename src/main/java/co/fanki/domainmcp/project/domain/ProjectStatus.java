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
    ERROR;

    /**
     * Checks if the project is in a state where it can be analyzed.
     *
     * @return true if analysis can be started
     */
    public boolean canAnalyze() {
        return this == PENDING || this == ANALYZED || this == ERROR;
    }

    /**
     * Checks if the project is in a processing state.
     *
     * @return true if currently processing
     */
    public boolean isProcessing() {
        return this == ANALYZING;
    }

}
