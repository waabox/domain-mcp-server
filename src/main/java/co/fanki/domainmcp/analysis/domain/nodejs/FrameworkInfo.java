package co.fanki.domainmcp.analysis.domain.nodejs;

import java.util.Map;

/**
 * Carries framework detection results from the Babel AST analyzer.
 *
 * <p>Contains the detected framework name, its conventional source root,
 * and any detected features (e.g. TypeScript, decorator support).</p>
 *
 * @param name the framework name (e.g. "nestjs", "nextjs", "express")
 * @param sourceRoot the conventional source root directory
 * @param features detected framework features as key-value pairs
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public record FrameworkInfo(
        String name,
        String sourceRoot,
        Map<String, String> features
) {
}
