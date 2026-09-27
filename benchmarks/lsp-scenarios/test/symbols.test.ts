import {test} from "node:test";
import assert from "node:assert/strict";
import {mkdtempSync,readFileSync,writeFileSync,rmSync} from "node:fs";
import {spawnSync} from "node:child_process";
import os from "node:os";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {SYMBOL_SOURCES,symbolDeclarations,exactSearchSymbols,exactSearchOutline} from "../harness/symbols.ts";
import {ScenarioContext} from "../harness/ScenarioContext.ts";
import {createFixture} from "../harness/fixture.ts";
import {symbolCases} from "../scenarios/symbols.ts";
import {validateCase} from "../reduce.ts";
import {range} from "../harness/oracles.ts";

const uri="file:///fixture/SearchCase.java",parentUri="file:///fixture/SymbolParent.java",source=SYMBOL_SOURCES["SearchCase.java"];
const types=symbolDeclarations(source,uri),rows=types.map(t=>({name:t.name,kind:t.kind,location:{uri:t.uri,range:t.selection}}));
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
});
for(const [route,mode,outcome] of [...["outline","extended","search","filtered-search","resolve"].map(route=>[route,"symbols-correct","pass"]),
  ["extended","symbols-missing-inherited","incorrect"],["extended","symbols-wrong-inherited-uri","incorrect"]])test(`actual ${route} symbol runner checks ${mode}`,()=>{
  const tmp=mkdtempSync(path.join(os.tmpdir(),"jvmd-symbols-"));
  try{
    const command=path.join(tmp,"command.json"),output=path.join(tmp,"run"),caseId=`NAV-03/${route}-first-repeat`;
    writeFileSync(command,JSON.stringify([process.execPath,fileURLToPath(new URL("./fake-server.ts",import.meta.url)),mode]));
    const run=spawnSync(process.execPath,[fileURLToPath(new URL("../run.ts",import.meta.url)),"--servers","jdtls","--profile","custom","--command-json",command,"--output",output,"--only",caseId,"--warmup","1","--samples","2"],{encoding:"utf8",timeout:15000});
    assert.equal(run.error,undefined,run.stderr);assert.equal(run.status,outcome==="pass"?0:1,run.stdout+run.stderr);
    const summary=JSON.parse(readFileSync(path.join(output,"summary.json"),"utf8"));assert.equal(summary.outcomes[outcome],1);if(outcome==="pass")assert.deepEqual(summary.integrityIssues,[]);
    if(route==="resolve"){
      const directory=path.join(output,"01-jdtls-"+caseId.replaceAll("/","-"));
      const report=JSON.parse(readFileSync(path.join(directory,"report.json"),"utf8")),lines=(name:string)=>readFileSync(path.join(directory,name+".jsonl"),"utf8").trim().split("\n").map(l=>JSON.parse(l));
      assert(report.operations.filter((o:any)=>o.endpoint==="java.project.resolveWorkspaceSymbol").every((o:any)=>o.originRequestId===report.operations[0].requestId));
      const forged=structuredClone(report.operations);forged.at(-1).originRequestId=999999;
      assert(validateCase(report,lines("events"),lines("exchanges"),forged).includes("workspace symbol item provenance mismatch"));
    }
  }finally{rmSync(tmp,{recursive:true,force:true});}
});
test("symbol resolve rejects forged and stale original items before sending",async()=>{
  const tmp=mkdtempSync(path.join(os.tmpdir(),"symbol-origin-"));
  try{
    let requests=0;const client={events:[],journal:()=>{},notify:()=>process.hrtime.bigint(),request:async()=>({id:++requests,result:rows,startNs:"1",endNs:"2"})} as any;
    const c=new ScenarioContext(client,createFixture(tmp,SYMBOL_SOURCES),"jdtls",100,1,1);await c.open("SearchCase.java");
    const original=await c.query("workspace/symbol",{},()=>{});
    await assert.rejects(c.execute("java.project.resolveWorkspaceSymbol",[JSON.stringify({...original[0],name:"forged"})],()=>{}));
    c.change("SearchCase.java",source+"\n");await assert.rejects(c.execute("java.project.resolveWorkspaceSymbol",[JSON.stringify(original[0])],()=>{}));assert.equal(requests,1);
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
    assert.equal(context.operations[1].state,"changed_immediate");assert.equal(context.operations[1].outcome,"incorrect");
    assert.equal(context.operations.at(-1).state,"changed_settled");assert.equal(context.operations.at(-1).outcome,"pass");
    assert.equal(phases.at(-1),"compiler");assert.equal(context.mutations.length,1);assert(context.assertions.every(a=>a.passed));
  }finally{rmSync(tmp,{recursive:true,force:true});}
});
