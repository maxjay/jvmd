import assert from "node:assert/strict";
import path from "node:path";
import {mkdirSync,writeFileSync,readFileSync,copyFileSync} from "node:fs";
import {pathToFileURL} from "node:url";
import {type Fixture,inventory,sha} from "../harness/fixture.ts";
import {type CaseDefinition,type ScenarioContext} from "../harness/ScenarioContext.ts";
import {position,range,exactLocations,hoverOracle,completionOracle} from "../harness/oracles.ts";
import {dependencyFixture} from "../harness/dependencies.ts";
const at=(c:ScenarioContext,file:string,token:string)=>({textDocument:{uri:c.file(file).uri},position:position(c.text(file),c.text(file).indexOf(token)+1)});
const register=(f:Fixture,name:string,relative:string,text:string)=>{const file=path.join(f.root,relative);mkdirSync(path.dirname(file),{recursive:true});writeFileSync(file,text);f.files[name]={path:file,uri:pathToFileURL(file).href,text};};
const provider='package bench;\npublic class OverloadProvider { public Object choose(Object value) { return "old"; } }\n';
const use='package bench;\npublic class OverloadUse { public Object call(OverloadProvider p) { return p.choose("probe"); } }\n';
const external='package external;\npublic class Shadow { public static String marker = "external"; }\n';
const local='package bench;\npublic class Shadow { public static int marker = 42; }\n';
const namespaceUse='package bench;\nimport external.*;\npublic class NamespaceUse { public Object call() { return Shadow.marker; } }\n';
export function classpathMetadata(order:string[]){
  const entries=order.map(v=>`<classpathentry kind="lib" path="lib/library-${v}.jar"/>`).join("");
  const dependencies=order.map(v=>`<dependency><groupId>bench</groupId><artifactId>library-${v.toLowerCase()}</artifactId><version>1</version></dependency>`).join("");
  return {".classpath":`<classpath><classpathentry kind="src" path=""/><classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/><classpathentry kind="output" path="bin"/>${entries}</classpath>`,
    "pom.xml":`<project><modelVersion>4.0.0</modelVersion><groupId>bench</groupId><artifactId>ordered-classpath</artifactId><version>1</version><properties><maven.compiler.release>17</maven.compiler.release></properties><build><sourceDirectory>.</sourceDirectory></build><dependencies>${dependencies}</dependencies></project>`};
}
export const invalidationCases:CaseDefinition[]=[
  ...["body-only","overload-add"].map(mutation=>({id:"INV/"+mutation,family:"INV",apis:["API-050","API-011"],capability:"definitionProvider",
    fixture:{"OverloadProvider.java":provider,"OverloadUse.java":use},variant:"unchanged caller; "+mutation+" on its provider; exact overload definition",
    run:async(c:ScenarioContext)=>{
      await c.open("OverloadProvider.java");await c.open("OverloadUse.java");
      const target=(changed:boolean)=>range(c.text("OverloadProvider.java"),"choose",changed&&mutation==="overload-add"?c.text("OverloadProvider.java").indexOf("String choose"):0);
      const oracle=(changed:boolean)=>(v:any)=>exactLocations(v,[{uri:c.file("OverloadProvider.java").uri,range:target(changed)}]);
      await c.series("textDocument/definition",at(c,"OverloadUse.java","choose"),oracle(false));
      const before=c.text("OverloadUse.java"),text=mutation==="body-only"?provider.replace('"old"','"new"'):provider.replace(/\}\n$/u,' public String choose(String value) { return value; } }\n');
      const {trigger}=c.change("OverloadProvider.java",text);
      await c.transition("textDocument/definition",()=>at(c,"OverloadUse.java","choose"),oracle(true),trigger,
        mutation==="overload-add"?"new more-specific overload selected by unchanged String argument":"same declaration after provider body edit; no zero-work or processing claim");
      c.assert("overload mutation leaves caller bytes unchanged",c.text("OverloadUse.java")===before);c.compileOracle();
    }})),
  ...["add","remove","unrelated"].map(mutation=>({id:"INV/namespace-"+mutation,family:"INV",apis:["API-047","API-015"],capability:"hoverProvider",
    fixture:{"NamespaceUse.java":namespaceUse},variant:"same-package lookup versus on-demand import; independent namespace "+mutation,
    prepare:(f:Fixture)=>{register(f,"ExternalShadow.java","external/Shadow.java",external);if(mutation==="remove")register(f,"LocalShadow.java","bench/Shadow.java",local);},
    run:async(c:ScenarioContext)=>{
      await c.open("NamespaceUse.java");const before=c.text("NamespaceUse.java");
      await c.series("textDocument/hover",at(c,"NamespaceUse.java","marker"),v=>hoverOracle(v,"marker",mutation==="remove"?"int":"String"));
      const trigger=mutation==="remove"?c.deleteDisk("LocalShadow.java"):mutation==="add"?c.createDisk("LocalShadow.java","bench/Shadow.java",local):
        c.createDisk("OtherShadow.java","bench/OtherShadow.java",local.replace("class Shadow","class OtherShadow"));
      await c.transition("textDocument/hover",()=>at(c,"NamespaceUse.java","marker"),v=>hoverOracle(v,"marker",mutation==="add"?"int":"String"),trigger,
        mutation==="unrelated"?"unrelated namespace addition preserves imported field; no zero-work claim":"namespace membership switches exact field type in unchanged caller");
      c.assert("namespace mutation leaves caller bytes unchanged",c.text("NamespaceUse.java")===before);c.compileOracle();
    }})),
  ...["reorder","identical"].map(mutation=>({id:"INV/classpath-"+mutation,family:"INV",apis:["API-044","API-015"],capability:"completionProvider",
    fixture:{"ClasspathUse.java":'package bench; public class ClasspathUse { public Object call(dep.Library library) { return library.toString(); } }\n'},
    variant:"ordered duplicate binary namespace; "+mutation+" metadata with fixed archive bytes",
    prepare:(f:Fixture,java:string)=>{dependencyFixture(false)(f,java);for(const [name,text] of Object.entries(classpathMetadata(["A","B"])))writeFileSync(path.join(f.root,name),text);
      f.classpath=[path.join(f.root,"lib/library-A.jar"),path.join(f.root,"lib/library-B.jar")];
      const repository=path.join(f.root,"..","repository");
      for(const version of ["A","B"]){const artifact="library-"+version.toLowerCase(),directory=path.join(repository,"bench",artifact,"1");mkdirSync(directory,{recursive:true});
        copyFileSync(path.join(f.root,"lib/library-"+version+".jar"),path.join(directory,artifact+"-1.jar"));
        writeFileSync(path.join(directory,artifact+"-1.pom"),`<project><modelVersion>4.0.0</modelVersion><groupId>bench</groupId><artifactId>${artifact}</artifactId><version>1</version></project>`);
      }
      f.preparation={...f.preparation,orderedClasspath:["library-A.jar","library-B.jar"],repositoryInputs:inventory(repository),mutation};},
    run:async(c:ScenarioContext)=>{
      await c.open("ClasspathUse.java");const params=()=>({textDocument:{uri:c.file("ClasspathUse.java").uri},position:position(c.text("ClasspathUse.java"),c.text("ClasspathUse.java").indexOf("toString"))});
      await c.series("textDocument/completion",params(),v=>completionOracle(v,["original"],["next"]));
      const before=inventory(path.join(c.fixture.root,"lib")),order=mutation==="reorder"?["B","A"]:["A","B"],metadata=classpathMetadata(order),changes:any[]=[];
      for(const [name,text] of Object.entries(metadata)){const file=path.join(c.fixture.root,name),previous=readFileSync(file);writeFileSync(file,text);changes.push({uri:pathToFileURL(file).href,type:2,before:sha(previous),after:sha(text)});}
      const trigger=c.client.notify("workspace/didChangeWatchedFiles",{changes:changes.map(({uri,type})=>({uri,type}))});
      c.mutations.push({kind:"ordered_classpath",before:["A","B"],after:order,changes,triggerNs:String(trigger)});
      await c.transition("textDocument/completion",params,v=>completionOracle(v,[mutation==="reorder"?"next":"original"],[mutation==="reorder"?"original":"next"]),trigger,
        mutation==="reorder"?"first ordered binary wins duplicate namespace after metadata change":"identical classpath metadata preserves required member; no zero-work claim");
      c.assert("classpath change preserves every dependency byte",JSON.stringify(inventory(path.join(c.fixture.root,"lib")))===JSON.stringify(before));
    }})),
];
