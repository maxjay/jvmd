import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

export const CONTRACT = JSON.parse(readFileSync(new URL("../../measurement-contract.json", import.meta.url), "utf8"));
export type Evidence = {status:"measured"|"verified"|"unavailable"|"contradicted"|"not_applicable"; value?:number|null; reason?:string; unit?:string};
export type DiagnosticVersionMode = "unknown"|"versioned"|"versionless";

export function diagnosticDecision(mode:DiagnosticVersionMode, params:any, uri:string, version:number,
  incarnation=1, eventIncarnation?:number):{kind:"ignore"|"verified"|"unavailable";mode:DiagnosticVersionMode}{
  if (params?.uri !== uri) return {kind:"ignore",mode};
  if (eventIncarnation !== undefined && eventIncarnation !== incarnation) return {kind:"ignore",mode};
  if (Number.isInteger(params?.version) && params.version === version) {
    if (incarnation > 1 && eventIncarnation === undefined) return {kind:"unavailable",mode};
    return {kind:"verified",mode:"versioned"};
  }
  if (params?.version === undefined && mode !== "versioned") return {kind:"unavailable",mode:"versionless"};
  return {kind:"ignore",mode};
}

export function measured(value:unknown,unit:string,reason="metric missing or non-finite"):Evidence {
  return typeof value === "number" && Number.isFinite(value)
    ? {status:"measured",value,unit} : {status:"unavailable",value:null,unit,reason};
}

export function counterDelta(before:any,after:any,key:string):Evidence {
  if (!before || !after || typeof before.owner!=="string" || !before.owner || before.epoch==null || before.owner !== after.owner || before.epoch !== after.epoch)
    return {status:"unavailable",value:null,reason:"counter owner or reset epoch is missing/changed"};
  const a=before.values?.[key], b=after.values?.[key];
  if (typeof a !== "number" || typeof b !== "number" || !Number.isFinite(a) || !Number.isFinite(b))
    return {status:"unavailable",value:null,reason:"counter not observed in both snapshots"};
  if (b<a) return {status:"contradicted",value:null,reason:"counter decreased within an epoch"};
  return measured(b-a,"count");
}

/** Legacy schema-3 files remain diagnosable, but every expected assertion is mandatory. */
export function legacyFailures(report:any):string[] {
  const failures:string[]=[];
  const check=(value:any,name:string)=>{if(value!==true)failures.push(name+" is not true");};
  if (!report || report.schema!==3) return ["expected legacy schema 3 report"];
  if (!report.correctness?.legacy || !Object.keys(report.correctness.legacy).length) failures.push("legacy correctness missing");
  for (const [name,value] of Object.entries(report.correctness?.legacy??{})) check(value,"legacy."+name);
  for (const [name,series] of Object.entries<any>(report.operations??{})) {
    const c=report.correctness?.operationCorrectness?.[name];
    check(c?.firstUse,name+".firstUse");
    for (const state of ["warmup","steady"]) {
      const expected=state==="warmup"?report.phaseModel?.defaults?.warmup:report.phaseModel?.defaults?.steady_samples;
      const rows=series?.[state], flags=c?.[state];
      if (!Number.isInteger(expected) || expected<1 || !Array.isArray(rows) || rows.length!==expected
          || !Array.isArray(flags) || flags.length!==expected) failures.push(name+"."+state+" sample count invalid");
      for (const [i,value] of (Array.isArray(flags)?flags:[]).entries()) check(value,`${name}.${state}[${i}]`);
    }
    for (const row of [series?.firstUse,...(series?.warmup??[]),...(series?.steady??[])])
      if (!Number.isFinite(row?.metrics?.latencyMs) || row.metrics.latencyMs<0) failures.push(name+" invalid request timing");
  }
  if (!Object.keys(report.operations??{}).length) failures.push("operations missing");
  return failures;
}

export function enforceLegacy(report:any) {
  const failures=legacyFailures(report);
  assert.equal(failures.length,0,"Benchmark correctness gate failed: "+failures.join("; "));
}

export function intervalUnion(spans:{startNs:bigint;endNs:bigint;clockDomain:string}[],clockDomain:string):bigint {
  const rows=spans.map(s=>{
    assert.equal(s.clockDomain,clockDomain,"mixed clock domains");
    assert(s.endNs>=s.startNs,"negative span"); return s;
  }).sort((a,b)=>a.startNs<b.startNs?-1:a.startNs>b.startNs?1:0);
  let total=0n,start:bigint|undefined,end=0n;
  for(const row of rows){
    if(start===undefined){start=row.startNs;end=row.endNs;}
    else if(row.startNs>end){total+=end-start;start=row.startNs;end=row.endNs;}
    else if(row.endNs>end)end=row.endNs;
  }
  return start===undefined?0n:total+end-start;
}
