import {type CaseDefinition,type ScenarioContext} from "../harness/ScenarioContext.ts";
import {dependencyFixture,dependencyTarget,attachmentOracle,attachedContentOracle,unattachedContentOracle,DEPENDENCY_USE} from "../harness/dependencies.ts";
import {position} from "../harness/oracles.ts";
const fixture={"DependencyUse.java":DEPENDENCY_USE};
const at=(c:ScenarioContext)=>({textDocument:{uri:c.file("DependencyUse.java").uri},position:position(c.text("DependencyUse.java"),c.text("DependencyUse.java").indexOf("original()")+1)});
/** Class-file content and attachment requests start from the URI the server's own definition issued. */
async function binaryUri(c:ScenarioContext,attached:boolean){
  await c.open("DependencyUse.java");
  const rows=await c.query("textDocument/definition",at(c),v=>dependencyTarget(v,c.fixture,{attached}),"binary_uri_acquisition");
  return dependencyTarget(rows,c.fixture,{attached});
}
const metadata=(uri:string)=>({command:"java.project.resolveSourceAttachment",arguments:[JSON.stringify({classFileUri:uri})]});

export const dependencyCases:CaseDefinition[]=[
  {id:"ENV-02/dependency-definition",family:"ENV-02",apis:["API-050","API-036"],capability:"definitionProvider",fixture,prepare:dependencyFixture(true),
    variant:"definition into a Maven dependency lands on the declaration in its -sources.jar",run:async c=>{
      await c.open("DependencyUse.java");await c.series("textDocument/definition",at(c),v=>dependencyTarget(v,c.fixture));c.compileOracle();
    }},
  ...([["class-content","API-018","java/classFileContents"],["document-content","API-017","workspace/textDocumentContent"]] as const).flatMap(([id,api,method])=>[true,false].map(attached=>({
    id:"ENV-02/"+id+(attached?"":"-unattached"),family:"ENV-02",apis:[api,"API-036"],method,fixture,prepare:dependencyFixture(attached),
    extension:method.startsWith("java/"),capability:method.startsWith("workspace/")?"workspace.textDocumentContent":undefined,
    variant:(attached?"with":"without")+" a -sources.jar; content of the class the definition named",
    run:async(c:ScenarioContext)=>{
      if(method.startsWith("java/"))await c.requireMethod(method,{uri:"file:///probe.class"});
      const uri=await binaryUri(c,attached),content=(v:any)=>{const text=method.startsWith("workspace/")?v?.text:v;attached?attachedContentOracle(text):unattachedContentOracle(text);};
      await c.series(method,{uri},content);
    }}))),
  {id:"ENV-02/decompile",family:"ENV-02",apis:["API-019","API-036"],command:"java.decompile",fixture,prepare:dependencyFixture(false),variant:"decompiled class without a -sources.jar",run:async c=>{
    const uri=await binaryUri(c,false);await c.series("workspace/executeCommand",{command:"java.decompile",arguments:[uri]},v=>unattachedContentOracle(v,true));
  }},
  ...[true,false].map(attached=>({id:"ENV-02/attachment-metadata"+(attached?"":"-unattached"),family:"ENV-02",apis:["API-036"],command:"java.project.resolveSourceAttachment",fixture,prepare:dependencyFixture(attached),
    variant:"source attachment of a Maven dependency "+(attached?"with":"without")+" a -sources.jar",run:async(c:ScenarioContext)=>{
      const uri=await binaryUri(c,attached);await c.series("workspace/executeCommand",metadata(uri),v=>attachmentOracle(v,c.fixture,attached));
    }})),
];
