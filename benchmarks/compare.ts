import assert from "node:assert/strict";
import {existsSync,readFileSync,readdirSync,statSync,writeFileSync} from "node:fs";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {summarize,bucket} from "./report.ts";

/**
 * Before/after report for a PR: every measurement run on the base build and on the PR's build, on
 * the same runner, by the same harness. Reads every results.json (LSP suite and apache/maven
 * lifecycle, from run.ts) and persistence.json (restart scenarios, from persistence.ts) under
 * --input, keyed by the side named in their directory ("base-…" or "head-…").
 *
 *   node benchmarks/compare.ts --input DIR --base <sha> --head <sha> --output report.md
 */
type Side="base"|"head";
const SIDES:Side[]=["base","head"];

function find(dir:string,name:string,out:string[]=[]){
  if(!existsSync(dir))return out;
  for(const e of readdirSync(dir,{withFileTypes:true})){const p=path.join(dir,e.name);
    if(e.isDirectory()||e.isSymbolicLink()&&statSync(p).isDirectory()){if(!["cases","fixture","repository","project","state","jvmd","lifecycle","synthetic","real"].includes(e.name))find(p,name,out);}
    else if(e.name===name)out.push(p);}
  return out;
}
const sideOf=(file:string):Side|undefined=>{const m=/(?:^|[/\\])(base|head)-[^/\\]*[/\\]/u.exec(file);return m?.[1] as Side|undefined;};
const quantile=(xs:number[],q:number)=>{if(!xs.length)return null;const s=[...xs].sort((a,b)=>a-b),h=(s.length-1)*q,i=Math.floor(h);return s[i]+(h-i)*(s[Math.min(i+1,s.length-1)]-s[i]);};
const median=(xs:(number|null|undefined)[])=>quantile(xs.filter((x):x is number=>typeof x==="number"),.5);
const dur=(v:number|null|undefined)=>v==null?"–":v<10?v.toFixed(1)+" ms":v<1000?v.toFixed(0)+" ms":v<120_000?(v/1000).toFixed(1)+" s":(v/60_000).toFixed(1)+" min";
const size=(v:number|null|undefined)=>v==null?"–":v<1<<20?(v/1024).toFixed(0)+" K":v<1<<30?(v/(1<<20)).toFixed(1)+" M":(v/(1<<30)).toFixed(2)+" G";
const num=(v:number|null|undefined)=>v==null?"–":String(Math.round(v));
/** "−42%" when head is lower; bold when the move is past the noise floor given. */
function delta(b:number|null|undefined,h:number|null|undefined,floor=0,rel=.1){
  if(b==null||h==null)return "";if(b===h)return "=";if(b===0)return "new";
  const r=(h-b)/b,text=(r>0?"+":"−")+Math.abs(Math.round(r*100))+"%";
  return Math.abs(h-b)>=floor&&Math.abs(r)>=rel?`**${text}**`:text;
}
const ratio=(b:number|null|undefined,h:number|null|undefined)=>b&&h?(b/h>=1?`${(b/h).toFixed(1)}× faster`:`${(h/b).toFixed(1)}× slower`):"";
const range=(xs:(number|null|undefined)[],f:(v:number)=>string)=>{const v=xs.filter((x):x is number=>typeof x==="number");return v.length>1?`${f(Math.min(...v))}–${f(Math.max(...v))}`:"";};
const table=(head:string[],rows:string[][])=>[`| ${head.join(" | ")} |`,`|${head.map(h=>h.startsWith(">")?"---:":"---").join("|")}|`,...rows.map(r=>`| ${r.join(" | ")} |`)].join("\n").replaceAll("| >","| ");
const details=(summary:string,body:string)=>`<details><summary>${summary}</summary>\n\n${body}\n\n</details>`;

export function render(input:string,o:{base:string;head:string;runner?:string}){
  const out:string[]=[`## Before / after: \`${o.base.slice(0,8)}\` (base) → \`${o.head.slice(0,8)}\` (this PR)`,""];
  out.push("Every number below was measured on both builds, on the same GitHub runner, by the same harness (this PR's `benchmarks/`). "+
    "Times are wall clock; **bold** changes are past the noise floor (≥10% and ≥ 50 ms, or ≥ 25% and ≥ 1 MB for allocation).","");

  // ---------- restart scenarios ----------
  const persistence=find(input,"persistence.json").map(f=>({side:sideOf(f),...JSON.parse(readFileSync(f,"utf8"))})).filter(p=>p.side);
  const sessions=new Map<string,Record<Side,any[]>>();
  for(const p of persistence)for(const r of p.rows){const k=r.scenario+"\u0000"+r.label,cell=sessions.get(k)??{base:[],head:[]};cell[p.side as Side].push(r);sessions.set(k,cell);}
  const restart=(title:string,which:(scenario:string)=>boolean,note:string)=>{
    const keys=[...sessions.keys()].filter(k=>which(k.split("\u0000")[0]));if(!keys.length)return;
    const rows:string[][]=[],extra:string[][]=[];
    for(const k of keys){const [scenario,label]=k.split("\u0000"),c=sessions.get(k)!,m=(side:Side,f:string)=>median(c[side].map(r=>r[f]));
      const errors=SIDES.flatMap(s=>c[s].filter(r=>r.error).map(r=>`${s}: ${r.error}`));
      const same=c.base.length&&c.head.length?(c.base.every(b=>c.head.every(h=>b.digest===h.digest))?"identical":"**differ**"):"–";
      const restored=c.head.map(r=>r.persistence?.restores).filter((x:any)=>x!=null);
      rows.push([(which===isReal?"":scenario+" · ")+label,num(m("base","units")),
        dur(m("base","firstAnswerMs")),dur(m("head","firstAnswerMs")),ratio(m("base","firstAnswerMs"),m("head","firstAnswerMs")),
        dur(m("base","diagnoseMs")),dur(m("head","diagnoseMs")),ratio(m("base","diagnoseMs"),m("head","diagnoseMs")),
        num(m("base","javac")),num(m("head","javac")),restored.length?num(median(restored)):"–",same+(errors.length?" ⚠️":"")]);
      extra.push([(which===isReal?"":scenario+" · ")+label,dur(m("base","readyMs")),dur(m("head","readyMs")),dur(m("base","openMs")),dur(m("head","openMs")),
        dur(m("base","totalMs")),dur(m("head","totalMs")),size(m("base","peakRssBytes")),size(m("head","peakRssBytes")),delta(m("base","peakRssBytes"),m("head","peakRssBytes"),1<<20,.1),
        size(m("base","allocatedBytes")),size(m("head","allocatedBytes")),delta(m("base","allocatedBytes"),m("head","allocatedBytes"),1<<20,.25),
        String(c.head.map(r=>JSON.stringify(r.persistence?.refusals??{})).find(x=>x!=="{}")??"–").replaceAll("|","\\|")]);
      for(const e of errors)extra.push([label,"⚠️ "+e.slice(0,200).replaceAll("|","\\|"),"","","","","","","","","","",""]);
    }
    const reps=Math.max(...[...sessions.values()].map(c=>Math.max(c.base.length,c.head.length)));
    out.push(`### ${title}`,"",note+(reps>1?` Medians of ${reps} repetitions.`:""),"",
      table(["session",">units",">first answer: base",">PR",">",">diagnose all: base",">PR",">",">javac runs: base",">PR",">PR restored","diagnostics"],rows),"",
      details("Daemon READY, open, whole session, peak RSS, allocation, refusals",
        table(["session",">READY: base",">PR",">open: base",">PR",">session: base",">PR",">peak RSS: base",">PR",">",">allocated: base",">PR",">","PR refusals"],extra)),"");
  };
  const isReal=(s:string)=>s==="real";
  restart("Restart scenarios · real project (ruoyi-vue-pro @ 1697112f, 977 main units, Lombok + MapStruct)",isReal,
    "Each row is a fresh daemon process on the same state (a machine restart, in order), then: open the project, wait for a correct completion "+
    "(*first answer*, measured from process start), then diagnostics for every unit (*diagnose all*). *javac runs* are the module actors' compiler "+
    "invocations (base batches up to 128 units per run). *Diagnostics* compares every file's diagnostics between the two builds.");
  restart("Restart scenarios · synthetic (5,000 units per topology)",s=>!isReal(s),
    "The A1–A6 fixtures of `RestartScenarioTest` (same generator and seed), as daemon sessions: *first answer* is the diagnostic of an error in one leaf unit, "+
    "measured from process start; then diagnostics for every unit.");

  // ---------- apache/maven lifecycle ----------
  const results=find(input,"results.json").map(f=>({side:sideOf(f),file:f,...JSON.parse(readFileSync(f,"utf8"))})).filter(r=>r.side);
  const lifecycles:Record<Side,any[]>={base:[],head:[]};
  for(const r of results)for(const l of r.lifecycle??[])if(l.server==="jvmd")lifecycles[r.side as Side].push(l);
  if(lifecycles.base.length||lifecycles.head.length){
    const phases:[string,string,(v:number|null|undefined)=>string][]=[["Machine index (cold)","machine_index_ms",dur],["Open workspace (cold)","open_ms",dur],
      ["Reconnect to warm daemon","reconnect_open_ms",dur],["Daemon restart (persisted index)","restart_index_ms",dur],["Open after restart","restart_open_ms",dur],
      ["Restart to first correct completion","restart_first_completion_ms",dur],["Resident memory after queries","rss_bytes",size]];
    const rows=phases.map(([label,key,f])=>{const v=(s:Side)=>lifecycles[s].map(l=>l.phases?.[key]);
      return [label,f(median(v("base"))),f(median(v("head"))),delta(median(v("base")),median(v("head")),f===size?1<<20:50),range(v("base"),f),range(v("head"),f)];});
    const firsts=[...new Set(SIDES.flatMap(s=>lifecycles[s].flatMap(l=>(l.operations??[]).filter((op:any)=>op.state==="first_use"&&op.outcome==="pass").map((op:any)=>op.endpoint))))].sort();
    for(const e of firsts){const v=(s:Side)=>lifecycles[s].map(l=>(l.operations??[]).find((op:any)=>op.endpoint===e&&op.state==="first_use"&&op.outcome==="pass")?.latencyMs);
      rows.push(["first "+e.replace(/^textDocument\//u,""),dur(median(v("base"))),dur(median(v("head"))),delta(median(v("base")),median(v("head")),50),range(v("base"),dur),range(v("head"),dur)]);}
    const outcomes=SIDES.map(s=>`${s==="base"?"base":"PR"}: ${lifecycles[s].map(l=>l.outcome).join(", ")||"not run"}`).join(" · ");
    out.push(`### apache/maven lifecycle (${lifecycles.base.length} + ${lifecycles.head.length} runs, interleaved)`,"",
      table(["phase",">base",">PR",">change",">base range",">PR range"],rows),"","Outcomes: "+outcomes,"");
    for(const s of SIDES)for(const l of lifecycles[s])if(l.error)out.push(`- ${s==="base"?"base":"PR"}: ${String(l.error).slice(0,200)}`);
    out.push("");
  }

  // ---------- LSP suite ----------
  const suites:Record<Side,any[]>={base:[],head:[]};
  for(const r of results)if(r.results?.length)suites[r.side as Side].push(r);
  if(suites.base.length&&suites.head.length){
    const summary=(side:Side)=>summarize({...suites[side][0].meta,runs:suites[side].length},suites[side].flatMap((r,i)=>r.results.map((x:any)=>({...x,run:i+1}))),[],[]);
    const perRun=(side:Side)=>suites[side].map(r=>summarize(r.meta,r.results,[],[]));
    const [b,h]=[summary("base"),summary("head")],[bRuns,hRuns]=[perRun("base"),perRun("head")];
    const count=(s:any,k:string)=>s.cases.filter((c:any)=>c.server==="jvmd"&&bucket(c.outcome)===k).length;
    const changed:string[][]=[];const before=new Map(b.cases.filter(c=>c.server==="jvmd").map(c=>[c.id,c]));
    for(const c of h.cases.filter(c=>c.server==="jvmd")){const was=before.get(c.id);if(was&&bucket(was.outcome)!==bucket(c.outcome))changed.push([c.id,was.outcome,c.outcome,String(c.detail??was.detail??"").slice(0,100).replaceAll("|","\\|")]);}
    const key=(p:any)=>p.endpoint+"\u0000"+p.phase,bp=new Map(b.performance.filter(p=>p.server==="jvmd").map(p=>[key(p),p]));
    const runMedians=(runs:any[],k:string,f:string)=>runs.map(s=>s.performance.find((p:any)=>p.server==="jvmd"&&key(p)===k)?.[f]);
    const rows:string[][]=[],moves:string[][]=[];
    for(const p of h.performance.filter(p=>p.server==="jvmd").sort((x,y)=>x.endpoint.localeCompare(y.endpoint)||x.phase.localeCompare(y.phase))){
      const was=bp.get(key(p));if(!was)continue;const name=p.endpoint.replace(/^textDocument\//u,"")+" · "+p.phase;
      const row=[name,dur(was.medianMs),dur(p.medianMs),delta(was.medianMs,p.medianMs,10,.25),range(runMedians(bRuns,key(p),"medianMs"),dur),range(runMedians(hRuns,key(p),"medianMs"),dur),
        dur(was.p95Ms),dur(p.p95Ms),size(was.medianAllocBytes),size(p.medianAllocBytes),delta(was.medianAllocBytes,p.medianAllocBytes,1<<20,.25),`${was.samples}/${p.samples}`];
      rows.push(row);if(row[3].startsWith("**")||row[10].startsWith("**"))moves.push(row);
    }
    out.push(`### LSP scenario suite (${suites.base.length} + ${suites.head.length} runs, interleaved base/PR)`,"",
      `Cases passing: base ${count(b,"pass")}/${b.cases.filter(c=>c.server==="jvmd").length}, PR ${count(h,"pass")}/${h.cases.filter(c=>c.server==="jvmd").length} `+
      `(worst run decides a case). Latency and allocation are medians over every passing request of every run; *range* is the spread of per-run medians.`,"",
      changed.length?table(["case","base","PR","detail"],changed):"No case changed outcome.","",
      moves.length?"Moves past the noise floor (latency ≥25% and ≥10 ms, allocation ≥25% and ≥1 MB):\n\n"+table(["endpoint · phase",">base",">PR",">change",">base range",">PR range",">p95 base",">p95 PR",">alloc base",">alloc PR",">change",">samples"],moves):"No endpoint moved past the noise floor.","",
      details(`All ${rows.length} endpoint × phase rows`,table(["endpoint · phase",">base",">PR",">change",">base range",">PR range",">p95 base",">p95 PR",">alloc base",">alloc PR",">change",">samples"],rows)),"");
  }

  // ---------- W8 ----------
  const w8=find(input,"machine-decision.md");
  if(w8.length)out.push("### W8 MACHINE storage decision (this PR only: the alternative backend exists only here)","",details("machine-decision.md",readFileSync(w8[0],"utf8").trim()),"");
  if(o.runner)out.push(`<sub>${o.runner}</sub>`);
  return out.join("\n")+"\n";
}

export async function main(argv=process.argv.slice(2)){
  const a:Record<string,string>={};for(let i=0;i<argv.length;i+=2)a[argv[i].replace(/^--/u,"")]=argv[i+1];
  assert(a.input&&a.output,"usage: compare.ts --input DIR --output FILE [--base SHA] [--head SHA] [--runner TEXT]");
  const text=render(a.input,{base:a.base??"base",head:a.head??"head",runner:a.runner});writeFileSync(a.output,text);console.log(text);
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))await main();
