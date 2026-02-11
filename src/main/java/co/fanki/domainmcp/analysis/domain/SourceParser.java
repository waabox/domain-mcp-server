package co.fanki.domainmcp.analysis.domain;

import co.fanki.domainmcp.shared.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Abstract strategy for building a {@link ProjectGraph} from local source files.
 *
 * <p>Each programming language has its own conventions for file discovery,
 * dependency extraction, and entry point identification. Subclasses implement
 * these language-specific operations while this class provides the template
 * method {@link #parse(Path)} that wires them together into a complete graph.</p>
 *
 * <p>Unlike the previous container-based approach, this parser operates
 * directly on a local filesystem clone of the repository.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public abstract class SourceParser {

    private static final Logger LOG = LoggerFactory.getLogger(
            SourceParser.class);

    /**
     * Returns the language identifier for this parser.
     *
     * <p>Used to parameterize Claude API prompts and differentiate
     * analysis output per language.</p>
     *
     * @return the language name (e.g., "java", "typescript")
     */
    public abstract String language();

    /**
     * Returns the source root path relative to the project root.
     *
     * @return the source root (e.g., "src/main/java" for Java)
     */
    public abstract String sourceRoot();

    /**
     * Discovers all production source files under the source root.
     *
     * @param projectRoot the local project root directory
     * @return list of relative file paths (relative to projectRoot)
     * @throws IOException if file discovery fails
     */
    protected abstract List<Path> discoverFiles(Path projectRoot)
            throws IOException;

    /**
     * Converts a source file path to a language-specific identifier.
     *
     * @param file the source file path (relative to project root)
     * @param sourceRoot the resolved source root path
     * @return the identifier (e.g., FQCN for Java)
     */
    protected abstract String extractIdentifier(Path file, Path sourceRoot);

    /**
     * Extracts the set of internal dependencies from a source file.
     *
     * <p>Only includes dependencies that match known identifiers in the
     * project, filtering out external library imports.</p>
     *
     * @param file the source file to analyze
     * @param knownIdentifiers the set of all known identifiers in the project
     * @return set of dependency identifiers
     * @throws IOException if file reading fails
     */
    protected abstract Set<String> extractDependencies(Path file,
            Set<String> knownIdentifiers) throws IOException;

    /**
     * Checks if a source file is an entry point (controller, listener, etc.).
     *
     * @param file the source file to check
     * @return true if the file is an entry point
     * @throws IOException if file reading fails
     */
    protected abstract boolean isEntryPoint(Path file) throws IOException;

    /**
     * Extracts method parameters from a source file, returning only
     * parameters whose type matches a known project identifier.
     *
     * <p>The returned map keys are method names, and values are ordered
     * lists of parameter type identifiers (FQCNs for Java, dot-separated
     * for Node.js). The list index corresponds to the parameter position
     * among matched parameters.</p>
     *
     * @param file the source file to analyze
     * @param sourceRoot the resolved source root path
     * @param knownIdentifiers all known identifiers in the project
     * @return map of method name to list of known parameter type identifiers
     * @throws IOException if file reading fails
     */
    public abstract Map<String, List<String>> extractMethodParameters(
            Path file, Path sourceRoot, Set<String> knownIdentifiers)
            throws IOException;

    /**
     * Builds a {@link ProjectGraph} from a local project clone.
     *
     * <p>Template method that orchestrates file discovery, identifier
     * extraction, dependency resolution, and entry point identification.</p>
     *
     * @param projectRoot the local project root directory
     * @return the built project graph
     * @throws IOException if any file operation fails
     */
    public ProjectGraph parse(final Path projectRoot) throws IOException {
        Preconditions.requireNonNull(projectRoot,
                "Project root is required");

        LOG.info("Parsing project graph from: {}", projectRoot);

        final List<Path> files = discoverFiles(projectRoot);
        LOG.info("Discovered {} source files", files.size());

        if (files.isEmpty()) {
            LOG.warn("No source files found in {}", projectRoot);
            return new ProjectGraph();
        }

        final ProjectGraph graph = new ProjectGraph();
        final Path sourceRootPath = projectRoot.resolve(sourceRoot());

        // Add all nodes first
        for (final Path file : files) {
            final String identifier = extractIdentifier(file, sourceRootPath);
            final String relativePath = projectRoot.relativize(file).toString();
            graph.addNode(identifier, relativePath);
        }

        // Extract dependencies using known identifiers
        final Set<String> knownIdentifiers = graph.identifiers();
        for (final Path file : files) {
            final String fromId = extractIdentifier(file, sourceRootPath);
            final Set<String> deps = extractDependencies(file,
                    knownIdentifiers);
            for (final String dep : deps) {
                graph.addDependency(fromId, dep);
            }
        }

        // Identify entry points
        for (final Path file : files) {
            if (isEntryPoint(file)) {
                final String identifier = extractIdentifier(file,
                        sourceRootPath);
                graph.markAsEntryPoint(identifier);
            }
        }

        LOG.info("Project graph built: {} nodes, {} entry points",
                graph.nodeCount(), graph.entryPointCount());

        return graph;
    }

}
