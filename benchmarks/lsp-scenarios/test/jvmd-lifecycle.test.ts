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

test("definition oracle accepts only the expected source URI",()=>{
  assert.equal(definitionCorrect([{uri:"file:///repo/MavenProject.java"}],"file:///repo/MavenProject.java"),true);
  assert.equal(definitionCorrect([{targetUri:"file:///repo/Other.java"}],"file:///repo/MavenProject.java"),false);
});

test("admission breakdown keeps top-level daemon work separate from compiler evidence",()=>{
  const metrics=[
    {method:"document.open",latencyMs:2,receivedNs:10},
    {method:"lsp.diagnostics",latencyMs:20,receivedNs:20},
    {method:"lsp.diagnostics",latencyMs:1,receivedNs:21},
  ];
  const top=methodBreakdown(metrics,0,30);
  assert.equal(top.documentMutationRpcMs,2);
  assert.equal(top.diagnosticsRpcMs,21);
  const evidence=compilerEvidence({queries:1,resident_semantic_state:{semantic_facts:2}},
    {queries:3,resident_semantic_state:{semantic_facts:7}});
  assert.equal(evidence.queries,2);
  assert.equal(evidence.semantic_facts,5);
});
