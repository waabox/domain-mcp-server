package co.fanki.domainmcp.analysis.domain;

import co.fanki.domainmcp.analysis.domain.ClaudeApiClient.BatchClassInput;
import co.fanki.domainmcp.analysis.domain.ClaudeApiClient.ClassAnalysisResult;
import co.fanki.domainmcp.analysis.domain.ClaudeApiClient.MethodAnalysisResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ClaudeApiClient}.
 *
 * <p>Tests the record factories, argument validation, and data
 * integrity without making actual Claude API calls.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class ClaudeApiClientTest {

    private static final String DUMMY_API_KEY = "sk-ant-dummy-key-for-testing";

    // -- Constructor validation tests --

    @Test
    void whenCreatingClient_givenNullApiKey_shouldThrowException() {
        assertThrows(IllegalArgumentException.class,
                () -> new ClaudeApiClient(null, 5));
    }

    @Test
    void whenCreatingClient_givenBlankApiKey_shouldThrowException() {
        assertThrows(IllegalArgumentException.class,
                () -> new ClaudeApiClient("   ", 5));
    }

    @Test
    void whenCreatingClient_givenEmptyApiKey_shouldThrowException() {
        assertThrows(IllegalArgumentException.class,
                () -> new ClaudeApiClient("", 5));
    }

    // -- analyzeClass argument validation tests --

    @Test
    void whenAnalyzingClass_givenNullSourceCode_shouldThrowException() {
        final ClaudeApiClient client = new ClaudeApiClient(DUMMY_API_KEY, 5);

        assertThrows(IllegalArgumentException.class,
                () -> client.analyzeClass(null, "com.example.Foo",
                        "Foo.java", "Some readme", "java"));
    }

    @Test
    void whenAnalyzingClass_givenBlankSourceCode_shouldThrowException() {
        final ClaudeApiClient client = new ClaudeApiClient(DUMMY_API_KEY, 5);

        assertThrows(IllegalArgumentException.class,
                () -> client.analyzeClass("   ", "com.example.Foo",
                        "Foo.java", "Some readme", "java"));
    }

    @Test
    void whenAnalyzingClass_givenEmptySourceCode_shouldThrowException() {
        final ClaudeApiClient client = new ClaudeApiClient(DUMMY_API_KEY, 5);

        assertThrows(IllegalArgumentException.class,
                () -> client.analyzeClass("", "com.example.Foo",
                        "Foo.java", "Some readme", "java"));
    }

    @Test
    void whenAnalyzingClass_givenNullClassName_shouldThrowException() {
        final ClaudeApiClient client = new ClaudeApiClient(DUMMY_API_KEY, 5);

        assertThrows(IllegalArgumentException.class,
                () -> client.analyzeClass("public class Foo {}", null,
                        "Foo.java", "Some readme", "java"));
    }

    @Test
    void whenAnalyzingClass_givenBlankClassName_shouldThrowException() {
        final ClaudeApiClient client = new ClaudeApiClient(DUMMY_API_KEY, 5);

        assertThrows(IllegalArgumentException.class,
                () -> client.analyzeClass("public class Foo {}", "  ",
                        "Foo.java", "Some readme", "java"));
    }

    // -- ClassAnalysisResult.success() factory tests --

    @Test
    void whenCreatingSuccessResult_givenValidData_shouldPopulateAllFields() {
        final List<MethodAnalysisResult> methods = List.of(
                new MethodAnalysisResult(
                        "findUser", "Finds a user by ID",
                        List.of("Lookup user in database"),
                        List.of("UserNotFoundException"),
                        "GET", "/api/users/{id}", 42));

        final ClassAnalysisResult result = ClassAnalysisResult.success(
                "co.fanki.user.UserService",
                "SERVICE",
                "Manages user lifecycle operations",
                "src/main/java/co/fanki/user/UserService.java",
                methods);

        assertTrue(result.success());
        assertEquals("co.fanki.user.UserService", result.fullClassName());
        assertEquals("SERVICE", result.classType());
        assertEquals("Manages user lifecycle operations", result.description());
        assertEquals("src/main/java/co/fanki/user/UserService.java",
                result.sourceFile());
        assertEquals(1, result.methods().size());
        assertNull(result.errorMessage());
    }

    @Test
    void whenCreatingSuccessResult_givenEmptyMethods_shouldHaveEmptyList() {
        final ClassAnalysisResult result = ClassAnalysisResult.success(
                "co.fanki.config.AppConfig",
                "CONFIGURATION",
                "Application configuration",
                "src/main/java/co/fanki/config/AppConfig.java",
                List.of());

        assertTrue(result.success());
        assertNotNull(result.methods());
        assertTrue(result.methods().isEmpty());
    }

    // -- ClassAnalysisResult.failure() factory tests --

    @Test
    void whenCreatingFailureResult_givenErrorMessage_shouldPopulateCorrectly() {
        final ClassAnalysisResult result = ClassAnalysisResult.failure(
                "co.fanki.user.UserService",
                "src/main/java/co/fanki/user/UserService.java",
                "API rate limit exceeded");

        assertFalse(result.success());
        assertEquals("co.fanki.user.UserService", result.fullClassName());
        assertEquals("OTHER", result.classType());
        assertNull(result.description());
        assertEquals("src/main/java/co/fanki/user/UserService.java",
                result.sourceFile());
        assertNotNull(result.methods());
        assertTrue(result.methods().isEmpty());
        assertEquals("API rate limit exceeded", result.errorMessage());
    }

    @Test
    void whenCreatingFailureResult_givenNullErrorMessage_shouldAcceptNull() {
        final ClassAnalysisResult result = ClassAnalysisResult.failure(
                "co.fanki.user.UserService",
                "UserService.java",
                null);

        assertFalse(result.success());
        assertNull(result.errorMessage());
    }

    // -- MethodAnalysisResult record tests --

    @Test
    void whenCreatingMethodResult_givenAllFields_shouldPopulateCorrectly() {
        final MethodAnalysisResult method = new MethodAnalysisResult(
                "createOrder",
                "Creates a new order for a customer",
                List.of("Validate cart", "Calculate total", "Persist order"),
                List.of("EmptyCartException", "InsufficientStockException"),
                "POST",
                "/api/orders",
                87);

        assertEquals("createOrder", method.methodName());
        assertEquals("Creates a new order for a customer",
                method.description());
        assertEquals(3, method.businessLogic().size());
        assertEquals("Validate cart", method.businessLogic().get(0));
        assertEquals(2, method.exceptions().size());
        assertEquals("POST", method.httpMethod());
        assertEquals("/api/orders", method.httpPath());
        assertEquals(87, method.lineNumber());
    }

    @Test
    void whenCreatingMethodResult_givenNullOptionalFields_shouldAcceptNulls() {
        final MethodAnalysisResult method = new MethodAnalysisResult(
                "processInternal",
                "Internal processing step",
                List.of(),
                List.of(),
                null,
                null,
                null);

        assertEquals("processInternal", method.methodName());
        assertNull(method.httpMethod());
        assertNull(method.httpPath());
        assertNull(method.lineNumber());
        assertTrue(method.businessLogic().isEmpty());
        assertTrue(method.exceptions().isEmpty());
    }

    @Test
    void whenCreatingMethodResult_givenHttpEndpoint_shouldRetainMethodAndPath() {
        final MethodAnalysisResult method = new MethodAnalysisResult(
                "deleteUser",
                "Deletes a user account",
                List.of("Mark user as deleted"),
                List.of(),
                "DELETE",
                "/api/users/{id}",
                120);

        assertEquals("DELETE", method.httpMethod());
        assertEquals("/api/users/{id}", method.httpPath());
    }

    // -- ClassAnalysisResult with methods integration --

    @Test
    void whenCreatingSuccessResult_givenMultipleMethods_shouldRetainAll() {
        final MethodAnalysisResult getMethod = new MethodAnalysisResult(
                "getUser", "Retrieves user by ID",
                List.of("Fetch user"),
                List.of("UserNotFoundException"), "GET", "/api/users/{id}", 30);

        final MethodAnalysisResult postMethod = new MethodAnalysisResult(
                "createUser", "Creates a new user",
                List.of("Validate input", "Persist user"),
                List.of("DuplicateEmailException"), "POST", "/api/users", 55);

        final MethodAnalysisResult deleteMethod = new MethodAnalysisResult(
                "deleteUser", "Soft-deletes a user",
                List.of("Mark as inactive"),
                List.of(), "DELETE", "/api/users/{id}", 80);

        final List<MethodAnalysisResult> methods = List.of(
                getMethod, postMethod, deleteMethod);

        final ClassAnalysisResult result = ClassAnalysisResult.success(
                "co.fanki.user.UserController",
                "CONTROLLER",
                "REST API for user management",
                "src/main/java/co/fanki/user/UserController.java",
                methods);

        assertEquals(3, result.methods().size());
        assertEquals("getUser", result.methods().get(0).methodName());
        assertEquals("createUser", result.methods().get(1).methodName());
        assertEquals("deleteUser", result.methods().get(2).methodName());
    }

    // -- Record equality tests --

    @Test
    void whenComparingMethodResults_givenSameData_shouldBeEqual() {
        final MethodAnalysisResult first = new MethodAnalysisResult(
                "findAll", "Lists all items",
                List.of("Query database"),
                List.of(), "GET", "/api/items", 10);

        final MethodAnalysisResult second = new MethodAnalysisResult(
                "findAll", "Lists all items",
                List.of("Query database"),
                List.of(), "GET", "/api/items", 10);

        assertEquals(first, second);
    }

    @Test
    void whenComparingClassResults_givenSameData_shouldBeEqual() {
        final ClassAnalysisResult first = ClassAnalysisResult.failure(
                "co.fanki.Foo", "Foo.java", "timeout");
        final ClassAnalysisResult second = ClassAnalysisResult.failure(
                "co.fanki.Foo", "Foo.java", "timeout");

        assertEquals(first, second);
    }

    // -- BatchClassInput record tests --

    @Test
    void whenCreatingBatchInput_givenValidData_shouldPopulateAllFields() {
        final BatchClassInput input = new BatchClassInput(
                "public class Foo {}",
                "co.fanki.Foo",
                "src/main/java/co/fanki/Foo.java",
                "java");

        assertEquals("public class Foo {}", input.sourceCode());
        assertEquals("co.fanki.Foo", input.fullClassName());
        assertEquals("src/main/java/co/fanki/Foo.java", input.sourceFile());
        assertEquals("java", input.language());
    }

    @Test
    void whenComparingBatchInputs_givenSameData_shouldBeEqual() {
        final BatchClassInput first = new BatchClassInput(
                "public class Foo {}",
                "co.fanki.Foo",
                "Foo.java",
                "java");
        final BatchClassInput second = new BatchClassInput(
                "public class Foo {}",
                "co.fanki.Foo",
                "Foo.java",
                "java");

        assertEquals(first, second);
    }

    // -- analyzeBatch validation tests --

    @Test
    void whenAnalyzingBatch_givenNullInputs_shouldThrowException() {
        final ClaudeApiClient client = new ClaudeApiClient(DUMMY_API_KEY, 5);

        assertThrows(IllegalArgumentException.class,
                () -> client.analyzeBatch(null, "readme"));
    }

    @Test
    void whenAnalyzingBatch_givenEmptyInputs_shouldReturnEmptyList() {
        final ClaudeApiClient client = new ClaudeApiClient(DUMMY_API_KEY, 5);

        final List<ClassAnalysisResult> results =
                client.analyzeBatch(List.of(), "readme");

        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

}
