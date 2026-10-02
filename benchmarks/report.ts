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

/** JDTLS 1.61.0 is a fixed reference: its rows are measured once and checked in. Rows for cases measured
 * now replace or extend the existing reference; everything else is kept as it was. */
export function jdtlsReference(s:Summary,existing?:any){
  const jdtls=(rows:any[])=>rows.filter((r:any)=>r.server==="jdtls");
  const keep=(old:any[]|undefined,now:any[],key:(r:any)=>string)=>{const fresh=new Set(now.map(key));return [...(old??[]).filter(r=>!fresh.has(key(r))),...now];};
  return {schema:s.schema,meta:existing?.meta??{revision:s.meta.revision,date:s.meta.date,jdk:s.meta.jdk,cpus:s.meta.cpus,jdtls:"1.61.0"},
    cases:keep(existing?.cases,jdtls(s.cases),r=>r.id),
    performance:keep(existing?.performance,jdtls(s.performance),p=>p.endpoint+"\u0000"+p.phase),
    lifecycle:s.lifecycle.jdtls?{jdtls:s.lifecycle.jdtls}:existing?.lifecycle??{}};
}
/**
 * Fills JDTLS's columns from the reference. JDTLS rows measured in this run (new scenarios) are kept;
 * everything else comes from the reference, which is steadier than a handful of fresh samples.
 */
export function withReference(summary:Summary,reference:any){
  if(!reference||summary.meta.servers.includes("jdtls"))return summary;
  const jdtls=(rows:any[])=>rows.filter((r:any)=>r.server==="jdtls");
  const measured=new Set(jdtls(summary.cases).map(c=>c.id));
  const cases=jdtls(reference.cases).filter((r:any)=>!measured.has(r.id)&&summary.cases.some(c=>c.id===r.id));
  const fromReference=jdtls(reference.performance),covered=new Set(fromReference.map((p:any)=>p.endpoint+"\u0000"+p.phase));
  const performance=[...summary.performance.filter(p=>p.server!=="jdtls"||!covered.has(p.endpoint+"\u0000"+p.phase)),...fromReference];
  return {...summary,meta:{...summary.meta,servers:[...summary.meta.servers,"jdtls"],reference:{revision:reference.meta?.revision,date:reference.meta?.date}},
    cases:[...summary.cases,...cases],performance,
    lifecycle:{...(reference.lifecycle?.jdtls?{jdtls:reference.lifecycle.jdtls}:{}),...summary.lifecycle}};
}

// ---------- rendering ----------
const ms=(v:number|null|undefined)=>v==null?"–":v<10?v.toFixed(1)+" ms":v<1000?v.toFixed(0)+" ms":(v/1000).toFixed(1)+" s";
const pct=(a:number,b:number)=>`${a>b?"+":""}${Math.round((a-b)/b*100)}%`;

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

/** "Open" is launch to the first correct answer on the project, not the server's own readiness signal. */
const LIFECYCLE:[string,string,string?][]=[
  ["Machine index (cold)","machine_index_ms"],["Open workspace (cold)","open_ms"],["Reconnect to warm daemon","reconnect_open_ms"],
  ["Daemon restart (persisted index)","restart_index_ms"],["Open after restart","restart_open_ms"],["Restart to first correct completion","restart_first_completion_ms"],["Resident memory after queries","rss_bytes","bytes"],
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
// ---------- readout ----------
/** A readout line: "+" good (green on GitHub), "-" needs attention (red), " " neutral. */
type Tone="+"|"-"|" ";type Line=[Tone,string];
type Section={title:string;lines:Line[];fold?:boolean};
const short=(e:string)=>e.replace(/^textDocument\//u,"");
const dur=(v:number|null|undefined)=>v==null?"-":v<10?v.toFixed(1)+"ms":v<1000?v.toFixed(0)+"ms":(v/1000).toFixed(1)+"s";
const size=(v:number|null|undefined)=>v==null?"-":v<1<<20?(v/1024).toFixed(0)+"K":v<1<<30?(v/(1<<20)).toFixed(1)+"M":(v/(1<<30)).toFixed(2)+"G";
/** █ pass, ▒ implemented but not passing, ░ not implemented. */
function bar(pass:number,bad:number,total:number,width=30){
  if(!total)return " ".repeat(width);
  const p=Math.round(pass/total*width),b=bad?Math.max(1,Math.round(bad/total*width)):0;
  return "█".repeat(p)+"▒".repeat(Math.min(b,width-p))+"░".repeat(Math.max(0,width-p-b));
}
/** Aligned columns; a header starting with ">" is right-aligned. Rows carry their tone. */
function table(head:string[],rows:[Tone,string[]][]):Line[]{
  const right=head.map(h=>h.startsWith(">")),h=head.map(x=>x.replace(/^>/u,""));
  const all=[h,...rows.map(r=>r[1])],w=h.map((_,i)=>Math.max(...all.map(r=>(r[i]??"").length)));
  const fmt=(r:string[])=>r.map((c,i)=>right[i]?(c??"").padStart(w[i]):(c??"").padEnd(w[i])).join("  ").trimEnd();
  return [[" ",fmt(h)],...rows.map(([t,r]):Line=>[t,fmt(r)])];
}
const versus=(a:number|null|undefined,b:number|null|undefined,better:string,worse:string):[Tone,string]=>{
  if(!a||!b)return [" ",""];const r=b/a,f=(x:number)=>x<10?x.toFixed(1):x.toFixed(0);
  return r>=1.1?["+",`${f(r)}x ${better}`]:r<=1/1.1?["-",`${f(1/r)}x ${worse}`]:[" ","same"];
};

export function readout(s:Summary,change?:ReturnType<typeof compare>,base?:Summary):Section[]{
  const out:Section[]=[],servers=[...new Set(s.cases.map(c=>c.server))],h=headline(s,base);
  const jdk=s.meta.jdk.match(/version "([^"]+)"/u)?.[1]??s.meta.jdk;
  const head:Line[]=[];
  for(const x of servers){const rows=s.cases.filter(c=>c.server===x),pass=rows.filter(c=>bucket(c.outcome)==="pass").length,
    bad=rows.filter(c=>!["pass","missing"].includes(bucket(c.outcome))).length;
    head.push([" ",`${x.padEnd(5)}  ${bar(pass,bad,rows.length)}  ${String(pass).padStart(3)}/${rows.length}`+
      (x==="jvmd"?`   contract ${h.contract} · roadmap ${h.roadmap}`:s.meta.reference?"   fixed reference 1.61.0":"")]);}
  head.push([" ",""],[" ","█ pass  ▒ implemented, not passing  ░ not implemented"]);
  if(h.cold)head.push([" ",""],[" ","apache/maven cold start: "+h.cold]);
  out.push({title:`jvmd benchmarks · ${String(s.meta.revision).slice(0,8)}${s.meta.dirty?"+dirty":""} · jdk ${jdk} · ${s.meta.cpus} cpu`,lines:head});
  if(change){
    const lines:Line[]=[];
    for(const c of change.regressed)lines.push(["-",`${c.id.padEnd(40)} ${WORDS[c.was]??c.was} → ${WORDS[c.outcome]??c.outcome}`]);
    for(const c of change.fixed)lines.push(["+",`${c.id.padEnd(40)} ${WORDS[c.was]??c.was} → pass`]);
    for(const m of change.moves){const worse=(m.alloc&&m.alloc[1]>m.alloc[0])||(m.latency&&m.latency[1]>m.latency[0]);
      lines.push([worse?"-":"+",`${(short(m.endpoint)+" "+m.phase).padEnd(40)} ${m.alloc?`alloc ${size(m.alloc[0])} → ${size(m.alloc[1])} (${pct(m.alloc[1],m.alloc[0])})`:""}${m.latency?`  latency ${dur(m.latency[0])} → ${dur(m.latency[1])}`:""}`]);}
    out.push({title:`since main · ${String(change.base.revision).slice(0,8)}`,lines:lines.length?lines:[[" ","no change"]]});
  }
  const bad=attention(s);
  if(bad.length)out.push({title:"needs attention · supported by JVMD, but wrong, late or not run",lines:[
    ...table(["case","result","detail"],bad.map(c=>["-",[c.id,WORDS[c.outcome]??c.outcome,String(c.detail??"").slice(0,90)]])),
    [" ",""],[" ","reproduce: node benchmarks/run.ts --servers jvmd --only "+bad.map(c=>c.id).join(",")+" --output /tmp/jvmd-bench"]]});

  const mark=(x:string,family:string)=>{const rs=s.cases.filter(c=>c.family===family&&c.server===x);if(!rs.length)return "not measured";
    const pass=rs.filter(c=>bucket(c.outcome)==="pass").length,flag=rs.some(c=>["wrong","harness"].includes(bucket(c.outcome)))?"!":rs.some(c=>bucket(c.outcome)==="stale")?"~":rs.some(c=>bucket(c.outcome)==="unchecked")?"?":"";
    return `${bar(pass,rs.length-pass-rs.filter(c=>bucket(c.outcome)==="missing").length,rs.length,10)} ${String(pass).padStart(2)}/${String(rs.length).padEnd(2)} ${flag}`.trimEnd();};
  const done=s.families.filter(f=>{const rs=s.cases.filter(c=>c.family===f.id&&c.server==="jvmd");return rs.length&&rs.every(c=>bucket(c.outcome)==="pass");}).length;
  out.push({title:`families · ${done}/${s.families.length} complete`,fold:true,lines:[
    ...table(["id","scenario",...servers],s.families.map(f=>{const rs=s.cases.filter(c=>c.family===f.id&&c.server==="jvmd");
      const tone:Tone=rs.some(c=>!["pass","missing"].includes(bucket(c.outcome)))?"-":rs.length&&rs.every(c=>bucket(c.outcome)==="pass")?"+":" ";
      return [tone,[f.id,f.name.toLowerCase(),...servers.map(x=>mark(x,f.id))]];})),
    ...s.outOfScope.map((o):Line=>[" ",`${o.id.padEnd(Math.max(2,...s.families.map(f=>f.id.length)))}  out of scope: ${o.reason}`]),
    [" ",""],[" ","! wrong or failed  ~ stale after an edit  ? can't be checked"]]});

  const life=Object.keys(s.lifecycle);
  if(life.length){
    const rows:[Tone,string[]][]=[];
    for(const [label,key,kind] of LIFECYCLE){const vals=life.map(x=>s.lifecycle[x]?.phases?.[key]);if(vals.every(v=>v==null))continue;
      rows.push([" ",[label.toLowerCase(),...vals.map(v=>kind==="bytes"?size(v):dur(v))]]);}
    for(const e of [...new Set(life.flatMap(x=>Object.keys(s.lifecycle[x]?.first??{})))].sort())
      rows.push([" ",["first "+short(e),...life.map(x=>dur(s.lifecycle[x]?.first?.[e]))]]);
    const lines=table(["",...life.map(x=>">"+x)],rows);
    for(const x of life)if(s.lifecycle[x]?.error)lines.push(["-",`${x}: ${String(s.lifecycle[x].error).slice(0,110)}`]);
    lines.push([" ",""],[" ","open = launch to the first correct answer · jvmd keeps one daemon and its index per machine"]);
    out.push({title:"lifecycle · apache/maven",fold:true,lines});
  }

  const missing=new Map<string,any[]>();
  for(const c of s.cases)if(c.server==="jvmd"&&c.outcome==="unsupported"){const k=c.missing??"?";missing.set(k,[...missing.get(k)??[],c]);}
  if(missing.size){
    const sorted=[...missing].sort((a,b)=>Number(!!b[1][0].native)-Number(!!a[1][0].native)||b[1].length-a[1].length||a[0].localeCompare(b[0])),max=Math.max(...sorted.map(r=>r[1].length));
    const native=sorted.filter(r=>r[1][0].native).length;
    out.push({title:`not implemented · ${missing.size} endpoints · ${native} already in JVMD's own API`,fold:true,lines:[
      ...table(["endpoint",">n","","families","jvmd api today"],sorted.map(([k,rs])=>[rs[0].native?"+":" ",[k,String(rs.length),"▪".repeat(Math.ceil(rs.length/max*10)),[...new Set(rs.map(r=>r.family))].join(" "),rs[0].native??""]])),
      [" ",""],[" ","+ already answered by JVMD's own API: exposing it over LSP is the work"]]});
  }

  const perf=s.performance.filter(p=>p.server==="jvmd").sort((a,b)=>a.endpoint.localeCompare(b.endpoint)||PHASES.indexOf(a.phase)-PHASES.indexOf(b.phase));
  if(perf.length){
    const vs=new Map(s.performance.filter(p=>p.server==="jdtls").map(p=>[p.endpoint+"\u0000"+p.phase,p]));let last="";
    const rows=perf.map(p=>{const o=vs.get(p.endpoint+"\u0000"+p.phase),[t,speed]=versus(p.medianMs,o?.medianMs,"faster","slower"),[,alloc]=versus(p.medianAllocBytes,o?.medianAllocBytes,"less","more");
      const label=p.endpoint===last?"":short(p.endpoint);last=p.endpoint;
      return [t,[label,p.phase,dur(p.medianMs),size(p.medianAllocBytes),dur(o?.medianMs),size(o?.medianAllocBytes),speed,alloc]] as [Tone,string[]];});
    out.push({title:`latency · alloc · ${new Set(perf.map(p=>p.endpoint)).size} endpoints`,fold:true,lines:[
      ...table(["endpoint","",">jvmd",">alloc",">jdtls",">alloc",">speed",">alloc"],rows),
      [" ",""],[" ","median per request · edit = edit to first correct answer · alloc = server JVM bytes (jvmd: daemon only)"]]});
  }
  const shortfalls=s.cases.filter(c=>c.server==="jdtls"&&!["pass","missing"].includes(bucket(c.outcome)));
  if(shortfalls.length)out.push({title:`jdtls falls short · ${shortfalls.length}`,fold:true,
    lines:table(["case","result","detail"],shortfalls.map(c=>[" ",[c.id,WORDS[c.outcome]??c.outcome,String(c.detail??"").slice(0,70)]]))});
  return out;
}

/** GitHub colours diff blocks: "+" lines green, "-" red, "@@" headers blue. */
export function renderMarkdown(s:Summary,change?:ReturnType<typeof compare>,base?:Summary){
  const sections=readout(s,change,base),block=(ss:Section[])=>"```diff\n"+ss.map(x=>[`@@ ${x.title} @@`,...x.lines.map(([t,l])=>(t+" "+l).trimEnd())].join("\n")).join("\n\n")+"\n```";
  return [block(sections.filter(x=>!x.fold)),...sections.filter(x=>x.fold).map(x=>`<details><summary><code>${x.title}</code></summary>\n\n${block([{...x,title:x.title.split(" · ")[0]}])}\n\n</details>`)].join("\n\n")+"\n";
}
/** The same readout for a terminal: plain, or with ANSI colours. */
export function renderText(s:Summary,change?:ReturnType<typeof compare>,color=false,folded=true){
  const paint=(t:Tone,l:string)=>!color||t===" "?l:`\x1b[${t==="+"?32:31}m${l}\x1b[0m`;
  return readout(s,change).filter(x=>folded||!x.fold).map(x=>[color?`\x1b[36m${x.title}\x1b[0m`:x.title,...x.lines.map(([t,l])=>paint(t,("  "+l).trimEnd()))].join("\n")).join("\n\n")+"\n";
}

export function writeReport(root:string,summary:Summary,baseline?:Summary){
  const change=compare(summary,baseline);
  writeFileSync(path.join(root,"summary.json"),JSON.stringify(summary,null,1)+"\n");
  writeFileSync(path.join(root,"report.md"),renderMarkdown(summary,change,baseline));
  writeFileSync(path.join(root,"statuses.json"),JSON.stringify(statuses(summary,baseline),null,1)+"\n");
  writeFileSync(path.join(root,"report.txt"),renderText(summary,change));
  // The terminal gets the unfolded sections, in colour when it is one.
  return {text:renderText(summary,change,!!process.stdout.isTTY,false),regressions:change?.regressed??[]};
}
