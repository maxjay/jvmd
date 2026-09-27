/** Structural replay of preparation/target boundaries. Semantic oracles remain
 * separate; a failed preparation never becomes a successful target sample. */
export function validateTransitionAttempts(series:any,operations:any[]){
  const issues:string[]=[],check=(value:any,message:string)=>{if(!value)issues.push(message);};
  const byId=new Map(operations.map(o=>[o.operationId,o])),used=new Set<string>();
  const attempts=series.attempts;
  check(Array.isArray(attempts)&&attempts.length===series.attemptCount,"transition attempt inventory mismatch");
  if(!Array.isArray(attempts))return issues;
  let previous=BigInt(series.triggerNs);
  for(const [index,attempt] of attempts.entries()){
    check(attempt.attempt===index+1,"transition attempt sequence mismatch");
    const validTimes=[attempt.startNs,attempt.endNs].every(t=>typeof t==="string"&&/^\d+$/u.test(t));
    check(validTimes,"transition attempt times missing");if(!validTimes)continue;
    const start=BigInt(attempt.startNs),end=BigInt(attempt.endNs);
    check(start>=previous&&end>=start,"transition attempt times out of order");previous=end;
    check(["pass","failed"].includes(attempt.outcome),"transition attempt unfinished");
    check(["prepare_immediate","query_immediate","prepare_settled","query_settled","complete"].includes(attempt.stage),"transition attempt stage invalid");
    check(attempt.outcome!=="failed"||typeof attempt.error==="string","failed transition attempt lacks error");
    check(attempt.outcome!=="pass"||attempt.stage==="complete","passing transition attempt lacks completion");
    check(!!attempt.immediate,"transition immediate phase missing");
    let phaseEnd=start;
    for(const phase of ["immediate","settled"]){
      const step=attempt[phase];if(!step)continue;
      check(Array.isArray(step.preparationOperationIds),"transition preparation inventory missing");
      let preparationEnd=phaseEnd;
      for(const id of step.preparationOperationIds??[]){
        const op=byId.get(id);check(!!op,"transition preparation operation missing");if(!op)continue;
        check(!used.has(id),"transition operation reused across phases");used.add(id);
        check(BigInt(op.startNs)>=preparationEnd&&BigInt(op.endNs)<=end,"transition preparation outside attempt");preparationEnd=BigInt(op.endNs);
        if(attempt.outcome==="pass")check(op.outcome==="pass","passing transition attempt conceals failed preparation");
      }
      if(step.operationId){
        const op=byId.get(step.operationId);check(!!op,"transition target operation missing");if(!op)continue;
        check(!used.has(step.operationId),"transition operation reused across phases");used.add(step.operationId);
        const state=phase==="settled"?"changed_settled":index===0?"changed_immediate":"changed_retry_"+(index+1);
        check(op.method===series.method&&op.state===state,"transition target identity mismatch");
        check(BigInt(op.startNs)>=preparationEnd&&BigInt(op.endNs)<=end,"transition target outside attempt");
        phaseEnd=BigInt(op.endNs);
        if(attempt.outcome==="pass")check(op.outcome==="pass","passing transition attempt conceals failure");
      }else{phaseEnd=preparationEnd;if(attempt.outcome==="pass")check(false,"passing transition attempt has no target");}
    }
    if(attempt.outcome==="pass")check(!!attempt.settled?.operationId,"passing attempt has no settled target");
  }
  const first=attempts[0];
  if(series.firstTargetRequest==="blocked_by_preparation")check(first?.outcome==="failed"&&first.stage==="prepare_immediate"&&!first.immediate?.operationId
    &&first.immediate?.preparationOperationIds?.some((id:string)=>byId.get(id)?.outcome!=="pass"&&byId.has(id)),"blocked target lacks failed preparation witness");
  else check(series.firstTargetRequest==="sent"&&!!first?.immediate?.operationId,"first target disposition mismatch");
  if(series.termination==="settled")check(attempts.at(-1)?.outcome==="pass"&&attempts.at(-1)?.settled?.operationId===series.settledOperationId,"settled attempt identity mismatch");
  return issues;
}
