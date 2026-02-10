package co.fanki.domainmcp.container.domain;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Builds Docker images for code analysis containers.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Component
public class DockerImageBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(
            DockerImageBuilder.class);

    private final DockerClient dockerClient;

    public DockerImageBuilder() {
        final var config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .build();

        final var httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(100)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofMinutes(5))
                .build();

        this.dockerClient = DockerClientImpl.getInstance(config, httpClient);
    }

    /**
     * Ensures all analysis images are built at startup.
     */
    @PostConstruct
    public void ensureImagesExist() {
        LOG.info("Checking analysis container images...");

        for (ContainerImage image : ContainerImage.values()) {
            ensureImageExists(image);
        }

        LOG.info("All analysis images ready");
    }

    /**
     * Checks if an image exists, builds it if not.
     *
     * @param image the container image to check/build
     */
    public void ensureImageExists(final ContainerImage image) {
        if (imageExists(image.imageName())) {
            LOG.debug("Image exists: {}", image.imageName());
            return;
        }

        LOG.info("Building image: {}", image.imageName());
        buildImage(image);
    }

    /**
     * Forces a rebuild of an image.
     *
     * @param image the container image to rebuild
     */
    public void rebuildImage(final ContainerImage image) {
        LOG.info("Rebuilding image: {}", image.imageName());
        buildImage(image);
    }

    private boolean imageExists(final String imageName) {
        final List<Image> images = dockerClient.listImagesCmd()
                .withImageNameFilter(imageName)
                .exec();
        return !images.isEmpty();
    }

    private void buildImage(final ContainerImage image) {
        final String dockerfilePath = image.dockerfilePath();
        final String dockerfileName = Path.of(dockerfilePath).getFileName().toString();
        final Path tempDir = extractDockerfile(dockerfilePath, dockerfileName);

        try {
            final String imageId = dockerClient.buildImageCmd()
                    .withDockerfile(tempDir.resolve(dockerfileName).toFile())
                    .withBaseDirectory(tempDir.toFile())
                    .withTags(Set.of(image.imageName()))
                    .withPull(true)
                    .exec(new BuildImageResultCallback())
                    .awaitImageId();

            LOG.info("Built image {} with ID: {}", image.imageName(), imageId);

        } finally {
            cleanupTempDir(tempDir);
        }
    }

    private Path extractDockerfile(final String dockerfilePath,
            final String dockerfileName) {
        try {
            final Path tempDir = Files.createTempDirectory("docker-build");
            final ClassPathResource resource = new ClassPathResource(dockerfilePath);

            try (InputStream is = resource.getInputStream()) {
                Files.copy(is, tempDir.resolve(dockerfileName),
                        StandardCopyOption.REPLACE_EXISTING);
            }

            return tempDir;

        } catch (IOException e) {
            throw new RuntimeException("Failed to extract Dockerfile: "
                    + dockerfilePath, e);
        }
    }

    private void cleanupTempDir(final Path tempDir) {
        try {
            Files.walk(tempDir)
                    .sorted((a, b) -> -a.compareTo(b))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            LOG.warn("Failed to delete: {}", path);
                        }
                    });
        } catch (IOException e) {
            LOG.warn("Failed to cleanup temp dir: {}", tempDir);
        }
    }

}
