import {test} from "node:test";
import assert from "node:assert/strict";
import {mkdtempSync,readFileSync,writeFileSync,rmSync,existsSync} from "node:fs";
import {spawn,spawnSync} from "node:child_process";
import os from "node:os";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {reduceBundle,validateCase,quantile} from "../reduce.ts";
const runner=fileURLToPath(new URL("../run.ts",import.meta.url)),fake=fileURLToPath(new URL("./fake-server.ts",import.meta.url));
function setup(mode:string,caseId="CMP-01/first-repeat"){
  const tmp=mkdtempSync(path.join(os.tmpdir(),"jvmd-reduce-")),output=path.join(tmp,"run"),command=path.join(tmp,"command.json");writeFileSync(command,JSON.stringify([process.execPath,fake,mode]));
  return {tmp,output,args:[runner,"--servers","jvmd","--profile","custom","--command-json",command,"--output",output,"--only",caseId,"--timeout-ms","2000"]};
}
test("artifact-only reduction reproduces the runner and rejects altered raw evidence",()=>{
  const {tmp,output,args}=setup("correct");try{
    const run=spawnSync(process.execPath,args,{encoding:"utf8",timeout:15000});assert.equal(run.status,0,run.stderr);
    const reduced=reduceBundle(output);assert.equal(reduced.summary.complete,true);
    assert.deepEqual(reduced.summary,JSON.parse(readFileSync(path.join(output,"summary.json"),"utf8")));
    assert.equal(reduced.metrics.find(r=>r.state==="steady")?.attempted,20);
    assert.equal(reduced.summary.catalogueComplete,false);
    const file=path.join(output,"01-jvmd-CMP-01-first-repeat/operations.jsonl");writeFileSync(file,readFileSync(file,"utf8").replace('"label":"name"','"label":"WRONG"'));
    const bad=reduceBundle(output);assert.equal(bad.summary.complete,false);assert(bad.summary.integrityIssues.some(s=>s.includes("hash mismatch")));assert(bad.summary.integrityIssues.some(s=>s.includes("raw result mismatch")));
  }finally{rmSync(tmp,{recursive:true,force:true});}
});
test("a successful settled retry cannot erase the incorrect immediate response",()=>{
  const {tmp,output,args}=setup("delayed-provider","CMP-01/api-edit");try{
    const run=spawnSync(process.execPath,args,{encoding:"utf8",timeout:15000});assert.equal(run.status,1,run.stderr);
    const reduced=reduceBundle(output);assert.equal(reduced.summary.outcomes.incorrect,1);
    const rows=readFileSync(path.join(output,"01-jvmd-CMP-01-api-edit/operations.jsonl"),"utf8").trim().split("\n").map(s=>JSON.parse(s));
    assert(rows.some(r=>r.state==="changed_immediate"&&r.outcome==="incorrect"));assert(rows.some(r=>r.state==="changed_settled"&&r.outcome==="pass"));
  }finally{rmSync(tmp,{recursive:true,force:true});}
});
test("interruption retains live protocol evidence and cannot pass reduction",async()=>{
  const {tmp,output,args}=setup("hang");const run=spawn(process.execPath,args,{stdio:"ignore"});const done=new Promise(resolve=>run.once("exit",resolve));
  try{
    const events=path.join(output,"01-jvmd-CMP-01-first-repeat/events.jsonl"),deadline=Date.now()+5000;
    while(!existsSync(events)||!readFileSync(events,"utf8").includes('"method":"textDocument/completion"')){assert(Date.now()<deadline,"request not journalled before deadline");await new Promise(resolve=>setTimeout(resolve,20));}
    run.kill("SIGTERM");await done;
    const reduced=reduceBundle(output);assert.equal(reduced.summary.complete,false);assert(reduced.summary.integrityIssues.some(s=>s.includes("interrupted")));
    assert(readFileSync(events,"utf8").includes('"method":"workspace/didChangeConfiguration"'));
  }finally{run.kill("SIGKILL");rmSync(tmp,{recursive:true,force:true});}
});
test("reducer refuses a pass that conceals a failed assertion",()=>{
  const issues=validateCase({outcome:"pass",correctnessOnly:true,assertions:[{passed:false}]},[],[],[]);assert(issues.includes("case pass conceals failed assertion"));
  assert(Math.abs(quantile([0,10,20,30],.95)!-28.5)<1e-12);assert.equal(quantile([],.95),null);
});
