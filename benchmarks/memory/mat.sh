#!/usr/bin/env bash
# Retained-heap analysis of one HPROF dump with Eclipse MAT in batch mode (no GUI).
#   mat.sh DUMP.hprof OUT_DIR
# Writes CSVs: dominators by class, top-level dominator objects, retained size of JVMD owners and
# their fields (OQL), duplicate strings, plus MAT's leak-suspects report (accumulation points and
# shortest GC-root paths).
set -euo pipefail
: "${MAT_HOME:?MAT_HOME must point at the MemoryAnalyzer directory}"
mkdir -p "$2"; dump=$(readlink -f "$1"); out=$(readlink -f "$2")
base="${dump%.hprof}"; name=$(basename "$base")
unset JAVA_TOOL_OPTIONS
cd "$(dirname "$dump")" # MAT writes an Eclipse workspace into the working directory
query() { # query LABEL COMMAND
  "$MAT_HOME/ParseHeapDump.sh" "$dump" "-command=$2" -format=csv -unzip org.eclipse.mat.api:query >/dev/null 2>&1 || true
  local page; page=$(ls -t "${base}_Query/pages/"*.csv 2>/dev/null | head -1 || true)
  if [[ -n "$page" ]]; then cp "$page" "$out/$1.csv"; fi
  rm -rf "${base}_Query" "${base}_Query.zip"
}
query dominators_by_class "dominator_tree -groupby BY_CLASS"
query dominators_top "dominator_tree"
query dominators_by_package "dominator_tree -groupby BY_PACKAGE"
query duplicate_strings "group_by_value java.lang.String"
# Retained size per instance of each JVMD owner class (instances of the class and subclasses).
owners=(dev.jvmd.index.rocks.RocksIndexStore dev.jvmd.index.rocks.RocksIndexStorage dev.jvmd.index.rocks.RocksArtifactRepository
  dev.jvmd.index.rocks.SourceOverlay dev.jvmd.index.rocks.RocksMemory dev.jvmd.index.IndexService dev.jvmd.index.SourceIndexPublisher
  dev.jvmd.core.Session dev.jvmd.core.Sessions dev.jvmd.core.Documents dev.jvmd.core.FileStateRegistry dev.jvmd.core.Metrics
  dev.jvmd.analyzer.Analyzer dev.jvmd.analyzer.CompilerPool dev.jvmd.analyzer.ResidentSemanticState dev.jvmd.analyzer.DiagnosticStore
  dev.jvmd.dist.ModuleAnalyzerRegistry dev.jvmd.dist.WorkspaceBindings dev.jvmd.dist.WorkspaceContextManager dev.jvmd.dist.Application
  dev.jvmd.resolver.MavenResolver dev.jvmd.resolver.WorkspaceOverlay com.sun.tools.javac.util.Context com.sun.tools.javac.api.JavacTaskImpl
  com.sun.tools.javac.util.Names com.sun.tools.javac.code.Symtab com.sun.tools.javac.file.JavacFileManager)
for c in "${owners[@]}"; do
  query "owner_${c##*.}" "oql \"SELECT s.@objectId AS id, toString(s) AS label, s.@usedHeapSize AS shallow, s.@retainedHeapSize AS retained FROM INSTANCEOF ${c} s\""
done
# Field-level retained sizes of the index store's on-heap metadata (the maps named in the report).
query fields_RocksIndexStore "oql \"SELECT s.artifacts.@retainedHeapSize AS artifacts, s.paths.@retainedHeapSize AS paths, s.workspaces.@retainedHeapSize AS workspaces, s.semanticLookups.@retainedHeapSize AS semanticLookups, s.classpathLookups.@retainedHeapSize AS classpathLookups, s.classpathSequences.@retainedHeapSize AS classpathSequences, s.sourceOverlay.@retainedHeapSize AS sourceOverlay, s.@retainedHeapSize AS total FROM dev.jvmd.index.rocks.RocksIndexStore s\""
query fields_Analyzer "oql \"SELECT s.compilerPools.@retainedHeapSize AS compilerPools, s.modules.@retainedHeapSize AS modules, s.@retainedHeapSize AS total FROM dev.jvmd.analyzer.Analyzer s\""
query fields_Session "oql \"SELECT s.id AS id, s.state.@retainedHeapSize AS state, s.@retainedHeapSize AS total FROM dev.jvmd.core.Session s\""
# Leak suspects: accumulation points with their shortest paths to GC roots.
"$MAT_HOME/ParseHeapDump.sh" "$dump" org.eclipse.mat.api:suspects >/dev/null 2>&1 || true
[[ -f "${base}_Leak_Suspects.zip" ]] && mv "${base}_Leak_Suspects.zip" "$out/leak_suspects.zip"
echo "$out"
