package co.fanki.domainmcp.analysis.application;

import co.fanki.domainmcp.analysis.application.CodeContextService.ClassContext;
import co.fanki.domainmcp.analysis.application.CodeContextService.MethodContext;
import co.fanki.domainmcp.analysis.application.CodeContextService.StackFrame;
import co.fanki.domainmcp.analysis.application.CodeContextService.StackTraceContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for code context retrieval operations.
 *
 * <p>Provides the get_class_context, get_method_context, and
 * get_stack_trace_context MCP tool functionality.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@RestController
@RequestMapping("/api/context")
@Tag(name = "Code Context", description = "Retrieve context for classes, methods, and stack traces")
public class ContextController {

    private static final Logger LOG = LoggerFactory.getLogger(
            ContextController.class);

    private final CodeContextService codeContextService;

    /**
     * Creates a new ContextController.
     *
     * @param theCodeContextService the code context service
     */
    public ContextController(final CodeContextService theCodeContextService) {
        this.codeContextService = theCodeContextService;
    }

    /**
     * Gets context for a class by its fully qualified name.
     *
     * <p>This endpoint implements the get_class_context MCP tool.</p>
     *
     * @param className the fully qualified class name
     * @return the class context
     */
    @Operation(
            summary = "Get class context by path",
            description = "Retrieves context for a class including description, type, and methods"
    )
    @GetMapping("/class/{className}")
    public ResponseEntity<ClassContext> getClassContext(
            @Parameter(description = "Fully qualified class name", example = "co.fanki.user.UserService")
            @PathVariable final String className) {

        LOG.debug("Getting class context for: {}", className);

        final ClassContext context = codeContextService.getClassContext(className);
        return ResponseEntity.ok(context);
    }

    /**
     * Gets context for a class using the full class name as a path.
     *
     * <p>This variant handles class names with dots by using double asterisk
     * path matching. The class name is passed as a query parameter.</p>
     *
     * @param className the fully qualified class name
     * @return the class context
     */
    @Operation(
            summary = "Get class context",
            description = "Retrieves context for a class including description, type, and methods. " +
                    "Use this endpoint when the class name contains dots."
    )
    @GetMapping("/class")
    public ResponseEntity<ClassContext> getClassContextByParam(
            @Parameter(description = "Fully qualified class name", example = "co.fanki.user.UserService")
            @RequestParam final String className) {

        LOG.debug("Getting class context for: {}", className);

        final ClassContext context = codeContextService.getClassContext(className);
        return ResponseEntity.ok(context);
    }

    /**
     * Gets context for a specific method.
     *
     * <p>This endpoint implements the get_method_context MCP tool.</p>
     *
     * @param className the fully qualified class name
     * @param methodName the method name
     * @return the method context
     */
    @Operation(
            summary = "Get method context",
            description = "Retrieves detailed context for a specific method including business logic, " +
                    "dependencies, exceptions, and HTTP endpoint if applicable"
    )
    @GetMapping("/method")
    public ResponseEntity<MethodContext> getMethodContext(
            @Parameter(description = "Fully qualified class name", example = "co.fanki.user.UserService")
            @RequestParam final String className,
            @Parameter(description = "Method name", example = "createUser")
            @RequestParam final String methodName) {

        LOG.debug("Getting method context for: {}.{}", className, methodName);

        final MethodContext context = codeContextService.getMethodContext(
                className, methodName);
        return ResponseEntity.ok(context);
    }

    /**
     * Gets context for a stack trace (multiple class/method pairs).
     *
     * <p>This endpoint implements the get_stack_trace_context MCP tool.</p>
     *
     * @param request the stack trace request
     * @return the stack trace context
     */
    @Operation(
            summary = "Get stack trace context",
            description = "Retrieves context for multiple stack frames, useful for correlating " +
                    "Datadog error stack traces with source code context"
    )
    @PostMapping("/stack-trace")
    public ResponseEntity<StackTraceContext> getStackTraceContext(
            @RequestBody final StackTraceRequest request) {

        LOG.debug("Getting stack trace context for {} frames",
                request.stackTrace().size());

        final List<StackFrame> frames = request.stackTrace().stream()
                .map(f -> new StackFrame(f.className(), f.methodName(), f.lineNumber()))
                .toList();

        final StackTraceContext context = codeContextService
                .getStackTraceContext(frames);
        return ResponseEntity.ok(context);
    }

    /**
     * Request for stack trace context.
     */
    public record StackTraceRequest(
            List<StackFrameRequest> stackTrace
    ) {}

    /**
     * A frame in the stack trace request.
     */
    public record StackFrameRequest(
            String className,
            String methodName,
            Integer lineNumber
    ) {}

}
