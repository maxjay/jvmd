/** Validate the preparation/target boundary independently of server output normalization. */
export function validateTransitionAttempts(series:any,operations:any[]){
  const issues:string[]=[];const check=(ok:any,message:string)=>{if(!ok)issues.push(message);};
  const attempts=series.attempts;
  if(!Array.isArray(attempts)){check(false,"transition attempt inventory missing");return issues;}
  check(attempts.length===series.attemptCount,"transition attempt count mismatch");
  const byId=new Map(operations.map(op=>[op.operationId,op]));let previous=BigInt(series.triggerNs);
  for(const [i,attempt] of attempts.entries()){
    check(attempt.attempt===i+1,"transition attempt sequence mismatch");
    check(typeof attempt.startNs==="string"&&typeof attempt.endNs==="string","transition attempt interval missing");
    const start=BigInt(attempt.startNs??0),end=BigInt(attempt.endNs??0);
    check(start>=previous&&end>=start,"transition attempt interval invalid");previous=end;
    for(const phase of ["immediate","settled"]){
      const step=attempt[phase];if(!step)continue;
      check(Array.isArray(step.preparationOperationIds),"preparation operation inventory missing");
      for(const id of step.preparationOperationIds??[]){const op=byId.get(id);check(!!op,"preparation operation missing");
        if(op)check(BigInt(op.startNs)>=start&&BigInt(op.endNs)<=end,"preparation operation outside attempt");}
      if(step.operationId){const op=byId.get(step.operationId);check(!!op,"transition target missing");
        if(op){check(op.method===series.method,"transition target method mismatch");check(BigInt(op.startNs)>=start&&BigInt(op.endNs)<=end,"target outside attempt");
          check(op.state===(phase==="settled"?"changed_settled":i===0?"changed_immediate":"changed_retry_"+(i+1)),"transition target phase mismatch");}}
      else check(attempt.outcome==="failed"&&attempt.stage==="prepare_"+phase,"unsent target without failed preparation");
    }
    if(attempt.outcome==="pass")check(!!attempt.settled?.operationId,"passing attempt has no settled target");
  }
  const first=attempts[0];
  if(series.firstTargetRequest==="blocked_by_preparation")check(first?.stage==="prepare_immediate"&&first.immediate?.operationId===null&&first.immediate.preparationOperationIds.some((id:string)=>byId.get(id)?.outcome!=="pass"),"blocked target lacks failed preparation witness");
  else check(first?.immediate?.operationId&&series.firstTargetRequest==="sent","initial target disposition invalid");
  return issues;
}

/** A completed failure trace is not a successful transition. A settled response
 * is required after convergence, not after a witnessed exhausted probe schedule.
 * This validates evidence only; callers retain the original failed case outcome. */
export function validateTransitionTermination(series:any,operations:any[],caseOutcome:string){
  const issues:string[]=[];const check=(ok:any,message:string)=>{if(!ok)issues.push(message);};
  const ns=(value:any):bigint|null=>typeof value==="string"&&/^\d+$/.test(value)?BigInt(value):null;
  const settled=operations.find(op=>op.operationId===series.settledOperationId
    &&op.method===series.method&&op.state==="changed_settled"&&op.outcome==="pass"&&!op.error&&!op.assertionError);
  const policy=series.probePolicy;
  if(!policy){check(!!settled,"settled probe missing");return issues;}
  const trigger=ns(series.triggerNs),ended=ns(series.endedNs),deadline=ns(series.deadlineNs);
  check(Number.isInteger(policy.maxAttempts)&&policy.maxAttempts>0,"invalid probe attempt limit");
  check(Number.isInteger(series.attemptCount)&&series.attemptCount>0&&series.attemptCount<=policy.maxAttempts,"probe attempt count outside policy");
  check(["settled","deadline","attempt_limit"].includes(series.termination),"probe termination missing");
  check(trigger!==null&&ended!==null&&ended>=trigger,"probe end observation missing");
  if(series.termination==="settled"){
    check(!!series.settledOperationId,"settled probe identity absent");
    check(!!settled,"settled probe missing");
    if(settled&&ended!==null)check(ns(settled.endNs)!==null&&BigInt(settled.endNs)<=ended,"settled probe finishes after transition termination");
    return issues;
  }
  if(!["deadline","attempt_limit"].includes(series.termination)){
    check(!!settled,"settled probe missing");return issues;
  }
  // Never infer valid non-convergence from a reason string or absence of success.
  // Require the predeclared bound, every attempt, and the actual failed operation.
  check(["incorrect","stale","timeout","protocol_error","harness_error","unavailable_evidence"].includes(caseOutcome),"exhausted transition cannot have a successful or unexecuted case disposition");
  check(!series.settledOperationId,"exhausted transition claims a settled result");
  check(deadline!==null&&trigger!==null&&deadline>=trigger,"probe deadline identity missing");
  if(series.termination==="deadline")check(ended!==null&&deadline!==null&&ended>=deadline,"probe stopped before declared deadline");
  if(series.termination==="attempt_limit")check(series.attemptCount===policy.maxAttempts,"probe stopped before declared attempt limit");
  if(!Array.isArray(series.attempts)||!series.attempts.length){issues.push("exhausted transition attempt evidence missing");return issues;}
  check(series.attempts.length===series.attemptCount,"exhausted transition attempt count mismatch");
  const byId=new Map(operations.map(op=>[op.operationId,op]));
  const failedOutcomes=new Set(["incorrect","stale","timeout","protocol_error","harness_error","unavailable_evidence"]);
  for(const attempt of series.attempts){
    check(attempt.outcome==="failed"&&typeof attempt.error==="string"&&attempt.error.length>0,"exhausted transition has an unproven failed attempt");
    const stage=/^(prepare|query)_(immediate|settled)$/.exec(attempt.stage??"");
    check(!!stage,"exhausted transition failure stage missing");
    if(!stage)continue;
    const step=attempt[stage[2]];
    const candidates=stage[1]==="query"?[step?.operationId]:(step?.preparationOperationIds??[]);
    check(candidates.some((id:string)=>{
      const op=byId.get(id);
      return op&&failedOutcomes.has(op.outcome)&&(op.error||op.assertionError);
    }),"exhausted transition lacks failed operation evidence");
    const end=ns(attempt.endNs);
    check(end!==null&&ended!==null&&end<=ended,"failed attempt finishes after transition termination");
  }
  return issues;
}
