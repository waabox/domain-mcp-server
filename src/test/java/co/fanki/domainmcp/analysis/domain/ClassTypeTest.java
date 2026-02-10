package co.fanki.domainmcp.analysis.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for ClassType enum.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class ClassTypeTest {

    @Test
    void whenParsingString_givenValidType_shouldReturnCorrectEnum() {
        assertEquals(ClassType.CONTROLLER, ClassType.fromString("CONTROLLER"));
        assertEquals(ClassType.SERVICE, ClassType.fromString("SERVICE"));
        assertEquals(ClassType.REPOSITORY, ClassType.fromString("REPOSITORY"));
        assertEquals(ClassType.ENTITY, ClassType.fromString("ENTITY"));
        assertEquals(ClassType.DTO, ClassType.fromString("DTO"));
    }

    @Test
    void whenParsingString_givenLowercaseType_shouldReturnCorrectEnum() {
        assertEquals(ClassType.CONTROLLER, ClassType.fromString("controller"));
        assertEquals(ClassType.SERVICE, ClassType.fromString("service"));
    }

    @Test
    void whenParsingString_givenMixedCaseType_shouldReturnCorrectEnum() {
        assertEquals(ClassType.CONTROLLER, ClassType.fromString("Controller"));
        assertEquals(ClassType.SERVICE, ClassType.fromString("Service"));
    }

    @Test
    void whenParsingString_givenInvalidType_shouldReturnOther() {
        assertEquals(ClassType.OTHER, ClassType.fromString("INVALID"));
        assertEquals(ClassType.OTHER, ClassType.fromString("unknown"));
        assertEquals(ClassType.OTHER, ClassType.fromString("xyz"));
    }

    @Test
    void whenParsingString_givenNullOrBlank_shouldReturnOther() {
        assertEquals(ClassType.OTHER, ClassType.fromString(null));
        assertEquals(ClassType.OTHER, ClassType.fromString(""));
        assertEquals(ClassType.OTHER, ClassType.fromString("  "));
    }

    @Test
    void whenCheckingRequestHandler_givenController_shouldReturnTrue() {
        assertTrue(ClassType.CONTROLLER.isRequestHandler());
    }

    @Test
    void whenCheckingRequestHandler_givenListener_shouldReturnTrue() {
        assertTrue(ClassType.LISTENER.isRequestHandler());
    }

    @Test
    void whenCheckingRequestHandler_givenService_shouldReturnFalse() {
        assertFalse(ClassType.SERVICE.isRequestHandler());
    }

    @Test
    void whenCheckingBusinessLogic_givenService_shouldReturnTrue() {
        assertTrue(ClassType.SERVICE.containsBusinessLogic());
    }

    @Test
    void whenCheckingBusinessLogic_givenEntity_shouldReturnTrue() {
        assertTrue(ClassType.ENTITY.containsBusinessLogic());
    }

    @Test
    void whenCheckingBusinessLogic_givenController_shouldReturnFalse() {
        assertFalse(ClassType.CONTROLLER.containsBusinessLogic());
    }

    @Test
    void whenGettingDescription_givenAnyType_shouldReturnNonNullDescription() {
        for (final ClassType type : ClassType.values()) {
            final String description = type.description();
            assertFalse(description == null || description.isBlank(),
                    "Description for " + type + " should not be null or blank");
        }
    }

}
