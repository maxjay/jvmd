import assert from "node:assert/strict";
import {spawn,type ChildProcess} from "node:child_process";
import {mkdirSync,mkdtempSync,writeFileSync,readFileSync,appendFileSync,openSync,closeSync,rmSync,existsSync,renameSync} from "node:fs";
import path from "node:path";
import os from "node:os";
import net from "node:net";
import {AsyncLocalStorage} from "node:async_hooks";
import {RpcClient,encode} from "../../../shim/src/transport.ts";
import {ProtocolClient,now} from "./ProtocolClient.ts";
import {sha} from "./fixture.ts";
import {LifetimeResources} from "./lifetimeResources.ts";
export type NativeOptions={profile:"product"|"direct"|"pipe";image:string;javaHome:string;pipeBuild?:string;repository:string;state:string;output:string;trace:boolean;heapMb:number};
const delay=(ms:number)=>new Promise(resolve=>setTimeout(resolve,ms));
const write=(file:string,value:any)=>writeFileSync(file,JSON.stringify(value,null,2)+"\n");
export class NativeDaemon {
  options:NativeOptions;process!:ChildProcess;pipe?:ProtocolClient;control?:RpcClient;clients:RpcClient[]=[];calls:any[]=[];epoch:string;phase="launch";
  startedNs=now();socketDirectory:string;socket:string;stderr:number;monitor?:ChildProcess;exited!:Promise<number|null>;traceContext=new AsyncLocalStorage<any>();
  closed=false;additionalPids=new Set<number>();
  lifetime?:LifetimeResources;
  constructor(options:NativeOptions){this.options=options;mkdirSync(options.output,{recursive:true});mkdirSync(options.state,{recursive:true});this.socketDirectory=mkdtempSync(path.join(os.tmpdir(),"jbl-"));this.socket=path.join(this.socketDirectory,"d.sock");this.stderr=openSync(path.join(options.output,"stderr.log"),"a");this.epoch=path.basename(options.output)+":"+this.startedNs;}
  static async start(options:NativeOptions){const d=new NativeDaemon(options);try{await d.launch();return d;}catch(error){await d.close().catch(()=>{});throw error;}}
  private async launch(){
    const o=this.options,config=path.join(o.output,"config.json"),recording=path.join(o.output,"server.jfr");
    write(config,{jdk_home:o.javaHome,m2_repo:o.repository,index_on_start:true,heap_ceiling_mb:o.heapMb,idle_timeout:3600});
    const env:NodeJS.ProcessEnv={...process.env,JVMD_CONFIG:config,JVMD_SOCKET:this.socket,XDG_CACHE_HOME:o.state};
    const flags=["-Xmx"+o.heapMb+"m",...(o.trace?["-Djvmd.trace=true","-Djvmd.benchmark.workflow=lifecycle","-XX:StartFlightRecording=filename="+recording+",settings=profile,dumponexit=true","-XX:FlightRecorderOptions=stackdepth=128","-Xlog:jfr*=off"]:[])];
    let command:string[];
    if(o.profile==="product"){env.JAVA_TOOL_OPTIONS=flags.join(" ");command=[path.join(o.image,"bin/jvmd")];}
    else{command=[path.join(o.javaHome,"bin/java"),...flags,"-XX:AOTMode=off","--enable-native-access=ALL-UNNAMED",...(["api","util","code","main","platform"].map(x=>"--add-exports=jdk.compiler/com.sun.tools.javac."+x+"=ALL-UNNAMED")),
      "-Djvmd.config="+config,"-Djvmd.state="+path.join(o.state,"jvmd"),"-Djvmd.socket="+this.socket,"-Djvmd.resolvers="+path.join(o.image,"lib/jvmd/resolvers")];
      if(o.profile==="pipe"){assert(o.pipeBuild,"pipe build required");const build=JSON.parse(readFileSync(o.pipeBuild,"utf8"));for(const [file,hash] of Object.entries(build.sources))assert.equal(sha(readFileSync(file)),hash,"pipe source changed");command.push("-cp",build.classpath,"dev.jvmd.benchmark.StdioApplication");}
      else command.push("-cp",path.join(o.image,"lib/jvmd/*"),"dev.jvmd.dist.Application");
    }
    this.lifetime=new LifetimeResources(o.output);const originalCommand=command;command=this.lifetime.command(command,"server");
    write(path.join(o.output,"launch.json"),{schemaVersion:1,command,originalCommand,profile:o.profile,epoch:this.epoch,repository:o.repository,state:o.state,heapMb:o.heapMb,trace:o.trace,launchStartedNs:String(this.startedNs),lifetimeResources:this.lifetime.start,limitations:o.profile==="pipe"?["serialized pipe adapter; no Unix lifecycle or scheduler proof"]:[]});
    if(o.profile==="pipe"){this.pipe=new ProtocolClient(command,{env,stderr:this.stderr,journalDirectory:o.output});this.process=this.pipe.child;this.exited=this.pipe.exited;}
    else{this.process=spawn(command[0],command.slice(1),{env,stdio:["ignore",this.stderr,this.stderr]});this.exited=new Promise(resolve=>{this.process.once("exit",resolve);this.process.once("error",()=>resolve(null));});}
    this.lifetime.track(this.process);this.recordResourceRoots();
    if(this.process.pid)this.monitor=spawn("python3",[path.resolve("benchmarks/workspaces/resources.py"),"--pid",String(this.process.pid),"--roots-file",path.join(o.output,"resource-roots.json"),"--output",o.output,"--stop-file",path.join(o.output,"monitor.stop")],{stdio:["ignore",this.stderr,this.stderr]});
    if(o.profile!=="pipe"){let failure:any;this.process.once("error",e=>{failure=e;});
      const deadline=Date.now()+30000;for(;;){if(failure)throw failure;if(this.process.exitCode!==null)throw new Error("daemon exited before transport: "+this.process.exitCode);
        try{this.control=await this.connect();break;}catch(error){if(!["ENOENT","ECONNREFUSED"].includes((error as any).code))throw error;}if(Date.now()>deadline)throw new Error("transport timeout");await delay(10);}}
    write(path.join(o.output,"process.json"),{schemaVersion:1,pid:this.process.pid,epoch:this.epoch,transportAvailableNs:String(now())});
  }
  recordResourceRoots(){const file=path.join(this.options.output,"resource-roots.json");write(file+".tmp",[...this.additionalPids]);renameSync(file+".tmp",file);appendFileSync(path.join(this.options.output,"resource-root-events.jsonl"),JSON.stringify({timeNs:String(now()),daemonPid:this.process?.pid,peerPids:[...this.additionalPids]})+"\n");}
  trackPeer(child:ChildProcess){if(!child.pid)return;this.lifetime?.track(child);this.additionalPids.add(child.pid);this.recordResourceRoots();child.once("exit",()=>{this.additionalPids.delete(child.pid!);this.recordResourceRoots();});}
  async connect(){const socket=await new Promise<net.Socket>((resolve,reject)=>{const s=net.createConnection(this.socket);s.once("connect",()=>resolve(s));s.once("error",reject);});
    if(this.options.trace){const original=socket.write.bind(socket);socket.write=((chunk:any,...args:any[])=>{if(Buffer.isBuffer(chunk)){const split=chunk.indexOf("\r\n\r\n");if(split>=0){const message=JSON.parse(chunk.subarray(split+4).toString());message._jvmdTrace=this.traceContext.getStore();return original(encode(message),...args);}}return original(chunk,...args);}) as any;}
    const client=new RpcClient(socket);this.clients.push(client);return client;
  }
  async reader(){return this.pipe?undefined:await this.connect();}
  async call(method:string,params:any={},client=this.control){
    const id=this.calls.length+1,started=now(),trace={workflow:"lifecycle",invocation:this.epoch+":"+id,revision:this.phase};
    const row:any={schemaVersion:1,id,method,params,phase:this.phase,epoch:this.epoch,clockDomain:"client",startNs:String(started),outcome:"pass",trace};this.calls.push(row);
    appendFileSync(path.join(this.options.output,"native-events.jsonl"),JSON.stringify({...row,event:"send"})+"\n");
    try{if(this.pipe){this.pipe.traceContext=this.options.trace?trace:undefined;const response=await this.pipe.request(method,params,180000);if(response.error)throw new Error(JSON.stringify(response.error));row.result=response.result;}
      else row.result=await this.traceContext.run(trace,()=>client!.call(method,params));return row.result;}
    catch(error){row.error=String(error);row.outcome="protocol_error";throw error;}
    finally{row.endNs=String(now());row.latencyMs=Number(BigInt(row.endNs)-started)/1e6;appendFileSync(path.join(this.options.output,"native-calls.jsonl"),JSON.stringify(row)+"\n");}
  }
  async close(){
    if(this.closed)return;this.closed=true;
    let code=this.process?.exitCode??null,forced=false,shutdownError:string|undefined;
    try{if(this.process?.pid&&this.process.exitCode===null){
      // Bound the entire shutdown, including an unanswered RPC.
      let timer:any;await Promise.race([(async()=>{try{await this.call("daemon.shutdown");}catch(error){shutdownError=String(error);}code=await this.exited;})(),new Promise<void>(resolve=>{timer=setTimeout(()=>{forced=true;this.process.kill("SIGKILL");resolve();},15000);})]);clearTimeout(timer);
    }write(path.join(this.options.output,"exit.json"),{schemaVersion:1,code,endNs:String(now()),forced,shutdownError});}
    finally{for(const c of this.clients)c.close();if(this.monitor){writeFileSync(path.join(this.options.output,"monitor.stop"),"");await new Promise<void>(resolve=>{if(this.monitor!.exitCode!==null)return resolve();const t=setTimeout(()=>{this.monitor!.kill();resolve();},3000);this.monitor!.once("exit",()=>{clearTimeout(t);resolve();});});}try{this.lifetime?.finish();}finally{rmSync(this.socketDirectory,{recursive:true,force:true});closeSync(this.stderr);}}
    assert(!forced&&code===0,"unclean native daemon shutdown: "+JSON.stringify({code,forced,shutdownError}));
  }
}
