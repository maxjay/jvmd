import assert from "node:assert/strict";
import {readFileSync,rmSync} from "node:fs";
import path from "node:path";
import {pathToFileURL} from "node:url";
import {ScenarioContext} from "./ScenarioContext.ts";
import {launch,JvmdDaemon,type Launch} from "./launch.ts";
import {settingsXml,type Pom} from "./maven.ts";
import {type Fixture} from "./fixture.ts";
import {position,range,exactLocations,hoverOracle,completionOracle} from "./oracles.ts";
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
const PROBE="    private void benchmarkCompletion(MavenProject project) {\n        project.\n    }\n";
const withProbe=(text:string)=>{const end=text.lastIndexOf("}");return text.slice(0,end)+PROBE+text.slice(end);};

/** A wrong answer is recorded on its operation; it must not abort the remaining measurements. */
const step=async(f:()=>Promise<unknown>)=>{try{await f();}catch(error){if(!(error instanceof assert.AssertionError))throw error;}};
/** The measured editor requests, each with an oracle derived from the source text. */
async function queries(c:ScenarioContext){
  const helper=c.text(HELPER),project=c.text(PROJECT),call=helper.indexOf("project.addAttachedArtifact("),declaration=project.indexOf("public void addAttachedArtifact(")+"public void ".length;
  await step(()=>c.series("textDocument/hover",at(c,HELPER,"addAttachedArtifact(",call),v=>hoverOracle(v,"addAttachedArtifact","void")));
  const target={uri:c.file(PROJECT).uri,range:range(project,"addAttachedArtifact",declaration)};
  await step(()=>c.series("textDocument/definition",at(c,HELPER,"addAttachedArtifact(",call),v=>exactLocations(v,[target])));
  await step(()=>c.series("textDocument/references",{...at(c,PROJECT,"addAttachedArtifact(",declaration),context:{includeDeclaration:true}},v=>{
    const rows=(v??[]).map((r:any)=>r.uri+"#"+JSON.stringify(r.range));
    for(const wanted of [target,{uri:c.file(HELPER).uri,range:range(helper,"addAttachedArtifact",call)}])assert(rows.includes(wanted.uri+"#"+JSON.stringify(wanted.range)),"reference missing: "+wanted.uri);
  }));
  await step(()=>c.series("textDocument/signatureHelp",at(c,HELPER,"addAttachedArtifact(",call,"addAttachedArtifact(".length),v=>assert.match(v?.signatures?.[v.activeSignature??0]?.label??"",/addAttachedArtifact/u)));
  await step(()=>c.series("textDocument/documentSymbol",{textDocument:{uri:c.file(PROJECT).uri}},v=>{
    const type=(v??[]).find((s:any)=>s.name==="MavenProject");assert(type,"MavenProject missing from outline");
    assert((type.children??[]).some((s:any)=>/^getArtifactId/u.test(s.name)),"getArtifactId missing from outline");
  }));
  await step(()=>c.series("textDocument/semanticTokens/full",{textDocument:{uri:c.file(HELPER).uri}},v=>{assert(v?.data?.length>0&&v.data.length%5===0,"semantic tokens missing");}));
  const probe=withProbe(helper),cursor=probe.indexOf("        project.\n")+"        project.".length;c.change(HELPER,probe);
  const complete=()=>({textDocument:{uri:c.file(HELPER).uri},position:position(probe,cursor)});
  await step(()=>c.series("textDocument/completion",complete(),v=>completionOracle(v,["getGroupId","getArtifactId","addAttachedArtifact"])));
  // After an edit: a new member on MavenProject must be offered at the unchanged probe.
  const edited=project.replace("public String getGroupId() {","public void benchmarkAdded() {}\n\n    public String getGroupId() {");
  const trigger=c.change(PROJECT,edited).trigger;
  await step(()=>c.transition("textDocument/completion",complete,v=>completionOracle(v,["benchmarkAdded","getGroupId"]),trigger,"new MavenProject member offered after the edit"));
  // After an error: the versioned diagnostic that marks it.
  const since=c.client.notifications.length,broken=probe.replace("        project.\n","        project.benchmarkMissing();\n"),changed=c.change(HELPER,broken);
  await step(()=>observeDiagnostics(c,c.file(HELPER).uri,changed.trigger,errorAt("benchmarkMissing"),since,"changed_diagnostic"));
}

export async function runLifecycle(o:LifecycleOptions){
  settingsXml(o.repository,path.join(o.repository,"settings.xml"));
  rmSync(o.state,{recursive:true,force:true});
  const fixture=projectFixture(o.project,o.repository),phases:Record<string,number|null>={},operations:any[]=[],errors:string[]=[];
  let daemon:JvmdDaemon|undefined,running:Launch|undefined;
  // Open the workspace and wait for it to be ready: JVMD's initialize resolves the project, JDTLS reports ServiceReady.
  const open=async(reuseState:boolean)=>{
    const started=performance.now();
    running=await launch({server:o.server,root:o.project,state:path.join(o.state,"server"),javaHome:o.javaHome,image:o.image,jdtlsHome:o.jdtlsHome,daemon,reuseState});
    const c=new ScenarioContext(running.client,fixture,o.server,o.openTimeout,o.warmup,o.samples);c.javaHome=o.javaHome;c.allocation=running.allocation;c.operations=operations;
    await c.initialize();const ms=performance.now()-started;c.timeout=o.timeout;return {c,ms};
  };
  const first=async(c:ScenarioContext,state:string)=>{
    await c.open(HELPER);const call=c.text(HELPER).indexOf("project.addAttachedArtifact(");
    await step(()=>c.query("textDocument/hover",at(c,HELPER,"addAttachedArtifact(",call),v=>hoverOracle(v,"addAttachedArtifact","void"),state));
    return operations.at(-1).latencyMs;
  };
  const pid=()=>o.server==="jvmd"?daemon?.process.pid:running?.client.child.pid;
  try{
    if(o.server==="jvmd"){
      daemon=await JvmdDaemon.start({javaHome:o.javaHome,image:o.image,state:path.join(o.state,"jvmd"),repository:o.repository});
      phases.machine_index_ms=daemon.readyMs;phases.indexed_artifacts=(await daemon.status()).index?.total??null;
    }
    let {c,ms}=await open(false);phases.open_ms=ms;
    await c.open(PROJECT);await c.open(HELPER);await queries(c);phases.rss_bytes=rssBytes(pid());
    await running!.stop({closeSession:false});running=undefined;
    if(o.server==="jvmd"){
      // Reconnect, as when an editor window reopens: the daemon and its session are still warm.
      ({c,ms}=await open(true));phases.reconnect_open_ms=ms;phases.reconnect_first_ms=await first(c,"reconnect_first");
      await running!.stop();running=undefined;
      await daemon!.stop();daemon=await JvmdDaemon.start({javaHome:o.javaHome,image:o.image,state:path.join(o.state,"jvmd"),repository:o.repository});
      phases.restart_index_ms=daemon.readyMs;
    }
    ({c,ms}=await open(true));phases.restart_open_ms=ms;phases.restart_first_ms=await first(c,"restart_first");
  }catch(error){errors.push(String(error).split("\n")[0]);}
  finally{
    if(running)await running.stop().catch(()=>undefined);
    if(daemon)await daemon.stop().catch(()=>undefined);
  }
  const failed=operations.filter(op=>op.outcome!=="pass");
  return {server:o.server,project:"apache/maven",phases,operations:operations.map(({rawResult,...op}:any)=>op),
    outcome:errors.length?"harness_error":failed.length?failed[0].outcome:"pass",error:errors[0]??failed[0]?.assertionError};
}
