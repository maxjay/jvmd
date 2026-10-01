import assert from "node:assert/strict";
import {existsSync,readFileSync,rmSync} from "node:fs";
import path from "node:path";
import {pathToFileURL} from "node:url";
import {ScenarioContext} from "./ScenarioContext.ts";
import {launch,JvmdDaemon,type Launch} from "./launch.ts";
import {settingsXml,type Pom} from "./maven.ts";
import {type Fixture} from "./fixture.ts";
import {position,range,exactLocations,locations,hoverOracle,completionOracle} from "./oracles.ts";
import {observeDiagnostics,errorAt} from "./diagnostics.ts";

/**
 * The lifecycle of a real Maven project (the pinned apache/maven checkout), measured the way JVMD is
 * meant to be used. JVMD: cold machine index, workspace open, queries, reconnect to the warm daemon,
 * daemon restart on the persisted index. JDTLS: cold import, queries, restart on the same workspace data.
 */
const CORE="impl/maven-core/src/main/java/org/apache/maven/project/";
const HELPER="DefaultMavenProjectHelper.java",PROJECT="MavenProject.java";

/** openTimeout bounds opening the workspace (a cold import can take minutes); timeout bounds each query. */
export type LifecycleOptions={server:"jvmd"|"jdtls";project:string;repository:string;state:string;javaHome:string;image:string;jdtlsHome?:string;
  openTimeout:number;timeout:number;warmup:number;samples:number};

const rssBytes=(pid?:number)=>{try{return Number(readFileSync(`/proc/${pid}/status`,"utf8").match(/^VmRSS:\s+(\d+)\s+kB/mu)?.[1])*1024;}catch{return null;}};

function projectFixture(project:string,repository:string):Fixture{
  const file=(name:string)=>{const p=path.join(project,CORE,name);return {path:p,uri:pathToFileURL(p).href,text:readFileSync(p,"utf8")};};
  return {root:project,repository,pom:{artifactId:"maven"} as Pom,files:{[HELPER]:file(HELPER),[PROJECT]:file(PROJECT)},identity:"apache/maven",
    workspaceFolders:[{uri:pathToFileURL(project).href,name:"maven"}]};
}
const at=(c:ScenarioContext,name:string,token:string,from=0,shift=1)=>{const text=c.text(name),offset=text.indexOf(token,from);assert(offset>=0,token+" missing from "+name);
  return {textDocument:{uri:c.file(name).uri},position:position(text,offset+shift)};};
/** Editors complete a typed prefix; servers cap long member lists, so a bare "project." proves little. */
const probe=(text:string,prefix:string)=>{const end=text.lastIndexOf("}");
  return text.slice(0,end)+`    private void benchmarkCompletion(MavenProject project) {\n        project.${prefix}\n    }\n`+text.slice(end);};
/** A wrong answer is recorded on its operation; it must not abort the remaining measurements. */
const step=async(f:()=>Promise<unknown>)=>{try{await f();}catch(error){if(!(error instanceof assert.AssertionError))throw error;}};
/** The measured editor requests, each with an oracle derived from the source text. */
async function queries(c:ScenarioContext){
  const helper=c.text(HELPER),project=c.text(PROJECT),call=helper.indexOf("project.addAttachedArtifact("),declaration=project.indexOf("public void addAttachedArtifact(")+"public void ".length;
  await step(()=>c.series("textDocument/hover",at(c,HELPER,"addAttachedArtifact(",call),v=>hoverOracle(v,"addAttachedArtifact","void")));
  const target={uri:c.file(PROJECT).uri,range:range(project,"addAttachedArtifact",declaration)};
  await step(()=>c.series("textDocument/definition",at(c,HELPER,"addAttachedArtifact(",call),v=>exactLocations(v,[target])));
  await step(()=>c.series("textDocument/references",{...at(c,PROJECT,"addAttachedArtifact(",declaration),context:{includeDeclaration:true}},v=>{
    // Servers differ on how much of a call a reference spans (name only, or up to the closing parenthesis): match where it starts.
    const rows=(v??[]).map((r:any)=>r.uri+"#"+JSON.stringify(r.range.start));
    for(const wanted of [target,{uri:c.file(HELPER).uri,range:range(helper,"addAttachedArtifact",call)}])assert(rows.includes(wanted.uri+"#"+JSON.stringify(wanted.range.start)),"reference missing: "+wanted.uri);
  }));
  await step(()=>c.series("textDocument/signatureHelp",at(c,HELPER,"addAttachedArtifact(",call,"addAttachedArtifact(".length),v=>assert.match(v?.signatures?.[v.activeSignature??0]?.label??"",/addAttachedArtifact/u)));
  await step(()=>c.series("textDocument/documentSymbol",{textDocument:{uri:c.file(PROJECT).uri}},v=>{
    const type=(v??[]).find((s:any)=>s.name==="MavenProject");assert(type,"MavenProject missing from outline");
    assert((type.children??[]).some((s:any)=>/^getArtifactId/u.test(s.name)),"getArtifactId missing from outline");
  }));
  await step(()=>c.series("textDocument/semanticTokens/full",{textDocument:{uri:c.file(HELPER).uri}},v=>{assert(v?.data?.length>0&&v.data.length%5===0,"semantic tokens missing");}));
  const complete=()=>{const text=c.text(HELPER),line=text.lastIndexOf("        project.");
    return {textDocument:{uri:c.file(HELPER).uri},position:position(text,text.indexOf("\n",line))};};
  c.change(HELPER,probe(helper,"getGr"));
  await step(()=>c.series("textDocument/completion",complete(),v=>completionOracle(v,["getGroupId"])));
  // After an edit: a member added to MavenProject must be offered at the unchanged caller.
  c.change(HELPER,probe(helper,"benchmark"));
  const trigger=c.change(PROJECT,project.replace("public String getGroupId() {","public void benchmarkAdded() {}\n\n    public String getGroupId() {")).trigger;
  await step(()=>c.transition("textDocument/completion",complete,v=>completionOracle(v,["benchmarkAdded"]),trigger,"new MavenProject member offered after the edit"));
  // After an error: the versioned diagnostic that marks it.
  const since=c.client.notifications.length,changed=c.change(HELPER,probe(helper,"benchmarkMissing();"));
  await step(()=>observeDiagnostics(c,c.file(HELPER).uri,changed.trigger,errorAt("benchmarkMissing"),since,"changed_diagnostic"));
}

/**
 * The daemon log's tail, a count of threads by name, and every JVMD thread stack that is doing or
 * waiting on work (idle file watchers excluded): enough to read a hang or a failed start from the job log.
 */
export function failureEvidence(log:string,dump?:string,adapter?:string){
  const out:string[]=[];
  if(existsSync(log))out.push("--- daemon.log (tail) ---",...readFileSync(log,"utf8").split("\n").slice(-60));
  if(adapter&&existsSync(adapter))out.push("--- adapter stderr (tail) ---",...readFileSync(adapter,"utf8").split("\n").slice(-20));
  if(dump&&existsSync(dump)){
    const stacks=readFileSync(dump,"utf8").split(/\n\s*\n/u),counts=new Map<string,number>();
    for(const stack of stacks){const name=/^"([^"]+)"/u.exec(stack.trim())?.[1];if(name){const key=name.replace(/[-#]?[0-9a-f]{6,}$|[-#]\d+$/u,"");counts.set(key,(counts.get(key)??0)+1);}}
    out.push("--- threads by name ---",...[...counts].sort((a,b)=>b[1]-a[1]).map(([name,n])=>`${String(n).padStart(4)} ${name}`));
    out.push("--- JVMD threads at work ---");
    for(const stack of stacks)if(stack.includes("dev.jvmd.")&&!stack.includes("LiveSourceState.watchLoop"))out.push(...stack.split("\n").slice(0,45),"");
  }
  return out.join("\n");
}

export async function runLifecycle(o:LifecycleOptions){
  settingsXml(o.repository,path.join(o.repository,"settings.xml"));
  rmSync(o.state,{recursive:true,force:true});
  const fixture=projectFixture(o.project,o.repository),phases:Record<string,any>={},operations:any[]=[],errors:string[]=[];
  let daemon:JvmdDaemon|undefined,running:Launch|undefined,restartStarted:number|undefined;
  // "Open" is launch to the first correct answer on the project. A server may report itself ready
  // (JDTLS's ServiceReady) before its project import finishes; that is not a usable workspace yet.
  const open=async(reuseState:boolean,label:string)=>{
    const started=performance.now();
    running=await launch({server:o.server,root:o.project,state:path.join(o.state,"server"),javaHome:o.javaHome,image:o.image,jdtlsHome:o.jdtlsHome,daemon,reuseState});
    const c=new ScenarioContext(running.client,fixture,o.server,o.openTimeout,o.warmup,o.samples);c.javaHome=o.javaHome;c.allocation=running.allocation;
    await c.initialize();phases[label+"_initialize_ms"]=performance.now()-started;
    // Readiness is a correct go-to-definition across files: the most basic navigation, and cheap on both servers.
    await c.open(HELPER);const params=at(c,HELPER,"addAttachedArtifact(",c.text(HELPER).indexOf("project.addAttachedArtifact("));
    const project=readFileSync(c.file(PROJECT).path,"utf8"),target=[{uri:c.file(PROJECT).uri,range:range(project,"addAttachedArtifact",project.indexOf("public void addAttachedArtifact(")+"public void ".length)}];
    for(const deadline=performance.now()+o.openTimeout;;){
      const r=await c.client.request("textDocument/definition",params,o.openTimeout);
      try{exactLocations(r.result,target);break;}catch(error){if(performance.now()>deadline)throw new Error("workspace never answered correctly: "+String(error).split("\n")[0]+"; answered "+JSON.stringify(locations(r.result))+", expected "+JSON.stringify(target));}
      await new Promise(resolve=>setTimeout(resolve,250));
    }
    c.close(HELPER);c.operations=operations;c.timeout=o.timeout;return {c,ms:performance.now()-started};
  };
  const pid=()=>o.server==="jvmd"?daemon?.process.pid:running?.client.child.pid;
  try{
    if(o.server==="jvmd"){
      daemon=await JvmdDaemon.start({javaHome:o.javaHome,image:o.image,state:path.join(o.state,"jvmd"),repository:o.repository});
      phases.machine_index_ms=daemon.readyMs;phases.indexed_artifacts=(await daemon.status()).index?.total??null;
    }
    let {c,ms}=await open(false,"open");phases.open_ms=ms;
    await c.open(PROJECT);await c.open(HELPER);await queries(c);phases.rss_bytes=rssBytes(pid());
    // The retained session keeps unsaved buffers across a reconnect, so close the edited documents (as an editor
    // closing its tabs without saving) before disconnecting: the reconnect's oracle reads the files on disk.
    for(const name of [PROJECT,HELPER])c.close(name);
    await running!.stop({closeSession:false});running=undefined;
    if(o.server==="jvmd"){
      // Reconnect, as when an editor window reopens: the daemon and its session are still warm.
      ({c,ms}=await open(true,"reconnect"));phases.reconnect_open_ms=ms;
      await running!.stop();running=undefined;
      await daemon!.stop();restartStarted=performance.now();daemon=await JvmdDaemon.start({javaHome:o.javaHome,image:o.image,state:path.join(o.state,"jvmd"),repository:o.repository});
      phases.restart_index_ms=daemon.readyMs;
    }
    restartStarted??=performance.now();
    ({c,ms}=await open(true,"restart"));phases.restart_open_ms=ms;
    // Restart to the first correct completion: daemon start (or server launch) until a member
    // completion on the reopened project answers with the expected candidate.
    await c.open(HELPER);c.change(HELPER,probe(c.text(HELPER),"getGr"));
    const completionParams=()=>{const text=c.text(HELPER),line=text.lastIndexOf("        project.");
      return {textDocument:{uri:c.file(HELPER).uri},position:position(text,text.indexOf("\n",line))};};
    for(const deadline=performance.now()+o.openTimeout;;){
      const r=await c.client.request("textDocument/completion",completionParams(),o.timeout);
      try{completionOracle(r.result,["getGroupId"]);break;}catch(error){if(performance.now()>deadline)throw new Error("restart never completed correctly: "+String(error).split("\n")[0]);}
      await new Promise(resolve=>setTimeout(resolve,100));
    }
    phases.restart_first_completion_ms=performance.now()-restartStarted;
  }catch(error){
    errors.push(String(error).split("\n")[0]);
    // The summary line is cut short; the whole first line (for an oracle, what was answered and expected) goes to the job log.
    console.error("[lifecycle] error: "+String(error).split("\n")[0]);
    // A JVMD that stopped answering leaves its thread dump in the run output, so the hang can be read without a rerun.
    if(daemon?.alive())try{phases.thread_dump=daemon.threadDump(o.javaHome,path.join(o.state,"jvmd-threads.txt"));}catch{/* best effort */}
    // Artifacts are not always reachable from where a failure is read: put the evidence in the job log too.
    try{console.error(failureEvidence(path.join(o.state,"jvmd","daemon.log"),phases.thread_dump,path.join(o.state,"server","stderr.log")));}catch{/* best effort */}
  }
  finally{
    if(running)await running.stop().catch(()=>undefined);
    if(daemon)await daemon.stop().catch(()=>undefined);
  }
  const failed=operations.filter(op=>op.outcome!=="pass");
  // Raw answers are kept only where a check failed, so a failure can be read without a rerun.
  return {server:o.server,project:"apache/maven",phases,operations:operations.map(({rawResult,...op}:any)=>op.outcome==="pass"?op:{...op,rawResult}),
    outcome:errors.length?"harness_error":failed.length?failed[0].outcome:"pass",error:errors[0]??failed[0]?.assertionError};
}
