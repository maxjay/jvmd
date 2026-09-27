import assert from "node:assert/strict";
import {now} from "./ProtocolClient.ts";
import {type ScenarioContext} from "./ScenarioContext.ts";
import {classifyDiagnostic,diagnosticPolicy,DIAGNOSTIC_CALLER,DIAGNOSTIC_PROVIDER,DIAGNOSTIC_CHANGED_PROVIDER,type DiagnosticExpectation} from "./diagnostics.ts";
import {type DiagnosticVersionMode} from "./contracts.ts";

export async function observeDiagnostics(c:ScenarioContext,input:Omit<DiagnosticExpectation,"deadlineNs">,since:number,state:string){
  const policy=diagnosticPolicy(c.timeout),expectation={...input,deadlineNs:String(BigInt(input.triggerNs)+BigInt(policy.deadlineMs)*1000000n)};
  assert.deepEqual(c.fixture.preparation?.diagnosticPolicy,policy,"diagnostic policy must be declared before server launch");
  const trigger=c.client.events.find(e=>e.direction==="send"&&e.timeNs===input.triggerNs&&["textDocument/didOpen","textDocument/didChange"].includes(e.message.method));
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
    assert.equal(outcome,"pass",reason);return operation;
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
export function validateDiagnosticObservations(report:any,events:any[],operations:any[]):string[]{
  const issues:string[]=[],check=(condition:any,message:string)=>{if(!condition)issues.push(message);};
  try{
    const journal=report.diagnosticObservations??[],observations=new Set<string>(),operationIds=new Set<string>();let offset=0;
    while(offset<journal.length){
      const start=journal[offset++];assert.equal(start.event,"started");assert(!observations.has(start.observationId));observations.add(start.observationId);
      assert.deepEqual(start.policy,report.preparation?.diagnosticPolicy);
      assert.deepEqual(start.policy,diagnosticPolicy(start.policy.deadlineMs));
      const exp:DiagnosticExpectation=start.expectation;assert.equal(exp.deadlineNs,String(BigInt(exp.triggerNs)+BigInt(start.policy.deadlineMs)*1000000n));
      const trigger=events.find(e=>e.sequence===start.triggerEventId);
      assert(trigger?.direction==="send"&&trigger.timeNs===exp.triggerNs&&["textDocument/didOpen","textDocument/didChange"].includes(trigger.message.method));
      assert(Number.isInteger(start.afterEventId)&&start.afterEventId>=0&&start.afterEventId<trigger.sequence);
      const documents=new Map<string,any>(),incarnations=new Map<string,number>();
      for(const e of events.filter(e=>e.sequence<=trigger.sequence&&e.direction==="send")){
        const d=e.message.params?.textDocument;if(!d)continue;
        if(e.message.method==="textDocument/didOpen"){const incarnation=(incarnations.get(d.uri)??0)+1;incarnations.set(d.uri,incarnation);documents.set(d.uri,{text:d.text,version:d.version,incarnation});}
        if(e.message.method==="textDocument/didChange"){
          const previous=documents.get(d.uri);assert(previous,"change lacks open");const changes=e.message.params.contentChanges;
          assert(changes.length===1&&!changes[0].range,"diagnostic fixture requires full-buffer changes");documents.set(d.uri,{...previous,version:d.version,text:changes[0].text});
        }
        if(e.message.method==="textDocument/didClose")documents.delete(d.uri);
      }
      assert.deepEqual(documents.get(exp.uri),{text:exp.source,version:exp.version,incarnation:exp.incarnation},"diagnostic expectation differs from actual open buffer");
      if(exp.kind==="valid"){assert.equal(trigger.message.method,"textDocument/didOpen");assert.equal(trigger.message.params.textDocument.uri,exp.uri);}
      else{
        assert.equal(exp.kind,"provider_mismatch");assert.equal(exp.source,DIAGNOSTIC_CALLER);assert.equal(trigger.message.method,"textDocument/didChange");
        const provider=trigger.message.params.textDocument.uri;assert.notEqual(provider,exp.uri);assert.equal(documents.get(provider)?.text,DIAGNOSTIC_CHANGED_PROVIDER);
        const providerHistory=events.filter(e=>e.sequence<trigger.sequence&&e.direction==="send"&&e.message.params?.textDocument?.uri===provider);
        assert(providerHistory.some(e=>e.message.method==="textDocument/didOpen"&&e.message.params.textDocument.text===DIAGNOSTIC_PROVIDER));
        assert(!providerHistory.some(e=>e.message.method==="textDocument/didChange"));
        assert(operations.some(o=>o.method==="textDocument/publishDiagnostics"&&o.state==="baseline_valid"&&o.outcome==="pass"&&o.rawResult?.uri===exp.uri&&BigInt(o.endNs)<BigInt(exp.triggerNs)),"provider transition lacks valid caller baseline");
      }
      const raw=events.filter(e=>e.sequence>start.afterEventId&&e.direction==="receive"&&e.message.method==="textDocument/publishDiagnostics"&&e.message.id===undefined);
      let count=0,mode:DiagnosticVersionMode="unknown",terminal:any,last:any,unavailable=false;
      while(journal[offset]?.event==="publication"){
        const entry=journal[offset++];assert.equal(entry.observationId,start.observationId);assert(!terminal,"observer continued after terminal publication");
        last=raw[count++];assert.equal(entry.notificationEventId,last?.sequence,"publication omitted or reordered");
        const decision=classifyDiagnostic(exp,{params:last.message.params,timeNs:last.timeNs},mode);mode=decision.mode;unavailable ||= decision.kind==="unavailable";
        assert.deepEqual(entry.decision,decision,"publication decision differs from raw evidence");
        if(["pass","incorrect"].includes(decision.kind))terminal=decision;
      }
      const finish=journal[offset++];assert.equal(finish?.event,"finished");assert.equal(finish.observationId,start.observationId);assert.equal(finish.publicationCount,count);assert(count<=start.policy.maxPublications);
      const op=operations.find(o=>o.operationId===finish.operationId);assert(op&&!operationIds.has(op.operationId));operationIds.add(op.operationId);
      assert.equal(op.diagnosticObservationId,start.observationId);assert.equal(op.state,start.state);assert.equal(op.method,"textDocument/publishDiagnostics");
      assert.equal(op.measurementKind,"notification_transition");assert.equal(op.requestId,undefined);assert.equal(op.latencyMs,undefined);
      assert.equal(op.triggerEventId,trigger.sequence);assert.equal(op.startNs,exp.triggerNs);assert.equal(op.triggerNs,exp.triggerNs);
      assert.equal(op.endNs,finish.endNs);assert.equal(op.outcome,finish.outcome);assert.equal(op.reason,finish.reason);
      assert.equal(op.transitionMs,Number(BigInt(op.endNs)-BigInt(exp.triggerNs))/1e6);
      if(terminal){
        assert.equal(op.outcome,terminal.kind);assert.equal(op.reason,terminal.reason);assert.equal(op.notificationEventId,last.sequence);assert.equal(op.endNs,last.timeNs);assert.deepEqual(op.rawResult,last.message.params);
        assert.equal(op.freshness?.status,terminal.kind==="pass"?"verified":"contradicted");
      }else{
        assert.equal(op.notificationEventId,undefined);assert.equal(op.rawResult,undefined);assert.equal(op.freshness?.status,"unavailable");
        if(finish.reason==="publication_limit"){assert.equal(count,start.policy.maxPublications);assert.equal(op.outcome,"unavailable_evidence");}
        else if(finish.reason==="deadline"){
          assert(BigInt(op.endNs)>=BigInt(exp.deadlineNs));assert.equal(op.outcome,unavailable?"unavailable_evidence":"timeout");
          assert(!raw.slice(count).some(e=>BigInt(e.timeNs)<=BigInt(exp.deadlineNs)),"eligible publication skipped before timeout");
        }else{assert.equal(op.outcome,"protocol_error");assert(report.protocolErrors?.length||report.processLifecycle?.some((e:any)=>["process_exit","process_error"].includes(e.event)&&BigInt(e.timeNs)<=BigInt(op.endNs)),"protocol failure lacks process evidence");}
      }
      for(const entry of journal.slice(offset-count-2,offset))assert(entry.schemaVersion===1&&entry.clockDomain==="client");
    }
    for(const op of operations)if(op.diagnosticObservationId)check(operationIds.has(op.operationId),"diagnostic operation lacks observer journal");
  }catch(error){issues.push("diagnostic observation evidence invalid: "+String(error));}
  return issues;
}
