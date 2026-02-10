package co.fanki.domainmcp.container.domain;

import co.fanki.domainmcp.shared.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Wrapper around a TestContainers container for Claude Code analysis.
 *
 * <p>Manages the lifecycle of a Docker container that runs Claude Code
 * for analyzing repositories.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public class AnalysisContainer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(
            AnalysisContainer.class);

    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(10);

    private static final String CONTAINER_SSH_DIR = "/home/claude/.ssh";

    private final GenericContainer<?> container;
    private final ContainerImage image;
    private final String sshKeyPath;

    /**
     * Creates a new analysis container without SSH key support.
     *
     * <p>Use this constructor for public repositories that don't require
     * SSH authentication.</p>
     *
     * @param theImage the container image to use
     * @param claudeApiKey the Claude API key
     */
    public AnalysisContainer(final ContainerImage theImage,
            final String claudeApiKey) {
        this(theImage, claudeApiKey, null);
    }

    /**
     * Creates a new analysis container with SSH key support.
     *
     * <p>The container image is built from the Dockerfile in classpath resources
     * if it doesn't exist locally. The image is cached for subsequent runs.</p>
     *
     * <p>If an SSH key path is provided, the SSH directory is mounted into the
     * container to allow cloning private repositories.</p>
     *
     * @param theImage the container image to use
     * @param claudeApiKey the Claude API key
     * @param theSshKeyPath path to SSH key file (e.g., ~/.ssh/id_rsa), or null
     */
    public AnalysisContainer(final ContainerImage theImage,
            final String claudeApiKey,
            final String theSshKeyPath) {
        Preconditions.requireNonNull(theImage, "Image is required");
        Preconditions.requireNonBlank(claudeApiKey, "Claude API key is required");

        this.image = theImage;
        this.sshKeyPath = theSshKeyPath;

        GenericContainer<?> c = new GenericContainer<>(buildImage(theImage))
                .withEnv("ANTHROPIC_API_KEY", claudeApiKey)
                .withEnv("CLAUDE_CODE_USE_BEDROCK", "0")
                .withCommand("tail", "-f", "/dev/null")
                .withStartupTimeout(STARTUP_TIMEOUT)
                .withLogConsumer(new Slf4jLogConsumer(LOG));

        if (theSshKeyPath != null && !theSshKeyPath.isBlank()) {
            c = configureSshMount(c, theSshKeyPath);
        }

        this.container = c;
    }

    /**
     * Configures SSH key mounting for the container.
     *
     * <p>Mounts the entire .ssh directory and configures GIT_SSH_COMMAND to
     * explicitly use the key file with the container path.</p>
     *
     * @param container the container to configure
     * @param keyPath the path to the SSH key
     * @return the configured container
     */
    private GenericContainer<?> configureSshMount(final GenericContainer<?> container,
            final String keyPath) {
        final Path sshKeyFile = resolveSshKeyPath(keyPath);
        final Path sshDir = sshKeyFile.getParent();

        if (!Files.exists(sshDir)) {
            LOG.warn("SSH directory does not exist: {}", sshDir);
            return container;
        }

        if (!Files.exists(sshKeyFile)) {
            LOG.warn("SSH key file does not exist: {}", sshKeyFile);
            return container;
        }

        // Get the key filename to construct container path
        final String keyFileName = sshKeyFile.getFileName().toString();
        final String containerKeyPath = CONTAINER_SSH_DIR + "/" + keyFileName;

        LOG.info("Mounting SSH directory: {} -> {}", sshDir, CONTAINER_SSH_DIR);
        LOG.info("Using SSH key: {}", containerKeyPath);

        // Configure SSH to use the specific key and accept new host keys
        final String sshCommand = String.format(
                "ssh -i %s -o StrictHostKeyChecking=accept-new -o IdentitiesOnly=yes",
                containerKeyPath);

        return container
                .withFileSystemBind(sshDir.toString(), CONTAINER_SSH_DIR, BindMode.READ_ONLY)
                .withEnv("GIT_SSH_COMMAND", sshCommand);
    }

    /**
     * Resolves the SSH key path, expanding ~ to home directory.
     *
     * @param keyPath the key path
     * @return the resolved path
     */
    private Path resolveSshKeyPath(final String keyPath) {
        String resolved = keyPath;
        if (resolved.startsWith("~")) {
            resolved = System.getProperty("user.home") + resolved.substring(1);
        }
        return Path.of(resolved);
    }

    /**
     * Builds the Docker image from the Dockerfile in classpath resources.
     *
     * <p>Uses the image name as cache key so the image is only built once.
     * The deleteOnExit flag is set to false to preserve the image between runs.</p>
     *
     * @param containerImage the container image configuration
     * @return the ImageFromDockerfile that will build/cache the image
     */
    private ImageFromDockerfile buildImage(final ContainerImage containerImage) {
        LOG.info("Building Docker image: {} from {}",
                containerImage.imageName(), containerImage.dockerfilePath());

        return new ImageFromDockerfile(containerImage.imageName(), false)
                .withFileFromClasspath("Dockerfile", containerImage.dockerfilePath());
    }

    /**
     * Starts the container.
     */
    public void start() {
        LOG.info("Starting analysis container with image: {}", image.imageName());
        container.start();
        LOG.info("Container started successfully");
    }

    /**
     * Clones a repository into the container.
     *
     * @param repositoryUrl the repository URL
     * @param targetDir the target directory inside the container
     * @return the exit code
     */
    public ExecResult cloneRepository(final String repositoryUrl,
            final String targetDir) throws IOException, InterruptedException {
        LOG.info("Cloning repository: {} to {}", repositoryUrl, targetDir);

        return executeCommand("git", "clone", "--depth", "1",
                repositoryUrl, targetDir);
    }

    /**
     * Runs Claude Code analysis on a directory.
     *
     * <p>Uses Claude Code CLI in print mode (-p) which is non-interactive
     * and outputs the response directly. The --dangerously-skip-permissions
     * flag is used since we're running in an isolated container.</p>
     *
     * @param workDir the working directory
     * @param prompt the analysis prompt
     * @return the execution result with Claude's output
     */
    public ExecResult runClaudeAnalysis(final String workDir,
            final String prompt) throws IOException, InterruptedException {
        LOG.info("Running Claude Code analysis in: {}", workDir);

        // Simplify prompt: remove newlines and escape for shell
        final String simplePrompt = prompt
                .replace("\n", " ")
                .replace("\r", "")
                .replace("\"", "\\\"")
                .replaceAll("\\s+", " ")
                .trim();

        LOG.debug("Prompt length: {}", simplePrompt.length());

        return executeCommand("sh", "-c",
                String.format("cd %s && claude -p --dangerously-skip-permissions --model sonnet \"%s\" 2>&1",
                        workDir, simplePrompt));
    }

    /**
     * Reads a file from inside the container.
     *
     * <p>Executes {@code cat} on the given path. Returns the file content
     * if the file exists, or an empty string if it does not.</p>
     *
     * @param path the absolute path inside the container
     * @return the file content, or empty string if not found
     */
    public String readFileFromContainer(final String path)
            throws IOException, InterruptedException {

        final ExecResult result = executeCommand("cat", path);
        if (!result.isSuccess()) {
            LOG.debug("File not found in container: {}", path);
            return "";
        }
        return result.stdout();
    }

    /**
     * Executes a command inside the container.
     *
     * @param command the command and arguments
     * @return the execution result
     */
    public ExecResult executeCommand(final String... command)
            throws IOException, InterruptedException {
        LOG.debug("Executing command: {}", String.join(" ", command));

        final org.testcontainers.containers.Container.ExecResult result =
                container.execInContainer(command);

        return new ExecResult(
                result.getExitCode(),
                result.getStdout(),
                result.getStderr());
    }

    /**
     * Returns the container image being used.
     *
     * @return the container image
     */
    public ContainerImage image() {
        return image;
    }

    /**
     * Checks if the container is running.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return container.isRunning();
    }

    @Override
    public void close() {
        LOG.info("Stopping analysis container");
        container.stop();
    }

    private String escapeForShell(final String input) {
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("$", "\\$")
                .replace("`", "\\`")
                .replace("\n", " ")
                .replace("\r", "");
    }

    /**
     * Result of executing a command in the container.
     */
    public record ExecResult(int exitCode, String stdout, String stderr) {

        /**
         * Checks if the command succeeded (exit code 0).
         *
         * @return true if successful
         */
        public boolean isSuccess() {
            return exitCode == 0;
        }

        /**
         * Returns the combined output (stdout + stderr).
         *
         * @return combined output
         */
        public String combinedOutput() {
            final StringBuilder sb = new StringBuilder();
            if (stdout != null && !stdout.isBlank()) {
                sb.append(stdout);
            }
            if (stderr != null && !stderr.isBlank()) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(stderr);
            }
            return sb.toString();
        }
    }

}
