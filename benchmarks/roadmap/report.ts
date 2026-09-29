import {readFileSync,writeFileSync} from "node:fs";
import path from "node:path";
import {type CaseDefinition} from "./harness/ScenarioContext.ts";

const CATALOGUE=JSON.parse(readFileSync(new URL("./catalogue.json",import.meta.url),"utf8"));

/** Collapses a case outcome into what it means for the roadmap. */
export const bucket=(o:string)=>o==="pass"||o==="not_applicable"?"pass":o==="unsupported"?"missing":o==="stale"?"stale":o==="unavailable_evidence"?"unchecked":o==="harness_error"?"harness":"failing";
const WORDS:Record<string,string>={incorrect:"wrong answer",timeout:"timed out",protocol_error:"protocol error",stale:"stale after edit",unavailable_evidence:"can't be checked",harness_error:"harness error"};
const SEVERITY=["failing","stale","harness","unchecked","missing","pass"];

const median=(xs:number[])=>quantile(xs,.5),p95=(xs:number[])=>quantile(xs,.95);
function quantile(xs:number[],q:number){if(!xs.length)return null;const s=[...xs].sort((a,b)=>a-b),h=(s.length-1)*q,i=Math.floor(h);return s[i]+(h-i)*(s[Math.min(i+1,s.length-1)]-s[i]);}
const ms=(v:number|null)=>v===null?"–":v<10?v.toFixed(1):v.toFixed(0);
const bytes=(v:number|null)=>v===null?"–":v<1024*1024?(v/1024).toFixed(0)+" KB":(v/1024/1024).toFixed(1)+" MB";
const pct=(a:number,b:number)=>`${a>b?"+":""}${Math.round((a-b)/b*100)}%`;
const cell=(s:string)=>s.replaceAll("|","\\|").replace(/\s+/gu," ").trim();

/** One row per server and case; with several runs, the worst run decides. */
export function caseStatus(results:any[]){
  const out=new Map<string,any>();
  for(const r of results){const key=r.server+"\u0000"+r.caseId,prev=out.get(key);
    if(!prev||SEVERITY.indexOf(bucket(r.outcome))<SEVERITY.indexOf(bucket(prev.outcome)))out.set(key,r);}
  return out;
}

/** Median latency and allocation of passing requests per endpoint and phase ("after change" is edit → first correct answer). */
export function performance(results:any[]){
  const out=new Map<string,Record<string,{latency:number[];alloc:number[]}>>();
  for(const r of results)for(const op of r.operations){
    if(op.outcome!=="pass")continue;
    const phase=op.state==="first_use"?"first":op.state==="steady"?"repeat":op.transitionMs!==undefined&&/^changed/u.test(op.state)?"after change":undefined;
    if(!phase)continue;
    const key=`${op.endpoint??op.method}\u0000${phase}`,row=out.get(key)??{};out.set(key,row);
    const c=row[r.server]??={latency:[],alloc:[]};
    c.latency.push(phase==="after change"?op.transitionMs:op.latencyMs);
    if(typeof op.allocatedBytes==="number")c.alloc.push(op.allocatedBytes);
  }
  return out;
}

function detail(r:any){
  const op=r.operations.find((o:any)=>o.outcome!=="pass");
  if(r.outcome==="timeout"&&op)return `no correct \`${op.endpoint??op.method}\` answer in time (${op.state.replaceAll("_"," ")})`;
  return cell((r.error??op?.assertionError??"").split("\n")[0].replace(/(Assertion)?Error( \[ERR_ASSERTION\])?: /gu,"")).slice(0,140);
}

/**
 * Writes report.md: a short headline, what changed against a baseline run (usually main), what needs
 * attention, and the full roadmap, API gaps and performance tables folded underneath. Returns the
 * regressions against the baseline so the caller can annotate them. Nothing here decides pass/fail.
 */
export function writeReport(root:string,meta:any,results:any[],selected:CaseDefinition[],baseline?:any[]){
  const servers:string[]=meta.servers,lines:string[]=[],status=caseStatus(results);
  const rows=(server:string)=>[...status.values()].filter(r=>r.server===server);
  const count=(server:string,b:string)=>rows(server).filter(r=>bucket(r.outcome)===b).length;
  const lead=servers.includes("jvmd")?"jvmd":servers[0],total=rows(lead).length,passing=count(lead,"pass");
  const attention=rows(lead).filter(r=>!["pass","missing"].includes(bucket(r.outcome)));

  lines.push("## JVMD roadmap","",
    `**${lead==="jvmd"?"JVMD":lead} passes ${passing} of ${total} editor scenarios (${Math.round(passing/Math.max(total,1)*100)}%).** `+
    [...count(lead,"missing")?[`${count(lead,"missing")} not implemented yet`]:[],...attention.length?[`${attention.length} need${attention.length===1?"s":""} attention`]:[],
      ...servers.filter(s=>s!==lead).map(s=>`${s.toUpperCase()} passes ${count(s,"pass")}`)].join(" · ")+".","",
    `<sub>${meta.revision.slice(0,10)}${meta.dirty?" (dirty)":""} · ${meta.jdk.replace(/^.*version\s+/u,"JDK ").replaceAll('"',"")} · ${meta.cpus} CPUs · ${meta.runs} run${meta.runs===1?"":"s"}</sub>`);

  const regressions:any[]=[];
  if(baseline){
    const before=caseStatus(baseline),fixed:string[]=[];
    for(const r of rows(lead)){const b=before.get(r.server+"\u0000"+r.caseId);if(!b)continue;
      const was=bucket(b.outcome),now=bucket(r.outcome);
      if(was!=="pass"&&now==="pass")fixed.push(r.caseId);
      else if(was==="pass"&&now!=="pass")regressions.push({...r,was:b.outcome});}
    const perf=performanceChanges(performance(results.filter(r=>r.server===lead)),performance(baseline.filter(r=>r.server===lead)),lead);
    lines.push("","### Since main","");
    if(!fixed.length&&!regressions.length&&!perf.length)lines.push("No change in results or performance.");
    if(fixed.length)lines.push(`✅ **Now passing (${fixed.length}):** ${fixed.map(id=>`\`${id}\``).join(", ")}`,"");
    if(regressions.length){lines.push(`❌ **Regressed (${regressions.length}):**`,"","| Case | Was | Now | Detail |","|---|---|---|---|");
      for(const r of regressions)lines.push(`| \`${r.caseId}\` | ${WORDS[r.was]??r.was} | ${WORDS[r.outcome]??r.outcome} | ${detail(r)} |`);lines.push("");}
    if(perf.length){lines.push("Performance moves over 25% (single CI runner, so treat as a hint):","","| Endpoint | When | Latency | Allocation |","|---|---|---:|---:|",...perf);}
  }

  if(attention.length){
    lines.push("","### Needs attention","","Scenarios JVMD implements but gets wrong, answers late, or couldn't be run.","","| Case | Result | Detail |","|---|---|---|");
    for(const r of attention)lines.push(`| \`${r.caseId}\` | ${WORDS[r.outcome]??r.outcome} | ${detail(r)} |`);
  }

  const mark=(rs:any[])=>{
    if(!rs.length)return "";
    const n=(b:string)=>rs.filter(r=>bucket(r.outcome)===b).length,pass=n("pass");
    const icon=n("failing")||n("harness")?"❌":n("stale")?"🕒":pass===rs.length?"✅":n("unchecked")?"⚠️":pass?"🟡":"⬜";
    return `${icon} ${pass}/${rs.length}`;
  };
  const families=CATALOGUE.scenarios.filter((f:any)=>selected.some(c=>c.family===f.id));
  const done=families.filter((f:any)=>{const rs=rows(lead).filter(r=>r.family===f.id);return rs.length&&rs.every(r=>bucket(r.outcome)==="pass");}).length;
  lines.push("",`<details><summary><b>Scenario families</b>: ${done} of ${families.length} complete</summary>`,"",
    `| | Scenario | ${servers.map(s=>s.toUpperCase()).join(" | ")} |`,`|---|---|${servers.map(()=>":--").join("|")}|`);
  for(const f of families)lines.push(`| ${f.id} | ${f.family} | ${servers.map(s=>mark(rows(s).filter(r=>r.family===f.id))).join(" | ")} |`);
  lines.push("","✅ all pass · 🟡 partly implemented · ⬜ not implemented · ❌ something implemented is wrong · 🕒 first answer after an edit is stale · ⚠️ answer can't be checked","","</details>");

  const missing=new Map<string,any[]>();
  for(const r of rows(lead))if(r.outcome==="unsupported"){const k=r.missing??"?";missing.set(k,[...missing.get(k)??[],r]);}
  if(missing.size){
    lines.push("",`<details><summary><b>Not implemented</b>: ${missing.size} endpoint${missing.size===1?"":"s"}, ${count(lead,"missing")} scenario${count(lead,"missing")===1?"":"s"} waiting</summary>`,"",
      "Decided by JVMD itself: a capability or command it doesn't advertise, or a \"method not found\" reply. Implementing one turns its scenarios on automatically.","",
      "| Endpoint | Scenarios | Families |","|---|--:|---|");
    for(const [k,rs] of [...missing].sort((a,b)=>b[1].length-a[1].length||a[0].localeCompare(b[0])))lines.push(`| \`${k}\` | ${rs.length} | ${[...new Set(rs.map(r=>r.family))].join(", ")} |`);
    lines.push("","</details>");
  }

  // Head-to-head only: endpoints the lead server answers. Everything else is in results.json.
  const perf=new Map([...performance(results)].filter(([,row])=>row[lead]));
  if(perf.size){
    const endpoints=new Set([...perf.keys()].map(k=>k.split("\u0000")[0])).size;
    lines.push("",`<details><summary><b>Latency and allocation</b>: the ${endpoints} endpoints ${lead.toUpperCase()} answers correctly</summary>`,"",
      "Latency is median / p95 in ms; *after change* is the edit to the first correct answer. Allocation is the median bytes the server JVM allocated during the request (JVMD: the daemon, not child processes). Other endpoints are in `results.json`.","",
      `| Endpoint | When | ${servers.map(s=>`${s.toUpperCase()} ms | ${s.toUpperCase()} alloc`).join(" | ")} |`,`|---|---|${servers.map(()=>"--:|--:").join("|")}|`);
    const order=(k:string)=>k.replace(/\u0000(first|repeat|after change)$/u,(_,p)=>"\u0000"+["first","repeat","after change"].indexOf(p));
    for(const [key,row] of [...perf].sort((a,b)=>order(a[0]).localeCompare(order(b[0])))){const [endpoint,phase]=key.split("\u0000");
      lines.push(`| \`${endpoint}\` | ${phase} | ${servers.map(s=>{const c=row[s];return c?`${ms(median(c.latency))} / ${ms(p95(c.latency))} | ${bytes(median(c.alloc))}`:"– | –";}).join(" | ")} |`);}
    lines.push("","</details>");
  }

  const others=servers.filter(s=>s!==lead).flatMap(s=>rows(s).filter(r=>!["pass","missing"].includes(bucket(r.outcome))));
  if(others.length){
    lines.push("",`<details><summary><b>Reference server notes</b>: ${others.length} scenarios where ${servers.filter(s=>s!==lead).map(s=>s.toUpperCase()).join(", ")} fell short</summary>`,"",
      "Useful context: these are behaviours the reference server doesn't get right either.","","| Server | Case | Result | Detail |","|---|---|---|---|");
    for(const r of others)lines.push(`| ${r.server.toUpperCase()} | \`${r.caseId}\` | ${WORDS[r.outcome]??r.outcome} | ${detail(r)} |`);
    lines.push("","</details>");
  }
  const unclean=results.filter(r=>r.shutdown);
  if(unclean.length)lines.push("",`<sub>Unclean exits (answers still count): ${servers.map(s=>`${s.toUpperCase()} ${unclean.filter(r=>r.server===s).length}`).join(", ")}.</sub>`);

  writeFileSync(path.join(root,"report.md"),lines.join("\n")+"\n");
  return regressions;
}

function performanceChanges(now:ReturnType<typeof performance>,before:ReturnType<typeof performance>,server:string){
  const out:string[]=[];
  for(const [key,row] of [...now].sort()){
    const a=row[server],b=before.get(key)?.[server];if(!a||!b)continue;
    const [la,lb,xa,xb]=[median(a.latency),median(b.latency),median(a.alloc),median(b.alloc)];
    const latency=la!==null&&lb&&Math.abs(la-lb)>=5&&Math.abs(la-lb)/lb>=.25,alloc=xa!==null&&xb&&Math.abs(xa-xb)>=1<<20&&Math.abs(xa-xb)/xb>=.25;
    if(!latency&&!alloc)continue;
    const [endpoint,phase]=key.split("\u0000");
    out.push(`| \`${endpoint}\` | ${phase} | ${latency?`${ms(lb)} → ${ms(la)} ms (${pct(la!,lb!)})`:"–"} | ${alloc?`${bytes(xb)} → ${bytes(xa)} (${pct(xa!,xb!)})`:"–"} |`);
  }
  return out;
}
