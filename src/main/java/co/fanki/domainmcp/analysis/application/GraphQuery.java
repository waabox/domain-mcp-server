package co.fanki.domainmcp.analysis.application;

import co.fanki.domainmcp.shared.DomainException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parsed graph query with a built-in lexer.
 *
 * <p>Query syntax:
 * {@code project:target[:navigation]*[:+include]*[:?check]}</p>
 *
 * <p>Token types:</p>
 * <ul>
 *   <li>{@code NAVIGATE} — plain segment, traverses the graph</li>
 *   <li>{@code INCLUDE} (+) — prefixed with {@code +}, adds a
 *       projection to the result</li>
 *   <li>{@code CHECK} (?) — prefixed with {@code ?}, performs
 *       an existence check</li>
 * </ul>
 *
 * <p>The first segment after the project is always a navigation target.
 * It can be a keyword ({@code endpoints}, {@code classes},
 * {@code entrypoints}) or any vertex identifier in the graph.</p>
 *
 * <p>Examples:</p>
 * <ul>
 *   <li>{@code stadium-service:endpoints}</li>
 *   <li>{@code stadium-service:endpoints:+logic}</li>
 *   <li>{@code stadium-service:classes:+dependencies}</li>
 *   <li>{@code stadium-service:entrypoints}</li>
 *   <li>{@code stadium-service:UserService}</li>
 *   <li>{@code stadium-service:UserService:methods}</li>
 *   <li>{@code stadium-service:UserService:methods:+logic}</li>
 *   <li>{@code stadium-service:UserService:method:createUser}</li>
 *   <li>{@code stadium-service:UserService:dependencies}</li>
 *   <li>{@code stadium-service:UserService:dependents}</li>
 *   <li>{@code stadium-service:UserService:?createUser}</li>
 * </ul>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class GraphQuery {

    /** Token types produced by the lexer. */
    public enum TokenType {
        /** Navigation segment — traverses the graph. */
        NAVIGATE,
        /** Include modifier — prefixed with {@code +}. */
        INCLUDE,
        /** Existence check — prefixed with {@code ?}. */
        CHECK
    }

    /** A single token from the query lexer. */
    public record Token(TokenType type, String value) {

        @Override
        public String toString() {
            return switch (type) {
                case NAVIGATE -> value;
                case INCLUDE -> "+" + value;
                case CHECK -> "?" + value;
            };
        }
    }

    private final String raw;
    private final String project;
    private final List<Token> tokens;

    private GraphQuery(final String theRaw, final String theProject,
            final List<Token> theTokens) {
        this.raw = theRaw;
        this.project = theProject;
        this.tokens = Collections.unmodifiableList(theTokens);
    }

    // -- Lexer ---------------------------------------------------------------

    /**
     * Parses a colon-separated query string into a GraphQuery.
     *
     * <p>Splits the query on {@code :}, extracts the project name from
     * the first segment, and tokenizes remaining segments based on
     * their prefix ({@code +} for includes, {@code ?} for checks,
     * otherwise navigation).</p>
     *
     * @param query the raw query string
     * @return the parsed GraphQuery
     * @throws DomainException if the query is invalid
     */
    public static GraphQuery parse(final String query) {
        if (query == null || query.isBlank()) {
            throw new DomainException(
                    "Query is required", "INVALID_QUERY");
        }

        final String trimmed = query.trim();
        final String[] parts = trimmed.split(":");

        if (parts.length < 2) {
            throw new DomainException(
                    "Query must have at least project:target. Got: "
                            + trimmed,
                    "INVALID_QUERY");
        }

        final String project = parts[0].trim();
        if (project.isEmpty()) {
            throw new DomainException(
                    "Project name is required", "INVALID_QUERY");
        }

        final List<Token> tokens = new ArrayList<>();

        for (int i = 1; i < parts.length; i++) {
            final String segment = parts[i].trim();
            if (segment.isEmpty()) {
                continue;
            }

            final char first = segment.charAt(0);

            if (first == '+') {
                final String value = segment.substring(1).trim();
                if (value.isEmpty()) {
                    throw new DomainException(
                            "Include modifier (+) requires a value",
                            "INVALID_QUERY");
                }
                tokens.add(new Token(TokenType.INCLUDE, value));
            } else if (first == '?') {
                final String value = segment.substring(1).trim();
                if (value.isEmpty()) {
                    throw new DomainException(
                            "Check (?) requires a value",
                            "INVALID_QUERY");
                }
                tokens.add(new Token(TokenType.CHECK, value));
            } else {
                tokens.add(new Token(TokenType.NAVIGATE, segment));
            }
        }

        if (tokens.isEmpty()) {
            throw new DomainException(
                    "Query must have at least one target after project",
                    "INVALID_QUERY");
        }

        if (tokens.get(0).type() != TokenType.NAVIGATE) {
            throw new DomainException(
                    "First segment after project must be a navigation"
                            + " target, not a modifier. Got: "
                            + tokens.get(0),
                    "INVALID_QUERY");
        }

        return new GraphQuery(trimmed, project, tokens);
    }

    // -- Accessors -----------------------------------------------------------

    /** Returns the raw query string. */
    public String raw() {
        return raw;
    }

    /** Returns the project name. */
    public String project() {
        return project;
    }

    /** Returns the full token list (unmodifiable). */
    public List<Token> tokens() {
        return tokens;
    }

    // -- Navigation helpers --------------------------------------------------

    /** Returns only NAVIGATE tokens. */
    public List<Token> navigations() {
        final List<Token> result = new ArrayList<>();
        for (final Token t : tokens) {
            if (t.type() == TokenType.NAVIGATE) {
                result.add(t);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** Returns the value of the first NAVIGATE token, or null. */
    public String firstNavigation() {
        for (final Token t : tokens) {
            if (t.type() == TokenType.NAVIGATE) {
                return t.value();
            }
        }
        return null;
    }

    /**
     * Returns navigation values starting from the given index.
     *
     * @param fromIndex the starting index (inclusive) among
     *                  NAVIGATE tokens
     * @return the remaining navigation values
     */
    public List<String> navigationsFrom(final int fromIndex) {
        final List<Token> navs = navigations();
        if (fromIndex >= navs.size()) {
            return List.of();
        }
        final List<String> result = new ArrayList<>();
        for (int i = fromIndex; i < navs.size(); i++) {
            result.add(navs.get(i).value());
        }
        return Collections.unmodifiableList(result);
    }

    // -- Include helpers -----------------------------------------------------

    /** Returns only INCLUDE tokens. */
    public List<Token> includes() {
        final List<Token> result = new ArrayList<>();
        for (final Token t : tokens) {
            if (t.type() == TokenType.INCLUDE) {
                result.add(t);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Checks if a specific include modifier is present
     * (case-insensitive).
     *
     * @param value the include value to check
     * @return true if the include is present
     */
    public boolean hasInclude(final String value) {
        for (final Token t : tokens) {
            if (t.type() == TokenType.INCLUDE
                    && t.value().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    // -- Check helpers -------------------------------------------------------

    /** Returns only CHECK tokens. */
    public List<Token> checks() {
        final List<Token> result = new ArrayList<>();
        for (final Token t : tokens) {
            if (t.type() == TokenType.CHECK) {
                result.add(t);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** Returns true if the query contains any CHECK token. */
    public boolean hasCheck() {
        for (final Token t : tokens) {
            if (t.type() == TokenType.CHECK) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the first CHECK value, or null.
     *
     * @return the first check value
     */
    public String checkValue() {
        for (final Token t : tokens) {
            if (t.type() == TokenType.CHECK) {
                return t.value();
            }
        }
        return null;
    }
}
