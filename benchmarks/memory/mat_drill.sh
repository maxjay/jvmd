#!/usr/bin/env bash
# One level below the top dominators of a heap dump: what the large JVMD owners retain, by object.
#   mat_drill.sh DUMP.hprof OUT_DIR
set -euo pipefail
: "${MAT_HOME:?MAT_HOME must point at the MemoryAnalyzer directory}"
mkdir -p "$2"; dump=$(readlink -f "$1"); out=$(readlink -f "$2"); base="${dump%.hprof}"
unset JAVA_TOOL_OPTIONS
cd "$(dirname "$dump")" # MAT writes an Eclipse workspace into the working directory
query() {
  "$MAT_HOME/ParseHeapDump.sh" "$dump" "-command=$2" -format=csv -unzip org.eclipse.mat.api:query >/dev/null 2>&1 || true
  local page; page=$(ls -t "${base}_Query/pages/"*.csv 2>/dev/null | head -1 || true)
  if [[ -n "$page" ]]; then cp "$page" "$out/$1.csv"; fi
  rm -rf "${base}_Query" "${base}_Query.zip"
}
dominated() { # label, OQL selecting the owner objects
  query "$1" "oql \"SELECT classof(o).@name AS class, toString(o) AS object, o.@retainedHeapSize AS retained FROM OBJECTS (SELECT OBJECTS dominators(x) FROM OBJECTS ($2) x) o WHERE o.@retainedHeapSize > 262144\""
}
dominated session_thread "SELECT OBJECTS t FROM java.lang.Thread t WHERE toString(t.name).startsWith(\\\"jvmd-session\\\")"
dominated module_actor_threads "SELECT OBJECTS t FROM java.lang.Thread t WHERE toString(t.name).startsWith(\\\"jvmd-module\\\")"
dominated analyzer "SELECT OBJECTS a FROM dev.jvmd.analyzer.Analyzer a"
dominated session "SELECT OBJECTS s FROM dev.jvmd.core.Session s"
dominated application "SELECT OBJECTS a FROM dev.jvmd.dist.Application a"
dominated index_service "SELECT OBJECTS i FROM dev.jvmd.index.IndexService i"
dominated file_state_registry "SELECT OBJECTS f FROM dev.jvmd.core.FileStateRegistry f"
dominated compiler_outcomes "SELECT OBJECTS c FROM dev.jvmd.analyzer.CompilerPool\$Outcome c"
query zipfs "oql \"SELECT toString(z.zfpath) AS path, z.@retainedHeapSize AS retained FROM jdk.nio.zipfs.ZipFileSystem z\""
query outcomes "oql \"SELECT c.@retainedHeapSize AS retained, toString(c) AS outcome FROM dev.jvmd.analyzer.CompilerPool\$Outcome c\""
for c in dev.jvmd.index.ResidentSemanticState dev.jvmd.index.SemanticUnitState dev.jvmd.index.SemanticFact dev.jvmd.index.DocumentSemanticSnapshot \
         dev.jvmd.index.QueryProof dev.jvmd.index.ClasspathSequence dev.jvmd.index.WorkspaceSemanticIdentity dev.jvmd.core.LiveSourceState \
         dev.jvmd.dist.BindingFacts dev.jvmd.analyzer.CompilerPool jdk.nio.zipfs.ZipFileSystem dev.jvmd.core.FileStateRegistry; do
  query "retained_${c##*.}" "show_retained_set $c"
done
rm -f "${base}".*.index "${base}.threads"
echo "$out"
