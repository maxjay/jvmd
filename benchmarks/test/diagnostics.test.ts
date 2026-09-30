import {test} from "node:test";
import assert from "node:assert/strict";
import {mkdtempSync,rmSync} from "node:fs";
import os from "node:os";
import path from "node:path";
import {ScenarioContext} from "../harness/ScenarioContext.ts";
import {createFixture} from "../harness/fixture.ts";
import {diagnosticCases} from "../scenarios/diagnostics.ts";
import {admission,DIAGNOSTIC_SOURCES,DIAGNOSTIC_CALLER} from "../harness/diagnostics.ts";
import {range} from "../harness/oracles.ts";

const mismatch={severity:1,message:"Type mismatch: cannot convert from String to int",range:range(DIAGNOSTIC_CALLER,"p.number()")};
/** A scripted client: publications arrive on didOpen/didChange according to mode. */
function setup(mode="correct",timeout=1000){
  const root=mkdtempSync(path.join(os.tmpdir(),"diagnostics-")),fixture=createFixture(path.join(root,"case","fixture"),path.join(root,"repo"),DIAGNOSTIC_SOURCES),uri=fixture.files["DiagnosticCaller.java"].uri;
  const notifications:any[]=[];const version=1;let sent=0n;
  // Publications are timestamped after the notification that caused them, as on the wire.
  const publish=(diagnostics:any[]=[],extra:any={})=>notifications.push({method:"textDocument/publishDiagnostics",params:{uri,version,diagnostics,...extra},timeNs:String(sent+BigInt(notifications.length+1)),sequence:notifications.length+1});
  const client:any={notifications,events:[],journal:()=>{},notify:(method:string,params:any)=>{sent=process.hrtime.bigint();
    if(method==="textDocument/didOpen"&&params.textDocument.uri===uri){
      if(mode==="versionless")publish([],{version:undefined});
      else if(mode!=="timeout"){publish([],{uri:"file:///unrelated.java"});publish([],{version:0});publish();}
    }
    if(method==="textDocument/didChange"){publish();publish([{...mismatch,severity:2}]);publish([mode==="wrong-range"?{...mismatch,range:range(DIAGNOSTIC_CALLER,"value")}:mismatch]);}
    return sent;
  },notification:async()=>{await new Promise(r=>setTimeout(r,timeout+2));throw new Error("notification timeout");}};
  const c=new ScenarioContext(client,fixture,"jdtls",timeout,1,1);c.compileOracle=()=>{};
  return {c,close:()=>rmSync(root,{recursive:true,force:true})};
}
test("admission only accepts the exact current version of the observed document",()=>{
  assert.deepEqual(admission("unknown",{uri:"a",version:3},"a",3),{kind:"current",mode:"versioned"});
  assert.equal(admission("unknown",{uri:"a",version:2},"a",3).kind,"ignore");
  assert.equal(admission("unknown",{uri:"b",version:3},"a",3).kind,"ignore");
  assert.deepEqual(admission("unknown",{uri:"a"},"a",3),{kind:"unversioned",mode:"versionless"});
  assert.equal(admission("versioned",{uri:"a"},"a",3).kind,"ignore");
});
test("valid source passes on the current-version empty publication",async()=>{
  const s=setup();try{await diagnosticCases[0].run(s.c);assert.deepEqual(s.c.operations.map(o=>o.outcome),["pass"]);}finally{s.close();}
});
test("provider edit waits past empty and warning-only publications for the exact mismatch",async()=>{
  const s=setup();try{await diagnosticCases[1].run(s.c);assert.deepEqual(s.c.operations.map(o=>o.outcome),["pass","pass"]);assert(s.c.operations[1].transitionMs>=0);}finally{s.close();}
});
for(const [mode,outcome] of [["wrong-range","incorrect"],["timeout","timeout"],["versionless","unavailable_evidence"]])test(`${mode} is reported as ${outcome}`,async()=>{
  const s=setup(mode,20);try{await assert.rejects(diagnosticCases[mode==="wrong-range"?1:0].run(s.c));assert.equal(s.c.operations.at(-1).outcome,outcome);}finally{s.close();}
});
