import assert from "node:assert/strict";
import {diagnosticDecision,type DiagnosticVersionMode} from "./contracts.ts";
import {range,selected} from "./oracles.ts";

export const DIAGNOSTIC_PROVIDER="package bench;\npublic class DiagnosticProvider { public int number() { return 7; } }\n";
export const DIAGNOSTIC_CHANGED_PROVIDER=DIAGNOSTIC_PROVIDER.replace("int number() { return 7;",'String number() { return "changed";');
export const DIAGNOSTIC_CALLER="package bench;\npublic class DiagnosticCaller { public int value(DiagnosticProvider p) { return p.number(); } }\n";
export const DIAGNOSTIC_SOURCES={"DiagnosticProvider.java":DIAGNOSTIC_PROVIDER,"DiagnosticCaller.java":DIAGNOSTIC_CALLER};
export type DiagnosticExpectation={
  kind:"valid"|"provider_mismatch";uri:string;version:number;incarnation:number;source:string;
  triggerNs:string;deadlineNs:string;
};
export type DiagnosticDisposition={
  kind:"ignore"|"unavailable"|"pending_provider_generation"|"pass"|"incorrect";
  mode:DiagnosticVersionMode;reason:string;
};

/** Asynchronous publications are observations, not replies to didChange.
 * In particular, an unchanged caller version cannot identify a provider edit.
 * The new, uniquely located mismatch is the positive generation witness. */
export function classifyDiagnostic(expectation:DiagnosticExpectation,publication:any,mode:DiagnosticVersionMode):DiagnosticDisposition {
  const result=(kind:DiagnosticDisposition["kind"],reason:string,nextMode=mode)=>({kind,reason,mode:nextMode});
  const time=BigInt(publication.timeNs);
  if(time<BigInt(expectation.triggerNs))return result("ignore","publication precedes trigger");
  if(time>BigInt(expectation.deadlineNs))return result("ignore","publication follows declared deadline");
  const params=publication.params;
  const decision=diagnosticDecision(mode,params,expectation.uri,expectation.version,expectation.incarnation,publication.incarnation);
  if(decision.kind==="ignore")return result("ignore","URI, version or incarnation does not match",decision.mode);
  if(decision.kind==="unavailable")return result("unavailable","publication cannot prove the current document incarnation and version",decision.mode);
  mode=decision.mode;
  try {
    assert(Array.isArray(params.diagnostics),"diagnostics must be an array");
    for(const d of params.diagnostics){
      assert(d&&typeof d.message==="string"&&d.message.length>0,"diagnostic message missing");
      assert(d.severity===undefined||[1,2,3,4].includes(d.severity),"invalid diagnostic severity");
      selected(expectation.source,d.range); // Also validates UTF-16 and bounds.
    }
    const errors=params.diagnostics.filter((d:any)=>d.severity===undefined||d.severity===1);
    if(expectation.kind==="valid"){
      assert.equal(errors.length,0,"valid source has an error diagnostic");
      return result("pass","exact current version has no error diagnostics");
    }
    assert.equal(expectation.source,DIAGNOSTIC_CALLER,"provider witness requires the declared unchanged caller");
    if(!errors.length)return result("pending_provider_generation","no new mismatch: caller version alone cannot identify provider generation");
    assert.equal(errors.length,1,"expected exactly one String-to-int caller error");
    const error=errors[0];
    assert(/\bString\b/u.test(error.message)&&/\bint\b/u.test(error.message),"missing String-to-int mismatch");
    const allowed=["p.number()","p.number","number"].map(token=>range(DIAGNOSTIC_CALLER,token));
    assert(allowed.some(r=>JSON.stringify(r)===JSON.stringify(error.range)),"mismatch is not at the responsible caller invocation");
    return result("pass","unique new String-to-int mismatch at the unchanged caller invocation");
  }catch(error){return result("incorrect",String(error));}
}

export const diagnosticPolicy=(timeoutMs:number)=>({
  deadlineMs:timeoutMs,maxPublications:1000,clock:"client monotonic",
  start:"didOpen or provider didChange send event",end:"first semantically current publication",
  admission:"exact document version and incarnation; unique mismatch additionally identifies changed provider",
  emptyProviderPublication:"retain as pending; not a response to the provider edit",
  retries:"none: observe notifications without sending readiness requests",
  terminalFailures:"wrong current-version nonempty errors remain incorrect; exit and exhaustion are explicit",
});
