import {test} from "node:test";
import assert from "node:assert/strict";
import {mkdtempSync,readFileSync,writeFileSync,rmSync} from "node:fs";
import {spawnSync} from "node:child_process";
import os from "node:os";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {validUnofferedSelection,SELECTION_COMMAND} from "../harness/completionSelection.ts";
import {reduceVariants} from "../variants.ts";
function evidence(){const item={label:"name()",data:{id:17}},operations=[{method:"textDocument/completion",operationId:"op-1",requestId:1,stateBefore:[],endNs:"20",outcome:"pass",rawResult:{items:[item]}},{method:"completionItem/resolve",originRequestId:1,stateBefore:[],startNs:"30",state:"after_selection",outcome:"pass",rawResult:{documentation:"NAME_DOC_V1"}}];return {caseId:"CMP-01/selection-command",operations,notApplicableEvidence:{kind:"completion_command_not_offered",endpoint:SELECTION_COMMAND,completionOperationId:"op-1",selectedItem:item}};}
test("not-offered proof requires an original command-free item and successful current resolve",()=>{
  assert(validUnofferedSelection(evidence()));
  for(const mutate of [(r:any)=>r.notApplicableEvidence.selectedItem={label:"other"},(r:any)=>r.operations[0].rawResult.items[0].command={command:SELECTION_COMMAND},(r:any)=>r.operations[0].outcome="incorrect",(r:any)=>r.operations[1].originRequestId=7,(r:any)=>r.operations[1].stateBefore=[{version:2}],(r:any)=>r.operations[1].rawResult.documentation="NUMBER_DOC_V1",(r:any)=>r.operations.push({endpoint:SELECTION_COMMAND,outcome:"pass"}),(r:any)=>r.shutdownError="unclean",(r:any)=>r.caseId="OTHER"]){const r=evidence();mutate(r);assert.equal(validUnofferedSelection(r),false);}
});
test("variant gate accepts not offered only when explicitly allowed and proven",()=>{
  const report={...evidence(),outcome:"not_applicable",server:"jvmd",block:1};
  const catalogue={scenarios:[{id:"CMP-01",variants:"optional",correctness:"original"}]},registry=[{id:report.caseId,family:"CMP-01"}];
  const witness={caseId:report.caseId,allowNotOffered:true,operations:[{endpoint:SELECTION_COMMAND,states:["first_use"]}]};
  const contract={schemaVersion:1,families:[{id:"CMP-01",sourceVariants:"optional",sourceCorrectness:"original",variants:[{id:"CMP-01/selection",requirement:"only if offered",implementation:"implemented",evidence:[witness]}]}]};
  const run=()=>reduceVariants(contract,catalogue,registry,{servers:["jvmd"],blocks:1},[report]);
  assert.equal(run().complete,true);assert.equal(run().rows[0].outcome,"not_applicable");
  witness.allowNotOffered=false;assert.equal(run().rows[0].outcome,"missing_evidence");
});
for(const [mode,outcome,exit] of [["selection-missing","not_applicable",0],["selection-offered","pass",0],["selection-command-error","protocol_error",1]] as const)test("actual selection runner preserves "+mode+" disposition",()=>{
  const tmp=mkdtempSync(path.join(os.tmpdir(),"jvmd-selection-"));
  try{
    const output=path.join(tmp,"run"),command=path.join(tmp,"command.json");writeFileSync(command,JSON.stringify([process.execPath,fileURLToPath(new URL("./fake-server.ts",import.meta.url)),mode]));
    const run=spawnSync(process.execPath,[fileURLToPath(new URL("../run.ts",import.meta.url)),"--servers","jvmd","--profile","custom","--command-json",command,"--output",output,"--only","CMP-01/selection-command"],{encoding:"utf8",timeout:15000});
    assert.equal(run.status,exit,run.stdout+run.stderr);const summary=JSON.parse(readFileSync(path.join(output,"summary.json"),"utf8"));assert.equal(summary.outcomes[outcome],1);assert.deepEqual(summary.integrityIssues,[]);
    const report=JSON.parse(readFileSync(path.join(output,"01-jvmd-CMP-01-selection-command/report.json"),"utf8"));
    assert.equal(report.outcome,outcome);assert.equal(report.operations.filter((o:any)=>o.endpoint===SELECTION_COMMAND).length,mode==="selection-missing"?0:1);
    if(mode==="selection-missing"){assert(validUnofferedSelection(report));assert(!summary.uncoveredTargetApiIds.includes("API-046"));}
  }finally{rmSync(tmp,{recursive:true,force:true});}
});
