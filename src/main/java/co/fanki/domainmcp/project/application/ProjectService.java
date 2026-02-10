package co.fanki.domainmcp.project.application;

import co.fanki.domainmcp.project.domain.Project;
import co.fanki.domainmcp.project.domain.ProjectRepository;
import co.fanki.domainmcp.project.domain.ProjectStatus;
import co.fanki.domainmcp.project.domain.RepositoryUrl;
import co.fanki.domainmcp.shared.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Application service for project operations.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Service
public class ProjectService {

    private static final Logger LOG = LoggerFactory.getLogger(
            ProjectService.class);

    private final ProjectRepository projectRepository;

    /**
     * Creates a new ProjectService.
     *
     * @param theProjectRepository the project repository
     */
    public ProjectService(final ProjectRepository theProjectRepository) {
        this.projectRepository = theProjectRepository;
    }

    /**
     * Registers a new project for analysis.
     *
     * @param name the project name
     * @param repositoryUrl the git repository URL
     * @param defaultBranch the default branch (nullable, defaults to "main")
     * @return the created project
     * @throws DomainException if a project with the same URL already exists
     */
    public Project registerProject(final String name,
            final String repositoryUrl,
            final String defaultBranch) {
        LOG.info("Registering project: {} with repository: {}", name,
                repositoryUrl);

        final RepositoryUrl url = RepositoryUrl.of(repositoryUrl);

        if (projectRepository.existsByRepositoryUrl(url)) {
            throw new DomainException(
                    "A project with this repository URL already exists",
                    "PROJECT_ALREADY_EXISTS");
        }

        final Project project = defaultBranch != null
                ? Project.create(name, url, defaultBranch)
                : Project.create(name, url);

        projectRepository.save(project);

        LOG.info("Project registered with ID: {}", project.id());
        return project;
    }

    /**
     * Finds a project by ID.
     *
     * @param projectId the project ID
     * @return the project if found
     */
    public Optional<Project> findById(final String projectId) {
        return projectRepository.findById(projectId);
    }

    /**
     * Finds a project by ID, throwing if not found.
     *
     * @param projectId the project ID
     * @return the project
     * @throws DomainException if the project is not found
     */
    public Project getById(final String projectId) {
        return findById(projectId)
                .orElseThrow(() -> new DomainException(
                        "Project not found: " + projectId,
                        "PROJECT_NOT_FOUND"));
    }

    /**
     * Finds a project by repository URL.
     *
     * @param repositoryUrl the repository URL
     * @return the project if found
     */
    public Optional<Project> findByRepositoryUrl(final String repositoryUrl) {
        return projectRepository.findByRepositoryUrl(
                RepositoryUrl.of(repositoryUrl));
    }

    /**
     * Lists all projects.
     *
     * @return list of all projects
     */
    public List<Project> listProjects() {
        return projectRepository.findAll();
    }

    /**
     * Lists projects by status.
     *
     * @param status the status to filter by
     * @return list of projects with the given status
     */
    public List<Project> listByStatus(final ProjectStatus status) {
        return projectRepository.findByStatus(status);
    }

    /**
     * Updates the project when analysis starts.
     *
     * @param projectId the project ID
     */
    public void markAnalysisStarted(final String projectId) {
        final Project project = getById(projectId);
        project.startAnalysis();
        projectRepository.update(project);
    }

    /**
     * Updates the project after successful analysis.
     *
     * @param projectId the project ID
     * @param commitHash the analyzed commit hash
     */
    public void markAnalysisCompleted(final String projectId,
            final String commitHash) {
        final Project project = getById(projectId);
        project.analysisCompleted(commitHash);
        projectRepository.update(project);
        LOG.info("Project {} analysis completed for commit: {}",
                projectId, commitHash);
    }

    /**
     * Marks the project as having an error.
     *
     * @param projectId the project ID
     */
    public void markError(final String projectId) {
        final Project project = getById(projectId);
        project.markError();
        projectRepository.update(project);
        LOG.warn("Project {} marked as error", projectId);
    }

    /**
     * Deletes a project.
     *
     * @param projectId the project ID
     */
    public void deleteProject(final String projectId) {
        projectRepository.delete(projectId);
        LOG.info("Project {} deleted", projectId);
    }

}
