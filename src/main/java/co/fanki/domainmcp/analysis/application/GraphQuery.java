package co.fanki.domainmcp.analysis.application;

import java.util.Collections;
import java.util.List;

/**
 * Parsed graph query with project, category, and sub-segments.
 *
 * <p>Query syntax: {@code project:category[:segment1[:segment2[...]]]}</p>
 *
 * <p>Examples:</p>
 * <ul>
 *   <li>{@code stadium-service:endpoints}</li>
 *   <li>{@code stadium-service:endpoints:logic}</li>
 *   <li>{@code stadium-service:classes}</li>
 *   <li>{@code stadium-service:entrypoints}</li>
 *   <li>{@code stadium-service:class:UserService}</li>
 *   <li>{@code stadium-service:class:UserService:methods}</li>
 *   <li>{@code stadium-service:class:UserService:dependencies}</li>
 *   <li>{@code stadium-service:class:UserService:dependents}</li>
 * </ul>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public record GraphQuery(
        String project,
        Category category,
        List<String> segments) {

    /**
     * The query category (second segment).
     */
    public enum Category {
        /** All HTTP endpoints in the project. */
        ENDPOINTS,
        /** All classes in the project. */
        CLASSES,
        /** Entry point classes (controllers, listeners). */
        ENTRYPOINTS,
        /** Navigate to a specific class. */
        CLASS
    }

    /**
     * Creates a GraphQuery with an immutable segments list.
     */
    public GraphQuery {
        segments = segments != null
                ? Collections.unmodifiableList(segments) : List.of();
    }

    /**
     * Returns the first sub-segment, or null if none.
     *
     * @return the first sub-segment
     */
    public String firstSegment() {
        return segments.isEmpty() ? null : segments.get(0);
    }

    /**
     * Returns sub-segments starting from the given index.
     *
     * @param fromIndex the starting index (inclusive)
     * @return the remaining segments
     */
    public List<String> segmentsFrom(final int fromIndex) {
        if (fromIndex >= segments.size()) {
            return List.of();
        }
        return segments.subList(fromIndex, segments.size());
    }

    /**
     * Checks if a specific sub-segment value is present.
     *
     * @param value the value to check
     * @return true if any sub-segment matches
     */
    public boolean hasSegment(final String value) {
        return segments.contains(value);
    }
}
