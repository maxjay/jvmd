import {spawn,spawnSync,type ChildProcess} from "node:child_process";
import {closeSync,existsSync,mkdirSync,openSync,readFileSync,writeFileSync,appendFileSync} from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import {AllocationProbe,agentJar} from "../harness/allocation.ts";
import {RpcClient} from "../../shim/src/transport.ts";

/**
 * Observation primitives for the memory investigation. Nothing here changes what the daemon does:
 * the daemon is launched with the benchmark's exact command line (same JDK, heap ceiling, allocation
 * agent, config), plus only the profiler flags of the selected mode.
 */

export const UTF8={LANG:"C.UTF-8",LC_ALL:"C.UTF-8"};
const JVMD_EXPORTS=["api","util","code","main","platform"].map(p=>"--add-exports=jdk.compiler/com.sun.tools.javac."+p+"=ALL-UNNAMED");

/** /proc/<pid>/status and smaps_rollup fields, in bytes. */
export function procMemory(pid:number){
  const out:Record<string,number>={};
  try{
    for(const m of readFileSync(`/proc/${pid}/status`,"utf8").matchAll(/^(Vm\w+|Rss\w+|Threads):\s+(\d+)(?:\s+kB)?$/gmu))out[m[1]]=m[1]==="Threads"?Number(m[2]):Number(m[2])*1024;
    for(const m of readFileSync(`/proc/${pid}/smaps_rollup`,"utf8").matchAll(/^(\w+):\s+(\d+) kB$/gmu))out["rollup_"+m[1]]=Number(m[2])*1024;
  }catch{/* exited */}
  return out;
}

/** Full smaps grouped into the report's mapping categories. */
export function smapsCategories(pid:number,raw?:string){
  let text:string;try{text=readFileSync(`/proc/${pid}/smaps`,"utf8");}catch{return null;}
  if(raw)writeFileSync(raw,text);
  const cats:Record<string,Record<string,number>>={},files:Record<string,Record<string,number>>={};
  let current:{cat:string;name:string}|undefined;
  for(const line of text.split("\n")){
    const header=line.match(/^([0-9a-f]+)-([0-9a-f]+)\s+(\S+)\s+\S+\s+\S+\s+(\d+)\s*(.*)$/u);
    if(header){
      const name=header[5].trim(),size=parseInt(header[2],16)-parseInt(header[1],16);
      current={cat:mappingCategory(name),name};
      const c=cats[current.cat]??={mappings:0,Size:0,Rss:0,Pss:0,Private_Dirty:0,Private_Clean:0,Shared_Clean:0,Shared_Dirty:0,Anonymous:0,Swap:0};
      c.mappings++;c.Size+=size;
      if(name){const f=files[name]??={Size:0,Rss:0,Pss:0};f.Size+=size;}
      continue;
    }
    const field=line.match(/^(Rss|Pss|Private_Dirty|Private_Clean|Shared_Clean|Shared_Dirty|Anonymous|Swap):\s+(\d+) kB$/u);
    if(field&&current){cats[current.cat][field[1]]+=Number(field[2])*1024;if(current.name&&(field[1]==="Rss"||field[1]==="Pss"))files[current.name][field[1]]+=Number(field[2])*1024;}
  }
  const topFiles=Object.entries(files).sort((a,b)=>b[1].Rss-a[1].Rss).slice(0,60).map(([name,v])=>({name,...v}));
  return {categories:cats,topFiles};
}

/** Java heap mappings are anonymous; they are identified from the heap's reserved range (see heapRange). */
let heapRanges:{start:bigint;end:bigint}[]=[];
export function mappingCategory(name:string){
  if(!name)return "anonymous";
  if(name==="[heap]")return "anonymous (brk heap)";
  if(name.startsWith("[stack"))return "main thread stack";
  if(name.startsWith("["))return "kernel ("+name+")";
  if(/librocksdbjni/u.test(name))return "RocksDB JNI library";
  if(/\/libjvm\.so/u.test(name))return "libjvm";
  if(/\/lib\/modules$/u.test(name))return "JDK modules image";
  if(/\.jsa$/u.test(name)||/\.aot$/u.test(name))return "CDS/AOT archive";
  if(/\.sst$/u.test(name))return "Rocks SST files";
  if(/\/(store|rocks)[^/]*\//u.test(name)&&/(MANIFEST|\.log|OPTIONS)/u.test(name))return "Rocks metadata files";
  if(/jvmd-[a-z-]+-0\.1\.0-SNAPSHOT\.jar$|\/lib\/jvmd\//u.test(name))return "JVMD/runtime JARs";
  if(/\.jar$/u.test(name))return "Maven/other JARs";
  if(/jdk-25[^/]*\/lib\/.*\.so$/u.test(name))return "JDK native libraries";
  if(/\.so(\.\d+)*$/u.test(name))return "other shared libraries";
  if(/\/hsperfdata/u.test(name))return "hsperfdata";
  if(/\(deleted\)$/u.test(name))return "deleted files";
  return "other files";
}

/**
 * Anonymous mappings split: the Java heap reservation (from NMT/-Xlog is unavailable in most modes),
 * so heap pages are identified by address range read from `jcmd VM.info`/GC.heap_info once per run.
 */
export function smapsAnonymousSplit(smapsText:string,heapStart:bigint,heapEnd:bigint){
  const out={javaHeap:{Size:0,Rss:0,Pss:0,Private_Dirty:0},otherAnonymous:{Size:0,Rss:0,Pss:0,Private_Dirty:0},threadStackGuess:{Size:0,Rss:0,Pss:0,Private_Dirty:0,count:0}};
  let current:any;
  const lines=smapsText.split("\n");
  for(let i=0;i<lines.length;i++){
    const h=lines[i].match(/^([0-9a-f]+)-([0-9a-f]+)\s+(\S+)\s+\S+\s+\S+\s+(\d+)\s*(.*)$/u);
    if(h){
      current=undefined;if(h[5].trim())continue;
      const s=BigInt("0x"+h[1]),e=BigInt("0x"+h[2]),size=Number(e-s);
      if(s>=heapStart&&e<=heapEnd)current=out.javaHeap;
      // HotSpot thread stacks: a 1 MiB (default Xss) anonymous rw mapping immediately above a guard page.
      else if(h[3]==="rw-p"&&size>=1040384&&size<=1052672&&i>0&&/---p/u.test(lines.slice(Math.max(0,i-30),i).reverse().find(l=>/^[0-9a-f]+-/u.test(l))??"")){current=out.threadStackGuess;out.threadStackGuess.count++;}
      else current=out.otherAnonymous;
      current.Size+=size;continue;
    }
    const f=lines[i].match(/^(Rss|Pss|Private_Dirty):\s+(\d+) kB$/u);
    if(f&&current)current[f[1]]+=Number(f[2])*1024;
  }
  return out;
}

export function jcmd(javaHome:string,pid:number,args:string[],timeout=600000){
  const started=performance.now();
  const r=spawnSync(path.join(javaHome,"bin/jcmd"),[String(pid),...args],{encoding:"utf8",timeout,maxBuffer:1<<30});
  return {ok:r.status===0,stdout:r.stdout??"",stderr:r.stderr??String(r.error??""),ms:performance.now()-started};
}

export function asprof(home:string,pid:number,args:string[]){
  const started=performance.now();
  const r=spawnSync(path.join(home,"bin/asprof"),[...args,String(pid)],{encoding:"utf8",timeout:600000});
  return {ok:r.status===0,stdout:r.stdout??"",stderr:r.stderr??"",ms:performance.now()-started};
}

export type DaemonOptions={javaHome:string;image:string;state:string;repository:string;heapMb:number;jvmArgs:string[];env?:Record<string,string>;
  onStdout?:(line:string)=>void};

/**
 * The benchmark's daemon launch (benchmarks/harness/launch.ts, JvmdDaemon.start), with the allocation
 * probe connected from process start instead of after READY, so M0-M3 can be observed.
 */
export class ProfiledDaemon {
  process!:ChildProcess;socket!:string;config!:string;allocation!:AllocationProbe;control?:RpcClient;
  spawnedMs=0;readyMs?:number;readyLine?:string;stdout="";dir!:string;private stderr!:number;
  static async spawn(o:DaemonOptions){
    const d=new ProfiledDaemon();mkdirSync(o.state,{recursive:true});
    d.dir=path.join(os.tmpdir(),"jvmd-mem-"+process.pid+"-"+Date.now());mkdirSync(d.dir,{recursive:true});
    d.socket=path.join(d.dir,"daemon.sock");const probe=path.join(d.dir,"alloc.sock");
    d.config=path.join(o.state,"config.json");
    writeFileSync(d.config,JSON.stringify({jdk_home:o.javaHome,m2_repo:o.repository,index_on_start:true,heap_ceiling_mb:1024}));
    d.stderr=openSync(path.join(o.state,"daemon.log"),"a");
    const args=[...(o.heapMb>0?["-Xmx"+o.heapMb+"m"]:[]),"-javaagent:"+agentJar(o.javaHome)+"="+probe,...o.jvmArgs,...JVMD_EXPORTS,"--enable-native-access=ALL-UNNAMED",
      "-Djvmd.config="+d.config,"-Djvmd.socket="+d.socket,"-Djvmd.state="+path.join(o.state,"store"),"-Djvmd.resolvers="+path.join(o.image,"lib/jvmd/resolvers"),
      "-Djvmd.index.scan.initial_delay_seconds=0","-cp",path.join(o.image,"lib/jvmd/*"),"dev.jvmd.dist.Application"];
    writeFileSync(path.join(o.state,"command.json"),JSON.stringify([path.join(o.javaHome,"bin/java"),...args]));
    d.spawnedMs=performance.now();
    d.process=spawn(path.join(o.javaHome,"bin/java"),args,{stdio:["ignore","pipe",d.stderr],env:{...process.env,...UTF8,...o.env}});
    const kill=()=>{if(d.process.exitCode===null)d.process.kill("SIGKILL");};process.once("exit",kill);d.process.once("exit",()=>process.removeListener("exit",kill));
    d.process.stdout!.setEncoding("utf8");
    let pending="";
    const stdoutLog=path.join(o.state,"daemon.stdout.log");
    d.process.stdout!.on("data",(chunk:string)=>{d.stdout+=chunk;appendFileSync(stdoutLog,chunk);pending+=chunk;let i;while((i=pending.indexOf("\n"))>=0){const line=pending.slice(0,i);pending=pending.slice(i+1);o.onStdout?.(line);}});
    d.allocation=await AllocationProbe.connect(probe,60000);
    return d;
  }
  get pid(){return this.process.pid!;}
  alive(){return this.process.exitCode===null&&this.process.signalCode===null;}
  async ready(timeoutMs=1800000){
    await new Promise<void>((resolve,reject)=>{
      const timer=setTimeout(()=>reject(new Error("daemon not READY")),timeoutMs);
      const check=()=>{const m=this.stdout.match(/^READY .*$/mu);if(m){clearTimeout(timer);this.readyLine=m[0];resolve();return true;}return false;};
      if(check())return;
      this.process.stdout!.on("data",()=>{check();});
      this.process.once("exit",code=>{clearTimeout(timer);reject(new Error("daemon exited before READY: "+code));});
    });
    this.readyMs=performance.now()-this.spawnedMs;
  }
  async connectControl(){
    for(let i=0;!this.control&&i<600;i++){
      try{this.control=new RpcClient(await new Promise<net.Socket>((resolve,reject)=>{const c=net.createConnection(this.socket);c.once("connect",()=>resolve(c));c.once("error",reject);}));}
      catch{await new Promise(r=>setTimeout(r,100));}
    }
    return this.control;
  }
  async call(method:string,params:any={},timeout=60000){
    const c=await this.connectControl();if(!c)throw new Error("no control socket");
    const r=await c.raw(method,params,timeout);if(r.error)throw new Error(method+": "+r.error.message);return r.result;
  }
  async status(){return (await this.call("daemon.status")).result;}
  async closeSession(root:string){
    const status=await this.status();const closed:string[]=[];
    for(const s of status?.sessions??[])if(path.resolve(s.root)===path.resolve(root)){await this.call("session.close",{session:s.session});closed.push(s.session);}
    return closed;
  }
  async stop(){
    try{await this.call("daemon.shutdown",{},15000);}catch{/* exiting */}
    const exited=await Promise.race([new Promise<boolean>(r=>this.process.once("exit",()=>r(true))),new Promise<boolean>(r=>setTimeout(()=>r(false),60000))]);
    this.control?.close();this.allocation.close();
    if(this.alive())this.process.kill("SIGKILL");
    closeSync(this.stderr);
    return exited||!this.alive();
  }
}

/** Background /proc sampler (and optionally the agent snapshot) at a fixed interval, as JSON lines. */
export class Sampler {
  private timer?:NodeJS.Timeout;private busy=false;count=0;overrunMs=0;
  private file:string;private pid:number;private intervalMs:number;private agent?:AllocationProbe;private extra?:()=>Record<string,any>;
  constructor(file:string,pid:number,intervalMs:number,agent?:AllocationProbe,extra?:()=>Record<string,any>){
    this.file=file;this.pid=pid;this.intervalMs=intervalMs;this.agent=agent;this.extra=extra;
  }
  start(){
    const t0=performance.now();
    this.timer=setInterval(async()=>{
      if(this.busy){this.overrunMs+=this.intervalMs;return;}
      this.busy=true;
      try{
        const row:any={t:Date.now(),mono:performance.now(),proc:procMemory(this.pid),...this.extra?.()};
        if(this.agent){const s=await this.agent.snapshot(false);if(s)row.jvm=compactSnapshot(s);}
        appendFileSync(this.file,JSON.stringify(row)+"\n");this.count++;
      }finally{this.busy=false;}
    },this.intervalMs);
  }
  stop(){if(this.timer)clearInterval(this.timer);}
}

export function compactSnapshot(s:any){
  const pools:Record<string,any>={};for(const [k,v] of Object.entries<any>(s.pools??{}))pools[k]={u:v.usage.used,c:v.usage.committed,p:v.peak.used};
  return {allocated:s.allocated,heapUsed:s.heap.used,heapCommitted:s.heap.committed,nonHeapUsed:s.non_heap.used,nonHeapCommitted:s.non_heap.committed,
    pools,buffers:s.buffers,gc:s.gc,threads:s.threads,classes:s.classes,probe:s.probe_allocated};
}

export function exists(p:string){return existsSync(p);}
