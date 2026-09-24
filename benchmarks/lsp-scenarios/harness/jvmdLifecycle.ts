export function number(value:any){return typeof value==="number"?value:0;}

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
  return number(after?.[key])-number(before?.[key]);
}

export function sumNumeric(value:any){
  if(!value||typeof value!=="object")return 0;
  return Object.values(value).reduce((sum:any,item:any)=>sum+(typeof item==="number"?item:0),0) as number;
}

export function definitionUris(value:any){
  const rows=Array.isArray(value)?value:value?[value]:[];
  return rows.map((row:any)=>row?.uri??row?.targetUri).filter((uri:any)=>typeof uri==="string");
}

export function definitionCorrect(value:any,expectedUri:string){
  return definitionUris(value).includes(expectedUri);
}

export function recursiveNumeric(value:any,key:string):number{
  if(!value||typeof value!=="object")return 0;
  let total=0;
  for(const [name,item] of Object.entries(value)){
    if(name===key&&typeof item==="number")total+=item;
    if(item&&typeof item==="object")total+=recursiveNumeric(item,key);
  }
  return total;
}

export function compilerEvidence(before:any,after:any){
  const keys=[
    "queries","query_ms","configure_calls","configure_ms","classpath_validations",
    "classpath_validation_ms","binding_computations","diagnostic_files_analysed",
    "index_publish_enqueue_ms","semantic_facts","semantic_units","semantic_fact_mutations",
  ];
  return Object.fromEntries(keys.map(key=>[
    key,
    recursiveNumeric(after,key)-recursiveNumeric(before,key),
  ]));
}

export function fileStateEvidence(before:any,after:any){
  const keys=["hashes","stat_hits","bytes_hashed","metadata_checks","directory_enumerations","inventory_entries"];
  return Object.fromEntries(keys.map(key=>[key,number(after?.[key])-number(before?.[key])]));
}

export function methodBreakdown(metrics:any[],startNs:number,endNs:number){
  const selected=metrics.filter(row=>row.receivedNs>=startNs&&row.receivedNs<=endNs);
  const sum=(method:string)=>selected.filter(row=>row.method===method).reduce((total,row)=>total+number(row.latencyMs),0);
  return {
    documentMutationRpcMs:sum("document.open")+sum("document.change"),
    diagnosticsRpcMs:sum("lsp.diagnostics"),
    documentOpenCalls:selected.filter(row=>row.method==="document.open").length,
    documentChangeCalls:selected.filter(row=>row.method==="document.change").length,
    diagnosticsCalls:selected.filter(row=>row.method==="lsp.diagnostics").length,
  };
}
