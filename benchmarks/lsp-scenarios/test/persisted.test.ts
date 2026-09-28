import test from "node:test";
import assert from "node:assert/strict";
import {mkdtempSync,writeFileSync,readFileSync,rmSync} from "node:fs";
import {spawnSync} from "node:child_process";
import os from "node:os";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {persistedStateOracle,persistedSessionOracle} from "../harness/persisted.ts";
import {reduceBundle} from "../reduce.ts";
const runner=fileURLToPath(new URL("../run.ts",import.meta.url)),fake=fileURLToPath(new URL("./fake-server.ts",import.meta.url));
test("persisted state rejects empty, replaced and temporally inverted snapshots",()=>{
  const snapshot={directory:"/state",files:{"index.bin":"a".repeat(64)},bytes:3,startNs:"10",endNs:"20"};
  const evidence={seedStopped:true,afterSeed:snapshot,beforeReopen:{...snapshot,startNs:"21",endNs:"30"},reopenStartedNs:"31"};
  persistedStateOracle(evidence);
  for(const mutate of [(e:any)=>e.seedStopped=false,(e:any)=>e.beforeReopen.files={"index.bin":"changed"},(e:any)=>e.beforeReopen.directory="/other",(e:any)=>e.afterSeed.bytes=0,(e:any)=>e.reopenStartedNs="29"]){const e=structuredClone(evidence);mutate(e);assert.throws(()=>persistedStateOracle(e));}
});
for(const [mode,outcome] of [["persisted-correct","pass"],["persisted-leak","incorrect"],["persisted-seed-stale","incorrect"],["persisted-empty","incorrect"],["persisted-seed-shutdown","protocol_error"]])test("actual persisted runner retains both phases: "+mode,()=>{
  const tmp=mkdtempSync(path.join(os.tmpdir(),"persisted-runner-"));
  try{
    const command=path.join(tmp,"command.json"),out=path.join(tmp,"run");writeFileSync(command,JSON.stringify([process.execPath,fake,mode]));
    const run=spawnSync(process.execPath,[runner,"--servers","jvmd","--profile","custom","--command-json",command,"--output",out,"--only","SES-01/persisted-reopen","--timeout-ms","2000"],{encoding:"utf8",timeout:15000});
    assert.equal(run.error,undefined,run.stderr);assert.equal(run.status,outcome==="pass"?0:1,run.stdout+run.stderr);
    const root=path.join(out,"01-jvmd-SES-01-persisted-reopen"),read=(name:string)=>JSON.parse(readFileSync(path.join(root,name),"utf8")),lines=(name:string)=>readFileSync(path.join(root,name),"utf8").trim().split("\n").filter(Boolean).map(x=>JSON.parse(x));
    const report=read("report.json"),seed=read("seed-session/report.json");assert.equal(report.outcome,outcome);assert.equal(seed.finalized,true);
    assert(lines("seed-session/events.jsonl").some(e=>e.message.method==="textDocument/didChange"));
    if(outcome==="pass"){
      const summary=JSON.parse(readFileSync(path.join(out,"summary.json"),"utf8"));assert.deepEqual(summary.integrityIssues,[]);
      persistedSessionOracle(report,seed,lines("seed-session/events.jsonl"),lines("events.jsonl"));
      assert.equal(report.launch.machineState,"persisted");assert.equal(report.operations.length,1);
      const seedResources=read("seed-session/resources/lifetime-resources.json"),reopenedResources=read("resources/lifetime-resources.json");
      assert.notDeepEqual(seedResources.expected_pids,reopenedResources.expected_pids,"restart reused the process root");
      assert.deepEqual(report.launch.lifetimeResources.result,reopenedResources);
      const file=path.join(root,"resources/lifetime-resources.json");
      const original=readFileSync(file,"utf8"),forged=JSON.parse(original);forged.cpu_seconds=forged.cpu_seconds===null?0:forged.cpu_seconds+1;
      writeFileSync(file,JSON.stringify(forged));
      const invalid=reduceBundle(out,false);
      assert(invalid.summary.integrityIssues.some((x:string)=>x.includes("invalid lifetime resource evidence")),"forged resource totals passed artifact audit");
      const observation=invalid.resources.find((r:any)=>r.phase==="case");
      assert.equal(observation.counterAudit,"invalid");assert.equal(observation.cpuSeconds,null);assert.equal(observation.kernelMemoryPeakBytes,null);
      writeFileSync(file,original);
      for(const mutate of [(r:any)=>r.launch.stateDirectory="/other",(r:any)=>r.operations[0].rawResult.contents.value="int label",(r:any)=>r.persistedEvidence.beforeReopen.files={}]){
        const forged=structuredClone(report);mutate(forged);assert.throws(()=>persistedSessionOracle(forged,seed,lines("seed-session/events.jsonl"),lines("events.jsonl")));
      }
    }else if(mode==="persisted-seed-shutdown"){
      assert.equal(seed.outcome,"protocol_error");assert.match(seed.shutdownError,/unclean shutdown/u);assert.equal(report.persistedEvidence.seedStopped,false);
      assert.equal(report.reopenStatus,"not_started");assert.equal(report.launch,undefined);assert.deepEqual(report.operations,[]);
      assert.deepEqual(reduceBundle(out).summary.integrityIssues,[],"failed seed must remain auditable without inventing a reopened process");
    }
    else if(mode==="persisted-seed-stale"){assert.equal(seed.outcome,"incorrect");assert(seed.operations.some(o=>o.state==="changed_immediate"&&o.outcome==="incorrect"));assert(report.operations.some(o=>o.state==="after_reopen"&&o.outcome==="pass"));}
    else if(mode==="persisted-leak")assert(report.operations.some(o=>o.state==="after_reopen"&&o.outcome==="incorrect"));
  }finally{rmSync(tmp,{recursive:true,force:true});}
});
