package co.fanki.domainmcp.container.domain;

import java.util.Set;

/**
 * Enumeration of container images for different programming languages.
 *
 * <p>Each image is configured with Claude Code pre-installed for
 * code analysis.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public enum ContainerImage {

    /**
     * Java 21 with Maven/Gradle support.
     */
    JAVA("domain-mcp/java",
            Set.of(".java"),
            Set.of("pom.xml", "build.gradle", "build.gradle.kts")),

    /**
     * Node.js 20 with npm/yarn support.
     */
    NODE("domain-mcp/node",
            Set.of(".js", ".ts", ".jsx", ".tsx"),
            Set.of("package.json")),

    /**
     * Python 3.12 with pip support.
     */
    PYTHON("domain-mcp/python",
            Set.of(".py"),
            Set.of("requirements.txt", "pyproject.toml", "setup.py")),

    /**
     * Go 1.22 with modules support.
     */
    GO("domain-mcp/go",
            Set.of(".go"),
            Set.of("go.mod")),

    /**
     * Generic container for unknown languages.
     */
    GENERIC("domain-mcp/generic",
            Set.of(),
            Set.of());

    private final String imageName;
    private final Set<String> fileExtensions;
    private final Set<String> markerFiles;

    ContainerImage(final String theImageName,
            final Set<String> theFileExtensions,
            final Set<String> theMarkerFiles) {
        this.imageName = theImageName;
        this.fileExtensions = theFileExtensions;
        this.markerFiles = theMarkerFiles;
    }

    /**
     * Returns the Docker image name.
     *
     * @return the image name with tag
     */
    public String imageName() {
        return imageName;
    }

    /**
     * Returns the file extensions associated with this language.
     *
     * @return set of file extensions (e.g., ".java", ".py")
     */
    public Set<String> fileExtensions() {
        return fileExtensions;
    }

    /**
     * Returns the marker files that indicate this language.
     *
     * @return set of marker file names (e.g., "pom.xml", "package.json")
     */
    public Set<String> markerFiles() {
        return markerFiles;
    }

    /**
     * Checks if this image supports the given file extension.
     *
     * @param extension the file extension to check
     * @return true if supported
     */
    public boolean supportsExtension(final String extension) {
        return fileExtensions.contains(extension.toLowerCase());
    }

    /**
     * Checks if a marker file indicates this language.
     *
     * @param fileName the file name to check
     * @return true if it's a marker file
     */
    public boolean hasMarkerFile(final String fileName) {
        return markerFiles.contains(fileName);
    }

}
