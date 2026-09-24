import assert from "node:assert/strict";
import { spawn, type ChildProcess } from "node:child_process";
import {
  copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync,
} from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { LspBridge } from "../../shim/src/lsp.ts";
import { RpcClient, RpcPool, type Message, type RpcCaller } from "../../shim/src/transport.ts";
import { latencyStats, PHASE_MODEL } from "./harness/phases.ts";
import {
  compilerEvidence, definitionCorrect, fileStateEvidence, indexSummary, methodBreakdown,
  numericDelta, resolverSummary, sumNumeric,
} from "./harness/jvmdLifecycle.ts";

type RpcMetric={method:string;latencyMs:number;receivedNs:number;session?:string};
type RpcCall={method:string;params:any;startedNs:number;finishedNs:number;result?:any;error?:string};
type Diagnostic={uri:string;version?:number;receivedNs:number;params:any};
type Operation={latencyMs:number;correct:boolean;result:any};

function nowNs(){return Number(process.hrtime.bigint());}
function ms(start:number,end:number){return (end-start)/1e6;}
function sleep(milliseconds:number){return new Promise(resolve=>setTimeout(resolve,milliseconds));}
function rssKb(pid?:number){
  if(!pid)return 0;
  try{
    const status=readFileSync("/proc/"+pid+"/status","utf8");
    return Number(status.match(/^VmRSS:\s+(\d+)\s+kB/m)?.[1]??0);
  }catch{return 0;}
}
function daemonResult(envelope:any){return envelope?.result??{};}
function deltaIndex(after:any,before:any){
  return {
    scans:numericDelta(after.timings,before.timings,"scans"),
    scanMs:numericDelta(after.timings,before.timings,"scanMs"),
    discoveryMs:numericDelta(after.timings,before.timings,"discoveryMs"),
    hashMs:numericDelta(after.timings,before.timings,"hashMs"),
    parseMs:numericDelta(after.timings,before.timings,"parseMs"),
    storageMs:numericDelta(after.timings,before.timings,"storageMs"),
    docsMs:numericDelta(after.timings,before.timings,"docsMs"),
    linkMs:numericDelta(after.timings,before.timings,"linkMs"),
    workspaceIndexLoads:numericDelta(after.timings,before.timings,"workspaceIndexLoads"),
    workspaceIndexLoadMs:numericDelta(after.timings,before.timings,"workspaceIndexLoadMs"),
    reused:numericDelta(after,before,"artifactsReused"),
    hashed:numericDelta(after,before,"artifactsHashed"),
    indexedPublications:numericDelta(after,before,"indexedPublications"),
  };
}
function inventoryRepository(root:string){
  let jars=0,sources=0,firstBinary:string|undefined;
  const stack=[root];
  while(stack.length){
    const directory=stack.pop()!;
    let entries;
    try{entries=readdirSync(directory,{withFileTypes:true});}catch{continue;}
    for(const entry of entries){
      const file=path.join(directory,entry.name);
      if(entry.isDirectory())stack.push(file);
      else if(entry.isFile()&&entry.name.endsWith(".jar")&&!entry.name.endsWith("-javadoc.jar")){
        jars++;
        if(entry.name.endsWith("-sources.jar"))sources++;
        else if(!firstBinary)firstBinary=file;
      }
    }
  }
  if(!firstBinary)throw new Error("No binary JAR available for controlled lifecycle mutation");
  return {jars,sources,firstBinary};
}

async function waitForRepositoryInventory(daemon:Daemon,expectedArtifacts:number,afterScans:number,timeoutMs=90000){
  const deadline=Date.now()+timeoutMs;
  while(Date.now()<deadline){
    const status=await daemon.status();
    const index=indexSummary(status);
    if(index.phase==="ready"&&index.artifactsDiscovered===expectedArtifacts&&index.timings.scans>afterScans)return status;
    await sleep(100);
  }
  throw new Error("Timed out waiting for machine repository reconciliation to "+expectedArtifacts+" artifacts");
}
async function connectClient(socketPath:string){
  const socket=await new Promise<net.Socket>((resolve,reject)=>{
    const connection=net.createConnection(socketPath);
    connection.once("connect",()=>resolve(connection));
    connection.once("error",reject);
  });
  return new RpcClient(socket);
}
async function waitExit(process:ChildProcess,timeoutMs=30000){
  if(process.exitCode!==null)return process.exitCode;
  return new Promise<number|null>((resolve,reject)=>{
    const timer=setTimeout(()=>reject(new Error("Daemon did not exit")),timeoutMs);
    process.once("exit",code=>{clearTimeout(timer);resolve(code);});
  });
}

class Daemon {
  process:ChildProcess;
  socketPath:string;
  stateDir:string;
  control:RpcClient;
  metrics:RpcMetric[]=[];
  processStartedNs:number;
  transportAvailableNs=0;
  machineIndexReadyNs=0;
  machineReadyStatus:any;
  machineReadyRssKb=0;
  private stderrBuffer="";
  constructor(process:ChildProcess,socketPath:string,stateDir:string,control:RpcClient,startedNs:number){
    this.process=process;this.socketPath=socketPath;this.stateDir=stateDir;this.control=control;this.processStartedNs=startedNs;
  }
  static async start(image:string,stateDir:string,label:string){
    mkdirSync(stateDir,{recursive:true});
    const socketPath=path.join(stateDir,"daemon.sock");
    const configPath=path.join(stateDir,"config.json");
    writeFileSync(configPath,"{}\n");
    const startedNs=nowNs();
    const child=spawn(path.join(image,"bin/java"),[
      "-Djvmd.socket="+socketPath,
      "-Djvmd.state="+stateDir,
      "-Djvmd.config="+configPath,
      "-cp",path.join(image,"lib/jvmd/*"),
      "dev.jvmd.dist.Application",
    ],{stdio:["ignore","pipe","pipe"],env:{...process.env,JVMD_SOCKET:socketPath}});
    child.stdout?.on("data",()=>{});
    let daemon:Daemon|undefined;
    child.stderr?.setEncoding("utf8");
    child.stderr?.on("data",(chunk:string)=>{if(daemon)daemon.consumeStderr(chunk,label);});
    const deadline=Date.now()+30000;
    let control:RpcClient|undefined;
    while(Date.now()<deadline&&!control){
      if(child.exitCode!==null)throw new Error(label+" daemon exited before transport became available");
      try{control=await connectClient(socketPath);}
      catch(error){
        const code=(error as NodeJS.ErrnoException).code;
        if(code!=="ENOENT"&&code!=="ECONNREFUSED")throw error;
        await sleep(10);
      }
    }
    if(!control)throw new Error(label+" daemon transport did not become available");
    daemon=new Daemon(child,socketPath,stateDir,control,startedNs);
    daemon.transportAvailableNs=nowNs();
    await daemon.waitMachineReady();
    return daemon;
  }
  private consumeStderr(chunk:string,label:string){
    this.stderrBuffer+=chunk;
    for(;;){
      const end=this.stderrBuffer.indexOf("\n");
      if(end<0)break;
      const line=this.stderrBuffer.slice(0,end).trim();this.stderrBuffer=this.stderrBuffer.slice(end+1);
      if(!line)continue;
      try{
        const row=JSON.parse(line);
        if(typeof row.method==="string"&&typeof row.latency_ms==="number"){
          this.metrics.push({method:row.method,latencyMs:row.latency_ms,receivedNs:nowNs(),session:row.session});
          continue;
        }
      }catch{}
      process.stderr.write("["+label+"] "+line+"\n");
    }
  }
  async call(method:string,params:any={}){
    return this.control.call(method,params);
  }
  async status(){return this.call("daemon.status");}
  async sessionStatus(session:string){return this.call("session.status",{session});}
  async waitMachineReady(){
    const deadline=Date.now()+180000;
    let status:any;
    while(Date.now()<deadline){
      status=await this.status();
      const index=daemonResult(status).index;
      if(index?.phase==="ready"&&Number(index?.timings?.scans??0)>=1){
        this.machineIndexReadyNs=nowNs();this.machineReadyStatus=status;this.machineReadyRssKb=rssKb(this.process.pid);return;
      }
      await sleep(25);
    }
    throw new Error("Timed out waiting for machine index");
  }
  async stop(){
    try{await this.call("daemon.shutdown");}catch{}
    this.control.close();
    try{await waitExit(this.process);}catch{this.process.kill("SIGTERM");await waitExit(this.process).catch(()=>this.process.kill("SIGKILL"));}
  }
}

class TracingCaller implements RpcCaller {
  pool:RpcPool;calls:RpcCall[]=[];
  constructor(pool:RpcPool){this.pool=pool;}
  async call(method:string,params:any={}){
    const call:RpcCall={method,params,startedNs:nowNs(),finishedNs:0};
    try{call.result=await this.pool.call(method,params);return call.result;}
    catch(error){call.error=String(error);throw error;}
    finally{call.finishedNs=nowNs();this.calls.push(call);}
  }
}

class BridgeDriver {
  bridge:LspBridge;responses=new Map<number,Message>();diagnostics:Diagnostic[]=[];nextId=0;
  constructor(bridge:LspBridge){this.bridge=bridge;}
  send=(message:Message)=>{
    if(typeof message.id==="number")this.responses.set(message.id,message);
    if(message.method==="textDocument/publishDiagnostics"){
      const params=message.params??{};
      this.diagnostics.push({uri:params.uri,version:params.version,receivedNs:nowNs(),params});
    }
  };
  async request(method:string,params:any){
    const id=++this.nextId;
    await this.bridge.handle({jsonrpc:"2.0",id,method,params});
    const response=this.responses.get(id);
    if(!response)throw new Error("Missing LSP response: "+method);
    this.responses.delete(id);
    if(response.error)throw new Error(method+": "+response.error.message);
    return response.result;
  }
  async notify(method:string,params:any={}){
    await this.bridge.handle({jsonrpc:"2.0",method,params});
  }
  async mutation(method:string,params:any,uri:string,version:number){
    const before=this.diagnostics.length,startedNs=nowNs();
    await this.notify(method,params);
    const diagnostic=await this.waitDiagnostic(uri,version,before);
    return {method,uri,version,startedNs,finishedNs:diagnostic.receivedNs,durationMs:ms(startedNs,diagnostic.receivedNs)};
  }
  private async waitDiagnostic(uri:string,version:number,after:number){
    const deadline=Date.now()+180000;
    for(;;){
      const found=this.diagnostics.slice(after).find(row=>row.uri===uri&&(row.version===undefined||row.version===version));
      if(found)return found;
      if(Date.now()>deadline)throw new Error("Timed out waiting for diagnostics "+uri+" v"+version);
      await sleep(10);
    }
  }
}

function machineReport(mode:string,daemon:Daemon,repository:any,indexStatus:any){
  const index=indexSummary(indexStatus);
  return {
    mode,
    processStartedNs:daemon.processStartedNs,
    transportAvailableNs:daemon.transportAvailableNs,
    machineIndexReadyNs:daemon.machineIndexReadyNs,
    processToTransportMs:ms(daemon.processStartedNs,daemon.transportAvailableNs),
    processToMachineIndexReadyMs:ms(daemon.processStartedNs,daemon.machineIndexReadyNs),
    machineIndexReconcileMs:index.timings.scanMs,
    rssAtMachineIndexReadyKb:daemon.machineReadyRssKb,
    repository:{
      path:path.join(os.homedir(),".m2","repository"),
      jarArtifacts:repository.jars,
      sourceJarArtifacts:repository.sources,
    },
    index,
    aotStatus:daemonResult(indexStatus).aot_cache??"unavailable",
    sourceArtifactUpdateCount:"unavailable: current daemon status exposes aggregate reuse/hash counters and docs_ms, not source-JAR update counts",
    jdkIndexReadiness:"not part of the current repository machine-index-ready barrier; no separate eager-JDK readiness milestone is exposed",
  };
}

function statusEvidence(status:any){
  const result=daemonResult(status);
  return {index:indexSummary(status),resolver:resolverSummary(status),rawAot:result.aot_cache};
}

function sessionValue(envelope:any){return envelope?.result??{};}
function recursiveStatusDelta(before:any,after:any,key:string){
  const walk=(value:any):number=>{
    if(!value||typeof value!=="object")return 0;
    let total=0;
    for(const [name,item] of Object.entries(value)){
      if(name===key&&typeof item==="number")total+=item;
      if(item&&typeof item==="object")total+=walk(item);
    }
    return total;
  };
  return walk(after)-walk(before);
}

async function runDefinitionSession(daemon:Daemon,root:string,label:string,localChange:boolean){
  const sessionBaselineRss=rssKb(daemon.process.pid);
  const beforeDaemon=await daemon.status();
  const pool=new RpcPool(()=>connectClient(daemon.socketPath));
  const tracing=new TracingCaller(pool);
  let driver!:BridgeDriver;
  const bridge=new LspBridge(async()=>tracing,root,message=>driver.send(message),()=>{});
  driver=new BridgeDriver(bridge);
  const rootUri=pathToFileURL(root).href;
  const initializeStartedNs=nowNs();
  await driver.request("initialize",{
    processId:process.pid,rootUri,workspaceFolders:[{uri:rootUri,name:"apache-maven"}],
    capabilities:{workspace:{configuration:true},textDocument:{publishDiagnostics:{versionSupport:true}}},
  });
  const initializeFinishedNs=nowNs();
  await driver.notify("initialized",{});
  const openCall=tracing.calls.find(call=>call.method==="session.open");
  if(!openCall)throw new Error("LSP initialize did not call session.open");
  const session=sessionValue(openCall.result).session;
  if(!session)throw new Error("session.open omitted session id");
  const afterInitialize=await daemon.status();
  const sessionAfterInitialize=sessionValue(await daemon.sessionStatus(session));
  const beforeIndex=indexSummary(beforeDaemon),afterIndex=indexSummary(afterInitialize);
  const beforeResolver=resolverSummary(beforeDaemon),afterResolver=resolverSummary(afterInitialize);
  const resolveCalls=numericDelta(afterResolver,beforeResolver,"resolveCalls");
  const fastHits=numericDelta(afterResolver,beforeResolver,"projectModelFastHits");
  const workspaceResolutionMs=resolveCalls>0?sumNumeric(afterResolver.coldTimings):null;
  const machineReadyRss=daemon.machineReadyRssKb;
  const sessionOpenedRss=rssKb(daemon.process.pid);

  const receiverPath=path.join(root,"impl/maven-core/src/main/java/org/apache/maven/project/MavenProject.java");
  const callerPath=path.join(root,"impl/maven-core/src/main/java/org/apache/maven/project/DefaultMavenProjectHelper.java");
  const receiverUri=pathToFileURL(receiverPath).href,callerUri=pathToFileURL(callerPath).href;
  const receiverText=readFileSync(receiverPath,"utf8"),callerText=readFileSync(callerPath,"utf8");
  const marker="/*BENCH_TYPE*/";
  const inserted="\n    private void benchmarkLifecycle("+marker+"MavenProject project) {\n        project.toString();\n    }\n";
  const end=callerText.lastIndexOf("}");
  assert(end>=0);
  const withMarker=callerText.slice(0,end)+inserted+callerText.slice(end);
  const typeOffset=withMarker.indexOf(marker);
  assert(typeOffset>=0);
  const admittedCaller=withMarker.replace(marker,"");
  const typePosition=position(admittedCaller,typeOffset+2);

  const admissionBeforeDaemon=await daemon.status();
  const admissionBeforeSession=sessionValue(await daemon.sessionStatus(session));
  const admissionStartedNs=nowNs();
  const spans=[];
  spans.push(await driver.mutation("textDocument/didOpen",{textDocument:{uri:receiverUri,languageId:"java",version:1,text:receiverText}},receiverUri,1));
  spans.push(await driver.mutation("textDocument/didOpen",{textDocument:{uri:callerUri,languageId:"java",version:1,text:callerText}},callerUri,1));
  spans.push(await driver.mutation("textDocument/didChange",{textDocument:{uri:callerUri,version:2},contentChanges:[{text:admittedCaller}]},callerUri,2));
  const admissionFinishedNs=spans.at(-1)!.finishedNs;
  const admissionAfterDaemon=await daemon.status();
  const admissionAfterSession=sessionValue(await daemon.sessionStatus(session));
  const daemonTop=methodBreakdown(daemon.metrics,admissionStartedNs,admissionFinishedNs);
  const admissionWallMs=ms(admissionStartedNs,admissionFinishedNs);
  const admissionIndexBefore=indexSummary(admissionBeforeDaemon),admissionIndexAfter=indexSummary(admissionAfterDaemon);
  const admissionResolverBefore=resolverSummary(admissionBeforeDaemon),admissionResolverAfter=resolverSummary(admissionAfterDaemon);
  const internalCompiler=compilerEvidence(admissionBeforeSession,admissionAfterSession);
  const fileStates=fileStateEvidence(admissionBeforeSession.file_states,admissionAfterSession.file_states);

  const definitionParams={textDocument:{uri:callerUri},position:typePosition};
  const firstUseStartedNs=nowNs();
  const first=await measureDefinition(driver,definitionParams,receiverUri);
  const firstUseFinishedNs=nowNs();
  const warmup:Operation[]=[];
  for(let i=0;i<PHASE_MODEL.defaults.warmup;i++)warmup.push(await measureDefinition(driver,definitionParams,receiverUri));
  const steady:Operation[]=[];
  for(let i=0;i<PHASE_MODEL.defaults.steady_samples;i++)steady.push(await measureDefinition(driver,definitionParams,receiverUri));
  const correctSteady=steady.filter(row=>row.correct).map(row=>row.latencyMs);
  const steadyStats=latencyStats(correctSteady);
  const afterSteadyRss=rssKb(daemon.process.pid);

  let localChangeReport:any=null;
  if(localChange){
    const localBeforeDaemon=await daemon.status();
    const localBeforeSession=sessionValue(await daemon.sessionStatus(session));
    const changed=admittedCaller.replace("project.toString();","project.hashCode();");
    const mutation=await driver.mutation("textDocument/didChange",{textDocument:{uri:callerUri,version:3},contentChanges:[{text:changed}]},callerUri,3);
    const localAfterDaemon=await daemon.status();
    const localAfterSession=sessionValue(await daemon.sessionStatus(session));
    const postChange=await measureDefinition(driver,definitionParams,receiverUri);
    const localIndexBefore=indexSummary(localBeforeDaemon),localIndexAfter=indexSummary(localAfterDaemon);
    const localResolverBefore=resolverSummary(localBeforeDaemon),localResolverAfter=resolverSummary(localAfterDaemon);
    localChangeReport={
      mutation,
      definition:postChange,
      machineIndexDelta:deltaIndex(localIndexAfter,localIndexBefore),
      resolverDelta:{
        resolveCalls:numericDelta(localResolverAfter,localResolverBefore,"resolveCalls"),
        projectModelFastHits:numericDelta(localResolverAfter,localResolverBefore,"projectModelFastHits"),
      },
      compilerEvidence:compilerEvidence(localBeforeSession,localAfterSession),
      fileStateEvidence:fileStateEvidence(localBeforeSession.file_states,localAfterSession.file_states),
      globalDependencyReindexObserved:
        numericDelta(localIndexAfter.timings,localIndexBefore.timings,"scans")!==0
        ||numericDelta(localIndexAfter,localIndexBefore,"artifactsHashed")!==0
        ||numericDelta(localIndexAfter,localIndexBefore,"artifactsReused")!==0,
    };
  }

  await bridge.drained();await bridge.close();
  await daemon.call("session.close",{session});
  pool.close();
  const afterSessionCloseRss=rssKb(daemon.process.pid);

  return {
    label,session,
    milestones:{
      initializeStartedNs,initializeFinishedNs,
      sessionOpenStartedNs:openCall.startedNs,sessionOpenFinishedNs:openCall.finishedNs,
      workspaceResolutionStartedNs:"unavailable",
      workspaceResolvedNs:"unavailable",
      workspaceIndexReadyNs:"unavailable",
      documentAdmissionStartedNs:admissionStartedNs,documentsAdmittedNs:admissionFinishedNs,
      firstUseStartedNs,firstUseFinishedNs,
    },
    initializeMs:ms(initializeStartedNs,initializeFinishedNs),
    sessionOpenMs:ms(openCall.startedNs,openCall.finishedNs),
    sessionOpenToFirstCorrectResultMs:first.correct?ms(openCall.startedNs,firstUseFinishedNs):null,
    workspaceResolution:{
      status:resolveCalls>0?"cold_resolution_measured_by_resolver_timings":fastHits>0?"resident_project_model_fast_hit; exact resolver duration unavailable":"unavailable",
      resolveCalls,projectModelFastHits:fastHits,
      reportedColdTimingMs:workspaceResolutionMs,
      coldTimings:afterResolver.coldTimings,
      classpathState:sessionAfterInitialize.classpath_state??"unavailable",
    },
    workspaceIndex:{
      ready:"unavailable: registerLocal refresh is asynchronous and no all-current barrier is exposed",
      loadCalls:numericDelta(afterIndex.timings,beforeIndex.timings,"workspaceIndexLoads"),
      loadMs:numericDelta(afterIndex.timings,beforeIndex.timings,"workspaceIndexLoadMs"),
      machineIndexWorkDuringSessionOpen:deltaIndex(afterIndex,beforeIndex),
      activeArtifactsAfter:afterIndex.activeArtifacts,
      sourcePublisherAfter:afterIndex.sourcePublisher,
    },
    admission:{
      wallMs:admissionWallMs,documents:spans,
      topLevelDaemon:{
        ...daemonTop,
        otherOrAdapterMs:Math.max(0,admissionWallMs-daemonTop.documentMutationRpcMs-daemonTop.diagnosticsRpcMs),
      },
      resolverDelta:{
        resolveCalls:numericDelta(admissionResolverAfter,admissionResolverBefore,"resolveCalls"),
        projectModelFastHits:numericDelta(admissionResolverAfter,admissionResolverBefore,"projectModelFastHits"),
      },
      workspaceIndexDelta:deltaIndex(admissionIndexAfter,admissionIndexBefore),
      sourceObservation:fileStates,
      compilerAndSemanticEvidence:internalCompiler,
      javacPhaseSplit:"unavailable: current session status exposes aggregate compiler query_ms, not separate parse/enter/attribute durations",
      diagnosticsNote:"compiler/semantic counters are nested evidence inside the lsp.diagnostics wall time and must not be summed with top-level RPC time",
    },
    definition:{
      firstUse:first,warmup,steady,
      steadyStats,
      correctness:{first:first.correct,warmup:warmup.map(row=>row.correct),steady:steady.map(row=>row.correct)},
    },
    memory:{
      daemonMachineReadyKb:machineReadyRss,
      daemonBaselineBeforeSessionKb:sessionBaselineRss,
      sessionOpenedKb:sessionOpenedRss,
      sessionOpenIncrementKb:sessionOpenedRss-sessionBaselineRss,
      afterSteadyKb:afterSteadyRss,
      afterSessionCloseKb:afterSessionCloseRss,
    },
    statusEvidence:{
      before:statusEvidence(beforeDaemon),
      afterInitialize:statusEvidence(afterInitialize),
    },
    localChange:localChangeReport,
  };
}

function position(text:string,offset:number){
  const before=text.slice(0,offset),line=(before.match(/\n/g)||[]).length;
  const newline=before.lastIndexOf("\n");
  return {line,character:offset-(newline+1)};
}
async function measureDefinition(driver:BridgeDriver,params:any,expectedUri:string):Promise<Operation>{
  const started=performance.now(),result=await driver.request("textDocument/definition",params);
  return {latencyMs:performance.now()-started,correct:definitionCorrect(result,expectedUri),result};
}

async function main(){
  assert(process.env.FIXTURE_ROOT,"FIXTURE_ROOT is required");
  const root=path.resolve(process.env.FIXTURE_ROOT);
  const image=path.resolve("jvmd-dist/target/image");
  const temp=path.resolve(process.env.RUNNER_TEMP??"benchmarks/lsp-scenarios/.state");
  const lifecycleRoot=path.join(temp,"jvmd-lifecycle");
  const stateDir=path.join(lifecycleRoot,"state");
  const repository=path.join(os.homedir(),".m2","repository");
  const controlledRoot=path.join(repository,"dev","jvmd","benchmark-lifecycle");
  rmSync(lifecycleRoot,{recursive:true,force:true});rmSync(controlledRoot,{recursive:true,force:true});
  mkdirSync(lifecycleRoot,{recursive:true});
  const inventory=inventoryRepository(repository);

  const cold=await Daemon.start(image,stateDir,"machine-cold");
  const machineCold=machineReport("machine_cold",cold,inventory,cold.machineReadyStatus);
  await cold.stop();

  const restart=await Daemon.start(image,stateDir,"daemon-restart");
  const daemonRestart=machineReport("daemon_restart",restart,inventory,restart.machineReadyStatus);
  const residentBaselineRss=restart.machineReadyRssKb;
  const residentIndexBefore=indexSummary(restart.machineReadyStatus);
  const repositoryBeforeSessions=inventoryRepository(repository);
  const session1=await runDefinitionSession(restart,root,"first_workspace_open",false);
  const session2=await runDefinitionSession(restart,root,"workspace_reopen",true);

  const repositoryAfterSessions=inventoryRepository(repository);
  let settledStatus=await restart.status();
  let settledIndex=indexSummary(settledStatus);
  if(settledIndex.artifactsDiscovered!==repositoryAfterSessions.jars){
    settledStatus=await waitForRepositoryInventory(
      restart,repositoryAfterSessions.jars,settledIndex.timings.scans,
    );
    settledIndex=indexSummary(settledStatus);
  }
  const residentDaemon={
    mode:"resident_daemon",
    machineIndexReadyBeforeSessions:true,
    daemonBaselineRssKb:residentBaselineRss,
    repositoryBeforeSessions:{jarArtifacts:repositoryBeforeSessions.jars,sourceJarArtifacts:repositoryBeforeSessions.sources},
    repositoryAfterSessions:{jarArtifacts:repositoryAfterSessions.jars,sourceJarArtifacts:repositoryAfterSessions.sources},
    machineIndexDeltaDuringSessions:deltaIndex(settledIndex,residentIndexBefore),
    sessions:[session1,session2],
  };

  const incrementalBeforeStatus=settledStatus;
  const incrementalBefore=indexSummary(incrementalBeforeStatus);
  mkdirSync(path.dirname(path.join(controlledRoot,"1.0","benchmark-lifecycle-1.0.jar")),{recursive:true});
  const controlledArtifact=path.join(controlledRoot,"1.0","benchmark-lifecycle-1.0.jar");
  copyFileSync(inventory.firstBinary,controlledArtifact);
  const incrementalInventory=inventoryRepository(repository);
  const incrementalStartedNs=nowNs();
  const incrementalAfterStatus=await waitForRepositoryInventory(
    restart,incrementalInventory.jars,incrementalBefore.timings.scans,
  );
  const incrementalFinishedNs=nowNs();
  const incrementalAfter=indexSummary(incrementalAfterStatus);
  const incrementalDelta=deltaIndex(incrementalAfter,incrementalBefore);
  const incrementalReconcile={
    mode:"incremental_reconcile",
    daemon:"resident",
    controlledArtifact,
    artifactAddedToReadyMs:ms(incrementalStartedNs,incrementalFinishedNs),
    machineIndexReconcileMs:incrementalDelta.scanMs,
    repositoryBefore:{jarArtifacts:repositoryAfterSessions.jars,sourceJarArtifacts:repositoryAfterSessions.sources},
    repositoryAfter:{jarArtifacts:incrementalInventory.jars,sourceJarArtifacts:incrementalInventory.sources},
    indexBefore:incrementalBefore,
    indexAfter:incrementalAfter,
    indexDelta:incrementalDelta,
    sourceArtifactUpdateCount:"unavailable: current status does not expose source-JAR update counts separately",
    aotStatus:daemonResult(incrementalAfterStatus).aot_cache??"unavailable",
  };
  rmSync(controlledRoot,{recursive:true,force:true});
  await restart.stop();

  const report={
    schema:1,phaseModel:PHASE_MODEL,
    server:"jvmd",
    revision:process.env.GITHUB_SHA??"working-tree",
    fixture:"apache/maven@5cd1b60264101080c712accd605180a4bd9222e0",
    provenance:{
      daemon:"machine_cold → daemon_restart with persisted index → resident sessions → one-artifact incremental reconciliation on the resident daemon",
      machineIndex:{cold:"empty",restart:"persisted",resident:"persisted/current",incremental:"persisted/current + one controlled binary JAR"},
      workspace:{session1:"first open",session2:"reopen on same resident daemon"},
      localWorkspaceState:"resident in daemon; persisted-local readiness not assumed",
      machineRepository:{baseline:"unchanged",incremental:"one controlled binary JAR added after resident sessions"},
      aot:{machineCold:machineCold.aotStatus,daemonRestart:daemonRestart.aotStatus,incremental:incrementalReconcile.aotStatus},
      filesystemCache:"uncontrolled; not flushed",
      jdk:process.env.JAVA_HOME??"system",node:process.version,
      runner:{platform:process.platform,arch:process.arch,cpus:os.cpus().length},
    },
    machine:{machineCold,daemonRestart,incrementalReconcile,residentDaemon},
  };
  const output=path.resolve(process.env.MACHINE_REPORT_FILE??path.join(lifecycleRoot,"jvmd-machine-lifecycle.json"));
  writeFileSync(output,JSON.stringify(report,null,2)+"\n");
  const summary=markdown(report);console.log(summary);
  if(process.env.GITHUB_STEP_SUMMARY)writeFileSync(process.env.GITHUB_STEP_SUMMARY,summary+"\n",{flag:"a"});
}

function fmt(value:any){return value===null||value===undefined?"-":Number(value).toFixed(2);}
function markdown(report:any){
  const m=report.machine;
  const sessions=m.residentDaemon.sessions;
  const rows=[
    ["machine_cold",m.machineCold],["daemon_restart",m.daemonRestart],["incremental_reconcile",m.incrementalReconcile],
  ];
  const lines=[
    "## JVMD daemon lifecycle",
    "",
    "### Machine-global index",
    "",
    "| Metric | machine cold | daemon restart | incremental reconcile | resident daemon |",
    "| --- | ---: | ---: | ---: | ---: |",
    "| Process → machine index ready | "+fmt(rows[0][1].processToMachineIndexReadyMs)+" | "+fmt(rows[1][1].processToMachineIndexReadyMs)+" | "+fmt(rows[2][1].processToMachineIndexReadyMs)+" | already paid |",
    "| Index reconciliation | "+fmt(rows[0][1].machineIndexReconcileMs)+" | "+fmt(rows[1][1].machineIndexReconcileMs)+" | "+fmt(rows[2][1].machineIndexReconcileMs)+" | 0 |",
    "| Artifacts discovered | "+rows[0][1].index.artifactsDiscovered+" | "+rows[1][1].index.artifactsDiscovered+" | "+rows[2][1].index.artifactsDiscovered+" | - |",
    "| Artifacts reused | "+rows[0][1].index.artifactsReused+" | "+rows[1][1].index.artifactsReused+" | "+rows[2][1].index.artifactsReused+" | - |",
    "| Artifacts hashed | "+rows[0][1].index.artifactsHashed+" | "+rows[1][1].index.artifactsHashed+" | "+rows[2][1].index.artifactsHashed+" | - |",
    "| Indexed publications | "+rows[0][1].index.indexedPublications+" | "+rows[1][1].index.indexedPublications+" | "+rows[2][1].index.indexedPublications+" | - |",
    "| Docs/source phase ms | "+fmt(rows[0][1].index.timings.docsMs)+" | "+fmt(rows[1][1].index.timings.docsMs)+" | "+fmt(rows[2][1].index.timings.docsMs)+" | 0 |",
    "",
    "### Resident daemon workspace sessions",
    "",
    "| Metric | first workspace open | workspace reopen |",
    "| --- | ---: | ---: |",
    "| Session open | "+fmt(sessions[0].sessionOpenMs)+" | "+fmt(sessions[1].sessionOpenMs)+" |",
    "| Resolver cold timing | "+fmt(sessions[0].workspaceResolution.reportedColdTimingMs)+" | "+fmt(sessions[1].workspaceResolution.reportedColdTimingMs)+" |",
    "| Project-model fast hits | "+sessions[0].workspaceResolution.projectModelFastHits+" | "+sessions[1].workspaceResolution.projectModelFastHits+" |",
    "| Workspace-index load | "+fmt(sessions[0].workspaceIndex.loadMs)+" | "+fmt(sessions[1].workspaceIndex.loadMs)+" |",
    "| Workspace-index ready | unavailable | unavailable |",
    "| Document admission | "+fmt(sessions[0].admission.wallMs)+" | "+fmt(sessions[1].admission.wallMs)+" |",
    "| Session open → first correct definition | "+fmt(sessions[0].sessionOpenToFirstCorrectResultMs)+" | "+fmt(sessions[1].sessionOpenToFirstCorrectResultMs)+" |",
    "| Definition first use | "+fmt(sessions[0].definition.firstUse.latencyMs)+" | "+fmt(sessions[1].definition.firstUse.latencyMs)+" |",
    "| Definition steady p50 | "+fmt(sessions[0].definition.steadyStats.p50Ms)+" | "+fmt(sessions[1].definition.steadyStats.p50Ms)+" |",
    "| Definition steady p95 | "+fmt(sessions[0].definition.steadyStats.p95Ms)+" | "+fmt(sessions[1].definition.steadyStats.p95Ms)+" |",
    "",
    "### Document admission attribution",
    "",
    "| Evidence | first workspace open | workspace reopen |",
    "| --- | ---: | ---: |",
    "| Mutation RPC ms | "+fmt(sessions[0].admission.topLevelDaemon.documentMutationRpcMs)+" | "+fmt(sessions[1].admission.topLevelDaemon.documentMutationRpcMs)+" |",
    "| Diagnostics RPC ms | "+fmt(sessions[0].admission.topLevelDaemon.diagnosticsRpcMs)+" | "+fmt(sessions[1].admission.topLevelDaemon.diagnosticsRpcMs)+" |",
    "| Adapter/other remainder ms | "+fmt(sessions[0].admission.topLevelDaemon.otherOrAdapterMs)+" | "+fmt(sessions[1].admission.topLevelDaemon.otherOrAdapterMs)+" |",
    "| Resolver cold calls | "+sessions[0].admission.resolverDelta.resolveCalls+" | "+sessions[1].admission.resolverDelta.resolveCalls+" |",
    "| Resolver fast hits | "+sessions[0].admission.resolverDelta.projectModelFastHits+" | "+sessions[1].admission.resolverDelta.projectModelFastHits+" |",
    "| Compiler query ms delta | "+fmt(sessions[0].admission.compilerAndSemanticEvidence.query_ms)+" | "+fmt(sessions[1].admission.compilerAndSemanticEvidence.query_ms)+" |",
    "| Compiler configure ms delta | "+fmt(sessions[0].admission.compilerAndSemanticEvidence.configure_ms)+" | "+fmt(sessions[1].admission.compilerAndSemanticEvidence.configure_ms)+" |",
    "| Semantic fact mutations | "+fmt(sessions[0].admission.compilerAndSemanticEvidence.semantic_fact_mutations)+" | "+fmt(sessions[1].admission.compilerAndSemanticEvidence.semantic_fact_mutations)+" |",
    "",
    "### Resident memory",
    "",
    "| State | first workspace open | workspace reopen |",
    "| --- | ---: | ---: |",
    "| Daemon baseline MB | "+fmt(sessions[0].memory.daemonMachineReadyKb/1024)+" | "+fmt(sessions[1].memory.daemonMachineReadyKb/1024)+" |",
    "| After session open MB | "+fmt((sessions[0].memory.daemonMachineReadyKb+sessions[0].memory.sessionIncrementKb)/1024)+" | "+fmt((sessions[1].memory.daemonMachineReadyKb+sessions[1].memory.sessionIncrementKb)/1024)+" |",
    "| Increment from machine-ready MB | "+fmt(sessions[0].memory.sessionIncrementKb/1024)+" | "+fmt(sessions[1].memory.sessionIncrementKb/1024)+" |",
    "| After steady MB | "+fmt(sessions[0].memory.afterSteadyKb/1024)+" | "+fmt(sessions[1].memory.afterSteadyKb/1024)+" |",
    "",
    "Local-change global dependency reindex observed: **"+String(sessions[1].localChange?.globalDependencyReindexObserved)+"**.",
    "",
  ];
  return lines.join("\n");
}

await main();
