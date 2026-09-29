/** Pure rendering for the legacy diagnostic report; never a metric reducer or oracle. */
function cell(value:string){return value.replaceAll("&","&amp;").replaceAll("<","&lt;").replaceAll(">","&gt;").replaceAll("|","&#124;").replace(/[\r\n]+/g," ");}

export function formatLegacyNumber(value:unknown,scale=1,decimals=2):string {
  if(!Number.isFinite(scale)||scale<=0||!Number.isInteger(decimals)||decimals<0||decimals>20)
    throw new Error("invalid numeric presentation scale or precision");
  let candidate:unknown=value;
  if(value!==null&&typeof value==="object"){
    const evidence=value as {status?:string;value?:unknown;reason?:unknown};
    if(evidence.status!=="measured"&&evidence.status!=="verified"){
      const disposition=evidence.status==="contradicted"?"contradicted":evidence.status==="not_applicable"?"not applicable":"unavailable";
      return disposition+" ("+cell(typeof evidence.reason==="string"&&evidence.reason?evidence.reason:"numeric evidence not measured")+")";
    }
    // observedDifference is descriptive raw evidence, never a fallback for a
    // missing or explicitly unavailable causally scoped value.
    candidate=evidence.value;
  }
  if(typeof candidate!=="number"||!Number.isFinite(candidate)||!Number.isFinite(candidate/scale))
    return "unavailable (missing or non-finite numeric evidence)";
  return (candidate/scale).toFixed(decimals);
}
const fmt=formatLegacyNumber;

/** /proc reports RSS in KiB. A missing/malformed field stays null before JSON serialization. */
export function parseLegacyRssKb(status:string):number|null {
  const match=/^VmRSS:[ \t]+(\d+)[ \t]+kB[ \t]*$/m.exec(status);
  if(!match)return null;
  const value=Number(match[1]);
  return Number.isSafeInteger(value)?value:null;
}

export function markdown(report:any){
  const m=report.machine;
  const sessions=m.residentDaemon.sessions;
  const cold=m.machineCold,restart=m.daemonRestart,incremental=m.incrementalReconcile;
  const lines=[
    "## JVMD daemon lifecycle",
    "",
    "Legacy direct-JVM diagnostic trace. Unavailable observations are not zero work; this report does not enable a comparative performance claim.",
    "",
    "### Machine-global index",
    "",
    "| Metric | machine cold | daemon restart | incremental reconcile | resident daemon |",
    "| --- | ---: | ---: | ---: | ---: |",
    "| Process → machine index ready | "+fmt(cold.processToMachineIndexReadyMs)+" | "+fmt(restart.processToMachineIndexReadyMs)+" | already running | already paid |",
    "| Index reconciliation | "+fmt(cold.machineIndexReconcileMs)+" | "+fmt(restart.machineIndexReconcileMs)+" | "+fmt(incremental.machineIndexReconcileMs)+" | unavailable |",
    "| Artifacts discovered / current | "+fmt(cold.index.artifactsDiscovered,1,0)+" | "+fmt(restart.index.artifactsDiscovered,1,0)+" | "+fmt(incremental.indexAfter.artifactsDiscovered,1,0)+" | "+fmt(m.residentDaemon.repositoryAfterSessions.jarArtifacts,1,0)+" |",
    "| Artifacts reused during reconcile | "+fmt(cold.index.artifactsReused,1,0)+" | "+fmt(restart.index.artifactsReused,1,0)+" | "+fmt(incremental.indexDelta.reused,1,0)+" | - |",
    "| Artifacts hashed during reconcile | "+fmt(cold.index.artifactsHashed,1,0)+" | "+fmt(restart.index.artifactsHashed,1,0)+" | "+fmt(incremental.indexDelta.hashed,1,0)+" | - |",
    "| Indexed publications during reconcile | "+fmt(cold.index.indexedPublications,1,0)+" | "+fmt(restart.index.indexedPublications,1,0)+" | "+fmt(incremental.indexDelta.indexedPublications,1,0)+" | - |",
    "| Discovery ms | "+fmt(cold.index.timings.discoveryMs)+" | "+fmt(restart.index.timings.discoveryMs)+" | "+fmt(incremental.indexDelta.discoveryMs)+" | - |",
    "| Hash ms | "+fmt(cold.index.timings.hashMs)+" | "+fmt(restart.index.timings.hashMs)+" | "+fmt(incremental.indexDelta.hashMs)+" | - |",
    "| Parse ms | "+fmt(cold.index.timings.parseMs)+" | "+fmt(restart.index.timings.parseMs)+" | "+fmt(incremental.indexDelta.parseMs)+" | - |",
    "| Storage worker-time ms | "+fmt(cold.index.timings.storageMs)+" | "+fmt(restart.index.timings.storageMs)+" | "+fmt(incremental.indexDelta.storageMs)+" | - |",
    "| Docs/source phase ms | "+fmt(cold.index.timings.docsMs)+" | "+fmt(restart.index.timings.docsMs)+" | "+fmt(incremental.indexDelta.docsMs)+" | unavailable |",
    "| Link ms | "+fmt(cold.index.timings.linkMs)+" | "+fmt(restart.index.timings.linkMs)+" | "+fmt(incremental.indexDelta.linkMs)+" | - |",
    "| Source/doc artifacts updated | unavailable | unavailable | unavailable | n/a |",
    "| Artifact added → next reconciliation observed (scanner cadence included) | - | - | "+fmt(incremental.artifactAddedToReadyMs)+" | - |",
    "",
    "The incremental wall time includes waiting for the daemon's periodic repository scanner; the separate Index reconciliation row is the scan work itself. A separate source-JAR update count is unavailable from current daemon status; aggregate reuse/hash counters and docs/source phase time are retained instead. Parse/storage timings are accumulated worker phase time and may exceed wall-clock scan time; do not sum phase rows into elapsed time.",
    "",
    "### Resident daemon workspace sessions",
    "",
    "| Metric | first workspace open | disposed workspace reopen |",
    "| --- | ---: | ---: |",
    "| Session open | "+fmt(sessions[0].sessionOpenMs)+" | "+fmt(sessions[1].sessionOpenMs)+" |",
    "| Workspace resolution evidence | "+sessions[0].workspaceResolution.status+" | "+sessions[1].workspaceResolution.status+" |",
    "| Resolver cold timing | "+fmt(sessions[0].workspaceResolution.reportedColdTimingMs)+" | "+fmt(sessions[1].workspaceResolution.reportedColdTimingMs)+" |",
    "| Project-model fast hits | "+fmt(sessions[0].workspaceResolution.projectModelFastHits,1,0)+" | "+fmt(sessions[1].workspaceResolution.projectModelFastHits,1,0)+" |",
    "| Workspace-index membership load | "+fmt(sessions[0].workspaceIndex.loadMs)+" | "+fmt(sessions[1].workspaceIndex.loadMs)+" |",
    "| Workspace-index ready | unavailable | unavailable |",
    "| Dependency-artifact hashes during session open | "+fmt(sessions[0].workspaceIndex.indexServiceWorkDuringSessionOpen.hashed,1,0)+" | "+fmt(sessions[1].workspaceIndex.indexServiceWorkDuringSessionOpen.hashed,1,0)+" |",
    "| Dependency-artifact reuses during session open | "+fmt(sessions[0].workspaceIndex.indexServiceWorkDuringSessionOpen.reused,1,0)+" | "+fmt(sessions[1].workspaceIndex.indexServiceWorkDuringSessionOpen.reused,1,0)+" |",
    "| IndexService publications during session open (dependency/local split unavailable) | "+fmt(sessions[0].workspaceIndex.indexServiceWorkDuringSessionOpen.indexedPublications,1,0)+" | "+fmt(sessions[1].workspaceIndex.indexServiceWorkDuringSessionOpen.indexedPublications,1,0)+" |",
    "| IndexService parse worker-time during session open ms (dependency/local split unavailable) | "+fmt(sessions[0].workspaceIndex.indexServiceWorkDuringSessionOpen.parseMs)+" | "+fmt(sessions[1].workspaceIndex.indexServiceWorkDuringSessionOpen.parseMs)+" |",
    "| IndexService storage worker-time during session open ms (dependency/local split unavailable) | "+fmt(sessions[0].workspaceIndex.indexServiceWorkDuringSessionOpen.storageMs)+" | "+fmt(sessions[1].workspaceIndex.indexServiceWorkDuringSessionOpen.storageMs)+" |",
    "| Document mutation admission | "+fmt(sessions[0].admission.wallMs)+" | "+fmt(sessions[1].admission.wallMs)+" |",
    "| Session open → first correct definition | "+fmt(sessions[0].sessionOpenToFirstCorrectResultMs)+" | "+fmt(sessions[1].sessionOpenToFirstCorrectResultMs)+" |",
    "| Definition first use | "+fmt(sessions[0].definition.firstUse.latencyMs)+" | "+fmt(sessions[1].definition.firstUse.latencyMs)+" |",
    "| Definition steady p50 | "+fmt(sessions[0].definition.steadyStats.p50Ms)+" | "+fmt(sessions[1].definition.steadyStats.p50Ms)+" |",
    "| Definition steady p95 | "+fmt(sessions[0].definition.steadyStats.p95Ms)+" | "+fmt(sessions[1].definition.steadyStats.p95Ms)+" |",
    "",
    "Workspace-local index completion remains unavailable because local refresh is asynchronous. Shared IndexService publication counters are retained as evidence but are not relabelled as machine-global when dependency/local attribution is unavailable.",
    "",
    "### Document admission attribution",
    "",
    "| Evidence | first workspace open | disposed workspace reopen |",
    "| --- | ---: | ---: |",
    "| MavenProject.java open mutation ms | "+fmt(sessions[0].admission.documents[0].durationMs)+" | "+fmt(sessions[1].admission.documents[0].durationMs)+" |",
    "| MavenProject.java diagnostic admission | "+sessions[0].admission.documents[0].diagnostic.status+" | "+sessions[1].admission.documents[0].diagnostic.status+" |",
    "| DefaultMavenProjectHelper.java open mutation ms | "+fmt(sessions[0].admission.documents[1].durationMs)+" | "+fmt(sessions[1].admission.documents[1].durationMs)+" |",
    "| DefaultMavenProjectHelper.java open diagnostic admission | "+sessions[0].admission.documents[1].diagnostic.status+" | "+sessions[1].admission.documents[1].diagnostic.status+" |",
    "| DefaultMavenProjectHelper.java change mutation ms | "+fmt(sessions[0].admission.documents[2].durationMs)+" | "+fmt(sessions[1].admission.documents[2].durationMs)+" |",
    "| DefaultMavenProjectHelper.java change diagnostic admission | "+sessions[0].admission.documents[2].diagnostic.status+" | "+sessions[1].admission.documents[2].diagnostic.status+" |",
    "| Mutation RPC ms | "+fmt(sessions[0].admission.topLevelDaemon.documentMutationRpcMs)+" | "+fmt(sessions[1].admission.topLevelDaemon.documentMutationRpcMs)+" |",
    "| Diagnostics RPC ms | "+fmt(sessions[0].admission.topLevelDaemon.diagnosticsRpcMs)+" | "+fmt(sessions[1].admission.topLevelDaemon.diagnosticsRpcMs)+" |",
    "| Adapter/other remainder ms | "+fmt(sessions[0].admission.topLevelDaemon.otherOrAdapterMs)+" | "+fmt(sessions[1].admission.topLevelDaemon.otherOrAdapterMs)+" |",
    "| Resolver cold calls | "+fmt(sessions[0].admission.resolverDelta.resolveCalls,1,0)+" | "+fmt(sessions[1].admission.resolverDelta.resolveCalls,1,0)+" |",
    "| Resolver fast hits | "+fmt(sessions[0].admission.resolverDelta.projectModelFastHits,1,0)+" | "+fmt(sessions[1].admission.resolverDelta.projectModelFastHits,1,0)+" |",
    "| Workspace-index load delta ms | "+fmt(sessions[0].admission.workspaceIndexDelta.workspaceIndexLoadMs)+" | "+fmt(sessions[1].admission.workspaceIndexDelta.workspaceIndexLoadMs)+" |",
    "| Source bytes hashed delta (shared snapshots; not causal work) | "+fmt(sessions[0].admission.sourceObservation.bytes_hashed)+" | "+fmt(sessions[1].admission.sourceObservation.bytes_hashed)+" |",
    "| Compiler query ms delta | "+fmt(sessions[0].admission.compilerAndSemanticEvidence.query_ms)+" | "+fmt(sessions[1].admission.compilerAndSemanticEvidence.query_ms)+" |",
    "| Compiler configure ms delta | "+fmt(sessions[0].admission.compilerAndSemanticEvidence.configure_ms)+" | "+fmt(sessions[1].admission.compilerAndSemanticEvidence.configure_ms)+" |",
    "| Semantic fact mutations | "+fmt(sessions[0].admission.compilerAndSemanticEvidence.semantic_fact_mutations)+" | "+fmt(sessions[1].admission.compilerAndSemanticEvidence.semantic_fact_mutations)+" |",
    "| javac parse / enter / attribute split | unavailable | unavailable |",
    "",
    "Compiler/semantic status differences remain unavailable as work counters without stable owner epochs and complete contributor coverage. They are not added to RPC elapsed time.",
    "",
    "### Resident memory",
    "",
    "| State | first workspace open | disposed workspace reopen |",
    "| --- | ---: | ---: |",
    "| Machine-ready daemon baseline MiB | "+fmt(sessions[0].memory.daemonMachineReadyKb,1024)+" | "+fmt(sessions[1].memory.daemonMachineReadyKb,1024)+" |",
    "| Daemon immediately before session MiB | "+fmt(sessions[0].memory.daemonBaselineBeforeSessionKb,1024)+" | "+fmt(sessions[1].memory.daemonBaselineBeforeSessionKb,1024)+" |",
    "| After session open MiB | "+fmt(sessions[0].memory.sessionOpenedKb,1024)+" | "+fmt(sessions[1].memory.sessionOpenedKb,1024)+" |",
    "| Session-open RSS increment MiB | "+fmt(sessions[0].memory.sessionOpenIncrementKb,1024)+" | "+fmt(sessions[1].memory.sessionOpenIncrementKb,1024)+" |",
    "| After steady MiB | "+fmt(sessions[0].memory.afterSteadyKb,1024)+" | "+fmt(sessions[1].memory.afterSteadyKb,1024)+" |",
    "| After session close MiB | "+fmt(sessions[0].memory.afterSessionCloseKb,1024)+" | "+fmt(sessions[1].memory.afterSessionCloseKb,1024)+" |",
    "",
    "Repository artifacts before resident sessions: **"+fmt(m.residentDaemon.repositoryBeforeSessions.jarArtifacts,1,0)+"**; after sessions/background reconciliation: **"+fmt(m.residentDaemon.repositoryAfterSessions.jarArtifacts,1,0)+"**.",
    "",
    "Workspace-local source publisher after resident sessions: **"+fmt(m.residentDaemon.localIndexPublisherAfterSessions?.failures,1,0)+" failure(s)**"
      +(m.residentDaemon.localIndexPublisherAfterSessions?.last_failure?" — "+m.residentDaemon.localIndexPublisherAfterSessions.last_failure:"")+".",
    "",
    "Local-change global dependency reindex observed in its measurement window: **"+(typeof sessions[1].localChange?.globalDependencyReindexObserved==="boolean"?String(sessions[1].localChange.globalDependencyReindexObserved):"unavailable")+"**.",
    "",
  ];
  return lines.join("\n");
}
