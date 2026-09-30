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

/** Reads a JVM's cumulative allocated bytes through the agent socket. null = unavailable. */
export class AllocationProbe {
  private socket?:net.Socket;private waiting:((line:string|null)=>void)[]=[];private buffer="";
  static async connect(socketPath:string,timeoutMs=30000){
    const probe=new AllocationProbe(),deadline=Date.now()+timeoutMs;
    while(Date.now()<deadline){
      try{probe.socket=await new Promise<net.Socket>((resolve,reject)=>{const s=net.createConnection(socketPath);s.once("connect",()=>resolve(s));s.once("error",reject);});break;}
      catch{await new Promise(r=>setTimeout(r,25));}
    }
    if(!probe.socket)return probe;
    probe.socket.setEncoding("ascii");
    probe.socket.on("data",chunk=>{probe.buffer+=chunk;let i;while((i=probe.buffer.indexOf("\n"))>=0){const line=probe.buffer.slice(0,i);probe.buffer=probe.buffer.slice(i+1);probe.waiting.shift()?.(line);}});
    probe.socket.on("close",()=>{for(const w of probe.waiting.splice(0))w(null);probe.socket=undefined;});
    return probe;
  }
  read():Promise<number|null>{
    return this.request("?").then(line=>{const v=Number(line);return line!==null&&v>=0?v:null;});
  }
  /** Memory investigations only: heap, pools (with peaks since the last reset), buffer pools and GC counters. */
  snapshot(resetPeaks=false):Promise<any|null>{
    return this.request(resetPeaks?"p":"s").then(line=>line===null?null:JSON.parse(line));
  }
  private request(command:string):Promise<string|null>{
    if(!this.socket)return Promise.resolve(null);
    return new Promise(resolve=>{this.waiting.push(resolve);this.socket!.write(command);});
  }
  close(){this.socket?.destroy();}
}
