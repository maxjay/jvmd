import {test} from "node:test";
import assert from "node:assert/strict";
import {exactTypeItem} from "../harness/hierarchy.ts";
import {range} from "../harness/oracles.ts";
import {runFake} from "./runFake.ts";

const source="interface R {}\ninterface B extends R {}\ninterface C extends B {}\ninterface D extends C {}\n",uri="file:///fixture/Hierarchy.java";
const item=(name:string)=>({name,kind:11,uri,range:range(source,source.split("\n").find(line=>line.startsWith("interface "+name))!),selectionRange:range(source,name)});
test("type identity oracle rejects homonyms, stale spans and wrong kinds",()=>{
  const valid=item("B");exactTypeItem(valid,source,uri,"B",11);
  for(const patch of [{uri:"file:///other/Hierarchy.java"},{kind:5},{range:item("C").range},{selectionRange:range(source,"B",source.indexOf("interface C"))}])
    assert.throws(()=>exactTypeItem({...valid,...patch},source,uri,"B",11));
});
for(const caseId of ["REL-02/supertypes","REL-02/subtypes"])test(`a type hierarchy that ignores the parent change is wrong: ${caseId}`,()=>{
  const r=runFake("stale-type-hierarchy",caseId);
  try{
    assert.equal(r.status,0,r.log);const report=r.case();assert.equal(report.outcome,"incorrect");
    for(const state of ["first_use","warmup","steady"])assert(report.operations.some((o:any)=>o.method==="textDocument/prepareTypeHierarchy"&&o.state===state&&o.outcome==="pass"));
    assert(report.operations.some((o:any)=>o.state==="changed_immediate"&&o.outcome==="incorrect"));
  }finally{r.cleanup();}
});
