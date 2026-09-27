import assert from "node:assert/strict";
import path from "node:path";
import {readFileSync} from "node:fs";
import {pathToFileURL} from "node:url";
import {type CaseDefinition,type ScenarioContext} from "../harness/ScenarioContext.ts";
import {dependencyFixture,DEPENDENCY_USE} from "../harness/dependencies.ts";
import {position,completionOracle,locations} from "../harness/oracles.ts";
const fixture={"DependencyUse.java":DEPENDENCY_USE};
const at=(c:ScenarioContext)=>({textDocument:{uri:c.file("DependencyUse.java").uri},position:position(c.text("DependencyUse.java"),c.text("DependencyUse.java").indexOf("original()")+1)});
async function binaryUri(c:ScenarioContext){await c.open("DependencyUse.java");const rows=await c.query("textDocument/definition",at(c),v=>{const rows=locations(v);assert.equal(rows.length,1);assert.equal(new URL(rows[0].uri).protocol,"jdt:");assert(new URL(rows[0].uri).pathname.endsWith("/dep/Library.java"));});return locations(rows)[0].uri;}
function sourceOracle(text:any,marker:string){assert.equal(typeof text,"string");assert(text.includes("package dep;"));assert(/class\s+Library\b/u.test(text));assert(/String\s+original\s*\(/u.test(text));assert(text.includes(marker));}
export const dependencyCases:CaseDefinition[]=[
  ...[["class-content","API-018","java/classFileContents"],["document-content","API-017","workspace/textDocumentContent"]].map(([id,api,method])=>({id:"ENV-02/"+id,family:"ENV-02",apis:[api],fixture,prepare:dependencyFixture(),extension:method.startsWith("java/"),capability:method.startsWith("workspace/")?"workspace.textDocumentContent":undefined,variant:"binary declaration resolves to exact attached source",
    run:async(c:ScenarioContext)=>{const uri=await binaryUri(c);await c.series(method,{uri},v=>sourceOracle(method.startsWith("workspace/")?v.text:v,"MEMBER_DOC_A"));}})),
  {id:"ENV-02/decompile",family:"ENV-02",apis:["API-019"],command:"java.decompile",fixture,prepare:dependencyFixture(false),variant:"no source attachment; compiled class member and constant recovered",run:async c=>{
    const uri=await binaryUri(c);await c.series("workspace/executeCommand",{command:"java.decompile",arguments:[uri]},v=>{sourceOracle(v,'"A"');assert(!v.includes("MEMBER_DOC_A"),"class-only artifact cannot contain stripped source comments");});
  }},
  {id:"ENV-02/source-attachment",family:"ENV-02",apis:["API-036","API-037","API-018"],command:"java.project.updateSourceAttachment",fixture,prepare:dependencyFixture(),variant:"replace source attachment; binary unchanged; fresh source witness",run:async c=>{
    const uri=await binaryUri(c),jar=path.join(c.fixture.root,"lib/library-A.jar"),before=readFileSync(jar);
    const current=await c.execute("java.project.resolveSourceAttachment",[JSON.stringify({classFileUri:uri})],v=>{assert(!v.errorMessage);assert.equal(path.resolve(v.attributes.jarPath),jar);assert(v.attributes.sourceAttachmentPath.endsWith("library-A-sources.jar"));});
    await c.query("java/classFileContents",{uri},v=>sourceOracle(v,"MEMBER_DOC_A"));
    await c.execute("java.project.updateSourceAttachment",[JSON.stringify({classFileUri:uri,attributes:{...current.attributes,sourceAttachmentPath:path.join(c.fixture.root,"lib/library-A-sources-v2.jar")}})],v=>assert(!v.errorMessage));
    await c.query("java/classFileContents",{uri},v=>sourceOracle(v,"MEMBER_DOC_ATTACHED_V2"),"changed_attachment");
    c.assert("source attachment mutation preserves binary bytes",readFileSync(jar).equals(before));
  }},
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
