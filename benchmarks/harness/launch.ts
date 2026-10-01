import assert from "node:assert/strict";
import {spawn,spawnSync,type ChildProcess} from "node:child_process";
import {copyFileSync,mkdirSync,mkdtempSync,openSync,closeSync,readdirSync,rmSync,writeFileSync} from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import {ProtocolClient} from "./ProtocolClient.ts";
import {AllocationProbe,agentJar} from "./allocation.ts";
import {RpcClient} from "../../shim/src/transport.ts";

export type Launch={client:ProtocolClient;allocation:AllocationProbe;startedMs:number;stop:(options?:{closeSession?:boolean})=>Promise<string|undefined>};

// Both servers run on the same JDK with the same heap ceiling and the allocation agent.
const HEAP="-Xmx1024m";
/** Real projects have non-ASCII paths; without a UTF-8 locale a JVM cannot even stat them. */
const UTF8={LANG:"C.UTF-8",LC_ALL:"C.UTF-8"};
const JVMD_EXPORTS=["api","util","code","main","platform"].map(p=>"--add-exports=jdk.compiler/com.sun.tools.javac."+p+"=ALL-UNNAMED");

/**
 * JVMD as users run it: one resident daemon per machine, its dependency index built once from the
 * local Maven repository, and a jvmd-lsp adapter per editor. The daemon runs on the full JDK rather
 * than the jlink image, which omits jdk.management (the allocation counters).
 */
export class JvmdDaemon {
  process:ChildProcess;socket:string;config:string;allocation:AllocationProbe;control:RpcClient;
  /** Launch to READY: the daemon prints READY once its machine index of the repository is built. */
  readyMs:number;
  private dir:string;private stderr:number;
  private constructor(fields:Record<string,any>){Object.assign(this,fields);}
  /** jfr: a JFR recording of the whole daemon (allocation samples at 20,000/s), dumped to this file on stop. */
  jfr?:string;javaHome?:string;
  static async start(o:{javaHome:string;image:string;state:string;repository:string;jfr?:string;jfrThrottle?:string;heap?:string}){
    mkdirSync(o.state,{recursive:true});
    const dir=mkdtempSync(path.join(os.tmpdir(),"jvmd-bench-")),socket=path.join(dir,"daemon.sock"),probe=path.join(dir,"alloc.sock");
    const config=path.join(o.state,"config.json");
    writeFileSync(config,JSON.stringify({jdk_home:o.javaHome,m2_repo:o.repository,index_on_start:true,heap_ceiling_mb:1024}));
    const stderr=openSync(path.join(o.state,"daemon.log"),"a"),started=performance.now();
    const recording=o.jfr?["-XX:StartFlightRecording:name=bench,settings=profile,maxsize=4g,jdk.ObjectAllocationSample#throttle="+(o.jfrThrottle??"20000/s")]:[];
    const child=spawn(path.join(o.javaHome,"bin/java"),[o.heap?"-Xmx"+o.heap:HEAP,...recording,"-javaagent:"+agentJar(o.javaHome)+"="+probe,...JVMD_EXPORTS,"--enable-native-access=ALL-UNNAMED",
      "-Djvmd.config="+config,"-Djvmd.socket="+socket,"-Djvmd.state="+path.join(o.state,"store"),"-Djvmd.resolvers="+path.join(o.image,"lib/jvmd/resolvers"),
      "-Djvmd.index.scan.initial_delay_seconds=0","-cp",path.join(o.image,"lib/jvmd/*"),"dev.jvmd.dist.Application"],{stdio:["ignore","pipe",stderr],env:{...process.env,...UTF8}});
    await new Promise<void>((resolve,reject)=>{
      let out="";const timer=setTimeout(()=>reject(new Error("JVMD daemon was not READY within 10 minutes")),600000);
      child.stdout!.on("data",chunk=>{out+=chunk;if(/^READY /mu.test(out)){clearTimeout(timer);resolve();}});
      child.once("exit",code=>{clearTimeout(timer);reject(new Error("JVMD daemon exited during startup: "+code));});
    });
    // Never outlive the harness, even when it crashes.
    const kill=()=>{if(child.exitCode===null)child.kill("SIGKILL");};process.once("exit",kill);child.once("exit",()=>process.removeListener("exit",kill));
    const readyMs=performance.now()-started;
    const control=new RpcClient(await connect(socket)),allocation=await AllocationProbe.connect(probe);
    return new JvmdDaemon({process:child,socket,config,allocation,control,readyMs,dir,stderr,jfr:o.jfr,javaHome:o.javaHome});
  }
  alive(){return this.process.exitCode===null&&this.process.signalCode===null;}
  /** Control calls are short: a hung daemon is reported and killed, never waited on. */
  private async call(method:string,params:any={}){
    const r=await this.control.raw(method,params,15000);if(r.error)throw new Error(method+": "+r.error.message);return r.result;
  }
  /** Disposes one workspace session so cases never share state; the machine index stays warm. */
  async closeSession(root:string){
    const status=await this.call("daemon.status");
    for(const s of status.result?.sessions??[])if(path.resolve(s.root)===path.resolve(root))await this.call("session.close",{session:s.session});
  }
  async status(){return (await this.call("daemon.status")).result;}
  /** Evidence for a hang: every thread's stack, written next to the daemon's log. */
  threadDump(javaHome:string,file:string){
    const r=spawnSync(path.join(javaHome,"bin/jcmd"),[String(this.process.pid),"Thread.print"],{encoding:"utf8",timeout:30000});
    writeFileSync(file,r.stdout||String(r.stderr||r.error));return file;
  }
  async stop(){
    if(this.jfr&&this.alive()){
      const r=spawnSync(path.join(this.javaHome!,"bin/jcmd"),[String(this.process.pid),"JFR.dump","name=bench","filename="+this.jfr],{encoding:"utf8",timeout:120000});
      if(r.status!==0)console.error("JFR.dump failed: "+(r.stderr||r.stdout));
    }
    try{await this.call("daemon.shutdown");}catch{/* exiting or hung */}
    this.control.close();this.allocation.close();
    const exited=new Promise(r=>{if(!this.alive())r(undefined);else this.process.once("exit",r);});
    await Promise.race([exited,new Promise(r=>setTimeout(r,10000))]);
    // A killed daemon still holds its store until the process is gone: the next daemon on the same
    // state (a restart) must not start before then.
    if(this.alive()){this.process.kill("SIGKILL");await exited;}
    closeSync(this.stderr);rmSync(this.dir,{recursive:true,force:true});
  }
}

export type LaunchOptions={server:"jvmd"|"jdtls";root:string;state:string;javaHome:string;jdtlsHome?:string;daemon?:JvmdDaemon;image?:string;
  environment?:Record<string,string>;reuseState?:boolean;customCommand?:string[]};

export async function launch(o:LaunchOptions):Promise<Launch>{
  const started=performance.now();mkdirSync(o.state,{recursive:true});
  const stderr=openSync(path.join(o.state,"stderr.log"),"a"),env={...process.env,...UTF8,...o.environment};
  let command:string[],allocation:AllocationProbe,probeDir:string|undefined;
  if(o.customCommand){command=o.customCommand;allocation=new AllocationProbe();} // Test seam: a scripted peer, no allocation.
  else if(o.server==="jvmd"){
    assert(o.daemon&&o.image,"JVMD needs a running daemon and its image");
    Object.assign(env,{JVMD_CONFIG:o.daemon.config,JVMD_SOCKET:o.daemon.socket});
    command=[path.join(o.image,"bin/jvmd-lsp"),"--root",o.root,"--socket",o.daemon.socket];allocation=o.daemon.allocation;
  }else{
    assert(o.jdtlsHome,"--jdtls-home required");
    probeDir=mkdtempSync(path.join(os.tmpdir(),"jdtls-bench-"));const probe=path.join(probeDir,"alloc.sock");
    const configuration=path.join(o.state,"equinox-config");mkdirSync(configuration,{recursive:true});
    if(!o.reuseState)copyFileSync(path.join(o.jdtlsHome,"config_linux/config.ini"),path.join(configuration,"config.ini"));
    const launcher=readdirSync(path.join(o.jdtlsHome,"plugins")).find(n=>/^org\.eclipse\.equinox\.launcher_.*\.jar$/u.test(n));
    assert(launcher,"JDTLS launcher missing");
    command=[path.join(o.javaHome,"bin/java"),HEAP,"-javaagent:"+agentJar(o.javaHome)+"="+probe,"-Declipse.application=org.eclipse.jdt.ls.core.id1","-Dosgi.install.area="+o.jdtlsHome,
      "-Dosgi.bundles.defaultStartLevel=4","-Declipse.product=org.eclipse.jdt.ls.core.product","-Dlog.level=WARNING",
      "--add-modules=ALL-SYSTEM","--add-opens","java.base/java.util=ALL-UNNAMED","--add-opens","java.base/java.lang=ALL-UNNAMED",
      "-jar",path.join(o.jdtlsHome,"plugins",launcher),"-configuration",configuration,"-data",path.join(o.state,"workspace")];
    const client=new ProtocolClient(command,{env,stderr});
    allocation=await AllocationProbe.connect(probe);
    return finish(client);
  }
  return finish(new ProtocolClient(command,{env,stderr}));
  function finish(client:ProtocolClient):Launch{
    return {client,allocation,startedMs:started,stop:async({closeSession=true}={})=>{
      let problem:string|undefined;
      // A short exit grace: a slow or failed exit is reported, but never discards the answers already checked.
      try{await client.shutdown(5000);}catch(error){problem=String(error);}
      if(o.server==="jvmd"&&o.daemon&&!o.customCommand){
        if(closeSession&&o.daemon.alive())try{await o.daemon.closeSession(o.root);}catch(error){problem??="session.close: "+String(error);}
      }else allocation.close();
      if(probeDir)rmSync(probeDir,{recursive:true,force:true});closeSync(stderr);
      return problem;
    }};
  }
}

async function connect(socket:string){
  return new Promise<net.Socket>((resolve,reject)=>{const c=net.createConnection(socket);c.once("connect",()=>{c.removeListener("error",reject);resolve(c);});c.once("error",reject);});
}
