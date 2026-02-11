package co.fanki.domainmcp.analysis.application;

import co.fanki.domainmcp.analysis.domain.ClassType;
import co.fanki.domainmcp.analysis.domain.ClaudeApiClient;
import co.fanki.domainmcp.analysis.domain.ClaudeApiClient.BatchClassInput;
import co.fanki.domainmcp.analysis.domain.ClaudeApiClient.ClassAnalysisResult;
import co.fanki.domainmcp.analysis.domain.ClaudeApiClient.MethodAnalysisResult;
import co.fanki.domainmcp.analysis.domain.ProjectGraph;
import co.fanki.domainmcp.analysis.domain.GraphService;
import co.fanki.domainmcp.analysis.domain.SourceClass;
import co.fanki.domainmcp.analysis.domain.SourceClassRepository;
import co.fanki.domainmcp.analysis.domain.SourceMethod;
import co.fanki.domainmcp.analysis.domain.SourceMethodRepository;
import co.fanki.domainmcp.analysis.domain.SourceParser;
import co.fanki.domainmcp.analysis.domain.java.JavaSourceParser;
import co.fanki.domainmcp.analysis.domain.nodejs.NodeJsSourceParser;
import co.fanki.domainmcp.project.domain.Project;
import co.fanki.domainmcp.project.domain.ProjectRepository;
import co.fanki.domainmcp.project.domain.RepositoryUrl;
import co.fanki.domainmcp.shared.DomainException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Application service for code context operations.
 *
 * <p>Provides functionality to analyze projects using class-by-class
 * Claude API analysis and retrieve context about classes and methods
 * for Datadog stack trace correlation.</p>
 *
 * <p>Analysis flow: Clone (JGit) -> Read README -> Parse imports ->
 * Build graph -> Analyze class-by-class (Claude API) -> Persist graph
 * + results.</p>
 *
 * <p>Query flow: Stack trace frame -> SQL lookup -> Graph neighbors ->
 * Return full context.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Service
public class CodeContextService {

    private static final Logger LOG = LoggerFactory.getLogger(
            CodeContextService.class);

    private static final int MAX_README_LENGTH = 10_000;

    private static final int BATCH_SIZE = 20;

    private final ProjectRepository projectRepository;
    private final SourceClassRepository sourceClassRepository;
    private final SourceMethodRepository sourceMethodRepository;
    private final GraphService graphCache;
    private final ClaudeApiClient claudeApiClient;
    private final String cloneBasePath;

    /**
     * Creates a new CodeContextService.
     *
     * @param theProjectRepository the project repository
     * @param theSourceClassRepository the source class repository
     * @param theSourceMethodRepository the source method repository
     * @param theGraphCache the project graph cache
     * @param theClaudeApiKey the Claude API key
     * @param theCloneBasePath the base path for cloning repositories
     */
    public CodeContextService(
            final ProjectRepository theProjectRepository,
            final SourceClassRepository theSourceClassRepository,
            final SourceMethodRepository theSourceMethodRepository,
            final GraphService theGraphCache,
            @Value("${claude.api-key}") final String theClaudeApiKey,
            @Value("${git.clone-base-path:/tmp/domain-mcp-repos}")
            final String theCloneBasePath) {
        this.projectRepository = theProjectRepository;
        this.sourceClassRepository = theSourceClassRepository;
        this.sourceMethodRepository = theSourceMethodRepository;
        this.graphCache = theGraphCache;
        this.claudeApiClient = new ClaudeApiClient(theClaudeApiKey);
        this.cloneBasePath = theCloneBasePath;
    }

    /**
     * Analyzes a project using class-by-class Claude API analysis.
     *
     * <p>Clones the repository locally, builds a dependency graph by
     * parsing imports (no LLM), then analyzes each class individually
     * via the Claude API. Persists the graph as JSON for query-time
     * neighbor resolution.</p>
     *
     * @param repositoryUrl the git repository URL
     * @param branch the branch to analyze (optional, defaults to main)
     * @return the analysis result summary
     */
    public AnalysisResult analyzeProject(final String repositoryUrl,
            final String branch) {

        LOG.info("Starting project analysis for: {}", repositoryUrl);

        final RepositoryUrl repoUrl = RepositoryUrl.of(repositoryUrl);
        final String branchName = branch != null ? branch : "main";

        final Project project = prepareProject(repoUrl, branchName);

        Path cloneDir = null;

        try {
            // Clone repository locally
            cloneDir = cloneRepository(repoUrl, branchName);

            // Read README for context
            final String readme = readReadme(cloneDir);
            if (readme != null) {
                project.updateDescription(readme.substring(0,
                        Math.min(readme.length(), 500)));
            }

            // Build dependency graph by parsing imports (no LLM)
            final SourceParser parser = detectParser(cloneDir);
            final ProjectGraph graph = parser.parse(cloneDir);

            LOG.info("Graph built: {} nodes, {} entry points",
                    graph.nodeCount(), graph.entryPointCount());

            // Analyze classes in batches of BATCH_SIZE concurrently
            final List<String> analysisOrder = graph.analysisOrder();
            int classCount = 0;
            int methodCount = 0;
            int failedCount = 0;

            for (int i = 0; i < analysisOrder.size(); i += BATCH_SIZE) {
                final List<String> batch = analysisOrder.subList(
                        i, Math.min(i + BATCH_SIZE, analysisOrder.size()));

                LOG.info("Analyzing batch {}/{} ({} classes)",
                        (i / BATCH_SIZE) + 1,
                        (int) Math.ceil(
                                (double) analysisOrder.size() / BATCH_SIZE),
                        batch.size());

                // Prepare batch inputs by reading source files
                final List<BatchClassInput> inputs = new ArrayList<>();
                for (final String identifier : batch) {
                    final String sourceFile = graph.sourceFile(identifier);
                    final Path filePath = cloneDir.resolve(sourceFile);

                    if (!Files.exists(filePath)) {
                        LOG.warn("Source file not found: {}", sourceFile);
                        continue;
                    }

                    try {
                        final String sourceCode = Files.readString(filePath);
                        inputs.add(new BatchClassInput(
                                sourceCode, identifier, sourceFile,
                                parser.language()));
                    } catch (final IOException e) {
                        LOG.warn("Failed to read source file {}: {}",
                                sourceFile, e.getMessage());
                        failedCount++;
                    }
                }

                // Fire concurrent analysis for the batch
                final List<ClassAnalysisResult> results =
                        claudeApiClient.analyzeBatch(inputs, readme);

                // Process results and persist
                for (final ClassAnalysisResult result : results) {
                    if (!result.success()) {
                        LOG.warn("Analysis failed for {}: {}",
                                result.fullClassName(),
                                result.errorMessage());
                        failedCount++;
                        continue;
                    }

                    final SourceClass sourceClass = SourceClass.create(
                            project.id(),
                            result.fullClassName(),
                            ClassType.fromString(result.classType()),
                            result.description(),
                            result.sourceFile());

                    persistSourceClass(sourceClass);
                    classCount++;

                    graph.bindClassId(
                            result.fullClassName(), sourceClass.id());

                    for (final MethodAnalysisResult method :
                            result.methods()) {
                        final SourceMethod sourceMethod = SourceMethod.create(
                                sourceClass.id(),
                                method.methodName(),
                                method.description(),
                                method.businessLogic(),
                                method.dependencies(),
                                method.exceptions(),
                                method.httpMethod(),
                                method.httpPath(),
                                method.lineNumber());

                        persistSourceMethod(sourceMethod);
                        methodCount++;
                    }
                }
            }

            // Persist graph JSON to project
            final String graphJson = graph.toJson();
            completeAnalysis(project, graphJson);

            // Update cache
            graphCache.put(project.id(), graph);

            final long endpointCount = sourceMethodRepository
                    .countEndpointsByProjectId(project.id());

            LOG.info("Analysis completed. Classes: {}, Methods: {}, "
                            + "Endpoints: {}, Failed: {}",
                    classCount, methodCount, endpointCount, failedCount);

            return new AnalysisResult(
                    true,
                    project.id(),
                    classCount,
                    (int) endpointCount,
                    "Analysis complete");

        } catch (final Exception e) {
            LOG.error("Analysis failed for {}: {}", repositoryUrl,
                    e.getMessage(), e);
            markProjectError(project);
            throw new DomainException(
                    "Analysis failed: " + e.getMessage(),
                    "ANALYSIS_FAILED", e);
        } finally {
            cleanupCloneDir(cloneDir);
        }
    }

    /**
     * Prepares the project for analysis: creates or clears existing data.
     *
     * @param repoUrl the repository URL
     * @param branchName the branch name
     * @return the project ready for analysis
     */
    @Transactional
    Project prepareProject(final RepositoryUrl repoUrl,
            final String branchName) {

        final String projectName = repoUrl.repositoryName();

        Project project = projectRepository.findByRepositoryUrl(repoUrl)
                .orElse(null);

        if (project == null) {
            project = Project.create(projectName, repoUrl, branchName);
            projectRepository.save(project);
            LOG.info("Created new project: {}", project.id());
        } else {
            sourceClassRepository.deleteByProjectId(project.id());
            LOG.info("Cleared existing analysis data for project: {}",
                    project.id());
        }

        project.startAnalysis();
        projectRepository.update(project);

        return project;
    }

    /**
     * Marks the project as errored.
     *
     * @param project the project to mark
     */
    @Transactional
    void markProjectError(final Project project) {
        project.markError();
        projectRepository.update(project);
    }

    /**
     * Persists a source class.
     *
     * @param sourceClass the source class to persist
     */
    @Transactional
    void persistSourceClass(final SourceClass sourceClass) {
        sourceClassRepository.save(sourceClass);
    }

    /**
     * Persists a source method.
     *
     * @param sourceMethod the source method to persist
     */
    @Transactional
    void persistSourceMethod(final SourceMethod sourceMethod) {
        sourceMethodRepository.save(sourceMethod);
    }

    /**
     * Completes the analysis by persisting graph and updating status.
     *
     * @param project the project
     * @param graphJson the graph JSON
     */
    @Transactional
    void completeAnalysis(final Project project, final String graphJson) {
        project.updateGraphData(graphJson);
        project.analysisCompleted("HEAD");
        projectRepository.update(project);
    }

    /**
     * Gets context for a class by its fully qualified name.
     *
     * @param fullClassName the fully qualified class name
     * @return the class context
     */
    public ClassContext getClassContext(final String fullClassName) {
        LOG.debug("Getting context for class: {}", fullClassName);

        final Optional<SourceClass> sourceClass = sourceClassRepository
                .findByFullClassName(fullClassName);

        if (sourceClass.isEmpty()) {
            return ClassContext.notFound(fullClassName, listKnownProjects());
        }

        final SourceClass sc = sourceClass.get();
        final List<SourceMethod> methods = sourceMethodRepository
                .findByClassId(sc.id());

        final Optional<Project> project = projectRepository
                .findById(sc.projectId());
        final String projectUrl = project
                .map(p -> p.repositoryUrl().value()).orElse(null);
        final String projectDescription = project
                .map(Project::description).orElse(null);

        return ClassContext.found(sc, methods, projectUrl,
                projectDescription);
    }

    /**
     * Gets context for a specific method.
     *
     * @param fullClassName the fully qualified class name
     * @param methodName the method name
     * @return the method context
     */
    public MethodContext getMethodContext(final String fullClassName,
            final String methodName) {

        LOG.debug("Getting context for method: {}.{}",
                fullClassName, methodName);

        final Optional<SourceClass> sourceClass = sourceClassRepository
                .findByFullClassName(fullClassName);

        if (sourceClass.isEmpty()) {
            return MethodContext.notFound(fullClassName, methodName,
                    listKnownProjects());
        }

        final SourceClass sc = sourceClass.get();
        final Optional<SourceMethod> method = sourceMethodRepository
                .findByClassNameAndMethodName(fullClassName, methodName);

        if (method.isEmpty()) {
            return MethodContext.classFoundMethodMissing(fullClassName,
                    methodName);
        }

        final Optional<Project> project = projectRepository
                .findById(sc.projectId());
        final String projectUrl = project
                .map(p -> p.repositoryUrl().value()).orElse(null);
        final String projectDescription = project
                .map(Project::description).orElse(null);

        return MethodContext.found(sc, method.get(), projectUrl,
                projectDescription);
    }

    /**
     * Gets context for a stack trace with graph-enhanced neighbor resolution.
     *
     * <p>For each matched frame, resolves graph neighbors (depth 1) and
     * includes their context in the response. This provides the full
     * dependency context around each stack trace frame.</p>
     *
     * @param stackFrames the stack trace frames
     * @return the stack trace context with neighbor dependencies
     */
    public StackTraceContext getStackTraceContext(
            final List<StackFrame> stackFrames) {

        LOG.debug("Getting context for {} stack frames", stackFrames.size());

        final List<ExecutionPathEntry> entries = new ArrayList<>();
        final List<StackFrame> missing = new ArrayList<>();
        String projectUrl = null;
        String projectDescription = null;
        String projectId = null;

        int order = 1;
        for (final StackFrame frame : stackFrames) {
            final Optional<SourceClass> sourceClass = sourceClassRepository
                    .findByFullClassName(frame.className());

            if (sourceClass.isEmpty()) {
                missing.add(frame);
                entries.add(ExecutionPathEntry.missing(order++, frame));
                continue;
            }

            final SourceClass sc = sourceClass.get();
            final Optional<SourceMethod> method = sourceMethodRepository
                    .findByClassNameAndMethodName(
                            frame.className(), frame.methodName());

            if (method.isEmpty()) {
                missing.add(frame);
                entries.add(ExecutionPathEntry.classMissing(
                        order++, frame, sc));
                continue;
            }

            if (projectUrl == null) {
                final Optional<Project> project = projectRepository
                        .findById(sc.projectId());
                projectUrl = project
                        .map(p -> p.repositoryUrl().value()).orElse(null);
                projectDescription = project
                        .map(Project::description).orElse(null);
                projectId = sc.projectId();
            }

            entries.add(ExecutionPathEntry.found(
                    order++, sc, method.get()));
        }

        // Resolve graph neighbors for matched classes
        final List<ExecutionPathEntry> neighborEntries = resolveNeighbors(
                entries, projectId);

        return new StackTraceContext(entries, missing, projectUrl,
                projectDescription, neighborEntries);
    }

    /**
     * Lists all analyzed projects.
     *
     * @return list of project summaries
     */
    public List<ProjectSummary> listProjects() {
        final List<Project> projects = projectRepository.findAll();
        final List<ProjectSummary> summaries = new ArrayList<>();

        for (final Project project : projects) {
            final long classCount = sourceClassRepository
                    .countByProjectId(project.id());
            final long endpointCount = sourceMethodRepository
                    .countEndpointsByProjectId(project.id());

            final String basePackage = determineBasePackage(project.id());

            summaries.add(new ProjectSummary(
                    project.id(),
                    project.name(),
                    project.repositoryUrl().value(),
                    basePackage,
                    project.description(),
                    project.status().name(),
                    project.lastAnalyzedAt(),
                    classCount,
                    endpointCount));
        }

        return summaries;
    }

    /**
     * Resolves graph neighbors for matched classes in the execution path.
     *
     * <p>For each found class in the execution path, looks up its graph
     * neighbors and returns their context. This enables understanding
     * the full dependency chain around a stack trace.</p>
     */
    private List<ExecutionPathEntry> resolveNeighbors(
            final List<ExecutionPathEntry> entries,
            final String projectId) {

        if (projectId == null) {
            return List.of();
        }

        final ProjectGraph graph = graphCache.getGraph(projectId);
        if (graph == null) {
            return List.of();
        }

        final Set<String> matchedClasses = new HashSet<>();
        for (final ExecutionPathEntry entry : entries) {
            if (entry.found()) {
                matchedClasses.add(entry.className());
            }
        }

        final Set<String> neighborClasses = new HashSet<>();
        for (final String className : matchedClasses) {
            final Set<String> neighbors = graph.resolve(className);
            for (final String neighbor : neighbors) {
                if (!matchedClasses.contains(neighbor)) {
                    neighborClasses.add(neighbor);
                }
            }
        }

        final List<ExecutionPathEntry> neighborEntries = new ArrayList<>();
        int order = 1;

        for (final String neighborClass : neighborClasses) {
            final Optional<SourceClass> sc = sourceClassRepository
                    .findByFullClassName(neighborClass);

            if (sc.isEmpty()) {
                continue;
            }

            final List<SourceMethod> methods = sourceMethodRepository
                    .findByClassId(sc.get().id());

            if (methods.isEmpty()) {
                neighborEntries.add(new ExecutionPathEntry(
                        order++,
                        sc.get().fullClassName(),
                        null,
                        sc.get().classType().name(),
                        sc.get().description(),
                        List.of(),
                        null,
                        true));
            } else {
                for (final SourceMethod method : methods) {
                    neighborEntries.add(ExecutionPathEntry.found(
                            order++, sc.get(), method));
                }
            }
        }

        return neighborEntries;
    }

    /**
     * Detects the appropriate source parser based on project markers.
     *
     * <p>Checks for build tool files to determine the project language:</p>
     * <ul>
     *   <li>pom.xml / build.gradle / build.gradle.kts -> Java</li>
     *   <li>package.json -> Node.js/TypeScript</li>
     *   <li>Default: Java</li>
     * </ul>
     *
     * @param cloneDir the cloned project root directory
     * @return the appropriate source parser
     */
    SourceParser detectParser(final Path cloneDir) {
        if (Files.exists(cloneDir.resolve("package.json"))
                && !Files.exists(cloneDir.resolve("pom.xml"))
                && !Files.exists(cloneDir.resolve("build.gradle"))
                && !Files.exists(cloneDir.resolve("build.gradle.kts"))) {
            LOG.info("Detected Node.js/TypeScript project");
            return new NodeJsSourceParser();
        }
        LOG.info("Detected Java project (default)");
        return new JavaSourceParser();
    }

    /**
     * Clones a repository using JGit.
     */
    private Path cloneRepository(final RepositoryUrl repoUrl,
            final String branch) throws IOException {

        final Path cloneDir = Path.of(cloneBasePath,
                repoUrl.repositoryName() + "-"
                        + System.currentTimeMillis());
        Files.createDirectories(cloneDir);

        LOG.info("Cloning {} (branch: {}) to {}", repoUrl, branch, cloneDir);

        try {
            Git.cloneRepository()
                    .setURI(repoUrl.value())
                    .setDirectory(cloneDir.toFile())
                    .setBranch(branch)
                    .setDepth(1)
                    .call()
                    .close();

            LOG.info("Clone completed to {}", cloneDir);
            return cloneDir;

        } catch (final GitAPIException e) {
            throw new IOException("Failed to clone repository: "
                    + e.getMessage(), e);
        }
    }

    /**
     * Reads the README.md content from the cloned repository.
     */
    private String readReadme(final Path cloneDir) {
        final Path readme = cloneDir.resolve("README.md");
        if (!Files.exists(readme)) {
            LOG.info("No README.md found");
            return null;
        }

        try {
            final String content = Files.readString(readme);
            if (content.isBlank()) {
                return null;
            }
            final String truncated = content.length() > MAX_README_LENGTH
                    ? content.substring(0, MAX_README_LENGTH)
                            + "\n...(truncated)"
                    : content;

            LOG.info("README.md found ({} chars)", content.length());
            return truncated;

        } catch (final IOException e) {
            LOG.warn("Failed to read README.md: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Cleans up the temporary clone directory.
     */
    private void cleanupCloneDir(final Path cloneDir) {
        if (cloneDir == null || !Files.exists(cloneDir)) {
            return;
        }
        try {
            Files.walk(cloneDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (final IOException e) {
                            LOG.warn("Failed to delete: {}", path);
                        }
                    });
            LOG.debug("Cleaned up clone directory: {}", cloneDir);
        } catch (final IOException e) {
            LOG.warn("Failed to cleanup clone directory: {}",
                    cloneDir, e);
        }
    }

    private String determineBasePackage(final String projectId) {
        final List<SourceClass> classes = sourceClassRepository
                .findByProjectId(projectId);

        if (classes.isEmpty()) {
            return null;
        }

        String commonPrefix = null;
        for (final SourceClass sc : classes) {
            if (sc.packageName() == null) {
                continue;
            }
            if (commonPrefix == null) {
                commonPrefix = sc.packageName();
            } else {
                commonPrefix = commonPackagePrefix(
                        commonPrefix, sc.packageName());
            }
        }
        return commonPrefix;
    }

    private String commonPackagePrefix(final String a, final String b) {
        final String[] partsA = a.split("\\.");
        final String[] partsB = b.split("\\.");
        final StringBuilder common = new StringBuilder();

        for (int i = 0; i < Math.min(partsA.length, partsB.length); i++) {
            if (!partsA[i].equals(partsB[i])) {
                break;
            }
            if (!common.isEmpty()) {
                common.append('.');
            }
            common.append(partsA[i]);
        }

        return common.toString();
    }

    private List<KnownProject> listKnownProjects() {
        final List<Project> projects = projectRepository.findAll();
        final List<KnownProject> known = new ArrayList<>();

        for (final Project project : projects) {
            final String basePackage = determineBasePackage(project.id());
            known.add(new KnownProject(
                    project.name(),
                    project.repositoryUrl().value(),
                    basePackage));
        }

        return known;
    }

    /**
     * Result of a project analysis.
     */
    public record AnalysisResult(
            boolean success,
            String projectId,
            int classesAnalyzed,
            int endpointsFound,
            String message
    ) {}

    /**
     * Context for a class.
     */
    public record ClassContext(
            boolean found,
            String className,
            String classType,
            String description,
            String projectDescription,
            List<MethodSummary> methods,
            String projectUrl,
            String message,
            List<KnownProject> knownProjects
    ) {
        /** Creates a found class context. */
        public static ClassContext found(final SourceClass sc,
                final List<SourceMethod> methods,
                final String projectUrl,
                final String projectDescription) {
            final List<MethodSummary> methodSummaries = methods.stream()
                    .map(m -> new MethodSummary(
                            m.methodName(),
                            m.description(),
                            m.businessLogic(),
                            m.dependencies()))
                    .toList();

            return new ClassContext(true, sc.fullClassName(),
                    sc.classType().name(), sc.description(),
                    projectDescription, methodSummaries, projectUrl,
                    null, List.of());
        }

        /** Creates a not-found class context. */
        public static ClassContext notFound(final String className,
                final List<KnownProject> knownProjects) {
            return new ClassContext(false, className, null, null,
                    null, List.of(), null,
                    "No context available for this class",
                    knownProjects);
        }
    }

    /**
     * Summary of a method for class context.
     */
    public record MethodSummary(
            String name,
            String description,
            List<String> businessLogic,
            List<String> dependencies
    ) {}

    /**
     * Context for a method.
     */
    public record MethodContext(
            boolean found,
            String className,
            String methodName,
            String httpEndpoint,
            String description,
            String projectDescription,
            List<String> businessLogic,
            List<String> dependencies,
            List<String> exceptions,
            String sourceFile,
            Integer lineNumber,
            String projectUrl,
            String message,
            List<KnownProject> knownProjects
    ) {
        /** Creates a found method context. */
        public static MethodContext found(final SourceClass sc,
                final SourceMethod method,
                final String projectUrl,
                final String projectDescription) {
            return new MethodContext(true, sc.fullClassName(),
                    method.methodName(), method.httpEndpoint(),
                    method.description(), projectDescription,
                    method.businessLogic(), method.dependencies(),
                    method.exceptions(), sc.sourceFile(),
                    method.lineNumber(), projectUrl, null, List.of());
        }

        /** Creates a not-found method context. */
        public static MethodContext notFound(final String className,
                final String methodName,
                final List<KnownProject> knownProjects) {
            return new MethodContext(false, className, methodName,
                    null, null, null, List.of(), List.of(), List.of(),
                    null, null, null,
                    "No context available for this method",
                    knownProjects);
        }

        /** Creates a class-found-method-missing context. */
        public static MethodContext classFoundMethodMissing(
                final String className, final String methodName) {
            return new MethodContext(false, className, methodName,
                    null, null, null, List.of(), List.of(), List.of(),
                    null, null, null,
                    "Class found but method not indexed",
                    List.of());
        }
    }

    /**
     * A frame in a stack trace.
     */
    public record StackFrame(
            String className,
            String methodName,
            Integer lineNumber
    ) {}

    /**
     * Context for a stack trace with graph-enhanced neighbor resolution.
     */
    public record StackTraceContext(
            List<ExecutionPathEntry> executionPath,
            List<StackFrame> missingContext,
            String projectUrl,
            String projectDescription,
            List<ExecutionPathEntry> relatedDependencies
    ) {}

    /**
     * An entry in the execution path.
     */
    public record ExecutionPathEntry(
            int order,
            String className,
            String methodName,
            String classType,
            String description,
            List<String> businessLogic,
            String httpEndpoint,
            boolean found
    ) {
        /** Creates a found entry. */
        public static ExecutionPathEntry found(final int order,
                final SourceClass sc, final SourceMethod method) {
            return new ExecutionPathEntry(order, sc.fullClassName(),
                    method.methodName(), sc.classType().name(),
                    method.description(), method.businessLogic(),
                    method.httpEndpoint(), true);
        }

        /** Creates a missing entry. */
        public static ExecutionPathEntry missing(final int order,
                final StackFrame frame) {
            return new ExecutionPathEntry(order, frame.className(),
                    frame.methodName(), null, null, List.of(),
                    null, false);
        }

        /** Creates a class-missing entry. */
        public static ExecutionPathEntry classMissing(final int order,
                final StackFrame frame, final SourceClass sc) {
            return new ExecutionPathEntry(order, frame.className(),
                    frame.methodName(), sc.classType().name(),
                    null, List.of(), null, false);
        }
    }

    /**
     * Summary of a known project.
     */
    public record KnownProject(
            String name,
            String repositoryUrl,
            String basePackage
    ) {}

    /**
     * Summary of a project.
     */
    public record ProjectSummary(
            String id,
            String name,
            String repositoryUrl,
            String basePackage,
            String description,
            String status,
            java.time.Instant lastAnalyzedAt,
            long classCount,
            long endpointCount
    ) {}

}
