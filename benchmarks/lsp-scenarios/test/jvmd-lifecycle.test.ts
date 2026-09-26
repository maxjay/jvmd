import { test } from "node:test";
import assert from "node:assert/strict";
import {
  compilerEvidence,definitionCorrect,indexSummary,methodBreakdown,numericDelta,resolverSummary,sumNumeric,
} from "../harness/jvmdLifecycle.ts";

test("machine index summary preserves reuse and phase timings",()=>{
  const summary=indexSummary({result:{index:{
    phase:"ready",total:10,scanned:10,indexed:4,reused:8,hashes:2,
    timings:{scans:1,scan_ms:100,discovery_ms:5,hash_ms:7,parse_ms:20,storage_ms:30,docs_ms:11,link_ms:4,
      workspace_resolution_calls:2,workspace_resolution_ms:3},
  }}});
  assert.equal(summary.artifactsDiscovered,10);
  assert.equal(summary.artifactsReused,8);
  assert.equal(summary.artifactsHashed,2);
  assert.equal(summary.timings.workspaceIndexLoads,2);
});

test("resolver summary distinguishes cold calls from resident fast hits",()=>{
  const before=resolverSummary({result:{resolver:{resolve_calls:1,project_model_fast_hits:0}}});
  const after=resolverSummary({result:{resolver:{resolve_calls:1,project_model_fast_hits:1,cold_timings:{models_ms:4,dependencies_ms:6}}}});
  assert.equal(numericDelta(after,before,"resolveCalls"),0);
  assert.equal(numericDelta(after,before,"projectModelFastHits"),1);
  assert.equal(sumNumeric(after.coldTimings),10);
});

test("definition oracle requires exact URI and declaration range",()=>{
  const range={start:{line:4,character:13},end:{line:4,character:25}};
  assert.equal(definitionCorrect([{uri:"file:///repo/MavenProject.java",range}],"file:///repo/MavenProject.java",range),true);
  assert.equal(definitionCorrect([{uri:"file:///repo/MavenProject.java"}],"file:///repo/MavenProject.java",range),false);
  assert.equal(definitionCorrect([{uri:"file:///repo/Other.java",range}],"file:///repo/MavenProject.java",range),false);
});

test("stderr timestamps cannot supply causal stage durations",()=>{
  const top=methodBreakdown([{method:"document.open",latencyMs:90,receivedNs:10},{method:"lsp.diagnostics",latencyMs:90,receivedNs:20}],0,30);
  assert.equal(top.status,"unavailable");assert.equal(top.documentMutationRpcMs,null);assert.equal(top.diagnosticsRpcMs,null);
  assert.equal(top.receivedLogs.length,2);
});

test("aggregate and nested actor copies are never recursively added",()=>{
  const evidence=compilerEvidence({analyzer:{queries:1,module_compilers:{a:{queries:1}}}},
    {analyzer:{queries:3,module_compilers:{a:{queries:3}}}});
  assert.equal(evidence.queries.observedDifference,2);
  assert.equal(evidence.queries.value,null);assert.equal(evidence.queries.status,"unavailable");
  assert.equal(evidence.binding_computations.observedDifference,null);
});
test("missing and reset counters never become zero",()=>{
  assert.equal(numericDelta({}, {},"queries"),null);
  assert.equal(numericDelta({queries:1},{queries:2},"queries"),null);
  assert.equal(indexSummary({}).artifactsHashed,null);
});
