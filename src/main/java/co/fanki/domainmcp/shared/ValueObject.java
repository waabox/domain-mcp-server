package co.fanki.domainmcp.shared;

import java.io.Serializable;

/**
 * Marker interface for value objects in the domain model.
 *
 * <p>Value objects are immutable objects that are defined by their attributes
 * rather than by their identity. They have no identity and are compared by
 * their values.</p>
 *
 * <p>Implementations must:</p>
 * <ul>
 *   <li>Be immutable</li>
 *   <li>Override equals() and hashCode() based on all attributes</li>
 *   <li>Be self-validating (validate in constructor)</li>
 * </ul>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public interface ValueObject extends Serializable {

}
