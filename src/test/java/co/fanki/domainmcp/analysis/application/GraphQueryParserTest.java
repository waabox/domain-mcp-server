package co.fanki.domainmcp.analysis.application;

import co.fanki.domainmcp.shared.DomainException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GraphQueryParser}.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class GraphQueryParserTest {

    @Test
    void whenParsing_givenProjectAndCategory_shouldParseCorrectly() {
        final GraphQuery query =
                GraphQueryParser.parse("stadium-service:endpoints");

        assertEquals("stadium-service", query.project());
        assertEquals(GraphQuery.Category.ENDPOINTS, query.category());
        assertTrue(query.segments().isEmpty());
    }

    @Test
    void whenParsing_givenCategoryWithSegments_shouldCaptureAllSegments() {
        final GraphQuery query =
                GraphQueryParser.parse("my-project:endpoints:logic");

        assertEquals("my-project", query.project());
        assertEquals(GraphQuery.Category.ENDPOINTS, query.category());
        assertEquals(List.of("logic"), query.segments());
    }

    @Test
    void whenParsing_givenClassCategory_shouldRequireClassName() {
        final GraphQuery query =
                GraphQueryParser.parse("proj:class:UserService");

        assertEquals("proj", query.project());
        assertEquals(GraphQuery.Category.CLASS, query.category());
        assertEquals("UserService", query.firstSegment());
    }

    @Test
    void whenParsing_givenClassWithSubNavigation_shouldCaptureAll() {
        final GraphQuery query = GraphQueryParser.parse(
                "proj:class:UserService:methods");

        assertEquals("proj", query.project());
        assertEquals(GraphQuery.Category.CLASS, query.category());
        assertEquals(List.of("UserService", "methods"),
                query.segments());
    }

    @Test
    void whenParsing_givenClassWithMethodNavigation_shouldCaptureAll() {
        final GraphQuery query = GraphQueryParser.parse(
                "proj:class:UserService:method:createUser");

        assertEquals(List.of("UserService", "method", "createUser"),
                query.segments());
    }

    @Test
    void whenParsing_givenEntrypointsCategory_shouldParse() {
        final GraphQuery query =
                GraphQueryParser.parse("proj:entrypoints");

        assertEquals(GraphQuery.Category.ENTRYPOINTS, query.category());
    }

    @Test
    void whenParsing_givenClassesCategory_shouldParse() {
        final GraphQuery query =
                GraphQueryParser.parse("proj:classes");

        assertEquals(GraphQuery.Category.CLASSES, query.category());
    }

    @Test
    void whenParsing_givenCaseInsensitiveCategory_shouldParse() {
        final GraphQuery query =
                GraphQueryParser.parse("proj:Endpoints");

        assertEquals(GraphQuery.Category.ENDPOINTS, query.category());
    }

    @Test
    void whenParsing_givenNullQuery_shouldThrow() {
        assertThrows(DomainException.class,
                () -> GraphQueryParser.parse(null));
    }

    @Test
    void whenParsing_givenBlankQuery_shouldThrow() {
        assertThrows(DomainException.class,
                () -> GraphQueryParser.parse("   "));
    }

    @Test
    void whenParsing_givenOnlyProject_shouldThrow() {
        assertThrows(DomainException.class,
                () -> GraphQueryParser.parse("stadium-service"));
    }

    @Test
    void whenParsing_givenUnknownCategory_shouldThrow() {
        assertThrows(DomainException.class,
                () -> GraphQueryParser.parse("proj:unknown"));
    }

    @Test
    void whenParsing_givenClassWithoutName_shouldThrow() {
        assertThrows(DomainException.class,
                () -> GraphQueryParser.parse("proj:class"));
    }

    @Test
    void whenParsing_givenEmptyProject_shouldThrow() {
        assertThrows(DomainException.class,
                () -> GraphQueryParser.parse(":endpoints"));
    }

    @Test
    void whenQueryHasSegment_givenExistingValue_shouldReturnTrue() {
        final GraphQuery query =
                GraphQueryParser.parse("proj:endpoints:logic");

        assertTrue(query.hasSegment("logic"));
    }

    @Test
    void whenQuerySegmentsFrom_givenValidIndex_shouldReturnSublist() {
        final GraphQuery query = GraphQueryParser.parse(
                "proj:class:UserService:method:createUser");

        assertEquals(List.of("method", "createUser"),
                query.segmentsFrom(1));
    }

    @Test
    void whenQuerySegmentsFrom_givenOutOfBounds_shouldReturnEmpty() {
        final GraphQuery query =
                GraphQueryParser.parse("proj:endpoints");

        assertTrue(query.segmentsFrom(0).isEmpty());
    }
}
