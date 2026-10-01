import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {existsSync,mkdirSync,readFileSync,writeFileSync} from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import {createHash} from "node:crypto";

const SOURCE=new URL("./agent/AllocationAgent.java",import.meta.url);

/** Compiles the agent once per source revision and JDK; returns the jar path. */
export function agentJar(javaHome:string){
  const source=readFileSync(SOURCE),key=createHash("sha256").update(source).update(javaHome).digest("hex").slice(0,16);
  const dir=path.join(os.tmpdir(),"jvmd-roadmap-agent-"+key),jar=path.join(dir,"agent.jar");
  if(existsSync(jar))return jar;
  mkdirSync(path.join(dir,"classes"),{recursive:true});writeFileSync(path.join(dir,"MANIFEST.MF"),"Premain-Class: AllocationAgent\n");
  const run=(tool:string,args:string[])=>{const r=spawnSync(path.join(javaHome,"bin",tool),args,{encoding:"utf8"});assert.equal(r.status,0,tool+": "+r.stderr);};
  run("javac",["-d",path.join(dir,"classes"),new URL(SOURCE).pathname]);
  run("jar",["--create","--file",jar,"--manifest",path.join(dir,"MANIFEST.MF"),"-C",path.join(dir,"classes"),"."]);
  return jar;
}

export type ThreadAllocation={threads:[number,string,number][];total:number};
/** Where JVMD's threads do their work, by thread name. Virtual threads (the memo writer, module dispatch) run on ForkJoinPool carriers. */
export function threadBucket(name:string){
  if(/^jvmd-session-/u.test(name))return "request (jvmd-session)";
  if(/^jvmd-module-dispatch/u.test(name))return "module dispatch";
  if(/^jvmd-module-/u.test(name))return "analyzer owner (jvmd-module)";
  if(name==="jvmd-local-memo-writer")return "memo writer";
  if(/^jvmd-(index|source-publisher)/u.test(name))return "index";
  if(/rocks/iu.test(name))return "rocksdb";
  if(/^ForkJoinPool/u.test(name))return "virtual-thread carriers";
  if(name==="allocation-probe")return "harness probe";
  return "other";
}
/** Bytes per bucket between two snapshots; threads that ended inside the window show up as "ended in window". */
export function threadDelta(before:ThreadAllocation|null,after:ThreadAllocation|null){
  if(!before||!after||before.total<0||after.total<0)return null;
  const start=new Map(before.threads.map(([id,,bytes])=>[id,bytes])),buckets:Record<string,number>={},names:Record<string,number>={};let attributed=0;
  for(const [id,name,bytes] of after.threads){const delta=bytes-(start.get(id)??0);if(delta<=0)continue;attributed+=delta;
    const bucket=threadBucket(name);buckets[bucket]=(buckets[bucket]??0)+delta;names[name]=(names[name]??0)+delta;}
  const total=after.total-before.total;if(total-attributed>0)buckets["ended in window"]=total-attributed;
  return {total,buckets,names};
}

/** Reads a JVM's cumulative allocated bytes through the agent socket. null = unavailable. */
export class AllocationProbe {
  private socket?:net.Socket;private waiting:((value:any)=>void)[]=[];private buffer="";
  static async connect(socketPath:string,timeoutMs=30000){
    const probe=new AllocationProbe(),deadline=Date.now()+timeoutMs;
    while(Date.now()<deadline){
      try{probe.socket=await new Promise<net.Socket>((resolve,reject)=>{const s=net.createConnection(socketPath);s.once("connect",()=>resolve(s));s.once("error",reject);});break;}
      catch{await new Promise(r=>setTimeout(r,25));}
    }
    if(!probe.socket)return probe;
    probe.socket.setEncoding("utf8");
    probe.socket.on("data",chunk=>{probe.buffer+=chunk;let i;while((i=probe.buffer.indexOf("\n"))>=0){const line=probe.buffer.slice(0,i);probe.buffer=probe.buffer.slice(i+1);
      const v=line.startsWith("{")?JSON.parse(line):Number(line);probe.waiting.shift()?.(typeof v==="number"?(v>=0?v:null):v);}});
    probe.socket.on("close",()=>{for(const w of probe.waiting.splice(0))w(null);probe.socket=undefined;});
    return probe;
  }
  read():Promise<number|null>{
    if(!this.socket)return Promise.resolve(null);
    return new Promise(resolve=>{this.waiting.push(resolve);this.socket!.write("?");});
  }
  /** Cumulative allocated bytes of every live platform thread, by id and name, and the JVM total. */
  threads():Promise<ThreadAllocation|null>{
    if(!this.socket)return Promise.resolve(null);
    return new Promise(resolve=>{this.waiting.push(resolve);this.socket!.write("T");});
  }
  /** Memory investigations only: heap, pools (with peaks since the last reset), buffer pools and GC counters. */
  snapshot(resetPeaks=false):Promise<any|null>{
    if(!this.socket)return Promise.resolve(null);
    return new Promise(resolve=>{this.waiting.push(resolve);this.socket!.write(resetPeaks?"p":"s");});
  }
  close(){this.socket?.destroy();}
}
