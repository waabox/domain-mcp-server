package co.fanki.domainmcp.analysis.domain.java;

import co.fanki.domainmcp.analysis.domain.SourceParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Java-specific implementation of {@link SourceParser}.
 *
 * <p>Discovers .java source files under src/main/java, extracts import
 * statements to build the dependency graph, and identifies entry points
 * based on Spring annotations.</p>
 *
 * <p>Entry point patterns:</p>
 * <ul>
 *   <li>{@code @RestController}, {@code @Controller} - HTTP endpoints</li>
 *   <li>{@code @KafkaListener} - Kafka consumers</li>
 *   <li>{@code @Scheduled}, {@code @EventListener} - Background jobs</li>
 *   <li>{@code @SpringBootApplication} - Application bootstrap</li>
 * </ul>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public class JavaSourceParser extends SourceParser {

    private static final Logger LOG = LoggerFactory.getLogger(
            JavaSourceParser.class);

    private static final String SOURCE_ROOT = "src/main/java";

    private static final Set<String> ENTRY_POINT_ANNOTATIONS = Set.of(
            "@RestController",
            "@Controller",
            "@KafkaListener",
            "@Scheduled",
            "@EventListener",
            "@SpringBootApplication"
    );

    /** {@inheritDoc} */
    @Override
    public String language() {
        return "java";
    }

    /** {@inheritDoc} */
    @Override
    protected String sourceRoot() {
        return SOURCE_ROOT;
    }

    /**
     * Discovers all .java files under src/main/java.
     *
     * @param projectRoot the local project root directory
     * @return list of .java file paths
     * @throws IOException if file discovery fails
     */
    @Override
    protected List<Path> discoverFiles(final Path projectRoot)
            throws IOException {

        final Path sourceDir = projectRoot.resolve(SOURCE_ROOT);

        if (!Files.isDirectory(sourceDir)) {
            LOG.warn("Source root not found: {}", sourceDir);
            return List.of();
        }

        try (Stream<Path> walk = Files.walk(sourceDir)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Converts a .java file path to a fully-qualified class name (FQCN).
     *
     * <p>Strips the source root prefix and .java suffix, then replaces
     * path separators with dots.</p>
     *
     * @param file the .java file path
     * @param sourceRoot the resolved source root path
     * @return the FQCN (e.g., "co.fanki.checkout.Cart")
     */
    @Override
    protected String extractIdentifier(final Path file,
            final Path sourceRoot) {

        final Path relative = sourceRoot.relativize(file);
        String result = relative.toString();

        if (result.endsWith(".java")) {
            result = result.substring(0, result.length() - 5);
        }

        return result.replace('/', '.').replace('\\', '.');
    }

    /**
     * Extracts internal import dependencies from a Java source file.
     *
     * <p>Parses import statements and filters to only include imports
     * that match known identifiers within the project.</p>
     *
     * @param file the Java source file
     * @param knownIdentifiers all known identifiers in the project
     * @return set of dependency identifiers (FQCNs)
     * @throws IOException if file reading fails
     */
    @Override
    protected Set<String> extractDependencies(final Path file,
            final Set<String> knownIdentifiers) throws IOException {

        final Set<String> deps = new HashSet<>();
        final List<String> lines = Files.readAllLines(file);

        for (final String line : lines) {
            final String trimmed = line.trim();

            // Stop scanning after the class/interface declaration
            if (isClassDeclaration(trimmed)) {
                break;
            }

            final String importedClass = parseImportLine(trimmed);
            if (importedClass != null
                    && knownIdentifiers.contains(importedClass)) {
                deps.add(importedClass);
            }
        }

        return deps;
    }

    /**
     * Checks if a Java source file is an entry point by scanning for
     * Spring annotations that indicate controllers, listeners, or jobs.
     *
     * @param file the Java source file
     * @return true if the file contains entry point annotations
     * @throws IOException if file reading fails
     */
    @Override
    protected boolean isEntryPoint(final Path file) throws IOException {
        final List<String> lines = Files.readAllLines(file);

        for (final String line : lines) {
            final String trimmed = line.trim();
            for (final String annotation : ENTRY_POINT_ANNOTATIONS) {
                if (trimmed.startsWith(annotation)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Parses an import line to extract the fully-qualified class name.
     *
     * @param importLine the import statement
     * @return the FQCN, or null if not parseable or a wildcard import
     */
    String parseImportLine(final String importLine) {
        if (importLine == null || !importLine.startsWith("import ")) {
            return null;
        }

        String line = importLine.substring("import ".length()).trim();

        // Handle static imports
        final boolean isStatic = line.startsWith("static ");
        if (isStatic) {
            line = line.substring("static ".length()).trim();
        }

        // Remove trailing semicolon and whitespace
        if (line.endsWith(";")) {
            line = line.substring(0, line.length() - 1).trim();
        }

        // Skip wildcard imports
        if (line.endsWith(".*")) {
            return null;
        }

        // For static imports, extract the class part (before the last dot)
        if (isStatic) {
            final int lastDot = line.lastIndexOf('.');
            if (lastDot > 0) {
                line = line.substring(0, lastDot);
            }
        }

        return line.isBlank() ? null : line;
    }

    /**
     * Checks if a line starts a class, interface, enum, or record declaration.
     */
    private boolean isClassDeclaration(final String line) {
        if (line.startsWith("//") || line.startsWith("*")
                || line.startsWith("/*")) {
            return false;
        }
        return line.contains("class ")
                || line.contains("interface ")
                || line.contains("enum ")
                || line.contains("record ");
    }

}
