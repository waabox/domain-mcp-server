package co.fanki.domainmcp.analysis.domain.java;

import co.fanki.domainmcp.analysis.domain.ClassType;
import co.fanki.domainmcp.analysis.domain.ProjectGraph;
import co.fanki.domainmcp.analysis.domain.StaticMethodInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link JavaSourceParser}.
 *
 * <p>Tests file discovery, FQCN extraction, dependency resolution,
 * entry point detection, and import line parsing using temporary
 * directories with sample Java source files.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class JavaSourceParserTest {

    private final JavaSourceParser parser = new JavaSourceParser();

    // -- parse(): full graph building --

    @Test
    void whenParsing_givenProjectWithMultipleFiles_shouldBuildCorrectGraph(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeJavaFile(sourceRoot, "co/fanki/app", "Application.java", """
                package co.fanki.app;

                import org.springframework.boot.SpringApplication;

                @SpringBootApplication
                public class Application {
                    public static void main(String[] args) {
                        SpringApplication.run(Application.class, args);
                    }
                }
                """);

        writeJavaFile(sourceRoot, "co/fanki/app/controller", "UserController.java", """
                package co.fanki.app.controller;

                import co.fanki.app.service.UserService;
                import org.springframework.web.bind.annotation.GetMapping;

                @RestController
                public class UserController {
                    private final UserService userService;
                }
                """);

        writeJavaFile(sourceRoot, "co/fanki/app/service", "UserService.java", """
                package co.fanki.app.service;

                import co.fanki.app.domain.User;

                public class UserService {
                    public User findById(String id) { return null; }
                }
                """);

        writeJavaFile(sourceRoot, "co/fanki/app/domain", "User.java", """
                package co.fanki.app.domain;

                public class User {
                    private String id;
                    private String name;
                }
                """);

        final ProjectGraph graph = parser.parse(projectRoot);

        assertEquals(4, graph.nodeCount());
        assertTrue(graph.contains("co.fanki.app.Application"));
        assertTrue(graph.contains("co.fanki.app.controller.UserController"));
        assertTrue(graph.contains("co.fanki.app.service.UserService"));
        assertTrue(graph.contains("co.fanki.app.domain.User"));
    }

    @Test
    void whenParsing_givenDependenciesBetweenFiles_shouldResolveEdges(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeJavaFile(sourceRoot, "co/fanki/app", "OrderService.java", """
                package co.fanki.app;

                import co.fanki.app.OrderRepository;

                public class OrderService {
                    private final OrderRepository repo;
                }
                """);

        writeJavaFile(sourceRoot, "co/fanki/app", "OrderRepository.java", """
                package co.fanki.app;

                public class OrderRepository {
                }
                """);

        final ProjectGraph graph = parser.parse(projectRoot);

        final Set<String> neighbors = graph.resolve("co.fanki.app.OrderService");
        assertTrue(neighbors.contains("co.fanki.app.OrderRepository"));
    }

    @Test
    void whenParsing_givenEntryPointAnnotations_shouldMarkEntryPoints(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeJavaFile(sourceRoot, "co/fanki/app", "ApiController.java", """
                package co.fanki.app;

                @RestController
                public class ApiController {
                }
                """);

        writeJavaFile(sourceRoot, "co/fanki/app", "PlainService.java", """
                package co.fanki.app;

                public class PlainService {
                }
                """);

        final ProjectGraph graph = parser.parse(projectRoot);

        assertEquals(1, graph.entryPointCount());
        final List<String> order = graph.analysisOrder();
        assertEquals("co.fanki.app.ApiController", order.get(0));
    }

    @Test
    void whenParsing_givenEmptySourceDirectory_shouldReturnEmptyGraph(
            @TempDir final Path projectRoot) throws IOException {

        createSourceRoot(projectRoot);

        final ProjectGraph graph = parser.parse(projectRoot);

        assertEquals(0, graph.nodeCount());
        assertEquals(0, graph.entryPointCount());
        assertTrue(graph.identifiers().isEmpty());
    }

    @Test
    void whenParsing_givenNoSourceRootDirectory_shouldReturnEmptyGraph(
            @TempDir final Path projectRoot) throws IOException {

        final ProjectGraph graph = parser.parse(projectRoot);

        assertEquals(0, graph.nodeCount());
        assertEquals(0, graph.entryPointCount());
    }

    @Test
    void whenParsing_givenNullProjectRoot_shouldThrowException() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(null));
    }

    @Test
    void whenParsing_givenSourceFilePaths_shouldStoreRelativePaths(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeJavaFile(sourceRoot, "co/fanki/app", "Config.java", """
                package co.fanki.app;

                public class Config {
                }
                """);

        final ProjectGraph graph = parser.parse(projectRoot);

        assertEquals(
                "src/main/java/co/fanki/app/Config.java",
                graph.sourceFile("co.fanki.app.Config"));
    }

    // -- parse(): dependency filtering --

    @Test
    void whenParsing_givenExternalImports_shouldFilterToInternalOnly(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeJavaFile(sourceRoot, "co/fanki/app", "MyService.java", """
                package co.fanki.app;

                import java.util.List;
                import org.springframework.stereotype.Service;
                import co.fanki.app.MyRepository;

                @Service
                public class MyService {
                }
                """);

        writeJavaFile(sourceRoot, "co/fanki/app", "MyRepository.java", """
                package co.fanki.app;

                public class MyRepository {
                }
                """);

        final ProjectGraph graph = parser.parse(projectRoot);

        final Set<String> deps = graph.resolve("co.fanki.app.MyService");
        assertEquals(1, deps.size());
        assertTrue(deps.contains("co.fanki.app.MyRepository"));
    }

    @Test
    void whenParsing_givenStaticImportOfInternalClass_shouldResolveDependency(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeJavaFile(sourceRoot, "co/fanki/app", "Constants.java", """
                package co.fanki.app;

                public class Constants {
                    public static final String NAME = "fanki";
                }
                """);

        writeJavaFile(sourceRoot, "co/fanki/app", "Printer.java", """
                package co.fanki.app;

                import static co.fanki.app.Constants.NAME;

                public class Printer {
                }
                """);

        final ProjectGraph graph = parser.parse(projectRoot);

        final Set<String> deps = graph.resolve("co.fanki.app.Printer");
        assertTrue(deps.contains("co.fanki.app.Constants"));
    }

    @Test
    void whenParsing_givenImportsAfterClassDeclaration_shouldIgnoreThem(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeJavaFile(sourceRoot, "co/fanki/app", "Ignored.java", """
                package co.fanki.app;

                public class Ignored {
                }
                """);

        writeJavaFile(sourceRoot, "co/fanki/app", "Weird.java", """
                package co.fanki.app;

                public class Weird {
                    // import co.fanki.app.Ignored;
                }
                """);

        final ProjectGraph graph = parser.parse(projectRoot);

        final Set<String> deps = graph.resolve("co.fanki.app.Weird");
        assertFalse(deps.contains("co.fanki.app.Ignored"));
    }

    // -- Entry point detection for all annotations --

    @Test
    void whenParsing_givenControllerAnnotation_shouldDetectEntryPoint(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeJavaFile(sourceRoot, "co/fanki/app", "WebController.java", """
                package co.fanki.app;

                @Controller
                public class WebController {
                }
                """);

        final ProjectGraph graph = parser.parse(projectRoot);

        assertEquals(1, graph.entryPointCount());
    }

    @Test
    void whenParsing_givenKafkaListenerAnnotation_shouldDetectEntryPoint(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeJavaFile(sourceRoot, "co/fanki/app", "EventConsumer.java", """
                package co.fanki.app;

                public class EventConsumer {

                    @KafkaListener(topics = "orders")
                    public void consume(String message) {
                    }
                }
                """);

        final ProjectGraph graph = parser.parse(projectRoot);

        assertEquals(1, graph.entryPointCount());
    }

    @Test
    void whenParsing_givenScheduledAnnotation_shouldDetectEntryPoint(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeJavaFile(sourceRoot, "co/fanki/app", "CronJob.java", """
                package co.fanki.app;

                public class CronJob {

                    @Scheduled(fixedRate = 5000)
                    public void run() {
                    }
                }
                """);

        final ProjectGraph graph = parser.parse(projectRoot);

        assertEquals(1, graph.entryPointCount());
    }

    @Test
    void whenParsing_givenEventListenerAnnotation_shouldDetectEntryPoint(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeJavaFile(sourceRoot, "co/fanki/app", "DomainListener.java", """
                package co.fanki.app;

                public class DomainListener {

                    @EventListener
                    public void onEvent(Object event) {
                    }
                }
                """);

        final ProjectGraph graph = parser.parse(projectRoot);

        assertEquals(1, graph.entryPointCount());
    }

    @Test
    void whenParsing_givenSpringBootApplicationAnnotation_shouldDetectEntryPoint(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeJavaFile(sourceRoot, "co/fanki/app", "App.java", """
                package co.fanki.app;

                @SpringBootApplication
                public class App {
                    public static void main(String[] args) {}
                }
                """);

        final ProjectGraph graph = parser.parse(projectRoot);

        assertEquals(1, graph.entryPointCount());
    }

    @Test
    void whenParsing_givenNoAnnotations_shouldNotDetectEntryPoint(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeJavaFile(sourceRoot, "co/fanki/app", "PlainPojo.java", """
                package co.fanki.app;

                public class PlainPojo {
                    private String name;
                }
                """);

        final ProjectGraph graph = parser.parse(projectRoot);

        assertEquals(0, graph.entryPointCount());
    }

    // -- FQCN extraction --

    @Test
    void whenExtractingFqcn_givenNestedPackage_shouldProduceDottedName(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeJavaFile(sourceRoot, "co/fanki/checkout/domain", "Cart.java", """
                package co.fanki.checkout.domain;

                public class Cart {
                }
                """);

        final ProjectGraph graph = parser.parse(projectRoot);

        assertTrue(graph.contains("co.fanki.checkout.domain.Cart"));
    }

    @Test
    void whenExtractingFqcn_givenRootPackageFile_shouldProduceSimpleName(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeJavaFile(sourceRoot, "", "Main.java", """
                public class Main {
                }
                """);

        final ProjectGraph graph = parser.parse(projectRoot);

        assertTrue(graph.contains("Main"));
    }

    // -- Analysis order with BFS from entry points --

    @Test
    void whenGettingAnalysisOrder_givenEntryPointsWithDeps_shouldBfsOrder(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeJavaFile(sourceRoot, "co/fanki/app", "Controller.java", """
                package co.fanki.app;

                import co.fanki.app.Service;

                @RestController
                public class Controller {
                }
                """);

        writeJavaFile(sourceRoot, "co/fanki/app", "Service.java", """
                package co.fanki.app;

                import co.fanki.app.Repository;

                public class Service {
                }
                """);

        writeJavaFile(sourceRoot, "co/fanki/app", "Repository.java", """
                package co.fanki.app;

                public class Repository {
                }
                """);

        final ProjectGraph graph = parser.parse(projectRoot);
        final List<String> order = graph.analysisOrder();

        assertEquals(3, order.size());
        assertEquals("co.fanki.app.Controller", order.get(0));

        int serviceIdx = order.indexOf("co.fanki.app.Service");
        int repoIdx = order.indexOf("co.fanki.app.Repository");
        assertTrue(serviceIdx < repoIdx,
                "Service should come before Repository in BFS order");
    }

    // -- Non-Java files ignored --

    @Test
    void whenParsing_givenNonJavaFiles_shouldIgnoreThem(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeJavaFile(sourceRoot, "co/fanki/app", "Valid.java", """
                package co.fanki.app;

                public class Valid {
                }
                """);

        final Path txtFile = sourceRoot
                .resolve("co/fanki/app/notes.txt");
        Files.writeString(txtFile, "This is not a Java file.");

        final Path xmlFile = sourceRoot
                .resolve("co/fanki/app/config.xml");
        Files.writeString(xmlFile, "<config/>");

        final ProjectGraph graph = parser.parse(projectRoot);

        assertEquals(1, graph.nodeCount());
        assertTrue(graph.contains("co.fanki.app.Valid"));
    }

    // -- extractMethodParameters() --

    @Test
    void whenExtractingParams_givenSingleInternalParam_shouldReturnIt(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeJavaFile(sourceRoot, "co/fanki/app", "UserService.java", """
                package co.fanki.app;

                import co.fanki.app.UserRepository;

                public class UserService {
                    public void findUser(UserRepository repo) {
                    }
                }
                """);

        writeJavaFile(sourceRoot, "co/fanki/app", "UserRepository.java", """
                package co.fanki.app;

                public class UserRepository {
                }
                """);

        final Path file = sourceRoot
                .resolve("co/fanki/app/UserService.java");
        final Set<String> known = Set.of(
                "co.fanki.app.UserService",
                "co.fanki.app.UserRepository");

        final Map<String, List<String>> result =
                parser.extractMethodParameters(file, sourceRoot, known);

        assertTrue(result.containsKey("findUser"));
        assertEquals(List.of("co.fanki.app.UserRepository"),
                result.get("findUser"));
    }

    @Test
    void whenExtractingParams_givenMultipleParams_shouldReturnAllMatched(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeJavaFile(sourceRoot, "co/fanki/app", "OrderService.java", """
                package co.fanki.app;

                import co.fanki.app.Order;
                import co.fanki.app.Customer;

                public class OrderService {
                    public void placeOrder(Order order, Customer customer, String note) {
                    }
                }
                """);

        writeJavaFile(sourceRoot, "co/fanki/app", "Order.java", """
                package co.fanki.app;
                public class Order {}
                """);

        writeJavaFile(sourceRoot, "co/fanki/app", "Customer.java", """
                package co.fanki.app;
                public class Customer {}
                """);

        final Path file = sourceRoot
                .resolve("co/fanki/app/OrderService.java");
        final Set<String> known = Set.of(
                "co.fanki.app.OrderService",
                "co.fanki.app.Order",
                "co.fanki.app.Customer");

        final Map<String, List<String>> result =
                parser.extractMethodParameters(file, sourceRoot, known);

        assertTrue(result.containsKey("placeOrder"));
        final List<String> params = result.get("placeOrder");
        assertEquals(2, params.size());
        assertTrue(params.contains("co.fanki.app.Order"));
        assertTrue(params.contains("co.fanki.app.Customer"));
    }

    @Test
    void whenExtractingParams_givenMultilineSignature_shouldResolveParams(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeJavaFile(sourceRoot, "co/fanki/app", "OrderService.java", """
                package co.fanki.app;

                import co.fanki.app.Order;
                import co.fanki.app.Customer;

                public class OrderService {
                    public void placeOrder(
                            final Order order,
                            final Customer customer,
                            final String note) {
                    }
                }
                """);

        writeJavaFile(sourceRoot, "co/fanki/app", "Order.java", """
                package co.fanki.app;
                public class Order {}
                """);

        writeJavaFile(sourceRoot, "co/fanki/app", "Customer.java", """
                package co.fanki.app;
                public class Customer {}
                """);

        final Path file = sourceRoot
                .resolve("co/fanki/app/OrderService.java");
        final Set<String> known = Set.of(
                "co.fanki.app.OrderService",
                "co.fanki.app.Order",
                "co.fanki.app.Customer");

        final Map<String, List<String>> result =
                parser.extractMethodParameters(file, sourceRoot, known);

        assertTrue(result.containsKey("placeOrder"));
        final List<String> params = result.get("placeOrder");
        assertEquals(2, params.size());
        assertTrue(params.contains("co.fanki.app.Order"));
        assertTrue(params.contains("co.fanki.app.Customer"));
    }

    @Test
    void whenExtractingParams_givenOnlyExternalTypes_shouldReturnEmpty(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeJavaFile(sourceRoot, "co/fanki/app", "Printer.java", """
                package co.fanki.app;

                public class Printer {
                    public void print(String message, int count) {
                    }
                }
                """);

        final Path file = sourceRoot
                .resolve("co/fanki/app/Printer.java");
        final Set<String> known = Set.of("co.fanki.app.Printer");

        final Map<String, List<String>> result =
                parser.extractMethodParameters(file, sourceRoot, known);

        assertTrue(result.isEmpty());
    }

    @Test
    void whenExtractingParams_givenSamePackageType_shouldResolveIt(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeJavaFile(sourceRoot, "co/fanki/app", "Handler.java", """
                package co.fanki.app;

                public class Handler {
                    public void handle(Event event) {
                    }
                }
                """);

        writeJavaFile(sourceRoot, "co/fanki/app", "Event.java", """
                package co.fanki.app;
                public class Event {}
                """);

        final Path file = sourceRoot
                .resolve("co/fanki/app/Handler.java");
        final Set<String> known = Set.of(
                "co.fanki.app.Handler",
                "co.fanki.app.Event");

        final Map<String, List<String>> result =
                parser.extractMethodParameters(file, sourceRoot, known);

        assertTrue(result.containsKey("handle"));
        assertEquals(List.of("co.fanki.app.Event"),
                result.get("handle"));
    }

    @Test
    void whenExtractingParams_givenFinalAnnotatedParam_shouldStillMatch(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeJavaFile(sourceRoot, "co/fanki/app", "Processor.java", """
                package co.fanki.app;

                import co.fanki.app.Task;

                public class Processor {
                    public void process(final Task task) {
                    }
                }
                """);

        writeJavaFile(sourceRoot, "co/fanki/app", "Task.java", """
                package co.fanki.app;
                public class Task {}
                """);

        final Path file = sourceRoot
                .resolve("co/fanki/app/Processor.java");
        final Set<String> known = Set.of(
                "co.fanki.app.Processor",
                "co.fanki.app.Task");

        final Map<String, List<String>> result =
                parser.extractMethodParameters(file, sourceRoot, known);

        assertTrue(result.containsKey("process"));
        assertEquals(List.of("co.fanki.app.Task"),
                result.get("process"));
    }

    @Test
    void whenExtractingParams_givenMethodWithNoParams_shouldSkipIt(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeJavaFile(sourceRoot, "co/fanki/app", "Runner.java", """
                package co.fanki.app;

                public class Runner {
                    public void run() {
                    }
                }
                """);

        final Path file = sourceRoot
                .resolve("co/fanki/app/Runner.java");
        final Set<String> known = Set.of("co.fanki.app.Runner");

        final Map<String, List<String>> result =
                parser.extractMethodParameters(file, sourceRoot, known);

        assertTrue(result.isEmpty());
    }

    // -- inferClassType() --

    @Test
    void whenInferringClassType_givenRestController_shouldReturnController(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeJavaFile(sourceRoot, "co/fanki/app",
                "UserController.java", """
                package co.fanki.app;

                @RestController
                @RequestMapping("/api/users")
                public class UserController {
                }
                """);

        assertEquals(ClassType.CONTROLLER, parser.inferClassType(file));
    }

    @Test
    void whenInferringClassType_givenController_shouldReturnController(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeJavaFile(sourceRoot, "co/fanki/app",
                "WebController.java", """
                package co.fanki.app;

                @Controller
                public class WebController {
                }
                """);

        assertEquals(ClassType.CONTROLLER, parser.inferClassType(file));
    }

    @Test
    void whenInferringClassType_givenServiceAnnotation_shouldReturnService(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeJavaFile(sourceRoot, "co/fanki/app",
                "UserService.java", """
                package co.fanki.app;

                @Service
                public class UserService {
                }
                """);

        assertEquals(ClassType.SERVICE, parser.inferClassType(file));
    }

    @Test
    void whenInferringClassType_givenRepositoryAnnotation_shouldReturnRepository(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeJavaFile(sourceRoot, "co/fanki/app",
                "UserRepository.java", """
                package co.fanki.app;

                @Repository
                public class UserRepository {
                }
                """);

        assertEquals(ClassType.REPOSITORY, parser.inferClassType(file));
    }

    @Test
    void whenInferringClassType_givenConfigurationAnnotation_shouldReturnConfiguration(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeJavaFile(sourceRoot, "co/fanki/app",
                "AppConfig.java", """
                package co.fanki.app;

                @Configuration
                public class AppConfig {
                }
                """);

        assertEquals(ClassType.CONFIGURATION, parser.inferClassType(file));
    }

    @Test
    void whenInferringClassType_givenEntityAnnotation_shouldReturnEntity(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeJavaFile(sourceRoot, "co/fanki/app",
                "User.java", """
                package co.fanki.app;

                @Entity
                public class User {
                    private String id;
                }
                """);

        assertEquals(ClassType.ENTITY, parser.inferClassType(file));
    }

    @Test
    void whenInferringClassType_givenKafkaListener_shouldReturnListener(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeJavaFile(sourceRoot, "co/fanki/app",
                "EventConsumer.java", """
                package co.fanki.app;

                public class EventConsumer {
                    @KafkaListener(topics = "orders")
                    public void consume(String msg) {}
                }
                """);

        assertEquals(ClassType.LISTENER, parser.inferClassType(file));
    }

    @Test
    void whenInferringClassType_givenNoAnnotations_shouldReturnOther(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeJavaFile(sourceRoot, "co/fanki/app",
                "PlainPojo.java", """
                package co.fanki.app;

                public class PlainPojo {
                    private String name;
                }
                """);

        assertEquals(ClassType.OTHER, parser.inferClassType(file));
    }

    // -- extractMethods() --

    @Test
    void whenExtractingMethods_givenSimpleClass_shouldReturnMethodsWithLineNumbers(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeJavaFile(sourceRoot, "co/fanki/app",
                "UserService.java", """
                package co.fanki.app;

                public class UserService {

                    public void findById(String id) {
                        // implementation
                    }

                    public void createUser(String name) {
                        // implementation
                    }
                }
                """);

        final List<StaticMethodInfo> methods = parser.extractMethods(file);

        assertEquals(2, methods.size());
        assertEquals("findById", methods.get(0).methodName());
        assertEquals(5, methods.get(0).lineNumber());
        assertEquals("createUser", methods.get(1).methodName());
        assertEquals(9, methods.get(1).lineNumber());
    }

    @Test
    void whenExtractingMethods_givenGetMapping_shouldExtractHttpInfo(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeJavaFile(sourceRoot, "co/fanki/app",
                "UserController.java", """
                package co.fanki.app;

                @RestController
                public class UserController {

                    @GetMapping("/users")
                    public void listUsers() {
                    }

                    @PostMapping("/users")
                    public void createUser(String name) {
                    }

                    @PutMapping("/users/{id}")
                    public void updateUser(String id) {
                    }

                    @DeleteMapping("/users/{id}")
                    public void deleteUser(String id) {
                    }

                    @PatchMapping("/users/{id}")
                    public void patchUser(String id) {
                    }
                }
                """);

        final List<StaticMethodInfo> methods = parser.extractMethods(file);

        assertEquals(5, methods.size());

        assertEquals("listUsers", methods.get(0).methodName());
        assertEquals("GET", methods.get(0).httpMethod());
        assertEquals("/users", methods.get(0).httpPath());

        assertEquals("createUser", methods.get(1).methodName());
        assertEquals("POST", methods.get(1).httpMethod());
        assertEquals("/users", methods.get(1).httpPath());

        assertEquals("updateUser", methods.get(2).methodName());
        assertEquals("PUT", methods.get(2).httpMethod());
        assertEquals("/users/{id}", methods.get(2).httpPath());

        assertEquals("deleteUser", methods.get(3).methodName());
        assertEquals("DELETE", methods.get(3).httpMethod());
        assertEquals("/users/{id}", methods.get(3).httpPath());

        assertEquals("patchUser", methods.get(4).methodName());
        assertEquals("PATCH", methods.get(4).httpMethod());
        assertEquals("/users/{id}", methods.get(4).httpPath());
    }

    @Test
    void whenExtractingMethods_givenThrowsClause_shouldExtractExceptions(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeJavaFile(sourceRoot, "co/fanki/app",
                "OrderService.java", """
                package co.fanki.app;

                public class OrderService {

                    public void placeOrder(String id) throws IllegalArgumentException, IOException {
                        // implementation
                    }
                }
                """);

        final List<StaticMethodInfo> methods = parser.extractMethods(file);

        assertEquals(1, methods.size());
        assertEquals("placeOrder", methods.get(0).methodName());
        assertEquals(List.of("IllegalArgumentException", "IOException"),
                methods.get(0).exceptions());
    }

    @Test
    void whenExtractingMethods_givenNoMethods_shouldReturnEmpty(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeJavaFile(sourceRoot, "co/fanki/app",
                "Constants.java", """
                package co.fanki.app;

                public class Constants {
                    public static final String NAME = "fanki";
                }
                """);

        final List<StaticMethodInfo> methods = parser.extractMethods(file);

        assertTrue(methods.isEmpty());
    }

    @Test
    void whenExtractingMethods_givenMethodWithNoHttpAnnotation_shouldHaveNullHttp(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeJavaFile(sourceRoot, "co/fanki/app",
                "Service.java", """
                package co.fanki.app;

                public class Service {
                    public void doWork() {
                    }
                }
                """);

        final List<StaticMethodInfo> methods = parser.extractMethods(file);

        assertEquals(1, methods.size());
        assertNull(methods.get(0).httpMethod());
        assertNull(methods.get(0).httpPath());
        assertTrue(methods.get(0).exceptions().isEmpty());
    }

    @Test
    void whenExtractingMethods_givenRequestMapping_shouldExtractHttpInfo(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeJavaFile(sourceRoot, "co/fanki/app",
                "LegacyController.java", """
                package co.fanki.app;

                @RestController
                public class LegacyController {

                    @RequestMapping(value = "/api/legacy", method = RequestMethod.POST)
                    public void legacyEndpoint() {
                    }
                }
                """);

        final List<StaticMethodInfo> methods = parser.extractMethods(file);

        assertEquals(1, methods.size());
        assertEquals("POST", methods.get(0).httpMethod());
        assertEquals("/api/legacy", methods.get(0).httpPath());
    }

    @Test
    void whenExtractingMethods_givenMultilineThrows_shouldExtractExceptions(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeJavaFile(sourceRoot, "co/fanki/app",
                "Processor.java", """
                package co.fanki.app;

                public class Processor {

                    public void process(
                            final String input)
                            throws IllegalStateException {
                        // implementation
                    }
                }
                """);

        final List<StaticMethodInfo> methods = parser.extractMethods(file);

        assertEquals(1, methods.size());
        assertEquals("process", methods.get(0).methodName());
        assertEquals(List.of("IllegalStateException"),
                methods.get(0).exceptions());
    }

    @Test
    void whenExtractingMethods_givenGenericReturnTypes_shouldExtractCorrectly(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeJavaFile(sourceRoot, "co/fanki/app",
                "EventService.java", """
                package co.fanki.app;

                import java.util.List;
                import java.util.Map;
                import java.util.Optional;

                public class EventService {

                    public Map<String, List<String>> getByCode(String code) {
                        return null;
                    }

                    public Optional<Map<String, Object>> findDetails(String id) {
                        return Optional.empty();
                    }

                    public List<String> findAll() {
                        return List.of();
                    }

                    public void save(String name) {
                    }
                }
                """);

        final List<StaticMethodInfo> methods = parser.extractMethods(file);

        assertEquals(4, methods.size());
        assertEquals("getByCode", methods.get(0).methodName());
        assertEquals("findDetails", methods.get(1).methodName());
        assertEquals("findAll", methods.get(2).methodName());
        assertEquals("save", methods.get(3).methodName());
    }

    @Test
    void whenExtractingMethods_givenConstructorAndMethod_shouldExtractBoth(
            @TempDir final Path projectRoot) throws IOException {

        // Note: the regex-based parser also matches constructors because
        // the access modifier can be consumed as a "return type".
        // This is a known limitation that does not affect enrichment
        // quality, since constructor descriptions are still useful.
        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeJavaFile(sourceRoot, "co/fanki/app",
                "MyService.java", """
                package co.fanki.app;

                public class MyService {

                    public MyService(String dependency) {
                    }

                    public void doWork() {
                    }
                }
                """);

        final List<StaticMethodInfo> methods = parser.extractMethods(file);

        assertEquals(2, methods.size());
        assertEquals("MyService", methods.get(0).methodName());
        assertEquals("doWork", methods.get(1).methodName());
    }

    @Test
    void whenExtractingParams_givenGenericReturnType_shouldExtractParams(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeJavaFile(sourceRoot, "co/fanki/app", "OrderService.java", """
                package co.fanki.app;

                import java.util.Map;
                import java.util.List;
                import co.fanki.app.Order;

                public class OrderService {
                    public Map<String, List<Order>> findOrders(Order filter) {
                        return null;
                    }
                }
                """);

        writeJavaFile(sourceRoot, "co/fanki/app", "Order.java", """
                package co.fanki.app;
                public class Order {}
                """);

        final Path file = sourceRoot
                .resolve("co/fanki/app/OrderService.java");
        final Set<String> known = Set.of(
                "co.fanki.app.OrderService",
                "co.fanki.app.Order");

        final Map<String, List<String>> result =
                parser.extractMethodParameters(file, sourceRoot, known);

        assertTrue(result.containsKey("findOrders"));
        assertEquals(List.of("co.fanki.app.Order"),
                result.get("findOrders"));
    }

    // -- Helper methods --

    private Path createSourceRoot(final Path projectRoot) throws IOException {
        final Path sourceRoot = projectRoot.resolve("src/main/java");
        Files.createDirectories(sourceRoot);
        return sourceRoot;
    }

    private Path writeJavaFile(final Path sourceRoot,
            final String packagePath,
            final String fileName,
            final String content) throws IOException {

        final Path dir = packagePath.isEmpty()
                ? sourceRoot
                : sourceRoot.resolve(packagePath);
        Files.createDirectories(dir);
        final Path file = dir.resolve(fileName);
        Files.writeString(file, content);
        return file;
    }

}
