import {test} from "node:test";
import assert from "node:assert/strict";
import {isRenameRejection} from "../harness/rename.ts";
import {runFake} from "./runFake.ts";

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
  const caseId=`REF-01/${kind}-invalid`,r=runFake(mode,caseId);
  try{
    assert.equal(r.status,0,r.log);const report=r.case(),operations=report.operations,op=operations.at(-1);
    assert.equal(report.outcome,outcome);assert.equal(operations[0].state,"baseline");
    if(mode==="rename-reject-all"){assert.equal(operations.length,1);assert.equal(op.outcome,"protocol_error");}
    else{assert.equal(operations[0].outcome,"pass");assert.equal(op.state,"invalid_position");assert.equal(op.outcome,outcome);}
    if(mode==="rename-rejection"){assert.equal(op.expectedRejection,"rename_rejection");assert.equal(op.error.code,-32600);}
  }finally{r.cleanup();}
});
