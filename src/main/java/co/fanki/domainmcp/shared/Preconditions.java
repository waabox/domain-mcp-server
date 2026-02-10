package co.fanki.domainmcp.shared;

/**
 * Utility class for argument validation and precondition checks.
 *
 * <p>Provides defensive programming utilities for validating method
 * arguments and enforcing preconditions.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class Preconditions {

    private Preconditions() {
        // Utility class, not instantiable
    }

    /**
     * Ensures that an object reference is not null.
     *
     * @param reference the object reference to check
     * @param message the exception message if null
     * @param <T> the type of the reference
     * @return the non-null reference
     * @throws IllegalArgumentException if reference is null
     */
    public static <T> T requireNonNull(final T reference, final String message) {
        if (reference == null) {
            throw new IllegalArgumentException(message);
        }
        return reference;
    }

    /**
     * Ensures that a string is not null or blank.
     *
     * @param value the string to check
     * @param message the exception message if null or blank
     * @return the non-blank string
     * @throws IllegalArgumentException if value is null or blank
     */
    public static String requireNonBlank(final String value,
            final String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    /**
     * Ensures that a condition is true.
     *
     * @param condition the condition to check
     * @param message the exception message if false
     * @throws IllegalArgumentException if condition is false
     */
    public static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Ensures that a condition is true, throwing a domain exception.
     *
     * @param condition the condition to check
     * @param message the exception message if false
     * @throws DomainException if condition is false
     */
    public static void requireDomain(final boolean condition,
            final String message) {
        if (!condition) {
            throw new DomainException(message);
        }
    }

    /**
     * Ensures that a number is positive.
     *
     * @param value the number to check
     * @param message the exception message if not positive
     * @return the positive number
     * @throws IllegalArgumentException if value is not positive
     */
    public static int requirePositive(final int value, final String message) {
        if (value <= 0) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    /**
     * Ensures that a number is non-negative.
     *
     * @param value the number to check
     * @param message the exception message if negative
     * @return the non-negative number
     * @throws IllegalArgumentException if value is negative
     */
    public static int requireNonNegative(final int value, final String message) {
        if (value < 0) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

}
