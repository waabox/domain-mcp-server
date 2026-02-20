package co.fanki.domainmcp.analysis.application;

import co.fanki.domainmcp.shared.DomainException;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a colon-separated query string into a {@link GraphQuery}.
 *
 * <p>Query syntax: {@code project:category[:segment1[:segment2[...]]]}</p>
 *
 * <p>The parser splits on {@code :}, extracts the project name (first
 * segment) and category (second segment), and collects any remaining
 * segments as sub-navigation filters.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class GraphQueryParser {

    private GraphQueryParser() {
    }

    /**
     * Parses a query string into a GraphQuery.
     *
     * @param query the colon-separated query string
     * @return the parsed GraphQuery
     * @throws DomainException if the query is invalid
     */
    public static GraphQuery parse(final String query) {
        if (query == null || query.isBlank()) {
            throw new DomainException(
                    "Query is required", "INVALID_QUERY");
        }

        final String[] parts = query.trim().split(":");
        if (parts.length < 2) {
            throw new DomainException(
                    "Query must have at least project:category. Got: "
                            + query,
                    "INVALID_QUERY");
        }

        final String project = parts[0].trim();
        if (project.isEmpty()) {
            throw new DomainException(
                    "Project name is required", "INVALID_QUERY");
        }

        final String categoryStr = parts[1].trim().toUpperCase();
        final GraphQuery.Category category;
        try {
            category = GraphQuery.Category.valueOf(categoryStr);
        } catch (final IllegalArgumentException e) {
            throw new DomainException(
                    "Unknown category: " + parts[1].trim()
                            + ". Valid categories: endpoints, classes,"
                            + " entrypoints, class",
                    "INVALID_QUERY");
        }

        if (category == GraphQuery.Category.CLASS && parts.length < 3) {
            throw new DomainException(
                    "Category 'class' requires a class name."
                            + " Example: project:class:UserService",
                    "INVALID_QUERY");
        }

        final List<String> segments = new ArrayList<>();
        for (int i = 2; i < parts.length; i++) {
            final String segment = parts[i].trim();
            if (!segment.isEmpty()) {
                segments.add(segment);
            }
        }

        return new GraphQuery(project, category, segments);
    }
}
