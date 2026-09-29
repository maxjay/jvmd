import {spawn,type ChildProcess} from "node:child_process";
import {appendFileSync,mkdirSync} from "node:fs";
import path from "node:path";
import {EventEmitter} from "node:events";
import {Framing,encode} from "../../shim/src/transport.ts";

export const now=()=>process.hrtime.bigint();
export type Exchange={id:number;method:string;params:any;startNs:string;endNs:string;result?:any;error?:any};
export class ProtocolClient {
  child:ChildProcess;
  events:any[]=[];exchanges:Exchange[]=[];notifications:any[]=[];protocolErrors:string[]=[];
  processLifecycle:any[]=[];
  traceContext?:Record<string,string>;
  next=0;emitter=new EventEmitter();spawnNs=now();exited:Promise<number|null>;
  private pending=new Map<number,{method:string;params:any;start:bigint;resolve:(v:Exchange)=>void;timer:ReturnType<typeof setTimeout>}>();
  private bytes=0;private ended=false;
  private closed:Promise<number|null>;
  private journalDirectory?:string;
  onServerRequest:(method:string,params:any)=>Promise<any>=async method=>{throw new Error("unhandled server request: "+method);};
  constructor(command:string[],options:{cwd?:string;env?:NodeJS.ProcessEnv;stderr?:number;journalDirectory?:string}={}){
    if(!Array.isArray(command)||!command.length)throw new Error("server command missing");
    this.journalDirectory=options.journalDirectory;
    if(this.journalDirectory)mkdirSync(this.journalDirectory,{recursive:true});
    this.child=spawn(command[0],command.slice(1),{cwd:options.cwd,env:options.env??process.env,stdio:["pipe","pipe",options.stderr??"inherit"]});
    this.processEvent("launch_requested",{command},this.spawnNs);
    this.child.once("spawn",()=>this.processEvent("spawned",{pid:this.child.pid}));
    this.exited=new Promise(resolve=>this.child.once("exit",(code,signal)=>{this.processEvent("process_exit",{pid:this.child.pid,code,signal});this.ended=true;this.failPending("server exited: "+code);resolve(code);}));
    this.closed=new Promise(resolve=>this.child.once("close",(code,signal)=>{this.processEvent("stdio_closed",{code,signal});resolve(code);}));
    this.child.once("error",error=>{this.processEvent("process_error",{message:error.message});this.ended=true;this.protocolErrors.push(error.message);this.failPending(error.message);});
    this.child.stdin!.on("error",error=>{this.protocolErrors.push("stdin: "+error.message);this.failPending(error.message);});
    const framing=new Framing("headers",message=>this.receive(message));
    this.child.stdout!.on("data",chunk=>{try{framing.push(chunk);}catch(e){this.protocolErrors.push(String(e));this.failPending(String(e));}});
  }
  private record(direction:string,message:any,time:bigint){
    this.bytes+=JSON.stringify(message).length;
    if(this.bytes>64*1024*1024){this.protocolErrors.push("bounded protocol recording exceeded 64 MiB");this.child.kill();return;}
    const row={schemaVersion:1,sequence:this.events.length+1,clockDomain:"client",timeNs:String(time),direction,message};
    this.events.push(row);this.journal("events",row);
  }
  journal(name:string,row:any){if(this.journalDirectory)appendFileSync(path.join(this.journalDirectory,name+".jsonl"),JSON.stringify(row)+"\n");}
  private processEvent(event:string,detail:any={},time=now()){
    const row={schemaVersion:1,sequence:this.processLifecycle.length+1,clockDomain:"client",timeNs:String(time),event,...detail};
    this.processLifecycle.push(row);this.journal("process",row);
  }
  notify(method:string,params:any={}):bigint {
    const message={jsonrpc:"2.0",method,params};const bytes=encode(message),t=now();
    this.child.stdin!.write(bytes);this.record("send",message,t);return t;
  }
  request(method:string,params:any={},timeoutMs=180000):Promise<Exchange>{
    if(this.ended)return Promise.reject(new Error("request after server exit"));
    const id=++this.next,message={jsonrpc:"2.0",id,method,params,...(this.traceContext?{_jvmdTrace:this.traceContext}:{})},bytes=encode(message),start=now();
    return new Promise(resolve=>{
      const timer=setTimeout(()=>this.finish(id,undefined,{kind:"timeout",message:method+" timed out"}),timeoutMs);
      this.pending.set(id,{method,params,start,resolve,timer});
      this.child.stdin!.write(bytes);this.record("send",message,start);
    });
  }
  private finish(id:number,result:any,error?:any,endedAt=now()){
    const p=this.pending.get(id);if(!p){this.protocolErrors.push("duplicate or unknown terminal response: "+id);return;}
    this.pending.delete(id);clearTimeout(p.timer);
    const row:Exchange={id,method:p.method,params:p.params,startNs:String(p.start),endNs:String(endedAt),...(error?{error}:{result})};
    this.exchanges.push(row);this.journal("exchanges",row);p.resolve(row);
  }
  private failPending(reason:string){for(const id of [...this.pending.keys()])this.finish(id,undefined,{kind:"protocol_error",message:reason});this.emitter.emit("notification");}
  private receive(message:any){
    const t=now();this.record("receive",message,t);
    if(message.method&&message.id!==undefined){
      void this.onServerRequest(message.method,message.params).then(result=>{
        const reply={jsonrpc:"2.0",id:message.id,result};this.child.stdin!.write(encode(reply));this.record("send",reply,now());
      },error=>{
        this.protocolErrors.push(String(error));
        const reply={jsonrpc:"2.0",id:message.id,error:{code:-32601,message:String(error)}};
        this.child.stdin!.write(encode(reply));this.record("send",reply,now());
      });
    }else if(message.id!==undefined)this.finish(message.id,message.result,message.error,t);
    else {this.notifications.push({params:message.params,method:message.method,timeNs:String(t),sequence:this.events.length});this.emitter.emit("notification");}
  }
  async notification(method:string,predicate:(params:any)=>boolean,since=0,timeoutMs=180000):Promise<any>{
    const find=()=>this.notifications.slice(since).find(n=>n.method===method&&predicate(n.params));
    const existing=find();if(existing)return existing;
    return new Promise((resolve,reject)=>{
      const clean=()=>{clearTimeout(timer);this.emitter.off("notification",check);};
      const check=()=>{const n=find();if(n){clean();resolve(n);}else if(this.ended){clean();reject(new Error("server exited while waiting for "+method));}};
      const timer=setTimeout(()=>{clean();reject(new Error("notification timeout: "+method));},timeoutMs);
      this.emitter.on("notification",check);check();
    });
  }
  async shutdown(timeoutMs=70000):Promise<void>{
    const waitForExit=(deadlineMs:number)=>new Promise<{code:number|null;timedOut:boolean}>(resolve=>{
      const timer=setTimeout(()=>resolve({code:null,timedOut:true}),deadlineMs);
      void this.closed.then(code=>{clearTimeout(timer);resolve({code,timedOut:false});});
    });
    if(this.ended){
      const final=await waitForExit(timeoutMs);
      if(final.timedOut)this.processEvent("process_exit_unobserved",{reason:"output streams did not close",graceMs:timeoutMs});
      if(this.child.exitCode!==0||final.timedOut)throw new Error("server already exited uncleanly: "+this.child.exitCode);
      return;
    }
    this.processEvent("shutdown_begin",{timeoutMs});
    const result=await this.request("shutdown",{},timeoutMs);
    this.processEvent("shutdown_response",{requestId:result.id,error:result.error??null},BigInt(result.endNs));
    const sent=this.notify("exit");this.processEvent("exit_notified",{},sent);this.child.stdin!.end();this.processEvent("stdin_ended");
    let final=await waitForExit(timeoutMs);const forced=final.timedOut;
    if(forced){
      this.processEvent("shutdown_deadline",{timeoutMs});
      const delivered=this.child.kill("SIGKILL");this.processEvent("forced_kill",{signal:"SIGKILL",delivered});
      // Observe exit and stream closure before callers seal journals; requesting
      // a kill is not itself evidence that the child terminated or output drained.
      final=await waitForExit(5000);
      if(final.timedOut)this.processEvent("process_exit_unobserved",{graceMs:5000});
    }
    if(result.error||forced||final.code!==0)throw new Error("unclean shutdown: "+JSON.stringify({error:result.error,code:final.code,forced,exitObserved:!final.timedOut}));
  }
}
