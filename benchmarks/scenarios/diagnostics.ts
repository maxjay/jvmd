import {type CaseDefinition} from "../harness/ScenarioContext.ts";
import {DIAGNOSTIC_SOURCES,DIAGNOSTIC_CHANGED_PROVIDER,observeDiagnostics,noErrors,callerMismatch} from "../harness/diagnostics.ts";

export const diagnosticCases:CaseDefinition[]=["valid","provider-edit"].map(variant=>({
  id:`DIA-01/${variant}`,family:"DIA-01",apis:["API-111"],fixture:DIAGNOSTIC_SOURCES,
  variant:variant==="valid"?"valid source publishes no errors for the current version":"provider signature edit; the unchanged caller gets exactly the new mismatch",
  run:async c=>{
    await c.open("DiagnosticProvider.java");const since=c.client.notifications.length,opened=await c.open("DiagnosticCaller.java");
    await observeDiagnostics(c,opened.uri,opened.trigger,noErrors,since,variant==="valid"?"valid":"baseline_valid");
    if(variant==="valid"){c.compileOracle();return;}
    const cursor=c.client.notifications.length,changed=c.change("DiagnosticProvider.java",DIAGNOSTIC_CHANGED_PROVIDER);
    await observeDiagnostics(c,opened.uri,changed.trigger,callerMismatch,cursor,"provider_changed");
  },
}));
