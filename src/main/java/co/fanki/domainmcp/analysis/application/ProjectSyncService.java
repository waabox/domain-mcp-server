package co.fanki.domainmcp.analysis.application;

import co.fanki.domainmcp.analysis.domain.ClassType;
import co.fanki.domainmcp.analysis.domain.ClaudeApiClient;
import co.fanki.domainmcp.analysis.domain.ClaudeApiClient.EnrichmentInput;
import co.fanki.domainmcp.analysis.domain.ClaudeApiClient.EnrichmentResult;
import co.fanki.domainmcp.analysis.domain.ClaudeApiClient.MethodEnrichment;
import co.fanki.domainmcp.analysis.domain.GitDiffResult;
import co.fanki.domainmcp.analysis.domain.GraphService;
import co.fanki.domainmcp.analysis.domain.MethodParameter;
import co.fanki.domainmcp.analysis.domain.MethodParameterRepository;
import co.fanki.domainmcp.analysis.domain.ProjectGraph;
import co.fanki.domainmcp.analysis.domain.SourceClass;
import co.fanki.domainmcp.analysis.domain.SourceClassRepository;
import co.fanki.domainmcp.analysis.domain.SourceMethod;
import co.fanki.domainmcp.analysis.domain.SourceMethodRepository;
import co.fanki.domainmcp.analysis.domain.SourceParser;
import co.fanki.domainmcp.analysis.domain.StaticMethodInfo;
import co.fanki.domainmcp.analysis.domain.java.JavaSourceParser;
import co.fanki.domainmcp.analysis.domain.nodejs.NodeJsGraalParser;
import co.fanki.domainmcp.project.domain.Project;
import co.fanki.domainmcp.project.domain.ProjectRepository;
import co.fanki.domainmcp.project.domain.ProjectStatus;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
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
 * Service for incremental project synchronization.
 *
 * <p>Detects git changes since the last analyzed commit and only
 * re-analyzes the delta. Static parsing is always done on the full
 * project (fast, no Claude), while Claude enrichment is only invoked
 * for new and modified classes.</p>
 *
 * <p>Flow:</p>
 * <ol>
 *   <li>Clone repo (full, no depth limit)</li>
 *   <li>Compute git diff between lastCommitHash and HEAD</li>
 *   <li>Re-parse all files statically to get full graph</li>
 *   <li>Determine added/updated/deleted/unchanged classes</li>
 *   <li>Apply DB changes (delete removed, re-persist changed)</li>
 *   <li>Claude enrichment only for added + updated classes</li>
 *   <li>Rebuild graph, update project state</li>
 * </ol>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Service
public class ProjectSyncService {

    private static final Logger LOG = LoggerFactory.getLogger(
            ProjectSyncService.class);

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
     * Creates a new ProjectSyncService.
     *
     * @param theProjectRepository the project repository
     * @param theSourceClassRepository the source class repository
     * @param theSourceMethodRepository the source method repository
     * @param theMethodParameterRepository the method parameter repository
     * @param theGraphCache the project graph cache
     * @param theClaudeApiKey the Claude API key (optional)
     * @param theCloneBasePath the base path for cloning repositories
     */
    public ProjectSyncService(
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
            LOG.warn("Claude API key not configured. Sync enrichment"
                    + " will be skipped.");
            this.claudeApiClient = null;
        }
        this.cloneBasePath = theCloneBasePath;
    }

    /**
     * Synchronizes a project by detecting and applying git changes.
     *
     * <p>If the HEAD commit matches the project's lastCommitHash, the
     * sync is skipped. Otherwise, computes a diff and incrementally
     * updates the analysis data.</p>
     *
     * @param project the project to sync
     * @return a summary of the sync result
     */
    public SyncResult syncProject(final Project project) {
        LOG.info("Starting sync for project: {} (last commit: {})",
                project.name(), project.lastCommitHash());

        Path cloneDir = null;
        Git git = null;

        try {
            project.startSync();
            projectRepository.update(project);

            // 1. Clone repo (full, no depth limit for diff)
            cloneDir = cloneRepository(project);

            git = Git.open(cloneDir.toFile());
            final Repository repo = git.getRepository();
            final String headCommitHash = repo.resolve("HEAD").getName();

            // 2. Check if HEAD matches last analyzed commit
            if (headCommitHash.equals(project.lastCommitHash())) {
                LOG.info("No changes detected for project: {} (HEAD: {})",
                        project.name(), headCommitHash);
                revertToAnalyzed(project);
                return SyncResult.noChanges(project.name());
            }

            // 3. Compute git diff
            final GitDiffResult diff = computeDiff(
                    repo, git, project.lastCommitHash(), headCommitHash);

            LOG.info("Diff computed for {}: {} changed, {} deleted,"
                            + " fullResync={}",
                    project.name(), diff.changedFiles().size(),
                    diff.deletedFiles().size(), diff.fullResyncRequired());

            // 4. Read README
            final String readme = readReadme(cloneDir);
            if (readme != null) {
                project.updateDescription(readme.substring(0,
                        Math.min(readme.length(), 500)));
            }

            // 5. Full static parse to get new graph
            final SourceParser parser = detectParser(cloneDir);
            final ProjectGraph newGraph = parser.parse(cloneDir);
            final Path sourceRootPath = cloneDir.resolve(
                    parser.sourceRoot());

            LOG.info("Static parse complete: {} nodes, {} entry points",
                    newGraph.nodeCount(), newGraph.entryPointCount());

            // 6. Load existing classes from DB
            final List<SourceClass> existingClasses =
                    sourceClassRepository.findByProjectId(project.id());
            final Map<String, SourceClass> existingByFQCN = new HashMap<>();
            for (final SourceClass sc : existingClasses) {
                existingByFQCN.put(sc.fullClassName(), sc);
            }

            final Set<String> newIdentifiers = newGraph.identifiers();

            // 7. Determine changes
            final Set<String> toDelete = new HashSet<>();
            final Set<String> toAdd = new HashSet<>();
            final Set<String> toUpdate = new HashSet<>();
            final Set<String> unchanged = new HashSet<>();

            for (final String existing : existingByFQCN.keySet()) {
                if (!newIdentifiers.contains(existing)) {
                    toDelete.add(existing);
                }
            }

            for (final String identifier : newIdentifiers) {
                final SourceClass existing = existingByFQCN.get(identifier);
                if (existing == null) {
                    toAdd.add(identifier);
                } else {
                    final String sourceFile = newGraph.sourceFile(identifier);
                    if (diff.fullResyncRequired()
                            || diff.changedFiles().contains(sourceFile)) {
                        toUpdate.add(identifier);
                    } else {
                        unchanged.add(identifier);
                    }
                }
            }

            LOG.info("Changes for {}: add={}, update={}, delete={},"
                            + " unchanged={}",
                    project.name(), toAdd.size(), toUpdate.size(),
                    toDelete.size(), unchanged.size());

            // 8. Apply deletions
            if (!toDelete.isEmpty()) {
                final List<String> deleteIds = toDelete.stream()
                        .map(fqcn -> existingByFQCN.get(fqcn).id())
                        .toList();
                methodParameterRepository.deleteByClassIds(deleteIds);
                sourceMethodRepository.deleteByClassIds(deleteIds);
                sourceClassRepository.deleteByIds(deleteIds);
                LOG.info("Deleted {} classes", deleteIds.size());
            }

            // 9. Apply updates (re-parse changed files)
            final List<SourceClass> updatedClasses = new ArrayList<>();
            final List<SourceMethod> updatedMethods = new ArrayList<>();
            final Map<String, List<SourceMethod>> methodsByIdentifier =
                    new HashMap<>();

            for (final String identifier : toUpdate) {
                final SourceClass existing = existingByFQCN.get(identifier);
                final String sourceFile = newGraph.sourceFile(identifier);
                final Path filePath = cloneDir.resolve(sourceFile);

                if (!Files.exists(filePath)) {
                    LOG.warn("Source file not found during update: {}",
                            sourceFile);
                    newGraph.bindClassId(identifier, existing.id());
                    unchanged.add(identifier);
                    continue;
                }

                try {
                    // Re-extract methods BEFORE deleting old data
                    // to avoid data loss on parse failure
                    final ClassType classType = parser.inferClassType(
                            filePath);
                    final List<StaticMethodInfo> methods =
                            parser.extractMethods(filePath);

                    // Parse succeeded - safe to delete old data
                    methodParameterRepository.deleteByClassIds(
                            List.of(existing.id()));
                    sourceMethodRepository.deleteByClassId(existing.id());

                    // Update class commit hash and type
                    sourceClassRepository.updateCommitHash(
                            existing.id(), headCommitHash);
                    sourceClassRepository.updateEnrichment(
                            existing.id(), classType, existing.description());

                    newGraph.bindClassId(identifier, existing.id());

                    final List<SourceMethod> classMethods = new ArrayList<>();
                    for (final StaticMethodInfo sm : methods) {
                        final SourceMethod sourceMethod = SourceMethod.create(
                                existing.id(),
                                sm.methodName(),
                                null,
                                List.of(),
                                sm.exceptions(),
                                sm.httpMethod(),
                                sm.httpPath(),
                                sm.lineNumber());
                        updatedMethods.add(sourceMethod);
                        classMethods.add(sourceMethod);
                    }
                    methodsByIdentifier.put(identifier, classMethods);
                    updatedClasses.add(existing);

                } catch (final IOException e) {
                    LOG.warn("Failed to re-parse {}: {}. Keeping existing"
                            + " data.", sourceFile, e.getMessage());
                    newGraph.bindClassId(identifier, existing.id());
                    unchanged.add(identifier);
                }
            }

            // 10. Apply additions
            final List<SourceClass> addedClasses = new ArrayList<>();
            final List<SourceMethod> addedMethods = new ArrayList<>();

            for (final String identifier : toAdd) {
                final String sourceFile = newGraph.sourceFile(identifier);
                final Path filePath = cloneDir.resolve(sourceFile);

                if (!Files.exists(filePath)) {
                    LOG.warn("Source file not found for new class: {}",
                            sourceFile);
                    continue;
                }

                try {
                    final ClassType classType = parser.inferClassType(
                            filePath);
                    final List<StaticMethodInfo> methods =
                            parser.extractMethods(filePath);

                    final SourceClass sourceClass = SourceClass.create(
                            project.id(), identifier, classType,
                            null, sourceFile, headCommitHash);

                    addedClasses.add(sourceClass);
                    newGraph.bindClassId(identifier, sourceClass.id());

                    final List<SourceMethod> classMethods = new ArrayList<>();
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
                        addedMethods.add(sourceMethod);
                        classMethods.add(sourceMethod);
                    }
                    methodsByIdentifier.put(identifier, classMethods);

                } catch (final IOException e) {
                    LOG.warn("Failed to parse new class {}: {}",
                            sourceFile, e.getMessage());
                }
            }

            // 11. Bind unchanged class IDs in the new graph
            for (final String identifier : unchanged) {
                final SourceClass existing = existingByFQCN.get(identifier);
                if (existing != null) {
                    newGraph.bindClassId(identifier, existing.id());
                }
            }

            // 12. Persist new/updated classes and methods
            persistClassesAndMethods(addedClasses, addedMethods,
                    updatedMethods);

            // 13. Re-extract method parameters for changed classes
            final Set<String> knownIdentifiers = newGraph.identifiers();
            final List<MethodParameter> allParams = new ArrayList<>();
            final Set<String> changedIdentifiers = new HashSet<>(toAdd);
            changedIdentifiers.addAll(toUpdate);

            for (final String identifier : changedIdentifiers) {
                final List<SourceMethod> methods =
                        methodsByIdentifier.get(identifier);
                final String sourceFile = newGraph.sourceFile(identifier);

                if (methods == null || methods.isEmpty()
                        || sourceFile == null) {
                    continue;
                }

                collectMethodParams(parser, cloneDir, sourceRootPath,
                        knownIdentifiers, newGraph,
                        identifier, sourceFile, methods, allParams);
            }

            persistMethodParameters(allParams);

            // 14. Claude enrichment (only changed + new)
            int enrichedCount = 0;
            int enrichFailedCount = 0;

            if (claudeApiClient != null && !changedIdentifiers.isEmpty()) {
                LOG.info("Claude enrichment for {} changed classes",
                        changedIdentifiers.size());

                final List<String> toEnrich =
                        new ArrayList<>(changedIdentifiers);

                for (int i = 0; i < toEnrich.size(); i += BATCH_SIZE) {
                    final List<String> batch = toEnrich.subList(
                            i, Math.min(i + BATCH_SIZE, toEnrich.size()));

                    final List<EnrichmentInput> inputs = new ArrayList<>();
                    for (final String identifier : batch) {
                        final String sourceFile =
                                newGraph.sourceFile(identifier);
                        final Path filePath = cloneDir.resolve(sourceFile);

                        if (!Files.exists(filePath)) {
                            continue;
                        }

                        try {
                            final String sourceCode =
                                    Files.readString(filePath);
                            final ClassType inferredType =
                                    parser.inferClassType(filePath);
                            final List<StaticMethodInfo> methods =
                                    parser.extractMethods(filePath);
                            final List<String> methodNames = methods.stream()
                                    .map(StaticMethodInfo::methodName)
                                    .toList();

                            inputs.add(new EnrichmentInput(
                                    sourceCode, identifier,
                                    parser.language(),
                                    inferredType.name(), methodNames));
                        } catch (final IOException e) {
                            LOG.warn("Failed to read source for"
                                            + " enrichment {}: {}",
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
                        applyEnrichment(result, newGraph);
                        enrichedCount++;
                    }
                }
            }

            // 15. Persist graph and update project
            final String graphJson = newGraph.toJson();
            completeSync(project, graphJson, headCommitHash);

            graphCache.put(project.id(), project.name(), newGraph);

            LOG.info("Sync completed for {}. Added: {}, Updated: {},"
                            + " Deleted: {}, Unchanged: {}, Enriched: {},"
                            + " EnrichFailed: {}",
                    project.name(), addedClasses.size(),
                    updatedClasses.size(), toDelete.size(),
                    unchanged.size(), enrichedCount, enrichFailedCount);

            return new SyncResult(
                    true, project.name(), headCommitHash,
                    addedClasses.size(), updatedClasses.size(),
                    toDelete.size(), unchanged.size(),
                    enrichedCount, enrichFailedCount, null);

        } catch (final Exception e) {
            LOG.error("Sync failed for {}: {}", project.name(),
                    e.getMessage(), e);
            markProjectError(project);
            return SyncResult.failure(project.name(), e.getMessage());
        } finally {
            closeGit(git);
            cleanupCloneDir(cloneDir);
        }
    }

    /**
     * Computes the git diff between two commits.
     *
     * @param repo the JGit repository
     * @param git the JGit Git instance
     * @param oldCommitHash the previous commit hash
     * @param newCommitHash the new HEAD commit hash
     * @return the diff result
     */
    private GitDiffResult computeDiff(final Repository repo,
            final Git git, final String oldCommitHash,
            final String newCommitHash) throws IOException, GitAPIException {

        if (oldCommitHash == null || oldCommitHash.isBlank()) {
            LOG.info("No previous commit hash, treating as full resync");
            return GitDiffResult.fullResync(newCommitHash);
        }

        final ObjectId oldHead = repo.resolve(oldCommitHash);
        if (oldHead == null) {
            LOG.warn("Old commit {} not found (force push?), "
                    + "treating as full resync", oldCommitHash);
            return GitDiffResult.fullResync(newCommitHash);
        }

        final ObjectId newHead = repo.resolve(newCommitHash);

        final AbstractTreeIterator oldTree =
                prepareTreeParser(repo, oldHead);
        final AbstractTreeIterator newTree =
                prepareTreeParser(repo, newHead);

        final List<DiffEntry> diffs = git.diff()
                .setOldTree(oldTree)
                .setNewTree(newTree)
                .call();

        final Set<String> changedFiles = new HashSet<>();
        final Set<String> deletedFiles = new HashSet<>();

        for (final DiffEntry entry : diffs) {
            switch (entry.getChangeType()) {
                case ADD -> changedFiles.add(entry.getNewPath());
                case MODIFY -> changedFiles.add(entry.getNewPath());
                case DELETE -> deletedFiles.add(entry.getOldPath());
                case RENAME -> {
                    deletedFiles.add(entry.getOldPath());
                    changedFiles.add(entry.getNewPath());
                }
                case COPY -> changedFiles.add(entry.getNewPath());
            }
        }

        return GitDiffResult.of(newCommitHash, changedFiles, deletedFiles);
    }

    /**
     * Prepares a tree parser for a given commit.
     */
    private AbstractTreeIterator prepareTreeParser(
            final Repository repo, final ObjectId commitId)
            throws IOException {

        try (final RevWalk walk = new RevWalk(repo)) {
            final RevCommit commit = walk.parseCommit(commitId);
            final RevTree tree = walk.parseTree(commit.getTree().getId());

            final CanonicalTreeParser parser = new CanonicalTreeParser();
            try (final ObjectReader reader = repo.newObjectReader()) {
                parser.reset(reader, tree.getId());
            }

            walk.dispose();
            return parser;
        }
    }

    /**
     * Applies Claude enrichment results to persisted records.
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

        ClassType classType = null;
        if (result.classTypeCorrection() != null
                && !"null".equals(result.classTypeCorrection())) {
            classType = ClassType.fromString(result.classTypeCorrection());
        }

        if (classType != null) {
            sourceClassRepository.updateEnrichment(
                    classId, classType, result.description());
        } else {
            final Optional<SourceClass> existing =
                    sourceClassRepository.findById(classId);
            if (existing.isPresent()) {
                sourceClassRepository.updateEnrichment(
                        classId, existing.get().classType(),
                        result.description());
            }
        }

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
            } else {
                LOG.debug("Method {} not found for enrichment in class {}",
                        methodName, result.fullClassName());
            }
        }
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
     * Collects method parameters for a single class.
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
     * Persists added classes, added methods, and updated methods.
     */
    @Transactional
    void persistClassesAndMethods(
            final List<SourceClass> addedClasses,
            final List<SourceMethod> addedMethods,
            final List<SourceMethod> updatedMethods) {
        sourceClassRepository.saveAll(addedClasses);
        final List<SourceMethod> allMethods = new ArrayList<>(addedMethods);
        allMethods.addAll(updatedMethods);
        sourceMethodRepository.saveAll(allMethods);
    }

    /**
     * Persists method parameters.
     */
    @Transactional
    void persistMethodParameters(final List<MethodParameter> params) {
        methodParameterRepository.saveAll(params);
    }

    /**
     * Completes the sync by persisting graph and updating status.
     */
    @Transactional
    void completeSync(final Project project, final String graphJson,
            final String commitHash) {
        project.updateGraphData(graphJson);
        project.syncCompleted(commitHash);
        projectRepository.update(project);
    }

    /**
     * Reverts the project status back to ANALYZED when no changes found.
     */
    @Transactional
    void revertToAnalyzed(final Project project) {
        project.syncCompleted(project.lastCommitHash());
        projectRepository.update(project);
    }

    /**
     * Marks the project as errored.
     */
    @Transactional
    void markProjectError(final Project project) {
        project.markError();
        projectRepository.update(project);
    }

    /**
     * Clones a repository using JGit (full clone, no depth limit).
     */
    private Path cloneRepository(final Project project) throws IOException {
        final Path cloneDir = Path.of(cloneBasePath,
                project.name() + "-sync-" + System.currentTimeMillis());
        Files.createDirectories(cloneDir);

        LOG.info("Cloning {} (branch: {}) to {} for sync",
                project.repositoryUrl(), project.defaultBranch(), cloneDir);

        try {
            Git.cloneRepository()
                    .setURI(project.repositoryUrl().value())
                    .setDirectory(cloneDir.toFile())
                    .setBranch(project.defaultBranch())
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
     * Detects the appropriate source parser based on project markers.
     */
    private SourceParser detectParser(final Path cloneDir) {
        if (Files.exists(cloneDir.resolve("package.json"))
                && !Files.exists(cloneDir.resolve("pom.xml"))
                && !Files.exists(cloneDir.resolve("build.gradle"))
                && !Files.exists(cloneDir.resolve("build.gradle.kts"))) {
            return new NodeJsGraalParser();
        }
        return new JavaSourceParser();
    }

    /**
     * Reads the README.md content from the cloned repository.
     */
    private String readReadme(final Path cloneDir) {
        final Path readme = cloneDir.resolve("README.md");
        if (!Files.exists(readme)) {
            return null;
        }
        try {
            final String content = Files.readString(readme);
            if (content.isBlank()) {
                return null;
            }
            return content.length() > MAX_README_LENGTH
                    ? content.substring(0, MAX_README_LENGTH)
                            + "\n...(truncated)"
                    : content;
        } catch (final IOException e) {
            LOG.warn("Failed to read README.md: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Safely closes a Git instance, logging any errors.
     */
    private void closeGit(final Git git) {
        if (git == null) {
            return;
        }
        try {
            git.close();
        } catch (final Exception e) {
            LOG.warn("Failed to close Git: {}", e.getMessage());
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
            LOG.warn("Failed to cleanup clone directory: {}", cloneDir, e);
        }
    }

    /**
     * Summary of an incremental sync operation.
     *
     * @param success whether the sync succeeded
     * @param projectName the project name
     * @param commitHash the new HEAD commit hash
     * @param addedClasses number of new classes
     * @param updatedClasses number of re-parsed classes
     * @param deletedClasses number of removed classes
     * @param unchangedClasses number of untouched classes
     * @param enrichedClasses number of successfully enriched classes
     * @param enrichFailedClasses number of enrichment failures
     * @param errorMessage error message if failed
     */
    public record SyncResult(
            boolean success,
            String projectName,
            String commitHash,
            int addedClasses,
            int updatedClasses,
            int deletedClasses,
            int unchangedClasses,
            int enrichedClasses,
            int enrichFailedClasses,
            String errorMessage
    ) {

        /** Creates a no-changes result. */
        public static SyncResult noChanges(final String projectName) {
            return new SyncResult(true, projectName, null,
                    0, 0, 0, 0, 0, 0, null);
        }

        /** Creates a failure result. */
        public static SyncResult failure(final String projectName,
                final String errorMessage) {
            return new SyncResult(false, projectName, null,
                    0, 0, 0, 0, 0, 0, errorMessage);
        }
    }

    /**
     * Aggregated result of syncing all eligible projects.
     *
     * @param success true if every project synced without errors
     * @param totalProjects number of eligible projects
     * @param successCount number of successful syncs
     * @param failureCount number of failed syncs
     * @param results per-project sync results
     */
    public record SyncAllResult(
            boolean success,
            int totalProjects,
            int successCount,
            int failureCount,
            List<SyncResult> results
    ) {}

    /**
     * Syncs all projects with {@code ANALYZED} status incrementally.
     *
     * <p>Replicates the same logic as the scheduled cron task:
     * fetches all eligible projects and syncs each one using git diffs,
     * re-enriching only changed or new classes.</p>
     *
     * @return the aggregated sync result
     */
    public SyncAllResult syncAllProjects() {
        LOG.info("Starting manual sync for all projects");

        final List<Project> projects = projectRepository.findByStatuses(
                List.of(ProjectStatus.ANALYZED, ProjectStatus.ERROR));

        if (projects.isEmpty()) {
            LOG.info("No projects eligible for sync");
            return new SyncAllResult(true, 0, 0, 0, List.of());
        }

        LOG.info("Found {} projects eligible for sync", projects.size());

        int successCount = 0;
        int failureCount = 0;
        final List<SyncResult> results = new ArrayList<>();

        for (final Project project : projects) {
            try {
                final SyncResult result = syncProject(project);

                if (result.success()) {
                    successCount++;
                } else {
                    failureCount++;
                }
                results.add(result);

            } catch (final Exception e) {
                failureCount++;
                LOG.error("Unexpected error syncing project {}: {}",
                        project.name(), e.getMessage(), e);
                results.add(SyncResult.failure(
                        project.name(), e.getMessage()));
            }
        }

        LOG.info("Manual sync complete. Success: {}, Failed: {}",
                successCount, failureCount);

        return new SyncAllResult(
                failureCount == 0,
                projects.size(),
                successCount,
                failureCount,
                results);
    }
}
