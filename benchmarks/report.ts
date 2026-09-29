import {readFileSync,writeFileSync} from "node:fs";
import path from "node:path";
import {type CaseDefinition} from "./harness/ScenarioContext.ts";
import {OUT_OF_SCOPE_FAMILIES} from "./harness/contract.ts";

const CATALOGUE=JSON.parse(readFileSync(new URL("./catalogue.json",import.meta.url),"utf8"));
export const SCHEMA=1;

/** What a case outcome means: pass, missing (not implemented), or a problem with an implemented endpoint. */
export const bucket=(o:string)=>o==="pass"||o==="not_applicable"?"pass":o==="unsupported"?"missing":o==="stale"?"stale":o==="unavailable_evidence"?"unchecked":o==="harness_error"?"harness":"wrong";
const WORDS:Record<string,string>={pass:"pass",incorrect:"wrong answer",timeout:"timed out",protocol_error:"protocol error",stale:"stale after edit",unavailable_evidence:"can't be checked",harness_error:"harness error",unsupported:"not implemented",not_applicable:"pass"};
const SEVERITY=["wrong","stale","harness","unchecked","missing","pass"];
const median=(xs:number[])=>quantile(xs,.5),p95=(xs:number[])=>quantile(xs,.95);
function quantile(xs:number[],q:number){if(!xs.length)return null;const s=[...xs].sort((a,b)=>a-b),h=(s.length-1)*q,i=Math.floor(h);return s[i]+(h-i)*(s[Math.min(i+1,s.length-1)]-s[i]);}
const PHASES=["first","repeat","edit"];

function detail(r:any){
  const op=r.operations?.find((o:any)=>o.outcome!=="pass");
  const text=r.outcome==="timeout"&&op?`no correct ${op.endpoint??op.method} after ${String(op.state).replaceAll("_"," ")}`
    :String(r.error??op?.assertionError??"").split("\n")[0].replace(/(Assertion)?Error( \[ERR_ASSERTION\])?: /gu,"");
  return text.replace(/\s+/gu," ").trim().slice(0,160);
}

/**
 * The stable, versioned result of a run. Renderers and the next run's comparison read only this.
 * With several runs, the worst run decides a case; latency and allocation are medians over passing requests.
 */
export function summarize(meta:any,results:any[],lifecycle:any[],selected:CaseDefinition[]){
  const cases=new Map<string,any>();
  for(const r of results){const key=r.server+"\u0000"+r.caseId,prev=cases.get(key);
    if(!prev||SEVERITY.indexOf(bucket(r.outcome))<SEVERITY.indexOf(bucket(prev.outcome)))cases.set(key,r);}
  const perf=new Map<string,{latency:number[];alloc:number[]}>();
  const add=(server:string,endpoint:string,phase:string,latency:number,alloc:number|null|undefined)=>{
    const key=[server,endpoint,phase].join("\u0000"),cell=perf.get(key)??{latency:[],alloc:[]};perf.set(key,cell);
    cell.latency.push(latency);if(typeof alloc==="number")cell.alloc.push(alloc);
  };
  for(const r of results)for(const op of r.operations??[]){
    if(op.outcome!=="pass")continue;
    const phase=op.state==="first_use"?"first":op.state==="steady"?"repeat":op.transitionMs!==undefined&&/^changed/u.test(op.state)?"edit":undefined;
    if(phase)add(r.server,op.endpoint??op.method,phase,phase==="edit"?op.transitionMs:op.latencyMs,op.allocatedBytes);
  }
  return {schema:SCHEMA,meta:{revision:meta.revision,dirty:meta.dirty,date:meta.date,jdk:meta.jdk,cpus:meta.cpus,runs:meta.runs,servers:meta.servers,reference:meta.reference},
    families:CATALOGUE.scenarios.filter((f:any)=>selected.some(c=>c.family===f.id)).map((f:any)=>({id:f.id,name:f.family})),
    outOfScope:Object.entries(OUT_OF_SCOPE_FAMILIES).map(([id,reason])=>({id,reason})),
    cases:[...cases.values()].map(r=>({id:r.caseId,family:r.family,server:r.server,scope:r.scope,native:r.native,outcome:r.outcome,
      ...(r.missing?{missing:r.missing}:{}),...(bucket(r.outcome)!=="pass"&&bucket(r.outcome)!=="missing"?{detail:detail(r)}:{})})),
    performance:[...perf].map(([key,c])=>{const [server,endpoint,phase]=key.split("\u0000");
      return {server,endpoint,phase,medianMs:median(c.latency),p95Ms:p95(c.latency),medianAllocBytes:median(c.alloc),samples:c.latency.length};}),
    lifecycle:Object.fromEntries(lifecycle.map(l=>[l.server,{outcome:l.outcome,error:l.error,phases:l.phases,
      first:Object.fromEntries((l.operations??[]).filter((o:any)=>o.state==="first_use"&&o.outcome==="pass").map((o:any)=>[o.endpoint,o.latencyMs]))}])),
  };
}
export type Summary=ReturnType<typeof summarize>;

/** JDTLS's rows from a run, kept as the checked-in reference: JDTLS is pinned, so it is measured once. */
export function jdtlsReference(s:Summary){
  const jdtls=(rows:any[])=>rows.filter((r:any)=>r.server==="jdtls");
  return {schema:s.schema,meta:{revision:s.meta.revision,date:s.meta.date,jdk:s.meta.jdk,cpus:s.meta.cpus,jdtls:"1.61.0"},
    cases:jdtls(s.cases),performance:jdtls(s.performance),lifecycle:s.lifecycle.jdtls?{jdtls:s.lifecycle.jdtls}:{}};
}
/** A run without JDTLS takes JDTLS's rows from the checked-in reference, marked as such. */
export function withReference(summary:Summary,reference:any){
  if(!reference||summary.meta.servers.includes("jdtls"))return summary;
  const pick=(rows:any[])=>rows.filter((r:any)=>r.server==="jdtls");
  return {...summary,meta:{...summary.meta,servers:[...summary.meta.servers,"jdtls"],reference:{revision:reference.meta.revision,date:reference.meta.date}},
    cases:[...summary.cases,...pick(reference.cases).filter((r:any)=>summary.cases.some(c=>c.id===r.id))],
    performance:[...summary.performance,...pick(reference.performance)],
    lifecycle:{...summary.lifecycle,...(reference.lifecycle?.jdtls?{jdtls:reference.lifecycle.jdtls}:{})}};
}

// ---------- rendering ----------
const ms=(v:number|null|undefined)=>v==null?"–":v<10?v.toFixed(1)+" ms":v<1000?v.toFixed(0)+" ms":(v/1000).toFixed(1)+" s";
const bytes=(v:number|null|undefined)=>v==null?"–":v<1<<20?(v/1024).toFixed(0)+" KB":v<1<<30?(v/(1<<20)).toFixed(1)+" MB":(v/(1<<30)).toFixed(2)+" GB";
const pct=(a:number,b:number)=>`${a>b?"+":""}${Math.round((a-b)/b*100)}%`;
const cell=(s:unknown)=>String(s??"").replaceAll("|","\\|");
const ratio=(a:number|null|undefined,b:number|null|undefined)=>{if(!a||!b)return "";const r=b/a;return r>=1.1?`${r<10?r.toFixed(1):r.toFixed(0)}× less`:r<=1/1.1?`${(1/r)<10?(1/r).toFixed(1):(1/r).toFixed(0)}× more`:"≈";};
const reproduce=(id:string)=>`node benchmarks/run.ts --servers jvmd --only ${id} --output /tmp/jvmd-bench`;

function totals(s:Summary,server:string,scope?:string){
  const rows=s.cases.filter(c=>c.server===server&&(!scope||c.scope===scope)),n=(b:string)=>rows.filter(r=>bucket(r.outcome)===b).length;
  return {total:rows.length,pass:n("pass"),missing:n("missing"),wrong:n("wrong"),stale:n("stale"),unchecked:n("unchecked"),harness:n("harness")};
}
/** Scenarios JVMD claims to support but gets wrong, answers late, or could not be run. */
const attention=(s:Summary)=>s.cases.filter(c=>c.server==="jvmd"&&!["pass","missing"].includes(bucket(c.outcome)));

/** Changes against a baseline summary (main): cases fixed or regressed, and allocation or latency moves. */
export function compare(now:Summary,base?:Summary){
  if(!base)return undefined;
  const before=new Map(base.cases.filter(c=>c.server==="jvmd").map(c=>[c.id,c]));
  const fixed:any[]=[],regressed:any[]=[];
  for(const c of now.cases.filter(c=>c.server==="jvmd")){const b=before.get(c.id);if(!b)continue;
    if(bucket(b.outcome)!=="pass"&&bucket(c.outcome)==="pass")fixed.push({...c,was:b.outcome});
    else if(bucket(b.outcome)==="pass"&&bucket(c.outcome)!=="pass")regressed.push({...c,was:b.outcome});}
  // Allocation is steady across runners; latency on one shared CI runner is only a hint, so it needs a larger move.
  const moves:any[]=[],old=new Map(base.performance.filter(p=>p.server==="jvmd").map(p=>[p.endpoint+"\u0000"+p.phase,p]));
  for(const p of now.performance.filter(p=>p.server==="jvmd")){const b=old.get(p.endpoint+"\u0000"+p.phase);if(!b)continue;
    const alloc=p.medianAllocBytes!=null&&b.medianAllocBytes&&Math.abs(p.medianAllocBytes-b.medianAllocBytes)>=1<<20&&Math.abs(p.medianAllocBytes-b.medianAllocBytes)/b.medianAllocBytes>=.25;
    const latency=p.medianMs!=null&&b.medianMs&&Math.abs(p.medianMs-b.medianMs)>=10&&Math.abs(p.medianMs-b.medianMs)/b.medianMs>=.5;
    if(alloc||latency)moves.push({endpoint:p.endpoint,phase:p.phase,alloc:alloc?[b.medianAllocBytes,p.medianAllocBytes]:undefined,latency:latency?[b.medianMs,p.medianMs]:undefined});}
  return {base:base.meta,fixed,regressed,moves};
}

const LIFECYCLE:[string,string,string?][]=[
  ["Machine index (cold)","machine_index_ms"],["Open workspace (cold)","open_ms"],["Reconnect to warm daemon","reconnect_open_ms"],["First hover after reconnect","reconnect_first_ms"],
  ["Daemon restart (persisted index)","restart_index_ms"],["Open after restart","restart_open_ms"],["First hover after restart","restart_first_ms"],["Resident memory after queries","rss_bytes","bytes"],
];

/** The numbers a PR shows at a glance, with their change against main. */
export function headline(s:Summary,base?:Summary){
  const count=(x:Summary|undefined,scope:string)=>x?totals(x,"jvmd",scope):undefined;
  const delta=(now:number,was?:number)=>was===undefined||now===was?"":` (${now>was?"+":"−"}${Math.abs(now-was)})`;
  const [c,r,bc,br]=[count(s,"supported")!,count(s,"roadmap")!,count(base,"supported"),count(base,"roadmap")];
  const lj=s.lifecycle.jvmd?.phases??{},bl=base?.lifecycle?.jvmd?.phases,ld=s.lifecycle.jdtls?.phases??{};
  const cold=lj.machine_index_ms!=null&&lj.open_ms!=null?lj.machine_index_ms+lj.open_ms:undefined;
  const was=bl?.machine_index_ms!=null&&bl?.open_ms!=null?bl.machine_index_ms+bl.open_ms:undefined;
  const move=cold!==undefined&&was?Math.round((cold-was)/was*100):0;
  return {
    contract:`${c.pass}/${c.total}${delta(c.pass,bc?.pass)}`,roadmap:`${r.pass}/${r.total}${delta(r.pass,br?.pass)}`,
    cold:cold===undefined?undefined:`index ${ms(lj.machine_index_ms)} · open ${ms(lj.open_ms)}${Math.abs(move)>=10?` (${move>0?"+":"−"}${Math.abs(move)}%)`:""}`+(ld.open_ms!=null?` · JDTLS import ${ms(ld.open_ms)}`:""),
  };
}
/** One-line commit statuses for the PR checks list: always success, since they inform rather than gate. */
export function statuses(s:Summary,base?:Summary){
  const h=headline(s,base),changed=/\([+−]/u.test(h.contract+h.roadmap);
  const rows=[{context:"jvmd / scenarios",description:`Contract ${h.contract} · Roadmap ${h.roadmap}${changed?" vs main":""}`}];
  if(h.cold)rows.push({context:"jvmd / cold start",description:"Apache Maven: "+h.cold});
  return rows.map(r=>({...r,description:r.description.slice(0,140)}));
}
export function renderMarkdown(s:Summary,change?:ReturnType<typeof compare>,base?:Summary){
  const out:string[]=[];
  const reference=s.meta.reference?` · JDTLS 1.61.0 measured once at ${String(s.meta.reference.revision).slice(0,8)}`:"";
  const h=headline(s,base);
  out.push("## JVMD benchmarks","",
    `**Contract** ${h.contract} correct · **Roadmap** ${h.roadmap} scenarios`+(h.cold?` · **Cold start** ${h.cold}`:""),"",
    `<sub>${String(s.meta.revision).slice(0,8)}${s.meta.dirty?"+dirty":""} · ${s.meta.jdk.replace(/^.*version\s+"?([^"\s]+)"?.*$/u,"JDK $1")} · ${s.meta.cpus} CPUs${reference}</sub>`);
  if(change){
    out.push("");
    if(!change.fixed.length&&!change.regressed.length&&!change.moves.length)out.push(`No change since main (${String(change.base.revision).slice(0,8)}).`);
    else{
      out.push(`### Since main`,"");
      if(change.fixed.length||change.regressed.length){out.push("| | Case | Was | Now |","|---|---|---|---|");
        for(const c of change.regressed)out.push(`| ❌ | \`${c.id}\` | ${WORDS[c.was]??c.was} | ${WORDS[c.outcome]??c.outcome} |`);
        for(const c of change.fixed)out.push(`| ✅ | \`${c.id}\` | ${WORDS[c.was]??c.was} | pass |`);out.push("");}
      if(change.moves.length){out.push("| Endpoint | When | Allocation | Latency (hint) |","|---|---|--:|--:|");
        for(const m of change.moves)out.push(`| \`${m.endpoint}\` | ${m.phase} | ${m.alloc?`${bytes(m.alloc[0])} → ${bytes(m.alloc[1])} (${pct(m.alloc[1],m.alloc[0])})`:""} | ${m.latency?`${ms(m.latency[0])} → ${ms(m.latency[1])}`:""} |`);}
    }
  }
  const bad=attention(s);
  if(bad.length){
    out.push("","### Needs attention","","Supported by JVMD, but wrong, late, or not run.","","| Case | Result | Detail |","|---|---|---|");
    for(const c of bad)out.push(`| \`${c.id}\` | ${WORDS[c.outcome]??c.outcome} | ${cell(c.detail)} |`);
    out.push("","<details><summary>Reproduce</summary>","","```sh",...bad.map(c=>reproduce(c.id)),"```","","</details>");
  }
  if(Object.keys(s.lifecycle).length){
    const servers=Object.keys(s.lifecycle);
    out.push("","<details><summary><b>Lifecycle</b> on apache/maven</summary>","",`| | ${servers.map(x=>x.toUpperCase()).join(" | ")} |`,`|---|${servers.map(()=>"--:").join("|")}|`);
    for(const [label,key,kind] of LIFECYCLE){const vals=servers.map(x=>s.lifecycle[x]?.phases?.[key]);if(vals.every(v=>v==null))continue;
      out.push(`| ${label} | ${vals.map(v=>kind==="bytes"?bytes(v):ms(v)).join(" | ")} |`);}
    const endpoints=[...new Set(servers.flatMap(x=>Object.keys(s.lifecycle[x]?.first??{})))].sort();
    for(const e of endpoints)out.push(`| First \`${e.replace(/^textDocument\//u,"")}\` | ${servers.map(x=>ms(s.lifecycle[x]?.first?.[e])).join(" | ")} |`);
    for(const x of servers)if(s.lifecycle[x]?.error)out.push("",`${x.toUpperCase()}: ${cell(s.lifecycle[x].error)}`);
    out.push("","JVMD keeps one daemon per machine: its dependency index is built once, then reused by every workspace and restart.","","</details>");
  }
  const perf=s.performance.filter(p=>p.server==="jvmd"),vs=s.performance.filter(p=>p.server==="jdtls");
  if(perf.length){
    const byKey=new Map(vs.map(p=>[p.endpoint+"\u0000"+p.phase,p]));
    out.push("","<details><summary><b>Latency and allocation</b> per request</summary>","","| Endpoint | When | JVMD | JDTLS | JVMD alloc | JDTLS alloc | Alloc |","|---|---|--:|--:|--:|--:|--:|");
    for(const p of [...perf].sort((a,b)=>a.endpoint.localeCompare(b.endpoint)||PHASES.indexOf(a.phase)-PHASES.indexOf(b.phase))){
      const o=byKey.get(p.endpoint+"\u0000"+p.phase);
      out.push(`| \`${p.endpoint.replace(/^textDocument\//u,"")}\` | ${p.phase} | ${ms(p.medianMs)} | ${ms(o?.medianMs)} | ${bytes(p.medianAllocBytes)} | ${bytes(o?.medianAllocBytes)} | ${ratio(p.medianAllocBytes,o?.medianAllocBytes)} |`);}
    out.push("","Medians over passing requests on small fixtures. *edit* is the time from an edit to the first correct answer. Allocation is what the server JVM allocated during the request.","","</details>");
  }
  const missing=new Map<string,any[]>();
  for(const c of s.cases)if(c.server==="jvmd"&&c.outcome==="unsupported"){const k=c.missing??"?";missing.set(k,[...missing.get(k)??[],c]);}
  if(missing.size){
    const native=[...missing.values()].filter(rs=>rs[0].native).length;
    out.push("",`<details><summary><b>Roadmap</b>: ${missing.size} endpoints not implemented over LSP (${native} already in JVMD's own API)</summary>`,"",
      "| Endpoint | Scenarios | JVMD API today |","|---|--:|---|");
    for(const [k,rs] of [...missing].sort((a,b)=>Number(!!b[1][0].native)-Number(!!a[1][0].native)||b[1].length-a[1].length||a[0].localeCompare(b[0])))
      out.push(`| \`${k}\` | ${rs.length} | ${rs[0].native?"`"+rs[0].native+"`":""} |`);
    out.push("","</details>");
  }
  const servers=[...new Set(s.cases.map(c=>c.server))];
  out.push("",`<details><summary><b>Scenario families</b></summary>`,"",`| | Family | ${servers.map(x=>x.toUpperCase()).join(" | ")} |`,`|---|---|${servers.map(()=>"--:").join("|")}|`);
  for(const f of s.families){
    const mark=(x:string)=>{const rs=s.cases.filter(c=>c.family===f.id&&c.server===x);if(!rs.length)return "not measured";const pass=rs.filter(c=>bucket(c.outcome)==="pass").length;
      const icon=rs.some(c=>["wrong","harness"].includes(bucket(c.outcome)))?"❌":rs.some(c=>bucket(c.outcome)==="stale")?"🕒":pass===rs.length?"✅":rs.some(c=>bucket(c.outcome)==="unchecked")?"⚠️":pass?"🟡":"⬜";
      return `${icon} ${pass}/${rs.length}`;};
    out.push(`| ${f.id} | ${f.name} | ${servers.map(mark).join(" | ")} |`);
  }
  for(const o of s.outOfScope)out.push(`| ${o.id} | *out of scope: ${o.reason}* | ${servers.map(()=>"").join(" | ")} |`);
  out.push("","✅ all pass · 🟡 partly implemented · ⬜ not implemented · ❌ something implemented is wrong · 🕒 stale after an edit · ⚠️ can't be checked","","</details>");
  const short=s.cases.filter(c=>c.server==="jdtls"&&!["pass","missing"].includes(bucket(c.outcome)));
  if(short.length){
    out.push("",`<details><summary><b>Where JDTLS falls short</b>: ${short.length} scenarios</summary>`,"","| Case | Result | Detail |","|---|---|---|");
    for(const c of short)out.push(`| \`${c.id}\` | ${WORDS[c.outcome]??c.outcome} | ${cell(c.detail)} |`);
    out.push("","</details>");
  }
  return out.join("\n")+"\n";
}

export function renderText(s:Summary,change?:ReturnType<typeof compare>){
  const out:string[]=[],servers=[...new Set(s.cases.map(c=>c.server))];
  const pad=(v:unknown,n:number)=>String(v).padEnd(n),rpad=(v:unknown,n:number)=>String(v).padStart(n);
  out.push(`jvmd benchmarks · ${String(s.meta.revision).slice(0,8)}${s.meta.dirty?"+dirty":""}`,"");
  for(const x of servers){const sup=totals(s,x,"supported"),road=totals(s,x,"roadmap");
    out.push(`  ${pad(x,5)}  contract ${rpad(sup.pass,3)}/${pad(sup.total,3)}  roadmap ${rpad(road.pass,3)}/${pad(road.total,3)}  not implemented ${rpad(sup.missing+road.missing,3)}`);}
  if(change){out.push("","since main");
    if(!change.fixed.length&&!change.regressed.length&&!change.moves.length)out.push("  no change");
    for(const c of change.regressed)out.push(`  - ${pad(c.id,40)} ${WORDS[c.was]??c.was} → ${WORDS[c.outcome]??c.outcome}`);
    for(const c of change.fixed)out.push(`  + ${pad(c.id,40)} now passes`);
    for(const m of change.moves)out.push(`  ~ ${pad(m.endpoint+" "+m.phase,40)} ${m.alloc?`${bytes(m.alloc[0])} → ${bytes(m.alloc[1])}`:""} ${m.latency?`${ms(m.latency[0])} → ${ms(m.latency[1])}`:""}`);}
  const bad=attention(s);
  if(bad.length){out.push("","needs attention");for(const c of bad)out.push(`  ${pad(c.id,40)} ${pad(WORDS[c.outcome]??c.outcome,16)} ${c.detail}`);}
  const life=Object.keys(s.lifecycle);
  if(life.length){out.push("","lifecycle (apache/maven)"+" ".repeat(18)+life.map(x=>rpad(x,10)).join(""));
    for(const [label,key,kind] of LIFECYCLE){const vals=life.map(x=>s.lifecycle[x]?.phases?.[key]);if(vals.every(v=>v==null))continue;
      out.push(`  ${pad(label,40)}${vals.map(v=>rpad(kind==="bytes"?bytes(v):ms(v),10)).join("")}`);}}
  return out.join("\n")+"\n";
}

export function writeReport(root:string,summary:Summary,baseline?:Summary){
  const change=compare(summary,baseline);
  writeFileSync(path.join(root,"summary.json"),JSON.stringify(summary,null,1)+"\n");
  writeFileSync(path.join(root,"report.md"),renderMarkdown(summary,change,baseline));
  writeFileSync(path.join(root,"statuses.json"),JSON.stringify(statuses(summary,baseline),null,1)+"\n");
  const text=renderText(summary,change);writeFileSync(path.join(root,"report.txt"),text);
  return {text,regressions:change?.regressed??[]};
}
