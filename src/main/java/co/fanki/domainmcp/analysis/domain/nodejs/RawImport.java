package co.fanki.domainmcp.analysis.domain.nodejs;

/**
 * Carries a single import declaration extracted from Babel AST.
 *
 * <p>Contains the local name used in the file, the imported name
 * from the source module, and the raw import source path. Cross-file
 * resolution (mapping the source path to a known project identifier)
 * is performed in Java.</p>
 *
 * @param importedName the exported name from the source module
 * @param localName the local name used in the importing file
 * @param source the raw import path (e.g. "../services/user.service")
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public record RawImport(
        String importedName,
        String localName,
        String source
) {
}
