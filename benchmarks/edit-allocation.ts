import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {existsSync,readFileSync,readdirSync,writeFileSync} from "node:fs";
import path from "node:path";
import {fileURLToPath} from "node:url";

/**
 * Every after-edit sample of one endpoint in a run.ts output, as the report's "edit" row is built
 * from them: the allocation of the request that first answered correctly (whole JVM, the reported
 * metric), and, with --thread-allocation, the same request and the whole edit window (change to
 * correct answer) by thread. With --jfr, the top allocation stacks of the highest and lowest
 * request windows from the daemon's recording.
 *
 *   node benchmarks/edit-allocation.ts --input <run.ts output> [--endpoint textDocument/hover] [--top 10] [--java-home DIR]
 */
const MB=(v:number|null|undefined)=>v==null?"–":(v/1048576).toFixed(2);
const quantile=(xs:number[],q:number)=>{if(!xs.length)return null;const s=[...xs].sort((a,b)=>a-b),h=(s.length-1)*q,i=Math.floor(h);return s[i]+(h-i)*(s[Math.min(i+1,s.length-1)]-s[i]);};
const stats=(xs:number[])=>`${MB(Math.min(...xs))} / ${MB(quantile(xs,.5))} / ${MB(Math.max(...xs))}`;

export function samples(results:any,endpoint:string){
  const out:any[]=[];
  for(const r of results.results??[])if(r.server==="jvmd")for(const op of r.operations??[])
    if(op.outcome==="pass"&&(op.endpoint??op.method)===endpoint&&op.transitionMs!==undefined&&/^changed/u.test(op.state)&&typeof op.allocatedBytes==="number")
      out.push({case:r.caseId,run:r.run,alloc:op.allocatedBytes,latencyMs:op.latencyMs,transitionMs:op.transitionMs,state:op.state,
        start:op.startEpochMs,end:op.endEpochMs,request:op.requestThreads,window:op.editWindow});
  return out;
}

export function render(results:any,o:{endpoint:string;top:number;jfr:string[];javaHome:string;label:string}){
  const all=samples(results,o.endpoint),lines:string[]=[];
  const starts=(results.meta?.daemonStarts??[]).map((d:any)=>d.readyMs!=null?`READY after ${(d.readyMs/1000).toFixed(1)} s with the machine index ${d.index?.phase??"?"} (${d.index?.indexed??"?"}/${d.index?.total??"?"} artifacts)`:d.event).join("; ");
  lines.push(`### ${o.label}: ${o.endpoint} after an edit (${all.length} samples, ${results.meta?.runs??"?"} runs)`,"",
    `Daemon: ${starts||"–"}.`,"","Allocation of the request that first answered correctly, whole daemon JVM (the reported `alloc`), MB min / median / max:","",
    `- all samples: ${all.length?stats(all.map(s=>s.alloc)):"–"}`);
  const byCase=new Map<string,any[]>();for(const s of all)byCase.set(s.case,[...byCase.get(s.case)??[],s]);
  lines.push("","| case | n | request alloc MB min / median / max | edit window MB min / median / max | edit ms median |","|---|---:|---|---|---:|");
  for(const [id,ss] of [...byCase].sort())lines.push(`| ${id} | ${ss.length} | ${stats(ss.map(s=>s.alloc))} | ${ss.every(s=>s.window)?stats(ss.map(s=>s.window.total)):"–"} | ${quantile(ss.map(s=>s.transitionMs),.5)!.toFixed(0)} |`);
  const split=(which:"request"|"window")=>{
    const withThreads=all.filter(s=>s[which]);if(!withThreads.length)return;
    const buckets=[...new Set(withThreads.flatMap(s=>Object.keys(s[which].buckets)))].sort();
    lines.push("",`${which==="request"?"The answering request":"The whole edit window"}, by thread (MB per sample: min / median / max; 0 when a bucket did not allocate):`,"",
      "| bucket | min / median / max | share of total (median of per-sample shares) |","|---|---|---:|");
    for(const b of buckets){const v=withThreads.map(s=>s[which].buckets[b]??0),share=withThreads.map(s=>(s[which].buckets[b]??0)/Math.max(1,s[which].total));
      lines.push(`| ${b} | ${stats(v)} | ${(100*quantile(share,.5)!).toFixed(0)}% |`);}
    lines.push(`| **total** | ${stats(withThreads.map(s=>s[which].total))} | |`);
  };
  split("request");split("window");
  // The extremes, with their thread split, so a high and a low sample can be compared directly.
  const sorted=[...all].sort((a,b)=>a.alloc-b.alloc),picks=sorted.length?[["high",sorted[sorted.length-1]],["low",sorted[0]]] as const:[];
  for(const [name,s] of picks){
    lines.push("",`${name} sample: ${s.case} run ${s.run}, request ${MB(s.alloc)} MB in ${s.latencyMs.toFixed(0)} ms, edit window ${s.window?MB(s.window.total)+" MB":"–"} over ${s.transitionMs.toFixed(0)} ms`);
    if(s.request)lines.push("  request by thread: "+Object.entries(s.request.names).sort((a:any,b:any)=>b[1]-a[1]).slice(0,8).map(([n,v]:any)=>`${n} ${MB(v)}`).join(", "));
    if(s.window)lines.push("  edit window by thread: "+Object.entries(s.window.names).sort((a:any,b:any)=>b[1]-a[1]).slice(0,8).map(([n,v]:any)=>`${n} ${MB(v)}`).join(", ")+(s.window.buckets["ended in window"]?`, ended in window ${MB(s.window.buckets["ended in window"])}`:""));
  }
  if(o.jfr.length&&picks.length){
    const windows=[...picks.flatMap(([name,s])=>[[`${name} sample, answering request (${s.case} run ${s.run})`,s.start,s.end],
      ...(s.window?[[`${name} sample, whole edit window`,s.window.startEpochMs,s.window.endEpochMs]]:[])]),
      ...all.filter(s=>s.start).map(s=>[`all ${all.length} answering requests together`,s.start,s.end]),
      ...all.filter(s=>s.window).map(s=>[`all ${all.length} edit windows together`,s.window.startEpochMs,s.window.endEpochMs]),
      ["whole recording (every case, every request and all background work)",0,8_000_000_000_000],
      ...all.filter(s=>s.start).map(s=>[`row:${s.case} run ${s.run} (${MB(s.alloc)} MB)`,s.start,s.end])];
    const tsv=path.join(path.dirname(o.jfr[0]),"windows.tsv");writeFileSync(tsv,windows.map(w=>w.join("\t")).join("\n")+"\n");
    const r=spawnSync(path.join(o.javaHome,"bin/java"),[fileURLToPath(new URL("./harness/JfrWindows.java",import.meta.url)),tsv,String(o.top),...o.jfr],{encoding:"utf8",maxBuffer:64<<20});
    lines.push("","JFR allocation samples (jdk.ObjectAllocationSample at 20,000/s; weights estimate bytes) in those windows:","",r.status===0?r.stdout:"JfrWindows failed: "+r.stderr.slice(0,2000));
  }
  return lines.join("\n")+"\n";
}

export async function main(argv=process.argv.slice(2)){
  const a:Record<string,string>={};for(let i=0;i<argv.length;i+=2)a[argv[i].replace(/^--/u,"")]=argv[i+1];
  assert(a.input,"usage: edit-allocation.ts --input <run.ts output dir> [--endpoint E] [--top N] [--label L] [--output FILE]");
  const results=JSON.parse(readFileSync(path.join(a.input,"results.json"),"utf8"));
  const jvmd=path.join(a.input,"jvmd"),jfr=existsSync(jvmd)?readdirSync(jvmd).filter(f=>f.endsWith(".jfr")).map(f=>path.join(jvmd,f)):[];
  const text=render(results,{endpoint:a.endpoint??"textDocument/hover",top:Number(a.top??10),jfr,javaHome:path.resolve(a["java-home"]??process.env.JAVA_HOME??""),label:a.label??"jvmd"});
  if(a.output)writeFileSync(a.output,text);console.log(text);
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))await main();
