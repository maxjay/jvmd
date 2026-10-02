import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {appendFileSync,copyFileSync,existsSync,mkdirSync,readFileSync,readdirSync,statSync,writeFileSync} from "node:fs";
import {gzipSync} from "node:zlib";
import os from "node:os";
import path from "node:path";
import {pathToFileURL} from "node:url";
import {ScenarioContext} from "../harness/ScenarioContext.ts";
import {launch,type Launch} from "../harness/launch.ts";
import {settingsXml,type Pom} from "../harness/maven.ts";
import {type Fixture} from "../harness/fixture.ts";
import {position,range,exactLocations,hoverOracle,completionOracle,completionItems} from "../harness/oracles.ts";
import {observeDiagnostics,errorAt,type DiagnosticCheck} from "../harness/diagnostics.ts";
import {ProfiledDaemon,Sampler,procMemory,smapsCategories,jcmd,asprof,compactSnapshot} from "./probes.ts";

/**
 * JVMD memory investigation driver. One invocation = one profiling mode over the full lifecycle of the
 * pinned apache/maven fixture (docs/jvmd-memory-allocation-profile.md), with explicit checkpoints M0-M26.
 *
 *   node benchmarks/memory/profile.ts --mode exact --output DIR --project APACHE_MAVEN --project-repository REPO
 *
 * Modes (never combined, each perturbs differently):
 *   control    allocation agent only (as the benchmark), checkpoint snapshots; the latency reference
 *   exact      + 50 ms /proc and heap sampler, RequestScope tracing, JFR (GC, dev.jvmd.Stage), GC log
 *   alloc      + async-profiler allocation sampling, one JFR per phase (split at every checkpoint)
 *   live       + async-profiler live-object sampling per phase, GC forced before each phase ends
 *   retention  + forced-GC class histogram at every checkpoint, HPROF dumps at H1-H9
 *   nmt        + -XX:NativeMemoryTracking=detail, summary/detail diffs against a post-bootstrap baseline
 *   native     + async-profiler nativemem (malloc/free) per phase
 *   rss        + 50 ms smaps_rollup sampler, full smaps at every checkpoint
 * --seed-only runs M0-M4 against --repository (scale and per-JAR experiments) then a forced-GC live heap.
 */

const a:Record<string,string>={};
for(let i=2;i<process.argv.length;i++){const k=process.argv[i].replace(/^--/u,"");if(k==="seed-only"||k==="no-restart"||k==="seed-restart"||k==="skip-references"){a[k]="true";continue;}a[k]=process.argv[++i];}
const MODE=a.mode??"control",OUT=path.resolve(a.output??assert.fail("--output")),HEAP=Number(a.heap??1024),N=Number(a.iterations??100);
const JAVA_HOME=path.resolve(a["java-home"]??process.env.JAVA_HOME??""),IMAGE=path.resolve(a.image??"jvmd-dist/target/image");
const AP=path.resolve(a["async-profiler"]??process.env.ASYNC_PROFILER_HOME??"");
const SEED_ONLY=a["seed-only"]==="true";
const PROJECT=SEED_ONLY?undefined:path.resolve(a.project??assert.fail("--project"));
const REPOSITORY=path.resolve(a["project-repository"]??a.repository??assert.fail("--project-repository"));
const NATIVE_BUDGET_MB=a["native-budget-mb"];
const ALLOC_INTERVAL=a["alloc-interval"]??"262144",NATIVE_INTERVAL=a["native-interval"]??"65536";
assert(["control","exact","alloc","live","retention","nmt","native","rss"].includes(MODE),"unknown mode "+MODE);
assert(!existsSync(OUT),"--output must be a new directory");
mkdirSync(OUT,{recursive:true});for(const d of ["phases","histograms","nmt","smaps","dumps","status"])mkdirSync(path.join(OUT,d),{recursive:true});
const STATE=path.join(OUT,"state");
const log_=(...m:any[])=>{const line=new Date().toISOString()+" "+m.join(" ");console.log(line);appendFileSync(path.join(OUT,"driver.log"),line+"\n");};
const HEAP_DUMPS:Record<string,string>={M4:"H1-machine-ready",M9:"H2-workspace-admitted","M14x":"H3-after-queries","M17r":"H4-after-mutations","M19s":"H5-session-closed",
  M20:"H6-reconnected","M20bx":"H7-before-shutdown","M24i":"H8-restart-ready","M26b":"H9-restart-workspace"};

// ---------------------------------------------------------------- environment
function sh(cmd:string,args:string[],cwd?:string){const r=spawnSync(cmd,args,{encoding:"utf8",cwd});return (r.stdout??"").trim();}
function dirStats(dir:string){let bytes=0,jars=0,sources=0;const walk=(d:string)=>{for(const e of readdirSync(d,{withFileTypes:true})){const p=path.join(d,e.name);
  if(e.isDirectory())walk(p);else if(e.isFile()){bytes+=statSync(p).size;if(e.name.endsWith(".jar")&&!e.name.endsWith("-javadoc.jar")){jars++;if(e.name.endsWith("-sources.jar"))sources++;}}}};walk(dir);return {bytes,jars,sources};}
const env={mode:MODE,date:new Date().toISOString(),jvmdCommit:sh("git",["rev-parse","HEAD"]),jvmdDirty:sh("git",["status","--porcelain"]).split("\n").filter(Boolean),
  jdk:spawnSync(path.join(JAVA_HOME,"bin/java"),["-version"],{encoding:"utf8"}).stderr.trim(),javaHome:JAVA_HOME,
  os:readFileSync("/etc/os-release","utf8").match(/^PRETTY_NAME="?([^"\n]*)/mu)?.[1],kernel:os.release(),arch:os.arch(),cpus:os.cpus().length,cpuModel:os.cpus()[0]?.model,
  memoryBytes:os.totalmem(),filesystem:sh("findmnt",["-n","-o","FSTYPE","-T",OUT]),xmxMb:HEAP,iterations:N,
  thp:existsSync("/sys/kernel/mm/transparent_hugepage/enabled")?readFileSync("/sys/kernel/mm/transparent_hugepage/enabled","utf8").trim():null,
  repository:{path:REPOSITORY,...dirStats(REPOSITORY)},fixtureCommit:PROJECT?sh("git",["-C",PROJECT,"rev-parse","HEAD"]):null,
  node:process.version,asyncProfiler:AP&&existsSync(AP)?sh(path.join(AP,"bin/asprof"),["--version"]):null,allocInterval:ALLOC_INTERVAL,reuseState:a["reuse-state"]??null,skipReferences:a["skip-references"]==="true",nativeBudgetMb:NATIVE_BUDGET_MB?Number(NATIVE_BUDGET_MB):"default (64)",nativeInterval:NATIVE_INTERVAL};
writeFileSync(path.join(OUT,"environment.json"),JSON.stringify(env,null,1));

// ---------------------------------------------------------------- mode-specific JVM flags
function jvmArgs(incarnation:number){
  const args=["-Djvmd.profile.bootstrap_status=true"];
  // Supplementary configuration only: an existing runtime property; the primary baseline never sets it.
  if(NATIVE_BUDGET_MB)args.push("-Djvmd.index.native_budget_mb="+NATIVE_BUDGET_MB);
  const tag=`i${incarnation}`;
  // --jfr-events adds JFR event settings to the exact mode's recording, e.g. jdk.ObjectAllocationOutsideTLAB#enabled=true.
  const jfrExtra=a["jfr-events"]?","+a["jfr-events"]:"";
  if(MODE==="exact")args.push("-Djvmd.trace=true",`-XX:StartFlightRecording=filename=${OUT}/exact-${tag}.jfr,settings=default,dumponexit=true,name=memory${jfrExtra}`,
    `-Xlog:gc*=info,gc+heap=debug,gc+humongous=debug,gc+age=trace:file=${OUT}/gc-${tag}.log:uptimemillis,tid,tags`);
  if(MODE==="nmt")args.push("-XX:NativeMemoryTracking=detail","-XX:+UnlockDiagnosticVMOptions","-XX:+PrintNMTStatistics");
  if(MODE==="alloc")args.push(`-agentpath:${AP}/lib/libasyncProfiler.so=start,event=alloc,alloc=${ALLOC_INTERVAL},jfr,file=${OUT}/phases/${tag}-000-start.jfr`);
  if(MODE==="live")args.push(`-agentpath:${AP}/lib/libasyncProfiler.so=start,event=alloc,live,alloc=${ALLOC_INTERVAL},jfr,file=${OUT}/phases/${tag}-000-start.jfr`);
  if(MODE==="native")args.push(`-agentpath:${AP}/lib/libasyncProfiler.so=start,event=nativemem,nativemem=${NATIVE_INTERVAL},jfr,file=${OUT}/phases/${tag}-000-start.jfr`);
  if(MODE==="retention")args.push("-XX:+HeapDumpOnOutOfMemoryError",`-XX:HeapDumpPath=${OUT}/dumps/oom-${tag}.hprof`);
  return args;
}
function profilerStart(){
  if(MODE==="alloc")return ["start","-e","alloc","--alloc",ALLOC_INTERVAL,"-o","jfr"];
  if(MODE==="live")return ["start","-e","alloc","--live","--alloc",ALLOC_INTERVAL,"-o","jfr"];
  if(MODE==="native")return ["start","-e","nativemem","--nativemem",NATIVE_INTERVAL,"-o","jfr"];
  return undefined;
}

/** On-disk bytes of the daemon state, per RocksDB database and per other state directory. */
function persistedSizes(){
  const out:Record<string,number>={};
  const du=(p:string)=>{const r=spawnSync("du",["-sb",p],{encoding:"utf8"});return Number(r.stdout.split("\t")[0])||0;};
  const store=path.join(STATE,"jvmd","store");if(!existsSync(store))return out;
  out.total=du(store);
  const gens=path.join(store,"index-v2","generations");
  if(existsSync(gens))for(const g of readdirSync(gens))for(const db of readdirSync(path.join(gens,g)))out["index/"+db]=du(path.join(gens,g,db));
  for(const d of readdirSync(store))if(d!=="index-v2")out[d]=du(path.join(store,d));
  const sst=spawnSync("bash",["-c",`find ${JSON.stringify(store)} -name '*.sst' -printf '%s\\n' | awk '{s+=$1;n++} END {print n+0, s+0}'`],{encoding:"utf8"}).stdout.trim().split(" ");
  out.sst_files=Number(sst[0]);out.sst_bytes=Number(sst[1]);
  return out;
}

// ---------------------------------------------------------------- checkpoints
let daemon:ProfiledDaemon,incarnation=0,sequence=0,sampler:Sampler|undefined,rssSampler:Sampler|undefined;
let baseline:any;let nmtBaselined=false;let phaseFile="";
const marks:any[]=[];
function summarizeStatus(s:any){
  if(!s)return null;const i=s.index??{},st=i.storage??{},r=st.repository??{};
  return {phase:i.phase,bootstrap:i.bootstrap??false,total:i.total,scanned:i.scanned,indexed:i.indexed,reused:i.reused,hashes:i.hashes,faults:i.faults,
    artifacts:i.artifacts,symbols:i.symbols,edges:i.edges,simple_names:i.simple_names,native_memory:st.native_memory,
    admission:{budget_bytes:st.budget_bytes,peak_estimated_bytes_in_flight:st.peak_estimated_bytes_in_flight,estimated_bytes_in_flight:st.estimated_bytes_in_flight},
    repository:{published:r.published,reused:r.reused,storage_bytes:r.storage_bytes,sort_peak_bytes:r.sort_peak_bytes,sort_spill_bytes:r.sort_spill_bytes,
      estimate_table_readers_mem:r.estimate_table_readers_mem,cur_size_all_mem_tables:r.cur_size_all_mem_tables,gram_occurrences:r.gram_occurrences},
    store:i.store,sessions:(s.sessions??[]).length,timings:i.timings};
}
async function mark(id:string,label:string,o:{histogram?:boolean;smaps?:boolean}={}){
  const t=Date.now(),mono=performance.now();
  const snap=daemon.alive()?await daemon.allocation.snapshot(false):null;
  const proc=procMemory(daemon.pid);
  let status:any=null;const statusStarted=performance.now();
  if(daemon.alive()&&daemon.control&&!stalled)try{status=await daemon.status();}catch(error){status={error:String(error)};}
  const statusMs=performance.now()-statusStarted;
  const afterStatus=daemon.alive()?await daemon.allocation.snapshot(false):null;
  const row:any={seq:sequence++,id,label,incarnation,t,mono,sinceSpawnMs:mono-daemon.spawnedMs,snapshot:snap,proc,status:summarizeStatus(status),statusMs,
    observerBytes:snap&&afterStatus?afterStatus.allocated-snap.allocated:null,hooks:{}};
  if(status)appendFileSync(path.join(OUT,"status","status.jsonl"),JSON.stringify({id,incarnation,t,status})+"\n");
  const prefix=`i${incarnation}-${String(row.seq).padStart(3,"0")}-${id}`;
  if(["M3","M4","M24","S1","M20bx","M26x"].includes(id))row.persisted=persistedSizes();
  if(daemon.alive()){
    // Every hook runs after the phase-closing snapshot and before the next phase's baseline.
    if(["alloc","live","native"].includes(MODE)){
      if(MODE==="live"){const g=jcmd(JAVA_HOME,daemon.pid,["GC.run"]);row.hooks.gcBeforeLiveStop={ok:g.ok,ms:g.ms};}
      // In JFR output the file is fixed when a recording starts: the phase ending here is in the file its start named.
      const stop=asprof(AP,daemon.pid,["stop"]);
      const start=asprof(AP,daemon.pid,[...profilerStart()!,"-f",path.join(OUT,"phases",`after-${prefix}.jfr`)]);
      row.hooks.profiler={stop:stop.ok||stop.stderr,start:start.ok||start.stderr,ms:stop.ms+start.ms,phaseFile:phaseFile,nextFile:`after-${prefix}.jfr`};
      phaseFile=`after-${prefix}.jfr`;
    }
    if(MODE==="nmt"){
      if(!nmtBaselined&&id==="M1"){const b=jcmd(JAVA_HOME,daemon.pid,["VM.native_memory","baseline"]);nmtBaselined=b.ok;row.hooks.nmtBaseline=b.ok;}
      const summary=jcmd(JAVA_HOME,daemon.pid,["VM.native_memory","summary","scale=KB"]);writeFileSync(path.join(OUT,"nmt",prefix+"-summary.txt"),summary.stdout);
      if(nmtBaselined){
        const diff=jcmd(JAVA_HOME,daemon.pid,["VM.native_memory","summary.diff","scale=KB"]);writeFileSync(path.join(OUT,"nmt",prefix+"-summary.diff.txt"),diff.stdout);
        const detail=jcmd(JAVA_HOME,daemon.pid,["VM.native_memory","detail.diff","scale=KB"]);writeFileSync(path.join(OUT,"nmt",prefix+"-detail.diff.txt.gz"),gzipSync(detail.stdout));
      }
      // glibc malloc_info (read-only): bytes glibc holds from the OS vs bytes free inside its arenas.
      const heapInfo=jcmd(JAVA_HOME,daemon.pid,["System.native_heap_info"]);writeFileSync(path.join(OUT,"nmt",prefix+"-malloc_info.xml"),heapInfo.stdout);
      row.hooks.nmt={ms:summary.ms+heapInfo.ms};
    }
    if(MODE==="rss"||o.smaps){
      const raw=path.join(OUT,"smaps",prefix+".smaps");const cats=smapsCategories(daemon.pid,raw);
      if(existsSync(raw)){writeFileSync(raw+".gz",gzipSync(readFileSync(raw)));spawnSync("rm",[raw]);}
      row.smaps=cats;
    }
    if(MODE==="retention"||o.histogram){
      // GC.class_histogram runs a full GC first: every retention figure below is post-GC by construction.
      const before=await daemon.allocation.snapshot(false);
      const h=jcmd(JAVA_HOME,daemon.pid,["GC.class_histogram"]);writeFileSync(path.join(OUT,"histograms",prefix+".txt"),h.stdout);
      const after=await daemon.allocation.snapshot(false);
      row.hooks.histogram={ms:h.ms,forcedFullGc:true,heapUsedBefore:before?.heap.used,heapUsedAfterGc:after?.heap.used};
      row.liveHeapAfterFullGc=after?.heap.used;
      if(MODE==="retention"&&HEAP_DUMPS[id]){
        const file=path.join(OUT,"dumps",`${HEAP_DUMPS[id]}.hprof`);
        const d=jcmd(JAVA_HOME,daemon.pid,["GC.heap_dump",file]);row.hooks.heapDump={file:path.basename(file),ok:d.ok,ms:d.ms,bytes:existsSync(file)?statSync(file).size:0};
      }
    }
  }
  const next=daemon.alive()?await daemon.allocation.snapshot(true):null; // resets pool peaks: the next phase's peaks start here
  row.hookBytes=next&&afterStatus?next.allocated-afterStatus.allocated:null;
  const previous=baseline;
  if(snap&&previous&&previous.incarnation===incarnation){
    const secs=(mono-previous.mono)/1000;
    row.phase={from:previous.id,allocatedBytes:snap.allocated-previous.snapshot.allocated,seconds:secs,
      bytesPerSecond:(snap.allocated-previous.snapshot.allocated)/Math.max(secs,1e-6),
      peakHeapUsed:Object.values<any>(snap.pools).filter((p:any)=>p.heap).reduce((s:number,p:any)=>s+p.peak.used,0),
      peakHeapCommitted:Object.values<any>(snap.pools).filter((p:any)=>p.heap).reduce((s:number,p:any)=>s+p.peak.committed,0),
      gcCountDelta:Object.entries<any>(snap.gc).reduce((s,[k,v])=>s+v.count-(previous.snapshot.gc[k]?.count??0),0),
      gcMsDelta:Object.entries<any>(snap.gc).reduce((s,[k,v])=>s+v.ms-(previous.snapshot.gc[k]?.ms??0),0)};
  }
  baseline={id,incarnation,mono:performance.now(),snapshot:next};
  marks.push(row);appendFileSync(path.join(OUT,"marks.jsonl"),JSON.stringify(row)+"\n");
  log_(`${id.padEnd(6)} ${label.padEnd(44)} heap=${mb(snap?.heap.used)} rss=${mb(proc.VmRSS)} alloc+=${mb(row.phase?.allocatedBytes)} `+
    (row.status?.native_memory?`rocks=${mb(row.status.native_memory.cache_usage_bytes)} `:"")+(row.liveHeapAfterFullGc?`live=${mb(row.liveHeapAfterFullGc)}`:""));
  return row;
}
const mb=(b?:number|null)=>b==null?"-":(b/1048576).toFixed(1)+"M";

/** Settled = whole-JVM allocation below 1 MiB/s for two consecutive 1 s windows (bounded at 90 s). Measured, not slept. */
async function settle(label:string,maxMs=90000){
  const started=performance.now();let quiet=0,last=await daemon.allocation.read();
  while(performance.now()-started<maxMs){
    await new Promise(r=>setTimeout(r,1000));const now=await daemon.allocation.read();
    if(now!==null&&last!==null&&now-last<1048576)quiet++;else quiet=0;last=now;if(quiet>=2)break;
  }
  const ms=performance.now()-started;appendFileSync(path.join(OUT,"settles.jsonl"),JSON.stringify({label,ms,settled:quiet>=2})+"\n");return ms;
}

// ---------------------------------------------------------------- daemon incarnations
async function startDaemon(repository:string){
  incarnation++;stalled=null;phaseFile=`i${incarnation}-000-start.jfr`;
  const seedFile=path.join(OUT,`seed-progress-i${incarnation}.jsonl`);
  daemon=await ProfiledDaemon.spawn({javaHome:JAVA_HOME,image:IMAGE,state:path.join(STATE,"jvmd"),repository,heapMb:HEAP,jvmArgs:jvmArgs(incarnation),
    env:{JAVA_TOOL_OPTIONS:"",JDK_JAVA_OPTIONS:""}});
  baseline=undefined;
  await mark(incarnation===1?"M0":"M23",incarnation===1?"process spawned (probe connected)":"persisted daemon restart begins");
  if(MODE==="exact"||MODE==="rss"){
    sampler=new Sampler(path.join(OUT,`samples-i${incarnation}.jsonl`),daemon.pid,50,MODE==="exact"?daemon.allocation:undefined);sampler.start();
  }
  await daemon.connectControl();
  // M1: control socket answering, first index status observed, before the index holds any artifact.
  let first:any;for(let i=0;i<200;i++){try{first=await daemon.status();break;}catch{await new Promise(r=>setTimeout(r,50));}}
  const m1=await mark(incarnation===1?"M1":"M23b",incarnation===1?"JVM initialized, index not yet populated":"restart: control socket up");
  appendFileSync(seedFile,JSON.stringify({t:m1.t,index:m1.status})+"\n");
  // M2: periodic seed samples at artifact-publication thresholds (scanned/total), from the profiling-only bootstrap status.
  const thresholds=[0,0.10,0.25,0.50,0.75,1.0];let next=0,readyDone=false;
  const readyPromise=daemon.ready().finally(()=>{readyDone=true;});readyPromise.catch(()=>undefined);
  while(!readyDone){
    await Promise.race([readyPromise.catch(()=>undefined),new Promise(r=>setTimeout(r,MODE==="control"?2000:500))]);
    if(readyDone)break;
    let s:any;try{s=summarizeStatus(await daemon.status());}catch{continue;}
    appendFileSync(seedFile,JSON.stringify({t:Date.now(),mono:performance.now(),index:s,jvm:MODE==="control"?undefined:compactSnapshot(await daemon.allocation.snapshot(false))})+"\n");
    if(s?.bootstrap&&s.total>0){
      while(next<thresholds.length&&s.scanned>=thresholds[next]*s.total){
        const pct=Math.round(thresholds[next]*100);await mark(`${incarnation===1?"M2":"M23"}-${pct}`,`index seed ${pct}% scanned (${s.scanned}/${s.total})`);next++;
      }
    }
  }
  try{await readyPromise;}
  catch(error){
    // A daemon that cannot reach READY (e.g. the persisted index fails to reopen) is evidence, not a driver failure.
    const log=readFileSync(path.join(STATE,"jvmd","daemon.log"),"utf8");
    const row={seq:sequence++,id:incarnation===1?"M3-failed":"M24-failed",label:"daemon exited before READY",incarnation,t:Date.now(),exitCode:daemon.process.exitCode,
      sinceSpawnMs:performance.now()-daemon.spawnedMs,error:String(error),stderrTail:log.slice(-6000)};
    marks.push(row);appendFileSync(path.join(OUT,"marks.jsonl"),JSON.stringify(row)+"\n");
    sampler?.stop();sampler=undefined;failures.push("daemon incarnation "+incarnation+" did not reach READY: exit "+daemon.process.exitCode);
    log_(`${row.id} exit ${row.exitCode} after ${row.sinceSpawnMs.toFixed(0)} ms`);
    return false;
  }
  await mark(incarnation===1?"M3":"M24",incarnation===1?"machine index READY":"persisted index reopened, READY");
  await settle("post-ready");
  await mark(incarnation===1?"M4":"M24i",incarnation===1?"daemon idle after READY":"restart: idle after READY");
  return true;
}
async function stopDaemon(){
  await mark(incarnation===1?"M21":"M27","daemon shutdown initiated");
  const started=performance.now(),snapBefore=await daemon.allocation.snapshot(false);
  sampler?.stop();sampler=undefined;
  const exited=await daemon.stop();
  const row={seq:sequence++,id:incarnation===1?"M22":"M28",label:"daemon exited",incarnation,t:Date.now(),exited,shutdownMs:performance.now()-started,exitCode:daemon.process.exitCode,
    lastSnapshotBeforeShutdown:snapBefore?compactSnapshot(snapBefore):null};
  marks.push(row);appendFileSync(path.join(OUT,"marks.jsonl"),JSON.stringify(row)+"\n");log_(`${row.id} daemon exited in ${row.shutdownMs.toFixed(0)} ms (exit ${row.exitCode})`);
}

// ---------------------------------------------------------------- workload (identical in every mode)
const CORE="impl/maven-core/src/main/java/org/apache/maven/project/",HELPER="DefaultMavenProjectHelper.java",PROJECT_FILE="MavenProject.java";
const UNRELATED="DebugConfigurationListener.java",UNRELATED_PATH="impl/maven-core/src/main/java/org/apache/maven/plugin/"+UNRELATED;
const ADDED="BenchmarkAddedType.java",ADDED_PATH=CORE+ADDED,POM="impl/maven-core/pom.xml";
const operations:any[]=[];const failures:string[]=[];
function fixture():Fixture{
  const file=(rel:string)=>{const p=path.join(PROJECT!,rel);return {path:p,uri:pathToFileURL(p).href,text:readFileSync(p,"utf8")};};
  return {root:PROJECT!,repository:REPOSITORY,pom:{artifactId:"maven"} as Pom,files:{[HELPER]:file(CORE+HELPER),[PROJECT_FILE]:file(CORE+PROJECT_FILE),[UNRELATED]:file(UNRELATED_PATH)},
    identity:"apache/maven",workspaceFolders:[{uri:pathToFileURL(PROJECT!).href,name:"maven"}]};
}
const at=(c:ScenarioContext,name:string,token:string,from=0,shift=1)=>{const text=c.text(name),offset=text.indexOf(token,from);assert(offset>=0,token+" missing from "+name);
  return {textDocument:{uri:c.file(name).uri},position:position(text,offset+shift)};};
const withProbe=(text:string,body:string)=>{const end=text.lastIndexOf("}");
  return text.slice(0,end)+`    private void benchmarkCompletion(MavenProject project) {\n        ${body}\n    }\n`+text.slice(end);};
/** Completion position: end of the probe's expression line. */
const probeAt=(c:ScenarioContext,needle:string)=>{const text=c.text(HELPER),line=text.lastIndexOf("        "+needle);assert(line>=0,"probe missing");
  return {textDocument:{uri:c.file(HELPER).uri},position:position(text,text.indexOf("\n",line))};};
const anyDiagnostics:DiagnosticCheck=()=>"pass";
const noErrorsLenient:DiagnosticCheck=errors=>{if(errors.length)throw new assert.AssertionError({message:"unexpected errors: "+errors.map((e:any)=>e.message).slice(0,3).join(" | ")});return "pass";};

let stalled:any=null;
async function attempt(label:string,f:()=>Promise<unknown>){
  if(stalled){failures.push(label+": skipped (daemon stalled)");return;}
  try{await f();}catch(error){failures.push(label+": "+String(error).split("\n")[0]);log_("FAILED "+label+": "+String(error).split("\n")[0]);await detectStall(label);}
}
/**
 * RocksDB write stall watchdog. A thread parked in RocksDB.put/write for two dumps 5 s apart while the process
 * burns no CPU is the WriteBufferManager stall (docs/jvmd-memory-allocation-profile.md); the evidence is kept and
 * the rest of the session is skipped, since every later request queues behind it.
 */
function cpuTicks(pid:number){try{const f=readFileSync(`/proc/${pid}/stat`,"utf8").split(") ")[1].split(" ");return Number(f[11])+Number(f[12]);}catch{return 0;}}
async function detectStall(label:string){
  if(!daemon.alive())return;
  const rocksWriters=(dump:string)=>dump.split("\n\n").filter(t=>/org\.rocksdb\.RocksDB\.(put|write|delete)/u.test(t)).map(t=>t.split("\n")[0]);
  const d1=jcmd(JAVA_HOME,daemon.pid,["Thread.print"]).stdout,c1=cpuTicks(daemon.pid);
  if(!rocksWriters(d1).length)return;
  await new Promise(r=>setTimeout(r,5000));
  const d2=jcmd(JAVA_HOME,daemon.pid,["Thread.print"]).stdout,c2=cpuTicks(daemon.pid);
  const stuck=rocksWriters(d2);
  if(!stuck.length||c2-c1>50)return; // >0.5 s CPU in 5 s: still working
  const dir=path.join(OUT,"stall");mkdirSync(dir,{recursive:true});
  writeFileSync(path.join(dir,"threads-1.txt"),d1);writeFileSync(path.join(dir,"threads-2.txt"),d2);
  const gdb=spawnSync("gdb",["-p",String(daemon.pid),"-batch","-ex","thread apply all bt 25"],{encoding:"utf8",timeout:120000});
  if(gdb.stdout)writeFileSync(path.join(dir,"gdb.txt"),gdb.stdout);
  const wals:Record<string,number>={};
  const gens=path.join(STATE,"jvmd","store","index-v2","generations");
  if(existsSync(gens))for(const g of readdirSync(gens))for(const db of readdirSync(path.join(gens,g)))if(statSync(path.join(gens,g,db)).isDirectory())for(const f of readdirSync(path.join(gens,g,db)))if(f.endsWith(".log"))wals[db+"/"+f]=statSync(path.join(gens,g,db,f)).size;
  stalled={label,t:Date.now(),threads:stuck,writeBufferManagerStall:/WriteBufferManagerStallWrites/u.test(gdb.stdout??""),walBytes:wals,cpuTicksIn5s:c2-c1,
    proc:procMemory(daemon.pid),smaps:smapsCategories(daemon.pid),snapshot:await daemon.allocation.snapshot(false)};
  writeFileSync(path.join(dir,"stall.json"),JSON.stringify(stalled,null,1));
  log_(`STALL detected after "${label}": ${stuck.length} thread(s) parked in RocksDB writes; WBM stall=${stalled.writeBufferManagerStall}; WAL ${JSON.stringify(wals)}`);
}
async function warm(c:ScenarioContext,method:string,params:()=>any,oracle:(v:any)=>void,count=N){
  for(let i=0;i<2;i++)await c.query(method,params(),oracle,"warmup");
  for(let i=0;i<count;i++)await c.query(method,params(),oracle,"steady");
}

async function adapter(label:string){
  const started=performance.now();
  const running=await launch({server:"jvmd",root:PROJECT!,state:path.join(STATE,"server"),javaHome:JAVA_HOME,image:IMAGE,daemon:daemon as any});
  const c=new ScenarioContext(running.client,fixture(),"jvmd",600000,2,0);c.javaHome=JAVA_HOME;c.allocation=daemon.allocation;c.operations=operations;
  await c.initialize();c.timeout=180000;
  return {running,c,initializeMs:performance.now()-started,label};
}
/** Readiness as in the benchmark lifecycle: go-to-definition across files until correct. */
async function firstCorrectDefinition(c:ScenarioContext,state:string){
  const helper=c.text(HELPER),project=readFileSync(c.file(PROJECT_FILE).path,"utf8");
  const params=at(c,HELPER,"addAttachedArtifact(",helper.indexOf("project.addAttachedArtifact("));
  const target=[{uri:c.file(PROJECT_FILE).uri,range:range(project,"addAttachedArtifact",project.indexOf("public void addAttachedArtifact(")+"public void ".length)}];
  const started=performance.now();let attempts=0;
  for(const deadline=performance.now()+600000;;){
    attempts++;const before=await daemon.allocation.read();const r=await c.client.request("textDocument/definition",params,600000);const after=await daemon.allocation.read();
    let ok=false;try{exactLocations(r.result,target);ok=true;}catch{/* not yet */}
    operations.push({method:"textDocument/definition",state:state+(ok?"":"_not_ready"),latencyMs:Number(BigInt(r.endNs)-BigInt(r.startNs))/1e6,allocatedBytes:before!==null&&after!==null?after-before:null,outcome:ok?"pass":"not_ready"});
    if(ok)break;if(performance.now()>deadline)throw new Error("workspace never answered definition correctly");
    await new Promise(res=>setTimeout(res,250));
  }
  return {ms:performance.now()-started,attempts};
}

async function workload(){
  const original={pom:readFileSync(path.join(PROJECT!,POM),"utf8")};
  // ---------- session 1: cold workspace open
  await mark("M5-","before workspace session (idle)");
  let {running,c,initializeMs}=await adapter("open");
  await mark("M6","session.open returned (project resolution complete)");
  const openedHelper=await c.open(HELPER),openedProject=await c.open(PROJECT_FILE);
  await mark("M8","documents opened (didOpen sent)");
  await attempt("open diagnostics HELPER",()=>observeDiagnostics(c,c.file(HELPER).uri,openedHelper.trigger,anyDiagnostics,0,"open_diagnostics"));
  await attempt("open diagnostics PROJECT",()=>observeDiagnostics(c,c.file(PROJECT_FILE).uri,openedProject.trigger,anyDiagnostics,0,"open_diagnostics"));
  await mark("M9a","first diagnostics published for both documents");
  await settle("admission");
  await mark("M9","diagnostics / semantic admission settled");
  const helper=c.text(HELPER),project=c.text(PROJECT_FILE),call=helper.indexOf("project.addAttachedArtifact("),declaration=project.indexOf("public void addAttachedArtifact(")+"public void ".length;
  // ---------- completion: first, warm loop, prefix narrowing
  let trigger=c.change(HELPER,withProbe(helper,"project.getGr")).trigger;
  await attempt("first completion",()=>c.transition("textDocument/completion",()=>probeAt(c,"project.getGr"),v=>completionOracle(v,["getGroupId"]),trigger,"first completion"));
  await mark("M10","first correct completion");
  await attempt("warm completion",()=>warm(c,"textDocument/completion",()=>probeAt(c,"project.getGr"),v=>completionOracle(v,["getGroupId"])));
  await mark("M11","steady warm completion loop finished");
  for(const [prefix,wanted] of [["","getGroupId"],["g","getGroupId"],["get","getGroupId"],["getM","getModel"]] as [string,string][]){
    trigger=c.change(HELPER,withProbe(helper,"project."+prefix)).trigger;
    const oracle=(v:any)=>{assert(completionItems(v).length>0,"no completion items");if(prefix)completionOracle(v,[wanted]);};
    await attempt("prefix first "+prefix,()=>c.transition("textDocument/completion",()=>probeAt(c,"project."+prefix),oracle,trigger,"prefix "+prefix));
    await mark(`M11p-${prefix||"dot"}-first`,`prefix "project.${prefix}" first`);
    await attempt("prefix warm "+prefix,()=>warm(c,"textDocument/completion",()=>probeAt(c,"project."+prefix),oracle,Math.min(N,30)));
    await mark(`M11p-${prefix||"dot"}-warm`,`prefix "project.${prefix}" warm x${Math.min(N,30)}`);
  }
  // completionItem/resolve (only if advertised)
  trigger=c.change(HELPER,withProbe(helper,"project.getGr")).trigger;
  let items:any[]=[];
  await attempt("completion for resolve",async()=>{items=completionItems(await c.transition("textDocument/completion",()=>probeAt(c,"project.getGr"),v=>completionOracle(v,["getGroupId"]),trigger,"resolve source"));});
  const resolvable=c.capabilities?.completionProvider?.resolveProvider;
  if(resolvable&&items.length){
    const item=items.find((i:any)=>String(i.label).startsWith("getGroupId"))??items[0];
    await mark("M11r-","before completionItem/resolve");
    await attempt("resolve first",()=>c.query("completionItem/resolve",item,v=>assert(String(v?.label??"").startsWith("getGroupId"),"resolved wrong item"),"first_use"));
    await mark("M11r-first","completionItem/resolve first");
    await attempt("resolve warm",()=>warm(c,"completionItem/resolve",()=>item,v=>assert(String(v?.label??"").startsWith("getGroupId"))));
    await mark("M11r-warm","completionItem/resolve warm loop");
  }else log_("completionItem/resolve not advertised; skipped");
  // ---------- definition / hover / references: first then warm
  const defParams=()=>at(c,HELPER,"addAttachedArtifact(",c.text(HELPER).indexOf("project.addAttachedArtifact("));
  const target={uri:c.file(PROJECT_FILE).uri,range:range(project,"addAttachedArtifact",declaration)};
  await attempt("definition first",()=>c.query("textDocument/definition",defParams(),v=>exactLocations(v,[target]),"first_use"));
  await mark("M12","first definition");
  await attempt("definition warm",()=>warm(c,"textDocument/definition",defParams,v=>exactLocations(v,[target])));
  await mark("M12w","definition warm loop");
  const hoverParams=()=>at(c,HELPER,"addAttachedArtifact(",c.text(HELPER).indexOf("project.addAttachedArtifact("));
  await attempt("hover first",()=>c.query("textDocument/hover",hoverParams(),v=>hoverOracle(v,"addAttachedArtifact","void"),"first_use"));
  await mark("M13","first hover");
  await attempt("hover warm",()=>warm(c,"textDocument/hover",hoverParams,v=>hoverOracle(v,"addAttachedArtifact","void")));
  await mark("M13w","hover warm loop");
  const refParams=()=>({...at(c,PROJECT_FILE,"addAttachedArtifact(",declaration),context:{includeDeclaration:true}});
  const refOracle=(v:any)=>{const rows=(v??[]).map((r:any)=>r.uri+"#"+JSON.stringify(r.range.start));
    for(const wanted of [target,{uri:c.file(HELPER).uri,range:range(c.text(HELPER),"addAttachedArtifact",c.text(HELPER).indexOf("project.addAttachedArtifact("))}])
      assert(rows.includes(wanted.uri+"#"+JSON.stringify(wanted.range.start)),"reference missing: "+wanted.uri);};
  if(a["skip-references"]==="true")log_("references skipped (--skip-references)");else{
  // First-use references scans the workspace (minutes at a 1 GiB heap): measured to completion, not cut at 60 s.
  c.timeout=1800000;
  await attempt("references first",()=>c.query("textDocument/references",refParams(),refOracle,"first_use"));
  await mark("M14","first references");
  await attempt("references warm",()=>warm(c,"textDocument/references",refParams,refOracle,Math.min(N,10)));
  c.timeout=180000;
  await mark("M14w","references warm loop");
  }
  await attempt("other queries",async()=>{
    await c.query("textDocument/documentSymbol",{textDocument:{uri:c.file(PROJECT_FILE).uri}},v=>assert((v??[]).some((s:any)=>s.name==="MavenProject")),"first_use");
    await c.query("textDocument/semanticTokens/full",{textDocument:{uri:c.file(HELPER).uri}},v=>assert(v?.data?.length>0),"first_use");
    await c.query("textDocument/signatureHelp",at(c,HELPER,"addAttachedArtifact(",c.text(HELPER).indexOf("project.addAttachedArtifact("),"addAttachedArtifact(".length),
      v=>assert.match(v?.signatures?.[v.activeSignature??0]?.label??"",/addAttachedArtifact/u),"first_use");
  });
  await mark("M14x","documentSymbol, semanticTokens, signatureHelp first use");
  if(stalled){log_("daemon stalled: session phases skipped; going to shutdown and restart");await running.stop({closeSession:false}).catch(()=>undefined);spawnSync("git",["-C",PROJECT!,"checkout","--",POM]);return;}
  // ---------- mutations
  // Body-only: a statement inside MavenProject.getGroupId(); the API is unchanged.
  let changed=c.change(PROJECT_FILE,project.replace("String groupId = getModel().getGroupId();","String groupId = getModel().getGroupId();\n        int benchmarkBodyOnly = groupId == null ? 0 : 1;"));
  let since=c.client.notifications.length;
  await attempt("body edit diagnostics",()=>observeDiagnostics(c,c.file(PROJECT_FILE).uri,changed.trigger,noErrorsLenient,since,"body_edit_diagnostics"));
  await mark("M15","body-only source mutation admitted (diagnostics for new version)");
  await attempt("completion after body edit",()=>c.transition("textDocument/completion",()=>probeAt(c,"project.getGr"),v=>completionOracle(v,["getGroupId"]),changed.trigger,"completion after body edit"));
  await mark("M15q","first completion after body-only edit");
  // Relevant API: a member added to MavenProject must be offered at the unchanged caller.
  c.change(HELPER,withProbe(helper,"project.benchmark"));
  const projectWithMember=c.text(PROJECT_FILE).replace("public String getGroupId() {","public void benchmarkAdded() {}\n\n    public String getGroupId() {");
  trigger=c.change(PROJECT_FILE,projectWithMember).trigger;
  await attempt("relevant API edit",()=>c.transition("textDocument/completion",()=>probeAt(c,"project.benchmark"),v=>completionOracle(v,["benchmarkAdded"]),trigger,"new MavenProject member offered"));
  await mark("M16","relevant API mutation admitted (new member offered)");
  // Unrelated API: a public method added to a plugin-package class neither open file uses.
  const unrelated=await c.open(UNRELATED);since=c.client.notifications.length;
  await attempt("unrelated open diagnostics",()=>observeDiagnostics(c,c.file(UNRELATED).uri,unrelated.trigger,anyDiagnostics,since,"unrelated_open"));
  await mark("M16u-","unrelated document opened");
  const unrelatedText=c.text(UNRELATED),end=unrelatedText.lastIndexOf("}");
  changed=c.change(UNRELATED,unrelatedText.slice(0,end)+"    public void benchmarkUnrelated() {}\n"+unrelatedText.slice(end));since=c.client.notifications.length;
  await attempt("unrelated API diagnostics",()=>observeDiagnostics(c,c.file(UNRELATED).uri,changed.trigger,noErrorsLenient,since,"unrelated_api_diagnostics"));
  await attempt("completion after unrelated edit",()=>c.transition("textDocument/completion",()=>probeAt(c,"project.benchmark"),v=>completionOracle(v,["benchmarkAdded"]),changed.trigger,"completion after unrelated edit"));
  await mark("M16u","unrelated API mutation admitted");
  c.close(UNRELATED);
  // Source add: a new type in the project package is completed from the unchanged caller.
  const addedText="package org.apache.maven.project;\n\npublic class BenchmarkAddedType {\n    public static int benchmarkValue() {\n        return 1;\n    }\n}\n";
  trigger=c.createDisk(ADDED,ADDED_PATH,addedText);
  c.change(HELPER,withProbe(helper,"BenchmarkAddedType.benchmarkV"));
  await attempt("source add",()=>c.transition("textDocument/completion",()=>probeAt(c,"BenchmarkAddedType.benchmarkV"),v=>completionOracle(v,["benchmarkValue"]),trigger,"added source type"));
  await mark("M16a","source file added (new type completed)");
  changed=c.change(HELPER,withProbe(helper,"BenchmarkAddedType.benchmarkValue();"));since=c.client.notifications.length;
  await attempt("source add diagnostics",()=>observeDiagnostics(c,c.file(HELPER).uri,changed.trigger,noErrorsLenient,since,"added_type_use"));
  c.change(HELPER,withProbe(helper,"BenchmarkAddedType.benchmarkV"));
  trigger=c.deleteDisk(ADDED);
  await attempt("source remove",()=>c.transition("textDocument/completion",()=>probeAt(c,"BenchmarkAddedType.benchmarkV"),v=>completionOracle(v,[],["benchmarkValue"]),trigger,"removed source type no longer completed"));
  await mark("M16r","source file removed (removed type no longer completed)");
  // POM: a dependency on commons-lang3 (present in the repository, absent from maven-core) resolves at the caller.
  const pomWithDependency=original.pom.replace("<dependencies>","<dependencies>\n    <dependency>\n      <groupId>org.apache.commons</groupId>\n      <artifactId>commons-lang3</artifactId>\n      <version>3.20.0</version>\n    </dependency>");
  assert.notEqual(pomWithDependency,original.pom);
  c.change(HELPER,withProbe(helper,"org.apache.commons.lang3.StringUtils.isBl"));
  trigger=c.writeProjectFile(POM,pomWithDependency);
  await attempt("pom dependency",()=>c.transition("textDocument/completion",()=>probeAt(c,"org.apache.commons.lang3.StringUtils.isBl"),v=>completionOracle(v,["isBlank"]),trigger,"dependency added in pom.xml"));
  await mark("M17","project/POM/classpath mutation admitted (new dependency completed)");
  trigger=c.writeProjectFile(POM,original.pom);
  await attempt("pom revert",()=>c.transition("textDocument/completion",()=>probeAt(c,"org.apache.commons.lang3.StringUtils.isBl"),v=>completionOracle(v,[],["isBlank"]),trigger,"dependency removed from pom.xml"));
  await mark("M17r","POM reverted (dependency no longer offered)");
  // ---------- close / reconnect
  c.close(HELPER);c.close(PROJECT_FILE);
  await settle("documents closed");
  await mark("M18","documents closed");
  let problem=await running.stop({closeSession:false});if(problem)failures.push("adapter stop: "+problem);
  await settle("adapter disconnected");
  await mark("M18b","adapter disconnected, session retained");
  ({running,c,initializeMs}=await adapter("reconnect"));
  await mark("M20-init","reconnect: initialize returned");
  await c.open(HELPER);
  await attempt("reconnect definition",()=>firstCorrectDefinition(c,"reconnect_first_correct"));
  await mark("M20","reconnect to retained daemon: first correct definition");
  c.close(HELPER);
  problem=await running.stop({closeSession:true});if(problem)failures.push("adapter stop: "+problem);
  await mark("M19","session closed (session.close returned)");
  await settle("session closed");
  await mark("M19s","session closed, settled");
  ({running,c,initializeMs}=await adapter("reopen"));
  await c.open(HELPER);
  await attempt("reopen definition",()=>firstCorrectDefinition(c,"reopen_after_close_first_correct"));
  await mark("M20b","reopen after session close: first correct definition");
  c.close(HELPER);
  problem=await running.stop({closeSession:true});if(problem)failures.push("adapter stop: "+problem);
  await settle("second session closed");
  await mark("M20bx","second session closed, settled (immediately before shutdown)");
  spawnSync("git",["-C",PROJECT!,"checkout","--",POM]);spawnSync("rm",["-f",path.join(PROJECT!,ADDED_PATH)]);
}

async function restartWorkload(){
  let {running,c}=await adapter("restart");
  await mark("M25","workspace reopened after daemon restart (session.open returned)");
  await c.open(HELPER);
  await attempt("restart definition",()=>firstCorrectDefinition(c,"restart_first_correct"));
  await mark("M26","first correct semantic query after restart (definition)");
  const helper=c.text(HELPER);const trigger=c.change(HELPER,withProbe(helper,"project.getGr")).trigger;
  await attempt("restart completion",()=>c.transition("textDocument/completion",()=>probeAt(c,"project.getGr"),v=>completionOracle(v,["getGroupId"]),trigger,"completion after restart"));
  await mark("M26b","first correct completion after restart");
  c.close(HELPER);
  const problem=await running.stop({closeSession:true});if(problem)failures.push("adapter stop: "+problem);
  await settle("restart session closed");
  await mark("M26x","restart session closed, settled");
}

// ---------------------------------------------------------------- main
const started=performance.now();
try{
  if(PROJECT){settingsXml(REPOSITORY,path.join(REPOSITORY,"settings.xml"));spawnSync("git",["-C",PROJECT,"checkout","--",POM]);}
  // --reuse-state: start from a copy of a persisted daemon state, so the first incarnation is itself a restart.
  if(a["reuse-state"]){mkdirSync(STATE,{recursive:true});spawnSync("cp",["-a",path.resolve(a["reuse-state"]),path.join(STATE,"jvmd")]);}
  await startDaemon(REPOSITORY);
  if(SEED_ONLY){
    // Forced full GC + histogram: the retained heap of the machine index alone.
    await mark("S1","seed-only: live heap after forced full GC",{histogram:true,smaps:true});
    await stopDaemon();
    if(a["seed-restart"]==="true"&&await startDaemon(REPOSITORY)){
      await mark("S2","seed-only restart: live heap after forced full GC",{histogram:true,smaps:true});
      await stopDaemon();
    }
  }else{
    await workload();
    await stopDaemon();
    if(a["no-restart"]!=="true"&&await startDaemon(REPOSITORY)){
      await restartWorkload();
      await stopDaemon();
    }
  }
}catch(error){failures.push("driver: "+String((error as Error).stack??error));log_("DRIVER ERROR",String(error));try{if(daemon?.alive())await daemon.stop();}catch{/* */}}
finally{
  sampler?.stop();
  // An adapter whose daemon disappears auto-launches a production daemon: adapters go first, then any daemon on this run's config.
  spawnSync("pkill",["-f","shim/src/main.ts --lsp --root "+(PROJECT??"/nonexistent")]);
  spawnSync("pkill",["-9","-f","-Djvmd.config="+path.join(STATE,"jvmd","config.json")]);
  if(PROJECT){spawnSync("git",["-C",PROJECT,"checkout","--",POM]);spawnSync("rm",["-f",path.join(PROJECT,ADDED_PATH)]);}
}
const resultShape=(r:any)=>{const items=Array.isArray(r)?r:Array.isArray(r?.items)?r.items:Array.isArray(r?.data)?null:null;
  return {resultItems:items?items.length:Array.isArray(r?.data)?r.data.length/5:r==null?0:1,resultBytes:r===undefined?0:Buffer.byteLength(JSON.stringify(r))};};
const ops=operations.map(({rawResult,...op}:any)=>({...op,...resultShape(rawResult),...(op.outcome==="pass"?{}:{rawResult:JSON.stringify(rawResult)?.slice(0,2000)})}));
writeFileSync(path.join(OUT,"results.json"),JSON.stringify({environment:env,wallMs:performance.now()-started,failures,marks,operations:ops,stall:stalled},null,1));
log_(`done in ${((performance.now()-started)/1000).toFixed(0)} s; ${failures.length} failures; ${ops.filter(o=>o.outcome&&o.outcome!=="pass"&&o.outcome!=="not_ready"&&o.outcome!=="stale").length} incorrect operations`);
for(const f of failures)log_("  failure: "+f);
console.log(`\n=== failures (${failures.length})`);for(const f of failures)console.log("failure: "+f.slice(0,400));
// The CI container that reads these runs cannot download artifacts: the control's summary goes to the job log.
const mbd=(b:any)=>b==null?"—":(b/1e6).toFixed(1);
console.log("\n=== checkpoints (id | s since spawn | allocated MB since previous | heap used MB | RSS MB | label)");
let previous:number|undefined;
for(const m of marks){const alloc=m.snapshot?.allocated;console.log(`checkpoint: ${m.id} | i${m.incarnation} | ${(m.sinceSpawnMs/1000).toFixed(1)} | ${previous!==undefined&&alloc!=null?mbd(alloc-previous):"—"} | ${mbd(m.snapshot?.heap?.used)} | ${mbd(m.proc?.VmRSS)} | ${m.label}`);if(alloc!=null)previous=alloc;}
console.log("\n=== first-use operations (method | state | outcome | latency ms | allocated MB)");
for(const o of ops)if(o.state!=="steady"&&!/warm/u.test(String(o.state)))console.log(`operation: ${o.method} | ${o.state} | ${o.outcome} | ${o.latencyMs?.toFixed?.(0)} | ${mbd(o.allocatedBytes)}`);
const refs=ops.find(o=>o.method==="textDocument/references"&&o.state==="first_use");
// Frozen M1 deadline (brief §11 M1, E9): the 60 s client deadline under which main answered 0 of 17 attempts.
console.log(`\nM1 references first use: ${refs?`${refs.outcome}, ${(refs.latencyMs/1000).toFixed(1)} s, timely=${refs.outcome==="pass"&&refs.latencyMs<=60000}`:"not reached"}`);
console.log(`M2 restart: ${marks.some(m=>m.id==="M26")?"first correct definition after restart reached":"not reached"}`);
process.exit(0);
