import assert from "node:assert/strict";
import {type CaseDefinition,type ScenarioContext} from "../harness/ScenarioContext.ts";
import {DIAGNOSTIC_SOURCES,DIAGNOSTIC_CHANGED_PROVIDER} from "../harness/diagnostics.ts";
import {prepareDiagnostics,diagnosticCompilerOracle} from "../harness/diagnosticCompiler.ts";
import {observeDiagnostics} from "../harness/diagnosticObserver.ts";

const existingDiagnosticCases:CaseDefinition[]=["valid","provider-edit"].map(variant=>({
  id:`DIA-01/${variant}`,family:"DIA-01",apis:["API-111"],fixture:DIAGNOSTIC_SOURCES,prepare:prepareDiagnostics,
  variant:variant==="valid"?"independent valid-source current-version publication":"provider-only signature edit; unchanged caller has unique new compiler-proven mismatch",
  run:async c=>{
    c.assert("independent diagnostic compiler witnesses verified",c.fixture.preparation?.status==="verified");diagnosticCompilerOracle(c.fixture.preparation!.witness);
    await c.open("DiagnosticProvider.java");const since=c.client.notifications.length,opened=await c.open("DiagnosticCaller.java");
    const expectation={kind:"valid" as const,uri:opened.uri,version:opened.version,incarnation:c.documents.get(opened.uri)!.incarnation,source:opened.text,triggerNs:String(opened.trigger)};
    await observeDiagnostics(c,expectation,since,variant==="valid"?"valid":"baseline_valid");
    c.assert("valid source has current-version diagnostic evidence",true);
    if(variant==="valid"){c.compileOracle();return;}
    const before=c.state(),provider=c.file("DiagnosticProvider.java"),cursor=c.client.notifications.length;
    const changed=c.change("DiagnosticProvider.java",DIAGNOSTIC_CHANGED_PROVIDER);
    c.mutations.push({kind:"provider_buffer_signature",uri:provider.uri,version:changed.version,triggerNs:String(changed.trigger),before:DIAGNOSTIC_SOURCES["DiagnosticProvider.java"],after:DIAGNOSTIC_CHANGED_PROVIDER});
    await observeDiagnostics(c,{...expectation,kind:"provider_mismatch",triggerNs:String(changed.trigger)},cursor,"provider_changed");
    c.assert("provider edit publishes exact new caller mismatch",true);
    c.assert("provider-only edit preserves caller bytes versions and all disk sources",c.mutations.length===1&&c.state().every(row=>{
      const old=before.find(s=>s.uri===row.uri)!;
      return row.uri===provider.uri?row.version===old.version!+1&&row.diskSha256===old.diskSha256:JSON.stringify(row)===JSON.stringify(old);
    })&&c.state().length===before.length);
    c.assert("diagnostic observation sent no readiness requests or server edits",!c.client.events.some(e=>e.direction==="send"&&e.message.id!==undefined&&BigInt(e.timeNs)>=changed.trigger)&&!c.serverActions.some(a=>a.method==="workspace/applyEdit"));
  },
}));


import {prepareModeDiagnostics,modeCompilerOracle,COMPILER_SOURCE,LOOSE_SEMANTIC,LOOSE_SYNTAX,type ModeKind} from "../harness/modeDiagnostics.ts";
const observeMode=async(c:ScenarioContext,file:string,kind:ModeKind,trigger:bigint,since:number,state:string)=>{
  const uri=c.file(file).uri,d=c.documents.get(uri)!;
  return observeDiagnostics(c,{kind,uri,source:c.text(file),version:d.version,incarnation:d.incarnation,triggerNs:String(trigger)},since,state,{continueUnavailable:true});
};
export const diagnosticCases:CaseDefinition[]=[...existingDiagnosticCases,
  ...[true,false].map(syntaxOnly=>({id:"DIA-01/"+(syntaxOnly?"syntax-only":"full"),family:"DIA-01",apis:["API-110","API-111"],command:"java.project.refreshDiagnostics",sourceDirectory:"src",prepare:prepareModeDiagnostics("loose",syntaxOnly),
    variant:"off-classpath source with compiler-proven semantic error; "+(syntaxOnly?"suppress semantic error and positively detect syntax error":"publish exact semantic error in full mode"),run:async(c:ScenarioContext)=>{
      modeCompilerOracle(c.fixture.preparation!.witness,"loose");c.assert("independent diagnostic mode compiler witnesses verified",c.fixture.preparation!.status==="verified");
      await c.open("LooseMode.java");await c.execute("java.project.refreshDiagnostics",[c.file("LooseMode.java").uri,"thisFile",syntaxOnly,true],v=>assert.equal(v,null),"mode_selection");
      const before=c.state(),cursor=c.client.notifications.length,changed=c.change("LooseMode.java",LOOSE_SEMANTIC);
      await observeMode(c,"LooseMode.java",syntaxOnly?"loose_suppressed":"loose_semantic",changed.trigger,cursor,"semantic_probe");
      if(syntaxOnly){const since=c.client.notifications.length,syntax=c.change("LooseMode.java",LOOSE_SYNTAX);await observeMode(c,"LooseMode.java","loose_syntax",syntax.trigger,since,"syntax_control");}
      c.assert("diagnostic mode changes only the selected unsaved loose buffer",c.state().length===before.length&&c.state().every(row=>{const old=before.find(s=>s.uri===row.uri)!;return row.uri===c.file("LooseMode.java").uri?row.diskSha256===old.diskSha256&&row.version===old.version!+(syntaxOnly?2:1):JSON.stringify(row)===JSON.stringify(old);}));
    }})),
  ...[false,true].map(changed=>({id:"PRJ-02/compiler-"+(changed?"change":"unchanged"),family:"PRJ-02",apis:["API-028","API-111",...(changed?["API-029"]:[])],command:changed?"java.project.updateSettings":"java.project.getSettings",fixture:{"CompilerMode.java":COMPILER_SOURCE},prepare:prepareModeDiagnostics("compiler"),
    variant:changed?"unchanged record source becomes rejected when compiler level changes 17 to 11":"unchanged reported compiler environment with independently valid record source and diagnostic baseline",run:async(c:ScenarioContext)=>{
      modeCompilerOracle(c.fixture.preparation!.witness,"compiler");c.assert("independent diagnostic mode compiler witnesses verified",c.fixture.preparation!.status==="verified");
      const keys=["compliance","source","codegen.targetPlatform"].map(k=>"org.eclipse.jdt.core.compiler."+k),uri=c.file("CompilerMode.java").uri;
      const check=(version:string)=>(v:any)=>{for(const key of keys)assert.equal(v[key],version);};
      const since=c.client.notifications.length,opened=await c.open("CompilerMode.java");
      await c.series("workspace/executeCommand",{command:"java.project.getSettings",arguments:[uri,keys]},check("17"));
      await observeMode(c,"CompilerMode.java","compiler_valid",opened.trigger,since,"baseline_valid");
      const before=c.state();if(changed){const cursor=c.client.notifications.length;
        await c.execute("java.project.updateSettings",[uri,Object.fromEntries(keys.map(k=>[k,"11"]))],v=>assert.equal(v,null),"compiler_change");
        const op=c.operations.at(-1),trigger=BigInt(op.startNs);c.mutations.push({kind:"compiler_setting",from:"17",to:"11",operationId:op.operationId,triggerNs:op.startNs});
        await observeMode(c,"CompilerMode.java","compiler_rejected",trigger,cursor,"changed_diagnostic");
        await c.execute("java.project.getSettings",[uri,keys],check("11"),"changed_environment");
      }
      c.assert("compiler mode preserves every source byte and document version",JSON.stringify(c.state())===JSON.stringify(before));
      c.assert("compiler mode has exactly the declared configuration mutation",c.mutations.length===(changed?1:0));
    }})),
];
