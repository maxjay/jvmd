import assert from "node:assert/strict";
import { spawn, type ChildProcess } from "node:child_process";
import { mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import net from "node:net";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { createMessageConnection, StreamMessageReader, StreamMessageWriter } from "vscode-jsonrpc/node";
import { RpcClient } from "../../shim/src/transport.ts";

const subjectRoot=path.resolve(process.env.SUBJECT_ROOT!);
const fixtureRoot=path.resolve(process.env.FIXTURE_ROOT!);
const reportDir=path.resolve(process.env.REPORT_DIR!);
const subjectSha=process.env.SUBJECT_SHA!;
mkdirSync(reportDir,{recursive:true});

const state=path.join(reportDir,"cmp01-state");
rmSync(state,{recursive:true,force:true});mkdirSync(state,{recursive:true});
const socket=path.join(state,"jvmd.sock");
const image=path.join(subjectRoot,"jvmd-dist/target/image");
const env={...process.env,JVMD_SOCKET:socket,XDG_CACHE_HOME:path.join(state,"cache"),JVMD_CONFIG:path.join(state,"config.json")};
writeFileSync(path.join(state,"config.json"),"{}\n");

const jfr=path.join(reportDir,"cmp01.jfr");
const server=spawn(path.join(image,"bin/java"),[
  "-Djvmd.trace=true",
  "-XX:StartFlightRecording=filename="+jfr+",settings=profile,dumponexit=true",
  "-Djvmd.socket="+socket,
  "-Djvmd.state="+path.join(state,"state"),
  "-Djvmd.config="+path.join(state,"config.json"),
  "-cp",path.join(image,"lib/jvmd/*"),
  "dev.jvmd.dist.Application",
],{env,stdio:["ignore","ignore","inherit"]});

const lifecycle:any={process_spawn_ns:Number(process.hrtime.bigint())};
const readiness=await waitForJvmdTransport(socket);
lifecycle.transport_available_ns=Number(process.hrtime.bigint());
await waitForJvmdIndex(readiness);
lifecycle.daemon_index_ready_ns=Number(process.hrtime.bigint());
readiness.close();

const adapter=spawn(path.join(image,"bin/jvmd-lsp"),["--root",fixtureRoot,"--socket",socket],{env,stdio:["pipe","pipe","inherit"]});
const connection=createMessageConnection(new StreamMessageReader(adapter.stdout!),new StreamMessageWriter(adapter.stdin!));
connection.onRequest("workspace/configuration",(params:any)=>(params?.items??[]).map(()=>({})));
connection.onRequest("client/registerCapability",()=>null);
connection.onRequest("client/unregisterCapability",()=>null);
connection.onRequest("window/workDoneProgress/create",()=>null);
connection.onRequest("workspace/applyEdit",()=>({applied:false}));
let diagnosticMode:"unknown"|"versioned"|"versionless"="unknown";
connection.onNotification("textDocument/publishDiagnostics",(params:any)=>{
  if(params?.version===undefined&&diagnosticMode==="unknown")diagnosticMode="versionless";
  if(params?.version!==undefined)diagnosticMode="versioned";
});
connection.listen();

const rootUri=pathToFileURL(fixtureRoot).href;
const initializeStart=performance.now();
await connection.sendRequest("initialize",{
  processId:process.pid,
  rootUri,
  workspaceFolders:[{uri:rootUri,name:"apache-maven"}],
  capabilities:{workspace:{configuration:true},textDocument:{publishDiagnostics:{versionSupport:true},completion:{completionItem:{snippetSupport:false}}}},
});
lifecycle.initialize_ms=performance.now()-initializeStart;
connection.sendNotification("initialized",{});

const receiver=openFile("impl/maven-core/src/main/java/org/apache/maven/project/MavenProject.java",1);
const caller=openFile("impl/maven-core/src/main/java/org/apache/maven/project/DefaultMavenProjectHelper.java",1);
connection.sendNotification("textDocument/didOpen",{textDocument:{uri:receiver.uri,languageId:"java",version:1,text:receiver.text}});
connection.sendNotification("textDocument/didOpen",{textDocument:{uri:caller.uri,languageId:"java",version:1,text:caller.text}});

const baselineExpected=JSON.parse(readFileSync(path.resolve("benchmarks/lsp-scenarios/expected/CMP-01.json"),"utf8"));
const expected=baselineExpected.first?.result??[];

const states:any[]=[];
let version=1;
for(const prefix of ["","g","get","getM"]){
  const marker="/*BENCH_CURSOR*/";
  const inserted="\n    private void benchmarkCompletion(MavenProject project) {\n        project."+prefix+marker+"\n    }\n";
  const text=insertBeforeLastBrace(caller.text,inserted).replace(marker,"");
  const markerSource=insertBeforeLastBrace(caller.text,inserted);
  const offset=markerSource.indexOf(marker);
  const before=markerSource.slice(0,offset).split("\n");
  const position={line:before.length-1,character:before.at(-1)!.length};
  version++;
  connection.sendNotification("textDocument/didChange",{textDocument:{uri:caller.uri,version},contentChanges:[{text}]});
  const samples:any[]=[];
  for(let i=0;i<(prefix===""?6:1);i++){
    const rssBefore=rssKb(server.pid)+rssKb(adapter.pid);
    const started=performance.now();
    const response:any=await connection.sendRequest("textDocument/completion",{
      textDocument:{uri:caller.uri},position,context:{triggerKind:prefix===""?2:1,triggerCharacter:prefix===""?".":undefined},
    });
    const latencyMs=performance.now()-started;
    const normalized=normaliseCompletion(response);
    samples.push({latency_ms:latencyMs,rss_kb_before:rssBefore,rss_kb_after:rssKb(server.pid)+rssKb(adapter.pid),result:normalized});
  }
  const first=samples[0].result;
  const oracle=prefix===""?deepEqual(first,expected):null;
  states.push({prefix,oracle_matches_jdtls:oracle,count:first.length,names:first.map((x:any)=>x.label),samples});
}

const resolved=firstCompletionItem
  ?await connection.sendRequest("completionItem/resolve",firstCompletionItem).catch((e:any)=>({error:String(e)}))
  :null;

const receiverEdit=insertBeforeLastBrace(receiver.text,"\n    public void issue36AddedMethod() {}\n");
connection.sendNotification("textDocument/didChange",{textDocument:{uri:receiver.uri,version:2},contentChanges:[{text:receiverEdit}]});

connection.sendNotification("textDocument/didClose",{textDocument:{uri:receiver.uri}});
connection.sendNotification("textDocument/didClose",{textDocument:{uri:caller.uri}});
await connection.sendRequest("shutdown").catch(()=>null);
connection.sendNotification("exit");
connection.dispose();
await stopAndWait(adapter);

const shutdown=await waitForJvmdTransport(socket);
await shutdown.call("daemon.shutdown").catch(()=>null);
shutdown.close();
await stopAndWait(server,5000);

const report={
  schema:1,
  subject_sha:subjectSha,
  fixture:"apache/maven@5cd1b60264101080c712accd605180a4bd9222e0",
  diagnostic_admission:diagnosticMode==="versioned"?"exact-version diagnostics available":"unavailable: versionless diagnostics cannot prove current-version admission",
  lifecycle,
  states,
  resolved:resolved?{label:(resolved as any).label??null,detail:(resolved as any).detail??null,has_documentation:Boolean((resolved as any).documentation)}:null,
};
writeFileSync(path.join(reportDir,"cmp01.json"),JSON.stringify(report,null,2)+"\n");
if(!states[0]?.oracle_matches_jdtls)console.error("CMP-01 baseline remains semantically incorrect; recorded as expected Stage-0 evidence.");

function openFile(relative:string,version:number){
  const file=path.join(fixtureRoot,relative);return {uri:pathToFileURL(file).href,text:readFileSync(file,"utf8"),version};
}
function insertBeforeLastBrace(source:string,text:string){
  const end=source.lastIndexOf("}");assert(end>=0);return source.slice(0,end)+"\n"+text+source.slice(end);
}
function positionFor(source:string,marker:string){
  const offset=source.indexOf(marker);assert(offset>=0);const before=source.slice(0,offset).split("\n");
  return {line:before.length-1,character:before.at(-1)!.length};
}
function normaliseCompletion(response:any){
  const items=Array.isArray(response)?response:response?.items??[];
  return items.map((item:any)=>({label:item.label,kind:item.kind??null,insertText:item.textEdit?.newText??item.insertText??item.label}))
    .sort((a:any,b:any)=>a.label.localeCompare(b.label)||String(a.kind).localeCompare(String(b.kind))||a.insertText.localeCompare(b.insertText));
}
function deepEqual(a:any,b:any){try{assert.deepEqual(a,b);return true;}catch{return false;}}
function rssKb(pid?:number){
  if(!pid)return 0;try{return Number(readFileSync("/proc/"+pid+"/status","utf8").match(/^VmRSS:\s+(\d+)\s+kB/m)?.[1]??0);}catch{return 0;}
}
async function waitForJvmdTransport(socketPath:string){
  const deadline=Date.now()+15000;
  while(Date.now()<deadline){
    try{
      const socket=await new Promise<net.Socket>((resolve,reject)=>{
        const c=net.createConnection(socketPath);c.once("connect",()=>resolve(c));c.once("error",reject);
      });
      return new RpcClient(socket);
    }catch(error){
      const code=(error as NodeJS.ErrnoException).code;if(code!=="ENOENT"&&code!=="ECONNREFUSED")throw error;
      await new Promise(r=>setTimeout(r,25));
    }
  }
  throw new Error("Timed out waiting for JVMD transport");
}
async function waitForJvmdIndex(client:RpcClient){
  const deadline=Date.now()+120000;
  while(Date.now()<deadline){
    const status:any=await client.call("daemon.status"),index=status?.result?.index;
    if(index?.phase==="ready"&&Number(index?.timings?.scans??0)>=1)return;
    await new Promise(r=>setTimeout(r,50));
  }
  throw new Error("Timed out waiting for JVMD index");
}
async function stopAndWait(child:ChildProcess,timeout=3000){
  if(child.exitCode!==null)return;
  await Promise.race([
    new Promise<void>(resolve=>child.once("exit",()=>resolve())),
    new Promise<void>(resolve=>setTimeout(()=>{if(child.exitCode===null)child.kill("SIGTERM");resolve();},timeout)),
  ]);
}
