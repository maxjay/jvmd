import { test } from "node:test";
import assert from "node:assert/strict";
import {
  diagnosticAdmissionDecision,
  type DiagnosticVersionMode,
} from "../harness/LspScenarioHarness.ts";

test("late versionless diagnostics cannot satisfy a new-version admission wait",()=>{
  const uri="file:///repo/A.java";
  let mode:DiagnosticVersionMode="unknown";

  const opened=diagnosticAdmissionDecision(mode,{uri,version:1,diagnostics:[]},uri,1);
  assert.equal(opened.kind,"verified");
  assert.equal(opened.mode,"versioned");
  mode=opened.mode;

  // didChange requested version 2. A late notification for the prior state carries no
  // version, so it is ambiguous and must not satisfy the version-2 admission boundary.
  const lateVersionless=diagnosticAdmissionDecision(mode,{uri,diagnostics:[]},uri,2);
  assert.equal(lateVersionless.kind,"ignore");
  assert.equal(lateVersionless.mode,"versioned");

  const oldVersion=diagnosticAdmissionDecision(mode,{uri,version:1,diagnostics:[]},uri,2);
  assert.equal(oldVersion.kind,"ignore");

  const current=diagnosticAdmissionDecision(mode,{uri,version:2,diagnostics:[]},uri,2);
  assert.equal(current.kind,"verified");
  assert.equal(current.mode,"versioned");
});

test("versionless-only diagnostics make current-version admission unavailable",()=>{
  const uri="file:///repo/A.java";
  const decision=diagnosticAdmissionDecision("unknown",{uri,diagnostics:[]},uri,1);
  assert.equal(decision.kind,"unavailable");
  assert.equal(decision.mode,"versionless");
});
