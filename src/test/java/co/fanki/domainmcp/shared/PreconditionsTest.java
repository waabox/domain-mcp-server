package co.fanki.domainmcp.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for Preconditions utility.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class PreconditionsTest {

    @Test
    void whenRequireNonNull_givenNonNullValue_shouldReturnValue() {
        final String value = "test";

        final String result = Preconditions.requireNonNull(value, "message");

        assertEquals(value, result);
    }

    @Test
    void whenRequireNonNull_givenNullValue_shouldThrowException() {
        assertThrows(IllegalArgumentException.class,
                () -> Preconditions.requireNonNull(null, "Value is null"));
    }

    @Test
    void whenRequireNonBlank_givenNonBlankString_shouldReturnString() {
        final String value = "test";

        final String result = Preconditions.requireNonBlank(value, "message");

        assertEquals(value, result);
    }

    @Test
    void whenRequireNonBlank_givenBlankString_shouldThrowException() {
        assertThrows(IllegalArgumentException.class,
                () -> Preconditions.requireNonBlank("  ", "String is blank"));
    }

    @Test
    void whenRequireNonBlank_givenNullString_shouldThrowException() {
        assertThrows(IllegalArgumentException.class,
                () -> Preconditions.requireNonBlank(null, "String is null"));
    }

    @Test
    void whenRequire_givenTrueCondition_shouldNotThrow() {
        Preconditions.require(true, "Should not throw");
    }

    @Test
    void whenRequire_givenFalseCondition_shouldThrowException() {
        assertThrows(IllegalArgumentException.class,
                () -> Preconditions.require(false, "Condition is false"));
    }

    @Test
    void whenRequireDomain_givenFalseCondition_shouldThrowDomainException() {
        assertThrows(DomainException.class,
                () -> Preconditions.requireDomain(false, "Domain error"));
    }

    @Test
    void whenRequirePositive_givenPositiveValue_shouldReturnValue() {
        final int result = Preconditions.requirePositive(5, "message");

        assertEquals(5, result);
    }

    @Test
    void whenRequirePositive_givenZero_shouldThrowException() {
        assertThrows(IllegalArgumentException.class,
                () -> Preconditions.requirePositive(0, "Not positive"));
    }

    @Test
    void whenRequirePositive_givenNegative_shouldThrowException() {
        assertThrows(IllegalArgumentException.class,
                () -> Preconditions.requirePositive(-1, "Not positive"));
    }

    @Test
    void whenRequireNonNegative_givenZero_shouldReturnZero() {
        final int result = Preconditions.requireNonNegative(0, "message");

        assertEquals(0, result);
    }

    @Test
    void whenRequireNonNegative_givenNegative_shouldThrowException() {
        assertThrows(IllegalArgumentException.class,
                () -> Preconditions.requireNonNegative(-1, "Negative"));
    }

}
