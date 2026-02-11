package co.fanki.domainmcp.analysis.application;

import co.fanki.domainmcp.analysis.application.CodeContextService.ClassContext;
import co.fanki.domainmcp.analysis.application.CodeContextService.ClassDependencies;
import co.fanki.domainmcp.analysis.application.CodeContextService.ExecutionPathEntry;
import co.fanki.domainmcp.analysis.application.CodeContextService.GraphInfo;
import co.fanki.domainmcp.analysis.application.CodeContextService.MethodContext;
import co.fanki.domainmcp.analysis.application.CodeContextService.ProjectOverview;
import co.fanki.domainmcp.analysis.application.CodeContextService.ProjectSearchResult;
import co.fanki.domainmcp.analysis.application.CodeContextService.ServiceApi;
import co.fanki.domainmcp.analysis.application.CodeContextService.StackFrame;
import co.fanki.domainmcp.analysis.application.CodeContextService.StackTraceContext;
import co.fanki.domainmcp.analysis.domain.ClassType;
import co.fanki.domainmcp.analysis.domain.MethodParameterRepository;
import co.fanki.domainmcp.analysis.domain.ProjectGraph;
import co.fanki.domainmcp.analysis.domain.GraphService;
import co.fanki.domainmcp.analysis.domain.SourceClass;
import co.fanki.domainmcp.analysis.domain.SourceClassRepository;
import co.fanki.domainmcp.analysis.domain.SourceMethod;
import co.fanki.domainmcp.analysis.domain.SourceMethodRepository;
import co.fanki.domainmcp.project.domain.Project;
import co.fanki.domainmcp.project.domain.ProjectRepository;
import co.fanki.domainmcp.project.domain.RepositoryUrl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for CodeContextService query methods.
 *
 * <p>Tests getClassContext, getMethodContext, and getStackTraceContext
 * with graph-enhanced neighbor resolution. The analyzeProject method
 * requires JGit cloning and Claude API calls, so it is tested via
 * integration tests instead.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class CodeContextServiceTest {

    private ProjectRepository projectRepository;
    private SourceClassRepository sourceClassRepository;
    private SourceMethodRepository sourceMethodRepository;
    private MethodParameterRepository methodParameterRepository;
    private GraphService graphCache;

    private CodeContextService service;

    @BeforeEach
    void setUp() {
        projectRepository = createMock(ProjectRepository.class);
        sourceClassRepository = createMock(SourceClassRepository.class);
        sourceMethodRepository = createMock(SourceMethodRepository.class);
        methodParameterRepository = createMock(MethodParameterRepository.class);
        graphCache = createMock(GraphService.class);

        service = new CodeContextService(
                projectRepository,
                sourceClassRepository,
                sourceMethodRepository,
                methodParameterRepository,
                graphCache,
                "sk-ant-dummy-key-for-tests",
                "/tmp/test-repos");
    }

    @Test
    void whenGettingClassContext_givenExistingClass_shouldReturnFullContext() {
        final SourceClass userClass = SourceClass.create(
                "project-1",
                "co.fanki.user.UserService",
                ClassType.SERVICE,
                "User management",
                "UserService.java",
                "abc123");

        final SourceMethod findUser = SourceMethod.create(
                userClass.id(), "findById", "Finds user by ID",
                List.of("Query DB"),
                List.of(), null, null, 30);

        final SourceMethod createUser = SourceMethod.create(
                userClass.id(), "createUser", "Creates a new user",
                List.of("Validate", "Save"),
                List.of("DuplicateEmailException"), "POST", "/api/users", 55);

        final Project project = Project.create(
                "user-service",
                RepositoryUrl.of("https://github.com/fanki/user-svc.git"),
                "main");

        expect(sourceClassRepository.findByFullClassName(
                "co.fanki.user.UserService"))
                .andReturn(Optional.of(userClass));

        expect(sourceMethodRepository.findByClassId(userClass.id()))
                .andReturn(List.of(findUser, createUser));

        expect(projectRepository.findById(userClass.projectId()))
                .andReturn(Optional.of(project));

        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.user.UserService", "UserService.java");
        graph.addNode("co.fanki.user.UserRepository", "UserRepo.java");
        graph.addNode("co.fanki.user.UserController", "UserCtrl.java");
        graph.addDependency("co.fanki.user.UserService",
                "co.fanki.user.UserRepository");
        graph.addDependency("co.fanki.user.UserController",
                "co.fanki.user.UserService");
        graph.markAsEntryPoint("co.fanki.user.UserController");

        expect(graphCache.getGraph(userClass.projectId()))
                .andReturn(graph);

        replay(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);

        final ClassContext context = service.getClassContext(
                "co.fanki.user.UserService");

        assertTrue(context.found());
        assertEquals("co.fanki.user.UserService", context.className());
        assertEquals("SERVICE", context.classType());
        assertEquals(2, context.methods().size());
        assertEquals("findById", context.methods().get(0).name());
        assertEquals("createUser", context.methods().get(1).name());

        assertNotNull(context.graphInfo());
        assertEquals(List.of("co.fanki.user.UserRepository"),
                context.graphInfo().dependencies());
        assertEquals(List.of("co.fanki.user.UserController"),
                context.graphInfo().dependents());
        assertFalse(context.graphInfo().entryPoint());

        verify(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);
    }

    @Test
    void whenGettingClassContext_givenUnknownClass_shouldReturnNotFound() {
        expect(sourceClassRepository.findByFullClassName("com.unknown.Foo"))
                .andReturn(Optional.empty());

        expect(projectRepository.findAll()).andReturn(List.of());

        replay(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);

        final ClassContext context = service.getClassContext("com.unknown.Foo");

        assertFalse(context.found());
        assertEquals("com.unknown.Foo", context.className());
        assertNotNull(context.message());

        verify(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);
    }

    @Test
    void whenGettingMethodContext_givenExistingMethod_shouldReturnContext() {
        final SourceClass orderClass = SourceClass.create(
                "project-1",
                "co.fanki.order.OrderService",
                ClassType.SERVICE,
                "Order processing",
                "OrderService.java",
                "abc123");

        final SourceMethod placeOrder = SourceMethod.create(
                orderClass.id(), "placeOrder",
                "Places a new order for a customer",
                List.of("Validate items", "Calculate total", "Save order"),
                List.of("InsufficientStockException"),
                "POST", "/api/orders", 85);

        final Project project = Project.create(
                "order-service",
                RepositoryUrl.of("https://github.com/fanki/orders.git"),
                "main");

        expect(sourceClassRepository.findByFullClassName(
                "co.fanki.order.OrderService"))
                .andReturn(Optional.of(orderClass));

        expect(sourceMethodRepository.findByClassNameAndMethodName(
                "co.fanki.order.OrderService", "placeOrder"))
                .andReturn(Optional.of(placeOrder));

        expect(projectRepository.findById(orderClass.projectId()))
                .andReturn(Optional.of(project));

        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.order.OrderService", "OrderService.java");
        graph.addNode("co.fanki.order.OrderDto", "OrderDto.java");
        graph.addMethodParameter("co.fanki.order.OrderService",
                "placeOrder", 0, "co.fanki.order.OrderDto");

        expect(graphCache.getGraph(orderClass.projectId()))
                .andReturn(graph);

        replay(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);

        final MethodContext context = service.getMethodContext(
                "co.fanki.order.OrderService", "placeOrder");

        assertTrue(context.found());
        assertEquals("co.fanki.order.OrderService", context.className());
        assertEquals("placeOrder", context.methodName());
        assertEquals("POST /api/orders", context.httpEndpoint());
        assertEquals(3, context.businessLogic().size());
        assertEquals(1, context.parameterTypes().size());
        assertEquals(0, context.parameterTypes().get(0).position());
        assertEquals("co.fanki.order.OrderDto",
                context.parameterTypes().get(0).typeName());

        verify(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);
    }

    @Test
    void whenGettingMethodContext_givenUnknownClass_shouldReturnNotFound() {
        expect(sourceClassRepository.findByFullClassName("com.unknown.Foo"))
                .andReturn(Optional.empty());

        expect(projectRepository.findAll()).andReturn(List.of());

        replay(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);

        final MethodContext context = service.getMethodContext(
                "com.unknown.Foo", "bar");

        assertFalse(context.found());
        assertNotNull(context.message());

        verify(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);
    }

    @Test
    void whenGettingMethodContext_givenClassFoundButMethodMissing_shouldReturnPartialContext() {
        final SourceClass orderClass = SourceClass.create(
                "project-1",
                "co.fanki.order.OrderService",
                ClassType.SERVICE,
                "Order processing",
                "OrderService.java",
                "abc123");

        expect(sourceClassRepository.findByFullClassName(
                "co.fanki.order.OrderService"))
                .andReturn(Optional.of(orderClass));

        expect(graphCache.getGraph(orderClass.projectId()))
                .andReturn(null);

        expect(sourceMethodRepository.findByClassNameAndMethodName(
                "co.fanki.order.OrderService", "unknownMethod"))
                .andReturn(Optional.empty());

        replay(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);

        final MethodContext context = service.getMethodContext(
                "co.fanki.order.OrderService", "unknownMethod");

        assertFalse(context.found());
        assertEquals("Class found but method not indexed", context.message());

        verify(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);
    }

    @Test
    void whenGettingStackTraceContext_givenMixedFrames_shouldReturnFoundAndMissing() {
        final SourceClass paymentClass = SourceClass.create(
                "project-1",
                "co.fanki.payment.PaymentService",
                ClassType.SERVICE,
                "Payment processing",
                "PaymentService.java",
                "abc123");

        final SourceMethod processMethod = SourceMethod.create(
                paymentClass.id(),
                "processPayment",
                "Processes a payment",
                List.of("Validate", "Charge", "Save"),
                List.of("PaymentDeclinedException"),
                null, null, 42);

        final Project project = Project.create(
                "payment-service",
                RepositoryUrl.of("https://github.com/fanki/payment.git"),
                "main");

        expect(sourceClassRepository.findByFullClassName(
                "co.fanki.payment.PaymentService"))
                .andReturn(Optional.of(paymentClass));

        expect(sourceMethodRepository.findByClassNameAndMethodName(
                "co.fanki.payment.PaymentService", "processPayment"))
                .andReturn(Optional.of(processMethod));

        expect(projectRepository.findById(paymentClass.projectId()))
                .andReturn(Optional.of(project));

        expect(sourceClassRepository.findByFullClassName(
                "org.springframework.web.servlet.DispatcherServlet"))
                .andReturn(Optional.empty());

        expect(graphCache.getGraph(paymentClass.projectId()))
                .andReturn(null);

        replay(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);

        final List<StackFrame> frames = List.of(
                new StackFrame("co.fanki.payment.PaymentService",
                        "processPayment", 42),
                new StackFrame(
                        "org.springframework.web.servlet.DispatcherServlet",
                        "doDispatch", 1067));

        final StackTraceContext context = service.getStackTraceContext(frames);

        assertNotNull(context);
        assertEquals(2, context.executionPath().size());
        assertEquals(1, context.missingContext().size());

        assertTrue(context.executionPath().get(0).found());
        assertEquals("co.fanki.payment.PaymentService",
                context.executionPath().get(0).className());
        assertEquals("processPayment",
                context.executionPath().get(0).methodName());
        assertEquals("SERVICE",
                context.executionPath().get(0).classType());
        assertEquals(3,
                context.executionPath().get(0).businessLogic().size());

        assertFalse(context.executionPath().get(1).found());
        assertEquals(
                "org.springframework.web.servlet.DispatcherServlet",
                context.executionPath().get(1).className());

        assertEquals("https://github.com/fanki/payment.git",
                context.projectUrl());

        verify(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);
    }

    @Test
    void whenGettingStackTraceContext_givenGraphWithNeighbors_shouldIncludeRelatedDependencies() {
        final SourceClass controllerClass = SourceClass.create(
                "project-1",
                "co.fanki.order.OrderController",
                ClassType.CONTROLLER,
                "Order REST controller",
                "OrderController.java",
                "abc123");

        final SourceMethod createOrder = SourceMethod.create(
                controllerClass.id(),
                "createOrder",
                "Creates a new order",
                List.of("Validates input", "Delegates to service"),
                List.of(),
                "POST", "/api/orders", 30);

        final Project project = Project.create(
                "order-service",
                RepositoryUrl.of("https://github.com/fanki/orders.git"),
                "main");

        final SourceClass serviceClass = SourceClass.create(
                "project-1",
                "co.fanki.order.OrderService",
                ClassType.SERVICE,
                "Order processing service",
                "OrderService.java",
                "abc123");

        final SourceMethod processOrder = SourceMethod.create(
                serviceClass.id(),
                "processOrder",
                "Processes the order",
                List.of("Calculate total", "Save"),
                List.of(),
                null, null, 50);

        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.order.OrderController",
                "OrderController.java");
        graph.addNode("co.fanki.order.OrderService",
                "OrderService.java");
        graph.addDependency("co.fanki.order.OrderController",
                "co.fanki.order.OrderService");

        expect(sourceClassRepository.findByFullClassName(
                "co.fanki.order.OrderController"))
                .andReturn(Optional.of(controllerClass));

        expect(sourceMethodRepository.findByClassNameAndMethodName(
                "co.fanki.order.OrderController", "createOrder"))
                .andReturn(Optional.of(createOrder));

        expect(projectRepository.findById(controllerClass.projectId()))
                .andReturn(Optional.of(project));

        expect(graphCache.getGraph(controllerClass.projectId()))
                .andReturn(graph);

        expect(sourceClassRepository.findByFullClassName(
                "co.fanki.order.OrderService"))
                .andReturn(Optional.of(serviceClass));

        expect(sourceMethodRepository.findByClassId(serviceClass.id()))
                .andReturn(List.of(processOrder));

        replay(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);

        final List<StackFrame> frames = List.of(
                new StackFrame("co.fanki.order.OrderController",
                        "createOrder", 30));

        final StackTraceContext context = service.getStackTraceContext(frames);

        assertNotNull(context);
        assertEquals(1, context.executionPath().size());
        assertTrue(context.missingContext().isEmpty());

        assertNotNull(context.relatedDependencies());
        assertFalse(context.relatedDependencies().isEmpty());

        final ExecutionPathEntry neighbor =
                context.relatedDependencies().get(0);
        assertEquals("co.fanki.order.OrderService", neighbor.className());
        assertEquals("processOrder", neighbor.methodName());
        assertTrue(neighbor.found());

        verify(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);
    }

    @Test
    void whenGettingStackTraceContext_givenAllFramesMissing_shouldReturnEmptyNeighbors() {
        expect(sourceClassRepository.findByFullClassName(
                "com.unknown.FooService"))
                .andReturn(Optional.empty());

        replay(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);

        final List<StackFrame> frames = List.of(
                new StackFrame("com.unknown.FooService", "doStuff", 10));

        final StackTraceContext context = service.getStackTraceContext(frames);

        assertNotNull(context);
        assertEquals(1, context.executionPath().size());
        assertFalse(context.executionPath().get(0).found());
        assertEquals(1, context.missingContext().size());
        assertTrue(context.relatedDependencies().isEmpty());

        verify(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);
    }

    @Test
    void whenGettingClassDependencies_givenExistingClass_shouldReturnFullGraph() {
        final SourceClass serviceClass = SourceClass.create(
                "project-1",
                "co.fanki.order.OrderService",
                ClassType.SERVICE,
                "Order processing",
                "OrderService.java",
                "abc123");

        final SourceClass repoClass = SourceClass.create(
                "project-1",
                "co.fanki.order.OrderRepository",
                ClassType.REPOSITORY,
                "Order persistence",
                "OrderRepository.java",
                "abc123");

        final SourceClass controllerClass = SourceClass.create(
                "project-1",
                "co.fanki.order.OrderController",
                ClassType.CONTROLLER,
                "Order REST API",
                "OrderController.java",
                "abc123");

        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.order.OrderService", "OrderService.java");
        graph.addNode("co.fanki.order.OrderRepository",
                "OrderRepository.java");
        graph.addNode("co.fanki.order.OrderController",
                "OrderController.java");
        graph.addNode("co.fanki.order.OrderDto", "OrderDto.java");
        graph.addDependency("co.fanki.order.OrderService",
                "co.fanki.order.OrderRepository");
        graph.addDependency("co.fanki.order.OrderController",
                "co.fanki.order.OrderService");
        graph.addMethodParameter("co.fanki.order.OrderService",
                "placeOrder", 0, "co.fanki.order.OrderDto");
        graph.markAsEntryPoint("co.fanki.order.OrderController");

        expect(sourceClassRepository.findByFullClassName(
                "co.fanki.order.OrderService"))
                .andReturn(Optional.of(serviceClass));

        expect(graphCache.getGraph(serviceClass.projectId()))
                .andReturn(graph);

        // Dependencies lookup
        expect(sourceClassRepository.findByFullClassName(
                "co.fanki.order.OrderRepository"))
                .andReturn(Optional.of(repoClass));

        // Dependents lookup
        expect(sourceClassRepository.findByFullClassName(
                "co.fanki.order.OrderController"))
                .andReturn(Optional.of(controllerClass));

        // Method parameter type lookup
        expect(sourceClassRepository.findByFullClassName(
                "co.fanki.order.OrderDto"))
                .andReturn(Optional.empty());

        replay(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);

        final ClassDependencies result = service.getClassDependencies(
                "co.fanki.order.OrderService");

        assertTrue(result.found());
        assertFalse(result.entryPoint());

        assertEquals(1, result.dependencies().size());
        assertEquals("co.fanki.order.OrderRepository",
                result.dependencies().get(0).className());
        assertEquals("REPOSITORY",
                result.dependencies().get(0).classType());

        assertEquals(1, result.dependents().size());
        assertEquals("co.fanki.order.OrderController",
                result.dependents().get(0).className());
        assertEquals("CONTROLLER",
                result.dependents().get(0).classType());

        assertEquals(1, result.methodParameterTypes().size());
        assertEquals("placeOrder",
                result.methodParameterTypes().get(0).methodName());

        verify(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);
    }

    @Test
    void whenGettingClassDependencies_givenUnknownClass_shouldReturnNotFound() {
        expect(sourceClassRepository.findByFullClassName("com.unknown.Foo"))
                .andReturn(Optional.empty());

        replay(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);

        final ClassDependencies result =
                service.getClassDependencies("com.unknown.Foo");

        assertFalse(result.found());
        assertNotNull(result.message());

        verify(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);
    }

    @Test
    void whenGettingProjectOverview_givenExistingProject_shouldReturnOverview() {
        final Project project = Project.create(
                "order-service",
                RepositoryUrl.of("https://github.com/fanki/orders.git"),
                "main");
        project.updateDescription("Order management system");

        final SourceClass controller = SourceClass.create(
                project.id(),
                "co.fanki.order.OrderController",
                ClassType.CONTROLLER,
                "Order REST controller",
                "OrderController.java",
                "abc123");

        final SourceClass service2 = SourceClass.create(
                project.id(),
                "co.fanki.order.OrderService",
                ClassType.SERVICE,
                "Order processing",
                "OrderService.java",
                "abc123");

        final SourceMethod createOrder = SourceMethod.create(
                controller.id(), "createOrder", "Creates order",
                List.of(), List.of(), "POST", "/api/orders", 30);

        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.order.OrderController",
                "OrderController.java");
        graph.addNode("co.fanki.order.OrderService",
                "OrderService.java");
        graph.markAsEntryPoint("co.fanki.order.OrderController");

        expect(projectRepository.findByName("order-service"))
                .andReturn(Optional.of(project));

        expect(graphCache.getGraph(project.id()))
                .andReturn(graph);

        expect(sourceClassRepository.findByProjectId(project.id()))
                .andReturn(List.of(controller, service2));

        expect(sourceClassRepository.findByFullClassName(
                "co.fanki.order.OrderController"))
                .andReturn(Optional.of(controller));

        expect(sourceMethodRepository.findByClassId(controller.id()))
                .andReturn(List.of(createOrder));

        replay(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);

        final ProjectOverview overview =
                service.getProjectOverview("order-service");

        assertTrue(overview.found());
        assertEquals("order-service", overview.projectName());
        assertEquals(2, overview.totalClasses());
        assertEquals(1, overview.totalEntryPoints());
        assertTrue(overview.classTypeBreakdown().containsKey("CONTROLLER"));
        assertTrue(overview.classTypeBreakdown().containsKey("SERVICE"));
        assertFalse(overview.entryPoints().isEmpty());
        assertEquals("co.fanki.order.OrderController",
                overview.entryPoints().get(0).className());

        verify(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);
    }

    @Test
    void whenGettingProjectOverview_givenUnknownProject_shouldReturnNotFound() {
        expect(projectRepository.findByName("unknown-project"))
                .andReturn(Optional.empty());

        replay(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);

        final ProjectOverview overview =
                service.getProjectOverview("unknown-project");

        assertFalse(overview.found());
        assertNotNull(overview.message());

        verify(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);
    }

    @Test
    void whenGettingServiceApi_givenExistingProject_shouldReturnEndpointsWithParams() {
        final Project project = Project.create(
                "order-service",
                RepositoryUrl.of("https://github.com/fanki/orders.git"),
                "main");
        project.updateDescription("Order management system");

        final SourceClass controller = SourceClass.create(
                project.id(),
                "co.fanki.order.OrderController",
                ClassType.CONTROLLER,
                "Order REST controller",
                "OrderController.java",
                "abc123");

        final SourceClass orderDto = SourceClass.create(
                project.id(),
                "co.fanki.order.OrderDto",
                ClassType.DTO,
                "Order data transfer object",
                "OrderDto.java",
                "abc123");

        final SourceMethod createOrder = SourceMethod.create(
                controller.id(), "createOrder", "Creates a new order",
                List.of("Validate input", "Delegate to service"),
                List.of("InvalidOrderException"),
                "POST", "/api/orders", 30);

        final SourceMethod getOrder = SourceMethod.create(
                controller.id(), "getOrder", "Gets an order by ID",
                List.of("Query DB"),
                List.of("OrderNotFoundException"),
                "GET", "/api/orders/{id}", 50);

        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("co.fanki.order.OrderController",
                "OrderController.java");
        graph.addNode("co.fanki.order.OrderDto", "OrderDto.java");
        graph.markAsEntryPoint("co.fanki.order.OrderController");
        graph.addMethodParameter("co.fanki.order.OrderController",
                "createOrder", 0, "co.fanki.order.OrderDto");

        expect(projectRepository.findByName("order-service"))
                .andReturn(Optional.of(project));

        expect(graphCache.getGraph(project.id()))
                .andReturn(graph);

        expect(sourceClassRepository.findByFullClassName(
                "co.fanki.order.OrderController"))
                .andReturn(Optional.of(controller));

        expect(sourceMethodRepository.findByClassId(controller.id()))
                .andReturn(List.of(createOrder, getOrder));

        expect(sourceClassRepository.findByFullClassName(
                "co.fanki.order.OrderDto"))
                .andReturn(Optional.of(orderDto));

        replay(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);

        final ServiceApi result =
                service.getServiceApi("order-service");

        assertTrue(result.found());
        assertEquals("order-service", result.projectName());
        assertEquals(1, result.controllers().size());

        final var ctrl = result.controllers().get(0);
        assertEquals("co.fanki.order.OrderController", ctrl.className());
        assertEquals(2, ctrl.endpoints().size());

        final var postEndpoint = ctrl.endpoints().stream()
                .filter(e -> "POST".equals(e.httpMethod()))
                .findFirst().orElseThrow();
        assertEquals("createOrder", postEndpoint.methodName());
        assertEquals("/api/orders", postEndpoint.httpPath());
        assertEquals(1, postEndpoint.parameters().size());
        assertEquals("co.fanki.order.OrderDto",
                postEndpoint.parameters().get(0).className());
        assertEquals("DTO",
                postEndpoint.parameters().get(0).classType());

        final var getEndpoint = ctrl.endpoints().stream()
                .filter(e -> "GET".equals(e.httpMethod()))
                .findFirst().orElseThrow();
        assertEquals("getOrder", getEndpoint.methodName());
        assertTrue(getEndpoint.parameters().isEmpty());

        verify(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);
    }

    @Test
    void whenGettingServiceApi_givenUnknownProject_shouldReturnNotFound() {
        expect(projectRepository.findByName("unknown-service"))
                .andReturn(Optional.empty());

        replay(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);

        final ServiceApi result =
                service.getServiceApi("unknown-service");

        assertFalse(result.found());
        assertNotNull(result.message());

        verify(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);
    }

    @Test
    void whenGettingServiceApi_givenProjectWithNoGraph_shouldReturnNotFound() {
        final Project project = Project.create(
                "orphan-service",
                RepositoryUrl.of("https://github.com/fanki/orphan.git"),
                "main");

        expect(projectRepository.findByName("orphan-service"))
                .andReturn(Optional.of(project));

        expect(graphCache.getGraph(project.id()))
                .andReturn(null);

        replay(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);

        final ServiceApi result =
                service.getServiceApi("orphan-service");

        assertFalse(result.found());
        assertNotNull(result.message());
        assertTrue(result.controllers().isEmpty());

        verify(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);
    }

    @Test
    void whenSearchingProject_givenExistingProjectAndMatchingQuery_shouldReturnMatches() {
        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("com.example.OrderService", "OrderService.java");
        graph.addNode("com.example.OrderController",
                "OrderController.java");
        graph.addNode("com.example.UserService", "UserService.java");
        graph.markAsEntryPoint("com.example.OrderController");

        final SourceClass orderService = SourceClass.create(
                "project-1", "com.example.OrderService",
                ClassType.SERVICE, "Order processing",
                "OrderService.java", "abc123");

        final SourceClass orderController = SourceClass.create(
                "project-1", "com.example.OrderController",
                ClassType.CONTROLLER, "Order REST API",
                "OrderController.java", "abc123");

        expect(graphCache.getGraphByProjectName("my-project"))
                .andReturn(graph);

        expect(sourceClassRepository.findByFullClassName(
                "com.example.OrderService"))
                .andReturn(Optional.of(orderService));

        expect(sourceClassRepository.findByFullClassName(
                "com.example.OrderController"))
                .andReturn(Optional.of(orderController));

        replay(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);

        final ProjectSearchResult result =
                service.searchProject("my-project", "Order");

        assertTrue(result.found());
        assertEquals("my-project", result.projectName());
        assertEquals("Order", result.query());
        assertEquals(2, result.matches().size());
        assertEquals(3, result.totalClassesInProject());

        verify(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);
    }

    @Test
    void whenSearchingProject_givenUnknownProject_shouldReturnNotFound() {
        expect(graphCache.getGraphByProjectName("unknown"))
                .andReturn(null);

        replay(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);

        final ProjectSearchResult result =
                service.searchProject("unknown", "Order");

        assertFalse(result.found());
        assertNotNull(result.message());

        verify(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);
    }

    @Test
    void whenSearchingProject_givenNoMatchingQuery_shouldReturnEmptyMatches() {
        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("com.example.OrderService", "OrderService.java");

        expect(graphCache.getGraphByProjectName("my-project"))
                .andReturn(graph);

        replay(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);

        final ProjectSearchResult result =
                service.searchProject("my-project", "ZzzNotExist");

        assertTrue(result.found());
        assertTrue(result.matches().isEmpty());
        assertEquals(1, result.totalClassesInProject());

        verify(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);
    }

    @Test
    void whenGettingClassContext_givenProjectName_shouldScopeToProject() {
        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("com.example.OrderService", "OrderService.java");
        graph.addNode("com.example.OrderRepository",
                "OrderRepository.java");
        graph.addDependency("com.example.OrderService",
                "com.example.OrderRepository");

        final SourceClass orderService = SourceClass.create(
                "project-1", "com.example.OrderService",
                ClassType.SERVICE, "Order processing",
                "OrderService.java", "abc123");

        final SourceMethod findOrder = SourceMethod.create(
                orderService.id(), "findById", "Finds order by ID",
                List.of("Query DB"), List.of(), null, null, 30);

        final Project project = Project.create(
                "my-project",
                RepositoryUrl.of("https://github.com/fanki/orders.git"),
                "main");

        expect(graphCache.getGraphByProjectName("my-project"))
                .andReturn(graph);

        expect(graphCache.getProjectIdByName("my-project"))
                .andReturn("project-1");

        expect(sourceClassRepository.findByProjectIdAndFullClassName(
                "project-1", "com.example.OrderService"))
                .andReturn(Optional.of(orderService));

        expect(sourceMethodRepository.findByClassId(orderService.id()))
                .andReturn(List.of(findOrder));

        expect(projectRepository.findById(orderService.projectId()))
                .andReturn(Optional.of(project));

        replay(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);

        final ClassContext context = service.getClassContext(
                "com.example.OrderService", "my-project");

        assertTrue(context.found());
        assertEquals("com.example.OrderService", context.className());
        assertEquals("SERVICE", context.classType());
        assertEquals(1, context.methods().size());
        assertNotNull(context.graphInfo());
        assertEquals(List.of("com.example.OrderRepository"),
                context.graphInfo().dependencies());

        verify(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);
    }

    @Test
    void whenGettingClassContext_givenProjectNameAndClassNotInGraph_shouldReturnNotFound() {
        final ProjectGraph graph = new ProjectGraph();
        graph.addNode("com.example.UserService", "UserService.java");

        expect(graphCache.getGraphByProjectName("my-project"))
                .andReturn(graph);

        expect(projectRepository.findAll()).andReturn(List.of());

        replay(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);

        final ClassContext context = service.getClassContext(
                "com.example.OrderService", "my-project");

        assertFalse(context.found());
        assertEquals("com.example.OrderService", context.className());

        verify(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);
    }
}
