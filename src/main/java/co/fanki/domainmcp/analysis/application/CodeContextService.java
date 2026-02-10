package co.fanki.domainmcp.analysis.application;

import co.fanki.domainmcp.analysis.domain.ClassType;
import co.fanki.domainmcp.analysis.domain.SourceClass;
import co.fanki.domainmcp.analysis.domain.SourceClassRepository;
import co.fanki.domainmcp.analysis.domain.SourceMethod;
import co.fanki.domainmcp.analysis.domain.SourceMethodRepository;
import co.fanki.domainmcp.container.application.ContainerAnalysisService;
import co.fanki.domainmcp.container.domain.AnalysisOutput;
import co.fanki.domainmcp.container.domain.AnalysisOutput.ClassInfo;
import co.fanki.domainmcp.container.domain.AnalysisOutput.MethodInfo;
import co.fanki.domainmcp.container.domain.ContainerImage;
import co.fanki.domainmcp.project.domain.Project;
import co.fanki.domainmcp.project.domain.ProjectRepository;
import co.fanki.domainmcp.project.domain.RepositoryUrl;
import co.fanki.domainmcp.shared.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Application service for code context operations.
 *
 * <p>Provides functionality to analyze projects and retrieve context
 * about classes and methods for Datadog stack trace correlation.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Service
public class CodeContextService {

    private static final Logger LOG = LoggerFactory.getLogger(
            CodeContextService.class);

    private final ProjectRepository projectRepository;
    private final SourceClassRepository sourceClassRepository;
    private final SourceMethodRepository sourceMethodRepository;
    private final ContainerAnalysisService containerAnalysisService;

    /**
     * Creates a new CodeContextService.
     *
     * @param theProjectRepository the project repository
     * @param theSourceClassRepository the source class repository
     * @param theSourceMethodRepository the source method repository
     * @param theContainerAnalysisService the container analysis service
     */
    public CodeContextService(
            final ProjectRepository theProjectRepository,
            final SourceClassRepository theSourceClassRepository,
            final SourceMethodRepository theSourceMethodRepository,
            final ContainerAnalysisService theContainerAnalysisService) {
        this.projectRepository = theProjectRepository;
        this.sourceClassRepository = theSourceClassRepository;
        this.sourceMethodRepository = theSourceMethodRepository;
        this.containerAnalysisService = theContainerAnalysisService;
    }

    /**
     * Analyzes a project and stores the extracted class/method information.
     *
     * @param repositoryUrl the git repository URL
     * @param branch the branch to analyze (optional, defaults to main)
     * @return the analysis result summary
     */
    @Transactional
    public AnalysisResult analyzeProject(final String repositoryUrl,
            final String branch) {

        LOG.info("Starting project analysis for: {}", repositoryUrl);

        final RepositoryUrl repoUrl = RepositoryUrl.of(repositoryUrl);
        final String projectName = repoUrl.repositoryName();
        final String branchName = branch != null ? branch : "main";

        Project project = projectRepository.findByRepositoryUrl(repoUrl)
                .orElse(null);

        if (project == null) {
            project = Project.create(projectName, repoUrl, branchName);
            projectRepository.save(project);
            LOG.info("Created new project: {}", project.id());
        } else {
            sourceClassRepository.deleteByProjectId(project.id());
            LOG.info("Cleared existing analysis data for project: {}", project.id());
        }

        project.startAnalysis();
        projectRepository.update(project);

        final AnalysisOutput output = containerAnalysisService
                .analyzeRepositoryDetailed(repoUrl, ContainerImage.JAVA);

        if (!output.isSuccess()) {
            project.markError();
            projectRepository.update(project);
            throw new DomainException("Analysis failed: " + output.errorMessage(),
                    "ANALYSIS_FAILED");
        }

        int classCount = 0;
        int methodCount = 0;

        for (final ClassInfo classInfo : output.classes()) {
            final SourceClass sourceClass = createSourceClass(project.id(), classInfo);
            sourceClassRepository.save(sourceClass);
            classCount++;

            for (final MethodInfo methodInfo : classInfo.methods()) {
                final SourceMethod sourceMethod = createSourceMethod(
                        sourceClass.id(), methodInfo);
                sourceMethodRepository.save(sourceMethod);
                methodCount++;
            }
        }

        project.analysisCompleted("HEAD");
        projectRepository.update(project);

        final long endpointCount = sourceMethodRepository
                .countEndpointsByProjectId(project.id());

        LOG.info("Analysis completed. Classes: {}, Methods: {}, Endpoints: {}",
                classCount, methodCount, endpointCount);

        return new AnalysisResult(
                true,
                project.id(),
                classCount,
                (int) endpointCount,
                "Analysis complete");
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

        final Optional<Project> project = projectRepository.findById(sc.projectId());
        final String projectUrl = project.map(p -> p.repositoryUrl().value())
                .orElse(null);

        return ClassContext.found(sc, methods, projectUrl);
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

        LOG.debug("Getting context for method: {}.{}", fullClassName, methodName);

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
            return MethodContext.classFoundMethodMissing(fullClassName, methodName);
        }

        final Optional<Project> project = projectRepository.findById(sc.projectId());
        final String projectUrl = project.map(p -> p.repositoryUrl().value())
                .orElse(null);

        return MethodContext.found(sc, method.get(), projectUrl);
    }

    /**
     * Gets context for a stack trace (multiple class/method pairs).
     *
     * @param stackFrames the stack trace frames
     * @return the stack trace context
     */
    public StackTraceContext getStackTraceContext(
            final List<StackFrame> stackFrames) {

        LOG.debug("Getting context for {} stack frames", stackFrames.size());

        final List<ExecutionPathEntry> entries = new ArrayList<>();
        final List<StackFrame> missing = new ArrayList<>();
        String projectUrl = null;

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
                    .findByClassNameAndMethodName(frame.className(), frame.methodName());

            if (method.isEmpty()) {
                missing.add(frame);
                entries.add(ExecutionPathEntry.classMissing(order++, frame, sc));
                continue;
            }

            if (projectUrl == null) {
                final Optional<Project> project = projectRepository
                        .findById(sc.projectId());
                projectUrl = project.map(p -> p.repositoryUrl().value())
                        .orElse(null);
            }

            entries.add(ExecutionPathEntry.found(order++, sc, method.get()));
        }

        return new StackTraceContext(entries, missing, projectUrl);
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
                    project.status().name(),
                    project.lastAnalyzedAt(),
                    classCount,
                    endpointCount));
        }

        return summaries;
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
                commonPrefix = commonPackagePrefix(commonPrefix, sc.packageName());
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

    private SourceClass createSourceClass(final String projectId,
            final ClassInfo classInfo) {
        return SourceClass.create(
                projectId,
                classInfo.fullClassName(),
                ClassType.fromString(classInfo.classType()),
                classInfo.description(),
                classInfo.sourceFile());
    }

    private SourceMethod createSourceMethod(final String classId,
            final MethodInfo methodInfo) {
        return SourceMethod.create(
                classId,
                methodInfo.methodName(),
                methodInfo.description(),
                methodInfo.businessLogic(),
                methodInfo.dependencies(),
                methodInfo.exceptions(),
                methodInfo.httpMethod(),
                methodInfo.httpPath(),
                methodInfo.lineNumber());
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
            List<MethodSummary> methods,
            String projectUrl,
            String message,
            List<KnownProject> knownProjects
    ) {
        public static ClassContext found(final SourceClass sc,
                final List<SourceMethod> methods,
                final String projectUrl) {
            final List<MethodSummary> methodSummaries = methods.stream()
                    .map(m -> new MethodSummary(
                            m.methodName(),
                            m.description(),
                            m.businessLogic(),
                            m.dependencies()))
                    .toList();

            return new ClassContext(true, sc.fullClassName(),
                    sc.classType().name(), sc.description(),
                    methodSummaries, projectUrl, null, List.of());
        }

        public static ClassContext notFound(final String className,
                final List<KnownProject> knownProjects) {
            return new ClassContext(false, className, null, null,
                    List.of(), null, "No context available for this class",
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
            List<String> businessLogic,
            List<String> dependencies,
            List<String> exceptions,
            String sourceFile,
            Integer lineNumber,
            String projectUrl,
            String message,
            List<KnownProject> knownProjects
    ) {
        public static MethodContext found(final SourceClass sc,
                final SourceMethod method,
                final String projectUrl) {
            return new MethodContext(true, sc.fullClassName(),
                    method.methodName(), method.httpEndpoint(),
                    method.description(), method.businessLogic(),
                    method.dependencies(), method.exceptions(),
                    sc.sourceFile(), method.lineNumber(),
                    projectUrl, null, List.of());
        }

        public static MethodContext notFound(final String className,
                final String methodName, final List<KnownProject> knownProjects) {
            return new MethodContext(false, className, methodName,
                    null, null, List.of(), List.of(), List.of(),
                    null, null, null,
                    "No context available for this method",
                    knownProjects);
        }

        public static MethodContext classFoundMethodMissing(final String className,
                final String methodName) {
            return new MethodContext(false, className, methodName,
                    null, null, List.of(), List.of(), List.of(),
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
     * Context for a stack trace.
     */
    public record StackTraceContext(
            List<ExecutionPathEntry> executionPath,
            List<StackFrame> missingContext,
            String projectUrl
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
        public static ExecutionPathEntry found(final int order,
                final SourceClass sc, final SourceMethod method) {
            return new ExecutionPathEntry(order, sc.fullClassName(),
                    method.methodName(), sc.classType().name(),
                    method.description(), method.businessLogic(),
                    method.httpEndpoint(), true);
        }

        public static ExecutionPathEntry missing(final int order,
                final StackFrame frame) {
            return new ExecutionPathEntry(order, frame.className(),
                    frame.methodName(), null, null, List.of(), null, false);
        }

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
            String status,
            java.time.Instant lastAnalyzedAt,
            long classCount,
            long endpointCount
    ) {}

}
