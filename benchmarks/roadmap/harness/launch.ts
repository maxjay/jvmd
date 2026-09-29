import assert from "node:assert/strict";
import {spawn,type ChildProcess} from "node:child_process";
import {copyFileSync,mkdirSync,mkdtempSync,openSync,closeSync,readdirSync,rmSync,writeFileSync} from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import {ProtocolClient} from "./ProtocolClient.ts";
import {AllocationProbe,agentJar} from "./allocation.ts";

export type LaunchOptions={server:"jvmd"|"jdtls";root:string;state:string;javaHome:string;image:string;jdtlsHome?:string;
  repository:string;environment?:Record<string,string>;reuseState?:boolean;customCommand?:string[]};
export type Launch={client:ProtocolClient;allocation:AllocationProbe;startedMs:number;stop:()=>Promise<string|undefined>};

// Both servers run on the same JDK with the same heap ceiling and the allocation agent.
const HEAP="-Xmx1024m";
const JVMD_EXPORTS=["api","util","code","main","platform"].map(p=>"--add-exports=jdk.compiler/com.sun.tools.javac."+p+"=ALL-UNNAMED");

export async function launch(o:LaunchOptions):Promise<Launch>{
  const started=performance.now();
  mkdirSync(o.state,{recursive:true});mkdirSync(o.repository,{recursive:true});
  const stderr=openSync(path.join(o.state,"stderr.log"),"a"),sockets=mkdtempSync(path.join(os.tmpdir(),"jrm-"));
  const probeSocket=path.join(sockets,"alloc.sock"),agent=o.customCommand?[]:["-javaagent:"+agentJar(o.javaHome)+"="+probeSocket];
  const env={...process.env,...o.environment};
  let command:string[],daemon:ChildProcess|undefined;
  if(o.customCommand)command=o.customCommand; // Test seam: a fake peer; allocation is unavailable.
  else if(o.server==="jdtls"){
    assert(o.jdtlsHome,"--jdtls-home required");
    const configuration=path.join(o.state,"equinox-config");mkdirSync(configuration,{recursive:true});
    if(!o.reuseState)copyFileSync(path.join(o.jdtlsHome,"config_linux/config.ini"),path.join(configuration,"config.ini"));
    const launcher=readdirSync(path.join(o.jdtlsHome,"plugins")).find(n=>/^org\.eclipse\.equinox\.launcher_.*\.jar$/u.test(n));
    assert(launcher,"JDTLS launcher missing");
    command=[path.join(o.javaHome,"bin/java"),HEAP,...agent,"-Declipse.application=org.eclipse.jdt.ls.core.id1","-Dosgi.install.area="+o.jdtlsHome,
      "-Dosgi.bundles.defaultStartLevel=4","-Declipse.product=org.eclipse.jdt.ls.core.product","-Dlog.level=WARNING",
      "--add-modules=ALL-SYSTEM","--add-opens","java.base/java.util=ALL-UNNAMED","--add-opens","java.base/java.lang=ALL-UNNAMED",
      "-jar",path.join(o.jdtlsHome,"plugins",launcher),"-configuration",configuration,"-data",path.join(o.state,"workspace")];
  }else{
    // The daemon runs on the full JDK rather than the jlink image, which omits jdk.management (allocation counters).
    const config=path.join(o.state,"config.json"),socket=path.join(sockets,"daemon.sock");
    writeFileSync(config,JSON.stringify({jdk_home:o.javaHome,m2_repo:o.repository,index_on_start:true,heap_ceiling_mb:1024}));
    const daemonCommand=[path.join(o.javaHome,"bin/java"),HEAP,...agent,...JVMD_EXPORTS,"--enable-native-access=ALL-UNNAMED",
      "-Djvmd.config="+config,"-Djvmd.socket="+socket,"-Djvmd.state="+path.join(o.state,"store"),"-Djvmd.resolvers="+path.join(o.image,"lib/jvmd/resolvers"),
      "-cp",path.join(o.image,"lib/jvmd/*"),"dev.jvmd.dist.Application"];
    daemon=spawn(daemonCommand[0],daemonCommand.slice(1),{env,stdio:["ignore",stderr,stderr]});
    await waitForSocket(socket,daemon);
    Object.assign(env,{JVMD_CONFIG:config,JVMD_SOCKET:socket});
    command=[path.join(o.image,"bin/jvmd-lsp"),"--root",o.root,"--socket",socket];
  }
  const client=new ProtocolClient(command,{env,stderr});
  const allocation=o.customCommand?new AllocationProbe():await AllocationProbe.connect(probeSocket);
  return {client,allocation,startedMs:started,stop:async()=>{
    let problem:string|undefined;
    // A short exit grace: a slow or failed exit is reported, but never discards the answers already checked.
    try{await client.shutdown(5000);}catch(error){problem=String(error);}
    allocation.close();
    if(daemon&&daemon.exitCode===null){daemon.kill("SIGTERM");await Promise.race([new Promise(r=>daemon!.once("exit",r)),new Promise(r=>setTimeout(r,5000))]);daemon.kill("SIGKILL");}
    rmSync(sockets,{recursive:true,force:true});closeSync(stderr);
    return problem;
  }};
}

async function waitForSocket(socket:string,daemon:ChildProcess){
  const deadline=Date.now()+30000;
  while(Date.now()<deadline){
    if(daemon.exitCode!==null)throw new Error("JVMD daemon exited during startup: "+daemon.exitCode);
    try{await new Promise<void>((resolve,reject)=>{const c=net.createConnection(socket);c.once("connect",()=>{c.end();resolve();});c.once("error",reject);});return;}
    catch{await new Promise(r=>setTimeout(r,10));}
  }
  throw new Error("JVMD daemon socket did not open within 30 s");
}
