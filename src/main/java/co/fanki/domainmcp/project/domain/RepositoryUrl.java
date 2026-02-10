package co.fanki.domainmcp.project.domain;

import co.fanki.domainmcp.shared.Preconditions;
import co.fanki.domainmcp.shared.ValueObject;

import java.util.regex.Pattern;

/**
 * Value object representing a git repository URL.
 *
 * <p>Supports both HTTPS and SSH URLs for git repositories.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class RepositoryUrl implements ValueObject {

    private static final long serialVersionUID = 1L;

    private static final Pattern HTTPS_PATTERN = Pattern.compile(
            "^https://[\\w.-]+(/[\\w.-]+)+\\.git$");
    private static final Pattern SSH_PATTERN = Pattern.compile(
            "^git@[\\w.-]+:[\\w.-]+(/[\\w.-]+)*\\.git$");

    private final String value;

    private RepositoryUrl(final String theValue) {
        Preconditions.requireNonBlank(theValue,
                "Repository URL cannot be null or blank");
        Preconditions.require(isValidUrl(theValue),
                "Invalid repository URL format: " + theValue);
        this.value = theValue;
    }

    /**
     * Creates a RepositoryUrl from a string.
     *
     * @param url the repository URL string
     * @return a RepositoryUrl
     * @throws IllegalArgumentException if the URL is invalid
     */
    public static RepositoryUrl of(final String url) {
        return new RepositoryUrl(url);
    }

    /**
     * Returns the URL value.
     *
     * @return the URL string
     */
    public String value() {
        return value;
    }

    /**
     * Checks if this is an SSH URL.
     *
     * @return true if SSH URL
     */
    public boolean isSsh() {
        return SSH_PATTERN.matcher(value).matches();
    }

    /**
     * Checks if this is an HTTPS URL.
     *
     * @return true if HTTPS URL
     */
    public boolean isHttps() {
        return HTTPS_PATTERN.matcher(value).matches();
    }

    /**
     * Extracts the repository name from the URL.
     *
     * @return the repository name without .git suffix
     */
    public String repositoryName() {
        final int lastSlash = value.lastIndexOf('/');
        final int lastColon = value.lastIndexOf(':');
        final int start = Math.max(lastSlash, lastColon);

        String name = value.substring(start + 1);
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }

    private static boolean isValidUrl(final String url) {
        return HTTPS_PATTERN.matcher(url).matches()
                || SSH_PATTERN.matcher(url).matches();
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final RepositoryUrl that = (RepositoryUrl) obj;
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }

}
