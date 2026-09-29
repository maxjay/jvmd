import {test} from "node:test";
import assert from "node:assert/strict";
import {mkdtempSync,rmSync} from "node:fs";
import os from "node:os";
import path from "node:path";
import {ScenarioContext} from "../harness/ScenarioContext.ts";
import {createFixture} from "../harness/fixture.ts";
import {diagnosticCases} from "../scenarios/diagnostics.ts";
import {observeDiagnostics} from "../harness/diagnosticObserver.ts";
import {diagnosticPolicy,DIAGNOSTIC_SOURCES,DIAGNOSTIC_CALLER} from "../harness/diagnostics.ts";
import {diagnosticCompilerOracle} from "../harness/diagnosticCompiler.ts";
import {range} from "../harness/oracles.ts";

const mismatch={severity:1,message:"Type mismatch: cannot convert from String to int",range:range(DIAGNOSTIC_CALLER,"p.number()")};
const compilerWitness={before:{status:0,signal:null,error:"",stderr:""},after:{status:1,signal:null,error:"",stderr:"DiagnosticCaller.java:2:89: compiler.err.prob.found.req: (compiler.misc.inconvertible.types: java.lang.String, int)\n1 error\n"}};
function setup(mode="correct",timeout=1000){
  const root=mkdtempSync(path.join(os.tmpdir(),"diagnostic-observer-")),fixture=createFixture(root,DIAGNOSTIC_SOURCES),uri=fixture.files["DiagnosticCaller.java"].uri;
  fixture.preparation={status:"verified",diagnosticPolicy:diagnosticPolicy(timeout),witness:structuredClone(compilerWitness)};
  const events:any[]=[],notifications:any[]=[],processLifecycle:any[]=[],journals:any[]=[];
  const add=(direction:string,message:any)=>{const timeNs=String(process.hrtime.bigint());const e={schemaVersion:1,clockDomain:"client",sequence:events.length+1,timeNs,direction,message};events.push(e);return e;};
  const publish=(diagnostics:any[]=[],extra:any={})=>{const params={uri,version:1,diagnostics,...extra},e=add("receive",{jsonrpc:"2.0",method:"textDocument/publishDiagnostics",params});notifications.push({params,method:e.message.method,timeNs:e.timeNs,sequence:e.sequence});};
  const client:any={events,notifications,processLifecycle,journal:(name:string,row:any)=>journals.push({name,row}),notify:(method:string,params:any)=>{
    const event=add("send",{jsonrpc:"2.0",method,params});
    if(method==="textDocument/didOpen"&&params.textDocument.uri===uri){
      if(mode==="timeout"||mode==="exit")return BigInt(event.timeNs);
      if(mode==="versionless"){publish([],{version:undefined});return BigInt(event.timeNs);}
      publish([],{uri:"file:///unrelated.java"});publish([],{version:0});publish();
    }
    if(method==="textDocument/didChange"){
      if(mode==="limit")for(let i=0;i<1000;i++)publish();
      else{publish();publish([{...mismatch,severity:2}]);publish([{...mismatch,...(mode==="wrong-range"?{range:range(DIAGNOSTIC_CALLER,"value")}: {})}]);if(mode==="wrong-range")publish([mismatch]);}
    }
    return BigInt(event.timeNs);
  },notification:async()=>{
    if(mode==="exit"){processLifecycle.push({event:"process_exit",timeNs:String(process.hrtime.bigint()),code:1});throw new Error("server exited while waiting for textDocument/publishDiagnostics");}
    await new Promise(resolve=>setTimeout(resolve,timeout+2));throw new Error("notification timeout: textDocument/publishDiagnostics");
  }};
  const c=new ScenarioContext(client,fixture,"jdtls",timeout,1,1);let compiled=false;c.compileOracle=()=>{compiled=true;};
  const report=()=>({preparation:fixture.preparation,diagnosticObservations:c.diagnosticObservations,processLifecycle});
  return {c,events,notifications,journals,report,compiled:()=>compiled,close:()=>rmSync(root,{recursive:true,force:true})};
}
test("valid scenario links every examined publication and compiles after admission",async()=>{
  const s=setup();try{
    await diagnosticCases[0].run(s.c);assert(s.compiled());assert.equal(s.c.operations.length,1);assert.equal(s.c.operations[0].outcome,"pass");
    assert.deepEqual(s.c.diagnosticObservations.filter(r=>r.event==="publication").map(r=>r.decision.kind),["ignore","ignore","pass"]);
    
    assert.equal(s.c.operations[0].latencyMs,undefined);
  }finally{s.close();}
});
test("provider-only scenario retains pending publications and admits exactly one positive witness",async()=>{
  const s=setup();try{
    await diagnosticCases[1].run(s.c);assert(!s.compiled());assert.equal(s.c.operations.length,2);
    assert(s.c.assertions.every(a=>a.passed));
    assert.deepEqual(s.c.diagnosticObservations.filter(r=>r.event==="publication").slice(-3).map(r=>r.decision.kind),["pending_provider_generation","pending_provider_generation","pass"]);
  }finally{s.close();}
});
test("wrong current-version error stays incorrect even if a later publication is correct",async()=>{
  const s=setup("wrong-range");try{
    await assert.rejects(diagnosticCases[1].run(s.c));assert.equal(s.c.operations.at(-1).outcome,"incorrect");
    assert.equal(s.c.operations.at(-1).notificationEventId,s.notifications.at(-2).sequence);
    
  }finally{s.close();}
});
for(const [mode,outcome] of [["timeout","timeout"],["versionless","unavailable_evidence"],["exit","protocol_error"]])test(`observer preserves ${mode} as ${outcome}`,async()=>{
  const s=setup(mode,5);try{
    await assert.rejects(diagnosticCases[0].run(s.c));assert.equal(s.c.operations[0].outcome,outcome);
    
  }finally{s.close();}
});
test("publication exhaustion creates an explicit failed operation and bounded journal",async()=>{
  const s=setup("limit");try{
    await assert.rejects(diagnosticCases[1].run(s.c));assert.equal(s.c.operations.at(-1).outcome,"unavailable_evidence");
    assert.equal(s.c.diagnosticObservations.at(-1).reason,"publication_limit");assert.equal(s.c.diagnosticObservations.at(-1).publicationCount,1000);
    
  }finally{s.close();}
});
test("a timely receipt remains admissible when observer scheduling resumes after deadline",async()=>{
  const s=setup("correct",5);try{
    const opened=await s.c.open("DiagnosticCaller.java");await new Promise(resolve=>setTimeout(resolve,10));
    await observeDiagnostics(s.c,{kind:"valid",uri:opened.uri,version:1,incarnation:1,source:opened.text,triggerNs:String(opened.trigger)},0,"valid");
    
  }finally{s.close();}
});
test("independent compiler witness rejects wrong causes, missing baseline and tool failure",()=>{
  diagnosticCompilerOracle(compilerWitness);
  for(const mutate of [(w:any)=>w.before.status=1,(w:any)=>w.after.status=0,(w:any)=>w.after.signal="SIGKILL",(w:any)=>w.after.error="ENOENT",
    (w:any)=>w.after.stderr=w.after.stderr.replace("String","Object"),(w:any)=>w.after.stderr+="compiler.err.other"]){
    const w=structuredClone(compilerWitness);mutate(w);assert.throws(()=>diagnosticCompilerOracle(w));
  }
});
