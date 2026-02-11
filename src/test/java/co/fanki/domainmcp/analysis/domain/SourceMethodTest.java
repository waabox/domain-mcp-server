package co.fanki.domainmcp.analysis.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Unit tests for SourceMethod entity.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class SourceMethodTest {

    @Test
    void whenCreatingMethod_givenValidData_shouldCreateWithCorrectValues() {
        final String classId = UUID.randomUUID().toString();
        final List<String> businessLogic = List.of("Validates input", "Saves to DB");
        final List<String> exceptions = List.of("ValidationException");

        final SourceMethod method = SourceMethod.create(
                classId,
                "createUser",
                "Creates a new user",
                businessLogic,
                exceptions,
                "POST",
                "/api/users",
                45);

        assertNotNull(method.id());
        assertEquals(classId, method.classId());
        assertEquals("createUser", method.methodName());
        assertEquals("Creates a new user", method.description());
        assertEquals(businessLogic, method.businessLogic());
        assertEquals(exceptions, method.exceptions());
        assertEquals("POST", method.httpMethod());
        assertEquals("/api/users", method.httpPath());
        assertEquals(45, method.lineNumber());
        assertNotNull(method.createdAt());
    }

    @Test
    void whenCreatingMethod_givenNullClassId_shouldThrowException() {
        assertThrows(IllegalArgumentException.class, () ->
                SourceMethod.create(null, "test", null, null, null,
                        null, null, null));
    }

    @Test
    void whenCreatingMethod_givenBlankMethodName_shouldThrowException() {
        final String classId = UUID.randomUUID().toString();

        assertThrows(IllegalArgumentException.class, () ->
                SourceMethod.create(classId, "  ", null, null, null,
                        null, null, null));
    }

    @Test
    void whenCreatingMethod_givenNullLists_shouldReturnEmptyLists() {
        final String classId = UUID.randomUUID().toString();

        final SourceMethod method = SourceMethod.create(
                classId, "test", null, null, null, null, null, null);

        assertTrue(method.businessLogic().isEmpty());
        assertTrue(method.exceptions().isEmpty());
    }

    @Test
    void whenCheckingHttpEndpoint_givenBothMethodAndPath_shouldReturnTrue() {
        final SourceMethod method = createHttpMethod("GET", "/api/users");

        assertTrue(method.isHttpEndpoint());
    }

    @Test
    void whenCheckingHttpEndpoint_givenOnlyMethod_shouldReturnFalse() {
        final SourceMethod method = createHttpMethod("GET", null);

        assertFalse(method.isHttpEndpoint());
    }

    @Test
    void whenCheckingHttpEndpoint_givenOnlyPath_shouldReturnFalse() {
        final SourceMethod method = createHttpMethod(null, "/api/users");

        assertFalse(method.isHttpEndpoint());
    }

    @Test
    void whenCheckingHttpEndpoint_givenNeitherMethodNorPath_shouldReturnFalse() {
        final SourceMethod method = createHttpMethod(null, null);

        assertFalse(method.isHttpEndpoint());
    }

    @Test
    void whenGettingHttpEndpoint_givenValidEndpoint_shouldReturnCombined() {
        final SourceMethod method = createHttpMethod("POST", "/api/users");

        assertEquals("POST /api/users", method.httpEndpoint());
    }

    @Test
    void whenGettingHttpEndpoint_givenNonEndpoint_shouldReturnNull() {
        final SourceMethod method = createHttpMethod(null, null);

        assertNull(method.httpEndpoint());
    }

    @Test
    void whenReconstituting_givenAllFields_shouldRecreateExactly() {
        final String id = UUID.randomUUID().toString();
        final String classId = UUID.randomUUID().toString();
        final Instant createdAt = Instant.now().minusSeconds(3600);
        final List<String> businessLogic = List.of("Step 1", "Step 2");
        final List<String> exceptions = List.of("Ex1");

        final SourceMethod method = SourceMethod.reconstitute(
                id, classId,
                "testMethod",
                "Test description",
                businessLogic,
                exceptions,
                "PUT",
                "/api/test",
                100,
                createdAt);

        assertEquals(id, method.id());
        assertEquals(classId, method.classId());
        assertEquals("testMethod", method.methodName());
        assertEquals("Test description", method.description());
        assertEquals(businessLogic, method.businessLogic());
        assertEquals(exceptions, method.exceptions());
        assertEquals("PUT", method.httpMethod());
        assertEquals("/api/test", method.httpPath());
        assertEquals(100, method.lineNumber());
        assertEquals(createdAt, method.createdAt());
    }

    @Test
    void whenCreatingMethod_givenLists_shouldReturnImmutableCopies() {
        final String classId = UUID.randomUUID().toString();
        final List<String> originalBusinessLogic = new java.util.ArrayList<>();
        originalBusinessLogic.add("Step 1");

        final SourceMethod method = SourceMethod.create(
                classId, "test", null, originalBusinessLogic,
                null, null, null, null);

        originalBusinessLogic.add("Step 2");

        assertEquals(1, method.businessLogic().size());

        assertThrows(UnsupportedOperationException.class, () ->
                method.businessLogic().add("Step 3"));
    }

    private SourceMethod createHttpMethod(final String httpMethod,
            final String httpPath) {
        return SourceMethod.create(
                UUID.randomUUID().toString(),
                "testMethod",
                "Test description",
                null, null,
                httpMethod,
                httpPath,
                null);
    }

}
