package co.fanki.domainmcp.analysis.domain.golang;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Top-level result from the Go AST analyzer tool.
 *
 * <p>This record maps directly to the JSON output of the
 * {@code go-analyzer} CLI tool. It contains the Go module path and all
 * analyzed packages with their types, functions, and dependencies.</p>
 *
 * @param module the Go module path from go.mod
 * @param packages the analyzed packages
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoAnalysisResult(
        String module,
        List<GoPackageInfo> packages
) {

    /**
     * Represents an analyzed Go package.
     *
     * @param path the fully-qualified import path
     * @param dir the directory path relative to project root
     * @param files the .go source file basenames
     * @param imports internal import paths
     * @param structs struct type declarations
     * @param interfaces interface type declarations
     * @param functions package-level functions
     * @param isEntryPoint whether this package is an entry point
     * @param classType the inferred architectural role
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GoPackageInfo(
            String path,
            String dir,
            List<String> files,
            List<String> imports,
            List<GoStructInfo> structs,
            List<GoInterfaceInfo> interfaces,
            List<GoFunctionInfo> functions,
            boolean isEntryPoint,
            String classType
    ) {}

    /**
     * Represents a Go struct type declaration.
     *
     * @param name the struct name
     * @param file the source file basename
     * @param line the line number
     * @param fields the struct fields
     * @param methods methods with this struct as receiver
     * @param embeddedTypes names of embedded types
     * @param implements_ interface names this struct implements
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GoStructInfo(
            String name,
            String file,
            int line,
            List<GoFieldInfo> fields,
            List<GoFunctionInfo> methods,
            List<String> embeddedTypes,
            @JsonProperty("implements") List<String> implements_
    ) {}

    /**
     * Represents a Go interface type declaration.
     *
     * @param name the interface name
     * @param file the source file basename
     * @param line the line number
     * @param methods the method signatures
     * @param embeddedInterfaces embedded interface names
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GoInterfaceInfo(
            String name,
            String file,
            int line,
            List<GoMethodSignature> methods,
            List<String> embeddedInterfaces
    ) {}

    /**
     * Represents a method signature in an interface.
     *
     * @param name the method name
     * @param params the parameter list
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GoMethodSignature(
            String name,
            List<GoParamInfo> params
    ) {}

    /**
     * Represents a function or method declaration.
     *
     * @param name the function name
     * @param file the source file basename
     * @param line the line number
     * @param receiver the receiver type (empty for free functions)
     * @param params the parameter list
     * @param returns the return type names
     * @param httpMethod HTTP method if this is a handler
     * @param httpPath route path if this is a handler
     * @param hasPanic whether the body contains panic()
     * @param doc the doc comment (first line)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GoFunctionInfo(
            String name,
            String file,
            int line,
            String receiver,
            List<GoParamInfo> params,
            List<String> returns,
            String httpMethod,
            String httpPath,
            boolean hasPanic,
            String doc
    ) {}

    /**
     * Represents a function parameter.
     *
     * @param name the parameter name
     * @param typeName the parameter type as written in source
     * @param packagePath the package path if the type is internal
     * @param isPointer whether the type is a pointer
     * @param isSlice whether the type is a slice
     * @param isVariadic whether this is a variadic param
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GoParamInfo(
            String name,
            @JsonProperty("type") String typeName,
            @JsonProperty("package") String packagePath,
            boolean isPointer,
            boolean isSlice,
            boolean isVariadic
    ) {}

    /**
     * Represents a struct field.
     *
     * @param name the field name (empty for embedded)
     * @param typeName the field type as written in source
     * @param packagePath the package path if the type is internal
     * @param isExported whether the field is exported
     * @param tag the struct tag
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GoFieldInfo(
            String name,
            @JsonProperty("type") String typeName,
            @JsonProperty("package") String packagePath,
            boolean isExported,
            String tag
    ) {}

}
