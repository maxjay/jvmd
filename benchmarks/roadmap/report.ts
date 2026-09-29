import {readFileSync,writeFileSync} from "node:fs";
import path from "node:path";
import {type CaseDefinition} from "./harness/ScenarioContext.ts";

const CATALOGUE=JSON.parse(readFileSync(new URL("./catalogue.json",import.meta.url),"utf8"));
const OK=new Set(["pass","unsupported","not_applicable"]);
/** What a case's outcome means on the roadmap. unverified: a legal answer the harness cannot check (e.g. unversioned diagnostics). */
export const bucket=(o:string)=>o==="pass"||o==="not_applicable"?"pass":o==="unsupported"?"missing":o==="stale"?"stale":o==="unavailable_evidence"?"unverified":o==="harness_error"?"harness":"failing";
const median=(xs:number[])=>quantile(xs,.5),p95=(xs:number[])=>quantile(xs,.95);
function quantile(xs:number[],q:number){if(!xs.length)return null;const s=[...xs].sort((a,b)=>a-b),h=(s.length-1)*q,i=Math.floor(h);return s[i]+(h-i)*(s[Math.min(i+1,s.length-1)]-s[i]);}
const ms=(v:number|null)=>v===null?"–":v<10?v.toFixed(1):v.toFixed(0);
const kb=(v:number|null)=>v===null?"–":v<1024*1024?(v/1024).toFixed(0)+" KB":(v/1024/1024).toFixed(1)+" MB";

/** Writes report.md; returns how many cases failed, went stale or hit a harness error on JVMD (or the only server run). */
export function writeReport(root:string,meta:any,results:any[],selected:CaseDefinition[]){
  const servers:string[]=meta.servers,gated=servers.includes("jvmd")?"jvmd":servers[0];
  const of=(server:string)=>results.filter(r=>r.server===server),lines:string[]=[];
  const status=(rows:any[])=>{
    if(!rows.length)return "–";
    const n=(b:string)=>rows.filter(r=>bucket(r.outcome)===b).length,pass=n("pass"),missing=n("missing");
    const label=n("failing")?"❌ failing":n("stale")?"🕒 stale":n("harness")?"⚠️ harness error":n("unverified")?"❔ unverified":missing===rows.length?"⬜ not implemented":missing?"🟡 partial":"✅ done";
    return `${label} (${pass}/${rows.length})`;
  };
  lines.push("# JVMD roadmap against JDTLS","",
    `${meta.date} · ${meta.revision.slice(0,12)}${meta.dirty?" (dirty)":""} · ${meta.jdk} · ${meta.cpus} CPUs · ${meta.runs} run(s), ${meta.warmup} warmup + ${meta.samples} samples per repeated request`,"",
    "Status is cases passing out of cases run, labelled by the worst case: ❌ wrong or failed answer · 🕒 stale (first answer after an edit out of date, later correct) · ⚠️ harness error · ❔ unverified (legal answer the harness cannot check, e.g. unversioned diagnostics) · ⬜ none implemented · 🟡 some implemented · ✅ all pass.","");
  lines.push("| Server | Pass | Not implemented | Failing | Stale | Unverified | Harness error | Median startup |","|---|---:|---:|---:|---:|---:|---:|---:|");
  for(const s of servers){const rows=of(s),n=(b:string)=>rows.filter(r=>bucket(r.outcome)===b).length;
    lines.push(`| ${s} | ${n("pass")} | ${n("missing")} | ${n("failing")} | ${n("stale")} | ${n("unverified")} | ${n("harness")} | ${ms(median(rows.filter(r=>r.startupMs).map(r=>r.startupMs)))} ms |`);}

  lines.push("","## Scenario families","",`| Family | Scenario | ${servers.join(" | ")} |`,`|---|---|${servers.map(()=>"---").join("|")}|`);
  for(const f of CATALOGUE.scenarios){
    if(!selected.some(c=>c.family===f.id))continue;
    lines.push(`| ${f.id} | ${f.family} | ${servers.map(s=>status(of(s).filter(r=>r.family===f.id))).join(" | ")} |`);
  }

  const missing=new Map<string,Set<string>>();
  for(const r of of("jvmd"))if(r.outcome==="unsupported"){const k=r.missing??"?";if(!missing.has(k))missing.set(k,new Set());missing.get(k)!.add(r.caseId);}
  if(missing.size){
    lines.push("","## Not implemented in JVMD","","Endpoints (or capabilities) JVMD does not offer yet, with the cases waiting on each.","","| Endpoint | Cases |","|---|---|");
    for(const [k,ids] of [...missing].sort((a,b)=>b[1].size-a[1].size))lines.push(`| \`${k}\` | ${[...ids].join(", ")} |`);
  }

  const broken=results.filter(r=>!OK.has(r.outcome));
  lines.push("","## Cases to look at","","Everything that is not a pass or a missing endpoint.","");
  if(!broken.length)lines.push("None.");
  else{lines.push("| Server | Case | Outcome | Detail |","|---|---|---|---|");
    for(const r of broken.sort((a,b)=>a.server.localeCompare(b.server)))
      lines.push(`| ${r.server} | ${r.caseId} | ${r.outcome} | ${(r.error??r.operations.find((o:any)=>o.outcome!=="pass")?.assertionError??"").replaceAll("|","\\|").replace(/\s+/gu," ").slice(0,160)} |`);}

  // Latency and allocation from passing requests only; "after change" is change → first correct answer.
  const buckets=new Map<string,Record<string,{latency:number[];alloc:number[]}>>();
  for(const r of results)for(const op of r.operations){
    if(op.outcome!=="pass")continue;
    const phase=op.state==="first_use"?"first":op.state==="steady"?"repeat":op.transitionMs!==undefined&&/^changed/u.test(op.state)?"after change":undefined;
    if(!phase)continue;
    const key=`${op.endpoint??op.method}\u0000${phase}`,row=buckets.get(key)??{};buckets.set(key,row);
    const cell=row[r.server]??={latency:[],alloc:[]};
    cell.latency.push(phase==="after change"?op.transitionMs:op.latencyMs);if(op.allocatedBytes!==null&&op.allocatedBytes!==undefined)cell.alloc.push(op.allocatedBytes);
  }
  lines.push("","## Latency and allocation","","Passing requests only. Latency is request → response (after change: edit → first correct answer), median / p95. Allocation is bytes allocated by the whole server JVM during the request, median; for JVMD this is the daemon JVM (child `javac`/resolver processes are not included).","",
    `| Endpoint | When | ${servers.map(s=>`${s} ms | ${s} alloc | n`).join(" | ")} |`,`|---|---|${servers.map(()=>"---:|---:|---:").join("|")}|`);
  for(const [key,row] of [...buckets].sort()){
    const [endpoint,phase]=key.split("\u0000");
    lines.push(`| \`${endpoint}\` | ${phase} | ${servers.map(s=>{const c=row[s];return c?`${ms(median(c.latency))} / ${ms(p95(c.latency))} | ${kb(median(c.alloc))} | ${c.latency.length}`:"– | – | –";}).join(" | ")} |`);
  }
  const unclean=results.filter(r=>r.shutdown);
  if(unclean.length)lines.push("",`Unclean exits (reported separately; answers above still count): ${servers.map(s=>`${s} ${unclean.filter(r=>r.server===s).length}`).join(", ")}.`);
  writeFileSync(path.join(root,"report.md"),lines.join("\n")+"\n");
  // Unverified answers are not failures: the server's reply was legal, the harness just cannot check it.
  return results.filter(r=>r.server===gated&&["failing","stale","harness"].includes(bucket(r.outcome))).length;
}
