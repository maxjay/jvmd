import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {formatLegacyNumber as fmt,markdown,parseLegacyRssKb} from "../harness/lifecycleReport.ts";

function fixture(){
  const index={artifactsDiscovered:3,artifactsReused:2,artifactsHashed:1,indexedPublications:1,
    timings:{discoveryMs:1,hashMs:2,parseMs:3,storageMs:4,docsMs:0,linkMs:0}};
  const delta={reused:2,hashed:1,indexedPublications:1,discoveryMs:1,hashMs:2,parseMs:3,storageMs:4,docsMs:0,linkMs:0};
  const unavailable={status:"unavailable",value:null,observedBefore:0,observedAfter:0,observedDifference:0,reason:"actor epoch unavailable"};
  const session={sessionOpenMs:10,sessionOpenToFirstCorrectResultMs:40,
    workspaceResolution:{status:"unavailable",reportedColdTimingMs:null,projectModelFastHits:null},
    workspaceIndex:{loadMs:5,indexServiceWorkDuringSessionOpen:delta},
    admission:{wallMs:20,documents:Array.from({length:3},()=>({durationMs:2,diagnostic:{status:"unavailable"}})),
      topLevelDaemon:{documentMutationRpcMs:null,diagnosticsRpcMs:null,otherOrAdapterMs:null},
      resolverDelta:{resolveCalls:0,projectModelFastHits:1},workspaceIndexDelta:{workspaceIndexLoadMs:0},
      sourceObservation:{bytes_hashed:{status:"measured",value:0,scope:"shared registry snapshot"}},
      compilerAndSemanticEvidence:{query_ms:unavailable,configure_ms:unavailable,semantic_fact_mutations:unavailable}},
    definition:{firstUse:{latencyMs:10},steadyStats:{p50Ms:1,p95Ms:2}},
    memory:{daemonMachineReadyKb:1024,daemonBaselineBeforeSessionKb:2048,sessionOpenedKb:1024,
      sessionOpenIncrementKb:-1024,afterSteadyKb:null,afterSessionCloseKb:null},
    localChange:{globalDependencyReindexObserved:null}};
  return {schema:1,machine:{machineCold:{processToMachineIndexReadyMs:50,machineIndexReconcileMs:30,index},
    daemonRestart:{processToMachineIndexReadyMs:20,machineIndexReconcileMs:10,index},
    incrementalReconcile:{machineIndexReconcileMs:1,indexAfter:index,indexDelta:delta,artifactAddedToReadyMs:60001},
    residentDaemon:{repositoryBeforeSessions:{jarArtifacts:3},repositoryAfterSessions:{jarArtifacts:4},
      localIndexPublisherAfterSessions:{failures:0},sessions:[session,structuredClone(session)]}}};
}

test("numeric evidence preserves observed zero without coercing missing or arbitrary values",()=>{
  assert.equal(fmt(0),"0.00");assert.equal(fmt({status:"measured",value:0}),"0.00");
  assert.equal(fmt({status:"verified",value:1.25}),"1.25");
  for(const value of [null,undefined,NaN,Infinity,-Infinity,"0",false,true,[],{},
    {value:0},{status:"measured",value:null},{status:"measured",value:Infinity},{status:"unknown",value:0}]){
    const result=fmt(value);assert.match(result,/^unavailable/);assert.doesNotMatch(result,/NaN|Infinity/);
  }
});
test("unavailable and contradicted observations cannot fall back to descriptive differences",()=>{
  assert.equal(fmt({status:"unavailable",value:null,observedDifference:0,reason:"owner missing"}),"unavailable (owner missing)");
  assert.equal(fmt({status:"unavailable",value:42,observedDifference:42,reason:"owner missing"}),"unavailable (owner missing)");
  assert.equal(fmt({status:"contradicted",value:0,observedDifference:-3,reason:"reset"}),"contradicted (reset)");
  assert.equal(fmt({status:"not_applicable",value:0,reason:"other profile"}),"not applicable (other profile)");
});
test("presentation scales only finite values and does not clamp signed gauge changes",()=>{
  assert.equal(fmt(2048,1024),"2.00");assert.equal(fmt(-1024,1024),"-1.00");
  assert.equal(fmt(3,1,0),"3");assert.match(fmt(Number.MAX_VALUE,Number.MIN_VALUE),/^unavailable/);
  for(const scale of [0,-1,Infinity,NaN])assert.throws(()=>fmt(1,scale),/invalid numeric presentation/);
  assert.throws(()=>fmt(1,1,100),/invalid numeric presentation/);
});
test("evidence reasons cannot create new Markdown rows or HTML",()=>{
  assert.equal(fmt({status:"unavailable",value:null,reason:"old|new\n<script>"}),"unavailable (old&#124;new &lt;script&gt;)");
});
test("RSS parsing preserves missing, malformed, overflow and unsafe integers as null",()=>{
  assert.equal(parseLegacyRssKb("Name:\tjava\nVmRSS:\t2048 kB\nThreads:\t3\n"),2048);
  assert.equal(parseLegacyRssKb("VmRSS:\t0 kB\n"),0);
  for(const value of ["Name:\tjava\n","VmRSS: 5 MB\n","VmRSS: -3 kB\n","VmRSS: NaN kB\n",
    "VmRSS: 2.5 kB\n","VmRSS: 2048 kB trailing\n","VmRSS: 9007199254740993 kB\n",`VmRSS: ${"9".repeat(400)} kB\n`]){
    const result=parseLegacyRssKb(value);assert.equal(result,null);assert.equal(JSON.stringify({rssKb:result}),'{"rssKb":null}');
  }
});
test("the complete report renders structured evidence without changing raw outcomes or counters",()=>{
  const report=fixture(),before=JSON.stringify(report),rendered=markdown(report);
  assert.equal(JSON.stringify(report),before);
  assert.doesNotMatch(rendered,/NaN|Infinity|\[object Object\]|\| null \|/);
  assert.match(rendered,/Compiler query ms delta \| unavailable \(actor epoch unavailable\)/);
  assert.match(rendered,/Source bytes hashed delta \(shared snapshots; not causal work\) \| 0\.00/);
  assert.match(rendered,/Session-open RSS increment MiB \| -1\.00/);
  assert.match(rendered,/disposed workspace reopen/);
  assert.match(rendered,/global dependency reindex observed.*\*\*unavailable\*\*/);
});
test("malformed stored numbers stay unavailable throughout the report, not just in a helper",()=>{
  const report=fixture();report.machine.machineCold.processToMachineIndexReadyMs=NaN;
  report.machine.machineCold.index.artifactsHashed=Infinity;
  report.machine.residentDaemon.sessions[0].memory.sessionOpenedKb=Infinity;
  report.machine.residentDaemon.sessions[0].admission.sourceObservation.bytes_hashed.value=NaN;
  const result=markdown(report);
  assert.doesNotMatch(result,/NaN|Infinity|\[object Object\]/);
  assert.match(result,/Artifacts hashed during reconcile \| unavailable/);
  assert.match(result,/After session open MiB \| unavailable/);
});
test("the production legacy entry point uses the tested renderer and nullable RSS parser",()=>{
  const source=readFileSync(new URL("../jvmd-machine-lifecycle.ts",import.meta.url),"utf8");
  assert.match(source,/import \{ markdown, parseLegacyRssKb \} from "\.\/harness\/lifecycleReport\.ts"/);
  assert.match(source,/return parseLegacyRssKb\(status\)/);
  assert.match(source,/const summary=markdown\(report\)/);
  assert.doesNotMatch(source,/function fmt\(|function markdown\(/);
});
