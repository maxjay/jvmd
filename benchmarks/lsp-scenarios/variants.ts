/** Required variants are independent of API coverage. Read the saved contract,
 * never the current checkout, when reducing an older experiment. */
export function reduceVariants(contract:any,catalogue:any,registry:any[],plan:any,reports:any[]){
  const issues:string[]=[],rows:any[]=[];
  if(!contract)return {complete:false,issues,rows,gaps:["required variant contract absent from bundle"]};
  const check=(ok:any,message:string)=>{if(!ok)issues.push(message);};
  check(contract.schemaVersion===1,"variant contract schema");
  const families=contract.families??[],ids=new Set<string>(),caseIds=new Map(registry.map(c=>[c.id,c]));
  check(JSON.stringify(families.map((f:any)=>f.id).sort())===JSON.stringify(catalogue.scenarios.map((s:any)=>s.id).sort()),"variant families differ from source catalogue");
  for(const family of families){
    const source=catalogue.scenarios.find((s:any)=>s.id===family.id);
    check(source&&family.sourceVariants===source.variants&&family.sourceCorrectness===source.correctness,"variant source instructions differ: "+family.id);
    check(Array.isArray(family.variants)&&family.variants.length>0,"no required variants: "+family.id);
    for(const variant of family.variants??[]){
      check(typeof variant.id==="string"&&variant.id.startsWith(family.id+"/")&&!ids.has(variant.id),"invalid or duplicate variant identity: "+variant.id);ids.add(variant.id);
      check(["implemented","partial","not_implemented"].includes(variant.implementation),"unknown implementation disposition: "+variant.id);
      const evidence=variant.evidence??[];
      check(variant.implementation!=="implemented"||evidence.length>0,"implemented variant without witness: "+variant.id);
      check(variant.implementation==="implemented"||!!variant.remaining,"unfinished variant without explanation: "+variant.id);
      for(const witness of evidence){
        check(caseIds.get(witness.caseId)?.family===family.id,"variant references unknown or foreign case: "+variant.id+" "+witness.caseId);
        check(witness.operations?.length>0,"variant requires no operation evidence: "+variant.id);
        for(const op of witness.operations??[])check(typeof op.endpoint==="string"&&op.states?.length>0,"invalid operation witness: "+variant.id);
      }
      for(const server of plan.servers)for(let block=1;block<=plan.blocks;block++){
        const witnesses=evidence.map((w:any)=>{
          const report=reports.find(r=>r.caseId===w.caseId&&r.server===server&&r.block===block);
          if(!report)return {caseId:w.caseId,outcome:"not_run",missing:["required case not collected in this server/block"]};
          const outcome=report.validationIssues?.length?"harness_error":report.validatedOutcome??report.outcome;
          if(outcome==="unsupported")return {caseId:w.caseId,outcome:report.supportEvidence?.source?"unsupported":"missing_evidence",supportEvidence:report.supportEvidence};
          if(outcome!=="pass")return {caseId:w.caseId,outcome};
          const missing:string[]=[],operationIds:string[]=[];
          for(const op of w.operations??[])for(const state of op.states){
            const matches=(report.operations??[]).filter((r:any)=>(r.endpoint??r.method)===op.endpoint&&r.state===state&&r.outcome==="pass");
            if(!matches.length)missing.push(op.endpoint+" "+state);else operationIds.push(...matches.map((r:any)=>r.operationId));
          }
          for(const name of w.assertions??[])if(!(report.assertions??[]).some((a:any)=>a.name===name&&a.passed===true))missing.push("assertion: "+name);
          return {caseId:w.caseId,outcome:missing.length?"missing_evidence":"pass",missing,operationIds};
        });
        const failed=witnesses.find((w:any)=>!["pass","unsupported"].includes(w.outcome));
        const outcome=variant.implementation!=="implemented"?variant.implementation:failed?.outcome??(witnesses.some((w:any)=>w.outcome==="unsupported")?"unsupported":"pass");
        rows.push({variantId:variant.id,family:family.id,requirement:variant.requirement,implementation:variant.implementation,remaining:variant.remaining,server,block,outcome,witnesses});
      }
    }
  }
  const gaps=[...issues,...new Set(rows.filter(r=>!["pass","unsupported"].includes(r.outcome)).map(r=>r.variantId))];
  return {complete:gaps.length===0,issues,rows,gaps};
}
