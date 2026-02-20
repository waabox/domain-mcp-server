package co.fanki.domainmcp.analysis.application;

import co.fanki.domainmcp.analysis.domain.ClassType;
import co.fanki.domainmcp.analysis.domain.ClaudeApiClient;
import co.fanki.domainmcp.analysis.domain.ClaudeApiClient.EnrichmentInput;
import co.fanki.domainmcp.analysis.domain.ClaudeApiClient.EnrichmentResult;
import co.fanki.domainmcp.analysis.domain.ClaudeApiClient.MethodEnrichment;
import co.fanki.domainmcp.analysis.domain.MethodParameter;
import co.fanki.domainmcp.analysis.domain.MethodParameterRepository;
import co.fanki.domainmcp.analysis.domain.ProjectGraph;
import co.fanki.domainmcp.analysis.domain.GraphService;
import co.fanki.domainmcp.analysis.domain.SourceClass;
import co.fanki.domainmcp.analysis.domain.SourceClassRepository;
import co.fanki.domainmcp.analysis.domain.SourceMethod;
import co.fanki.domainmcp.analysis.domain.SourceMethodRepository;
import co.fanki.domainmcp.analysis.domain.SourceParser;
import co.fanki.domainmcp.analysis.domain.StaticMethodInfo;
import co.fanki.domainmcp.analysis.domain.golang.GoSourceParser;
import co.fanki.domainmcp.analysis.domain.java.JavaSourceParser;
import co.fanki.domainmcp.analysis.domain.nodejs.NodeJsGraalParser;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Application service for code context operations.
 *
 * <p>Provides functionality to analyze projects using a two-phase
 * pipeline and retrieve context about classes and methods for Datadog
 * stack trace correlation.</p>
 *
 * <p>Analysis flow:</p>
 * <ol>
 *   <li><b>Phase 1 (Static Parse)</b>: Clone -> Read README -> Build
 *       graph -> Statically extract classes, methods, HTTP endpoints,
 *       line numbers, exceptions -> Persist immediately (no LLM).</li>
 *   <li><b>Phase 2 (Claude Enrich)</b>: Send source code with
 *       pre-extracted structural data -> Claude returns only business
 *       descriptions and logic steps -> UPDATE persisted records.</li>
 * </ol>
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

    private static final int MAX_CONCURRENT_CLAUDE_REQUESTS = 5;

    private final ProjectRepository projectRepository;
    private final SourceClassRepository sourceClassRepository;
    private final SourceMethodRepository sourceMethodRepository;
    private final MethodParameterRepository methodParameterRepository;
    private final GraphService graphCache;
    private final ClaudeApiClient claudeApiClient;
    private final String cloneBasePath;

    /**
     * Creates a new CodeContextService.
     *
     * <p>When the Claude API key is not provided (blank or null), the
     * enrichment phases (Phase 2/3) are skipped during analysis. Context
     * query operations work without the API key.</p>
     *
     * @param theProjectRepository the project repository
     * @param theSourceClassRepository the source class repository
     * @param theSourceMethodRepository the source method repository
     * @param theMethodParameterRepository the method parameter repository
     * @param theGraphCache the project graph cache
     * @param theClaudeApiKey the Claude API key (optional)
     * @param theCloneBasePath the base path for cloning repositories
     */
    public CodeContextService(
            final ProjectRepository theProjectRepository,
            final SourceClassRepository theSourceClassRepository,
            final SourceMethodRepository theSourceMethodRepository,
            final MethodParameterRepository theMethodParameterRepository,
            final GraphService theGraphCache,
            @Value("${claude.api-key:}") final String theClaudeApiKey,
            @Value("${git.clone-base-path:/tmp/domain-mcp-repos}")
            final String theCloneBasePath) {
        this.projectRepository = theProjectRepository;
        this.sourceClassRepository = theSourceClassRepository;
        this.sourceMethodRepository = theSourceMethodRepository;
        this.methodParameterRepository = theMethodParameterRepository;
        this.graphCache = theGraphCache;
        if (theClaudeApiKey != null && !theClaudeApiKey.isBlank()) {
            this.claudeApiClient = new ClaudeApiClient(
                    theClaudeApiKey, MAX_CONCURRENT_CLAUDE_REQUESTS);
        } else {
            LOG.warn("Claude API key not configured. Enrichment phases"
                    + " will be skipped during analysis.");
            this.claudeApiClient = null;
        }
        this.cloneBasePath = theCloneBasePath;
    }

    /**
     * Analyzes a project using a two-phase pipeline.
     *
     * <p><b>Phase 1 (Static Parse)</b>: Clones the repository, builds
     * a dependency graph, statically extracts classes, methods, HTTP
     * endpoints, line numbers, and exceptions, then persists everything
     * immediately (no LLM). Descriptions are null at this point.</p>
     *
     * <p><b>Phase 2 (Claude Enrich)</b>: Sends pre-extracted structural
     * data to Claude for business meaning. Claude only provides
     * descriptions and logic steps. Results are applied via SQL UPDATE
     * queries to the already-persisted records.</p>
     *
     * @param repositoryUrl the git repository URL
     * @param branch the branch to analyze (optional, defaults to main)
     * @param fixMissed whether to run Phase 3 recovery for unenriched
     *     classes
     * @return the analysis result summary
     */
    public AnalysisResult analyzeProject(final String repositoryUrl,
            final String branch, final boolean fixMissed) {

        if (claudeApiClient == null) {
            LOG.error("Cannot run analysis in read-only mode. "
                    + "ANTHROPIC_API_KEY is not configured.");
            throw new DomainException(
                    "Cannot run analysis in read-only mode."
                            + " ANTHROPIC_API_KEY is not configured.",
                    "READ_ONLY_MODE");
        }

        LOG.info("Starting project analysis for: {}", repositoryUrl);

        final RepositoryUrl repoUrl = RepositoryUrl.of(repositoryUrl);
        final String branchName = branch != null ? branch : "main";

        final Project project = prepareProject(repoUrl, branchName);

        Path cloneDir = null;
        String commitHash = null;

        try {
            final CloneResult cloneResult = cloneRepository(repoUrl,
                    branchName);
            cloneDir = cloneResult.directory();
            commitHash = cloneResult.commitHash();

            final String readme = readReadme(cloneDir);
            if (readme != null) {
                project.updateDescription(readme.substring(0,
                        Math.min(readme.length(), 500)));
            }

            final SourceParser parser = detectParser(cloneDir);
            final ProjectGraph graph = parser.parse(cloneDir);

            LOG.info("Graph built: {} nodes, {} entry points",
                    graph.nodeCount(), graph.entryPointCount());

            final List<String> analysisOrder = graph.analysisOrder();
            final Set<String> knownIdentifiers = graph.identifiers();
            final Path sourceRootPath = cloneDir.resolve(parser.sourceRoot());

            // ---- Phase 1: Static Parse and Persist (no Claude) ----
            LOG.info("Phase 1: Static parse and persist for {} classes",
                    analysisOrder.size());

            final List<SourceClass> allClasses = new ArrayList<>();
            final List<SourceMethod> allMethods = new ArrayList<>();

            // Collect methods per identifier for parameter extraction
            // in a second pass (after all classIds are bound)
            final Map<String, List<SourceMethod>> methodsByIdentifier =
                    new HashMap<>();

            for (final String identifier : analysisOrder) {
                final String sourceFile = graph.sourceFile(identifier);
                final Path filePath = cloneDir.resolve(sourceFile);

                if (!Files.exists(filePath)) {
                    LOG.warn("Source file not found: {}", sourceFile);
                    continue;
                }

                try {
                    final ClassType classType = parser.inferClassType(
                            filePath);
                    final List<StaticMethodInfo> methods =
                            parser.extractMethods(filePath);

                    final SourceClass sourceClass = SourceClass.create(
                            project.id(), identifier, classType,
                            null, sourceFile, commitHash);

                    allClasses.add(sourceClass);
                    graph.bindClassId(identifier, sourceClass.id());

                    // Populate graph node metadata
                    graph.setNodeInfo(identifier,
                            classType.name(), null);

                    final List<SourceMethod> classMethods =
                            new ArrayList<>();

                    for (final StaticMethodInfo sm : methods) {
                        final SourceMethod sourceMethod = SourceMethod.create(
                                sourceClass.id(),
                                sm.methodName(),
                                null,
                                List.of(),
                                sm.exceptions(),
                                sm.httpMethod(),
                                sm.httpPath(),
                                sm.lineNumber());

                        allMethods.add(sourceMethod);
                        classMethods.add(sourceMethod);

                        // Populate graph method metadata
                        graph.addMethodInfo(identifier,
                                new ProjectGraph.MethodInfo(
                                        sm.methodName(),
                                        null,
                                        List.of(),
                                        sm.exceptions(),
                                        sm.httpMethod(),
                                        sm.httpPath(),
                                        sm.lineNumber()));
                    }

                    methodsByIdentifier.put(identifier, classMethods);

                } catch (final IOException e) {
                    LOG.warn("Failed to statically parse {}: {}",
                            sourceFile, e.getMessage());
                }
            }

            // Batch persist all classes and methods
            saveAllSourceClasses(allClasses);
            saveAllSourceMethods(allMethods);

            final int classCount = allClasses.size();
            final int methodCount = allMethods.size();

            // Second pass: extract method parameters now that all
            // classIds are bound in the graph
            final List<MethodParameter> allParams = new ArrayList<>();
            for (final String identifier : analysisOrder) {
                final String sourceFile = graph.sourceFile(identifier);
                final List<SourceMethod> methods =
                        methodsByIdentifier.get(identifier);

                if (methods == null || methods.isEmpty()) {
                    continue;
                }

                collectMethodParams(
                        parser, cloneDir, sourceRootPath,
                        knownIdentifiers, graph,
                        identifier, sourceFile, methods, allParams);
            }

            // Batch persist all parameters
            persistMethodParameters(allParams);
            final int paramCount = allParams.size();

            LOG.info("Phase 1 complete. Classes: {}, Methods: {}, "
                    + "Parameters: {}", classCount, methodCount, paramCount);

            // ---- Phase 2: Claude Enrichment (update only) ----
            LOG.info("Phase 2: Claude enrichment for {} classes",
                    analysisOrder.size());

            int enrichedCount = 0;
            int enrichFailedCount = 0;

            for (int i = 0; i < analysisOrder.size(); i += BATCH_SIZE) {
                final List<String> batch = analysisOrder.subList(
                        i, Math.min(i + BATCH_SIZE, analysisOrder.size()));

                LOG.info("Enriching batch {}/{} ({} classes)",
                        (i / BATCH_SIZE) + 1,
                        (int) Math.ceil(
                                (double) analysisOrder.size() / BATCH_SIZE),
                        batch.size());

                final List<EnrichmentInput> inputs = new ArrayList<>();
                for (final String identifier : batch) {
                    final String sourceFile = graph.sourceFile(identifier);
                    final Path filePath = cloneDir.resolve(sourceFile);

                    if (!Files.exists(filePath)) {
                        continue;
                    }

                    try {
                        final String sourceCode = Files.readString(filePath);
                        final String classId = graph.classId(identifier);
                        final ClassType inferredType = parser.inferClassType(
                                filePath);
                        final List<StaticMethodInfo> methods =
                                parser.extractMethods(filePath);
                        final List<String> methodNames = methods.stream()
                                .map(StaticMethodInfo::methodName)
                                .toList();

                        inputs.add(new EnrichmentInput(
                                sourceCode, identifier, parser.language(),
                                inferredType.name(), methodNames));
                    } catch (final IOException e) {
                        LOG.warn("Failed to read source for enrichment {}: {}",
                                sourceFile, e.getMessage());
                        enrichFailedCount++;
                    }
                }

                final List<EnrichmentResult> results =
                        claudeApiClient.enrichBatch(inputs, readme);

                for (final EnrichmentResult result : results) {
                    if (!result.success()) {
                        LOG.warn("Enrichment failed for {}: {}",
                                result.fullClassName(),
                                result.errorMessage());
                        enrichFailedCount++;
                        continue;
                    }

                    applyEnrichment(result, graph);
                    enrichedCount++;
                }
            }

            // ---- Phase 3: Recovery of missed enrichments ----
            int recoveredCount = 0;
            if (fixMissed) {
                final List<SourceClass> unenriched =
                        sourceClassRepository.findUnenrichedByProjectId(
                                project.id());

                if (!unenriched.isEmpty()) {
                    LOG.info("Phase 3: Found {} classes with missing "
                            + "enrichment, retrying", unenriched.size());

                    for (int i = 0; i < unenriched.size();
                            i += BATCH_SIZE) {

                        final List<SourceClass> batch = unenriched.subList(
                                i, Math.min(i + BATCH_SIZE,
                                        unenriched.size()));

                        final List<EnrichmentInput> recoveryInputs =
                                new ArrayList<>();
                        for (final SourceClass sc : batch) {
                            final Path filePath = cloneDir.resolve(
                                    sc.sourceFile());
                            if (!Files.exists(filePath)) {
                                continue;
                            }
                            try {
                                final String sourceCode =
                                        Files.readString(filePath);
                                final List<StaticMethodInfo> methods =
                                        parser.extractMethods(filePath);
                                final List<String> methodNames =
                                        methods.stream()
                                                .map(StaticMethodInfo
                                                        ::methodName)
                                                .toList();
                                recoveryInputs.add(new EnrichmentInput(
                                        sourceCode,
                                        sc.fullClassName(),
                                        parser.language(),
                                        sc.classType().name(),
                                        methodNames));
                            } catch (final IOException e) {
                                LOG.warn("Phase 3: Failed to read {}: {}",
                                        sc.sourceFile(), e.getMessage());
                            }
                        }

                        if (!recoveryInputs.isEmpty()) {
                            final List<EnrichmentResult> recoveryResults =
                                    claudeApiClient.enrichBatch(
                                            recoveryInputs, readme);
                            for (final EnrichmentResult result
                                    : recoveryResults) {
                                if (result.success()) {
                                    applyEnrichment(result, graph);
                                    recoveredCount++;
                                } else {
                                    LOG.warn("Phase 3: Recovery failed "
                                            + "for {}: {}",
                                            result.fullClassName(),
                                            result.errorMessage());
                                }
                            }
                        }
                    }

                    LOG.info("Phase 3 complete. Recovered: {}/{}",
                            recoveredCount, unenriched.size());
                } else {
                    LOG.info("Phase 3: No unenriched classes found, "
                            + "skipping recovery");
                }
            }

            // Persist graph JSON to project
            final String graphJson = graph.toJson();
            completeAnalysis(project, graphJson, commitHash);

            graphCache.put(project.id(), project.name(), graph);

            final long endpointCount = sourceMethodRepository
                    .countEndpointsByProjectId(project.id());

            LOG.info("Analysis completed. Classes: {}, Methods: {}, "
                            + "Parameters: {}, Endpoints: {}, "
                            + "Enriched: {}, Enrich failed: {}, "
                            + "Recovered: {}",
                    classCount, methodCount, paramCount, endpointCount,
                    enrichedCount, enrichFailedCount, recoveredCount);

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
     * Rebuilds the project graph by re-cloning and re-parsing source code
     * without invoking Claude.
     *
     * <p>Useful after parser improvements (e.g., regex fixes) to rebuild
     * the graph with updated parsing logic while preserving all existing
     * enrichment data. The flow:</p>
     * <ol>
     *   <li>Load existing project from DB</li>
     *   <li>Clone the repo (same branch)</li>
     *   <li>Run parser.parse() to build a fresh graph</li>
     *   <li>Bind existing classIds from DB records</li>
     *   <li>Re-extract and persist method parameters</li>
     *   <li>Persist graph JSON and reload cache</li>
     * </ol>
     *
     * <p>Does NOT create new SourceClass/SourceMethod records, does NOT
     * call Claude, and does NOT delete existing enrichment data.</p>
     *
     * @param projectId the project ID
     */
    public void rebuildGraph(final String projectId) {
        final Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new DomainException(
                        "Project not found: " + projectId,
                        "PROJECT_NOT_FOUND"));

        LOG.info("Rebuilding graph for project: {} ({})",
                project.name(), projectId);

        Path cloneDir = null;

        try {
            final CloneResult cloneResult = cloneRepository(
                    project.repositoryUrl(), project.defaultBranch());
            cloneDir = cloneResult.directory();

            final SourceParser parser = detectParser(cloneDir);
            final ProjectGraph graph = parser.parse(cloneDir);

            LOG.info("Rebuild graph built: {} nodes, {} entry points",
                    graph.nodeCount(), graph.entryPointCount());

            // Bind existing classIds from persisted records
            final List<SourceClass> existingClasses =
                    sourceClassRepository.findByProjectId(projectId);
            final Map<String, SourceClass> classByName = new HashMap<>();
            for (final SourceClass sc : existingClasses) {
                classByName.put(sc.fullClassName(), sc);
            }

            int boundCount = 0;
            int unboundCount = 0;
            for (final String identifier : graph.identifiers()) {
                final SourceClass existing = classByName.get(identifier);
                if (existing != null) {
                    graph.bindClassId(identifier, existing.id());
                    boundCount++;
                } else {
                    LOG.debug("Rebuild: no existing record for {}",
                            identifier);
                    unboundCount++;
                }
            }

            LOG.info("Rebuild: bound {}/{} identifiers ({} unmatched)",
                    boundCount, graph.nodeCount(), unboundCount);

            // Backfill missing commit hashes
            final String commitHash = cloneResult.commitHash();
            int backfilledCount = 0;
            for (final SourceClass sc : existingClasses) {
                if (sc.commitHash() == null) {
                    sourceClassRepository.updateCommitHash(
                            sc.id(), commitHash);
                    backfilledCount++;
                }
            }
            if (backfilledCount > 0) {
                LOG.info("Rebuild: backfilled commit hash for {} classes",
                        backfilledCount);
            }

            // Re-extract method parameters
            final Set<String> knownIdentifiers = graph.identifiers();
            final Path sourceRootPath = cloneDir.resolve(
                    parser.sourceRoot());
            final List<MethodParameter> allParams = new ArrayList<>();

            for (final String identifier : graph.analysisOrder()) {
                final SourceClass sc = classByName.get(identifier);
                if (sc == null) {
                    continue;
                }

                final String sourceFile = graph.sourceFile(identifier);
                final List<SourceMethod> methods =
                        sourceMethodRepository.findByClassId(sc.id());

                if (methods.isEmpty()) {
                    continue;
                }

                collectMethodParams(
                        parser, cloneDir, sourceRootPath,
                        knownIdentifiers, graph,
                        identifier, sourceFile, methods, allParams);
            }

            // Clear and re-persist parameters for this project
            for (final SourceClass sc : existingClasses) {
                final List<SourceMethod> methods =
                        sourceMethodRepository.findByClassId(sc.id());
                for (final SourceMethod m : methods) {
                    methodParameterRepository.deleteByMethodId(m.id());
                }
            }
            persistMethodParameters(allParams);

            // Persist graph and reload cache
            final String graphJson = graph.toJson();
            project.updateGraphData(graphJson);
            projectRepository.update(project);

            graphCache.put(projectId, project.name(), graph);

            LOG.info("Graph rebuild complete for {}. Params: {}",
                    project.name(), allParams.size());

        } catch (final Exception e) {
            LOG.error("Graph rebuild failed for {}: {}", projectId,
                    e.getMessage(), e);
            throw new DomainException(
                    "Graph rebuild failed: " + e.getMessage(),
                    "REBUILD_FAILED", e);
        } finally {
            cleanupCloneDir(cloneDir);
        }
    }

    /**
     * Applies Claude enrichment results to already-persisted records.
     *
     * <p>Updates the source class with the description and optionally
     * corrected class type, then updates each method with its business
     * description and logic steps.</p>
     *
     * @param result the enrichment result from Claude
     * @param graph the project graph for classId lookup
     */
    @Transactional
    void applyEnrichment(final EnrichmentResult result,
            final ProjectGraph graph) {

        final String classId = graph.classId(result.fullClassName());
        if (classId == null) {
            LOG.warn("No classId found for {} during enrichment",
                    result.fullClassName());
            return;
        }

        // Determine class type: use correction if provided, else keep
        // the statically inferred type
        ClassType classType = null;
        if (result.classTypeCorrection() != null
                && !"null".equals(result.classTypeCorrection())) {
            classType = ClassType.fromString(result.classTypeCorrection());
        }

        // If Claude corrected the type, update it; otherwise just
        // update the description
        if (classType != null) {
            sourceClassRepository.updateEnrichment(
                    classId, classType, result.description());
        } else {
            // Fetch current class type to preserve it
            final Optional<SourceClass> existing =
                    sourceClassRepository.findById(classId);
            if (existing.isPresent()) {
                sourceClassRepository.updateEnrichment(
                        classId, existing.get().classType(),
                        result.description());
            }
        }

        // Build method enrichment data for the graph
        final Map<String, ProjectGraph.MethodEnrichmentData>
                methodEnrichments = new HashMap<>();

        // Update methods
        for (final MethodEnrichment methodEnrich : result.methods()) {
            final String methodName = normalizeMethodName(
                    methodEnrich.methodName());

            final Optional<SourceMethod> method =
                    sourceMethodRepository.findByClassIdAndMethodName(
                            classId, methodName);

            if (method.isPresent()) {
                sourceMethodRepository.updateEnrichment(
                        method.get().id(),
                        methodEnrich.description(),
                        methodEnrich.businessLogic(),
                        method.get().exceptions());

                methodEnrichments.put(methodName,
                        new ProjectGraph.MethodEnrichmentData(
                                methodEnrich.description(),
                                methodEnrich.businessLogic()));
            } else {
                LOG.debug("Method {} not found for enrichment in class {}",
                        methodName, result.fullClassName());
            }
        }

        // Update the in-memory graph with enrichment data
        final String resolvedClassType;
        if (classType != null) {
            resolvedClassType = classType.name();
        } else {
            final ProjectGraph.NodeInfo existing =
                    graph.nodeInfo(result.fullClassName());
            resolvedClassType = existing != null
                    ? existing.classType() : "OTHER";
        }

        graph.applyEnrichment(result.fullClassName(),
                resolvedClassType, result.description(),
                methodEnrichments);
    }

    /**
     * Normalizes a method name returned by Claude enrichment.
     *
     * <p>Claude sometimes qualifies method names with context in
     * parentheses, e.g. "onSuccess (loginMutation)". This strips
     * any trailing parenthetical qualifier to match the raw extracted
     * method name.</p>
     *
     * @param methodName the method name from Claude enrichment
     * @return the normalized method name
     */
    private String normalizeMethodName(final String methodName) {
        if (methodName == null) {
            return null;
        }
        final int parenIndex = methodName.indexOf('(');
        if (parenIndex > 0) {
            return methodName.substring(0, parenIndex).trim();
        }
        return methodName.trim();
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
     * Batch-persists all source classes in a single transaction.
     *
     * @param sourceClasses the source classes to persist
     */
    @Transactional
    void saveAllSourceClasses(final List<SourceClass> sourceClasses) {
        sourceClassRepository.saveAll(sourceClasses);
    }

    /**
     * Batch-persists all source methods in a single transaction.
     *
     * @param sourceMethods the source methods to persist
     */
    @Transactional
    void saveAllSourceMethods(final List<SourceMethod> sourceMethods) {
        sourceMethodRepository.saveAll(sourceMethods);
    }

    /**
     * Collects method parameters for a single class into the provided list.
     *
     * <p>Parses the source file for method signatures, resolves parameter
     * types against known project identifiers, and appends MethodParameter
     * entries to the accumulator list. Actual persistence is deferred to
     * a single batch call by the caller.</p>
     *
     * @param parser the source parser
     * @param cloneDir the cloned project root
     * @param sourceRootPath the resolved source root path
     * @param knownIdentifiers all known identifiers in the project
     * @param graph the project graph with classId bindings
     * @param identifier the class identifier (FQCN)
     * @param sourceFile the relative source file path
     * @param methods the methods for this class
     * @param accumulator the list to collect parameters into
     */
    private void collectMethodParams(
            final SourceParser parser, final Path cloneDir,
            final Path sourceRootPath,
            final Set<String> knownIdentifiers,
            final ProjectGraph graph,
            final String identifier,
            final String sourceFile,
            final List<SourceMethod> methods,
            final List<MethodParameter> accumulator) {

        if (sourceFile == null || methods.isEmpty()) {
            return;
        }

        final Path filePath = cloneDir.resolve(sourceFile);
        if (!Files.exists(filePath)) {
            return;
        }

        try {
            final Map<String, List<String>> methodParams =
                    parser.extractMethodParameters(filePath,
                            sourceRootPath, knownIdentifiers);

            for (final SourceMethod method : methods) {
                final List<String> paramTypes =
                        methodParams.get(method.methodName());

                if (paramTypes == null || paramTypes.isEmpty()) {
                    continue;
                }

                for (int pos = 0; pos < paramTypes.size(); pos++) {
                    final String paramType = paramTypes.get(pos);
                    final String paramClassId = graph.classId(paramType);

                    graph.addMethodParameter(
                            identifier, method.methodName(),
                            pos, paramType);

                    if (paramClassId != null) {
                        accumulator.add(MethodParameter.create(
                                method.id(), pos, paramClassId));
                    }
                }
            }

        } catch (final IOException e) {
            LOG.warn("Failed to extract parameters from {}: {}",
                    sourceFile, e.getMessage());
        }
    }

    /**
     * Persists method parameters.
     *
     * @param params the method parameters to persist
     */
    @Transactional
    void persistMethodParameters(final List<MethodParameter> params) {
        methodParameterRepository.saveAll(params);
    }

    /**
     * Completes the analysis by persisting graph and updating status.
     *
     * @param project the project
     * @param graphJson the graph JSON
     * @param commitHash the analyzed commit hash
     */
    @Transactional
    void completeAnalysis(final Project project, final String graphJson,
            final String commitHash) {
        project.updateGraphData(graphJson);
        project.analysisCompleted(commitHash);
        projectRepository.update(project);
    }

    /**
     * Gets context for a class by its fully qualified name.
     *
     * @param fullClassName the fully qualified class name
     * @return the class context
     */
    public ClassContext getClassContext(final String fullClassName) {
        return getClassContext(fullClassName, null);
    }

    /**
     * Gets context for a class by its fully qualified name, optionally
     * scoped to a specific project.
     *
     * @param fullClassName the fully qualified class name
     * @param projectName optional project name to scope the search
     * @return the class context
     */
    public ClassContext getClassContext(final String fullClassName,
            final String projectName) {

        LOG.debug("Getting context for class: {} (project: {})",
                fullClassName, projectName);

        if (projectName != null) {
            final ProjectGraph graph =
                    graphCache.getGraphByProjectName(projectName);

            if (graph == null) {
                return ClassContext.notFound(fullClassName,
                        listKnownProjects());
            }

            if (!graph.contains(fullClassName)) {
                return ClassContext.notFound(fullClassName,
                        listKnownProjects());
            }

            final String projectId =
                    graphCache.getProjectIdByName(projectName);

            final Optional<SourceClass> sourceClass =
                    sourceClassRepository.findByProjectIdAndFullClassName(
                            projectId, fullClassName);

            if (sourceClass.isEmpty()) {
                return ClassContext.notFound(fullClassName,
                        listKnownProjects());
            }

            return buildClassContext(sourceClass.get(), fullClassName,
                    graph);
        }

        final Optional<SourceClass> sourceClass = sourceClassRepository
                .findByFullClassName(fullClassName);

        if (sourceClass.isEmpty()) {
            return ClassContext.notFound(fullClassName, listKnownProjects());
        }

        final SourceClass sc = sourceClass.get();
        final ProjectGraph graph = graphCache.getGraph(sc.projectId());

        return buildClassContext(sc, fullClassName, graph);
    }

    /**
     * Builds a ClassContext from a SourceClass and its graph.
     */
    private ClassContext buildClassContext(final SourceClass sc,
            final String fullClassName, final ProjectGraph graph) {

        final List<SourceMethod> methods = sourceMethodRepository
                .findByClassId(sc.id());

        final Optional<Project> project = projectRepository
                .findById(sc.projectId());
        final String projectUrl = project
                .map(p -> p.repositoryUrl().value()).orElse(null);
        final String projectDescription = project
                .map(Project::description).orElse(null);

        final GraphInfo graphInfo;
        if (graph != null && graph.contains(fullClassName)) {
            graphInfo = new GraphInfo(
                    List.copyOf(graph.dependencies(fullClassName)),
                    List.copyOf(graph.dependents(fullClassName)),
                    graph.isEntryPoint(fullClassName));
        } else {
            graphInfo = null;
        }

        return ClassContext.found(sc, methods, projectUrl,
                projectDescription, graphInfo);
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
        return getMethodContext(fullClassName, methodName, null);
    }

    /**
     * Gets context for a specific method, optionally scoped to a project.
     *
     * @param fullClassName the fully qualified class name
     * @param methodName the method name
     * @param projectName optional project name to scope the search
     * @return the method context
     */
    public MethodContext getMethodContext(final String fullClassName,
            final String methodName, final String projectName) {

        LOG.debug("Getting context for method: {}.{} (project: {})",
                fullClassName, methodName, projectName);

        if (projectName != null) {
            final ProjectGraph graph =
                    graphCache.getGraphByProjectName(projectName);

            if (graph == null) {
                return MethodContext.notFound(fullClassName, methodName,
                        listKnownProjects());
            }

            if (!graph.contains(fullClassName)) {
                return MethodContext.notFound(fullClassName, methodName,
                        listKnownProjects());
            }

            final String projectId =
                    graphCache.getProjectIdByName(projectName);

            final Optional<SourceClass> sourceClass =
                    sourceClassRepository.findByProjectIdAndFullClassName(
                            projectId, fullClassName);

            if (sourceClass.isEmpty()) {
                return MethodContext.notFound(fullClassName, methodName,
                        listKnownProjects());
            }

            return buildMethodContext(sourceClass.get(), fullClassName,
                    methodName, graph);
        }

        final Optional<SourceClass> sourceClass = sourceClassRepository
                .findByFullClassName(fullClassName);

        if (sourceClass.isEmpty()) {
            return MethodContext.notFound(fullClassName, methodName,
                    listKnownProjects());
        }

        final SourceClass sc = sourceClass.get();
        final ProjectGraph graph = graphCache.getGraph(sc.projectId());

        return buildMethodContext(sc, fullClassName, methodName, graph);
    }

    /**
     * Builds a MethodContext from a SourceClass, method name, and graph.
     */
    private MethodContext buildMethodContext(final SourceClass sc,
            final String fullClassName, final String methodName,
            final ProjectGraph graph) {

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

        final List<MethodParameterInfo> parameterTypes;
        if (graph != null) {
            final Map<String, List<ProjectGraph.MethodParameterLink>> params =
                    graph.methodParameters(fullClassName);
            final List<ProjectGraph.MethodParameterLink> links =
                    params.getOrDefault(methodName, List.of());
            parameterTypes = links.stream()
                    .map(l -> new MethodParameterInfo(
                            l.position(), l.targetIdentifier()))
                    .toList();
        } else {
            parameterTypes = List.of();
        }

        return MethodContext.found(sc, method.get(), projectUrl,
                projectDescription, parameterTypes);
    }

    /**
     * Gets the full dependency graph neighborhood for a class.
     *
     * <p>Returns what this class imports (dependencies), what imports it
     * (dependents), and method parameter types. Useful for understanding
     * how a class connects to the rest of the system.</p>
     *
     * @param fullClassName the fully qualified class name
     * @return the class dependencies
     */
    public ClassDependencies getClassDependencies(
            final String fullClassName) {
        return getClassDependencies(fullClassName, null);
    }

    /**
     * Gets the full dependency graph neighborhood for a class,
     * optionally scoped to a specific project.
     *
     * @param fullClassName the fully qualified class name
     * @param projectName optional project name to scope the search
     * @return the class dependencies
     */
    public ClassDependencies getClassDependencies(
            final String fullClassName, final String projectName) {

        LOG.debug("Getting dependencies for class: {} (project: {})",
                fullClassName, projectName);

        if (projectName != null) {
            final ProjectGraph graph =
                    graphCache.getGraphByProjectName(projectName);

            if (graph == null) {
                return new ClassDependencies(false, fullClassName, false,
                        List.of(), List.of(), List.of(),
                        "Project not found. Use list_projects to see"
                                + " available projects.");
            }

            if (!graph.contains(fullClassName)) {
                return new ClassDependencies(false, fullClassName, false,
                        List.of(), List.of(), List.of(),
                        "Class not found in project " + projectName);
            }

            return buildClassDependencies(fullClassName, graph);
        }

        final Optional<SourceClass> sourceClass = sourceClassRepository
                .findByFullClassName(fullClassName);

        if (sourceClass.isEmpty()) {
            return new ClassDependencies(false, fullClassName, false,
                    List.of(), List.of(), List.of(),
                    "Class not found in any indexed project");
        }

        final SourceClass sc = sourceClass.get();
        final ProjectGraph graph = graphCache.getGraph(sc.projectId());

        if (graph == null || !graph.contains(fullClassName)) {
            return new ClassDependencies(false, fullClassName, false,
                    List.of(), List.of(), List.of(),
                    "No graph data available for this project");
        }

        return buildClassDependencies(fullClassName, graph);
    }

    /**
     * Builds ClassDependencies from a graph for a given class.
     */
    private ClassDependencies buildClassDependencies(
            final String fullClassName, final ProjectGraph graph) {

        final List<DependencySummary> dependencies =
                graph.dependencies(fullClassName).stream()
                        .map(this::toDependencySummary)
                        .toList();

        final List<DependencySummary> dependents =
                graph.dependents(fullClassName).stream()
                        .map(this::toDependencySummary)
                        .toList();

        final Map<String, List<ProjectGraph.MethodParameterLink>> params =
                graph.methodParameters(fullClassName);

        final List<MethodParameterTypeSummary> methodParamTypes =
                new ArrayList<>();
        for (final Map.Entry<String,
                List<ProjectGraph.MethodParameterLink>> entry
                : params.entrySet()) {

            final List<DependencySummary> paramSummaries =
                    entry.getValue().stream()
                            .map(l -> toDependencySummary(
                                    l.targetIdentifier()))
                            .toList();

            methodParamTypes.add(new MethodParameterTypeSummary(
                    entry.getKey(), paramSummaries));
        }

        return new ClassDependencies(true, fullClassName,
                graph.isEntryPoint(fullClassName),
                dependencies, dependents, methodParamTypes, null);
    }

    /**
     * Gets a structural overview of an indexed project.
     *
     * <p>Returns entry points (controllers, listeners), HTTP endpoints,
     * class type breakdown, and project description. Useful for
     * understanding the architecture before drilling into specific
     * classes.</p>
     *
     * @param projectName the project name as returned by list_projects
     * @return the project overview
     */
    public ProjectOverview getProjectOverview(final String projectName) {
        LOG.debug("Getting overview for project: {}", projectName);

        final Optional<Project> projectOpt =
                projectRepository.findByName(projectName);

        if (projectOpt.isEmpty()) {
            return new ProjectOverview(false, projectName, null, null,
                    0, 0, Map.of(), List.of(),
                    "Project not found. Use list_projects to see"
                            + " available projects.");
        }

        final Project project = projectOpt.get();
        final List<SourceClass> allClasses =
                sourceClassRepository.findByProjectId(project.id());

        final Map<String, Integer> classTypeBreakdown = new HashMap<>();
        for (final SourceClass sc : allClasses) {
            classTypeBreakdown.merge(sc.classType().name(), 1,
                    Integer::sum);
        }

        final ProjectGraph graph = graphCache.getGraph(project.id());

        if (graph == null) {
            return new ProjectOverview(true, project.name(),
                    project.repositoryUrl().value(),
                    project.description(), allClasses.size(),
                    0, classTypeBreakdown, List.of(),
                    "No graph data available");
        }

        final Set<String> graphEntryPoints = graph.entryPoints();
        final List<EntryPointSummary> entryPointSummaries =
                new ArrayList<>();

        for (final String ep : graphEntryPoints) {
            final Optional<SourceClass> sc =
                    sourceClassRepository.findByFullClassName(ep);

            if (sc.isEmpty()) {
                continue;
            }

            final List<SourceMethod> methods =
                    sourceMethodRepository.findByClassId(sc.get().id());

            final List<String> httpEndpoints = methods.stream()
                    .filter(SourceMethod::isHttpEndpoint)
                    .map(SourceMethod::httpEndpoint)
                    .toList();

            entryPointSummaries.add(new EntryPointSummary(
                    sc.get().fullClassName(),
                    sc.get().classType().name(),
                    sc.get().description(),
                    httpEndpoints));
        }

        return new ProjectOverview(true, project.name(),
                project.repositoryUrl().value(),
                project.description(), allClasses.size(),
                graphEntryPoints.size(), classTypeBreakdown,
                entryPointSummaries, null);
    }

    /**
     * Gets the public API surface of an indexed microservice.
     *
     * <p>Returns all HTTP endpoints grouped by controller, with parameter
     * types (DTOs), descriptions, business logic, and exceptions. Use this
     * when integrating with another microservice.</p>
     *
     * @param projectName the project name as returned by list_projects
     * @return the service API surface
     */
    public ServiceApi getServiceApi(final String projectName) {
        LOG.debug("Getting service API for project: {}", projectName);

        final Optional<Project> projectOpt =
                projectRepository.findByName(projectName);

        if (projectOpt.isEmpty()) {
            return new ServiceApi(false, projectName, null, null,
                    List.of(),
                    "Project not found. Use list_projects to see"
                            + " available projects.");
        }

        final Project project = projectOpt.get();
        final ProjectGraph graph = graphCache.getGraph(project.id());

        if (graph == null) {
            return new ServiceApi(false, projectName,
                    project.repositoryUrl().value(),
                    project.description(), List.of(),
                    "No graph data available for this project."
                            + " The project may need to be re-analyzed.");
        }

        final Set<String> graphEntryPoints = graph.entryPoints();
        final List<ControllerApi> controllers = new ArrayList<>();

        for (final String ep : graphEntryPoints) {
            final Optional<SourceClass> sc =
                    sourceClassRepository.findByFullClassName(ep);

            if (sc.isEmpty()) {
                continue;
            }

            final List<SourceMethod> methods =
                    sourceMethodRepository.findByClassId(sc.get().id());

            final Map<String, List<ProjectGraph.MethodParameterLink>>
                    classParams = graph.methodParameters(ep);

            final List<EndpointDetail> endpoints = new ArrayList<>();

            for (final SourceMethod method : methods) {
                if (!method.isHttpEndpoint()) {
                    continue;
                }

                final List<ProjectGraph.MethodParameterLink> paramLinks =
                        classParams.getOrDefault(
                                method.methodName(), List.of());

                final List<EndpointParameterSummary> params =
                        paramLinks.stream()
                                .map(link -> {
                                    final Optional<SourceClass> paramClass =
                                            sourceClassRepository
                                                    .findByFullClassName(
                                                            link.targetIdentifier());
                                    return new EndpointParameterSummary(
                                            link.position(),
                                            link.targetIdentifier(),
                                            paramClass.map(c ->
                                                    c.classType().name())
                                                    .orElse(null),
                                            paramClass.map(
                                                    SourceClass::description)
                                                    .orElse(null));
                                })
                                .toList();

                endpoints.add(new EndpointDetail(
                        method.methodName(),
                        method.httpMethod(),
                        method.httpPath(),
                        method.description(),
                        method.businessLogic(),
                        method.exceptions(),
                        params));
            }

            if (!endpoints.isEmpty()) {
                controllers.add(new ControllerApi(
                        sc.get().fullClassName(),
                        sc.get().description(),
                        endpoints));
            }
        }

        return new ServiceApi(true, project.name(),
                project.repositoryUrl().value(),
                project.description(), controllers, null);
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
     * Searches for classes within a specific project by partial name.
     *
     * <p>Performs a case-insensitive search against both simple class names
     * and fully qualified class names in the project's graph. For each
     * match, enrichment data (class type, description) is looked up from
     * the database.</p>
     *
     * @param projectName the project name as returned by list_projects
     * @param query the partial class name or keyword to search for
     * @return the search result with matching classes
     */
    public ProjectSearchResult searchProject(final String projectName,
            final String query) {

        LOG.debug("Searching project '{}' for: {}", projectName, query);

        final ProjectGraph graph =
                graphCache.getGraphByProjectName(projectName);

        if (graph == null) {
            return new ProjectSearchResult(false, projectName, query,
                    List.of(), 0,
                    "Project not found. Use list_projects to see"
                            + " available projects.");
        }

        final String lowerQuery = query.toLowerCase();
        final Set<String> identifiers = graph.identifiers();
        final List<SearchMatch> matches = new ArrayList<>();

        for (final String identifier : identifiers) {
            final String simpleName = extractSimpleName(identifier);
            if (identifier.toLowerCase().contains(lowerQuery)
                    || simpleName.toLowerCase().contains(lowerQuery)) {

                final Optional<SourceClass> sc =
                        sourceClassRepository.findByFullClassName(identifier);

                matches.add(new SearchMatch(
                        identifier,
                        sc.map(c -> c.classType().name()).orElse(null),
                        sc.map(SourceClass::description).orElse(null),
                        graph.isEntryPoint(identifier),
                        graph.sourceFile(identifier)));
            }
        }

        return new ProjectSearchResult(true, projectName, query,
                matches, identifiers.size(), null);
    }

    /**
     * Extracts the simple class name from a fully qualified name.
     *
     * @param fqcn the fully qualified class name
     * @return the simple class name
     */
    private String extractSimpleName(final String fqcn) {
        final int lastDot = fqcn.lastIndexOf('.');
        return lastDot >= 0 ? fqcn.substring(lastDot + 1) : fqcn;
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
        if (Files.exists(cloneDir.resolve("go.mod"))) {
            LOG.info("Detected Go project");
            return new GoSourceParser();
        }
        if (Files.exists(cloneDir.resolve("package.json"))
                && !Files.exists(cloneDir.resolve("pom.xml"))
                && !Files.exists(cloneDir.resolve("build.gradle"))
                && !Files.exists(cloneDir.resolve("build.gradle.kts"))) {
            LOG.info("Detected Node.js/TypeScript project");
            return new NodeJsGraalParser();
        }
        LOG.info("Detected Java project (default)");
        return new JavaSourceParser();
    }

    /**
     * Result of cloning a repository, carrying the local path and the
     * HEAD commit hash.
     */
    private record CloneResult(Path directory, String commitHash) {}

    /**
     * Clones a repository using JGit and extracts the HEAD commit hash.
     */
    private CloneResult cloneRepository(final RepositoryUrl repoUrl,
            final String branch) throws IOException {

        final Path cloneDir = Path.of(cloneBasePath,
                repoUrl.repositoryName() + "-"
                        + System.currentTimeMillis());
        Files.createDirectories(cloneDir);

        LOG.info("Cloning {} (branch: {}) to {}", repoUrl, branch, cloneDir);

        try {
            final Git git = Git.cloneRepository()
                    .setURI(repoUrl.value())
                    .setDirectory(cloneDir.toFile())
                    .setBranch(branch)
                    .setDepth(1)
                    .call();

            final String commitHash = git.getRepository()
                    .resolve("HEAD").getName();
            git.close();

            LOG.info("Clone completed to {} (commit: {})", cloneDir,
                    commitHash);
            return new CloneResult(cloneDir, commitHash);

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

    /**
     * Converts a class identifier to a DependencySummary by looking up
     * its indexed data. Returns a minimal summary if not indexed.
     */
    private DependencySummary toDependencySummary(final String className) {
        final Optional<SourceClass> sc = sourceClassRepository
                .findByFullClassName(className);
        if (sc.isPresent()) {
            return new DependencySummary(className,
                    sc.get().classType().name(), sc.get().description());
        }
        return new DependencySummary(className, null, null);
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
     * A single class match from a project search.
     *
     * @param className the fully qualified class name
     * @param classType the class type, null if not enriched
     * @param description the business description, null if not enriched
     * @param entryPoint whether the class is an entry point
     * @param sourceFile the relative source file path
     */
    public record SearchMatch(
            String className,
            String classType,
            String description,
            boolean entryPoint,
            String sourceFile
    ) {}

    /**
     * Result of searching for classes within a project.
     *
     * @param found whether the project was found
     * @param projectName the project name
     * @param query the search query
     * @param matches the matching classes
     * @param totalClassesInProject the total number of classes in the project
     * @param message informational message
     */
    public record ProjectSearchResult(
            boolean found,
            String projectName,
            String query,
            List<SearchMatch> matches,
            int totalClassesInProject,
            String message
    ) {}

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
     * Graph-derived relationship information for a class.
     *
     * @param dependencies outgoing: what this class imports
     * @param dependents incoming: what imports this class
     * @param entryPoint whether this class is a controller/listener
     */
    public record GraphInfo(
            List<String> dependencies,
            List<String> dependents,
            boolean entryPoint
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
            List<KnownProject> knownProjects,
            GraphInfo graphInfo
    ) {
        /** Creates a found class context. */
        public static ClassContext found(final SourceClass sc,
                final List<SourceMethod> methods,
                final String projectUrl,
                final String projectDescription,
                final GraphInfo graphInfo) {
            final List<MethodSummary> methodSummaries = methods.stream()
                    .map(m -> new MethodSummary(
                            m.methodName(),
                            m.description(),
                            m.businessLogic()))
                    .toList();

            return new ClassContext(true, sc.fullClassName(),
                    sc.classType().name(), sc.description(),
                    projectDescription, methodSummaries, projectUrl,
                    null, List.of(), graphInfo);
        }

        /** Creates a not-found class context. */
        public static ClassContext notFound(final String className,
                final List<KnownProject> knownProjects) {
            return new ClassContext(false, className, null, null,
                    null, List.of(), null,
                    "No context available for this class",
                    knownProjects, null);
        }
    }

    /**
     * Summary of a method for class context.
     */
    public record MethodSummary(
            String name,
            String description,
            List<String> businessLogic
    ) {}

    /**
     * A method parameter type resolved from the project graph.
     *
     * @param position the 0-based parameter position
     * @param typeName the FQCN of the parameter type
     */
    public record MethodParameterInfo(
            int position,
            String typeName
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
            List<String> exceptions,
            String sourceFile,
            Integer lineNumber,
            String projectUrl,
            String message,
            List<KnownProject> knownProjects,
            List<MethodParameterInfo> parameterTypes
    ) {
        /** Creates a found method context. */
        public static MethodContext found(final SourceClass sc,
                final SourceMethod method,
                final String projectUrl,
                final String projectDescription,
                final List<MethodParameterInfo> parameterTypes) {
            return new MethodContext(true, sc.fullClassName(),
                    method.methodName(), method.httpEndpoint(),
                    method.description(), projectDescription,
                    method.businessLogic(),
                    method.exceptions(), sc.sourceFile(),
                    method.lineNumber(), projectUrl, null, List.of(),
                    parameterTypes);
        }

        /** Creates a not-found method context. */
        public static MethodContext notFound(final String className,
                final String methodName,
                final List<KnownProject> knownProjects) {
            return new MethodContext(false, className, methodName,
                    null, null, null, List.of(), List.of(),
                    null, null, null,
                    "No context available for this method",
                    knownProjects, List.of());
        }

        /** Creates a class-found-method-missing context. */
        public static MethodContext classFoundMethodMissing(
                final String className, final String methodName) {
            return new MethodContext(false, className, methodName,
                    null, null, null, List.of(), List.of(),
                    null, null, null,
                    "Class found but method not indexed",
                    List.of(), List.of());
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
     * Summary of a class dependency in the graph.
     *
     * @param className the fully qualified class name
     * @param classType the class type, null if not indexed
     * @param description the business description, null if not indexed
     */
    public record DependencySummary(
            String className,
            String classType,
            String description
    ) {}

    /**
     * Summary of method parameter types for a single method.
     *
     * @param methodName the method name
     * @param parameterTypes the parameter type summaries
     */
    public record MethodParameterTypeSummary(
            String methodName,
            List<DependencySummary> parameterTypes
    ) {}

    /**
     * Full dependency graph neighborhood for a class.
     *
     * @param found whether the class was found
     * @param className the fully qualified class name
     * @param entryPoint whether this class is an entry point
     * @param dependencies outgoing: what this class imports
     * @param dependents incoming: what imports this class
     * @param methodParameterTypes parameter types per method
     * @param message informational message
     */
    public record ClassDependencies(
            boolean found,
            String className,
            boolean entryPoint,
            List<DependencySummary> dependencies,
            List<DependencySummary> dependents,
            List<MethodParameterTypeSummary> methodParameterTypes,
            String message
    ) {}

    /**
     * Summary of an entry point class with its HTTP endpoints.
     *
     * @param className the fully qualified class name
     * @param classType the class type
     * @param description the business description
     * @param httpEndpoints HTTP endpoint strings, e.g. "GET /api/users"
     */
    public record EntryPointSummary(
            String className,
            String classType,
            String description,
            List<String> httpEndpoints
    ) {}

    /**
     * Structural overview of an indexed project.
     *
     * @param found whether the project was found
     * @param projectName the project name
     * @param repositoryUrl the git repository URL
     * @param description the project description from README
     * @param totalClasses total number of indexed classes
     * @param totalEntryPoints number of entry points in the graph
     * @param classTypeBreakdown count per class type
     * @param entryPoints entry point summaries with endpoints
     * @param message informational message
     */
    public record ProjectOverview(
            boolean found,
            String projectName,
            String repositoryUrl,
            String description,
            int totalClasses,
            int totalEntryPoints,
            Map<String, Integer> classTypeBreakdown,
            List<EntryPointSummary> entryPoints,
            String message
    ) {}

    /**
     * A parameter accepted by an HTTP endpoint, resolved from the graph.
     *
     * @param position the 0-based parameter position
     * @param className the FQCN of the parameter type
     * @param classType the class type (e.g., DTO, ENTITY), null if not indexed
     * @param description the business description, null if not indexed
     */
    public record EndpointParameterSummary(
            int position,
            String className,
            String classType,
            String description
    ) {}

    /**
     * A single HTTP endpoint exposed by a controller.
     *
     * @param methodName the Java method name
     * @param httpMethod the HTTP verb (GET, POST, etc.)
     * @param httpPath the URL path
     * @param description the business description of this endpoint
     * @param businessLogic the business logic steps
     * @param exceptions the exceptions this endpoint may throw
     * @param parameters the resolved parameter types
     */
    public record EndpointDetail(
            String methodName,
            String httpMethod,
            String httpPath,
            String description,
            List<String> businessLogic,
            List<String> exceptions,
            List<EndpointParameterSummary> parameters
    ) {}

    /**
     * A controller and its HTTP endpoints.
     *
     * @param className the FQCN of the controller
     * @param description the business description of the controller
     * @param endpoints the HTTP endpoints exposed by this controller
     */
    public record ControllerApi(
            String className,
            String description,
            List<EndpointDetail> endpoints
    ) {}

    /**
     * The public API surface of a microservice.
     *
     * @param found whether the project was found and has graph data
     * @param projectName the project name
     * @param repositoryUrl the git repository URL
     * @param description the project description from README
     * @param controllers the controllers with their HTTP endpoints
     * @param message informational message
     */
    public record ServiceApi(
            boolean found,
            String projectName,
            String repositoryUrl,
            String description,
            List<ControllerApi> controllers,
            String message
    ) {}

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
