import {test} from "node:test";
import assert from "node:assert/strict";
import {mkdtempSync,writeFileSync,rmSync} from "node:fs";
import path from "node:path";
import os from "node:os";
import {createFixture,sha} from "../harness/fixture.ts";
import {ScenarioContext} from "../harness/ScenarioContext.ts";
import {SYMBOL_SOURCES,symbolDeclarations} from "../harness/symbols.ts";
import {FOREIGN_SYMBOL,exactFilteredSymbols} from "../harness/symbolFilters.ts";
import {symbolFilterCases} from "../scenarios/symbols.ts";
const primary=symbolDeclarations(SYMBOL_SOURCES["SearchCase.java"],"file:///primary/SearchCase.java"),foreign=symbolDeclarations(FOREIGN_SYMBOL,"file:///secondary/Foreign.java");
const rows=(declarations:any[])=>declarations.map(d=>({name:d.name,kind:d.kind,location:{uri:d.uri,range:d.selection}}));
const binary={name:"BenchmarkBinary",kind:5,location:{uri:"jdt://contents/benchmark-symbols-1.jar/binary/BenchmarkBinary.class?opaque",range:{start:{line:0,character:0},end:{line:0,character:0}}}};
test("filter oracle independently excludes another project and a present binary",()=>{
  exactFilteredSymbols([...rows(primary),...rows(foreign),binary],{sources:[...primary,...foreign],binary:true});
  exactFilteredSymbols(rows(primary),{sources:primary,binary:false});
  assert.throws(()=>exactFilteredSymbols([...rows(primary),...rows(foreign)],{sources:primary,binary:false}));
  assert.throws(()=>exactFilteredSymbols([...rows(primary),binary],{sources:primary,binary:false}));
  assert.throws(()=>exactFilteredSymbols(rows(primary),{sources:[...primary,...foreign],binary:false}));
  assert.throws(()=>exactFilteredSymbols([...rows(primary),{...binary,location:{...binary.location,uri:binary.location.uri.replace("/binary/","/unrelated/")}}],{sources:primary,binary:true}));
});
test("limit control accepts any valid one of several matches, never empty, duplicate or unbounded replies",()=>{
  const expected={sources:[...primary,...foreign],binary:false,limit:1};
  for(const row of rows(expected.sources))exactFilteredSymbols([row],expected);
  for(const wrong of [[],rows(primary),[rows(primary)[0],rows(primary)[0]],[binary]])assert.throws(()=>exactFilteredSymbols(wrong,expected));
  assert.throws(()=>exactFilteredSymbols(rows(foreign),{sources:foreign,binary:false,limit:1}),/too few matching/u);
});
for(const server of ["jvmd","jdtls"])test(`all declared workspace folders agree across ${server} initialize and server callback`,async()=>{
  const tmp=mkdtempSync(path.join(os.tmpdir(),"symbol-folders-"));
  try{
    const fixture=createFixture(path.join(tmp,"fixture"),path.join(tmp,"repo"));fixture.workspaceFolders=[{uri:"file:///first",name:"first"},{uri:"file:///second",name:"second"}];
    const calls:any[]=[],client={notify:()=>{},notification:async()=>({}),request:async(method:string,params:any)=>{calls.push({method,params});return {result:{capabilities:{}},endNs:"1"};}} as any;
    const c=new ScenarioContext(client,fixture,server,100,1,1);await c.initialize();
    assert.deepEqual(calls[0].params.initializationOptions.workspaceFolders,server==="jdtls"?fixture.workspaceFolders.map(f=>f.uri):undefined);
    assert.deepEqual(calls[0].params.workspaceFolders,fixture.workspaceFolders);assert.deepEqual(await client.onServerRequest("workspace/workspaceFolders",{}),fixture.workspaceFolders);
  }finally{rmSync(tmp,{recursive:true,force:true});}
});
for(const variant of ["first-repeat","new","renamed"])test(`actual filter scenario validates all controls after ${variant} semantic probes`,async()=>{
  const tmp=mkdtempSync(path.join(os.tmpdir(),"filter-scenario-"));
  try{
    const fixture=createFixture(path.join(tmp,"fixture"),path.join(tmp,"repo"),SYMBOL_SOURCES),other=createFixture(path.join(tmp,"other"),path.join(tmp,"repo"),{"Foreign.java":FOREIGN_SYMBOL},{base:false});
    const jar=path.join(tmp,"test-only-jar");writeFileSync(jar,"synthetic test bytes");
    fixture.preparation={kind:"symbol-filter-controls",other:{sources:other.files},binary:{jar,jarSha256:sha("synthetic test bytes")}};
    let c:ScenarioContext;const requests:any[]=[];
    const client={events:[],journal:()=>{},notify:()=>process.hrtime.bigint(),request:async(method:string,params:any)=>{
      requests.push({method,params});const declared=symbolDeclarations(c.text("SearchCase.java"),fixture.files["SearchCase.java"].uri),foreign=symbolDeclarations(FOREIGN_SYMBOL,other.files["Foreign.java"].uri);
      const matches=[...rows(params.projectName==="benchmark_secondary"?[]:declared),...rows(params.projectName==="benchmark"?[]:foreign),...(!params.sourceOnly&&params.projectName!=="benchmark_secondary"?[binary]:[])];
      const time=String(process.hrtime.bigint());return {id:requests.length,startNs:time,endNs:time,result:matches.slice(0,params.maxResults)};
    }} as any;
    c=new ScenarioContext(client,fixture,"jdtls",100,1,2);let compiled=false;c.compileOracle=()=>{compiled=true;};
    await symbolFilterCases.find(d=>d.id===`NAV-03/filter-controls-${variant}`)!.run(c);
    assert(c.operations.every(o=>o.outcome==="pass"));
    const controls=c.operations.filter(o=>o.state.startsWith("filter_control_"));assert.equal(controls.length,12);
    assert(c.operations.indexOf(controls[0])>c.operations.findIndex(o=>variant==="first-repeat"?o.state==="steady":/^changed_/u.test(o.state)),"controls warmed the measured semantic probe");
    assert.equal(compiled,variant!=="first-repeat");assert(c.assertions.some(a=>a.name==="project source and limit filters have present excluded candidates"&&a.passed));
    const positive=controls.find(o=>o.state==="filter_control_all");assert(positive.rawResult.some((r:any)=>r.name==="BenchmarkForeign"));assert(positive.rawResult.some((r:any)=>r.name==="BenchmarkBinary"));
    assert(controls.filter(o=>o.state.includes("limit")).every(o=>!o.freshness.witness),"arbitrary limited subset was used to claim fresh mutation readiness");
  }finally{rmSync(tmp,{recursive:true,force:true});}
});
