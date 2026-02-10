package co.fanki.domainmcp.container.domain;

import co.fanki.domainmcp.shared.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
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

    private final GenericContainer<?> container;
    private final ContainerImage image;

    /**
     * Creates a new analysis container.
     *
     * @param theImage the container image to use
     * @param claudeApiKey the Claude API key
     */
    public AnalysisContainer(final ContainerImage theImage,
            final String claudeApiKey) {
        Preconditions.requireNonNull(theImage, "Image is required");
        Preconditions.requireNonBlank(claudeApiKey, "Claude API key is required");

        this.image = theImage;
        this.container = new GenericContainer<>(
                DockerImageName.parse(theImage.imageName()))
                .withEnv("ANTHROPIC_API_KEY", claudeApiKey)
                .withEnv("CLAUDE_CODE_USE_BEDROCK", "0")
                .withCommand("tail", "-f", "/dev/null")
                .withStartupTimeout(STARTUP_TIMEOUT)
                .withLogConsumer(new Slf4jLogConsumer(LOG));
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
     * @param workDir the working directory
     * @param prompt the analysis prompt
     * @return the execution result with Claude's output
     */
    public ExecResult runClaudeAnalysis(final String workDir,
            final String prompt) throws IOException, InterruptedException {
        LOG.info("Running Claude Code analysis in: {}", workDir);

        return executeCommand("sh", "-c",
                String.format("cd %s && claude --print \"%s\"", workDir,
                        escapeForShell(prompt)));
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
        return input.replace("\"", "\\\"")
                .replace("$", "\\$")
                .replace("`", "\\`");
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
