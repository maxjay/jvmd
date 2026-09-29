import {test} from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {validateTransitionAttempts,validateTransitionTermination} from "../harness/transitions.ts";

function fixture(){
  const operations=Array.from({length:3},(_,i)=>({operationId:`op-${i+1}`,method:"textDocument/completion",
    state:i===0?"changed_immediate":`changed_retry_${i+1}`,outcome:"incorrect",assertionError:"current marker absent",
    startNs:String(100+i*10),endNs:String(105+i*10)}));
  const series:any={kind:"transition",method:"textDocument/completion",firstOperationIndex:0,triggerNs:"90",deadlineNs:"1000",
    endedNs:"126",attemptCount:3,firstTargetRequest:"sent",settledOperationId:null,termination:"attempt_limit",
    probePolicy:{maxAttempts:3},attempts:operations.map((op,i)=>({attempt:i+1,startNs:op.startNs,endNs:op.endNs,
      stage:"query_immediate",outcome:"failed",error:"current marker absent",
      immediate:{preparationOperationIds:[],operationId:op.operationId}}))};
  return {operations,series,outcome:"incorrect"};
}
const audit=(f:ReturnType<typeof fixture>)=>[...validateTransitionAttempts(f.series,f.operations),...validateTransitionTermination(f.series,f.operations,f.outcome)];

test("all failed attempts at the declared limit form complete evidence, not a product pass",()=>{
  const f=fixture(),before=JSON.stringify(f);assert.deepEqual(audit(f),[]);assert.equal(JSON.stringify(f),before);
  assert.equal(f.outcome,"incorrect");assert(f.operations.every(op=>op.outcome==="incorrect"));
});
test("a witnessed deadline is valid failed evidence without a successful settled result",()=>{
  const f=fixture();f.series.termination="deadline";f.series.endedNs=f.series.deadlineNs;
  assert.deepEqual(audit(f),[]);f.series.endedNs="999";assert(audit(f).includes("probe stopped before declared deadline"));
});
test("stopping short of the declared attempt limit is still an integrity failure",()=>{
  const f=fixture();f.series.attemptCount=2;f.series.attempts.pop();f.operations.pop();
  assert(audit(f).includes("probe stopped before declared attempt limit"));
});
test("missing attempts cannot be explained away as non-convergence",()=>{
  const f=fixture();f.series.attempts.pop();assert(audit(f).some(issue=>issue.includes("count mismatch")));
  delete f.series.attempts;assert(audit(f).includes("exhausted transition attempt evidence missing"));
});
test("an error string without the failed operation is not a complete failure witness",()=>{
  const f=fixture();f.operations.pop();assert(audit(f).includes("transition target missing"));
  assert(audit(f).includes("exhausted transition lacks failed operation evidence"));
});
test("a passing operation or absent error cannot be relabelled as a failed attempt",()=>{
  for(const mutate of [(op:any)=>op.outcome="pass",(op:any)=>delete op.assertionError]){
    const f=fixture();mutate(f.operations[1]);assert(audit(f).includes("exhausted transition lacks failed operation evidence"));
  }
  const f=fixture();delete f.series.attempts[0].error;assert(audit(f).includes("exhausted transition has an unproven failed attempt"));
});
test("exhausted transitions never permit pass, unsupported, not-applicable or not-run dispositions",()=>{
  for(const outcome of ["pass","unsupported","not_applicable","not_run"]){
    const f=fixture();f.outcome=outcome;assert(audit(f).includes("exhausted transition cannot have a successful or unexecuted case disposition"));
  }
});
test("successful convergence still requires the exact passing settled operation",()=>{
  const f=fixture();f.series.termination="settled";
  assert(audit(f).includes("settled probe missing"));
  f.series.settledOperationId=f.operations[2].operationId;
  assert(audit(f).includes("settled probe missing"));
  f.operations[2].outcome="pass";f.operations[2].state="changed_settled";delete (f.operations[2] as any).assertionError;
  assert.deepEqual(validateTransitionTermination(f.series,f.operations,f.outcome),[]);
  f.operations[2].method="textDocument/hover";assert(validateTransitionTermination(f.series,f.operations,f.outcome).includes("settled probe missing"));
});
test("preparation failure can terminate a target that was never sent, but needs the failed preparation",()=>{
  const f=fixture();
  f.series.firstTargetRequest="blocked_by_preparation";
  f.series.attempts[0].stage="prepare_immediate";
  f.series.attempts[0].immediate={preparationOperationIds:["op-1"],operationId:null};
  f.operations[0].method="textDocument/prepareTypeHierarchy";
  assert.deepEqual(audit(f),[]);f.operations[0].outcome="pass";
  assert(audit(f).includes("exhausted transition lacks failed operation evidence"));
});
test("an attempt cannot finish after its recorded transition termination",()=>{
  const f=fixture();f.series.endedNs="124";assert(audit(f).includes("failed attempt finishes after transition termination"));
});
test("unsupported termination and legacy missing settled evidence still fail",()=>{
  const f=fixture();f.series.termination="interrupted";
  assert(audit(f).includes("probe termination missing"));assert(audit(f).includes("settled probe missing"));
  delete f.series.probePolicy;assert(audit(f).includes("settled probe missing"));
});
test("the canonical case reducer invokes the tested termination validator",()=>{
  const source=readFileSync(new URL("../reduce.ts",import.meta.url),"utf8");
  assert.match(source,/validateTransitionTermination\(s,operations,report.outcome\)/);
  assert.doesNotMatch(source,/check\(operations.some\(o=>o.operationId===s.settledOperationId/);
});
