import {test} from "node:test";
import assert from "node:assert/strict";
import {mkdtempSync,mkdirSync,readFileSync,writeFileSync,rmSync} from "node:fs";
import os from "node:os";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {createFixture} from "../harness/fixture.ts";
import {ScenarioContext,SETTINGS} from "../harness/ScenarioContext.ts";
import {buildParams,buildRuntimeOracle,buildDiagnosticOracle,BUILD_SOURCE,BUILD_ERROR,BUILD_PEER,type BuildScope,type BuildVariant} from "../harness/builds.ts";
import {runBuildCase} from "../scenarios/builds.ts";
import {range} from "../harness/oracles.ts";

function setup(variant:BuildVariant,mode="correct"){
  const tmp=mkdtempSync(path.join(os.tmpdir(),"build-scope-")),fixture=createFixture(path.join(tmp,"primary"),{"BuildProbe.java":variant==="error"?BUILD_ERROR:BUILD_SOURCE},"src"),peer=createFixture(path.join(tmp,"peer"),{"BuildPeer.java":BUILD_PEER},"src");
  fixture.settings=structuredClone(SETTINGS);fixture.settings.java.autobuild.enabled=false;fixture.preparation={status:"verified",peer};
  const notifications:any[]=[],events:any[]=[],calls:any[]=[],order:string[]=[];let next=0;
  const emit=(direction:string,message:any)=>{const e={sequence:events.length+1,timeNs:String(process.hrtime.bigint()),direction,message};events.push(e);return e;};
  const client:any={events,notifications,journal:()=>{},notify:(method:string,params:any)=>BigInt(emit("send",{method,params}).timeNs),
    request:async(method:string,params:any)=>{
      const id=++next,start=emit("send",{id,method,params});calls.push({method,params});order.push("build");
      const roots=method==="java/buildWorkspace"||mode==="wrong-scope"?[fixture.root,peer.root]:params.identifiers.map((p:any)=>fileURLToPath(p.uri));
      let error=false;
      for(const root of roots){
        const project=root===fixture.root?fixture:peer,name=root===fixture.root?"BuildProbe":"BuildPeer",file=project.files[name+".java"],source=readFileSync(file.path,"utf8"),token=/missing\w+Value/u.exec(source)?.[0];
        if(token){error=true;const params={uri:file.uri,diagnostics:[{severity:1,message:token+" cannot be resolved",range:range(source,token)}]};const e=emit("receive",{method:"textDocument/publishDiagnostics",params});notifications.push({params,method:e.message.method,timeNs:e.timeNs,sequence:e.sequence});}
        else{mkdirSync(path.join(root,"bin/bench"),{recursive:true});const value=Number(/return (\d+);/u.exec(source)![1]);writeFileSync(path.join(root,"bin/bench/"+name+".class"),String((mode==="stale-output"||mode==="stale-once"&&next===2)&&value===13?7:value));}
      }
      const result=mode==="wrong-status"?1:error?2:1,end=emit("receive",{id,result});return {id,startNs:start.timeNs,endNs:end.timeNs,result};
    },notification:async(method:string,predicate:any)=>{const n=notifications.find(n=>n.method===method&&predicate(n.params));assert(n,"expected build diagnostic absent");return n;}};
  const c=new ScenarioContext(client,fixture,"jdtls",1000,1,1),output=(_c:any,root:string,name:string,value:number)=>{order.push("validate_output");assert.equal(Number(readFileSync(path.join(root,"bin/bench/"+name+".class"),"utf8")),value,"stale compiled output");};
  return {c,calls,order,output,close:()=>rmSync(tmp,{recursive:true,force:true})};
}
for(const scope of ["workspace","projects"] as const)for(const variant of ["full","unchanged","changed","error"] as const)test(`independent ${scope} ${variant} build checks output and both scope controls`,async()=>{
  const s=setup(variant);try{
    await runBuildCase(s.c,scope,variant,s.output);assert(s.c.operations.every(o=>o.outcome==="pass"));assert(s.c.assertions.every(a=>a.passed));
    const operations=s.c.operations,at=operations.findIndex(o=>o.state===(variant==="changed"?"changed_immediate":variant+"_build"));assert(at>=0);
    assert.equal(at,variant==="unchanged"||variant==="changed"?1:0);
    assert.deepEqual(operations.slice(at+(variant==="changed"?2:1)).map(o=>o.state),["scope_control_excludes_error","scope_control_includes_error"]);
    assert.equal(s.calls[at].method,scope==="workspace"?"java/buildWorkspace":"java/buildProjects");
    assert.deepEqual(s.calls[at].params,buildParams(scope,s.c.fixture.root,variant==="full"||variant==="error"));
    assert.equal(s.order[0],"build");assert(s.c.assertions.some(a=>a.name==="build status agrees with exact source diagnostic"));
    assert.equal(s.c.mutations.filter(m=>m.kind==="external_write").length,variant==="changed"?1:0);
  }finally{s.close();}
});
for(const [scope,variant,mode] of [["projects","full","wrong-scope"],["workspace","changed","stale-output"],["projects","error","wrong-status"]] as [BuildScope,BuildVariant,string][])test(`build runner rejects ${mode}`,async()=>{
  const s=setup(variant,mode);try{
    await assert.rejects(runBuildCase(s.c,scope,variant,s.output));assert(s.c.operations.some(o=>o.outcome==="incorrect"));
  }finally{s.close();}
});
test("compiled class behaviour rejects stale, foreign and failed output",()=>{
  const good={status:0,signal:null,error:"",stdout:"13\n"};buildRuntimeOracle(good,13);
  for(const wrong of [{...good,stdout:"7\n"},{...good,stdout:"73\n"},{...good,status:1},{...good,signal:"SIGKILL"},{...good,error:"ENOENT"}])assert.throws(()=>buildRuntimeOracle(wrong,13));
});
test("build diagnostic requires the unique error in the selected file and exact token span",()=>{
  const uri="file:///BuildProbe.java",d={severity:1,message:"missingBuildValue cannot be resolved",range:range(BUILD_ERROR,"missingBuildValue")},good={uri,diagnostics:[d]};
  buildDiagnosticOracle(good,uri,BUILD_ERROR,"missingBuildValue");
  for(const wrong of [{...good,uri:"file:///Peer.java"},{uri,diagnostics:[]},{uri,diagnostics:[d,d]},{uri,diagnostics:[{...d,range:range(BUILD_ERROR,"value")}]},{uri,diagnostics:[{...d,message:"unrelated error"}]}])assert.throws(()=>buildDiagnosticOracle(wrong,uri,BUILD_ERROR,"missingBuildValue"));
});

for(const scope of ["workspace","projects"] as const)test(`changed ${scope} build retains stale immediate output after successful recovery`,async()=>{
  const s=setup("changed","stale-once");try{
    await runBuildCase(s.c,scope,"changed",s.output);
    const first=s.c.operations.find(o=>o.state==="changed_immediate"),settled=s.c.operations.find(o=>o.state==="changed_settled");
    assert.equal(first.outcome,"incorrect");assert.equal(settled.outcome,"pass");
    assert(s.c.operations.find(o=>o.state==="scope_control_excludes_error"));
    assert.equal(s.c.seriesExpectations[0].attemptCount,2);assert.equal(s.c.seriesExpectations[0].termination,"settled");
  }finally{s.close();}
});
