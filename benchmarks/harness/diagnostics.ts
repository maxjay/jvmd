import assert from "node:assert/strict";
import {now} from "./ProtocolClient.ts";
import {type ScenarioContext} from "./ScenarioContext.ts";
import {range,selected} from "./oracles.ts";

export const DIAGNOSTIC_PROVIDER="package bench;\npublic class DiagnosticProvider { public int number() { return 7; } }\n";
export const DIAGNOSTIC_CHANGED_PROVIDER=DIAGNOSTIC_PROVIDER.replace("int number() { return 7;",'String number() { return "changed";');
export const DIAGNOSTIC_CALLER="package bench;\npublic class DiagnosticCaller { public int value(DiagnosticProvider p) { return p.number(); } }\n";
export const DIAGNOSTIC_SOURCES={"DiagnosticProvider.java":DIAGNOSTIC_PROVIDER,"DiagnosticCaller.java":DIAGNOSTIC_CALLER};

/** A server's answer is legal but cannot be checked, e.g. diagnostics published without a document version. */
export class UnavailableEvidence extends Error {
  evidence:any;
  constructor(reason:string,evidence:any){super(reason);this.name="UnavailableEvidence";this.evidence=evidence;}
}

/** pass, or pending when this publication is legal but not yet the awaited one; throws when it is wrong. */
export type DiagnosticCheck=(errors:any[],source:string)=>"pass"|"pending";
export const noErrors:DiagnosticCheck=errors=>{assert.equal(errors.length,0,"valid source has an error diagnostic");return "pass";};
/** The provider edit makes the unchanged caller wrong; an empty publication for the caller is not yet the answer. */
export const callerMismatch:DiagnosticCheck=(errors,source)=>{
  assert.equal(source,DIAGNOSTIC_CALLER,"caller must be unchanged");
  if(!errors.length)return "pending";
  assert.equal(errors.length,1,"expected exactly one String-to-int caller error");
  assert(/\bString\b/u.test(errors[0].message)&&/\bint\b/u.test(errors[0].message),"missing String-to-int mismatch");
  assert(["p.number()","p.number","number"].some(t=>JSON.stringify(range(DIAGNOSTIC_CALLER,t))===JSON.stringify(errors[0].range)),"mismatch is not at the caller invocation");
  return "pass";
};
/** Exactly one error whose range selects `token`. */
export const errorAt=(token:string):DiagnosticCheck=>(errors,source)=>{
  if(!errors.length)return "pending";
  assert.equal(errors.length,1,"expected exactly one error");assert.equal(selected(source,errors[0].range),token,"error is not at "+token);return "pass";
};

type Mode="unknown"|"versioned"|"versionless";
/** Only a publication for the exact current document version can answer; a versionless server can only be observed. */
export function admission(mode:Mode,params:any,uri:string,version:number):{kind:"ignore"|"current"|"unversioned";mode:Mode}{
  if(params?.uri!==uri)return {kind:"ignore",mode};
  if(Number.isInteger(params?.version))return params.version===version?{kind:"current",mode:"versioned"}:{kind:"ignore",mode:"versioned"};
  return mode==="versioned"?{kind:"ignore",mode}:{kind:"unversioned",mode:"versionless"};
}

/**
 * Waits for the publication that answers a document change, without sending any request that could
 * warm the server. Records one operation whose transitionMs is change → first correct publication.
 */
export async function observeDiagnostics(c:ScenarioContext,uri:string,triggerNs:bigint,check:DiagnosticCheck,since:number,state:string){
  const document=c.documents.get(uri);assert(document,"diagnostics are observed for open documents");
  const deadline=triggerNs+BigInt(c.timeout)*1000000n;let mode:Mode="unknown",cursor=since,unversioned=false,last:any;
  const finish=(outcome:string,reason:string)=>{
    const endNs=last?.timeNs??String(now());
    c.recordOperation({operationId:"op-"+(c.operations.length+1),method:"textDocument/publishDiagnostics",endpoint:"textDocument/publishDiagnostics",state,
      startNs:String(triggerNs),triggerNs:String(triggerNs),endNs,transitionMs:Number(BigInt(endNs)-triggerNs)/1e6,rawResult:last?.params,outcome,assertionError:outcome==="pass"?undefined:reason});
    assert.equal(outcome,"pass",`textDocument/publishDiagnostics: ${reason}`);
  };
  for(;;){
    last=c.client.notifications.slice(cursor).find(n=>n.method==="textDocument/publishDiagnostics");
    const remaining=Number(deadline-now())/1e6;
    if(!last){
      if(remaining<=0)return finish(unversioned?"unavailable_evidence":"timeout",unversioned?"diagnostics were published without a document version":"no current diagnostics before the deadline");
      try{last=await c.client.notification("textDocument/publishDiagnostics",()=>true,cursor,Math.max(1,Math.ceil(remaining)));}
      catch{last=undefined;continue;}
    }
    cursor=c.client.notifications.findIndex(n=>n.sequence===last.sequence)+1;
    const decision=admission(mode,last.params,uri,document.version);mode=decision.mode;
    if(decision.kind==="ignore")continue;
    if(decision.kind==="unversioned"){unversioned=true;continue;}
    try{
      for(const d of last.params.diagnostics){assert(typeof d.message==="string"&&d.message,"diagnostic message missing");selected(document.text,d.range);}
      const errors=last.params.diagnostics.filter((d:any)=>d.severity===undefined||d.severity===1);
      if(check(errors,document.text)==="pass")return finish("pass","");
    }catch(error){return finish("incorrect",String(error));}
  }
}
