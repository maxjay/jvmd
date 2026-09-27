import assert from "node:assert/strict";
import {readFileSync,writeFileSync,existsSync,readdirSync} from "node:fs";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {sha} from "./harness/fixture.ts";
import {CONTRACT} from "./harness/contracts.ts";
import {reduceVariants} from "./variants.ts";
import {isRenameRejection,isInvalidRenameRequest} from "./harness/rename.ts";
import {validateTransitionAttempts} from "./harness/transitions.ts";

const read=(file:string)=>JSON.parse(readFileSync(file,"utf8"));
const lines=(file:string)=>existsSync(file)?readFileSync(file,"utf8").split("\n").filter(Boolean).map(s=>JSON.parse(s)):[];
const failures=new Set(CONTRACT.outcomes.filter((s:string)=>!["pass","unsupported","not_applicable"].includes(s)));
export function quantile(values:number[],p:number){if(!values.length)return null;const sorted=[...values].sort((a,b)=>a-b),h=(sorted.length-1)*p,i=Math.floor(h);return sorted[i]+(h-i)*(sorted[Math.min(i+1,sorted.length-1)]-sorted[i]);}
function counts(rows:any[]){return Object.fromEntries(CONTRACT.outcomes.map((s:string)=>[s,rows.filter(r=>r.outcome===s).length]));}
function stats(rows:any[]){const valid=rows.filter(r=>r.outcome==="pass"&&r.freshness?.status!=="unavailable");const values=valid.map(r=>Number(BigInt(r.endNs)-BigInt(r.startNs))/1e6);
  return {attempted:rows.length,outcomes:counts(rows),successful:valid.length,medianMs:quantile(values,.5),p95Ms:quantile(values,.95),quantileEstimator:"Hyndman-Fan type 7",failedTimingsIncluded:false};}
export function validateCase(report:any,events:any[],exchanges:any[],operations:any[]){
  const issues:string[]=[];const check=(condition:any,message:string)=>{if(!condition)issues.push(message);};
  const ids=new Set<string>();let previous=0n;
  for(const [i,e] of events.entries()){check(e.schemaVersion===1,"event schema");check(e.sequence===i+1,"event sequence gap");check(e.clockDomain==="client","event clock domain");const t=BigInt(e.timeNs);check(t>=previous,"non-monotonic event time");previous=t;}
  const byId=new Map<number,any>();for(const e of exchanges){check(!byId.has(e.id),"duplicate exchange "+e.id);byId.set(e.id,e);}
  for(const op of operations){
    check(!ids.has(op.operationId),"duplicate operation "+op.operationId);ids.add(op.operationId);
    check(CONTRACT.outcomes.includes(op.outcome),"unknown outcome");check(BigInt(op.endNs)>=BigInt(op.startNs),"negative operation interval");
    if(op.triggerNs)check(BigInt(op.startNs)>=BigInt(op.triggerNs),"operation starts before its trigger");
    if(op.requestId!==undefined){const exchange=byId.get(op.requestId);check(!!exchange,"missing exchange "+op.requestId);
      if(exchange){check(exchange.method===op.method,"exchange method mismatch");check(exchange.startNs===op.startNs&&exchange.endNs===op.endNs,"exchange interval mismatch");
        check((op.endpoint??op.method)===(exchange.method==="workspace/executeCommand"?exchange.params.command:exchange.method),"exchange endpoint mismatch");
        check(JSON.stringify(exchange.result)===JSON.stringify(op.rawResult),"raw result mismatch");check(JSON.stringify(exchange.error)===JSON.stringify(op.error),"raw error mismatch");}
      const sent=events.find(e=>e.sequence===op.requestEventId),received=events.find(e=>e.sequence===op.responseEventId);
      check(sent?.direction==="send"&&sent.message.id===op.requestId&&sent.message.method===op.method,"request event mismatch");
      if(op.outcome!=="timeout"&&received){check(received.direction==="receive"&&received.message.id===op.requestId&&!received.message.method,"response event mismatch");
        if(exchange){check(JSON.stringify(received.message.result)===JSON.stringify(exchange.result),"wire result differs from exchange");check(JSON.stringify(received.message.error)===JSON.stringify(exchange.error),"wire error differs from exchange");}}
      else if(op.outcome==="pass")check(false,"successful response event missing");
    }
    const expectedRejection=op.expectedRejection==="rename_rejection"&&op.responsePolicy==="rename_rejection"
      &&isInvalidRenameRequest(op.method,op.state)&&isRenameRejection(op.error)
      &&report.caseId===(op.method==="textDocument/prepareRename"?"REF-01/prepare-invalid":"REF-01/rename-invalid")
      &&operations.some(b=>b.method===op.method&&b.state==="baseline"&&b.outcome==="pass"&&!b.error&&BigInt(b.endNs)<=BigInt(op.startNs));
    if(op.expectedRejection!==undefined)check(expectedRejection,"invalid expected rejection disposition");
    if(op.endpoint==="java.navigate.resolveTypeHierarchy"){
      const original=operations.find(o=>o.requestId===op.originRequestId),source=byId.get(op.originRequestId),resolved=byId.get(op.requestId);
      check(original?.endpoint==="java.navigate.openTypeHierarchy"&&original.outcome==="pass"&&!original.error
        &&JSON.stringify(original.stateBefore)===JSON.stringify(op.stateBefore)&&BigInt(original.endNs)<=BigInt(op.startNs)
        &&source?.params?.command==="java.navigate.openTypeHierarchy"&&resolved?.params?.command==="java.navigate.resolveTypeHierarchy"
        &&resolved.params.arguments?.[0]===JSON.stringify(source.result),"legacy hierarchy item provenance mismatch");
    }
    if(op.outcome==="pass"){check((!op.error||expectedRejection)&&!op.assertionError,"pass conceals error");check(["verified","not_applicable"].includes(op.freshness?.status),"pass without freshness disposition");}
  }
  for(const s of report.seriesExpectations??[]){
    if(s.kind==="transition"){
      if(s.attempts)issues.push(...validateTransitionAttempts(s,operations));
      const blocked=s.firstTargetRequest==="blocked_by_preparation"&&s.attempts?.[0]?.immediate?.operationId===null
        &&s.attempts[0].stage==="prepare_immediate"&&s.attempts[0].outcome==="failed"
        &&s.attempts[0].immediate.preparationOperationIds.some((id:string)=>operations.some(o=>o.operationId===id&&o.outcome!=="pass"));
      check(blocked||operations.slice(s.firstOperationIndex).find(o=>o.method===s.method&&o.state!=="item_acquisition")?.state==="changed_immediate","immediate probe missing");
      if(s.probePolicy){
        check(Number.isInteger(s.probePolicy.maxAttempts)&&s.probePolicy.maxAttempts>0,"invalid probe attempt limit");
        check(Number.isInteger(s.attemptCount)&&s.attemptCount>0&&s.attemptCount<=s.probePolicy.maxAttempts,"probe attempt count outside policy");
        check(["settled","deadline","attempt_limit"].includes(s.termination),"probe termination missing");
        check(typeof s.endedNs==="string"&&BigInt(s.endedNs)>=BigInt(s.triggerNs),"probe end observation missing");
        if(s.termination==="deadline")check(BigInt(s.endedNs)>=BigInt(s.deadlineNs),"probe stopped before declared deadline");
        if(s.termination==="attempt_limit")check(s.attemptCount===s.probePolicy.maxAttempts,"probe stopped before declared attempt limit");
        if(s.termination==="settled")check(!!s.settledOperationId,"settled probe identity absent");
      }
      check(operations.some(o=>o.operationId===s.settledOperationId&&o.state==="changed_settled"&&o.outcome==="pass"),"settled probe missing");continue;}
    const expected=[...Array(s.firstUse).fill("first_use"),...Array(s.warmup).fill("warmup"),...Array(s.steady).fill("steady")];
    for(const [i,state] of expected.entries()){const o=operations[s.firstOperationIndex+i];check(o?.method===s.method&&o?.state===state,"series sample missing or reordered: "+s.method+" "+state+" "+i);
      if(s.endpoint&&o)check(o.endpoint===s.endpoint,"series endpoint mismatch");}
  }
  if(report.outcome==="pass"){
    check(operations.length>0||report.correctnessOnly,"pass without operations");check(operations.every(o=>o.outcome==="pass"),"case pass conceals failed operation");
    check((report.assertions??[]).every((a:any)=>a.passed===true),"case pass conceals failed assertion");
    check(!(report.protocolErrors?.length||report.shutdownError||report.cleanupError||report.error),"case pass conceals protocol/harness error");
  }
  if(report.outcome==="unsupported")check(!!report.supportEvidence?.source,"unsupported without evidence");
  return issues;
}
export function reduceBundle(root:string,verifyHashes=true){
  const manifest=read(path.join(root,"manifest.json")),catalogue=read(path.join(root,"catalogue.json"));
  const issues:string[]=[];const checksumFile=path.join(root,"checksums.sha256");const sealed=existsSync(checksumFile);const covered=new Set<string>();
  if(manifest.sourceDrift)issues.push("source changed during collection");
  if(!sealed)issues.push("bundle is unsealed or interrupted: checksums.sha256 absent");
  if(sealed&&verifyHashes){for(const row of readFileSync(checksumFile,"utf8").trim().split("\n")){
    const m=/^([a-f0-9]{64})  (.+)$/u.exec(row);assert(m,"invalid checksum row");const [_,hash,relative]=m,file=path.resolve(root,relative);
    assert(file.startsWith(path.resolve(root)+path.sep),"checksum path escapes bundle");assert(!covered.has(relative),"duplicate checksum path");covered.add(relative);
    if(!existsSync(file)||sha(readFileSync(file))!==hash)issues.push("artifact hash mismatch: "+relative);
  }
    const visit=(directory:string)=>{for(const entry of readdirSync(directory,{withFileTypes:true})){
      const file=path.join(directory,entry.name),relative=path.relative(root,file);
      if(entry.isDirectory())visit(file);
      else if(relative!=="checksums.sha256"&&!covered.has(relative))issues.push("artifact outside checksum inventory: "+relative);
    }};visit(root);
  }
  const reports:any[]=[],metrics:any[]=[];const apiEvents=new Map<string,any[]>();
  for(let block=1;block<=manifest.plan.blocks;block++)for(const caseId of manifest.plan.caseIds)for(const server of manifest.plan.servers){
    const name=`${String(block).padStart(2,"0")}-${server}-${caseId.replaceAll("/","-")}`,dir=path.join(root,name),file=path.join(dir,"report.json");
    if(!existsSync(file)){reports.push({caseId,server,block,outcome:"not_run",reason:"planned case has no final report"});continue;}
    const report=read(file),events=lines(path.join(dir,"events.jsonl")),exchanges=lines(path.join(dir,"exchanges.jsonl")),operations=lines(path.join(dir,"operations.jsonl"));
    const caseIssues=validateCase(report,events,exchanges,operations);
    const transitions=(report.seriesExpectations??[]).filter((s:any)=>s.kind==="transition"&&s.attempts);
    if(transitions.length){
      const journal=lines(path.join(dir,"transitions.jsonl"));
      const expected=transitions.flatMap((s:any)=>s.attempts.map((attempt:any)=>({schemaVersion:1,clockDomain:"client",event:"attempt_finished",transitionId:s.transitionId,...attempt})));
      if(JSON.stringify(journal.filter(row=>row.event==="attempt_finished"))!==JSON.stringify(expected))caseIssues.push("transition journal differs from report");
      const starts=transitions.flatMap((s:any)=>s.attempts.map((attempt:any)=>({schemaVersion:1,clockDomain:"client",event:"attempt_started",transitionId:s.transitionId,attempt:attempt.attempt,startNs:attempt.startNs})));
      if(JSON.stringify(journal.filter(row=>row.event==="attempt_started"))!==JSON.stringify(starts)||journal.length!==starts.length+expected.length)caseIssues.push("transition start journal differs from report");
      if(JSON.stringify(journal)!==JSON.stringify(starts.flatMap((start:any,i:number)=>[start,expected[i]])))caseIssues.push("transition journal event order differs from report");
    }
    if(manifest.plan.transitionPolicy)for(const series of report.seriesExpectations??[]){
      if(series.kind==="transition"&&JSON.stringify(series.probePolicy)!==JSON.stringify(manifest.plan.transitionPolicy))caseIssues.push("transition probe policy differs from predeclared plan");
      if(series.kind==="transition"&&manifest.plan.transitionPolicy.preparationFailure&&!Array.isArray(series.attempts))caseIssues.push("declared transition attempt evidence missing");
    }
    if(report.processLifecycle){
      const lifecycle=lines(path.join(dir,"process.jsonl"));
      if(JSON.stringify(lifecycle)!==JSON.stringify(report.processLifecycle))caseIssues.push("process journal differs from report");
      let time=0n;
      for(const [i,row] of lifecycle.entries()){
        if(row.schemaVersion!==1||row.clockDomain!=="client"||row.sequence!==i+1||BigInt(row.timeNs)<time)caseIssues.push("invalid process lifecycle event");
        time=BigInt(row.timeNs);
      }
      if(report.outcome==="pass"&&(!lifecycle.some(row=>row.event==="process_exit"&&row.code===0&&!row.signal)||lifecycle.at(-1)?.event!=="stdio_closed"||lifecycle.some(row=>["forced_kill","shutdown_deadline","process_error","process_exit_unobserved"].includes(row.event))))caseIssues.push("case pass without observed clean process exit");
    }
    if(report.caseId!==caseId||report.server!==server||report.block!==block)caseIssues.push("planned case identity mismatch");
    if(report.finalized!==true)caseIssues.push("case interrupted before finalization");
    if(sealed&&verifyHashes)for(const n of ["report.json","events.jsonl","exchanges.jsonl","operations.jsonl","fixture.json"])if(!covered.has(name+"/"+n))caseIssues.push("artifact outside checksum inventory: "+n);
    issues.push(...caseIssues.map(s=>name+": "+s));
    reports.push({...report,operations,validationIssues:caseIssues,validatedOutcome:caseIssues.length&&report.outcome==="pass"?"harness_error":report.outcome});
    const groups=new Map<string,any[]>();for(const op of operations){const key=JSON.stringify([op.endpoint??op.method,op.state]);if(!groups.has(key))groups.set(key,[]);groups.get(key)!.push(op);}
    for(const [key,rows] of groups){const [endpoint,state]=JSON.parse(key);metrics.push({caseId,server,block,endpoint,state,...stats(rows),transitionMedianMs:quantile(rows.filter(o=>o.outcome==="pass"&&o.transitionMs!==undefined).map(o=>o.transitionMs),.5)});}
    for(const api of catalogue.apis){const matching=events.filter(e=>e.message.method&&(api.kind==="C"?e.message.method==="workspace/executeCommand"&&e.message.params?.command===api.method:e.message.method===api.method));
      if(matching.length){if(!apiEvents.has(api.id))apiEvents.set(api.id,[]);apiEvents.get(api.id)!.push({caseId,server,block,eventIds:matching.map(e=>e.sequence),declaredTarget:report.apiIds.includes(api.id)});}}
  }
  const coverage=catalogue.apis.map((api:any)=>({apiId:api.id,method:api.method,role:api.testUse,
    caseIds:(manifest.registry??[]).filter((c:any)=>c.apis.includes(api.id)).map((c:any)=>c.id),
    observations:apiEvents.get(api.id)??[],dispositions:reports.filter(r=>r.apiIds?.includes(api.id)).map(r=>({caseId:r.caseId,server:r.server,block:r.block,outcome:r.validatedOutcome??r.outcome,supportEvidence:r.supportEvidence})),
  }));
  const gaps=coverage.filter((a:any)=>a.role==="Scenario target"&&(!a.caseIds.length||manifest.plan.servers.some((server:string)=>!a.dispositions.some((d:any)=>d.server===server&&(d.outcome==="unsupported"||d.outcome==="pass"&&a.observations.some((o:any)=>o.server===server&&o.declaredTarget)))))).map((a:any)=>a.apiId);
  const effective=reports.map(r=>({...r,outcome:r.validatedOutcome??r.outcome}));
  const variantFile=path.join(root,"required-variants.json");
  const variants=reduceVariants(existsSync(variantFile)?read(variantFile):null,catalogue,manifest.registry??[],manifest.plan,reports);
  issues.push(...variants.issues);
  if(existsSync(variantFile)&&sealed&&verifyHashes&&!covered.has("required-variants.json"))issues.push("variant contract outside checksum inventory");
  const complete=!issues.length&&effective.every(r=>!failures.has(r.outcome));
  const summary={schemaVersion:1,planned:manifest.plan.blocks*manifest.plan.caseIds.length*manifest.plan.servers.length,executed:reports.filter(r=>r.outcome!=="not_run").length,
    outcomes:counts(effective),complete,scope:"selected cases",catalogueComplete:complete&&!gaps.length&&variants.complete,uncoveredTargetApiIds:gaps,uncoveredRequiredVariants:variants.gaps,integrityIssues:issues,
    claims:{publicComparativePerformance:false,reasons:[...(!complete?["selected cases or artifact integrity failed"]:[]),...(gaps.length?["catalogue API coverage incomplete"]:[]),...(!variants.complete?["required variant coverage incomplete"]:[]),...(manifest.plan.blocks<10?["fewer than ten independent blocks"]:[]),"native lifecycle, resources, observer overhead and production AOT gates require independent evidence"]}};
  const report=["# LSP benchmark evidence","",`Selected cases: ${summary.executed}/${summary.planned}. Correctness and integrity: ${complete?"pass":"fail"}. Catalogue targets lacking valid evidence: ${gaps.length}.`,"",
    `Required variants lacking valid evidence: ${variants.gaps.length}. See variants.json for each server/block and the operation witnesses. API declarations do not satisfy this gate.`,"",
    "Public comparative performance claims are disabled. Raw failed attempts remain in the denominator.","","| Case | Server | Block | Outcome | Integrity issues |","|---|---|---:|---|---:|",
    ...effective.map(r=>`| ${r.caseId} | ${r.server} | ${r.block} | ${r.outcome} | ${r.validationIssues?.length??0} |`),"",
    "Endpoint metrics separate first use, warmup, steady, immediate change, retries and settled probes. Percentiles use type 7 interpolation within one case; they are not independent-run confidence intervals.",""].join("\n");
  return {summary,coverage,variants,metrics,report};
}
export function writeReduction(root:string,result:ReturnType<typeof reduceBundle>,prefix=""){
  for(const name of ["summary","coverage","variants","metrics"] as const)writeFileSync(path.join(root,prefix+name+".json"),JSON.stringify(result[name],null,2)+"\n");
  writeFileSync(path.join(root,prefix+"report.md"),result.report);
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  assert(process.argv[2],"usage: node reduce.ts BUNDLE [OUTPUT_DIRECTORY]");const root=path.resolve(process.argv[2]),result=reduceBundle(root);
  if(process.argv[3]){const out=path.resolve(process.argv[3]);assert(out!==root,"reduction output must preserve original bundle");writeReduction(out,result);}
  console.log(JSON.stringify(result.summary,null,2));if(!result.summary.complete)process.exitCode=1;
}
