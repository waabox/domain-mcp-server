package co.fanki.domainmcp.analysis.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link MethodParameter}.
 *
 * <p>Tests factory methods, validation, and accessors.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class MethodParameterTest {

    // -- create() --

    @Test
    void whenCreating_givenValidInputs_shouldCreateInstance() {
        final MethodParameter param = MethodParameter.create(
                "method-1", 0, "class-1");

        assertNotNull(param.id());
        assertEquals("method-1", param.methodId());
        assertEquals(0, param.position());
        assertEquals("class-1", param.classId());
        assertNotNull(param.createdAt());
    }

    @Test
    void whenCreating_givenPositionGreaterThanZero_shouldCreateInstance() {
        final MethodParameter param = MethodParameter.create(
                "method-1", 3, "class-1");

        assertEquals(3, param.position());
    }

    // -- reconstitute() --

    @Test
    void whenReconstituting_givenAllFields_shouldPreserveValues() {
        final Instant created = Instant.parse("2025-01-15T10:30:00Z");

        final MethodParameter param = MethodParameter.reconstitute(
                "param-id", "method-id", 2, "class-id", created);

        assertEquals("param-id", param.id());
        assertEquals("method-id", param.methodId());
        assertEquals(2, param.position());
        assertEquals("class-id", param.classId());
        assertEquals(created, param.createdAt());
    }

    // -- validation --

    @Test
    void whenCreating_givenNullMethodId_shouldThrowException() {
        assertThrows(IllegalArgumentException.class,
                () -> MethodParameter.create(null, 0, "class-1"));
    }

    @Test
    void whenCreating_givenBlankMethodId_shouldThrowException() {
        assertThrows(IllegalArgumentException.class,
                () -> MethodParameter.create("  ", 0, "class-1"));
    }

    @Test
    void whenCreating_givenNullClassId_shouldThrowException() {
        assertThrows(IllegalArgumentException.class,
                () -> MethodParameter.create("method-1", 0, null));
    }

    @Test
    void whenCreating_givenBlankClassId_shouldThrowException() {
        assertThrows(IllegalArgumentException.class,
                () -> MethodParameter.create("method-1", 0, ""));
    }

    @Test
    void whenCreating_givenNegativePosition_shouldThrowException() {
        assertThrows(IllegalArgumentException.class,
                () -> MethodParameter.create("method-1", -1, "class-1"));
    }

    @Test
    void whenReconstituting_givenNullId_shouldThrowException() {
        assertThrows(IllegalArgumentException.class,
                () -> MethodParameter.reconstitute(
                        null, "method-1", 0, "class-1", Instant.now()));
    }

    @Test
    void whenReconstituting_givenBlankId_shouldThrowException() {
        assertThrows(IllegalArgumentException.class,
                () -> MethodParameter.reconstitute(
                        "", "method-1", 0, "class-1", Instant.now()));
    }

}
