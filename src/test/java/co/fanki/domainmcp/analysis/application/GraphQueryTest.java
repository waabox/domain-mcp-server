package co.fanki.domainmcp.analysis.application;

import co.fanki.domainmcp.shared.DomainException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link GraphQuery} lexer.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class GraphQueryTest {

    // -- Basic parsing -------------------------------------------------------

    @Test
    void whenParsing_givenProjectAndTarget_shouldTokenize() {
        final GraphQuery query =
                GraphQuery.parse("stadium-service:endpoints");

        assertEquals("stadium-service", query.project());
        assertEquals("endpoints", query.firstNavigation());
        assertEquals(1, query.tokens().size());
        assertEquals(GraphQuery.TokenType.NAVIGATE,
                query.tokens().get(0).type());
    }

    @Test
    void whenParsing_givenMultipleNavigations_shouldTokenizeAll() {
        final GraphQuery query =
                GraphQuery.parse("proj:UserService:methods");

        assertEquals("proj", query.project());
        assertEquals(2, query.navigations().size());
        assertEquals("UserService",
                query.navigations().get(0).value());
        assertEquals("methods",
                query.navigations().get(1).value());
    }

    @Test
    void whenParsing_givenClassWithMethodNavigation_shouldCaptureAll() {
        final GraphQuery query = GraphQuery.parse(
                "proj:UserService:method:createUser");

        assertEquals(List.of("UserService", "method", "createUser"),
                query.navigationsFrom(0));
    }

    // -- Include tokens (+) --------------------------------------------------

    @Test
    void whenParsing_givenIncludeToken_shouldRecognize() {
        final GraphQuery query =
                GraphQuery.parse("proj:endpoints:+logic");

        assertEquals(1, query.navigations().size());
        assertEquals("endpoints", query.firstNavigation());
        assertEquals(1, query.includes().size());
        assertEquals("logic", query.includes().get(0).value());
        assertTrue(query.hasInclude("logic"));
    }

    @Test
    void whenParsing_givenMultipleIncludes_shouldCaptureAll() {
        final GraphQuery query = GraphQuery.parse(
                "proj:classes:+dependencies:+dependents");

        assertEquals(1, query.navigations().size());
        assertEquals(2, query.includes().size());
        assertTrue(query.hasInclude("dependencies"));
        assertTrue(query.hasInclude("dependents"));
    }

    @Test
    void whenParsing_givenIncludeMixedWithNavigation_shouldSeparate() {
        final GraphQuery query = GraphQuery.parse(
                "proj:UserService:methods:+logic");

        assertEquals(2, query.navigations().size());
        assertEquals("UserService",
                query.navigations().get(0).value());
        assertEquals("methods",
                query.navigations().get(1).value());
        assertEquals(1, query.includes().size());
        assertTrue(query.hasInclude("logic"));
    }

    @Test
    void hasInclude_givenCaseInsensitive_shouldMatch() {
        final GraphQuery query =
                GraphQuery.parse("proj:endpoints:+Logic");

        assertTrue(query.hasInclude("logic"));
        assertTrue(query.hasInclude("LOGIC"));
    }

    // -- Check tokens (?) ----------------------------------------------------

    @Test
    void whenParsing_givenCheckToken_shouldRecognize() {
        final GraphQuery query =
                GraphQuery.parse("proj:UserService:?createUser");

        assertEquals(1, query.navigations().size());
        assertEquals("UserService", query.firstNavigation());
        assertTrue(query.hasCheck());
        assertEquals("createUser", query.checkValue());
    }

    @Test
    void whenParsing_givenNoCheck_shouldNotHaveCheck() {
        final GraphQuery query =
                GraphQuery.parse("proj:UserService:methods");

        assertFalse(query.hasCheck());
        assertNull(query.checkValue());
    }

    @Test
    void whenParsing_givenCheckAndInclude_shouldCaptureBoth() {
        final GraphQuery query = GraphQuery.parse(
                "proj:UserService:+logic:?createUser");

        assertTrue(query.hasInclude("logic"));
        assertTrue(query.hasCheck());
        assertEquals("createUser", query.checkValue());
    }

    // -- Navigation helpers --------------------------------------------------

    @Test
    void navigationsFrom_givenValidIndex_shouldReturnSublist() {
        final GraphQuery query = GraphQuery.parse(
                "proj:UserService:method:createUser");

        assertEquals(List.of("method", "createUser"),
                query.navigationsFrom(1));
    }

    @Test
    void navigationsFrom_givenOutOfBounds_shouldReturnEmpty() {
        final GraphQuery query =
                GraphQuery.parse("proj:endpoints");

        assertTrue(query.navigationsFrom(1).isEmpty());
    }

    @Test
    void firstNavigation_givenEntrypoints_shouldReturn() {
        final GraphQuery query =
                GraphQuery.parse("proj:entrypoints");

        assertEquals("entrypoints", query.firstNavigation());
    }

    @Test
    void firstNavigation_givenClasses_shouldReturn() {
        final GraphQuery query =
                GraphQuery.parse("proj:classes");

        assertEquals("classes", query.firstNavigation());
    }

    // -- Token toString ------------------------------------------------------

    @Test
    void tokenToString_shouldFormatWithPrefix() {
        assertEquals("foo",
                new GraphQuery.Token(
                        GraphQuery.TokenType.NAVIGATE, "foo")
                        .toString());
        assertEquals("+logic",
                new GraphQuery.Token(
                        GraphQuery.TokenType.INCLUDE, "logic")
                        .toString());
        assertEquals("?create",
                new GraphQuery.Token(
                        GraphQuery.TokenType.CHECK, "create")
                        .toString());
    }

    // -- Raw and project accessors -------------------------------------------

    @Test
    void raw_shouldReturnOriginalQuery() {
        final GraphQuery query =
                GraphQuery.parse("proj:endpoints:+logic");

        assertEquals("proj:endpoints:+logic", query.raw());
    }

    // -- Error cases ---------------------------------------------------------

    @Test
    void whenParsing_givenNullQuery_shouldThrow() {
        assertThrows(DomainException.class,
                () -> GraphQuery.parse(null));
    }

    @Test
    void whenParsing_givenBlankQuery_shouldThrow() {
        assertThrows(DomainException.class,
                () -> GraphQuery.parse("   "));
    }

    @Test
    void whenParsing_givenOnlyProject_shouldThrow() {
        assertThrows(DomainException.class,
                () -> GraphQuery.parse("stadium-service"));
    }

    @Test
    void whenParsing_givenEmptyProject_shouldThrow() {
        assertThrows(DomainException.class,
                () -> GraphQuery.parse(":endpoints"));
    }

    @Test
    void whenParsing_givenEmptyIncludeValue_shouldThrow() {
        assertThrows(DomainException.class,
                () -> GraphQuery.parse("proj:endpoints:+"));
    }

    @Test
    void whenParsing_givenEmptyCheckValue_shouldThrow() {
        assertThrows(DomainException.class,
                () -> GraphQuery.parse("proj:UserService:?"));
    }

    @Test
    void whenParsing_givenIncludeAsFirstToken_shouldThrow() {
        assertThrows(DomainException.class,
                () -> GraphQuery.parse("proj:+logic"));
    }

    @Test
    void whenParsing_givenCheckAsFirstToken_shouldThrow() {
        assertThrows(DomainException.class,
                () -> GraphQuery.parse("proj:?foo"));
    }
}
