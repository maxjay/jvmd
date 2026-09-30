import {test} from "node:test";
import {runFake} from "./runFake.ts";
import assert from "node:assert/strict";
import {SELECTION_COMMAND} from "../harness/completionSelection.ts";
for(const [mode,outcome] of [["selection-missing","not_applicable"],["selection-offered","pass"],["selection-command-error","protocol_error"]] as const)test("actual selection runner preserves "+mode+" disposition",()=>{
  const r=runFake(mode,"CMP-01/selection-command");
  try{
    assert.equal(r.status,0,r.log);const report=r.case();
    assert.equal(report.outcome,outcome);assert.equal(report.operations.filter((o:any)=>o.endpoint===SELECTION_COMMAND).length,mode==="selection-missing"?0:1);
    if(mode==="selection-missing")assert.equal(report.notApplicableEvidence.kind,"completion_command_not_offered");
  }finally{r.cleanup();}
});
