package co.fanki.domainmcp.analysis.domain.nodejs;

import java.util.List;

/**
 * Carries file-level analysis data extracted from Babel AST parsing.
 *
 * <p>Contains per-file AST extraction results: methods, raw imports,
 * class type, and entry point status. Cross-file resolution
 * (dependencies, parameter types) is handled in Java using the
 * raw imports and method parameter type names.</p>
 *
 * @param path the relative file path within the project
 * @param classType the inferred class type (CONTROLLER, SERVICE, etc.)
 * @param entryPoint whether this file is an entry point
 * @param methods extracted method declarations
 * @param rawImports raw import declarations for Java-side resolution
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public record FileAnalysisResult(
        String path,
        String classType,
        boolean entryPoint,
        List<MethodAnalysisResult> methods,
        List<RawImport> rawImports
) {
}
