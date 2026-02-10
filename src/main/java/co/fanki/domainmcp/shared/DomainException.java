package co.fanki.domainmcp.shared;

/**
 * Base exception for domain-level errors.
 *
 * <p>Domain exceptions represent violations of business rules or
 * invariants within the domain model.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public class DomainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String errorCode;

    /**
     * Creates a new domain exception with a message.
     *
     * @param message the error message
     */
    public DomainException(final String message) {
        super(message);
        this.errorCode = "DOMAIN_ERROR";
    }

    /**
     * Creates a new domain exception with a message and error code.
     *
     * @param message the error message
     * @param errorCode the specific error code
     */
    public DomainException(final String message, final String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * Creates a new domain exception with a message and cause.
     *
     * @param message the error message
     * @param cause the underlying cause
     */
    public DomainException(final String message, final Throwable cause) {
        super(message, cause);
        this.errorCode = "DOMAIN_ERROR";
    }

    /**
     * Creates a new domain exception with message, error code, and cause.
     *
     * @param message the error message
     * @param errorCode the specific error code
     * @param cause the underlying cause
     */
    public DomainException(final String message, final String errorCode,
            final Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * Returns the error code for this exception.
     *
     * @return the error code
     */
    public String getErrorCode() {
        return errorCode;
    }

}
