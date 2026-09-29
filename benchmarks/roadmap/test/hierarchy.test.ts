import {test} from "node:test";
import assert from "node:assert/strict";
import {mkdtempSync,rmSync} from "node:fs";
import os from "node:os";
import path from "node:path";
import {exactLegacyHierarchy,exactTypeItem,legacyHierarchyArguments} from "../harness/hierarchy.ts";
import {range} from "../harness/oracles.ts";
import {ScenarioContext} from "../harness/ScenarioContext.ts";
import {createFixture} from "../harness/fixture.ts";
import {runFake} from "./runFake.ts";

const source="interface R {}\ninterface B extends R {}\ninterface C extends B {}\ninterface D extends C {}\n",uri="file:///fixture/Hierarchy.java";
const graph={R:{kind:11,parents:[],children:["B"]},B:{kind:11,parents:["R"],children:["C"]},C:{kind:11,parents:["B"],children:["D"]},D:{kind:11,parents:["C"],children:[]}};
const item=(name:string)=>({name,kind:11,uri,range:range(source,source.split("\n").find(line=>line.startsWith("interface "+name))!),selectionRange:range(source,name)});
const both={...item("B"),parents:[{...item("R"),parents:[],children:[item("B")]}],children:[{...item("C"),parents:[item("B")],children:[item("D")]}]};
test("legacy command encodes direction and depth as JSON strings like the Java editor client",()=>{
  const raw={opaque:{token:"original"}};
  assert.deepEqual(legacyHierarchyArguments(raw,2,1),['{"opaque":{"token":"original"}}',"2","1"]);
  assert.deepEqual(legacyHierarchyArguments(raw,0,0).map(s=>JSON.parse(s)),[raw,0,0]);
  assert.throws(()=>legacyHierarchyArguments(raw,3,1));assert.throws(()=>legacyHierarchyArguments(raw,1,-1));
});
test("hierarchy oracle accepts exact bounded bidirectional trees including back edges",()=>{
  exactLegacyHierarchy(both,source,uri,graph,"B",2,2);
  exactLegacyHierarchy(item("B"),source,uri,graph,"B",2,0);
  exactLegacyHierarchy({...item("B"),parents:[item("R")]},source,uri,graph,"B",1,1);
  exactLegacyHierarchy({...item("B"),children:[item("C")]},source,uri,graph,"B",0,1);
});
test("hierarchy oracle rejects wrong direction, excess depth, missing grandchildren and duplicate types",()=>{
  assert.throws(()=>exactLegacyHierarchy(both,source,uri,graph,"B",1,2));
  assert.throws(()=>exactLegacyHierarchy(both,source,uri,graph,"B",2,1));
  for(const children of [[],[item("C"),item("C")],[{...item("C"),parents:[item("B")],children:[]}]])
    assert.throws(()=>exactLegacyHierarchy({...both,children},source,uri,graph,"B",2,2));
});
test("type identity oracle rejects homonyms, stale spans and wrong kinds",()=>{
  const valid=item("B");exactTypeItem(valid,source,uri,"B",11);
  for(const patch of [{uri:"file:///other/Hierarchy.java"},{kind:5},{range:item("C").range},{selectionRange:range(source,"B",source.indexOf("interface C"))}])
    assert.throws(()=>exactTypeItem({...valid,...patch},source,uri,"B",11));
});
test("legacy resolve requires an issued unchanged item from the current client state",async()=>{
  const tmp=mkdtempSync(path.join(os.tmpdir(),"jvmd-hierarchy-origin-"));
  try{
    let requests=0;
    const client={events:[],journal:()=>{},notify:()=>process.hrtime.bigint(),request:async()=>({id:++requests,result:{...item("B"),data:{token:"opaque"}},startNs:"1",endNs:"2"})} as any;
    const c=new ScenarioContext(client,createFixture(tmp),"jvmd",100,1,1);await c.open("Hierarchy.java");
    const original=await c.execute("java.navigate.openTypeHierarchy",[],()=>{});
    await c.execute("java.navigate.resolveTypeHierarchy",[JSON.stringify(original),2,1],()=>{});
    assert.equal(c.operations.at(-1).originRequestId,1);
    await assert.rejects(c.execute("java.navigate.resolveTypeHierarchy",[JSON.stringify({...original,data:{token:"forged"}}),2,1],()=>{}));
    c.change("Hierarchy.java",c.text("Hierarchy.java")+"\n");
    await assert.rejects(c.execute("java.navigate.resolveTypeHierarchy",[JSON.stringify(original),2,1],()=>{}));
    assert.equal(requests,2,"invalid opaque items reached the server");
  }finally{rmSync(tmp,{recursive:true,force:true});}
});
for(const [mode,caseId,outcome] of [
  ["hierarchy-correct","REL-02/legacy-children-depth-2","pass"],
  ["hierarchy-wrong-direction","REL-02/legacy-parents-depth-1","incorrect"],
  ["hierarchy-wrong-depth","REL-02/legacy-both-depth-0","incorrect"],
  ["stale-type-hierarchy","REL-02/supertypes","incorrect"],
  ["stale-type-hierarchy","REL-02/subtypes","incorrect"],
  ...["children","parents","both"].map(direction=>["stale-legacy-hierarchy",`REL-02/legacy-${direction}-parent-change`,"incorrect"]),
])test(`actual hierarchy runner ${mode}: ${caseId}`,()=>{
  const r=runFake(mode,caseId);
  try{
    assert.equal(r.status,0,r.log);const report=r.case();assert.equal(report.outcome,outcome);
    if(mode==="hierarchy-correct")assert.equal(report.operations.filter((o:any)=>o.endpoint==="java.navigate.resolveTypeHierarchy"&&o.originRequestId).length,4);
    if(mode==="stale-type-hierarchy"){
      for(const state of ["first_use","warmup","steady"])assert(report.operations.some((o:any)=>o.method==="textDocument/prepareTypeHierarchy"&&o.state===state&&o.outcome==="pass"));
      assert(report.operations.some((o:any)=>o.state==="changed_immediate"&&o.outcome==="incorrect"));
    }
    if(mode==="stale-legacy-hierarchy"){
      assert(report.operations.some((o:any)=>o.state==="baseline"&&o.outcome==="pass"));
      const expansions=report.operations.filter((o:any)=>o.endpoint==="java.navigate.resolveTypeHierarchy");
      assert(expansions.some((o:any)=>o.state==="changed_immediate"&&o.outcome==="incorrect"));
      assert.equal(new Set(expansions.map((o:any)=>o.originRequestId)).size,expansions.length,"legacy retry reused an earlier opaque item");
    }
  }finally{r.cleanup();}
});
