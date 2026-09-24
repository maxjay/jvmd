import { readFileSync } from "node:fs";
import path from "node:path";

export type PhaseName =
  | "startup"
  | "machine_index"
  | "initialize"
  | "service_ready"
  | "session_open"
  | "workspace_resolution"
  | "workspace_index"
  | "document_admission"
  | "first_use"
  | "warmup"
  | "steady";

export const PHASE_MODEL = JSON.parse(
  readFileSync(path.resolve("benchmarks/phase-model.json"), "utf8"),
) as {
  schema:number;
  states:PhaseName[];
  defaults:{warmup:number;steady_samples:number};
  rules:Record<PhaseName,string>;
};

export function percentile(values:number[], quantile:number){
  if(!values.length)return null;
  const sorted=[...values].sort((a,b)=>a-b);
  return sorted[Math.ceil(quantile*sorted.length)-1];
}

export function latencyStats(values:number[]){
  return {
    samples:values.length,
    p50Ms:percentile(values,.5),
    p95Ms:values.length>=20?percentile(values,.95):null,
    minMs:values.length?Math.min(...values):null,
    maxMs:values.length?Math.max(...values):null,
  };
}

export function elapsedMs(origin:number,value:number|undefined){
  return value===undefined?null:(value-origin)/1e6;
}

export function orderedMilestones(milestones:Record<string,number>, names:string[]){
  const values=names.map(name=>milestones[name]);
  return values.every(value=>Number.isFinite(value))
    && values.every((value,index)=>index===0||value>=values[index-1]);
}
