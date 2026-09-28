import test from "node:test";
import assert from "node:assert/strict";
import {mkdtempSync,rmSync} from "node:fs";
import os from "node:os";
import path from "node:path";
import {enumerationOracle,validIncompleteEnumeration} from "../harness/enumeration.ts";
import {UnavailableEvidence} from "../harness/contracts.ts";
import {ScenarioContext} from "../harness/ScenarioContext.ts";
import {createFixture} from "../harness/fixture.ts";
const expected=Array.from({length:64},(_,i)=>"scaleMember"+i);
const result=(names:string[],incomplete:boolean)=>({items:names.map(label=>({label:label+"() : int",kind:2})),isIncomplete:incomplete});
test("explicit truncation cannot become a successful full enumeration or conceal a wrong member",()=>{
  assert.throws(()=>enumerationOracle(result(expected.slice(0,50),true),expected),UnavailableEvidence);
  assert.throws(()=>enumerationOracle(result(expected.slice(0,50),false),expected),assert.AssertionError);
  assert.throws(()=>enumerationOracle(result([...expected.slice(0,49),"scaleChanged"],true),expected),assert.AssertionError);
  assert.throws(()=>enumerationOracle(result([...expected.slice(0,49),expected[0]],true),expected),assert.AssertionError);
  enumerationOracle(result(expected,false),expected);
});
test("actual query recorder retains incomplete response and replay rejects forged unavailability",async()=>{
  const root=mkdtempSync(path.join(os.tmpdir(),"incomplete-query-"));
  try{
    const value=result(expected.slice(0,50),true),params={},message={jsonrpc:"2.0",id:1};
    const client={events:[{direction:"send",sequence:1,message:{...message,method:"textDocument/completion",params}},{direction:"receive",sequence:2,message:{...message,result:value}}],
      request:async()=>({id:1,startNs:"1",endNs:"2",result:value}),journal:()=>{}} as any;
    const context=new ScenarioContext(client,createFixture(root),"jvmd",1000,1,1);
    await assert.rejects(()=>context.query("textDocument/completion",params,v=>enumerationOracle(v,expected)),/unavailable_evidence/u);
    const op=context.operations[0],id="SCALE/members-64-relevant-api";
    assert.equal(op.outcome,"unavailable_evidence");assert.deepEqual(op.rawResult,value);assert(validIncompleteEnumeration(id,op));
    for(const mutate of [(o:any)=>o.rawResult.isIncomplete=false,(o:any)=>o.unavailableEvidence.missing=[],(o:any)=>o.outcome="pass",(o:any)=>o.rawResult.items.push({label:"scaleChanged()"})]){
      const forged=structuredClone(op);mutate(forged);assert(!validIncompleteEnumeration(id,forged));
    }
  }finally{rmSync(root,{recursive:true,force:true});}
});
