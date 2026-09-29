import {test} from "node:test";
import assert from "node:assert/strict";
import {CATALOGUE,classify,dispositions} from "../harness/registry.ts";
import {cases} from "../run.ts";

test("every workbook API has exactly one disposition and none is unclassified",()=>{
  const rows=dispositions(cases);
  assert.equal(rows.length,129);assert.equal(new Set(rows.map((r:any)=>r.apiId)).size,129);
  assert.deepEqual(rows.filter((r:any)=>r.disposition.kind==="unclassified").map((r:any)=>r.apiId),[]);
  for(const row of rows){
    if(row.role==="Reference only")assert.equal(row.disposition.kind,"reference_only",row.apiId);
    // Support entries never become padded benchmark cases unless a case really drives them.
    if(["Setup / support","Harness support"].includes(row.role))assert(["client_support","cases"].includes(row.disposition.kind),row.apiId);
  }
});
test("registered cases cite only real workbook targets and families",()=>{
  const apis=new Map(CATALOGUE.apis.map((a:any)=>[a.id,a])),families=new Set(CATALOGUE.scenarios.map((s:any)=>s.id));
  assert.equal(new Set(cases.map(x=>x.id)).size,cases.length,"duplicate case id");
  for(const c of cases){
    assert(families.has(c.family),c.id);
    for(const id of c.apis)assert(apis.has(id),c.id+" cites unknown "+id);
  }
});
test("any scenario target without a case is a gap, whether standard LSP or JDTLS API",()=>{
  for(const method of ["textDocument/moniker","java/buildWorkspace","java.project.getAll"])
    assert.equal(classify({id:"API-X",method,testUse:"Scenario target"},[]).kind,"unclassified");
  assert.equal(classify({id:"API-Y",method:"java/buildWorkspace",testUse:"Scenario target"},[{id:"BLD-01/x",apis:["API-Y"]}]).kind,"cases");
});
test("every workbook family has at least one case",()=>{
  const covered=new Set(cases.map(c=>c.family));
  assert.deepEqual(CATALOGUE.scenarios.map((f:any)=>f.id).filter((id:string)=>!covered.has(id)),[]);
});
