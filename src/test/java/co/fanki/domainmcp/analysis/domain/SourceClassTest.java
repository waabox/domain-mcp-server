package co.fanki.domainmcp.analysis.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for SourceClass entity.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class SourceClassTest {

    @Test
    void whenCreatingClass_givenValidData_shouldCreateWithCorrectValues() {
        final String projectId = UUID.randomUUID().toString();

        final SourceClass sourceClass = SourceClass.create(
                projectId,
                "co.fanki.user.UserService",
                ClassType.SERVICE,
                "Manages user operations",
                "src/main/java/co/fanki/user/UserService.java");

        assertNotNull(sourceClass.id());
        assertEquals(projectId, sourceClass.projectId());
        assertEquals("co.fanki.user.UserService", sourceClass.fullClassName());
        assertEquals("UserService", sourceClass.simpleName());
        assertEquals("co.fanki.user", sourceClass.packageName());
        assertEquals(ClassType.SERVICE, sourceClass.classType());
        assertEquals("Manages user operations", sourceClass.description());
        assertNotNull(sourceClass.createdAt());
    }

    @Test
    void whenCreatingClass_givenNoPackage_shouldHandleSimpleName() {
        final String projectId = UUID.randomUUID().toString();

        final SourceClass sourceClass = SourceClass.create(
                projectId,
                "UserService",
                ClassType.SERVICE,
                "Manages user operations",
                "UserService.java");

        assertEquals("UserService", sourceClass.fullClassName());
        assertEquals("UserService", sourceClass.simpleName());
        assertNull(sourceClass.packageName());
    }

    @Test
    void whenCreatingClass_givenNullProjectId_shouldThrowException() {
        assertThrows(IllegalArgumentException.class, () ->
                SourceClass.create(null, "co.fanki.Test", ClassType.OTHER,
                        null, null));
    }

    @Test
    void whenCreatingClass_givenBlankClassName_shouldThrowException() {
        final String projectId = UUID.randomUUID().toString();

        assertThrows(IllegalArgumentException.class, () ->
                SourceClass.create(projectId, "  ", ClassType.OTHER,
                        null, null));
    }

    @Test
    void whenCreatingClass_givenNullClassType_shouldThrowException() {
        final String projectId = UUID.randomUUID().toString();

        assertThrows(IllegalArgumentException.class, () ->
                SourceClass.create(projectId, "co.fanki.Test", null,
                        null, null));
    }

    @Test
    void whenCheckingPackage_givenExactMatch_shouldReturnTrue() {
        final SourceClass sourceClass = createTestClass("co.fanki.user.UserService");

        assertTrue(sourceClass.belongsToPackage("co.fanki.user"));
    }

    @Test
    void whenCheckingPackage_givenSubpackage_shouldReturnTrue() {
        final SourceClass sourceClass = createTestClass(
                "co.fanki.user.domain.User");

        assertTrue(sourceClass.belongsToPackage("co.fanki.user"));
        assertTrue(sourceClass.belongsToPackage("co.fanki"));
        assertTrue(sourceClass.belongsToPackage("co"));
    }

    @Test
    void whenCheckingPackage_givenDifferentPackage_shouldReturnFalse() {
        final SourceClass sourceClass = createTestClass("co.fanki.user.UserService");

        assertFalse(sourceClass.belongsToPackage("co.fanki.order"));
        assertFalse(sourceClass.belongsToPackage("com.example"));
    }

    @Test
    void whenCheckingPackage_givenPartialMatch_shouldReturnFalse() {
        final SourceClass sourceClass = createTestClass("co.fanki.user.UserService");

        assertFalse(sourceClass.belongsToPackage("co.fanki.use"));
    }

    @Test
    void whenCheckingPackage_givenNullPackage_shouldReturnFalse() {
        final SourceClass sourceClass = createTestClass("co.fanki.user.UserService");

        assertFalse(sourceClass.belongsToPackage(null));
    }

    @Test
    void whenReconstituting_givenAllFields_shouldRecreateExactly() {
        final String id = UUID.randomUUID().toString();
        final String projectId = UUID.randomUUID().toString();
        final Instant createdAt = Instant.now().minusSeconds(3600);

        final SourceClass sourceClass = SourceClass.reconstitute(
                id, projectId,
                "co.fanki.user.UserService",
                "UserService",
                "co.fanki.user",
                ClassType.SERVICE,
                "Manages user operations",
                "src/main/java/co/fanki/user/UserService.java",
                createdAt);

        assertEquals(id, sourceClass.id());
        assertEquals(projectId, sourceClass.projectId());
        assertEquals("co.fanki.user.UserService", sourceClass.fullClassName());
        assertEquals("UserService", sourceClass.simpleName());
        assertEquals("co.fanki.user", sourceClass.packageName());
        assertEquals(ClassType.SERVICE, sourceClass.classType());
        assertEquals("Manages user operations", sourceClass.description());
        assertEquals(createdAt, sourceClass.createdAt());
    }

    private SourceClass createTestClass(final String fullClassName) {
        return SourceClass.create(
                UUID.randomUUID().toString(),
                fullClassName,
                ClassType.SERVICE,
                "Test class",
                null);
    }

}
