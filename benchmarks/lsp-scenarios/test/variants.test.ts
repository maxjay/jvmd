import {test} from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {reduceVariants} from "../variants.ts";
import {cases} from "../suite.ts";

function fixture(){
  const catalogue={scenarios:[{id:"DOC-01",variants:"First; unchanged repeat.",correctness:"Current buffer."}]};
  const registry=[{id:"DOC-01/read",family:"DOC-01"}];
  const contract={schemaVersion:1,families:[{id:"DOC-01",sourceVariants:catalogue.scenarios[0].variants,sourceCorrectness:"Current buffer.",variants:[{id:"DOC-01/repeat",requirement:"Repeat",implementation:"implemented",evidence:[{caseId:"DOC-01/read",operations:[{endpoint:"textDocument/hover",states:["first_use","steady"]}],assertions:["current version"]}]}]}]};
  const report={caseId:"DOC-01/read",server:"jvmd",block:1,outcome:"pass",assertions:[{name:"current version",passed:true}],operations:["first_use","steady"].map((state,i)=>({endpoint:"textDocument/hover",state,outcome:"pass",operationId:String(i)}))};
  const plan={servers:["jvmd"],blocks:1};
  return {catalogue,registry,contract,report,plan};
}
test("all original families have explicit variants and valid case witnesses",()=>{
  const catalogue=JSON.parse(readFileSync(new URL("../catalogue.json",import.meta.url),"utf8"));
  const contract=JSON.parse(readFileSync(new URL("../required-variants.json",import.meta.url),"utf8"));
  assert.equal(contract.families.length,28);
  assert.equal(contract.families.flatMap((f:any)=>f.variants).length,107);
  const result=reduceVariants(contract,catalogue,cases,{servers:["jvmd","jdtls"],blocks:2},[]);
  assert.deepEqual(result.issues,[]);assert.equal(result.complete,false);
  assert.equal(result.rows.length,107*4);
});
test("a passing API case cannot cover an unimplemented required variant",()=>{
  const f=fixture();const v=f.contract.families[0].variants[0] as any;
  v.implementation="partial";v.remaining="repeat missing";
  const r=reduceVariants(f.contract,f.catalogue,f.registry,f.plan,[f.report]);
  assert.equal(r.complete,false);assert.equal(r.rows[0].outcome,"partial");assert.deepEqual(r.gaps,["DOC-01/repeat"]);
});
test("variant gate requires operations and assertions in each server/block",()=>{
  const f=fixture(),run=(reports:any[],plan=f.plan)=>reduceVariants(f.contract,f.catalogue,f.registry,plan,reports);
  assert.equal(run([f.report]).complete,true);
  assert.equal(run([{...f.report,operations:f.report.operations.slice(0,1)}]).rows[0].outcome,"missing_evidence");
  assert.equal(run([{...f.report,assertions:[]}]).rows[0].outcome,"missing_evidence");
  const missing=run([f.report],{servers:["jvmd","jdtls"],blocks:2});
  assert.equal(missing.complete,false);assert.equal(missing.rows.filter(r=>r.outcome==="not_run").length,3);
});
test("unsupported needs evidence and cannot cover an absent implementation",()=>{
  const f=fixture(),run=(report:any)=>reduceVariants(f.contract,f.catalogue,f.registry,f.plan,[report]);
  assert.equal(run({...f.report,outcome:"unsupported"}).complete,false);
  const report={...f.report,outcome:"unsupported",supportEvidence:{source:"initialize response"}};
  assert.equal(run(report).complete,true);
  (f.contract.families[0].variants[0] as any).implementation="not_implemented";
  (f.contract.families[0].variants[0] as any).remaining="case absent";
  assert.equal(run(report).complete,false);
});
test("settled success and other blocks cannot erase a failed immediate case",()=>{
  const f=fixture();const report={...f.report,outcome:"incorrect"};
  const result=reduceVariants(f.contract,f.catalogue,f.registry,{servers:["jvmd"],blocks:2},[report,{...f.report,block:2}]);
  assert.equal(result.complete,false);assert.equal(result.rows[0].outcome,"incorrect");
  assert.equal(result.rows[1].outcome,"pass");
});
test("legacy bundles and mismatched source instructions cannot claim variant completeness",()=>{
  const f=fixture();assert.equal(reduceVariants(null,f.catalogue,f.registry,f.plan,[f.report]).complete,false);
  f.contract.families[0].sourceVariants="First only";
  const result=reduceVariants(f.contract,f.catalogue,f.registry,f.plan,[f.report]);
  assert.equal(result.complete,false);assert(result.issues.some(s=>s.includes("source instructions")));
});
