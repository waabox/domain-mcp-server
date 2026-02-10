package co.fanki.domainmcp.project.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for RepositoryUrl value object.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class RepositoryUrlTest {

    @Test
    void whenCreatingUrl_givenValidHttpsUrl_shouldCreate() {
        final RepositoryUrl url = RepositoryUrl.of(
                "https://github.com/example/repo.git");

        assertEquals("https://github.com/example/repo.git", url.value());
        assertTrue(url.isHttps());
        assertFalse(url.isSsh());
    }

    @Test
    void whenCreatingUrl_givenValidSshUrl_shouldCreate() {
        final RepositoryUrl url = RepositoryUrl.of(
                "git@github.com:example/repo.git");

        assertEquals("git@github.com:example/repo.git", url.value());
        assertTrue(url.isSsh());
        assertFalse(url.isHttps());
    }

    @Test
    void whenCreatingUrl_givenInvalidUrl_shouldThrowException() {
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryUrl.of("not-a-valid-url"));
    }

    @Test
    void whenCreatingUrl_givenBlankUrl_shouldThrowException() {
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryUrl.of("  "));
    }

    @Test
    void whenExtractingRepoName_givenHttpsUrl_shouldReturnName() {
        final RepositoryUrl url = RepositoryUrl.of(
                "https://github.com/example/my-project.git");

        assertEquals("my-project", url.repositoryName());
    }

    @Test
    void whenExtractingRepoName_givenSshUrl_shouldReturnName() {
        final RepositoryUrl url = RepositoryUrl.of(
                "git@github.com:example/my-project.git");

        assertEquals("my-project", url.repositoryName());
    }

    @Test
    void whenComparingUrls_givenSameValue_shouldBeEqual() {
        final RepositoryUrl url1 = RepositoryUrl.of(
                "https://github.com/example/repo.git");
        final RepositoryUrl url2 = RepositoryUrl.of(
                "https://github.com/example/repo.git");

        assertEquals(url1, url2);
        assertEquals(url1.hashCode(), url2.hashCode());
    }

}
