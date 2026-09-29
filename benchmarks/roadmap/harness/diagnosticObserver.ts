import assert from "node:assert/strict";
import {now} from "./ProtocolClient.ts";
import {type ScenarioContext} from "./ScenarioContext.ts";
import {classifyDiagnostic,diagnosticPolicy,type DiagnosticExpectation} from "./diagnostics.ts";
import {isModeKind,modeDiagnosticPolicy} from "./modeDiagnostics.ts";
import {type DiagnosticVersionMode} from "./contracts.ts";

export async function observeDiagnostics(c:ScenarioContext,input:Omit<DiagnosticExpectation,"deadlineNs">,since:number,state:string,options:{continueUnavailable?:boolean}={}){
  const policy=(isModeKind(input.kind)?modeDiagnosticPolicy:diagnosticPolicy)(c.timeout),expectation={...input,deadlineNs:String(BigInt(input.triggerNs)+BigInt(policy.deadlineMs)*1000000n)};
  assert.deepEqual(c.fixture.preparation?.diagnosticPolicy,policy,"diagnostic policy must be declared before server launch");
  const trigger=c.client.events.find(e=>e.direction==="send"&&e.timeNs===input.triggerNs&&["textDocument/didOpen","textDocument/didChange",...(input.kind==="compiler_rejected"?["workspace/executeCommand"]:[])].includes(e.message.method));
  assert(trigger,"diagnostic trigger must be a recorded document notification");
  const observationId="diagnostic-"+(c.diagnosticObservations.length+1),start={schemaVersion:1,clockDomain:"client",event:"started",observationId,
    expectation,policy,state,triggerEventId:trigger.sequence,afterEventId:c.client.notifications[since-1]?.sequence??0};
  const journal=(row:any)=>{c.diagnosticObservations.push(row);c.client.journal("diagnostic-observations",row);};journal(start);
  let mode:DiagnosticVersionMode="unknown",cursor=since,count=0,unavailable=false,last:any;
  const finish=(outcome:string,reason:string,publication?:any)=>{
    const endNs=publication?.timeNs??String(now()),operationId="op-"+(c.operations.length+1);
    const operation={schemaVersion:1,clockDomain:"client",operationId,method:"textDocument/publishDiagnostics",endpoint:"textDocument/publishDiagnostics",state,
      measurementKind:"notification_transition",diagnosticObservationId:observationId,triggerEventId:trigger.sequence,notificationEventId:publication?.sequence,
      startNs:input.triggerNs,triggerNs:input.triggerNs,endNs,transitionMs:Number(BigInt(endNs)-BigInt(input.triggerNs))/1e6,
      rawResult:publication?.params,outcome,reason,freshness:outcome==="pass"?{status:"verified",witness:reason}:outcome==="incorrect"?{status:"contradicted",reason}:{status:"unavailable",reason}};
    c.recordOperation(operation);
    journal({schemaVersion:1,clockDomain:"client",event:"finished",observationId,operationId,outcome,reason,publicationCount:count,endNs});
    if(!(outcome==="unavailable_evidence"&&options.continueUnavailable))assert.equal(outcome,"pass",reason);return operation;
  };
  for(;;){
    if(count>=policy.maxPublications)return finish("unavailable_evidence","publication_limit");
    // Drain already received evidence before considering the observer's own
    // scheduling delay. Admission is bounded by wire receipt, not validation.
    last=c.client.notifications.slice(cursor).find(n=>n.method==="textDocument/publishDiagnostics");
    const remaining=Number(BigInt(expectation.deadlineNs)-now())/1e6;
    if(!last&&remaining<=0)return finish(unavailable?"unavailable_evidence":"timeout","deadline");
    try{last??=await c.client.notification("textDocument/publishDiagnostics",()=>true,cursor,Math.max(1,Math.ceil(remaining)));}
    catch(error){
      const timedOut=String(error).startsWith("Error: notification timeout:");
      return finish(timedOut?(unavailable?"unavailable_evidence":"timeout"):"protocol_error",timedOut?"deadline":String(error));
    }
    cursor=c.client.notifications.findIndex(n=>n.sequence===last.sequence)+1;
    assert(cursor>0,"publication missing from notification journal");
    const decision=classifyDiagnostic(expectation,last,mode);mode=decision.mode;count++;unavailable ||= decision.kind==="unavailable";
    journal({schemaVersion:1,clockDomain:"client",event:"publication",observationId,notificationEventId:last.sequence,decision});
    if(decision.kind==="pass"||decision.kind==="incorrect")return finish(decision.kind,decision.reason,last);
  }
}

/** Rebuild admission decisions from raw receive events, including rejected
 * publications. No server, mutable cache or serialized pass flag is trusted. */
