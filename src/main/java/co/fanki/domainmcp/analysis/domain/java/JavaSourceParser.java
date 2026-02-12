package co.fanki.domainmcp.analysis.domain.java;

import co.fanki.domainmcp.analysis.domain.ClassType;
import co.fanki.domainmcp.analysis.domain.SourceParser;
import co.fanki.domainmcp.analysis.domain.StaticMethodInfo;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.type.ReferenceType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Java-specific implementation of {@link SourceParser}.
 *
 * <p>Discovers .java source files under src/main/java and uses JavaParser
 * to build an AST for extracting imports, methods, annotations, and
 * parameter types.</p>
 *
 * <p>Entry point patterns:</p>
 * <ul>
 *   <li>{@code @RestController}, {@code @Controller} - HTTP endpoints</li>
 *   <li>{@code @KafkaListener} - Kafka consumers</li>
 *   <li>{@code @Scheduled}, {@code @EventListener} - Background jobs</li>
 *   <li>{@code @SpringBootApplication} - Application bootstrap</li>
 * </ul>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public class JavaSourceParser extends SourceParser {

    private static final Logger LOG = LoggerFactory.getLogger(
            JavaSourceParser.class);

    private static final JavaParser PARSER = new JavaParser(
            new ParserConfiguration()
                    .setLanguageLevel(
                            ParserConfiguration.LanguageLevel.JAVA_21));

    private static final String SOURCE_ROOT = "src/main/java";

    private static final Set<String> ENTRY_POINT_ANNOTATIONS = Set.of(
            "RestController",
            "Controller",
            "KafkaListener",
            "Scheduled",
            "EventListener",
            "SpringBootApplication"
    );

    /** Maps Spring annotations to their corresponding ClassType. */
    private static final Map<String, ClassType> ANNOTATION_CLASS_TYPE_MAP =
            Map.ofEntries(
                    Map.entry("RestController", ClassType.CONTROLLER),
                    Map.entry("Controller", ClassType.CONTROLLER),
                    Map.entry("Service", ClassType.SERVICE),
                    Map.entry("Repository", ClassType.REPOSITORY),
                    Map.entry("Configuration", ClassType.CONFIGURATION),
                    Map.entry("Entity", ClassType.ENTITY),
                    Map.entry("KafkaListener", ClassType.LISTENER),
                    Map.entry("EventListener", ClassType.LISTENER)
            );

    /** Maps annotation names to HTTP methods. */
    private static final Map<String, String> HTTP_METHOD_MAP = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH"
    );

    /** {@inheritDoc} */
    @Override
    public String language() {
        return "java";
    }

    /** {@inheritDoc} */
    @Override
    public String sourceRoot() {
        return SOURCE_ROOT;
    }

    /**
     * Discovers all .java files under src/main/java.
     *
     * @param projectRoot the local project root directory
     * @return list of .java file paths
     * @throws IOException if file discovery fails
     */
    @Override
    protected List<Path> discoverFiles(final Path projectRoot)
            throws IOException {

        final Path sourceDir = projectRoot.resolve(SOURCE_ROOT);

        if (!Files.isDirectory(sourceDir)) {
            LOG.warn("Source root not found: {}", sourceDir);
            return List.of();
        }

        try (Stream<Path> walk = Files.walk(sourceDir)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Converts a .java file path to a fully-qualified class name (FQCN).
     *
     * <p>Strips the source root prefix and .java suffix, then replaces
     * path separators with dots.</p>
     *
     * @param file the .java file path
     * @param sourceRoot the resolved source root path
     * @return the FQCN (e.g., "co.fanki.checkout.Cart")
     */
    @Override
    protected String extractIdentifier(final Path file,
            final Path sourceRoot) {

        final Path relative = sourceRoot.relativize(file);
        String result = relative.toString();

        if (result.endsWith(".java")) {
            result = result.substring(0, result.length() - 5);
        }

        return result.replace('/', '.').replace('\\', '.');
    }

    /**
     * Extracts internal import dependencies from a Java source file.
     *
     * <p>Parses import statements via the AST and filters to only include
     * imports that match known identifiers within the project.</p>
     *
     * @param file the Java source file
     * @param knownIdentifiers all known identifiers in the project
     * @return set of dependency identifiers (FQCNs)
     * @throws IOException if file reading fails
     */
    @Override
    protected Set<String> extractDependencies(final Path file,
            final Set<String> knownIdentifiers) throws IOException {

        final CompilationUnit cu = parseFile(file);
        final Set<String> deps = new HashSet<>();

        for (final ImportDeclaration imp : cu.getImports()) {
            final String imported = resolveImportIdentifier(imp);
            if (imported != null
                    && knownIdentifiers.contains(imported)) {
                deps.add(imported);
            }
        }

        return deps;
    }

    /**
     * Checks if a Java source file is an entry point by scanning for
     * Spring annotations that indicate controllers, listeners, or jobs.
     *
     * @param file the Java source file
     * @return true if the file contains entry point annotations
     * @throws IOException if file reading fails
     */
    @Override
    protected boolean isEntryPoint(final Path file) throws IOException {
        final CompilationUnit cu = parseFile(file);

        for (final TypeDeclaration<?> type : cu.getTypes()) {
            for (final AnnotationExpr ann : type.getAnnotations()) {
                if (ENTRY_POINT_ANNOTATIONS.contains(
                        ann.getNameAsString())) {
                    return true;
                }
            }

            // Check method-level annotations (e.g. @KafkaListener on method)
            for (final MethodDeclaration method : type.getMethods()) {
                for (final AnnotationExpr ann : method.getAnnotations()) {
                    if (ENTRY_POINT_ANNOTATIONS.contains(
                            ann.getNameAsString())) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Infers the {@link ClassType} from Spring annotations on the class.
     *
     * <p>Scans for Spring/Jakarta annotations on the type declaration and
     * on method declarations, mapping them to the corresponding ClassType.
     * The first annotation found (by type-level then method-level) wins.</p>
     *
     * @param file the Java source file to analyze
     * @return the inferred class type
     * @throws IOException if file reading fails
     */
    @Override
    public ClassType inferClassType(final Path file) throws IOException {
        final CompilationUnit cu = parseFile(file);

        for (final TypeDeclaration<?> type : cu.getTypes()) {
            // First pass: class-level annotations
            for (final AnnotationExpr ann : type.getAnnotations()) {
                final ClassType classType = ANNOTATION_CLASS_TYPE_MAP.get(
                        ann.getNameAsString());
                if (classType != null) {
                    return classType;
                }
            }

            // Second pass: method-level annotations that indicate class type
            for (final MethodDeclaration method : type.getMethods()) {
                for (final AnnotationExpr ann : method.getAnnotations()) {
                    final String name = ann.getNameAsString();
                    if ("KafkaListener".equals(name)
                            || "EventListener".equals(name)) {
                        return ClassType.LISTENER;
                    }
                }
            }
        }

        return ClassType.OTHER;
    }

    /**
     * Extracts method declarations from a Java source file with line
     * numbers, HTTP annotations, and throws clauses.
     *
     * <p>Uses the JavaParser AST to visit method and constructor
     * declarations, extracting line numbers, HTTP mapping annotations,
     * and throws clauses.</p>
     *
     * @param file the Java source file to analyze
     * @return list of statically extracted method information
     * @throws IOException if file reading fails
     */
    @Override
    public List<StaticMethodInfo> extractMethods(final Path file)
            throws IOException {

        final CompilationUnit cu = parseFile(file);
        final List<StaticMethodInfo> result = new ArrayList<>();

        for (final TypeDeclaration<?> type : cu.getTypes()) {

            // Extract constructors
            if (type instanceof ClassOrInterfaceDeclaration classDecl) {
                for (final ConstructorDeclaration ctor
                        : classDecl.getConstructors()) {

                    final int lineNumber = ctor.getBegin()
                            .map(pos -> pos.line)
                            .orElse(0);

                    final List<String> exceptions = ctor
                            .getThrownExceptions()
                            .stream()
                            .map(ReferenceType::asString)
                            .toList();

                    result.add(new StaticMethodInfo(
                            ctor.getNameAsString(),
                            lineNumber,
                            null, null,
                            exceptions));
                }
            } else if (type instanceof RecordDeclaration recordDecl) {
                for (final ConstructorDeclaration ctor
                        : recordDecl.getConstructors()) {

                    final int lineNumber = ctor.getBegin()
                            .map(pos -> pos.line)
                            .orElse(0);

                    final List<String> exceptions = ctor
                            .getThrownExceptions()
                            .stream()
                            .map(ReferenceType::asString)
                            .toList();

                    result.add(new StaticMethodInfo(
                            ctor.getNameAsString(),
                            lineNumber,
                            null, null,
                            exceptions));
                }
            }

            // Extract methods
            for (final MethodDeclaration method : type.getMethods()) {
                final int lineNumber = method.getBegin()
                        .map(pos -> pos.line)
                        .orElse(0);

                final String methodName = method.getNameAsString();

                // Extract HTTP annotation info
                String httpMethod = null;
                String httpPath = null;

                for (final AnnotationExpr ann : method.getAnnotations()) {
                    final String annName = ann.getNameAsString();

                    if (HTTP_METHOD_MAP.containsKey(annName)) {
                        httpMethod = HTTP_METHOD_MAP.get(annName);
                        httpPath = extractAnnotationPath(ann);
                        break;
                    }

                    if ("RequestMapping".equals(annName)) {
                        httpMethod = extractRequestMappingMethod(ann);
                        httpPath = extractAnnotationPath(ann);
                        break;
                    }
                }

                // Extract throws clause
                final List<String> exceptions = method
                        .getThrownExceptions()
                        .stream()
                        .map(ReferenceType::asString)
                        .toList();

                result.add(new StaticMethodInfo(
                        methodName, lineNumber,
                        httpMethod, httpPath, exceptions));
            }
        }

        return result;
    }

    /**
     * Extracts method parameters from a Java source file.
     *
     * <p>Uses the AST to parse imports and method declarations, resolving
     * parameter types against known project identifiers.</p>
     *
     * @param file the Java source file
     * @param sourceRoot the resolved source root path
     * @param knownIdentifiers all known identifiers in the project
     * @return map of method name to list of known parameter type identifiers
     * @throws IOException if file reading fails
     */
    @Override
    public Map<String, List<String>> extractMethodParameters(
            final Path file, final Path sourceRoot,
            final Set<String> knownIdentifiers) throws IOException {

        final CompilationUnit cu = parseFile(file);
        final String filePackage = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse(null);

        // Build import map: simple name -> FQCN
        final Map<String, String> importMap = new HashMap<>();
        for (final ImportDeclaration imp : cu.getImports()) {
            if (!imp.isAsterisk()) {
                final String fqcn = imp.isStatic()
                        ? extractStaticImportClass(imp)
                        : imp.getNameAsString();
                if (fqcn != null) {
                    final int lastDot = fqcn.lastIndexOf('.');
                    if (lastDot > 0) {
                        final String simpleName =
                                fqcn.substring(lastDot + 1);
                        importMap.put(simpleName, fqcn);
                    }
                }
            }
        }

        final Map<String, List<String>> result = new HashMap<>();

        for (final TypeDeclaration<?> type : cu.getTypes()) {

            // Process constructors
            if (type instanceof ClassOrInterfaceDeclaration classDecl) {
                for (final ConstructorDeclaration ctor
                        : classDecl.getConstructors()) {
                    processParameters(ctor.getNameAsString(),
                            ctor.getParameters(), importMap,
                            filePackage, knownIdentifiers, result);
                }
            }

            // Process methods
            for (final MethodDeclaration method : type.getMethods()) {
                processParameters(method.getNameAsString(),
                        method.getParameters(), importMap,
                        filePackage, knownIdentifiers, result);
            }
        }

        return result;
    }

    /**
     * Resolves parameter types for a single method/constructor and adds
     * matched parameters to the result map.
     */
    private void processParameters(
            final String name,
            final List<Parameter> parameters,
            final Map<String, String> importMap,
            final String filePackage,
            final Set<String> knownIdentifiers,
            final Map<String, List<String>> result) {

        if (parameters.isEmpty()) {
            return;
        }

        final List<String> matched = new ArrayList<>();

        for (final Parameter param : parameters) {
            final String typeName = extractSimpleTypeName(param);
            if (typeName == null || typeName.isEmpty()) {
                continue;
            }

            final String resolved = resolveType(typeName, importMap,
                    filePackage, knownIdentifiers);
            if (resolved != null) {
                matched.add(resolved);
            }
        }

        if (!matched.isEmpty()) {
            result.put(name, matched);
        }
    }

    /**
     * Extracts the simple type name from a JavaParser Parameter node.
     *
     * <p>Strips generic parameters, array brackets, and varargs to
     * return the raw type name.</p>
     */
    private String extractSimpleTypeName(final Parameter param) {
        String typeName = param.getTypeAsString();

        // Strip generics: List<Foo> -> List
        final int genericIdx = typeName.indexOf('<');
        if (genericIdx > 0) {
            typeName = typeName.substring(0, genericIdx);
        }

        // Strip varargs
        if (typeName.endsWith("...")) {
            typeName = typeName.substring(0, typeName.length() - 3);
        }

        // Strip array brackets
        if (typeName.endsWith("[]")) {
            typeName = typeName.substring(0, typeName.length() - 2);
        }

        return typeName.isBlank() ? null : typeName;
    }

    /**
     * Resolves a simple type name to an FQCN using imports or same-package.
     */
    private String resolveType(final String simpleName,
            final Map<String, String> importMap,
            final String filePackage,
            final Set<String> knownIdentifiers) {

        // Already an FQCN?
        if (knownIdentifiers.contains(simpleName)) {
            return simpleName;
        }

        // Check imports
        final String fromImport = importMap.get(simpleName);
        if (fromImport != null && knownIdentifiers.contains(fromImport)) {
            return fromImport;
        }

        // Check same package
        if (filePackage != null && !filePackage.isEmpty()) {
            final String samePackage = filePackage + "." + simpleName;
            if (knownIdentifiers.contains(samePackage)) {
                return samePackage;
            }
        }

        return null;
    }

    /**
     * Resolves an import declaration to the FQCN it represents.
     *
     * <p>For regular imports, returns the fully qualified name directly.
     * For static imports, extracts the class portion (stripping the
     * member name). Skips wildcard imports.</p>
     */
    private String resolveImportIdentifier(final ImportDeclaration imp) {
        if (imp.isAsterisk()) {
            return null;
        }

        if (imp.isStatic()) {
            return extractStaticImportClass(imp);
        }

        return imp.getNameAsString();
    }

    /**
     * Extracts the class portion from a static import.
     *
     * <p>For {@code import static co.fanki.Constants.VALUE}, returns
     * {@code co.fanki.Constants}.</p>
     */
    private String extractStaticImportClass(final ImportDeclaration imp) {
        final String fullName = imp.getNameAsString();
        final int lastDot = fullName.lastIndexOf('.');
        if (lastDot > 0) {
            return fullName.substring(0, lastDot);
        }
        return null;
    }

    /**
     * Extracts the path value from a mapping annotation.
     *
     * <p>Handles both single-member annotations
     * ({@code @GetMapping("/path")}) and normal annotations
     * ({@code @GetMapping(value = "/path")}).</p>
     */
    private String extractAnnotationPath(final AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr single) {
            return stripQuotes(single.getMemberValue().toString());
        }

        if (ann instanceof NormalAnnotationExpr normal) {
            for (final MemberValuePair pair : normal.getPairs()) {
                if ("value".equals(pair.getNameAsString())
                        || "path".equals(pair.getNameAsString())) {
                    return stripQuotes(pair.getValue().toString());
                }
            }
        }

        return null;
    }

    /**
     * Extracts the HTTP method from a @RequestMapping annotation.
     *
     * <p>Looks for method = RequestMethod.XXX in the annotation pairs.
     * Defaults to GET if no method attribute is found.</p>
     */
    private String extractRequestMappingMethod(final AnnotationExpr ann) {
        if (ann instanceof NormalAnnotationExpr normal) {
            for (final MemberValuePair pair : normal.getPairs()) {
                if ("method".equals(pair.getNameAsString())) {
                    final String value = pair.getValue().toString();
                    // Extract method from RequestMethod.POST, etc.
                    final int dotIdx = value.lastIndexOf('.');
                    if (dotIdx >= 0) {
                        return value.substring(dotIdx + 1);
                    }
                    return value;
                }
            }
        }
        return "GET";
    }

    /**
     * Strips surrounding double quotes from a string value.
     */
    private String stripQuotes(final String value) {
        if (value != null && value.length() >= 2
                && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * Parses a Java source file into a JavaParser CompilationUnit.
     *
     * @param file the Java source file to parse
     * @return the parsed compilation unit
     * @throws IOException if parsing fails
     */
    private CompilationUnit parseFile(final Path file) throws IOException {
        try {
            return PARSER.parse(file)
                    .getResult()
                    .orElseThrow(() -> new IOException(
                            "No parse result for " + file));
        } catch (final IOException e) {
            throw e;
        } catch (final Exception e) {
            LOG.warn("Failed to parse {}: {}", file, e.getMessage());
            throw new IOException("Failed to parse " + file, e);
        }
    }

}
