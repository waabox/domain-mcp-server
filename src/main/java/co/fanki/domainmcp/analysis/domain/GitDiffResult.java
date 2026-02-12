package co.fanki.domainmcp.analysis.domain;

import java.util.Collections;
import java.util.Set;

/**
 * Value object holding the result of a git diff between two commits.
 *
 * <p>Captures the set of changed and deleted files relative to the
 * previous analyzed commit, along with the new HEAD commit hash.
 * When the old commit is not found (e.g. after a force push), the
 * {@code fullResyncRequired} flag is set to indicate that all files
 * should be treated as changed.</p>
 *
 * @param newCommitHash the HEAD commit hash of the latest clone
 * @param changedFiles modified, added, or renamed files (new paths)
 * @param deletedFiles deleted files or renamed files (old paths)
 * @param fullResyncRequired true if the old commit was not found
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public record GitDiffResult(
        String newCommitHash,
        Set<String> changedFiles,
        Set<String> deletedFiles,
        boolean fullResyncRequired
) {

    /**
     * Creates a GitDiffResult indicating a full resync is needed.
     *
     * <p>Used when the previously analyzed commit cannot be found in
     * the repository history (e.g. after a force push).</p>
     *
     * @param newCommitHash the current HEAD commit hash
     * @return a GitDiffResult with fullResyncRequired set to true
     */
    public static GitDiffResult fullResync(final String newCommitHash) {
        return new GitDiffResult(
                newCommitHash, Set.of(), Set.of(), true);
    }

    /**
     * Creates a GitDiffResult with the computed diff.
     *
     * @param newCommitHash the current HEAD commit hash
     * @param changedFiles the set of changed file paths
     * @param deletedFiles the set of deleted file paths
     * @return a GitDiffResult with the diff data
     */
    public static GitDiffResult of(
            final String newCommitHash,
            final Set<String> changedFiles,
            final Set<String> deletedFiles) {
        return new GitDiffResult(
                newCommitHash,
                Collections.unmodifiableSet(changedFiles),
                Collections.unmodifiableSet(deletedFiles),
                false);
    }

    /**
     * Checks if a source file was changed or deleted.
     *
     * @param sourceFile the relative source file path
     * @return true if the file was modified, added, or deleted
     */
    public boolean isAffected(final String sourceFile) {
        return fullResyncRequired
                || changedFiles.contains(sourceFile)
                || deletedFiles.contains(sourceFile);
    }
}
