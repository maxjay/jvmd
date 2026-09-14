package dev.jvmd.index;

/** Implements 4.4: select the same artifact variant for relationships as for symbol metadata. */
final class EdgeScope {
    static final String CONTEXT="WITH RECURSIVE edge_context(workspace) AS (VALUES (?)) ";
    private EdgeScope() { }
    static String chosen(String symbol,String artifact) {
        return artifact+"=(SELECT v.artifact_id FROM artifact_symbols v JOIN artifacts a ON a.id=v.artifact_id WHERE v.symbol_id="+symbol+
                " AND ((SELECT workspace FROM edge_context) IS NULL OR a.gav LIKE 'jdk:%' OR EXISTS(SELECT 1 FROM workspace_artifacts w WHERE w.workspace_id=(SELECT workspace FROM edge_context) AND w.artifact_id=a.id)) ORDER BY CASE a.kind WHEN 'local' THEN 0 ELSE 1 END,a.id LIMIT 1)";
    }
    static String edge(String alias) { return chosen(alias+".src",alias+".src_artifact")+" AND "+chosen(alias+".dst",alias+".dst_artifact"); }
}
