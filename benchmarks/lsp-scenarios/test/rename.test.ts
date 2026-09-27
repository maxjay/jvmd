import {test} from "node:test";
import assert from "node:assert/strict";
import {mkdtempSync,readFileSync,writeFileSync,rmSync} from "node:fs";
import {spawnSync} from "node:child_process";
import os from "node:os";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {isRenameRejection} from "../harness/rename.ts";
import {validateCase} from "../reduce.ts";

test("rename rejection accepts semantic errors but excludes unavailable, internal, cancelled and timed-out requests",()=>{
  for(const code of [-32600,-32602,-32803])assert(isRenameRejection({code,message:"Cannot rename this element"}));
  for(const error of [null,{code:-32601,message:"Rename unavailable"},{code:-32603,message:"Rename internal failure"},
    {code:-32800,message:"Rename cancelled"},{code:-32802,message:"Rename cancelled by server"},
    {code:-32600,message:"Malformed request"},{code:-32600,message:"Rename timeout",kind:"timeout"},
    {code:"-32600",message:"Cannot rename"}])assert.equal(isRenameRejection(error),false);
});

for(const kind of ["prepare","rename"])for(const [mode,outcome] of [
  ["rename-null","pass"],["rename-rejection","pass"],["rename-wrong-result","incorrect"],
  ["rename-internal-error","protocol_error"],["rename-missing-method","protocol_error"],["rename-reject-all","protocol_error"],
  ...(kind==="rename"?[["rename-empty-edit","pass"]]:[]),
])test(`actual runner ${kind} invalid position: ${mode} is ${outcome}`,()=>{
  const tmp=mkdtempSync(path.join(os.tmpdir(),"jvmd-rename-")),caseId=`REF-01/${kind}-invalid`;
  try{
    const command=path.join(tmp,"command.json"),output=path.join(tmp,"run");
    writeFileSync(command,JSON.stringify([process.execPath,fileURLToPath(new URL("./fake-server.ts",import.meta.url)),mode]));
    const run=spawnSync(process.execPath,[fileURLToPath(new URL("../run.ts",import.meta.url)),"--servers","jvmd","--profile","custom","--command-json",command,"--output",output,"--only",caseId],{encoding:"utf8",timeout:15000});
    assert.equal(run.error,undefined,run.stderr);assert.equal(run.status,outcome==="pass"?0:1,run.stdout+run.stderr);
    const summary=JSON.parse(readFileSync(path.join(output,"summary.json"),"utf8"));
    assert.equal(summary.outcomes[outcome],1);assert.deepEqual(summary.integrityIssues,[]);
    const directory=path.join(output,"01-jvmd-"+caseId.replaceAll("/","-"));
    const lines=(name:string)=>readFileSync(path.join(directory,name+".jsonl"),"utf8").trim().split("\n").filter(Boolean).map(l=>JSON.parse(l));
    const operations=lines("operations"),events=lines("events"),exchanges=lines("exchanges"),op=operations.at(-1);
    assert.equal(operations[0].state,"baseline");
    if(mode==="rename-reject-all"){assert.equal(operations.length,1);assert.equal(op.outcome,"protocol_error");}
    else{assert.equal(operations[0].outcome,"pass");assert.equal(op.state,"invalid_position");assert.equal(op.outcome,outcome);}
    if(mode==="rename-rejection"){
      assert.equal(op.expectedRejection,"rename_rejection");assert.equal(op.error.code,-32600);
      assert.deepEqual(exchanges.find(e=>e.id===op.requestId).error,op.error);
      assert.deepEqual(events.find(e=>e.sequence===op.responseEventId).message.error,op.error);
      const report=JSON.parse(readFileSync(path.join(directory,"report.json"),"utf8"));
      assert(report.assertions.some((a:any)=>a.name==="rename rejection preserves complete fixture state"&&a.passed));
      for(const mutate of [
        (rows:any[])=>{delete rows.at(-1).expectedRejection;},
        (rows:any[])=>{delete rows.at(-1).responsePolicy;},
        (rows:any[])=>{rows.at(-1).state="first_use";},
        (rows:any[])=>{rows[0].outcome="protocol_error";},
        (rows:any[])=>{rows.at(-1).error.code=-32603;},
      ]){
        const tampered=structuredClone(operations);mutate(tampered);
        assert(validateCase(report,events,exchanges,tampered).some(s=>s==="pass conceals error"),"reducer accepted a forged rejection disposition");
      }
      assert(validateCase({...report,caseId:"REF-01/rename"},events,exchanges,operations).includes("pass conceals error"));
    }
  }finally{rmSync(tmp,{recursive:true,force:true});}
});
