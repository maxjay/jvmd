import {isDeepStrictEqual} from "node:util";
export function number(value:any):number|null{return typeof value==="number"&&Number.isFinite(value)?value:null;}

export function indexSummary(status:any){
  const index=status?.result?.index??status?.index??{};
  const timings=index.timings??{};
  return {
    phase:index.phase??"unavailable",
    artifactsDiscovered:number(index.total),
    artifactsScanned:number(index.scanned),
    indexedPublications:number(index.indexed),
    artifactsReused:number(index.reused),
    artifactsHashed:number(index.hashes),
    faults:number(index.faults),
    timings:{
      scans:number(timings.scans),
      scanMs:number(timings.scan_ms),
      discoveryMs:number(timings.discovery_ms),
      hashMs:number(timings.hash_ms),
      parseMs:number(timings.parse_ms),
      storageMs:number(timings.storage_ms),
      docsMs:number(timings.docs_ms),
      linkMs:number(timings.link_ms),
      workspaceIndexLoads:number(timings.workspace_resolution_calls),
      workspaceIndexLoadMs:number(timings.workspace_resolution_ms),
    },
    sourcePublisher:index.source_publisher??null,
    activeArtifacts:index.active_artifacts??{},
    store:index.store??null,
    storage:index.storage??null,
  };
}

export function resolverSummary(status:any){
  const resolver=status?.result?.resolver??status?.resolver??{};
  return {
    initialized:Boolean(resolver.initialized??resolver.maven_version),
    resolveCalls:number(resolver.resolve_calls),
    projectModelFastHits:number(resolver.project_model_fast_hits),
    projectModelInvalidations:number(resolver.project_model_invalidations),
    projectModelResidents:number(resolver.project_model_residents),
    coldTimings:resolver.cold_timings??{},
    nativeTimings:resolver.native_timings??{},
  };
}

export function numericDelta(after:any,before:any,key:string){
  const a=number(after?.[key]),b=number(before?.[key]);
  return a===null||b===null||a<b?null:a-b;
}

export function sumNumeric(value:any){
  if(!value||typeof value!=="object"||!Object.keys(value).length)return null;
  const values=Object.values(value).map(number);
  return values.some(x=>x===null)?null:values.reduce<number>((sum,x)=>sum+x!,0);
}

export function definitionUris(value:any){
  const rows=Array.isArray(value)?value:value?[value]:[];
  return rows.map((row:any)=>row?.uri??row?.targetUri).filter((uri:any)=>typeof uri==="string");
}

export function definitionCorrect(value:any,expectedUri:string,expectedRange:any){
  if(!expectedRange)return false;
  const rows=Array.isArray(value)?value:value?[value]:[];
  return rows.length===1&&rows[0]&&(rows[0].uri??rows[0].targetUri)===expectedUri
    &&isDeepStrictEqual(rows[0].targetSelectionRange??rows[0].range,expectedRange);
}

/** Read the advertised aggregate once; never recursively add its actor children. */
export function compilerEvidence(before:any,after:any){
  const paths:Record<string,string[]>={
    queries:["queries"],query_ms:["query_ms"],configure_calls:["configure_calls"],configure_ms:["configure_ms"],
    classpath_validations:["classpath_validations"],classpath_validation_ms:["classpath_validation_ms"],
    binding_computations:["binding_computations"],diagnostic_files_analysed:["diagnostic_files_analysed"],
    index_publish_enqueue_ms:["index_publish_enqueue_ms"],semantic_facts:["resident_semantic_state","semantic_facts"],
    semantic_units:["resident_semantic_state","semantic_units"],semantic_fact_mutations:["resident_semantic_state","semantic_fact_mutations"],
  };
  const at=(v:any,p:string[])=>number(p.reduce((o,k)=>o?.[k],v?.analyzer));
  return Object.fromEntries(Object.entries(paths).map(([key,path])=>{
    const a=at(before,path),b=at(after,path);
    return [key,{status:"unavailable",value:null,scope:"session.analyzer aggregate, counted once",
      observedBefore:a,observedAfter:b,observedDifference:a===null||b===null?null:b-a,
      reason:"Status snapshots do not establish stable actor epochs, complete contributor coverage, or causal ownership. Gauge differences are not work counters."}];
  }));
}

export function fileStateEvidence(before:any,after:any){
  const keys=["hashes","stat_hits","bytes_hashed","metadata_checks","directory_enumerations","inventory_entries"];
  return Object.fromEntries(keys.map(key=>[key,{value:numericDelta(after,before,key),status:numericDelta(after,before,key)===null?"unavailable":"measured",scope:"shared FileStateRegistry observation; not causally exclusive",reason:numericDelta(after,before,key)===null?"missing, non-finite or decreased counter":undefined}]));
}

export function methodBreakdown(metrics:any[],startNs:number,endNs:number){
  const selected=metrics.filter(row=>row.receivedNs>=startNs&&row.receivedNs<=endNs);
  return {
    status:"unavailable",documentMutationRpcMs:null,diagnosticsRpcMs:null,
    reason:"stderr receipt timestamps do not establish request ownership or server span boundaries; worker durations may overlap",
    receivedLogs:selected,
    documentOpenCalls:selected.filter(row=>row.method==="document.open").length,
    documentChangeCalls:selected.filter(row=>row.method==="document.change").length,
    diagnosticsCalls:selected.filter(row=>row.method==="lsp.diagnostics").length,
    countScope:"logs received in client interval; not causally attributed calls",
  };
}
