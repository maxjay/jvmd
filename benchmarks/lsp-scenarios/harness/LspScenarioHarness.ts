import assert from "node:assert/strict";
import { spawn, type ChildProcess } from "node:child_process";
import { appendFileSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { isDeepStrictEqual } from "node:util";
import net from "node:net";
import { RpcClient } from "../../../shim/src/transport.ts";
import { PHASE_MODEL, elapsedMs, latencyStats, orderedMilestones } from "./phases.ts";
import {
  createMessageConnection,
  StreamMessageReader,
  StreamMessageWriter,
  type MessageConnection,
} from "vscode-jsonrpc/node";

export type Memory = { serverKb:number; adapterKb:number; totalKb:number };
export type Measurement<T> = {
  result:T;
  metrics:{ latencyMs:number; memory:{ before:Memory; after:Memory; peak:Memory } };
};
export type OperationSeries<T> = {
  firstUse:Measurement<T>;
  warmup:Measurement<T>[];
  steady:Measurement<T>[];
  stats:ReturnType<typeof latencyStats>;
  correctStats?:ReturnType<typeof latencyStats>;
};
export type ScenarioPayload = {
  legacy:Record<string,Measurement<unknown>|undefined>;
  operations:Record<string,OperationSeries<unknown>>;
  metadata?:Record<string,unknown>;
};
type RunningServer = {
  connection:MessageConnection;
  server:ChildProcess;
  adapter?:ChildProcess;
  milestones:Record<string,number>;
  metadata:Record<string,unknown>;
};
type DiagnosticRecord = { params:any; sequence:number; receivedNs:number };
type DiagnosticWaiter = {
  uri:string;
  version:number;
  afterSequence:number;
  resolve:(params:any)=>void;
  reject:(error:Error)=>void;
  timer:ReturnType<typeof setTimeout>;
};

function nowNs(){ return Number(process.hrtime.bigint()); }

export abstract class LspScenarioHarness {
  private static fixtureRoot:string;
  private static mode:"record"|"compare";
  private static serverId:"jvmd"|"jdtls";
  private static running:RunningServer;
  private static openDocuments=new Map<string,number>();
  private static diagnostics:DiagnosticRecord[]=[];
  private static diagnosticWaiters:DiagnosticWaiter[]=[];
  private static diagnosticSequence=0;
  private static milestones:Record<string,number>={};
  private static phaseMemory:Record<string,Memory>={};
  private static documentAdmissionStarted=false;

  abstract readonly id:string;
  abstract readonly name:string;
  protected abstract scenario():Promise<ScenarioPayload>;

  static async beforeAll(){
    assert(process.env.FIXTURE_ROOT,"FIXTURE_ROOT is required");
    this.fixtureRoot=path.resolve(process.env.FIXTURE_ROOT);
    this.mode=process.env.MODE==="record"?"record":"compare";
    this.serverId=(process.env.SERVER??"jvmd")==="jdtls"?"jdtls":"jvmd";
    this.running=await startServer(this.fixtureRoot);
    this.milestones={...this.running.milestones};
    this.diagnostics=[];this.diagnosticWaiters=[];this.diagnosticSequence=0;this.openDocuments.clear();
    this.phaseMemory={};this.documentAdmissionStarted=false;

    let serviceReadyResolve:()=>void=()=>{};
    const serviceReady=new Promise<void>(resolve=>serviceReadyResolve=resolve);
    this.running.connection.onNotification("language/status",(params:any)=>{
      if(params?.type==="ServiceReady"){
        this.milestones.service_ready=nowNs();
        serviceReadyResolve();
      }
    });
    this.running.connection.onNotification("textDocument/publishDiagnostics",(params:any)=>{
      const record={params,sequence:++this.diagnosticSequence,receivedNs:nowNs()};
      this.diagnostics.push(record);
      for(const waiter of [...this.diagnosticWaiters]){
        if(
          record.sequence>waiter.afterSequence
          &&params?.uri===waiter.uri
          &&(params?.version===undefined||params.version===waiter.version)
        ){
          clearTimeout(waiter.timer);
          this.diagnosticWaiters.splice(this.diagnosticWaiters.indexOf(waiter),1);
          waiter.resolve(params);
        }
      }
    });
    this.running.connection.onRequest("workspace/configuration",(params:any)=>(params?.items??[]).map(()=>({})));
    this.running.connection.onRequest("client/registerCapability",()=>null);
    this.running.connection.onRequest("client/unregisterCapability",()=>null);
    this.running.connection.onRequest("window/workDoneProgress/create",()=>null);
    this.running.connection.onRequest("workspace/applyEdit",()=>({applied:false}));
    this.running.connection.listen();

    const rootUri=pathToFileURL(this.fixtureRoot).href;
    this.milestones.initialize_sent=nowNs();
    await this.running.connection.sendRequest("initialize",{
      processId:process.pid,
      rootUri,
      workspaceFolders:[{uri:rootUri,name:"apache-maven"}],
      capabilities:{
        workspace:{configuration:true},
        textDocument:{
          publishDiagnostics:{versionSupport:true},
          completion:{completionItem:{snippetSupport:false}},
        },
      },
    });
    this.milestones.initialize_received=nowNs();
    this.running.connection.sendNotification("initialized",{});
    this.milestones.initialized_sent=nowNs();

    if(this.serverId==="jdtls")await serviceReady;
    this.phaseMemory.pre_document_admission=memory(this.running);
  }

  static async afterAll(){
    try{
      await this.running.connection.sendRequest("shutdown");
      this.running.connection.sendNotification("exit");
    }finally{
      this.running.connection.dispose();
      stop(this.running.adapter);
      stop(this.running.server);
    }
  }

  async execute(){
    try{
      const payload=await this.scenario();
      const verification=this.verify(payload);
      const completionCorrectness=verification.operationCorrectness.completion;
      if(payload.operations.completion&&completionCorrectness){
        payload.operations.completion.correctStats=latencyStats(
          payload.operations.completion.steady
            .filter((_,index)=>completionCorrectness.steady[index])
            .map(row=>row.metrics.latencyMs),
        );
      }
      const spawn=LspScenarioHarness.milestones.process_spawn;
      const firstUseEnd=LspScenarioHarness.milestones.first_use_finished;
      const diagnosticCold=spawn!==undefined&&firstUseEnd!==undefined?(firstUseEnd-spawn)/1e6:null;
      const report={
        schema:3,
        phaseModel:PHASE_MODEL,
        scenario:{id:this.id,name:this.name},
        server:LspScenarioHarness.serverId,
        lifecycle:this.lifecycleReport(),
        operations:payload.operations,
        legacy:payload.legacy,
        correctness:verification,
        coldEndToEnd:{
          diagnosticMs:diagnosticCold,
          correct:verification.operationCorrectness.completion?.firstUse??false,
          firstCorrectResultMs:(verification.operationCorrectness.completion?.firstUse??false)?diagnosticCold:null,
        },
        provenance:this.provenance(),
        metadata:payload.metadata??{},
      };
      const reportFile=process.env.REPORT_FILE
        ?path.resolve(process.env.REPORT_FILE)
        :path.resolve("benchmarks/lsp-scenarios/.state",LspScenarioHarness.serverId,this.id+"-report.json");
      mkdirSync(path.dirname(reportFile),{recursive:true});
      writeFileSync(reportFile,JSON.stringify(report,null,2)+"\n");
      writeSummary(report);
    }finally{
      await this.closeDocuments();
    }
  }

  protected beginDocumentAdmission(){
    if(!LspScenarioHarness.documentAdmissionStarted){
      LspScenarioHarness.documentAdmissionStarted=true;
      LspScenarioHarness.milestones.document_admission_started=nowNs();
    }
  }

  protected async endDocumentAdmission(){
    LspScenarioHarness.milestones.documents_admitted=nowNs();
    LspScenarioHarness.phaseMemory.documents_admitted=memory(LspScenarioHarness.running);
  }

  protected async open(relativePath:string){
    this.beginDocumentAdmission();
    const file=path.resolve(LspScenarioHarness.fixtureRoot,relativePath);
    const uri=pathToFileURL(file).href;
    const text=readFileSync(file,"utf8");
    LspScenarioHarness.openDocuments.set(uri,1);
    const sequence=LspScenarioHarness.diagnosticSequence;
    LspScenarioHarness.running.connection.sendNotification("textDocument/didOpen",{
      textDocument:{uri,languageId:"java",version:1,text},
    });
    await this.waitForDiagnostics(uri,1,sequence);
    return {uri,text,version:1};
  }

  protected async change(uri:string,text:string){
    this.beginDocumentAdmission();
    const version=(LspScenarioHarness.openDocuments.get(uri)??1)+1;
    LspScenarioHarness.openDocuments.set(uri,version);
    const sequence=LspScenarioHarness.diagnosticSequence;
    LspScenarioHarness.running.connection.sendNotification("textDocument/didChange",{
      textDocument:{uri,version},
      contentChanges:[{text}],
    });
    await this.waitForDiagnostics(uri,version,sequence);
    return version;
  }

  protected request<T>(method:string,params:unknown):Promise<T>{
    return LspScenarioHarness.running.connection.sendRequest(method,params);
  }

  protected async measure<T,U>(request:()=>Promise<T>,normalise:(value:T)=>U):Promise<Measurement<U>>{
    const running=LspScenarioHarness.running;
    const before=memory(running);let peak=before;
    const sampler=setInterval(()=>{peak=maxMemory(peak,memory(running));},20);
    const started=performance.now();
    try{
      const raw=await request();
      const latencyMs=performance.now()-started;
      const after=memory(running);peak=maxMemory(peak,after);
      return {result:normalise(raw),metrics:{latencyMs,memory:{before,after,peak}}};
    }finally{clearInterval(sampler);}
  }

  protected beginFirstUse(){
    if(LspScenarioHarness.milestones.first_use_started===undefined)
      LspScenarioHarness.milestones.first_use_started=nowNs();
  }

  protected finishFirstUse(){
    if(LspScenarioHarness.milestones.first_use_finished===undefined){
      LspScenarioHarness.milestones.first_use_finished=nowNs();
      LspScenarioHarness.phaseMemory.post_first_use=memory(LspScenarioHarness.running);
    }
  }

  protected async measureWarmupAndSteady<T,U>(request:()=>Promise<T>,normalise:(value:T)=>U){
    const warmup:Measurement<U>[]=[];
    for(let i=0;i<PHASE_MODEL.defaults.warmup;i++)warmup.push(await this.measure(request,normalise));
    LspScenarioHarness.phaseMemory.post_warmup=memory(LspScenarioHarness.running);
    const steady:Measurement<U>[]=[];
    for(let i=0;i<PHASE_MODEL.defaults.steady_samples;i++)steady.push(await this.measure(request,normalise));
    LspScenarioHarness.phaseMemory.post_steady=memory(LspScenarioHarness.running);
    LspScenarioHarness.phaseMemory.steady_peak=steady
      .map(row=>row.metrics.memory.peak)
      .reduce((peak,current)=>maxMemory(peak,current),{serverKb:0,adapterKb:0,totalKb:0});
    return {warmup,steady,stats:latencyStats(steady.map(row=>row.metrics.latencyMs))};
  }

  protected async measureSeries<T,U>(request:()=>Promise<T>,normalise:(value:T)=>U):Promise<OperationSeries<U>>{
    this.beginFirstUse();
    const firstUse=await this.measure(request,normalise);
    this.finishFirstUse();
    const rest=await this.measureWarmupAndSteady(request,normalise);
    return {firstUse,...rest};
  }

  private async waitForDiagnostics(uri:string,version:number,afterSequence:number){
    const existing=LspScenarioHarness.diagnostics.find(row=>
      row.sequence>afterSequence
      &&row.params?.uri===uri
      &&(row.params?.version===undefined||row.params.version===version)
    );
    if(existing)return existing.params;
    return new Promise<any>((resolve,reject)=>{
      const waiter:DiagnosticWaiter={
        uri,version,afterSequence,resolve,reject,
        timer:setTimeout(()=>{
          const index=LspScenarioHarness.diagnosticWaiters.indexOf(waiter);
          if(index>=0)LspScenarioHarness.diagnosticWaiters.splice(index,1);
          reject(new Error("Timed out waiting for diagnostics "+uri+" v"+version));
        },180000),
      };
      LspScenarioHarness.diagnosticWaiters.push(waiter);
    });
  }

  private async closeDocuments(){
    for(const uri of LspScenarioHarness.openDocuments.keys()){
      LspScenarioHarness.running.connection.sendNotification("textDocument/didClose",{textDocument:{uri}});
    }
    LspScenarioHarness.openDocuments.clear();
  }

  private verify(payload:ScenarioPayload){
    const expectedDir=path.resolve(process.env.EXPECTED_DIR??"benchmarks/lsp-scenarios/expected");
    const file=path.join(expectedDir,this.id+".json");
    const legacy=Object.fromEntries(Object.entries(payload.legacy).filter(([,value])=>value!==undefined));
    if(LspScenarioHarness.mode==="record"){
      mkdirSync(expectedDir,{recursive:true});
      writeFileSync(file,JSON.stringify(legacy,null,2)+"\n");
    }
    const expected:Record<string,Measurement<unknown>>=JSON.parse(readFileSync(file,"utf8"));
    const legacyCorrectness=Object.fromEntries(
      [...new Set([...Object.keys(expected),...Object.keys(legacy)])].map(name=>[
        name,
        expected[name]!==undefined&&legacy[name]!==undefined&&isDeepStrictEqual(legacy[name].result,expected[name].result),
      ]),
    );
    const completion=payload.operations.completion;
    const repeatedOracle=expected.repeated?.result??expected.first?.result;
    const operationCorrectness:any={};
    if(completion){
      operationCorrectness.completion={
        firstUse:isDeepStrictEqual(completion.firstUse.result,expected.first?.result),
        warmup:completion.warmup.map(row=>isDeepStrictEqual(row.result,repeatedOracle)),
        steady:completion.steady.map(row=>isDeepStrictEqual(row.result,repeatedOracle)),
      };
    }
    return {legacy:legacyCorrectness,operationCorrectness};
  }

  private lifecycleReport(){
    const origin=LspScenarioHarness.milestones.process_spawn;
    const m=LspScenarioHarness.milestones;
    assert(
      orderedMilestones(m,["process_spawn","initialize_received","documents_admitted","first_use_started","first_use_finished"]),
      "benchmark lifecycle milestones are out of order",
    );
    return {
      milestonesNs:m,
      milestonesMs:Object.fromEntries(Object.entries(m).map(([name,value])=>[name,elapsedMs(origin,value)])),
      initializeMs:(m.initialize_received-m.initialize_sent)/1e6,
      processToInitializeResponseMs:(m.initialize_received-m.process_spawn)/1e6,
      serviceReadyMs:m.service_ready===undefined?null:(m.service_ready-m.process_spawn)/1e6,
      machineIndexReadyMs:m.daemon_index_ready===undefined?null:(m.daemon_index_ready-m.process_spawn)/1e6,
      documentsAdmittedFromProcessMs:(m.documents_admitted-m.process_spawn)/1e6,
      documentAdmissionMs:(m.documents_admitted-m.document_admission_started)/1e6,
      sessionOpen:"not directly observable through the external jvmd-lsp process; see jvmd-machine-lifecycle report for exact native RPC timing",
      workspaceResolution:"not directly timestamped by CMP-01; see jvmd-machine-lifecycle resolver evidence",
      workspaceIndexReady:"unavailable for JVMD: local module refresh is asynchronous and no all-current barrier is exposed",
      memory:LspScenarioHarness.phaseMemory,
      nativeReadiness:{
        jdtlsServiceReady:m.service_ready===undefined?null:elapsedMs(origin,m.service_ready),
        jvmdMachineIndexReady:m.daemon_index_ready===undefined?null:elapsedMs(origin,m.daemon_index_ready),
        equivalence:"none claimed; these are server-native milestones at different architectural layers",
        targetQueried:false,
      },
      documentAdmission:{
        boundary:"publishDiagnostics observed for each current document version",
        diagnosticsWaited:true,
        mode:"settled editor admission",
      },
    };
  }

  private provenance(){
    return {
      server:LspScenarioHarness.serverId,
      serverRevision:LspScenarioHarness.serverId==="jvmd"?(process.env.GITHUB_SHA??"working-tree"):"JDTLS 1.61.0",
      node:process.version,
      jdk:process.env.JAVA_HOME??"system",
      fixture:{
        identity:"apache/maven@5cd1b60264101080c712accd605180a4bd9222e0",
        preparedBeforeServerStart:true,
      },
      lifecycle:{
        process:"fresh",
        daemon:LspScenarioHarness.serverId==="jvmd"?"fresh machine daemon":"fresh language-server process",
        machineIndex:LspScenarioHarness.serverId==="jvmd"?"empty benchmark state (machine_cold)":"n/a",
        workspace:"first open",
        localWorkspaceState:"fresh/unknown",
        documents:"opened after server-native readiness; admission is measured separately",
        semanticTarget:"not queried before first_use",
        diagnosticsWaitedBeforeFirstUse:true,
        warmupCount:PHASE_MODEL.defaults.warmup,
        steadySamples:PHASE_MODEL.defaults.steady_samples,
        filesystemCache:"OS cache uncontrolled and not flushed",
      },
      launch:LspScenarioHarness.running.metadata,
      runner:{platform:process.platform,arch:process.arch,cpus:os.cpus().length},
    };
  }
}

async function startServer(root:string):Promise<RunningServer>{
  return (process.env.SERVER??"jvmd")==="jdtls"?startJdtls():startJvmd(root);
}

async function startJvmd(root:string):Promise<RunningServer>{
  const state=path.resolve("benchmarks/lsp-scenarios/.state/jvmd");
  rmSync(state,{recursive:true,force:true});mkdirSync(state,{recursive:true});
  const socket=path.join(state,"jvmd.sock"),image=path.resolve("jvmd-dist/target/image");
  const env={...process.env,JVMD_SOCKET:socket,XDG_CACHE_HOME:path.join(state,"cache"),JVMD_CONFIG:path.join(state,"config.json")};
  writeFileSync(path.join(state,"config.json"),"{}\n");
  const milestones:Record<string,number>={process_spawn:nowNs()};
  const server=spawn(path.join(image,"bin/java"),[
    "-Djvmd.socket="+socket,
    "-Djvmd.state="+path.join(state,"state"),
    "-Djvmd.config="+path.join(state,"config.json"),
    "-cp",path.join(image,"lib/jvmd/*"),
    "dev.jvmd.dist.Application",
  ],{env,stdio:["ignore","ignore","inherit"]});
  await waitFor(()=>existsSync(socket),"JVMD socket");
  milestones.transport_available=nowNs();
  await waitForJvmdIndex(socket);
  milestones.daemon_index_ready=nowNs();
  const adapter=spawn(path.join(image,"bin/jvmd-lsp"),["--root",root,"--socket",socket],{env,stdio:["pipe","pipe","inherit"]});
  milestones.adapter_spawned=nowNs();
  return {
    connection:createMessageConnection(new StreamMessageReader(adapter.stdout!),new StreamMessageWriter(adapter.stdin!)),
    server,adapter,milestones,
    metadata:{mode:"distributed-image JVM plus LSP adapter; AOT cache not enabled",aotCacheUsed:false,residentDaemon:false},
  };
}

function startJdtls():RunningServer{
  assert(process.env.JDTLS_HOME,"JDTLS_HOME is required when SERVER=jdtls");
  const home=path.resolve(process.env.JDTLS_HOME);
  const launcher=readdirSync(path.join(home,"plugins")).find(name=>name.startsWith("org.eclipse.equinox.launcher_")&&name.endsWith(".jar"));
  assert(launcher,"JDTLS launcher not found");
  const state=path.resolve("benchmarks/lsp-scenarios/.state/jdtls");
  rmSync(state,{recursive:true,force:true});mkdirSync(state,{recursive:true});
  const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,"bin/java"):"java";
  const milestones:Record<string,number>={process_spawn:nowNs()};
  const server=spawn(java,[
    "-Declipse.application=org.eclipse.jdt.ls.core.id1",
    "-Dosgi.bundles.defaultStartLevel=4",
    "-Declipse.product=org.eclipse.jdt.ls.core.product",
    "-jar",path.join(home,"plugins",launcher),
    "-configuration",path.join(home,"config_linux"),
    "-data",state,
  ],{stdio:["pipe","pipe","inherit"]});
  milestones.transport_available=nowNs();
  return {
    connection:createMessageConnection(new StreamMessageReader(server.stdout!),new StreamMessageWriter(server.stdin!)),
    server,milestones,
    metadata:{mode:"fresh direct JDTLS process",aotCacheUsed:false,residentDaemon:false},
  };
}

async function waitForJvmdIndex(socketPath:string){
  const socket=await new Promise<net.Socket>((resolve,reject)=>{
    const connection=net.createConnection(socketPath);
    connection.once("connect",()=>resolve(connection));
    connection.once("error",reject);
  });
  const client=new RpcClient(socket),deadline=Date.now()+120000;
  try{
    while(Date.now()<deadline){
      const status=await client.call("daemon.status"),index=status?.result?.index;
      if(index?.phase==="ready"&&Number(index?.timings?.scans??0)>=1)return;
      await new Promise(resolve=>setTimeout(resolve,50));
    }
  }finally{client.close();}
  throw new Error("Timed out waiting for JVMD repository index");
}

function memory(running:RunningServer):Memory{
  const serverKb=rssKb(running.server.pid),adapterKb=rssKb(running.adapter?.pid);
  return {serverKb,adapterKb,totalKb:serverKb+adapterKb};
}
function rssKb(pid?:number){
  if(!pid)return 0;
  try{
    const status=readFileSync("/proc/"+pid+"/status","utf8");
    return Number(status.match(/^VmRSS:\s+(\d+)\s+kB/m)?.[1]??0);
  }catch{return 0;}
}
function maxMemory(a:Memory,b:Memory):Memory{
  return {
    serverKb:Math.max(a.serverKb,b.serverKb),
    adapterKb:Math.max(a.adapterKb,b.adapterKb),
    totalKb:Math.max(a.totalKb,b.totalKb),
  };
}
async function waitFor(predicate:()=>boolean,description:string){
  const deadline=Date.now()+10000;
  while(Date.now()<deadline){
    if(predicate())return;
    await new Promise(resolve=>setTimeout(resolve,25));
  }
  throw new Error("Timed out waiting for "+description);
}
function stop(process?:ChildProcess){if(!process||process.killed)return;process.kill("SIGTERM");}
function mb(kb:number){return (kb/1024).toFixed(1);}

function writeSummary(report:any){
  const correctness=report.correctness.operationCorrectness.completion;
  const lines=[
    "## "+report.scenario.id+" — "+report.server,
    "",
    "### Startup / readiness",
    "",
    "| Metric | ms |",
    "| --- | ---: |",
    "| Initialize request | "+report.lifecycle.initializeMs.toFixed(2)+" |",
    "| Process → initialize response | "+report.lifecycle.processToInitializeResponseMs.toFixed(2)+" |",
    "| JDTLS ServiceReady | "+(report.lifecycle.serviceReadyMs?.toFixed(2)??"n/a")+" |",
    "| JVMD machine index ready | "+(report.lifecycle.machineIndexReadyMs?.toFixed(2)??"n/a")+" |",
    "| Process → documents admitted | "+report.lifecycle.documentsAdmittedFromProcessMs.toFixed(2)+" |",
    "| Document admission interval | "+report.lifecycle.documentAdmissionMs.toFixed(2)+" |",
    "| Process → first-use response (diagnostic timing) | "+(report.coldEndToEnd.diagnosticMs?.toFixed(2)??"-")+" |",
    "",
    "### Completion",
    "",
    "| State | latency | correctness |",
    "| --- | ---: | --- |",
    "| first_use | "+(correctness.firstUse?report.operations.completion.firstUse.metrics.latencyMs.toFixed(2)+" ms":"diagnostic "+report.operations.completion.firstUse.metrics.latencyMs.toFixed(2)+" ms")+" | "+(correctness.firstUse?"correct":"INCORRECT")+" |",
    "| warmup | "+report.operations.completion.warmup.map((x:any)=>x.metrics.latencyMs.toFixed(2)).join(", ")+" ms | discarded |",
    "| steady | "+(correctness.steady.every(Boolean)?("p50 "+(report.operations.completion.correctStats?.p50Ms?.toFixed(2)??"-")+" / p95 "+(report.operations.completion.correctStats?.p95Ms?.toFixed(2)??"-")+" ms"):("diagnostic p50 "+(report.operations.completion.stats.p50Ms?.toFixed(2)??"-")+" / p95 "+(report.operations.completion.stats.p95Ms?.toFixed(2)??"-")+" ms"))+" | "+(correctness.steady.every(Boolean)?"correct":"INCORRECT")+" |",
    "",
    "### Memory",
    "",
    "| State | RSS MB |",
    "| --- | ---: |",
    "| Pre-document admission | "+mb(report.lifecycle.memory.pre_document_admission.totalKb)+" |",
    "| Documents admitted | "+mb(report.lifecycle.memory.documents_admitted.totalKb)+" |",
    "| After first use | "+mb(report.lifecycle.memory.post_first_use.totalKb)+" |",
    "| After steady | "+mb(report.lifecycle.memory.post_steady.totalKb)+" |",
    "| Steady peak | "+mb(report.lifecycle.memory.steady_peak.totalKb)+" |",
    "",
  ];
  console.log(lines.join("\n"));
  if(process.env.GITHUB_STEP_SUMMARY)appendFileSync(process.env.GITHUB_STEP_SUMMARY,lines.join("\n")+"\n");
}
