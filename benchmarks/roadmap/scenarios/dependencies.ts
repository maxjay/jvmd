import assert from "node:assert/strict";
import path from "node:path";
import {pathToFileURL} from "node:url";
import {type CaseDefinition,type ScenarioContext} from "../harness/ScenarioContext.ts";
import {dependencyFixture,DEPENDENCY_USE} from "../harness/dependencies.ts";
import {dependencyBinaryUri,attachmentOracle,attachedContentOracle,unattachedContentOracle,type AttachmentState} from "../harness/dependencySource.ts";
import {inventory} from "../harness/fixture.ts";
import {position,completionOracle} from "../harness/oracles.ts";
const fixture={"DependencyUse.java":DEPENDENCY_USE};
const at=(c:ScenarioContext)=>({textDocument:{uri:c.file("DependencyUse.java").uri},position:position(c.text("DependencyUse.java"),c.text("DependencyUse.java").indexOf("original()")+1)});
async function binaryUri(c:ScenarioContext){await c.open("DependencyUse.java");const rows=await c.query("textDocument/definition",at(c),dependencyBinaryUri,"binary_uri_acquisition");return dependencyBinaryUri(rows);}
const metadataParams=(uri:string)=>({command:"java.project.resolveSourceAttachment",arguments:[JSON.stringify({classFileUri:uri})]});
async function updateAttachment(c:ScenarioContext,uri:string){
  const current=await c.query("workspace/executeCommand",metadataParams(uri),v=>attachmentOracle(v,c.fixture.root,"attached"),"baseline_attachment");
  const attributes={...current.attributes,sourceAttachmentPath:path.join(c.fixture.root,"lib/library-A-sources-v2.jar")};
  await c.execute("java.project.updateSourceAttachment",[JSON.stringify({classFileUri:uri,attributes})],v=>{assert(v&&typeof v==="object");assert(!v.errorMessage);assert(v.attributes==null);},"attachment_update");
  const trigger=BigInt(c.operations.at(-1).startNs);c.mutations.push({kind:"source_attachment",uri,before:current.attributes,after:attributes,triggerNs:String(trigger)});return trigger;
}
function preserve(c:ScenarioContext,before:any,lib:any){
  c.assert("source retrieval preserves binary and all archive bytes",JSON.stringify(inventory(path.join(c.fixture.root,"lib")))===JSON.stringify(lib));
  c.assert("source retrieval preserves caller and unrelated document states",JSON.stringify(c.state())===JSON.stringify(before));
}
export const dependencyCases:CaseDefinition[]=[
  ...[["class-content","API-018","java/classFileContents"],["document-content","API-017","workspace/textDocumentContent"]].flatMap(([id,api,method])=>(["attached","unattached","updated"] as AttachmentState[]).map(state=>({
    id:"ENV-02/"+id+(state==="attached"?"":"-"+state),family:"ENV-02",apis:[api,"API-036",...(state==="updated"?["API-037"]:[])],fixture,prepare:dependencyFixture(state!=="unattached"),extension:method.startsWith("java/"),capability:method.startsWith("workspace/")?"workspace.textDocumentContent":undefined,
    variant:"independent "+state+" "+id+"; source identity, first/repeat, metadata and unchanged binary",
    run:async(c:ScenarioContext)=>{
      const uri=await binaryUri(c),before=c.state(),lib=inventory(path.join(c.fixture.root,"lib"));
      const content=(value:any,wanted:AttachmentState)=>{const text=method.startsWith("workspace/")?value?.text:value;if(wanted==="unattached")unattachedContentOracle(text);else attachedContentOracle(text,wanted);c.assert("dependency content matches declared attachment state",true,{state:wanted,uri});};
      if(state==="updated"){
        await c.query(method,{uri},v=>content(v,"attached"),"baseline_content");
        const trigger=await updateAttachment(c,uri);
        await c.transition(method,()=>({uri}),v=>content(v,"updated"),trigger,"new attachment comment and complete source identify V2; binary unchanged");
        await c.query(method,{uri},v=>content(v,"updated"),"changed_repeat");
      }else await c.series(method,{uri},v=>content(v,state));
      // Metadata follows content so it cannot silently warm the target after update.
      await c.query("workspace/executeCommand",metadataParams(uri),v=>attachmentOracle(v,c.fixture.root,state),"content_metadata_control");
      preserve(c,before,lib);c.compileOracle();
    }}))),
  {id:"ENV-02/decompile",family:"ENV-02",apis:["API-019","API-036"],command:"java.decompile",fixture,prepare:dependencyFixture(false),variant:"unattached decompiler first/repeat; semantic declaration and compiled constant, no source golden",run:async c=>{
    const uri=await binaryUri(c),before=c.state(),lib=inventory(path.join(c.fixture.root,"lib"));
    await c.series("workspace/executeCommand",{command:"java.decompile",arguments:[uri]},v=>{unattachedContentOracle(v,true);c.assert("decompiled content identifies binary A without attachment comments",true);});
    await c.query("workspace/executeCommand",metadataParams(uri),v=>attachmentOracle(v,c.fixture.root,"unattached"),"content_metadata_control");preserve(c,before,lib);
  }},
  ...(["attached","unattached","updated"] as AttachmentState[]).map(state=>({
    id:state==="updated"?"ENV-02/source-attachment":"ENV-02/attachment-metadata"+(state==="attached"?"":"-unattached"),family:"ENV-02",apis:["API-036",...(state==="updated"?["API-037"]:[])],command:state==="updated"?"java.project.updateSourceAttachment":"java.project.resolveSourceAttachment",fixture,prepare:dependencyFixture(state!=="unattached"),
    variant:"independent "+state+" attachment metadata; exact archive identities and repeat",run:async(c:ScenarioContext)=>{
      const uri=await binaryUri(c),before=c.state(),lib=inventory(path.join(c.fixture.root,"lib")),check=(v:any)=>{attachmentOracle(v,c.fixture.root,state);c.assert("attachment metadata matches the exact declared archive",true,{state,uri});};
      if(state==="updated"){
        const trigger=await updateAttachment(c,uri);
        await c.transition("workspace/executeCommand",()=>metadataParams(uri),check,trigger,"attachment metadata selects the exact V2 source archive");
        await c.query("workspace/executeCommand",metadataParams(uri),check,"changed_repeat");
      }else await c.series("workspace/executeCommand",metadataParams(uri),check);
      preserve(c,before,lib);
    }})),
  {id:"ENV-01/classpath-replacement",family:"ENV-01",apis:["API-028","API-032","API-031"],command:"java.project.updateClassPaths",sourceDirectory:"src",fixture,prepare:dependencyFixture(),variant:"replace A with B; old member removed and new member visible at unchanged caller",run:async c=>{
    await c.open("DependencyUse.java");const params=()=>({...at(c),position:position(c.text("DependencyUse.java"),c.text("DependencyUse.java").indexOf("original()"))});
    await c.query("textDocument/completion",params(),v=>completionOracle(v,["original"],["next"]));
    const key="org.eclipse.jdt.ls.core.classpathEntries",uri=pathToFileURL(c.fixture.root).href;
    const settings=await c.execute("java.project.getSettings",[uri,[key]],v=>assert(v[key].some((x:any)=>x.path.endsWith("library-A.jar"))));
    const entries=settings[key].map((e:any)=>e.path.endsWith("library-A.jar")?{...e,path:path.join(c.fixture.root,"lib/library-B.jar")}:e);
    await c.execute("java.project.updateClassPaths",[uri,JSON.stringify({classpathEntries:entries})],v=>assert.equal(v,null));
    const update=c.operations.at(-1);c.mutations.push({kind:"classpath_replacement",before:settings[key],after:entries,triggerNs:update.startNs});
    await c.transition("textDocument/completion",params,v=>completionOracle(v,["next"],["original"]),BigInt(update.startNs),"new dependency member next replaces original");
    await c.execute("java.project.getClasspaths",[uri,JSON.stringify({scope:"runtime"})],v=>{assert(v.classpaths.includes(path.join(c.fixture.root,"lib/library-B.jar")));assert(!v.classpaths.includes(path.join(c.fixture.root,"lib/library-A.jar")));});
    c.change("DependencyUse.java",c.text("DependencyUse.java").replace("original()","next()"));c.fixture.classpath=[path.join(c.fixture.root,"lib/library-B.jar")];c.compileOracle();
  }},
];
