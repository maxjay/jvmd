import assert from "node:assert/strict";
import { spawn, type ChildProcess } from "node:child_process";
import { mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import net from "node:net";
import path from "node:path";
import { pathToFileURL } from "node:url";

const subjectRoot=path.resolve(process.env.SUBJECT_ROOT??"measured-subject");
const fixtureRoot=path.resolve(process.env.FIXTURE_ROOT!);
const reportFile=path.resolve(process.env.REPORT_FILE!);
const subjectSha=process.env.ISSUE36_SUBJECT_SHA??"unknown";
const {LspBridge}=await import(pathToFileURL(path.join(subjectRoot,"shim/src/lsp.ts")).href);
const {RpcClient,RpcPool}=await import(pathToFileURL(path.join(subjectRoot,"shim/src/transport.ts")).href);

const state=path.join(path.dirname(reportFile),"issue36-cmp-status-state");
rmSync(state,{recursive:true,force:true});mkdirSync(state,{recursive:true});
const socketPath=path.join(state,"daemon.sock"),configPath=path.join(state,"config.json");
writeFileSync(configPath,"{}\n");
const image=path.join(subjectRoot,"jvmd-dist/target/image");
const daemon=spawn(path.join(image,"bin/java"),[
  "-Djvmd.socket="+socketPath,
  "-Djvmd.state="+state,
  "-Djvmd.config="+configPath,
  "-cp",path.join(image,"lib/jvmd/*"),
  "dev.jvmd.dist.Application",
],{stdio:["ignore","ignore","inherit"],env:{...process.env,JVMD_SOCKET:socketPath}});
const control=await waitForControl(socketPath,daemon);
await waitMachineReady(control);

const pool=new RpcPool(()=>connectClient(socketPath));
const calls:any[]=[];
const caller={
  async call(method:string,params:any={}){
    const result=await pool.call(method,params);calls.push({method,params,result});return result;
  },
};
let driver:any;
const bridge=new LspBridge(async()=>caller,fixtureRoot,(message:any)=>driver.send(message),()=>{});
driver=new Driver(bridge);

const rootUri=pathToFileURL(fixtureRoot).href;
await driver.request("initialize",{
  processId:process.pid,rootUri,workspaceFolders:[{uri:rootUri,name:"apache-maven"}],
  capabilities:{workspace:{configuration:true},textDocument:{publishDiagnostics:{versionSupport:true},completion:{completionItem:{snippetSupport:false}}}},
});
const openCall=calls.find(call=>call.method==="session.open");
const session=openCall?.result?.result?.session;
assert(session,"session.open omitted session");
await driver.notify("initialized",{});

const receiverPath=path.join(fixtureRoot,"impl/maven-core/src/main/java/org/apache/maven/project/MavenProject.java");
const callerPath=path.join(fixtureRoot,"impl/maven-core/src/main/java/org/apache/maven/project/DefaultMavenProjectHelper.java");
const receiverText=readFileSync(receiverPath,"utf8"),callerText=readFileSync(callerPath,"utf8");
const receiverUri=pathToFileURL(receiverPath).href,callerUri=pathToFileURL(callerPath).href;
await driver.notify("textDocument/didOpen",{textDocument:{uri:receiverUri,languageId:"java",version:1,text:receiverText}});
await driver.notify("textDocument/didOpen",{textDocument:{uri:callerUri,languageId:"java",version:1,text:callerText}});

const marker="/*BENCH_CURSOR*/";
const withMarker=insertBeforeLastBrace(callerText,"\n    private void benchmarkCompletion(MavenProject project) {\n        project."+marker+"\n    }\n");
const offset=withMarker.indexOf(marker);assert(offset>=0);
const admitted=withMarker.replace(marker,"");
const position=positionAt(withMarker,offset);
await driver.notify("textDocument/didChange",{textDocument:{uri:callerUri,version:2},contentChanges:[{text:admitted}]});

const before=sessionValue(await control.call("session.status",{session}));
const started=performance.now();
const response=await driver.request("textDocument/completion",{
  textDocument:{uri:callerUri},position,context:{triggerKind:2,triggerCharacter:"."},
});
const latencyMs=performance.now()-started;
const after=sessionValue(await control.call("session.status",{session}));

const beforeAnalyzer=before.analyzer??{},afterAnalyzer=after.analyzer??{};
const result={
  schema:1,
  subject_sha:subjectSha,
  scenario:"CMP-01-status",
  latency_ms:latencyMs,
  result_count:Array.isArray(response)?response.length:(response?.items??[]).length,
  analyzer_before:select(beforeAnalyzer),
  analyzer_after:select(afterAnalyzer),
  delta:diff(beforeAnalyzer,afterAnalyzer),
  note:"This companion proof uses the production daemon and LspBridge with the same Apache Maven source mutation as CMP-01; it samples native session.status immediately around the completion request so compiler-query counts do not require RequestScope tracing in the jlink image.",
};
writeFileSync(reportFile,JSON.stringify(result,null,2)+"\n");

await driver.notify("textDocument/didClose",{textDocument:{uri:receiverUri}});
await driver.notify("textDocument/didClose",{textDocument:{uri:callerUri}});
await driver.request("shutdown",{}).catch(()=>null);
await bridge.handle({jsonrpc:"2.0",method:"exit",params:{}}).catch(()=>null);
await control.call("daemon.shutdown",{}).catch(()=>null);
control.close();await pool.close?.();await waitExit(daemon).catch(()=>daemon.kill("SIGTERM"));

function sessionValue(value:any){return value?.result??{};}
function number(value:any,key:string){return typeof value?.[key]==="number"?value[key]:null;}
function select(value:any){
  const result:any={};
  for(const key of ["queries","batch_queries","completion_requests","binding_computations","api_fingerprint_changes","api_fingerprint_unchanged","pending_api_files","conditional_files"])result[key]=number(value,key);
  const resident=value?.resident_semantic_state??{};
  for(const key of ["semantic_fact_mutations","semantic_tree_range_entries_read","semantic_units","semantic_stale_units"])result["resident."+key]=number(resident,key);
  return result;
}
function diff(before:any,after:any){
  const a=select(before),b=select(after),out:any={};
  for(const key of Object.keys(b))out[key]=typeof a[key]==="number"&&typeof b[key]==="number"?b[key]-a[key]:null;
  return out;
}
function insertBeforeLastBrace(source:string,text:string){
  const end=source.lastIndexOf("}");assert(end>=0);return source.slice(0,end)+"\n"+text+source.slice(end);
}
function positionAt(source:string,offset:number){
  const before=source.slice(0,offset).split("\n");return {line:before.length-1,character:before.at(-1)!.length};
}
async function connectClient(socket:string){
  const stream=await new Promise<net.Socket>((resolve,reject)=>{
    const connection=net.createConnection(socket);connection.once("connect",()=>resolve(connection));connection.once("error",reject);
  });
  return new RpcClient(stream);
}
async function waitForControl(socket:string,process:ChildProcess){
  const deadline=Date.now()+30000;
  while(Date.now()<deadline){
    if(process.exitCode!==null)throw new Error("daemon exited before transport");
    try{return await connectClient(socket);}catch(error){
      const code=(error as NodeJS.ErrnoException).code;if(code!=="ENOENT"&&code!=="ECONNREFUSED")throw error;
      await new Promise(r=>setTimeout(r,20));
    }
  }
  throw new Error("timed out waiting for daemon");
}
async function waitMachineReady(client:any){
  const deadline=Date.now()+180000;
  while(Date.now()<deadline){
    const status=await client.call("daemon.status",{}),index=status?.result?.index;
    if(index?.phase==="ready"&&Number(index?.timings?.scans??0)>=1)return;
    await new Promise(r=>setTimeout(r,50));
  }
  throw new Error("timed out waiting for machine index");
}
async function waitExit(process:ChildProcess){
  if(process.exitCode!==null)return;
  await new Promise<void>((resolve,reject)=>{
    const timer=setTimeout(()=>reject(new Error("daemon did not exit")),10000);
    process.once("exit",()=>{clearTimeout(timer);resolve();});
  });
}
class Driver {
  responses=new Map<number,any>();next=0;bridge:any;
  constructor(bridge:any){this.bridge=bridge;}
  send=(message:any)=>{if(typeof message.id==="number")this.responses.set(message.id,message);};
  async request(method:string,params:any){
    const id=++this.next;await this.bridge.handle({jsonrpc:"2.0",id,method,params});
    const response=this.responses.get(id);if(!response)throw new Error("missing response "+method);
    this.responses.delete(id);if(response.error)throw new Error(method+": "+response.error.message);return response.result;
  }
  async notify(method:string,params:any){await this.bridge.handle({jsonrpc:"2.0",method,params});}
}
