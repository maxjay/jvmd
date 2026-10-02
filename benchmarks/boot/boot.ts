/**
 * Daemon boot/persistence assessment harness (docs/daemon-boot-persistence-assessment.md).
 *
 * Measures one fresh daemon process from spawn to DOMAIN_READY: the first REPOSITORY_RECONCILIATION_FINISHED
 * boot event with reconciled=true, i.e. the initial scan of the whole configured repository finished with
 * every artifact read, the inventory completed and the generation activated, and IndexService's readiness
 * future completed. Cold boots start from an empty state directory; warm boots from a stopped copy of the
 * state a cold boot left at its DOMAIN_READY (the cold daemon is SIGKILLed after its post-ready window,
 * so nothing an orderly shutdown would publish is credited to it).
 *
 * The daemon runs with product defaults (no initial-delay override, no READY policy override, no -Xmx),
 * plus -Djvmd.profile.boot_events/boot_dump and the selected profiling mode's flags only.
 *
 *   node benchmarks/boot/boot.ts pairs    --mode control --pairs 5 ...common
 *   node benchmarks/boot/boot.ts profile  --mode cpu|wall|alloc|native|retention|counters ...common
 *   node benchmarks/boot/boot.ts controls ...common            (small fixture + corpus correctness controls)
 *   node benchmarks/boot/boot.ts scaling  --fractions 25,50,100 ...common
 *   node benchmarks/boot/boot.ts shutdown ...common            (orderly shutdown accounting)
 * common: --image IMAGE --java-home JDK --repository M2 --output DIR [--asprof DIR] [--timeout-min 30]
 */
import {spawn,spawnSync,execFileSync,type ChildProcess} from "node:child_process";
import {appendFileSync,closeSync,cpSync,existsSync,mkdirSync,openSync,readFileSync,readdirSync,rmSync,statSync,writeFileSync,lstatSync} from "node:fs";
import {createHash} from "node:crypto";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import {RpcClient} from "../../shim/src/transport.ts";

const HERE=path.dirname(new URL(import.meta.url).pathname);
const UTF8={LANG:"C.UTF-8",LC_ALL:"C.UTF-8"};
const CLK_TCK=Number(spawnSync("getconf",["CLK_TCK"],{encoding:"utf8"}).stdout.trim()||"100");
const PAGE=Number(spawnSync("getconf",["PAGESIZE"],{encoding:"utf8"}).stdout.trim()||"4096");
const POST_READY_MS=5000;

type Mode="control"|"counters"|"cpu"|"wall"|"alloc"|"native"|"retention";
type Common={image:string;javaHome:string;repository:string;output:string;asprof?:string;timeoutMs:number};
type Terminate={kind:"kill"}|{kind:"graceful"}|{kind:"kill-after";event:string;delayMs:number};
type BootOptions=Common&{label:string;mode:Mode;state:string;dir:string;terminate:Terminate;repositoryOverride?:string;
  /** Labelled supplementary runs only: system properties that change product defaults. */
  props?:Record<string,string>};

function args(argv:string[]){
  const out:Record<string,string>={};for(let i=0;i<argv.length;i++)if(argv[i].startsWith("--")){out[argv[i].slice(2)]=argv[i+1]??"";i++;}
  return out;
}
const now=()=>process.hrtime.bigint();
const ms=(ns:bigint|number)=>Number(ns)/1e6;
function log(line:string){console.log(new Date().toISOString().slice(11,19)+" "+line);}

/** /proc observations of one pid; every field is null when unreadable (process gone). */
function procSample(pid:number){
  const row:any={mono_ns:String(now())};
  try{
    const stat=readFileSync(`/proc/${pid}/stat`,"utf8");const f=stat.slice(stat.lastIndexOf(")")+2).split(" ");
    // fields after comm: state(0) ... minflt(7) majflt(9) utime(11) stime(12) num_threads(17) starttime(19) rss(21)
    row.minflt=Number(f[7]);row.majflt=Number(f[9]);row.utime_ms=Number(f[11])*1000/CLK_TCK;row.stime_ms=Number(f[12])*1000/CLK_TCK;
    row.threads=Number(f[17]);row.starttime_ticks=Number(f[19]);row.rss_bytes=Number(f[21])*PAGE;
  }catch{return null;}
  try{for(const m of readFileSync(`/proc/${pid}/status`,"utf8").matchAll(/^(VmHWM|VmRSS|RssAnon|RssFile|RssShmem|VmSwap):\s+(\d+) kB$/gmu))row[m[1]]=Number(m[2])*1024;}catch{}
  try{for(const m of readFileSync(`/proc/${pid}/io`,"utf8").matchAll(/^(\w+):\s+(\d+)$/gmu))row["io_"+m[1]]=Number(m[2]);}catch{}
  return row;
}
function smapsRollup(pid:number){
  const out:Record<string,number>={};
  try{for(const m of readFileSync(`/proc/${pid}/smaps_rollup`,"utf8").matchAll(/^(\w+):\s+(\d+) kB$/gmu))out[m[1]]=Number(m[2])*1024;}catch{}
  return out;
}

function listing(dir:string){
  const files:Record<string,{size:number;mtime_ms:number}>={};
  const walk=(d:string)=>{if(!existsSync(d))return;for(const name of readdirSync(d)){const p=path.join(d,name);const s=lstatSync(p);
    if(s.isDirectory())walk(p);else if(s.isFile())files[path.relative(dir,p)]={size:s.size,mtime_ms:s.mtimeMs};}};
  walk(dir);return files;
}
function listingDiff(a:Record<string,any>,b:Record<string,any>){
  const added=Object.keys(b).filter(k=>!(k in a)),removed=Object.keys(a).filter(k=>!(k in b));
  const changed=Object.keys(a).filter(k=>k in b&&(a[k].size!==b[k].size||a[k].mtime_ms!==b[k].mtime_ms));
  const bytes=(o:Record<string,any>,keys:string[])=>keys.reduce((s,k)=>s+o[k].size,0);
  return {added,removed,changed,added_bytes:bytes(b,added),removed_bytes:bytes(a,removed),
    changed_size_delta:changed.reduce((s,k)=>s+b[k].size-a[k].size,0),
    total_before:bytes(a,Object.keys(a)),total_after:bytes(b,Object.keys(b))};
}
/** Content hash of a whole tree: used to prove an observer did not modify a copy. */
function treeHash(dir:string){
  const h=createHash("sha256");const files=listing(dir);
  for(const name of Object.keys(files).sort()){h.update(name+"\0");h.update(readFileSync(path.join(dir,name)));}
  return h.digest("hex");
}
/** Stopped-process copy, preserving mtimes. Never a hard link (warm runs mutate their copy). */
function copyTree(from:string,to:string){
  rmSync(to,{recursive:true,force:true});mkdirSync(path.dirname(to),{recursive:true});
  const r=spawnSync("cp",["-a","--no-preserve=links",from,to],{encoding:"utf8"});if(r.status!==0)throw new Error("cp failed: "+r.stderr);
}

function readEvents(file:string){
  if(!existsSync(file))return [];
  return readFileSync(file,"utf8").split("\n").filter(Boolean).map(l=>{try{return JSON.parse(l);}catch{return {event:"UNPARSEABLE",raw:l.slice(0,200)};}});
}

function launchCommand(o:BootOptions,events:string){
  const imageJava=path.join(o.image,"bin/java"),fullJava=path.join(o.javaHome,"bin/java");
  const product=o.mode!=="counters";
  const java=product?imageJava:fullJava;
  const flags:string[]=[];
  if(product)flags.push("-XX:AOTCache="+path.join(o.image,"lib/jvmd/jvmd.aot"),"-Xlog:aot=info:file="+path.join(o.state,"aot.log"),"-Djvmd.aot.log="+path.join(o.state,"aot.log"));
  else{
    // The jlink image omits jdk.management; the full JDK of the same build reports cumulative allocation.
    // Its exports are baked into the image, so they are added here. The AOT cache belongs to the image.
    flags.push(...["api","util","code","main","platform"].map(p=>"--add-exports=jdk.compiler/com.sun.tools.javac."+p+"=ALL-UNNAMED"),"--enable-native-access=ALL-UNNAMED");
    flags.push("-Xlog:gc*,safepoint:file="+path.join(o.dir,"gc.log")+":uptime,uptimenanos,level,tags");
  }
  const asprof=o.asprof?path.join(o.asprof,"lib/libasyncProfiler.so"):"";
  if(o.mode==="cpu")flags.push(`-agentpath:${asprof}=start,event=cpu,interval=1ms,cstack=vm,file=${path.join(o.dir,"cpu.jfr")}`);
  if(o.mode==="wall")flags.push(`-agentpath:${asprof}=start,event=wall,interval=5ms,file=${path.join(o.dir,"wall.jfr")}`);
  if(o.mode==="alloc")flags.push(`-agentpath:${asprof}=start,event=alloc,alloc=256k,file=${path.join(o.dir,"alloc.jfr")}`);
  if(o.mode==="native")flags.push("-XX:NativeMemoryTracking=summary");
  const config=path.join(o.dir,"config.json");
  writeFileSync(config,JSON.stringify({jdk_home:o.javaHome,m2_repo:o.repositoryOverride??o.repository}));
  const socket=path.join(o.dir,"daemon.sock");
  for(const [k,v] of Object.entries(o.props??{}))flags.push(`-D${k}=${v}`);
  return {java,socket,config,args:[...flags,"-Djvmd.config="+config,"-Djvmd.state="+o.state,"-Djvmd.socket="+socket,
    "-Djvmd.profile.boot_events="+events,"-Djvmd.profile.boot_dump=true","-cp",path.join(o.image,"lib/jvmd/*"),"dev.jvmd.dist.Application"]};
}

async function rpc(socket:string,method:string,params:any={},timeout=60000){
  const s=await new Promise<net.Socket>((resolve,reject)=>{const c=net.createConnection(socket);c.once("connect",()=>resolve(c));c.once("error",reject);});
  const client=new RpcClient(s);
  // The JSON-RPC result is the daemon's envelope; its payload is envelope.result.
  try{const r=await client.raw(method,params,timeout);if(r.error)throw new Error(method+": "+JSON.stringify(r.error));return r.result?.result??r.result;}
  finally{s.destroy();}
}

export async function boot(o:BootOptions){
  mkdirSync(o.dir,{recursive:true});
  const events=path.join(o.dir,"milestones.jsonl"),samples=path.join(o.dir,"samples.jsonl");
  const stateExisted=existsSync(o.state);
  const preListing=listing(o.state);
  // As the product launcher does (mkdir -p "$state"): the AOT log is written there from JVM start.
  mkdirSync(o.state,{recursive:true});
  const cmd=launchCommand(o,events);
  writeFileSync(path.join(o.dir,"command.json"),JSON.stringify([cmd.java,...cmd.args],null,1));
  const stdout=openSync(path.join(o.dir,"daemon.stdout.log"),"w"),stderr=openSync(path.join(o.dir,"daemon.log"),"w");
  const spawnNs=now();
  const child:ChildProcess=spawn(cmd.java,cmd.args,{stdio:["ignore","pipe",stderr],env:{...process.env,...UTF8}});
  const kill=()=>{if(child.exitCode===null)child.kill("SIGKILL");};process.once("exit",kill);
  const pid=child.pid!;
  let readyStdoutNs:bigint|undefined;let out="";
  child.stdout!.on("data",chunk=>{appendFileSync(stdout,chunk);out+=chunk;if(readyStdoutNs===undefined&&/^READY /mu.test(out))readyStdoutNs=now();});
  let exitNs:bigint|undefined;let exitCode:number|null=null,exitSignal:string|null=null;
  const exited=new Promise<void>(resolve=>child.once("exit",(code,signal)=>{exitNs=now();exitCode=code;exitSignal=signal;resolve();}));
  const procStart=procSample(pid);
  const identity={pid,spawn_mono_ns:String(spawnNs),starttime_ticks:procStart?.starttime_ticks,
    cmdline:(()=>{try{return readFileSync(`/proc/${pid}/cmdline`,"utf8").split("\0").filter(Boolean);}catch{return null;}})(),
    state_existed_before:stateExisted,state_files_before:Object.keys(preListing).length,state_bytes_before:Object.values(preListing).reduce((s,f)=>s+f.size,0)};

  // 100 ms /proc sampler; smaps_rollup every second. Its cost is the harness's, not the daemon's.
  let last:any=procStart;let ticks=0;
  const sampler=setInterval(()=>{const s=procSample(pid);if(!s)return;if(++ticks%10===0)s.smaps=smapsRollup(pid);last=s;appendFileSync(samples,JSON.stringify(s)+"\n");},100);

  const result:any={label:o.label,mode:o.mode,state:o.state,identity,terminate:o.terminate};
  const deadline=Date.now()+o.timeoutMs;
  let seen:any[]=[];let domain:any;let failure:string|undefined;let killedEarly=false;let socketOwner:string|undefined;
  let killAfterArmed:bigint|undefined;
  for(;;){
    await new Promise(r=>setTimeout(r,25));
    seen=readEvents(events);
    if(!socketOwner&&seen.some(e=>e.event==="SOCKET_LISTENING")){
      const r=spawnSync("ss",["-xlp"],{encoding:"utf8"});socketOwner=(r.stdout||"").split("\n").filter(l=>l.includes(cmd.socket)).join("\n")||"(not listed)";
    }
    if(o.terminate.kind==="kill-after"){
      const t=o.terminate;
      if(killAfterArmed===undefined&&seen.some(e=>e.event===t.event))killAfterArmed=now();
      if(killAfterArmed!==undefined&&ms(now()-killAfterArmed)>=t.delayMs){
        result.killed_mono_ns=String(now());result.proc_at_kill=procSample(pid);child.kill("SIGKILL");killedEarly=true;break;
      }
    }
    const finished=seen.find(e=>e.event==="REPOSITORY_RECONCILIATION_FINISHED");
    if(finished){
      result.detect_mono_ns=String(now());result.proc_at_detect=procSample(pid);result.native_libraries=nativeLibraries(pid);
      if(finished.reconciled===true)domain=finished;else failure="reconciliation finished without a complete reconciliation: "+finished.state+" "+finished.failure;
      break;
    }
    if(exitNs!==undefined){failure=`daemon exited before DOMAIN_READY: code=${exitCode} signal=${exitSignal}`;break;}
    if(Date.now()>deadline){failure="timeout: DOMAIN_READY not observed within "+o.timeoutMs/60000+" min";result.thread_dump=jcmd(o.javaHome,pid,["Thread.print"],path.join(o.dir,"timeout-threads.txt"));break;}
  }
  if(domain&&!killedEarly){
    // Runtime view dump follows the endpoint on the scanner thread.
    for(let i=0;i<400&&!readEvents(events).some(e=>e.event==="RUNTIME_VIEWS");i++)await new Promise(r=>setTimeout(r,25));
    await new Promise(r=>setTimeout(r,POST_READY_MS));
    result.proc_post_ready=procSample(pid);result.smaps_post_ready=smapsRollup(pid);
    const extra=modeCollection(o,pid);if(extra)result.collection=extra;
    try{result.status=await rpc(cmd.socket,"daemon.status",{},60000);}catch(e){result.status_error=String(e);}
    result.listing_at_ready=listing(o.state);
  }
  if(!killedEarly&&exitNs===undefined){
    if(o.terminate.kind==="graceful"&&domain){
      const before=procSample(pid);result.shutdown_requested_mono_ns=String(now());
      try{await rpc(cmd.socket,"daemon.shutdown",{},15000);}catch(e){result.shutdown_rpc_error=String(e);}
      const ok=await Promise.race([exited.then(()=>true),new Promise<boolean>(r=>setTimeout(()=>r(false),120000))]);
      result.shutdown_graceful=ok;result.proc_before_shutdown=before;result.proc_last_seen=last;
      if(!ok)child.kill("SIGKILL");
    }else{result.killed_mono_ns=String(now());result.proc_at_kill=procSample(pid);child.kill("SIGKILL");}
  }
  await exited;clearInterval(sampler);process.removeListener("exit",kill);closeSync(stdout);closeSync(stderr);
  result.exit={code:exitCode,signal:exitSignal,mono_ns:String(exitNs)};
  result.listing_after_exit=listing(o.state);
  if(result.listing_at_ready)result.exit_vs_ready=listingDiff(result.listing_at_ready,result.listing_after_exit);
  result.socket_owner=socketOwner;
  result.events=seen=readEvents(events);
  result.failure=failure;result.domain_ready=!!domain;
  result.series=series(samples,spawnNs);result.store_files=storeFiles(result.listing_at_ready??result.listing_after_exit);
  if(o.mode==="counters")result.gc=parseGcLog(path.join(o.dir,"gc.log"),result.events,spawnNs);
  if(o.mode==="retention")result.retention=parseRetention(o.dir);
  if(o.mode==="native")result.nmt=parseNmt(path.join(o.dir,"nmt-summary.txt"));
  result.summary=summarize(result,spawnNs,readyStdoutNs);
  writeFileSync(path.join(o.dir,"result.json"),JSON.stringify(result,null,1));
  writeFileSync(path.join(o.dir,"summary.json"),JSON.stringify(result.summary,null,1));
  if(!domain&&!killedEarly)for(const line of tail(path.join(o.dir,"daemon.log"),25).concat(tail(path.join(o.dir,"daemon.stdout.log"),10)))log("  | "+line);
  log(`${o.label}: ${domain?"DOMAIN_READY "+result.summary.t_ms.DOMAIN_READY?.toFixed(0)+" ms":"NOT REACHED ("+(failure??(killedEarly?"killed by design":"?"))+")"} product READY ${result.summary.t_ms.PRODUCT_READY?.toFixed(0)} ms`);
  return result;
}

/** Every fifth 100 ms sample (and the last), relative to spawn: CPU, RSS and process I/O over time. */
function series(file:string,spawnNs:bigint){
  if(!existsSync(file))return [];
  const rows=readFileSync(file,"utf8").split("\n").filter(Boolean).map(l=>JSON.parse(l));
  return rows.filter((_,i)=>i%5===0||i===rows.length-1).map(r=>[Math.round(ms(BigInt(r.mono_ns)-spawnNs)),r.utime_ms,r.stime_ms,r.VmRSS,r.io_read_bytes,r.io_write_bytes,r.threads]);
}
/** SST and WAL files per Rocks database directory of a state listing. */
function storeFiles(files:Record<string,{size:number}>){
  const out:Record<string,{sst:number;sst_bytes:number;wal:number;wal_bytes:number;other_bytes:number}>={};
  for(const [name,f] of Object.entries(files??{})){
    const dir=path.dirname(name);const o=out[dir]??={sst:0,sst_bytes:0,wal:0,wal_bytes:0,other_bytes:0};
    if(name.endsWith(".sst")){o.sst++;o.sst_bytes+=f.size;}else if(/\/\d+\.log$/u.test(name)){o.wal++;o.wal_bytes+=f.size;}else o.other_bytes+=f.size;
  }
  return out;
}
/** G1 pauses from -Xlog:gc: count, total pause, peak heap before a pause and live heap after, up to DOMAIN_READY and in total. */
function parseGcLog(file:string,events:any[],spawnNs:bigint){
  if(!existsSync(file))return null;
  const dr=events.find((e:any)=>e.event==="REPOSITORY_RECONCILIATION_FINISHED");
  const drUptime=dr?ms(BigInt(dr.mono_ns)-spawnNs)/1000:Infinity;// JVM uptime ≈ time since spawn (launch latency of a few ms)
  const acc=(rows:any[])=>({pauses:rows.length,pause_ms:rows.reduce((s,r)=>s+r.ms,0),max_before_mb:Math.max(0,...rows.map(r=>r.before)),
    max_after_mb:Math.max(0,...rows.map(r=>r.after)),max_committed_mb:Math.max(0,...rows.map(r=>r.committed)),last_after_mb:rows.at(-1)?.after??null,
    young:rows.filter(r=>/Young/u.test(r.kind)).length,mixed:rows.filter(r=>/Mixed/u.test(r.kind)).length,full:rows.filter(r=>/Full/u.test(r.kind)).length,
    concurrent_cycles:0});
  const rows:any[]=[];let cycles=0;
  for(const line of readFileSync(file,"utf8").split("\n")){
    const m=line.match(/^\[([\d.]+)s\].*?\[gc\s*\] GC\(\d+\) (Pause .+?) (\d+)M->(\d+)M\((\d+)M\) ([\d.]+)ms/u);
    if(m)rows.push({t:Number(m[1]),kind:m[2],before:Number(m[3]),after:Number(m[4]),committed:Number(m[5]),ms:Number(m[6])});
    if(/Concurrent Mark Cycle [\d.]+ms/u.test(line))cycles++;
  }
  const until=acc(rows.filter(r=>r.t<=drUptime)),all=acc(rows);all.concurrent_cycles=cycles;
  // Lower bound on heap allocation up to the last pause before DOMAIN_READY: what each pause found minus what the
  // previous one left. A cross-check of the in-process counter's coverage, not a replacement for it.
  const before=rows.filter(r=>r.t<=drUptime);let allocated=0,previous=0;
  for(const r of before){allocated+=Math.max(0,r.before-previous);previous=r.after;}
  return {until_domain_ready:until,whole_process:all,domain_ready_uptime_s:drUptime,allocation_lower_bound_mb_until_last_pause:allocated,
    last_pause_before_domain_ready_s:before.at(-1)?.t??null,note:"uptime correlated to spawn; heap figures are at GC pauses only"};
}
function parseRetention(dir:string){
  const read=(f:string)=>{try{return readFileSync(path.join(dir,f),"utf8");}catch{return "";}};
  const heap=(t:string)=>{const m=t.match(/garbage-first heap\s+total reserved (\d+)K, committed (\d+)K, used (\d+)K/u);return m?{reserved_kb:Number(m[1]),committed_kb:Number(m[2]),used_kb:Number(m[3])}:null;};
  const hist=read("class-histogram.txt").split("\n").filter(l=>/^\s*\d+:/u.test(l)).slice(0,25).map(l=>{const f=l.trim().split(/\s+/u);return [f[3],Number(f[1]),Number(f[2])];});
  const total=read("class-histogram.txt").match(/^Total\s+(\d+)\s+(\d+)/mu);
  const threads=(read("threads.txt").match(/^"/gmu)??[]).length;
  return {heap_before_gc:heap(read("heap-info-before.txt")),heap_after_full_gc:heap(read("heap-info-after-gc.txt")),
    histogram_total:total?{instances:Number(total[1]),bytes:Number(total[2])}:null,histogram_top:hist,platform_threads_listed:threads,
    note:"Retention procedure: GC.class_histogram forces a full GC; values after it are altered by the procedure"};
}
function parseNmt(file:string){
  if(!existsSync(file))return null;
  const t=readFileSync(file,"utf8");const out:any={};
  const total=t.match(/Total: reserved=(\d+)KB, committed=(\d+)KB/u);if(total)out.total={reserved_kb:Number(total[1]),committed_kb:Number(total[2])};
  out.categories={};
  for(const m of t.matchAll(/^-\s+([\w ]+?) \(reserved=(\d+)KB, committed=(\d+)KB\)/gmu))out.categories[m[1].trim()]={reserved_kb:Number(m[2]),committed_kb:Number(m[3])};
  out.note="NMT covers JVM-owned native memory; RocksDB (malloc outside the JVM) is not in NMT";
  return out;
}
/** Mapped RocksDB JNI library: where the process loaded it from and its size (extracted copies live in java.io.tmpdir). */
function nativeLibraries(pid:number){
  try{
    const paths=[...new Set(readFileSync(`/proc/${pid}/maps`,"utf8").split("\n").map(l=>l.trim().split(/\s+/u)[5]).filter(p=>p&&/rocksdbjni/u.test(p)))];
    return paths.map(p=>{let size:number|null=null;try{size=statSync(p.replace(/ \(deleted\)$/u,"")).size;}catch{}return {path:p,size};});
  }catch{return null;}
}
function jcmd(javaHome:string,pid:number,command:string[],file:string){
  const started=now();
  const r=spawnSync(path.join(javaHome,"bin/jcmd"),[String(pid),...command],{encoding:"utf8",timeout:600000,maxBuffer:1<<30});
  writeFileSync(file,(r.stdout??"")+(r.stderr??""));return {file,ok:r.status===0,ms:ms(now()-started)};
}
function asprofStop(o:BootOptions,pid:number,name:string){
  if(!o.asprof)return {error:"no async-profiler"};
  const started=now();const file=path.join(o.dir,name+".jfr");
  const r=spawnSync(path.join(o.asprof,"bin/asprof"),["stop","-f",file,String(pid)],{encoding:"utf8",timeout:600000});
  return {file,ok:r.status===0,stderr:r.stderr,stdout:r.stdout,ms:ms(now()-started)};
}
/** Mode-specific evidence, collected after the post-ready window and outside every timed interval. */
function modeCollection(o:BootOptions,pid:number){
  switch(o.mode){
    case "cpu":return asprofStop(o,pid,"cpu");
    case "wall":return asprofStop(o,pid,"wall");
    case "alloc":return asprofStop(o,pid,"alloc");
    case "native":return {nmt:jcmd(o.javaHome,pid,["VM.native_memory","summary"],path.join(o.dir,"nmt-summary.txt")),
      smaps:(()=>{try{writeFileSync(path.join(o.dir,"smaps.txt"),readFileSync(`/proc/${pid}/smaps`,"utf8"));return true;}catch{return false;}})()};
    case "retention":return {
      // Ordered: heap info before the forced GC of the histogram, then threads.
      heap_before:jcmd(o.javaHome,pid,["GC.heap_info"],path.join(o.dir,"heap-info-before.txt")),
      histogram:jcmd(o.javaHome,pid,["GC.class_histogram"],path.join(o.dir,"class-histogram.txt")),
      heap_after:jcmd(o.javaHome,pid,["GC.heap_info"],path.join(o.dir,"heap-info-after-gc.txt")),
      threads:jcmd(o.javaHome,pid,["Thread.print"],path.join(o.dir,"threads.txt")),
      smaps:(()=>{try{writeFileSync(path.join(o.dir,"smaps.txt"),readFileSync(`/proc/${pid}/smaps`,"utf8"));return true;}catch{return false;}})()};
    default:return undefined;
  }
}

const MILESTONES=["MAIN_ENTERED","OBSERVATION_JOURNAL_LOAD_BEGIN","OBSERVATION_JOURNAL_LOAD_END","STORAGE_OPEN_BEGIN","STORAGE_OPEN_END",
  "INITIAL_SCAN_SCHEDULED","APPLICATION_CONSTRUCTED","SOCKET_LISTENING","SCAN_STARTED","DISCOVERY_COMPLETE","SKELETONS_COMPLETE","DOCS_COMPLETE",
  "INVENTORY_COMPLETED","PATHS_RECONCILED","SCAN_COMPLETE","INITIAL_SCAN_RETURNED","SESSION_CAPABLE","PRODUCT_READY","REPOSITORY_RECONCILIATION_FINISHED","RUNTIME_VIEWS",
  // MACHINE cold boot stages (layer model).
  "MACHINE_CREATED","MACHINE_ENUMERATED","MACHINE_ARTIFACTS_BUILT","MACHINE_TREE_BUILT","MACHINE_COMMITTED"];

function summarize(r:any,spawnNs:bigint,readyStdoutNs?:bigint){
  const t:Record<string,number>={};const first:Record<string,any>={};
  for(const e of r.events){if(e.event&&!(e.event in first)){first[e.event]=e;}}
  for(const name of MILESTONES)if(first[name])t[name]=ms(BigInt(first[name].mono_ns)-spawnNs);
  if(readyStdoutNs!==undefined)t.PRODUCT_READY_STDOUT=ms(readyStdoutNs-spawnNs);
  if(r.domain_ready)t.DOMAIN_READY=t.REPOSITORY_RECONCILIATION_FINISHED;
  if(r.exit?.mono_ns&&r.exit.mono_ns!=="undefined")t.EXIT=ms(BigInt(r.exit.mono_ns)-spawnNs);
  const shutdownBegin=r.events.find((e:any)=>e.event==="SHUTDOWN_BEGIN"),shutdownEnd=r.events.find((e:any)=>e.event==="SHUTDOWN_END");
  if(shutdownBegin)t.SHUTDOWN_BEGIN=ms(BigInt(shutdownBegin.mono_ns)-spawnNs);if(shutdownEnd)t.SHUTDOWN_END=ms(BigInt(shutdownEnd.mono_ns)-spawnNs);
  const at=r.proc_at_detect,post=r.proc_post_ready;
  const d=first.REPOSITORY_RECONCILIATION_FINISHED;
  const cpu=(s:any)=>s?{user_ms:s.utime_ms,system_ms:s.stime_ms}:null;
  const alloc=(e:any)=>e&&e.allocated_bytes>=0?e.allocated_bytes:null;
  return {
    label:r.label,mode:r.mode,domain_ready:r.domain_ready,failure:r.failure,t_ms:t,
    // Harness detection lags the in-process event by the polling interval; /proc values are read at detection.
    detection_lag_ms:d&&r.detect_mono_ns?ms(BigInt(r.detect_mono_ns)-BigInt(d.mono_ns)):null,
    cpu_at_domain_ready:cpu(at),cpu_post_ready_delta:at&&post?{user_ms:post.utime_ms-at.utime_ms,system_ms:post.stime_ms-at.stime_ms}:null,
    in_process_cpu_ns_at_domain_ready:d&&d.process_cpu_ns>=0?d.process_cpu_ns:null,
    allocated_bytes:{SESSION_CAPABLE:alloc(first.SESSION_CAPABLE),PRODUCT_READY:alloc(first.PRODUCT_READY),DOMAIN_READY:alloc(d)},
    peak_rss_bytes_at_domain_ready:at?.VmHWM??null,rss_bytes_at_domain_ready:at?.VmRSS??null,
    rss_post_ready:post?{VmRSS:post.VmRSS,RssAnon:post.RssAnon,RssFile:post.RssFile,VmHWM:post.VmHWM}:null,pss_post_ready:r.smaps_post_ready?.Pss??null,
    io_at_domain_ready:at?{rchar:at.io_rchar,wchar:at.io_wchar,read_bytes:at.io_read_bytes,write_bytes:at.io_write_bytes,syscr:at.io_syscr,syscw:at.io_syscw}:null,
    io_post_ready_delta:at&&post?{read_bytes:post.io_read_bytes-at.io_read_bytes,write_bytes:post.io_write_bytes-at.io_write_bytes,wchar:post.io_wchar-at.io_wchar}:null,
    faults_at_domain_ready:at?{minor:at.minflt,major:at.majflt}:null,threads_at_domain_ready:at?.threads??null,
    counters:d?.counters??first.SCAN_COMPLETE?.counters??null,in_flight:d?.in_flight??null,native_libraries:r.native_libraries??null,gc:r.gc??null,retention:r.retention??null,nmt:r.nmt??null,series:r.series,store_files:r.store_files,
    status_error:r.status_error??null,
    session_capable:first.SESSION_CAPABLE?{persisted_index_complete:first.SESSION_CAPABLE.persisted_index_complete}:null,
    storage_open_end:first.STORAGE_OPEN_END?{scan_completed:first.STORAGE_OPEN_END.scan_completed}:null,
    discovery:first.DISCOVERY_COMPLETE?{jars:first.DISCOVERY_COMPLETE.jars,sources_jars:first.DISCOVERY_COMPLETE.sources_jars}:null,
    runtime_dump_ms:first.RUNTIME_VIEWS?first.RUNTIME_VIEWS.dump_ns/1e6:null,
    index_status:r.status?.index?{counts:pick(r.status.index,["artifacts","symbols","edges","simple_names","unmatched_source_members"]),
      scanned:r.status.index.scanned,indexed:r.status.index.indexed,reused:r.status.index.reused,hashes:r.status.index.hashes,faults:r.status.index.faults,
      reconciliation:r.status.index.reconciliation,timings:r.status.index.timings,store:r.status.index.store,
      storage:r.status.index.storage?{...r.status.index.storage,repository:r.status.index.storage.repository}:null}:null,
    readiness:r.status?.readiness??null,classpath_files:r.status?.classpath_files??null,aot_cache:r.status?.aot_cache??null,
    aot_log_head:(()=>{try{return readFileSync(path.join(r.state,"aot.log"),"utf8").split("\n").slice(0,6);}catch{return null;}})(),
    exit_vs_ready:r.exit_vs_ready?{added:r.exit_vs_ready.added.length,removed:r.exit_vs_ready.removed.length,changed:r.exit_vs_ready.changed.length,
      added_bytes:r.exit_vs_ready.added_bytes,removed_bytes:r.exit_vs_ready.removed_bytes,changed_size_delta:r.exit_vs_ready.changed_size_delta,
      files:{added:r.exit_vs_ready.added.slice(0,40),removed:r.exit_vs_ready.removed.slice(0,40),changed:r.exit_vs_ready.changed.slice(0,40)}}:null,
    state_bytes_after_exit:Object.values(r.listing_after_exit??{}).reduce((s:number,f:any)=>s+f.size,0),
    identity:{pid:r.identity.pid,starttime_ticks:r.identity.starttime_ticks,state_existed_before:r.identity.state_existed_before,
      state_files_before:r.identity.state_files_before},socket_owner:r.socket_owner,exit:r.exit,
  };
}
function pick(o:any,keys:string[]){const out:any={};for(const k of keys)if(o&&k in o)out[k]=o[k];return out;}

/** Offline export of a stopped state copy; the copy is hashed before and after to prove the exporter wrote nothing. */
function exportState(c:Common,state:string,out:string,options:{full?:boolean;verifyNoninterference?:boolean}={}){
  const validation=out.replace(/\.json$/u,"")+"-validation-copy";
  copyTree(state,validation);
  const before=options.verifyNoninterference?treeHash(validation):undefined;
  const started=now();
  const r=spawnSync(path.join(c.javaHome,"bin/java"),["-cp",path.join(c.image,"lib/jvmd/*"),path.join(HERE,"StoreExport.java"),validation,out,...(options.full?["--full"]:[])],
    {encoding:"utf8",maxBuffer:1<<28,env:{...process.env,...UTF8}});
  const exportMs=ms(now()-started);
  if(r.status!==0)throw new Error("StoreExport failed: "+r.stderr+r.stdout);
  let noninterference:any;
  if(options.verifyNoninterference){
    const r2=spawnSync(path.join(c.javaHome,"bin/java"),["-cp",path.join(c.image,"lib/jvmd/*"),path.join(HERE,"StoreExport.java"),validation,out+".second",...(options.full?["--full"]:[])],{encoding:"utf8",maxBuffer:1<<28});
    const after=treeHash(validation);
    const same=r2.status===0&&JSON.stringify(JSON.parse(readFileSync(out,"utf8")).family_digests)===JSON.stringify(JSON.parse(readFileSync(out+".second","utf8")).family_digests);
    noninterference={tree_hash_before:before,tree_hash_after:after,unchanged:before===after,repeat_export_identical:same};
    rmSync(out+".second",{force:true});
  }
  rmSync(validation,{recursive:true,force:true});
  return {file:out,export_ms:exportMs,noninterference};
}
function compareExports(a:string,b:string,out:string,runtimeA?:string,runtimeB?:string){
  const extra:string[]=[];if(runtimeA)extra.push("--runtime-a",runtimeA);if(runtimeB)extra.push("--runtime-b",runtimeB);
  const r=spawnSync("python3",[path.join(HERE,"compare.py"),a,b,out,...extra],{encoding:"utf8"});
  if(!existsSync(out))throw new Error("compare failed: "+r.stderr);
  return JSON.parse(readFileSync(out,"utf8"));
}

/** One cold/warm pair: cold to DOMAIN_READY, SIGKILL, stopped copy, warm from that copy, compare outside every timed interval. */
async function pair(c:Common,o:{id:string;mode:Mode;repository?:string;exportFull?:boolean;noninterference?:boolean;keepSnapshot?:boolean}){
  const dir=path.join(c.output,o.id);
  const coldState=path.join(dir,"cold/state"),warmState=path.join(dir,"warm/state"),snapshot=path.join(dir,"snapshot-cold-ready");
  if(existsSync(coldState))throw new Error("cold state must start empty: "+coldState);
  const cold=await boot({...c,label:o.id+"/cold",mode:o.mode,state:coldState,dir:path.join(dir,"cold"),terminate:{kind:"kill"},repositoryOverride:o.repository});
  const provenance:any={pair:o.id,mode:o.mode,cold_terminated:"SIGKILL after DOMAIN_READY + "+POST_READY_MS+" ms post-ready window (no orderly shutdown)",
    warm_store_provenance:"cold-ready recoverable state",repository:o.repository??c.repository};
  if(!cold.domain_ready){provenance.warm_store_provenance="not established: cold boot did not reach DOMAIN_READY";writeFileSync(path.join(dir,"publication-provenance.json"),JSON.stringify(provenance,null,1));return {cold,warm:null,comparison:null};}
  copyTree(coldState,snapshot);
  provenance.snapshot_listing=listing(snapshot);provenance.snapshot_vs_cold_ready_listing=listingDiff(cold.listing_at_ready,provenance.snapshot_listing);
  copyTree(snapshot,warmState);
  const warm=await boot({...c,label:o.id+"/warm",mode:o.mode,state:warmState,dir:path.join(dir,"warm"),terminate:{kind:"kill"},repositoryOverride:o.repository});
  writeFileSync(path.join(dir,"publication-provenance.json"),JSON.stringify(provenance,null,1));
  // Semantic comparison, outside both timed intervals, on validation copies.
  const coldExport=exportState(c,snapshot,path.join(dir,"cold-export.json"),{full:o.exportFull,verifyNoninterference:o.noninterference});
  const warmExport=exportState(c,warmState,path.join(dir,"warm-export.json"),{full:o.exportFull});
  const comparison=compareExports(coldExport.file,warmExport.file,path.join(dir,"semantic-comparison.json"),path.join(dir,"cold/milestones.jsonl"),path.join(dir,"warm/milestones.jsonl"));
  const equivalence=classify(comparison,warm);
  writeFileSync(path.join(dir,"pair.json"),JSON.stringify({id:o.id,mode:o.mode,equivalence,cold:cold.summary,warm:warm.summary,
    exports:{cold:coldExport,warm:warmExport},verdict:comparison.verdict,runtime:comparison.runtime?.status,
    runtime_vs_disk:{cold:comparison.runtime_vs_disk_a?.status,warm:comparison.runtime_vs_disk_b?.status}},null,1));
  log(`${o.id}: ${equivalence.outcome} (exports ${comparison.verdict}, runtime ${comparison.runtime?.status})`);
  if(!o.keepSnapshot){rmSync(snapshot,{recursive:true,force:true});rmSync(coldState,{recursive:true,force:true});rmSync(warmState,{recursive:true,force:true});}
  return {cold,warm,comparison,equivalence,snapshot,dir};
}
function classify(comparison:any,warm:any){
  const c=warm?.summary?.counters??{};
  const recomputed={jars_parsed:c["jar.parsed"]??0,sources_parsed:c["sources.parsed"]??0,jar_hashes:c["jar.hash_calls"]??0};
  const equal=comparison.verdict==="EQUAL"&&(comparison.runtime?.status??"EQUAL")==="EQUAL"&&warm?.domain_ready;
  return {outcome:!equal?"NOT_EQUIVALENT / NOT_ESTABLISHED":recomputed.jars_parsed+recomputed.sources_parsed>0?"EQUIVALENT_WITH_RECOMPUTATION":"EQUIVALENT_BY_REUSE",
    recomputed,reused:{jar_metadata:c["jar.metadata_reuse"]??0,jar_hash:c["jar.hash_reuse"]??0,sources_metadata:c["sources.metadata_reuse"]??0}};
}

// ---------------------------------------------------------------- small fixture
/** A two-artifact Maven repository with a binary JAR, matching sources and a javadoc JAR, plus a dependency without sources. */
export function buildFixture(javaHome:string,root:string,variant:"base"|"changed"="base"){
  const work=path.join(root,"..",path.basename(root)+"-build-"+variant);rmSync(work,{recursive:true,force:true});
  const src=path.join(work,"src"),classes=path.join(work,"classes"),depSrc=path.join(work,"dep-src"),depClasses=path.join(work,"dep-classes");
  for(const d of [path.join(src,"org/example"),classes,path.join(depSrc,"org/example/dep"),depClasses])mkdirSync(d,{recursive:true});
  writeFileSync(path.join(src,"org/example/Greeter.java"),`package org.example;
/** Greets people by name. */
public class Greeter {
    /** The default salutation. */
    public static final String SALUTATION = "Hello";
    /**
     * Returns a greeting for the given name.
     * @param name the person to greet
     */
    public String greet(String name) { return SALUTATION + ", " + name; }
    /** Returns the number of greetings issued. */
    public int count() { return 0; }
}
`);
  writeFileSync(path.join(src,"org/example/Shape.java"),`package org.example;
/** A two-dimensional shape. */
public interface Shape {
    /** Returns the area of this shape. */
    double area();
}
`);
  writeFileSync(path.join(depSrc,"org/example/dep/Helper.java"),`package org.example.dep;
public class Helper {
    public int one() { return 1; }
${variant==="changed"?"    public int two() { return 2; }\n":""}}
`);
  const run=(tool:string,a:string[])=>{const r=spawnSync(path.join(javaHome,"bin",tool),a,{encoding:"utf8"});if(r.status!==0)throw new Error(tool+": "+r.stderr);};
  run("javac",["--release","17","-d",classes,path.join(src,"org/example/Greeter.java"),path.join(src,"org/example/Shape.java")]);
  run("javac",["--release","17","-d",depClasses,path.join(depSrc,"org/example/dep/Helper.java")]);
  const fixtureDir=path.join(root,"org/example/boot-fixture/1.0"),depDir=path.join(root,"org/example/boot-dep/1.0");
  mkdirSync(fixtureDir,{recursive:true});mkdirSync(depDir,{recursive:true});
  const jar=path.join(fixtureDir,"boot-fixture-1.0.jar");
  // Fixed entry timestamps: identical fixture bytes on every build. The changed variant rewrites only the
  // dependency JAR, so exactly one input changes.
  if(variant==="base"){
    run("jar",["--create","--date=2026-01-01T00:00:00Z","--file",jar,"-C",classes,"."]);
    run("jar",["--create","--date=2026-01-01T00:00:00Z","--file",path.join(fixtureDir,"boot-fixture-1.0-sources.jar"),"-C",src,"."]);
    run("jar",["--create","--date=2026-01-01T00:00:00Z","--file",path.join(fixtureDir,"boot-fixture-1.0-javadoc.jar"),"-C",src,"."]);
    writeFileSync(jar+".sha1",createHash("sha1").update(readFileSync(jar)).digest("hex")+"\n");
  }
  run("jar",["--create","--date=2026-01-01T00:00:00Z","--file",path.join(depDir,"boot-dep-1.0.jar"),"-C",depClasses,"."]);
  rmSync(work,{recursive:true,force:true});
  return {root,jar,sources:path.join(fixtureDir,"boot-fixture-1.0-sources.jar"),dep:path.join(depDir,"boot-dep-1.0.jar")};
}
/** Independently checkable facts of the fixture in a --full export. */
function checkFixtureFacts(exportFile:string,fixture:{jar:string;sources:string;dep:string},expectedDepSymbols?:number){
  const e=JSON.parse(readFileSync(exportFile,"utf8"));const art=e.families["metadata.artifacts"]??{};const gens=e.families["repository.generations"]??{};
  const checks:Record<string,boolean>={};
  checks.binary_artifact_present=!!art[fixture.jar]&&art[fixture.jar].input.context.kind==="jar";
  checks.dependency_artifact_present=!!art[fixture.dep];
  checks.sources_artifact_present=!!art[fixture.sources]&&art[fixture.sources].input.context.kind==="sources";
  checks.javadoc_jar_excluded=!Object.keys(art).some(p=>p.endsWith("-javadoc.jar"));
  const docsKey=art[fixture.jar]?.docsKey;
  checks.binary_has_documentation=!!docsKey&&!!gens[docsKey];
  const docRows:any[]=gens[docsKey]?.rows??[];
  const greet=docRows.find(r=>/Greeter#greet\(/u.test(String(r[0])));
  checks.greet_documentation_text=!!greet&&String(greet[1]).includes("Returns a greeting for the given name.");
  checks.shape_area_documentation=docRows.some(r=>/Shape#area\(/u.test(String(r[0]))&&String(r[1]).includes("Returns the area"));
  checks.greeter_symbols=(art[fixture.jar]?.symbols??0)>=4;
  checks.dependency_symbols=expectedDepSymbols===undefined?(art[fixture.dep]?.symbols??0)>=2:art[fixture.dep]?.symbols===expectedDepSymbols;
  checks.complete=e.migration?.complete===true;
  return {ok:Object.values(checks).every(Boolean),checks,observed:{greeter_symbols:art[fixture.jar]?.symbols,dep_symbols:art[fixture.dep]?.symbols,doc_rows:docRows.length}};
}

async function controls(c:Common){
  const out:any={};const dir=path.join(c.output,"controls");mkdirSync(dir,{recursive:true});
  // C5 comparator sensitivity.
  const unit=spawnSync("python3",[path.join(HERE,"test_compare.py"),"-v"],{encoding:"utf8"});
  out.comparator_sensitivity={ok:unit.status===0,output:(unit.stderr+unit.stdout).trim().split("\n").slice(-20)};
  log("comparator sensitivity: "+(unit.status===0?"ok":"FAILED"));

  // C1 no change on the small fixture, with expected facts and observer noninterference (C6).
  const fixture=buildFixture(c.javaHome,path.join(dir,"fixture-base/repository"));
  const c1=await pair(c,{id:"controls/c1-no-change",mode:"control",repository:fixture.root,exportFull:true,noninterference:true,keepSnapshot:true});
  out.c1_no_change={equivalence:c1.equivalence,verdict:c1.comparison?.verdict,runtime:c1.comparison?.runtime?.status,
    cold_facts:checkFixtureFacts(path.join(c.output,"controls/c1-no-change/cold-export.json"),fixture),
    warm_facts:checkFixtureFacts(path.join(c.output,"controls/c1-no-change/warm-export.json"),fixture),
    noninterference:JSON.parse(readFileSync(path.join(c.output,"controls/c1-no-change/pair.json"),"utf8")).exports.cold.noninterference};

  // C2 one offline input change: warm from the unchanged snapshot over changed inputs equals a clean cold of the changed inputs.
  {
    const base=buildFixture(c.javaHome,path.join(dir,"fixture-c2/repository"));
    const id="controls/c2-offline-change",pd=path.join(c.output,id);
    const cold=await boot({...c,label:id+"/cold",mode:"control",state:path.join(pd,"cold/state"),dir:path.join(pd,"cold"),terminate:{kind:"kill"},repositoryOverride:base.root});
    copyTree(path.join(pd,"cold/state"),path.join(pd,"snapshot"));
    await new Promise(r=>setTimeout(r,1100));// a distinct mtime second for the rewritten JAR
    buildFixture(c.javaHome,base.root,"changed");
    copyTree(path.join(pd,"snapshot"),path.join(pd,"warm/state"));
    const warm=await boot({...c,label:id+"/warm",mode:"control",state:path.join(pd,"warm/state"),dir:path.join(pd,"warm"),terminate:{kind:"kill"},repositoryOverride:base.root});
    const clean=await boot({...c,label:id+"/clean-cold",mode:"control",state:path.join(pd,"clean/state"),dir:path.join(pd,"clean"),terminate:{kind:"kill"},repositoryOverride:base.root});
    const ew=exportState(c,path.join(pd,"warm/state"),path.join(pd,"warm-export.json"),{full:true});
    const ec=exportState(c,path.join(pd,"clean/state"),path.join(pd,"clean-export.json"),{full:true});
    const eo=exportState(c,path.join(pd,"snapshot"),path.join(pd,"original-export.json"),{full:true});
    const warmVsClean=compareExports(ew.file,ec.file,path.join(pd,"warm-vs-clean.json"),path.join(pd,"warm/milestones.jsonl"),path.join(pd,"clean/milestones.jsonl"));
    const warmVsOriginal=compareExports(ew.file,eo.file,path.join(pd,"warm-vs-original.json"));
    out.c2_offline_change={cold:cold.domain_ready,warm:warm.domain_ready,clean:clean.domain_ready,
      warm_vs_clean_families:warmVsClean.families,
      warm_vs_clean_cold:warmVsClean.verdict,warm_vs_clean_runtime:warmVsClean.runtime?.status,
      warm_vs_original_cold:warmVsOriginal.verdict,stale_accepted:warmVsOriginal.verdict==="EQUAL",
      changed_families:Object.entries(warmVsOriginal.families).filter(([,v]:any)=>v.status==="DIFFERENT").map(([k])=>k),
      warm_counters:pick(warm.summary.counters??{},["jar.parsed","jar.metadata_reuse","jar.hash_reuse","sources.metadata_reuse","sources.parsed"]),
      warm_facts:checkFixtureFacts(ew.file,base,(JSON.parse(readFileSync(eo.file,"utf8")).families["metadata.artifacts"][base.dep]?.symbols??-10)+1)};
    log("c2: warm vs clean "+warmVsClean.verdict+", warm vs original "+warmVsOriginal.verdict);
  }

  // C3 missing or corrupt persisted results, from the C1 snapshot.
  out.c3_missing_or_corrupt={};
  const snap=path.join(c.output,"controls/c1-no-change/snapshot-cold-ready");
  const variants:Record<string,(state:string)=>string>={
    "repository-sst-deleted":state=>{const db=findGeneration(state,"db");const sst=readdirSync(db).filter(f=>f.endsWith(".sst")).sort()[0];rmSync(path.join(db,sst));return "deleted "+sst;},
    "repository-sst-truncated":state=>{const db=findGeneration(state,"db");const sst=readdirSync(db).filter(f=>f.endsWith(".sst")).sort()[0];const p=path.join(db,sst);
      const bytes=readFileSync(p);writeFileSync(p,bytes.subarray(0,Math.floor(bytes.length/2)));return "truncated "+sst+" to "+Math.floor(bytes.length/2)+" bytes";},
    "validated-marker-removed":state=>{const g=findGeneration(state,"");rmSync(path.join(g,"VALIDATED"));return "removed VALIDATED marker";},
    "metadata-store-deleted":state=>{const s=findGeneration(state,"store");rmSync(s,{recursive:true});return "deleted metadata store database";},
  };
  for(const [name,mutate] of Object.entries(variants)){
    const id="controls/c3-"+name,pd=path.join(c.output,id);
    copyTree(snap,path.join(pd,"state"));const what=mutate(path.join(pd,"state"));
    const r=await boot({...c,label:id,mode:"control",state:path.join(pd,"state"),dir:path.join(pd,"boot"),terminate:{kind:"kill"},repositoryOverride:fixture.root,timeoutMs:5*60000});
    let verdict:any=null,facts:any=null;
    try{const e=exportState(c,path.join(pd,"state"),path.join(pd,"export.json"),{full:true});
      verdict=compareExports(path.join(c.output,"controls/c1-no-change/cold-export.json"),e.file,path.join(pd,"vs-cold.json")).verdict;facts=checkFixtureFacts(e.file,fixture);}
    catch(error){verdict="export failed: "+String(error).slice(0,300);}
    const s=r.summary;
    out.c3_missing_or_corrupt[name]={mutation:what,domain_ready:r.domain_ready,failure:r.failure,exit:r.exit,
      product_ready_ms:s.t_ms.PRODUCT_READY??null,domain_ready_ms:s.t_ms.DOMAIN_READY??null,
      persisted_index_complete_at_open:s.session_capable?.persisted_index_complete??null,
      product_ready_before_reconciliation:s.t_ms.PRODUCT_READY!==undefined&&s.t_ms.REPOSITORY_RECONCILIATION_FINISHED!==undefined&&s.t_ms.PRODUCT_READY<s.t_ms.REPOSITORY_RECONCILIATION_FINISHED,
      counters:pick(s.counters??{},["jar.parsed","jar.metadata_reuse","jar.hash_reuse","sources.parsed","sources.metadata_reuse","docs.verify_full_passes"]),
      final_vs_cold:verdict,final_facts:facts,daemon_log_tail:tail(path.join(pd,"boot/daemon.log"),15)};
    log("c3 "+name+": domain_ready="+r.domain_ready+" final_vs_cold="+verdict);
  }
  rmSync(snap,{recursive:true,force:true});

  // C4 incomplete cold publication on the main corpus: kill mid-scan, then restart.
  {
    const id="controls/c4-incomplete-publication",pd=path.join(c.output,id);
    const killed=await boot({...c,label:id+"/killed-cold",mode:"control",state:path.join(pd,"killed/state"),dir:path.join(pd,"killed"),
      terminate:{kind:"kill-after",event:"DISCOVERY_COMPLETE",delayMs:2000}});
    const partial=exportState(c,path.join(pd,"killed/state"),path.join(pd,"killed-export.json"));
    const partialExport=JSON.parse(readFileSync(partial.file,"utf8"));
    copyTree(path.join(pd,"killed/state"),path.join(pd,"restart/state"));
    const restart=await boot({...c,label:id+"/restart",mode:"control",state:path.join(pd,"restart/state"),dir:path.join(pd,"restart"),terminate:{kind:"kill"}});
    const reference=await boot({...c,label:id+"/reference-cold",mode:"control",state:path.join(pd,"reference/state"),dir:path.join(pd,"reference"),terminate:{kind:"kill"}});
    const er=exportState(c,path.join(pd,"restart/state"),path.join(pd,"restart-export.json"));
    const ef=exportState(c,path.join(pd,"reference/state"),path.join(pd,"reference-export.json"));
    const cmp=compareExports(ef.file,er.file,path.join(pd,"reference-vs-restart.json"),path.join(pd,"reference/milestones.jsonl"),path.join(pd,"restart/milestones.jsonl"));
    const s=restart.summary;
    out.c4_incomplete_publication={killed_at_ms:killed.killed_mono_ns?ms(BigInt(killed.killed_mono_ns)-BigInt(killed.identity.spawn_mono_ns)):null,
      killed_reached:Object.keys(killed.summary.t_ms),
      partial_store:{complete:partialExport.migration.complete,artifacts:Object.keys(partialExport.families["metadata.artifacts"]??{}).length},
      restart_persisted_index_complete:s.session_capable?.persisted_index_complete,
      restart_product_ready_ms:s.t_ms.PRODUCT_READY,restart_domain_ready_ms:s.t_ms.DOMAIN_READY,
      restart_ready_gated_on_scan:s.t_ms.PRODUCT_READY!==undefined&&s.t_ms.REPOSITORY_RECONCILIATION_FINISHED!==undefined&&s.t_ms.PRODUCT_READY>=s.t_ms.REPOSITORY_RECONCILIATION_FINISHED,
      restart_counters:pick(s.counters??{},["jar.parsed","jar.metadata_reuse","jar.hash_reuse","sources.parsed"]),
      reference_vs_restart:cmp.verdict,runtime:cmp.runtime?.status};
    log("c4: partial complete="+partialExport.migration.complete+" restart gated="+out.c4_incomplete_publication.restart_ready_gated_on_scan+" reference vs restart "+cmp.verdict);
    for(const d of ["killed","restart","reference"])rmSync(path.join(pd,d,"state"),{recursive:true,force:true});
  }
  writeFileSync(path.join(c.output,"controls.json"),JSON.stringify(out,null,1));
  return out;
}
function findGeneration(state:string,sub:string){
  const g=path.join(state,"index-v2/generations");const name=readdirSync(g).sort()[0];return path.join(g,name,sub);
}
function tail(file:string,n:number){try{return readFileSync(file,"utf8").trim().split("\n").slice(-n);}catch{return [];}}

/** Orderly shutdown accounting: what a graceful close writes after DOMAIN_READY, and a labelled warm boot from it. */
async function shutdownAccounting(c:Common){
  const id="shutdown",pd=path.join(c.output,id);
  const cold=await boot({...c,label:id+"/cold-graceful",mode:"control",state:path.join(pd,"cold/state"),dir:path.join(pd,"cold"),terminate:{kind:"graceful"}});
  copyTree(path.join(pd,"cold/state"),path.join(pd,"snapshot-after-shutdown"));
  copyTree(path.join(pd,"snapshot-after-shutdown"),path.join(pd,"warm/state"));
  const warm=await boot({...c,label:id+"/warm-from-graceful",mode:"control",state:path.join(pd,"warm/state"),dir:path.join(pd,"warm"),terminate:{kind:"kill"}});
  const eg=exportState(c,path.join(pd,"snapshot-after-shutdown"),path.join(pd,"graceful-export.json"));
  const ew=exportState(c,path.join(pd,"warm/state"),path.join(pd,"warm-export.json"));
  const cmp=compareExports(eg.file,ew.file,path.join(pd,"graceful-vs-warm.json"),path.join(pd,"cold/milestones.jsonl"),path.join(pd,"warm/milestones.jsonl"));
  const result={cold:cold.summary,warm:warm.summary,shutdown_writes:cold.exit_vs_ready,graceful:cold.shutdown_graceful,
    shutdown_ms:cold.summary.t_ms.EXIT!==undefined&&cold.shutdown_requested_mono_ns?cold.summary.t_ms.EXIT-ms(BigInt(cold.shutdown_requested_mono_ns)-BigInt(cold.identity.spawn_mono_ns)):null,
    write_bytes_during_shutdown:"not observable after exit; see exit_vs_ready listing diff",
    graceful_vs_warm:cmp.verdict,label:"REQUIRES_SHUTDOWN_PUBLICATION (secondary; the primary pairs use SIGKILL-at-ready provenance)"};
  writeFileSync(path.join(pd,"shutdown.json"),JSON.stringify(result,null,1));
  for(const d of ["cold/state","warm/state","snapshot-after-shutdown"])rmSync(path.join(pd,d),{recursive:true,force:true});
  return result;
}

/**
 * Supplementary, labelled causal experiments. They change one product default each and never replace the
 * default-configuration results: a larger and a smaller admission budget (is admission waiting on the cold critical
 * path?), no initial scan delay, and READY held until reconciliation (the existing awaitRepositoryScan option).
 */
async function experiments(c:Common){
  const dir=path.join(c.output,"experiments");const out:any={label:"SUPPLEMENTARY: changed configuration, not the default product result"};
  const run=async(id:string,state:string,props?:Record<string,string>)=>(await boot({...c,label:"experiments/"+id,mode:"counters",state,dir:path.join(dir,id),terminate:{kind:"kill"},props})).summary;
  out.cold_default=await run("cold-default",path.join(dir,"cold-default/state"));
  copyTree(path.join(dir,"cold-default/state"),path.join(dir,"snapshot"));
  for(const [id,props] of [["warm-default",{}],["warm-initial-delay-0",{"jvmd.index.scan.initial_delay_seconds":"0"}],["warm-await-repository-scan",{"jvmd.ready.awaitRepositoryScan":"true"}]] as [string,Record<string,string>][]){
    copyTree(path.join(dir,"snapshot"),path.join(dir,id,"state"));out[id]={props,summary:await run(id,path.join(dir,id,"state"),props)};
    rmSync(path.join(dir,id,"state"),{recursive:true,force:true});
  }
  for(const budget of ["512","32"]){
    const id="cold-generation-budget-"+budget+"mb";
    out[id]={props:{"jvmd.index.generation_budget_mb":budget},summary:await run(id,path.join(dir,id,"state"),{"jvmd.index.generation_budget_mb":budget})};
    rmSync(path.join(dir,id,"state"),{recursive:true,force:true});
  }
  out.cold_default_repeat=await run("cold-default-repeat",path.join(dir,"cold-default-repeat/state"));
  for(const d of ["cold-default/state","cold-default-repeat/state","snapshot"])rmSync(path.join(dir,d),{recursive:true,force:true});
  writeFileSync(path.join(c.output,"experiments.json"),JSON.stringify(out,null,1));
  return out;
}

/** Deterministic artifact subsets of the corpus (every k-th version directory), copied with preserved mtimes. */
function subsetRepository(repository:string,target:string,fraction:number){
  const dirs:string[]=[];
  const walk=(d:string)=>{const names=readdirSync(d);if(names.some(n=>n.endsWith(".jar")))dirs.push(d);for(const n of names){const p=path.join(d,n);if(lstatSync(p).isDirectory())walk(p);}};
  walk(repository);dirs.sort();
  const keep=dirs.filter((_,i)=>fraction>=100||Math.floor((i+1)*fraction/100)>Math.floor(i*fraction/100));
  rmSync(target,{recursive:true,force:true});
  for(const d of keep){const rel=path.relative(repository,d);mkdirSync(path.dirname(path.join(target,rel)),{recursive:true});
    const r=spawnSync("cp",["-a",d,path.join(target,rel)]);if(r.status!==0)throw new Error("subset copy failed");}
  const jars=keep.reduce((s,d)=>s+readdirSync(d).filter(n=>n.endsWith(".jar")&&!n.endsWith("-javadoc.jar")).length,0);
  const bytes=keep.reduce((s,d)=>s+readdirSync(d).filter(n=>n.endsWith(".jar")).reduce((t,n)=>t+statSync(path.join(d,n)).size,0),0);
  return {directories:keep.length,of:dirs.length,jars,jar_bytes:bytes};
}

function inputManifest(repository:string){
  const files:any[]=[];const h=createHash("sha256");
  const walk=(d:string)=>{for(const n of readdirSync(d).sort()){const p=path.join(d,n);const s=lstatSync(p);if(s.isDirectory())walk(p);
    else if(n.endsWith(".jar")){files.push({path:path.relative(repository,p),size:s.size,kind:n.endsWith("-sources.jar")?"sources":n.endsWith("-javadoc.jar")?"javadoc (excluded)":"binary"});h.update(path.relative(repository,p)+"\0"+s.size+"\0");}}};
  walk(repository);
  const by=(k:string)=>files.filter(f=>f.kind===k);
  return {repository,jar_files:files.length,binary:by("binary").length,sources:by("sources").length,javadoc_excluded:by("javadoc (excluded)").length,
    bytes:files.reduce((s,f)=>s+f.size,0),path_size_digest:h.digest("hex"),files};
}

async function main(){
  const [command,...rest]=process.argv.slice(2);const a=args(rest);
  const c:Common={image:path.resolve(a.image),javaHome:path.resolve(a["java-home"]),repository:path.resolve(a.repository),output:path.resolve(a.output),
    asprof:a.asprof?path.resolve(a.asprof):undefined,timeoutMs:Number(a["timeout-min"]??"30")*60000};
  mkdirSync(c.output,{recursive:true});
  writeFileSync(path.join(c.output,"input-manifest.json"),JSON.stringify(inputManifest(c.repository),null,1));
  const results:any={command,options:a,started:new Date().toISOString(),host:{cpus:os.cpus().length,cpu_model:os.cpus()[0]?.model,memory_bytes:os.totalmem(),kernel:os.release()}};
  if(command==="pairs"){
    results.pairs=[];
    for(let i=1;i<=Number(a.pairs??"5");i++){
      const p=await pair(c,{id:`pair-${i}`,mode:(a.mode??"control") as Mode,noninterference:i===1});
      results.pairs.push({id:`pair-${i}`,equivalence:p.equivalence,verdict:p.comparison?.verdict,cold:p.cold.summary,warm:p.warm?.summary});
    }
  }else if(command==="profile"){
    results.pairs=[];
    for(let i=1;i<=Number(a.repeat??"1");i++){
      const p=await pair(c,{id:`${a.mode}-${i}`,mode:a.mode as Mode});
      results.pairs.push({id:`${a.mode}-${i}`,equivalence:p.equivalence,verdict:p.comparison?.verdict,cold:p.cold.summary,warm:p.warm?.summary});
    }
  }else if(command==="experiments")results.experiments=await experiments(c);
  else if(command==="controls")results.controls=await controls(c);
  else if(command==="shutdown")results.shutdown=await shutdownAccounting(c);
  else if(command==="scaling"){
    results.scaling=[];
    for(const f of (a.fractions??"25,50,100").split(",").map(Number)){
      const target=path.join(c.output,`repository-${f}`);const subset=subsetRepository(c.repository,target,f);
      writeFileSync(path.join(c.output,`input-manifest-${f}.json`),JSON.stringify(inputManifest(target),null,1));
      const p=await pair(c,{id:`scale-${f}`,mode:(a.mode??"counters") as Mode,repository:target});
      results.scaling.push({fraction:f,subset,equivalence:p.equivalence,verdict:p.comparison?.verdict,cold:p.cold.summary,warm:p.warm?.summary});
      rmSync(target,{recursive:true,force:true});
    }
  }else throw new Error("unknown command "+command);
  results.finished=new Date().toISOString();
  writeFileSync(path.join(c.output,"results.json"),JSON.stringify(results,null,1));
  log("wrote "+path.join(c.output,"results.json"));
}

if(import.meta.url===`file://${process.argv[1]}`)await main();
