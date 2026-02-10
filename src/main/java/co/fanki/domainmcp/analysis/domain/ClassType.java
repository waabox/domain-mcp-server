package co.fanki.domainmcp.analysis.domain;

/**
 * Represents the type/role of a class in the codebase architecture.
 *
 * <p>Used to categorize classes for better understanding of their
 * purpose and behavior within the system.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public enum ClassType {

    /**
     * REST or web controller handling HTTP requests.
     */
    CONTROLLER("Controller handling HTTP requests"),

    /**
     * Service class containing business logic.
     */
    SERVICE("Service containing business logic"),

    /**
     * Repository class for data persistence operations.
     */
    REPOSITORY("Repository for data persistence"),

    /**
     * Domain entity representing a business concept.
     */
    ENTITY("Domain entity"),

    /**
     * Data Transfer Object for API contracts.
     */
    DTO("Data Transfer Object"),

    /**
     * Configuration class for application setup.
     */
    CONFIGURATION("Configuration class"),

    /**
     * Kafka listener or message consumer.
     */
    LISTENER("Message listener/consumer"),

    /**
     * Utility or helper class.
     */
    UTILITY("Utility/helper class"),

    /**
     * Exception class.
     */
    EXCEPTION("Exception class"),

    /**
     * Unknown or unclassified type.
     */
    OTHER("Other/unclassified");

    private final String description;

    ClassType(final String theDescription) {
        this.description = theDescription;
    }

    /**
     * Returns a human-readable description of this class type.
     *
     * @return the description
     */
    public String description() {
        return description;
    }

    /**
     * Parses a string into a ClassType, returning OTHER if not recognized.
     *
     * @param value the string value to parse
     * @return the corresponding ClassType or OTHER if not found
     */
    public static ClassType fromString(final String value) {
        if (value == null || value.isBlank()) {
            return OTHER;
        }
        try {
            return valueOf(value.toUpperCase().trim());
        } catch (final IllegalArgumentException e) {
            return OTHER;
        }
    }

    /**
     * Checks if this class type represents a request handler.
     *
     * @return true if this is a controller or listener
     */
    public boolean isRequestHandler() {
        return this == CONTROLLER || this == LISTENER;
    }

    /**
     * Checks if this class type contains business logic.
     *
     * @return true if this is a service or entity
     */
    public boolean containsBusinessLogic() {
        return this == SERVICE || this == ENTITY;
    }

}
