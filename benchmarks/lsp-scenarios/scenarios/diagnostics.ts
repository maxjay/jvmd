import {type CaseDefinition} from "../harness/ScenarioContext.ts";
import {DIAGNOSTIC_SOURCES,DIAGNOSTIC_CHANGED_PROVIDER} from "../harness/diagnostics.ts";
import {prepareDiagnostics,diagnosticCompilerOracle} from "../harness/diagnosticCompiler.ts";
import {observeDiagnostics} from "../harness/diagnosticObserver.ts";

export const diagnosticCases:CaseDefinition[]=["valid","provider-edit"].map(variant=>({
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
