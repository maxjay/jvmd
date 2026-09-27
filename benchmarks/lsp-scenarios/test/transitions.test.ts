import {test} from "node:test";
import assert from "node:assert/strict";
import {mkdtempSync,readFileSync,writeFileSync,rmSync} from "node:fs";
import {spawnSync} from "node:child_process";
import os from "node:os";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {reduceBundle} from "../reduce.ts";
import {validateTransitionAttempts} from "../harness/transitions.ts";

test("failed preparation blocks the unsent target and survives settled success with replayable attempt evidence",()=>{
  const tmp=mkdtempSync(path.join(os.tmpdir(),"jvmd-transition-"));
  try{
    const command=path.join(tmp,"command.json"),output=path.join(tmp,"run"),caseId="REL-02/supertypes";
    writeFileSync(command,JSON.stringify([process.execPath,fileURLToPath(new URL("./fake-server.ts",import.meta.url)),"delayed-type-preparation"]));
    const run=spawnSync(process.execPath,[fileURLToPath(new URL("../run.ts",import.meta.url)),"--servers","jvmd","--profile","custom","--command-json",command,"--output",output,"--only",caseId,"--warmup","1","--samples","2"],{encoding:"utf8",timeout:15000});
    assert.equal(run.error,undefined,run.stderr);assert.equal(run.status,1,run.stdout+run.stderr);
    const summary=JSON.parse(readFileSync(path.join(output,"summary.json"),"utf8"));assert.equal(summary.outcomes.incorrect,1);assert.deepEqual(summary.integrityIssues,[]);
    const directory=path.join(output,"01-jvmd-REL-02-supertypes"),report=JSON.parse(readFileSync(path.join(directory,"report.json"),"utf8"));
    const series=report.seriesExpectations.find((s:any)=>s.kind==="transition");
    assert.equal(series.firstTargetRequest,"blocked_by_preparation");assert.equal(series.attemptCount,2);assert.equal(series.termination,"settled");
    assert.equal(series.attempts[0].stage,"prepare_immediate");assert.equal(series.attempts[0].immediate.operationId,null);
    const failed=report.operations.find((o:any)=>o.operationId===series.attempts[0].immediate.preparationOperationIds[0]);
    assert.equal(failed.method,"textDocument/prepareTypeHierarchy");assert.equal(failed.outcome,"incorrect");
    assert(!report.operations.some((o:any)=>o.state==="changed_immediate"),"fabricated an unsent immediate target");
    assert(report.operations.some((o:any)=>o.state==="changed_retry_2"&&o.outcome==="pass"));
    assert(report.operations.some((o:any)=>o.state==="changed_settled"&&o.outcome==="pass"));
    const forged=structuredClone(report.operations);forged.find((o:any)=>o.operationId===failed.operationId).outcome="pass";
    assert(validateTransitionAttempts(series,forged).includes("blocked target lacks failed preparation witness"));
    const incomplete=structuredClone(series);delete incomplete.attempts[1].settled;
    assert(validateTransitionAttempts(incomplete,report.operations).includes("passing attempt has no settled target"));
    const journalFile=path.join(directory,"transitions.jsonl"),journal=readFileSync(journalFile,"utf8").trim().split("\n").map(l=>JSON.parse(l));
    assert.deepEqual(journal.map(row=>row.event),["attempt_started","attempt_finished","attempt_started","attempt_finished"]);
    journal[1].immediate.operationId="fabricated-target";writeFileSync(journalFile,journal.map(row=>JSON.stringify(row)).join("\n")+"\n");
    assert(reduceBundle(output,false).summary.integrityIssues.some((s:string)=>s.includes("transition journal differs from report")));
    delete series.attempts;writeFileSync(path.join(directory,"report.json"),JSON.stringify(report));
    assert(reduceBundle(output,false).summary.integrityIssues.some((s:string)=>s.includes("declared transition attempt evidence missing")),"new capture was downgraded to legacy validation by deleting its attempt records");
  }finally{rmSync(tmp,{recursive:true,force:true});}
});
