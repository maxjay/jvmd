import {type CaseDefinition} from "./ScenarioContext.ts";

/**
 * JVMD's LSP contract, as documented in docs/integration.md ("Supported methods").
 * A case whose endpoint is here is `supported`: a wrong answer is a JVMD bug.
 * Every other case is `roadmap`: something an IDE needs that JVMD does not offer over LSP yet.
 */
export const SUPPORTED_CAPABILITIES=new Set(["hoverProvider","definitionProvider","referencesProvider","renameProvider","renameProvider.prepareProvider",
  "documentSymbolProvider","completionProvider","completionProvider.resolveProvider","signatureHelpProvider","semanticTokensProvider"]);

/** Roadmap endpoints JVMD already answers through its own RPC or MCP tools; exposing them over LSP is the work. */
export const NATIVE:Record<string,string>={
  callHierarchyProvider:"references in/out",
  typeHierarchyProvider:"hierarchy up/down",
  implementationProvider:"hierarchy down",
  documentHighlightProvider:"symbol.occurrences",
  workspaceSymbolProvider:"find",
  "java/searchSymbols":"find",
  "java/extendedDocumentSymbol":"overview",
  "java.project.resolveWorkspaceSymbol":"find",
  "java.getFullyQualifiedName":"symbol.atPosition",
  "java/buildWorkspace":"diagnostics verified",
  "java/buildProjects":"diagnostics verified",
  "java.project.refreshDiagnostics":"diagnostics",
  "java/validateDocument":"diagnostics",
  "java.project.getClasspaths":"deps",
  "java.project.getAll":"status",
};

/** The endpoint a case depends on: its capability, its command, or the java/... request it sends. */
export const endpoint=(c:CaseDefinition)=>c.capability??c.command??c.method;

export function scope(c:CaseDefinition):{scope:"supported"|"roadmap";native?:string}{
  // Lifecycle, document sync and push diagnostics need no capability and are part of every LSP server.
  if(!c.capability&&!c.command&&!c.extension)return {scope:"supported"};
  if(c.capability&&SUPPORTED_CAPABILITIES.has(c.capability))return {scope:"supported"};
  const key=endpoint(c);
  return {scope:"roadmap",...(key&&NATIVE[key]?{native:NATIVE[key]}:{})};
}

/** Workbook APIs that belong to JDTLS's Eclipse or Gradle project model rather than to an editor feature JVMD should offer. */
export const OUT_OF_SCOPE:Record<string,string>={
  "API-022":"JVMD reads project settings from pom.xml and machine settings from config.json",
  "API-023":"JVMD watches pom.xml and re-resolves by itself (see ENV-01/dependency-*, PRJ-02/release-change)",
  "API-024":"JVMD watches pom.xml and re-resolves by itself (see ENV-01/dependency-*, PRJ-02/release-change)",
  "API-026":"Eclipse project import; JVMD opens Maven roots directly",
  "API-027":"Eclipse project import; JVMD opens Maven roots directly",
  "API-028":"Eclipse compiler preferences; compiler settings come from pom.xml",
  "API-029":"Eclipse compiler preferences; compiler settings come from pom.xml",
  "API-032":"Eclipse .classpath editing; Maven owns the classpath",
  "API-033":"Eclipse source-path editing; Maven owns the source roots",
  "API-034":"Eclipse source-path editing; Maven owns the source roots",
  "API-037":"Maven attaches -sources.jar artifacts automatically",
  "API-038":"JVMD compiles with the configured jdk_home and the pom's release",
  "API-039":"JVMD compiles with the configured jdk_home and the pom's release",
  "API-043":"Gradle protobuf generation; JVMD is built for Maven",
  "API-069":"Legacy JDTLS command; the standard LSP type hierarchy (REL-02) covers it",
  "API-070":"Legacy JDTLS command; the standard LSP type hierarchy (REL-02) covers it",
  "API-109":"A notification with no reply; JVMD validates on every change (DIA-01)",
};
export const OUT_OF_SCOPE_FAMILIES:Record<string,string>={"BLD-02":"Gradle protobuf generation; JVMD is built for Maven"};
