import {readFileSync,writeFileSync} from "node:fs";
import path from "node:path";
import {type CaseDefinition} from "./harness/ScenarioContext.ts";

const CATALOGUE=JSON.parse(readFileSync(new URL("./catalogue.json",import.meta.url),"utf8"));

/** Collapses a case outcome into what it means for the roadmap. */
export const bucket=(o:string)=>o==="pass"||o==="not_applicable"?"pass":o==="unsupported"?"missing":o==="stale"?"stale":o==="unavailable_evidence"?"unchecked":o==="harness_error"?"harness":"failing";
const WORDS:Record<string,string>={pass:"pass",incorrect:"wrong",timeout:"timeout",protocol_error:"protocol",stale:"stale",unavailable_evidence:"unchecked",harness_error:"harness",unsupported:"missing"};
const SEVERITY=["failing","stale","harness","unchecked","missing","pass"];

const median=(xs:number[])=>quantile(xs,.5);
function quantile(xs:number[],q:number){if(!xs.length)return null;const s=[...xs].sort((a,b)=>a-b),h=(s.length-1)*q,i=Math.floor(h);return s[i]+(h-i)*(s[Math.min(i+1,s.length-1)]-s[i]);}
const dur=(v:number|null)=>v===null?"-":v<10?v.toFixed(1)+"ms":v<1000?v.toFixed(0)+"ms":(v/1000).toFixed(2)+"s";
const size=(v:number|null)=>v===null?"-":v<1<<20?(v/1024).toFixed(0)+"K":v<1<<30?(v/(1<<20)).toFixed(1)+"M":(v/(1<<30)).toFixed(2)+"G";
const pct=(a:number,b:number)=>`${a>b?"+":""}${Math.round((a-b)/b*100)}%`;
/** How the lead compares with the reference: ratio of reference to lead, worded for the lead. */
const versus=(a:number|null,b:number|null,better:string,worse:string)=>{
  if(a===null||b===null||a<=0||b<=0)return "";const r=b/a;
  return r>=1.1?`${r<10?r.toFixed(1):r.toFixed(0)}x ${better}`:r<=1/1.1?`${1/r<10?(1/r).toFixed(1):(1/r).toFixed(0)}x ${worse}`:"same";
};
const short=(endpoint:string)=>endpoint.replace(/^textDocument\//u,"");
/** Pads a table: columns joined by two spaces, right-aligned where the header starts with ">". */
function table(rows:string[][],indent="  "){
  if(!rows.length)return [];
  const right=rows[0].map(h=>h.startsWith(">")),head=rows[0].map(h=>h.replace(/^>/u,""));
  const all=[head,...rows.slice(1)],width=head.map((_,i)=>Math.max(...all.map(r=>(r[i]??"").length)));
  return all.map(r=>indent+r.map((c,i)=>right[i]?(c??"").padStart(width[i]):(c??"").padEnd(width[i])).join("  ").trimEnd());
}
/** █ pass, ▒ implemented but not passing, ░ not implemented. */
function bar(pass:number,bad:number,total:number,width=30){
  if(!total)return "".padEnd(width);
  const p=Math.round(pass/total*width),b=bad?Math.max(1,Math.round(bad/total*width)):0;
  return "█".repeat(p)+"▒".repeat(Math.min(b,width-p))+"░".repeat(Math.max(0,width-p-b));
}

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
    const phase=op.state==="first_use"?"first":op.state==="steady"?"repeat":op.transitionMs!==undefined&&/^changed/u.test(op.state)?"edit":undefined;
    if(!phase)continue;
    const key=`${op.endpoint??op.method}\u0000${phase}`,row=out.get(key)??{};out.set(key,row);
    const c=row[r.server]??={latency:[],alloc:[]};
    c.latency.push(phase==="edit"?op.transitionMs:op.latencyMs);
    if(typeof op.allocatedBytes==="number")c.alloc.push(op.allocatedBytes);
  }
  return out;
}
const PHASES=["first","repeat","edit"];
const byEndpoint=(a:string,b:string)=>{const [ea,pa]=a.split("\u0000"),[eb,pb]=b.split("\u0000");return ea.localeCompare(eb)||PHASES.indexOf(pa)-PHASES.indexOf(pb);};

function detail(r:any,width=90){
  const op=r.operations.find((o:any)=>o.outcome!=="pass");
  const text=r.outcome==="timeout"&&op?`no correct ${op.endpoint??op.method} after ${op.state.replaceAll("_"," ")}`
    :(r.error??op?.assertionError??"").split("\n")[0].replace(/(Assertion)?Error( \[ERR_ASSERTION\])?: /gu,"");
  const flat=text.replace(/\s+/gu," ").trim();
  return flat.length>width?flat.slice(0,width-1)+"…":flat;
}

type Section={title:string;lines:string[];fold?:boolean};

/**
 * Renders the run as a terminal-style readout. report.txt is the plain text; report.md wraps the same
 * text in code blocks, folding the long sections, for job summaries and PR comments. Returns the
 * summary text (what the runner prints) and regressions against the baseline. Nothing here gates.
 */
export function writeReport(root:string,meta:any,results:any[],selected:CaseDefinition[],baseline?:any[]){
  const servers:string[]=meta.servers,status=caseStatus(results),sections:Section[]=[];
  const rows=(server:string)=>[...status.values()].filter(r=>r.server===server);
  const count=(rs:any[],...b:string[])=>rs.filter(r=>b.includes(bucket(r.outcome))).length;
  const lead=servers.includes("jvmd")?"jvmd":servers[0],others=servers.filter(s=>s!==lead);
  const attention=rows(lead).filter(r=>!["pass","missing"].includes(bucket(r.outcome)));
  const name=Math.max(...servers.map(s=>s.length));

  const jdk=meta.jdk.match(/version "([^"]+)"/u)?.[1]??meta.jdk;
  const head=[`${meta.revision.slice(0,8)}${meta.dirty?"+dirty":""} · jdk ${jdk} · ${meta.cpus} cpu · ${meta.runs} run${meta.runs===1?"":"s"}`,""];
  for(const s of servers){const rs=rows(s),pass=count(rs,"pass"),bad=rs.length-pass-count(rs,"missing");
    head.push(`  ${s.padEnd(name)}  ${bar(pass,bad,rs.length)}  ${String(pass).padStart(3)}/${rs.length}  ${String(Math.round(pass/Math.max(rs.length,1)*100)).padStart(3)}%`+
      (s===lead?`   ${count(rs,"missing")} missing · ${bad} attention`:""));}
  head.push("",`  █ pass  ▒ implemented, not passing  ░ not implemented`);
  sections.push({title:"jvmd roadmap",lines:head});

  const regressions:any[]=[];
  if(baseline){
    const before=caseStatus(baseline),lines:string[]=[],changed:string[][]=[[" ","case","was","now"]];
    for(const r of rows(lead)){const b=before.get(r.server+"\u0000"+r.caseId);if(!b)continue;
      const was=bucket(b.outcome),now=bucket(r.outcome);
      if(was!=="pass"&&now==="pass")changed.push(["+",r.caseId,WORDS[b.outcome]??b.outcome,"pass"]);
      else if(was==="pass"&&now!=="pass"){regressions.push({...r,was:b.outcome});changed.push(["-",r.caseId,"pass",WORDS[r.outcome]??r.outcome]);}}
    if(changed.length>1)lines.push(...table(changed));
    const perf=performanceChanges(performance(results.filter(r=>r.server===lead)),performance(baseline.filter(r=>r.server===lead)),lead);
    if(perf.length>1){if(lines.length)lines.push("");lines.push(...table(perf));}
    sections.push({title:"since main",lines:lines.length?lines:["  no change"]});
  }

  if(attention.length)sections.push({title:"attention",
    lines:table([["case","result","detail"],...attention.map(r=>[r.caseId,WORDS[r.outcome]??r.outcome,detail(r)])])});

  const families=CATALOGUE.scenarios.filter((f:any)=>selected.some(c=>c.family===f.id));
  const mark=(rs:any[])=>{
    if(!rs.length)return "";
    const pass=count(rs,"pass"),flag=count(rs,"failing","harness")?"!":count(rs,"stale")?"~":count(rs,"unchecked")?"?":"";
    return `${bar(pass,rs.length-pass-count(rs,"missing"),rs.length,10)} ${String(pass).padStart(2)}/${String(rs.length).padEnd(2)} ${flag}`.trimEnd();
  };
  const done=families.filter((f:any)=>{const rs=rows(lead).filter(r=>r.family===f.id);return rs.length&&rs.every(r=>bucket(r.outcome)==="pass");}).length;
  sections.push({title:`families · ${done}/${families.length} complete`,fold:true,lines:[
    ...table([["id","scenario",...servers],...families.map((f:any)=>[f.id,f.family.toLowerCase(),...servers.map(s=>mark(rows(s).filter(r=>r.family===f.id)))])]),
    "","  ! wrong/failed  ~ stale after edit  ? can't be checked"]});

  const missing=new Map<string,any[]>();
  for(const r of rows(lead))if(r.outcome==="unsupported"){const k=r.missing??"?";missing.set(k,[...missing.get(k)??[],r]);}
  if(missing.size){
    const sorted=[...missing].sort((a,b)=>b[1].length-a[1].length||a[0].localeCompare(b[0])),max=sorted[0][1].length;
    sections.push({title:`not implemented · ${missing.size} endpoint${missing.size===1?"":"s"} block${missing.size===1?"s":""} ${count(rows(lead),"missing")} scenario${count(rows(lead),"missing")===1?"":"s"}`,fold:true,lines:
      table([["endpoint",">n","","families"],...sorted.map(([k,rs])=>[k,String(rs.length),"▪".repeat(Math.ceil(rs.length/max*12)),[...new Set(rs.map(r=>r.family))].join(" ")])])});
  }

  // Head to head: endpoints the lead server answers correctly. Everything else is in results.json.
  const perf=[...performance(results)].filter(([,row])=>row[lead]).sort((a,b)=>byEndpoint(a[0],b[0]));
  if(perf.length){
    const vs=others[0],header=["endpoint","",`>${lead}`,">alloc",...vs?[`>${vs}`,">alloc",">speed",">alloc"]:[]];
    let last="";
    const body=perf.map(([key,row])=>{const [endpoint,phase]=key.split("\u0000"),a=row[lead],b=vs?row[vs]:undefined;
      const la=median(a.latency),lb=b?median(b.latency):null,xa=median(a.alloc),xb=b?median(b.alloc):null;
      const label=endpoint===last?"":short(endpoint);last=endpoint;
      return [label,phase,dur(la),size(xa),...vs?[dur(lb),size(xb),versus(la,lb,"faster","slower"),versus(xa,xb,"less","more")]:[]];});
    sections.push({title:`latency · alloc · ${new Set(perf.map(([k])=>k.split("\u0000")[0])).size} endpoints`,fold:true,lines:[...table([header,...body]),"",
      `  median per request · speed/alloc: ${lead} relative to ${vs??lead} · edit = edit to first correct answer · alloc = server JVM bytes (jvmd: daemon only)`]});
  }

  const reference=others.flatMap(s=>rows(s).filter(r=>!["pass","missing"].includes(bucket(r.outcome))));
  if(reference.length)sections.push({title:`${others.join(", ")} falls short · ${reference.length}`,fold:true,
    lines:table([["server","case","result","detail"],...reference.sort((a,b)=>a.caseId.localeCompare(b.caseId)).map(r=>[r.server,r.caseId,WORDS[r.outcome]??r.outcome,detail(r,70)])])});
  const unclean=results.filter(r=>r.shutdown);
  if(unclean.length)sections.at(-1)!.lines.push("",`  unclean exits (answers still count): ${servers.map(s=>`${s} ${unclean.filter(r=>r.server===s).length}`).join(", ")}`);

  const text=(ss:Section[])=>ss.map(s=>[s.title,...s.lines].join("\n")).join("\n\n")+"\n";
  const block=(s:Section)=>"```text\n"+[s.title,...s.lines].join("\n")+"\n```";
  const md=[block({title:sections[0].title,lines:[...sections[0].lines,...sections.slice(1).filter(s=>!s.fold).flatMap(s=>["",s.title,...s.lines])]}),
    ...sections.filter(s=>s.fold).map(s=>`<details><summary><code>${s.title}</code></summary>\n\n${"```text\n"+s.lines.join("\n")+"\n```"}\n\n</details>`)].join("\n\n")+"\n";
  writeFileSync(path.join(root,"report.txt"),text(sections));
  writeFileSync(path.join(root,"report.md"),md);
  return {regressions,summary:text(sections.filter(s=>!s.fold))};
}

function performanceChanges(now:ReturnType<typeof performance>,before:ReturnType<typeof performance>,server:string){
  const out:string[][]=[[" ","endpoint","","latency","","alloc",""]];
  for(const [key,row] of [...now].sort((a,b)=>byEndpoint(a[0],b[0]))){
    const a=row[server],b=before.get(key)?.[server];if(!a||!b)continue;
    const [la,lb,xa,xb]=[median(a.latency),median(b.latency),median(a.alloc),median(b.alloc)];
    const latency=la!==null&&lb&&Math.abs(la-lb)>=5&&Math.abs(la-lb)/lb>=.25,alloc=xa!==null&&xb&&Math.abs(xa-xb)>=1<<20&&Math.abs(xa-xb)/xb>=.25;
    if(!latency&&!alloc)continue;
    const [endpoint,phase]=key.split("\u0000"),worse=(latency&&la!>lb!)||(alloc&&xa!>xb!);
    out.push([worse?"▲":"▼",short(endpoint),phase,latency?`${dur(lb)} → ${dur(la)}`:"",latency?pct(la!,lb!):"",alloc?`${size(xb)} → ${size(xa)}`:"",alloc?pct(xa!,xb!):""]);
  }
  return out;
}
