import assert from "node:assert/strict";
import {spawn, type ChildProcess} from "node:child_process";
import {mkdirSync,readFileSync,readdirSync,writeFileSync,openSync,closeSync,mkdtempSync,rmSync,copyFileSync} from "node:fs";
import path from "node:path";
import os from "node:os";
import net from "node:net";
import {now} from "./ProtocolClient.ts";
import {ProtocolClient} from "./ProtocolClient.ts";
import {sha} from "./fixture.ts";
import {captureAot} from "./aotEvidence.ts";
import {LifetimeResources} from "./lifetimeResources.ts";

export type LaunchOptions={server:"jvmd"|"jdtls";profile:"product"|"direct"|"pipe"|"custom";root:string;state:string;
  javaHome:string;image:string;jdtlsHome?:string;pipeBuild?:string;customCommand?:string[];repository:string;trace?:boolean;environment?:Record<string,string>;journalDirectory?:string;reuseState?:boolean};
export type Launch={client:ProtocolClient;metadata:any;stop:()=>Promise<void>};
const EXPORTS=["api","util","code","main","platform"].map(p=>"--add-exports=jdk.compiler/com.sun.tools.javac."+p+"=ALL-UNNAMED");

export async function launch(o:LaunchOptions):Promise<Launch> {
  const started=now();
  mkdirSync(o.state,{recursive:true});mkdirSync(o.repository,{recursive:true});
  const stderr=openSync(path.join(o.state,"stderr.log"),"a");
  const socketDirectory=mkdtempSync(path.join(os.tmpdir(),"jbm-"));
  const config=path.join(o.state,"config.json"),env={...process.env,...o.environment,JVMD_CONFIG:config,
    XDG_CACHE_HOME:path.join(o.state,"cache"),JVMD_SOCKET:path.join(socketDirectory,"daemon.sock"),JAVA_TOOL_OPTIONS:"-Xmx1024m"};
  writeFileSync(config,JSON.stringify({jdk_home:o.javaHome,m2_repo:o.repository,index_on_start:true,heap_ceiling_mb:1024}));
  let command:string[],daemon:ChildProcess|undefined;
  const metadata:any={server:o.server,profile:o.profile,process:"fresh",machineState:o.reuseState?"persisted":"empty",
    launchStartedNs:String(started),stateDirectory:o.state,workspaceRoot:o.root,filesystemCache:"OS cache uncontrolled",repository:o.repository,aot:{status:"unavailable",reason:"runtime acceptance not yet observed"}};
  const lifetime=new LifetimeResources(path.join(o.journalDirectory??path.dirname(o.state),"resources"));
  metadata.lifetimeResources={schemaVersion:1,start:lifetime.start};
  const saveMetadata=()=>writeFileSync(path.join(o.state,o.reuseState?"reopen-launch.json":"launch.json"),JSON.stringify(metadata,null,2)+"\n");
  let daemonExit:Promise<void>|undefined;
  const stopDaemon=async()=>{
    if(!daemon||daemon.exitCode!==null||daemon.signalCode!==null)return;
    metadata.daemonProcessLifecycle.push({event:"termination_requested",signal:"SIGTERM",timeNs:String(now())});daemon.kill("SIGTERM");
    let timer:any;await Promise.race([daemonExit,new Promise<void>(resolve=>{timer=setTimeout(()=>{
      metadata.daemonProcessLifecycle.push({event:"forced_termination",signal:"SIGKILL",timeNs:String(now())});daemon!.kill("SIGKILL");resolve();
    },5000);})]);clearTimeout(timer);
    // Final accounting requires observed exit; a remaining descendant invalidates it.
    let finalTimer:any;await Promise.race([daemonExit,new Promise<void>(resolve=>{finalTimer=setTimeout(resolve,1000);})]);clearTimeout(finalTimer);
  };
  const finishResources=()=>{try{metadata.lifetimeResources.result=lifetime.finish();if(o.server==="jvmd"&&o.profile==="product")metadata.aot=captureAot(o.state,lifetime.output,o.image);saveMetadata();}finally{rmSync(socketDirectory,{recursive:true,force:true});closeSync(stderr);}};
  try{
  if(o.customCommand){
    assert.equal(o.profile,"custom","explicit command requires custom diagnostic profile");command=o.customCommand;
    metadata.performanceClaimsAllowed=false;
  }else if(o.server==="jdtls"){
    assert(o.jdtlsHome,"JDTLS_HOME required");
    const configuration=path.join(o.state,"equinox-config");mkdirSync(configuration,{recursive:true});
    if(!o.reuseState)copyFileSync(path.join(o.jdtlsHome,"config_linux/config.ini"),path.join(configuration,"config.ini"));
    const launcher=readdirSync(path.join(o.jdtlsHome,"plugins")).find(n=>/^org\.eclipse\.equinox\.launcher_.*\.jar$/u.test(n));
    assert(launcher,"JDTLS launcher missing");
    command=[path.join(o.javaHome,"bin/java"),"-Xmx1024m","-Declipse.application=org.eclipse.jdt.ls.core.id1","-Dosgi.install.area="+o.jdtlsHome,
      "-Dosgi.bundles.defaultStartLevel=4","-Declipse.product=org.eclipse.jdt.ls.core.product","-Dlog.level=WARNING",
      "--add-modules=ALL-SYSTEM","--add-opens","java.base/java.util=ALL-UNNAMED","--add-opens","java.base/java.lang=ALL-UNNAMED",
      "-jar",path.join(o.jdtlsHome,"plugins",launcher),"-configuration",configuration,"-data",path.join(o.state,"workspace")];
    metadata.launcherSha256=sha(readFileSync(path.join(o.jdtlsHome,"plugins",launcher)));
    metadata.aot={status:"not_applicable"};
  }else if(o.profile==="pipe"){
    assert(o.pipeBuild,"--pipe-build required for pipe profile");
    const build=JSON.parse(readFileSync(o.pipeBuild,"utf8"));
    for(const [file,digest] of Object.entries<string>(build.sources??{}))assert.equal(sha(readFileSync(file)),digest,"pipe build source changed: "+file);
    command=[path.join(o.javaHome,"bin/java"),"-Xmx1024m",...EXPORTS,"--enable-native-access=ALL-UNNAMED",
      "-Djvmd.config="+config,"-Djvmd.state="+path.join(o.state,"store"),"-Djvmd.resolvers="+path.join(o.image,"lib/jvmd/resolvers")];
    if(o.trace)command.push("-Djvmd.trace=true","-XX:StartFlightRecording=filename="+path.join(o.state,"server.jfr")+",settings=profile,dumponexit=true",
      "-XX:FlightRecorderOptions=stackdepth=128","-Xlog:jfr*=off");
    command.push("-cp",build.classpath,"dev.jvmd.benchmark.StdioApplication");
    const bridge=path.join(o.state,"bridge.json");
    writeFileSync(bridge,JSON.stringify({repo:process.cwd(),root:o.root,command,...(o.trace?{trace:{workflow:path.basename(o.state),invocation:"case",revision:"fixture"}}:{})}));
    command=[process.execPath,path.resolve("benchmarks/workspaces/bridge.ts"),bridge];
    metadata.aot={status:"not_applicable",reason:"explicit diagnostic pipe profile"};
    metadata.transport="existing benchmark pipe adapter; does not prove Unix daemon lifecycle";
    metadata.build=build;
  }else{
    assert(["product","direct"].includes(o.profile),"invalid JVMD launch profile");
    const originalDaemonCommand=o.profile==="product"?[path.join(o.image,"bin/jvmd")]:[
      path.join(o.image,"bin/java"),"-XX:AOTMode=off","-Djvmd.config="+config,"-Djvmd.socket="+env.JVMD_SOCKET,
      "-Djvmd.state="+path.join(o.state,"store"),"-cp",path.join(o.image,"lib/jvmd/*"),"dev.jvmd.dist.Application"];
    const daemonCommand=lifetime.command(originalDaemonCommand,"server");
    metadata.originalDaemonCommand=originalDaemonCommand;metadata.daemonProcessLifecycle=[];
    daemon=spawn(daemonCommand[0],daemonCommand.slice(1),{env,stdio:["ignore",stderr,stderr]});lifetime.track(daemon);
    daemon.once("spawn",()=>metadata.daemonProcessLifecycle.push({event:"spawned",pid:daemon!.pid,timeNs:String(now())}));
    daemonExit=new Promise(resolve=>{daemon!.once("exit",(code,signal)=>{metadata.daemonProcessLifecycle.push({event:"process_exit",pid:daemon!.pid,code,signal,timeNs:String(now())});resolve();});daemon!.once("error",error=>{metadata.daemonProcessLifecycle.push({event:"process_error",message:error.message,timeNs:String(now())});resolve();});});
    // Only a transport connection is tested here. No target query or index barrier
    // warms the workspace. Waiting also prevents the shim from auto-starting a rival daemon.
    let launchError:Error|undefined;daemon.once("error",error=>{launchError=error;});
    try{
      const deadline=Date.now()+30000;
      for(;;){
        if(launchError)throw launchError;
        if(daemon.exitCode!==null)throw new Error("daemon exited before transport: "+daemon.exitCode);
        try{await new Promise<void>((resolve,reject)=>{const connection=net.createConnection(env.JVMD_SOCKET);connection.once("connect",()=>{connection.end();resolve();});connection.once("error",reject);});break;}
        catch(error){if(!["ENOENT","ECONNREFUSED"].includes((error as NodeJS.ErrnoException).code??""))throw error;}
        if(Date.now()>=deadline)throw new Error("daemon transport deadline exceeded");
        await new Promise(resolve=>setTimeout(resolve,10));
      }
      metadata.transportAvailableNs=String(now());
    }catch(error){throw error;}
    command=[path.join(o.image,"bin/jvmd-lsp"),"--root",o.root,"--socket",env.JVMD_SOCKET];
    metadata.daemonCommand=daemonCommand;
    if(o.profile==="direct")metadata.aot={status:"not_applicable",reason:"explicitly disabled diagnostic profile"};
  }
  metadata.originalCommand=command;command=lifetime.command(command,daemon?"bridge":"server");
  metadata.command=command;metadata.environment={...o.environment,JAVA_TOOL_OPTIONS:env.JAVA_TOOL_OPTIONS,XDG_CACHE_HOME:env.XDG_CACHE_HOME,JVMD_SOCKET:env.JVMD_SOCKET};metadata.javaHome=o.javaHome;metadata.trace=!!o.trace;
  saveMetadata();
  const client=new ProtocolClient(command,{env,stderr,journalDirectory:o.journalDirectory??path.dirname(o.state)});
  lifetime.track(client.child);
  daemon?.on("error",e=>{client.protocolErrors.push("daemon launch: "+e.message);client.child.kill();});
  return {client,metadata,stop:async()=>{
    try{await client.shutdown();}finally{
      try{await stopDaemon();}finally{finishResources();}
    }
  }};
  }catch(error){try{await stopDaemon();}finally{finishResources();}throw error;}
}
