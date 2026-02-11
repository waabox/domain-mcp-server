package co.fanki.domainmcp.analysis.application;

import co.fanki.domainmcp.analysis.application.CodeContextService.ClassContext;
import co.fanki.domainmcp.analysis.application.CodeContextService.ExecutionPathEntry;
import co.fanki.domainmcp.analysis.application.CodeContextService.MethodContext;
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
                "UserService.java");

        final SourceMethod findUser = SourceMethod.create(
                userClass.id(), "findById", "Finds user by ID",
                List.of("Query DB"), List.of("UserRepository"),
                List.of(), null, null, 30);

        final SourceMethod createUser = SourceMethod.create(
                userClass.id(), "createUser", "Creates a new user",
                List.of("Validate", "Save"), List.of("UserRepository"),
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
                "OrderService.java");

        final SourceMethod placeOrder = SourceMethod.create(
                orderClass.id(), "placeOrder",
                "Places a new order for a customer",
                List.of("Validate items", "Calculate total", "Save order"),
                List.of("OrderRepository", "InventoryService"),
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

        replay(projectRepository, sourceClassRepository,
                sourceMethodRepository, graphCache);

        final MethodContext context = service.getMethodContext(
                "co.fanki.order.OrderService", "placeOrder");

        assertTrue(context.found());
        assertEquals("co.fanki.order.OrderService", context.className());
        assertEquals("placeOrder", context.methodName());
        assertEquals("POST /api/orders", context.httpEndpoint());
        assertEquals(3, context.businessLogic().size());
        assertEquals(2, context.dependencies().size());

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
                "OrderService.java");

        expect(sourceClassRepository.findByFullClassName(
                "co.fanki.order.OrderService"))
                .andReturn(Optional.of(orderClass));

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
                "PaymentService.java");

        final SourceMethod processMethod = SourceMethod.create(
                paymentClass.id(),
                "processPayment",
                "Processes a payment",
                List.of("Validate", "Charge", "Save"),
                List.of("CardGateway"),
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
                "OrderController.java");

        final SourceMethod createOrder = SourceMethod.create(
                controllerClass.id(),
                "createOrder",
                "Creates a new order",
                List.of("Validates input", "Delegates to service"),
                List.of("OrderService"),
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
                "OrderService.java");

        final SourceMethod processOrder = SourceMethod.create(
                serviceClass.id(),
                "processOrder",
                "Processes the order",
                List.of("Calculate total", "Save"),
                List.of("OrderRepository"),
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
}
