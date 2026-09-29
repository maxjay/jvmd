import {test} from "node:test";
import assert from "node:assert/strict";
import {mkdtempSync,readFileSync,writeFileSync,rmSync} from "node:fs";
import {spawnSync} from "node:child_process";
import os from "node:os";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {reduceBundle,validateCase} from "../reduce.ts";

const json=(file:string)=>JSON.parse(readFileSync(file,"utf8"));
const rows=(file:string)=>readFileSync(file,"utf8").split("\n").filter(Boolean).map(line=>JSON.parse(line));

test("actual runner preserves exhausted stale replies, exits nonzero, and replays them without inventing a missing success",()=>{
  const tmp=mkdtempSync(path.join(os.tmpdir(),"jvmd-exhausted-transition-"));
  try{
    const command=path.join(tmp,"command.json"),output=path.join(tmp,"run");
    writeFileSync(command,JSON.stringify([process.execPath,fileURLToPath(new URL("./fake-server.ts",import.meta.url)),"stale-provider"]));
    const run=spawnSync(process.execPath,[fileURLToPath(new URL("../run.ts",import.meta.url)),
      "--servers","jvmd","--profile","custom","--command-json",command,"--output",output,
      "--only","CMP-01/api-edit","--warmup","1","--samples","2","--timeout-ms","30000"],
      {encoding:"utf8",timeout:45000});
    assert.equal(run.error,undefined,run.stderr);assert.equal(run.status,1,run.stdout+run.stderr);
    const summary=json(path.join(output,"summary.json"));
    assert.equal(summary.complete,false);assert.equal(summary.outcomes.incorrect,1);assert.deepEqual(summary.integrityIssues,[]);
    const dir=path.join(output,"01-jvmd-CMP-01-api-edit"),report=json(path.join(dir,"report.json"));
    const series=report.seriesExpectations.find((s:any)=>s.kind==="transition");
    assert.equal(series.termination,"attempt_limit");assert.equal(series.attemptCount,series.probePolicy.maxAttempts);
    assert.equal(series.attempts.length,series.attemptCount);assert.equal(series.settledOperationId,null);
    assert(series.attempts.every((attempt:any)=>attempt.outcome==="failed"));
    const operations=rows(path.join(dir,"operations.jsonl")),events=rows(path.join(dir,"events.jsonl")),exchanges=rows(path.join(dir,"exchanges.jsonl"));
    const replay=reduceBundle(output);
    assert.equal(replay.summary.complete,false);assert.deepEqual(replay.summary.outcomes,summary.outcomes);assert.deepEqual(replay.summary.integrityIssues,[]);
    assert(validateCase({...report,outcome:"pass"},events,exchanges,operations).includes("exhausted transition cannot have a successful or unexecuted case disposition"));
    const missing=structuredClone(report);missing.seriesExpectations.find((s:any)=>s.kind==="transition").attempts.pop();
    assert(validateCase(missing,events,exchanges,operations).some(issue=>issue.includes("attempt count mismatch")));
    const journalFile=path.join(dir,"transitions.jsonl"),journal=rows(journalFile);journal.pop();
    writeFileSync(journalFile,journal.map(row=>JSON.stringify(row)).join("\n")+"\n");
    assert(reduceBundle(output,false).summary.integrityIssues.some(issue=>issue.includes("transition journal differs from report")));
  }finally{rmSync(tmp,{recursive:true,force:true});}
});
