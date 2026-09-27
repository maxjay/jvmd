import test from "node:test";
import assert from "node:assert/strict";
import {classifyDiagnostic} from "../harness/diagnostics.ts";
import {modeDiagnosticOracle,modeSource,modeCompilerOracle,validateModeTrigger,LOOSE_VALID,LOOSE_SEMANTIC,LOOSE_SYNTAX,COMPILER_SOURCE,type ModeKind} from "../harness/modeDiagnostics.ts";
import {range} from "../harness/oracles.ts";
import {sha} from "../harness/fixture.ts";
import {createFixture} from "../harness/fixture.ts";
import {ScenarioContext} from "../harness/ScenarioContext.ts";
import {diagnosticCases} from "../scenarios/diagnostics.ts";
import {modeDiagnosticPolicy} from "../harness/modeDiagnostics.ts";
import {validateDiagnosticObservations} from "../harness/diagnosticObserver.ts";
import {mkdtempSync,rmSync,readFileSync} from "node:fs";
import os from "node:os";
import path from "node:path";
import {addSource} from "../harness/projectScope.ts";
const diagnostic=(source:string,token:string,message:string)=>({severity:1,range:range(source,token),message});
const semantic=diagnostic(LOOSE_SEMANTIC,"uniqueModeMissing","uniqueModeMissing cannot be resolved"),syntax=diagnostic(LOOSE_SYNTAX,";", "Syntax error");
syntax.range=range(LOOSE_SYNTAX,";",LOOSE_SYNTAX.indexOf("1 +"));
const record=diagnostic(COMPILER_SOURCE,"record","Records require source level 16");
test("loose modes distinguish suppressed semantic errors from positive syntax and full-mode errors",()=>{
  assert.equal(modeDiagnosticOracle("loose_suppressed",LOOSE_SEMANTIC,[]),"pass");
  assert.equal(modeDiagnosticOracle("loose_semantic",LOOSE_SEMANTIC,[semantic]),"pass");
  assert.equal(modeDiagnosticOracle("loose_syntax",LOOSE_SYNTAX,[syntax]),"pass");
  assert.throws(()=>modeDiagnosticOracle("loose_suppressed",LOOSE_SEMANTIC,[semantic]));
  assert.throws(()=>modeDiagnosticOracle("loose_semantic",LOOSE_SEMANTIC,[]));
  assert.throws(()=>modeDiagnosticOracle("loose_syntax",LOOSE_SYNTAX,[]));
  assert.throws(()=>modeDiagnosticOracle("loose_semantic",LOOSE_SEMANTIC,[{...semantic,range:range(LOOSE_SEMANTIC,"value")} ]));
});
test("changed compiler configuration requires a positive new language rejection, not the unchanged source version",()=>{
  assert.equal(modeDiagnosticOracle("compiler_valid",COMPILER_SOURCE,[]),"pass");
  assert.equal(modeDiagnosticOracle("compiler_rejected",COMPILER_SOURCE,[]),"pending_provider_generation");
  assert.equal(modeDiagnosticOracle("compiler_rejected",COMPILER_SOURCE,[record]),"pass");
  assert.throws(()=>modeDiagnosticOracle("compiler_valid",COMPILER_SOURCE,[record]));
  assert.throws(()=>modeDiagnosticOracle("compiler_rejected",COMPILER_SOURCE,[{...record,range:range(COMPILER_SOURCE,"bench")} ]));
});
test("mode diagnostics preserve exact-version admission, selected ranges and failure disposition",()=>{
  const kind:ModeKind="loose_semantic",exp={kind,source:modeSource(kind),uri:"file:///fixture/loose/bench/LooseMode.java",version:2,incarnation:1,triggerNs:"100",deadlineNs:"200"};
  const pub={timeNs:"150",params:{uri:exp.uri,version:2,diagnostics:[semantic]}};
  assert.equal(classifyDiagnostic(exp,pub,"unknown").kind,"pass");
  assert.equal(classifyDiagnostic(exp,{...pub,params:{...pub.params,version:undefined}},"unknown").kind,"unavailable");
  assert.equal(classifyDiagnostic(exp,{...pub,params:{...pub.params,version:1}},"unknown").kind,"ignore");
  assert.equal(classifyDiagnostic(exp,{...pub,params:{...pub.params,diagnostics:[]}},"unknown").kind,"incorrect");
});
const compilerRow=(source:string,status:number,stderr="",release="17")=>({source,sourceSha256:sha(source),status,signal:null,error:"",stderr,command:["javac","--release",release]});
const looseWitness=()=>({valid:compilerRow(LOOSE_VALID,0),semantic:compilerRow(LOOSE_SEMANTIC,1,"compiler.err.cant.resolve: uniqueModeMissing"),syntax:compilerRow(LOOSE_SYNTAX,1,"compiler.err.illegal.start.of.expr")});
test("mode compiler evidence rejects wrong source, unexpected failures and fabricated release settings",()=>{
  modeCompilerOracle(looseWitness(),"loose");
  for(const change of [(w:any)=>w.semantic.status=0,(w:any)=>w.semantic.source="class Different {}",(w:any)=>w.syntax.stderr+=" compiler.err.unrelated",(w:any)=>w.valid.command[2]="11"]){const w=looseWitness();change(w);assert.throws(()=>modeCompilerOracle(w,"loose"));}
});
test("artifact-only mode trigger validation requires off-classpath source and acknowledged exact mode selection",()=>{
  const uri="file:///fixture/loose/bench/LooseMode.java",exp={kind:"loose_suppressed",uri,source:LOOSE_SEMANTIC,version:2};
  const event=(sequence:number,direction:string,message:any)=>({sequence,direction,message});
  const events=[event(1,"send",{method:"textDocument/didOpen",params:{textDocument:{uri,text:LOOSE_VALID}}}),
    event(2,"send",{id:7,method:"workspace/executeCommand",params:{command:"java.project.refreshDiagnostics",arguments:[uri,"thisFile",true,true]}}),
    event(3,"receive",{id:7,result:null}),event(4,"send",{method:"textDocument/didChange",params:{textDocument:{uri}}})];
  const report={preparation:{kind:"diagnostic-mode",syntaxOnly:true,witness:looseWitness(),initialInputs:{sourceUri:uri,classpath:'<classpathentry kind="src" path="src"/>',preferences:["compliance","source","codegen.targetPlatform"].map(k=>"org.eclipse.jdt.core.compiler."+k+"=17\n").join("")}}};
  validateModeTrigger(exp,events[3],events,new Map(),report);
  assert.throws(()=>validateModeTrigger(exp,events[3],events.filter(e=>e.sequence!==3),new Map(),report));
  const altered=structuredClone(report);altered.preparation.initialInputs.classpath='<classpathentry kind="src" path=""/>';
  assert.throws(()=>validateModeTrigger(exp,events[3],events,new Map(),altered));
  const wrong=structuredClone(events);wrong[1].message.params.arguments[2]=false;
  assert.throws(()=>validateModeTrigger(exp,wrong[3],wrong,new Map(),report));
});

for(const id of ["DIA-01/syntax-only","DIA-01/full","PRJ-02/compiler-change","PRJ-02/compiler-change-versionless"])test("real mode scenario and raw observer replay: "+id,async()=>{
  const versionless=id.endsWith("-versionless"),caseId=id.replace("-versionless",""),loose=id.startsWith("DIA"),syntaxOnly=id.endsWith("syntax-only");
  const root=mkdtempSync(path.join(os.tmpdir(),"mode-scenario-")),fixture=createFixture(root,loose?{}:{"CompilerMode.java":COMPILER_SOURCE},loose?"src":"");
  if(loose)addSource(fixture,"LooseMode.java","loose/bench/LooseMode.java",LOOSE_VALID);
  const file=fixture.files[loose?"LooseMode.java":"CompilerMode.java"],timeout=versionless?5:1000;
  fixture.preparation={kind:"diagnostic-mode",mode:loose?"loose":"compiler",status:"verified",syntaxOnly,diagnosticPolicy:modeDiagnosticPolicy(timeout),
    witness:loose?looseWitness():{accepted:compilerRow(COMPILER_SOURCE,0),rejected:compilerRow(COMPILER_SOURCE,1,"compiler.err.feature.not.supported.in.source: records","11")},
    initialInputs:{sourceUri:file.uri,classpath:readFileSync(path.join(root,".classpath"),"utf8"),preferences:readFileSync(path.join(root,".settings/org.eclipse.jdt.core.prefs"),"utf8")}};
  const events:any[]=[],notifications:any[]=[],exchanges:any[]=[];let next=0,level="17",version=1;
  const add=(direction:string,message:any)=>{const row={schemaVersion:1,clockDomain:"client",sequence:events.length+1,timeNs:String(process.hrtime.bigint()),direction,message};events.push(row);return row;};
  const publish=(diagnostics:any[])=>{const params={uri:file.uri,...(versionless?{}:{version}),diagnostics},e=add("receive",{method:"textDocument/publishDiagnostics",params});notifications.push({method:e.message.method,params,timeNs:e.timeNs,sequence:e.sequence});};
  const client:any={events,notifications,exchanges,journal:()=>{},notify:(method:string,params:any)=>{
    const e=add("send",{method,params});if(params.textDocument?.uri===file.uri){version=params.textDocument.version;
      if(method==="textDocument/didOpen")publish([]);
      if(method==="textDocument/didChange")publish(params.contentChanges[0].text===LOOSE_SYNTAX?[syntax]:syntaxOnly?[]:[semantic]);}
    return BigInt(e.timeNs);
  },request:async(method:string,params:any)=>{const id=++next,start=add("send",{id,method,params});let result:any=null;
    if(params.command==="java.project.getSettings")result=Object.fromEntries(params.arguments[1].map((k:string)=>[k,level]));
    if(params.command==="java.project.updateSettings"){level="11";publish([]);publish([record]);}
    const end=add("receive",{id,result}),row={id,method,params,result,startNs:start.timeNs,endNs:end.timeNs};exchanges.push(row);return row;
  },notification:async()=>{await new Promise(resolve=>setTimeout(resolve,8));throw new Error("notification timeout: textDocument/publishDiagnostics");}};
  try{
    const c=new ScenarioContext(client,fixture,"jdtls",timeout,1,1);await diagnosticCases.find(d=>d.id===caseId)!.run(c);
    const report={preparation:fixture.preparation,diagnosticObservations:c.diagnosticObservations,operations:c.operations};
    assert.deepEqual(validateDiagnosticObservations(report,events,c.operations),[]);
    const measured=c.operations.filter(o=>o.diagnosticObservationId);assert.equal(measured.length,caseId==="DIA-01/full"?1:2);
    assert(measured.every(o=>o.outcome===(versionless?"unavailable_evidence":"pass")));
    if(versionless)assert(exchanges.some(e=>e.params.command==="java.project.updateSettings"),"unavailable baseline must not erase the later declared stage");
    const forged=structuredClone(report);forged.preparation.initialInputs.sourceUri="file:///foreign.java";assert(validateDiagnosticObservations(forged,events,c.operations).length);
  }finally{rmSync(root,{recursive:true,force:true});}
});
