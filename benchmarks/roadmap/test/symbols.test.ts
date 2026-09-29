import {test} from "node:test";
import assert from "node:assert/strict";
import {mkdtempSync,rmSync} from "node:fs";
import os from "node:os";
import path from "node:path";
import {SYMBOL_SOURCES,symbolDeclarations,exactSearchSymbols,exactSearchOutline,workspaceSymbolArgument} from "../harness/symbols.ts";
import {ScenarioContext} from "../harness/ScenarioContext.ts";
import {createFixture} from "../harness/fixture.ts";
import {symbolCases} from "../scenarios/symbols.ts";
import {runFake} from "./runFake.ts";
import {range} from "../harness/oracles.ts";

const uri="file:///fixture/SearchCase.java",parentUri="file:///fixture/SymbolParent.java",source=SYMBOL_SOURCES["SearchCase.java"];
const types=symbolDeclarations(source,uri),rows=types.map(t=>({name:t.name,kind:t.kind,location:{uri:t.uri,range:t.selection}}));
test("Java symbol command uses named enum serialization without changing the issued item",()=>{
  const original={...rows[0],data:{opaque:"preserved"}},before=structuredClone(original),encoded=JSON.parse(workspaceSymbolArgument(original));
  assert.equal(encoded.kind,"Interface");assert.deepEqual({...encoded,kind:original.kind},original);assert.deepEqual(original,before);
  assert.equal(JSON.parse(workspaceSymbolArgument(rows[1])).kind,"Class");
  for(const kind of [0,27,5.5,"Class",null])assert.throws(()=>workspaceSymbolArgument({...original,kind}));
});
test("symbol search rejects stale names, homonyms, wrong kinds, use ranges and extra declarations",()=>{
  exactSearchSymbols(rows,types);
  for(const value of [rows.slice(1),[...rows,rows[0]],rows.map((r,i)=>i? r:{...r,name:"BenchmarkAfter"}),
    rows.map((r,i)=>i?r:{...r,kind:5}),rows.map((r,i)=>i?r:{...r,location:{...r.location,uri:parentUri}}),
    rows.map((r,i)=>i?r:{...r,location:{uri,range:range(source,"SymbolParent")}})])assert.throws(()=>exactSearchSymbols(value,types));
});
test("extended outline proves inherited declaration in the parent's own source",()=>{
  const local={name:"local()",kind:6,uri,selectionRange:range(source,"local"),range:range(source,"int local();")};
  const inherited={name:"inherited()",kind:6,uri:parentUri,selectionRange:range(SYMBOL_SOURCES["SymbolParent.java"],"inherited"),range:range(SYMBOL_SOURCES["SymbolParent.java"],"int inherited();")};
  const outline=types.map(t=>({name:t.name,kind:t.kind,uri,selectionRange:t.selection,range:t.range,children:t.kind===11?[local,inherited]:[]}));
  const verify=(v:any)=>exactSearchOutline(v,source,uri,SYMBOL_SOURCES["SymbolParent.java"],parentUri,true);verify(outline);
  for(const children of [[local],[local,{...inherited,uri}],[local,{...inherited,kind:8}],[local,inherited,inherited]])assert.throws(()=>verify([{...outline[0],children},outline[1]]));
  assert.throws(()=>verify([outline[0],{...outline[1],children:[inherited]}]));
  for(const selectionRange of [range(source,"bench"),range(source,"package bench;")])verify([{name:"bench",kind:4,uri,range:range(source,"package bench;"),selectionRange},...outline]);
  assert.throws(()=>verify([{name:"bench",kind:4,uri,range:range(source,"package bench;"),selectionRange:range(source,"BenchmarkBefore")},...outline]));
});
for(const [route,mode,outcome] of [...["outline","extended","search","filtered-search","resolve"].map(route=>[route,"symbols-correct","pass"]),
  ["extended","symbols-missing-inherited","incorrect"],["extended","symbols-wrong-inherited-uri","incorrect"]])test(`actual ${route} symbol runner checks ${mode}`,()=>{
  const caseId=`NAV-03/${route}-first-repeat`,r=runFake(mode,caseId,{server:"jdtls"});
  try{
    assert.equal(r.status,outcome==="pass"?0:1,r.log);const result=r.case();assert.equal(result.outcome,outcome);
    if(route==="resolve")assert(result.operations.filter((o:any)=>o.endpoint==="java.project.resolveWorkspaceSymbol").every((o:any)=>o.originRequestId===result.operations[0].requestId));
  }finally{r.cleanup();}
});
test("symbol resolve rejects forged and stale original items before sending",async()=>{
  const tmp=mkdtempSync(path.join(os.tmpdir(),"symbol-origin-"));
  try{
    let requests=0;const client={events:[],journal:()=>{},notify:()=>process.hrtime.bigint(),request:async()=>({id:++requests,result:rows,startNs:"1",endNs:"2"})} as any;
    const c=new ScenarioContext(client,createFixture(tmp,SYMBOL_SOURCES),"jdtls",100,1,1);await c.open("SearchCase.java");
    const original=await c.query("workspace/symbol",{},()=>{});
    await assert.rejects(c.execute("java.project.resolveWorkspaceSymbol",[workspaceSymbolArgument({...original[0],name:"forged"})],()=>{}));
    await c.execute("java.project.resolveWorkspaceSymbol",[workspaceSymbolArgument(original[0])],()=>{});
    c.change("SearchCase.java",source+"\n");await assert.rejects(c.execute("java.project.resolveWorkspaceSymbol",[workspaceSymbolArgument(original[0])],()=>{}));assert.equal(requests,2);
  }finally{rmSync(tmp,{recursive:true,force:true});}
});
for(const mutation of ["new","renamed"])test(`symbol ${mutation} keeps a stale immediate result after a successful retry`,async()=>{
  const tmp=mkdtempSync(path.join(os.tmpdir(),"symbol-transition-"));
  try{
    const fixture=createFixture(tmp,SYMBOL_SOURCES);let count=0,context:ScenarioContext;const phases:string[]=[];
    const client={events:[],journal:()=>{},notify:()=>process.hrtime.bigint(),request:async()=>{
      const text=++count===2?source:context.text("SearchCase.java"),time=String(process.hrtime.bigint());phases.push("request");
      return {id:count,startNs:time,endNs:time,result:symbolDeclarations(text,fixture.files["SearchCase.java"].uri).map(t=>({name:t.name,kind:t.kind,location:{uri:t.uri,range:t.selection}}))};
    }} as any;
    context=new ScenarioContext(client,fixture,"jdtls",100,1,1);context.compileOracle=()=>{phases.push("compiler");};
    await symbolCases.find(c=>c.id===`NAV-03/search-${mutation}`)!.run(context);
    assert.equal(context.operations[1].state,"changed_immediate");assert.equal(context.operations[1].outcome,"stale");
    assert.equal(context.operations.at(-1).state,"changed_retry");assert.equal(context.operations.at(-1).outcome,"pass");
    assert.equal(phases.at(-1),"compiler");assert.equal(context.mutations.length,1);assert(context.assertions.every(a=>a.passed));
  }finally{rmSync(tmp,{recursive:true,force:true});}
});
