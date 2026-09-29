import assert from "node:assert/strict";
import path from "node:path";
import {mkdirSync,writeFileSync,readFileSync} from "node:fs";
import {pathToFileURL,fileURLToPath} from "node:url";
import {type CaseDefinition,type ScenarioContext} from "../harness/ScenarioContext.ts";
import {position} from "../harness/oracles.ts";
import {exactLegacyHierarchy,legacyHierarchyArguments,type TypeGraph} from "../harness/hierarchy.ts";
import {exactJava,exactRefactor,sourceSnapshot} from "../harness/refactors.ts";
import {addSource} from "../harness/projectScope.ts";
import {exactSourceSymbol} from "../harness/oracles.ts";
const LEGACY_SOURCE="package bench;\ninterface Root {}\ninterface Base extends Root {}\ninterface Child extends Base {}\ninterface Grandchild extends Child {}\ninterface UnrelatedType {}\n";
const LEGACY_GRAPH:TypeGraph={Root:{kind:11,parents:[],children:["Base"]},Base:{kind:11,parents:["Root"],children:["Child"]},
  Child:{kind:11,parents:["Base"],children:["Grandchild"]},Grandchild:{kind:11,parents:["Child"],children:[]},UnrelatedType:{kind:11,parents:[],children:[]}};
export const refactoringCases:CaseDefinition[]=[
  ...[0,1,2].flatMap(direction=>[0,1,2].map(depth=>({id:direction===2&&depth===1?"REL-02/legacy-hierarchy":`REL-02/legacy-${["children","parents","both"][direction]}-depth-${depth}`,
    family:"REL-02",apis:["API-069","API-070"],command:"java.navigate.openTypeHierarchy",fixture:{"Hierarchy.java":LEGACY_SOURCE},
    variant:`independent legacy ${["children","parents","both"][direction]} traversal at depth ${depth}; first/repeat open and resolve`,run:async(c:ScenarioContext)=>{
      await c.open("Hierarchy.java");const text=c.text("Hierarchy.java"),uri=c.file("Hierarchy.java").uri;
      const params={textDocument:{uri},position:position(text,text.indexOf("interface Base")+11)};
      const check=(v:any,d:number)=>exactLegacyHierarchy(v,text,uri,LEGACY_GRAPH,"Base",direction,d);
      await c.series("workspace/executeCommand",{command:"java.navigate.openTypeHierarchy",arguments:legacyHierarchyArguments(params,direction,depth)},(v:any)=>{check(v,depth);c.assert("legacy open respects exact identity direction and depth",true);});
      // Resolve the exact raw depth-zero item, so eager open results cannot stand in for expansion.
      const item=await c.execute("java.navigate.openTypeHierarchy",legacyHierarchyArguments(params,direction,0),(v:any)=>check(v,0),"item_acquisition");
      await c.series("workspace/executeCommand",{command:"java.navigate.resolveTypeHierarchy",arguments:legacyHierarchyArguments(item,direction,depth)},(v:any)=>{check(v,depth);c.assert("legacy resolve respects exact identity direction and depth",true);});
    }}))),
  ...[0,1,2].map(direction=>({id:`REL-02/legacy-${["children","parents","both"][direction]}-parent-change`,family:"REL-02",apis:["API-069","API-070","API-011"],
    command:"java.navigate.openTypeHierarchy",fixture:{"Hierarchy.java":LEGACY_SOURCE},variant:"independent parent replacement; freshly prepared legacy item before every expansion",run:async(c:ScenarioContext)=>{
      await c.open("Hierarchy.java");const uri=c.file("Hierarchy.java").uri,focus=direction===0?"Base":"Child";
      const graph=structuredClone(LEGACY_GRAPH);
      const check=(v:any,depth:number)=>exactLegacyHierarchy(v,c.text("Hierarchy.java"),uri,graph,focus,direction,depth);
      const acquire=()=>c.execute("java.navigate.openTypeHierarchy",legacyHierarchyArguments({textDocument:{uri},position:position(c.text("Hierarchy.java"),c.text("Hierarchy.java").indexOf("interface "+focus)+11)},direction,0),v=>check(v,0),"item_acquisition");
      const params=async()=>({command:"java.navigate.resolveTypeHierarchy",arguments:legacyHierarchyArguments(await acquire(),direction,2)});
      await c.query("workspace/executeCommand",await params(),v=>check(v,2),"baseline");
      const before=c.text("Hierarchy.java"),after=before.replace("Child extends Base","Child extends UnrelatedType");
      c.assert("one independent legacy parent replacement",after!==before);
      graph.Base.children=[];graph.Child.parents=["UnrelatedType"];graph.UnrelatedType.children=["Child"];
      const trigger=c.change("Hierarchy.java",after).trigger;
      await c.transition("workspace/executeCommand",params,v=>check(v,2),trigger,"Child now extends UnrelatedType and is absent below Base; fresh raw legacy item on every attempt");
      c.assert("legacy parent replacement has exact current edges",true);c.compileOracle();
    }})),
  ...["add","remove"].map(mutation=>({id:"ENV-01/source-path-"+mutation,family:"ENV-01",apis:[mutation==="add"?"API-033":"API-034","API-035","API-057"],command:mutation==="add"?"java.project.addToSourcePath":"java.project.removeFromSourcePath",sourceDirectory:"src",variant:"one source-root change with exact semantic presence/absence and unchanged source controls",
    prepare:fixture=>{addSource(fixture,"ExtraRootWitness.java","extra/other/ExtraRootWitness.java","package other; public class ExtraRootWitness { public int uniqueValue(){return 51;} }\n");
      if(mutation==="remove"){const cp=path.join(fixture.root,".classpath");writeFileSync(cp,readFileSync(cp,"utf8").replace('</classpath>','<classpathentry kind="src" path="extra"/></classpath>'));}},
    run:async(c:ScenarioContext)=>{
      const before=c.state(),directory=path.join(c.fixture.root,"extra"),uri=pathToFileURL(directory).href;
      const paths=(present:boolean)=>(v:any)=>{assert.equal(v.status,true);assert.deepEqual(v.data.map((r:any)=>path.resolve(r.path)).sort(),[path.join(c.fixture.root,"src"),...(present?[directory]:[])].sort());};
      await c.execute("java.project.listSourcePaths",[],paths(mutation==="remove"),"baseline_paths");
      await c.query("workspace/symbol",{query:"ExtraRootWitness"},v=>exactSourceSymbol(v,c.file("ExtraRootWitness.java"),"ExtraRootWitness",mutation==="remove"),"baseline");
      await c.execute(mutation==="add"?"java.project.addToSourcePath":"java.project.removeFromSourcePath",[uri],v=>assert.equal(v.status,true),"root_change");
      const op=c.operations.at(-1),trigger=BigInt(op.startNs);c.mutations.push({kind:"source_root",mutation,uri,operationId:op.operationId,triggerNs:op.startNs,acknowledgedNs:op.endNs});
      await c.transition("workspace/symbol",()=>({query:"ExtraRootWitness"}),v=>exactSourceSymbol(v,c.file("ExtraRootWitness.java"),"ExtraRootWitness",mutation==="add"),trigger,"root-specific type follows the one declared classpath membership change");
      await c.execute("java.project.listSourcePaths",[],paths(mutation==="add"),"changed_paths");
      c.assert("source root mutation preserves all document and disk states",JSON.stringify(c.state())===JSON.stringify(before));
      c.assert("exactly one source-root change has semantic visibility evidence",c.mutations.length===1);
    }})),
  {id:"REF-03/move-resource",family:"REF-03",apis:["API-093","API-094"],extension:true,sourceDirectory:"src",variant:"move class to another package and apply exact reference updates",
    prepare:fixture=>{const file=path.join(fixture.root,"src/destination/Anchor.java");mkdirSync(path.dirname(file),{recursive:true});const text="package destination; public class Anchor {}\n";writeFileSync(file,text);fixture.files["Anchor.java"]={path:file,uri:pathToFileURL(file).href,text};},
    run:async c=>{await c.open("Customer.java");await c.open("Use.java");const before=sourceSnapshot(c),source=c.file("Customer.java").uri;
      const choices=await c.query("java/getMoveDestinations",{moveKind:"moveResource",sourceUris:[source]},v=>{assert(!v.errorMessage);assert(v.destinations.some((p:any)=>p.displayName==="destination"));});
      const destination=choices.destinations.find((p:any)=>p.displayName==="destination"&&p.project==="benchmark");assert(destination);assert.equal(path.resolve(fileURLToPath(destination.uri)),path.join(c.fixture.root,"src/destination"));
      const result=await c.query("java/move",{moveKind:"moveResource",sourceUris:[source],destination,updateReferences:true},v=>{assert(!v.errorMessage);assert(v.edit);});
      c.applyWorkspaceEdit(result.edit);const moved=Object.entries(c.fixture.files).find(([,f])=>f.path===path.join(c.fixture.root,"src/destination/Customer.java"));assert(moved,"class was not moved to selected destination");
      exactRefactor(before,sourceSnapshot(c),{
        "src/bench/Customer.java":null,"src/destination/Customer.java":before["src/bench/Customer.java"].text.replace("package bench;","package destination;"),
        ...Object.fromEntries(["Use.java","Generate.java"].map(name=>["src/bench/"+name,before["src/bench/"+name].text.replace("package bench;","package bench;\nimport destination.Customer;")]))});
      c.assert("move has exact destination package and all reference updates",true);c.assert("refactor preserves exact source membership and unrelated states",true);
      c.compileOracle('package bench; import destination.Customer; public class HarnessOracle { public static void main(String[] args) { Customer c=new Customer(); Use u=new Use(); if(!c.name().equals("Ada") || c.number()!=7 || !c.join("a",2).equals("a2") || !u.read(c).equals("Ada") || u.count(c)!=7 || u.twice(c)!=14 || !u.join(c).equals("a2")) throw new AssertionError("move changed behaviour"); } }');
    }},
  {id:"REF-03/extract-interface",family:"REF-03",apis:["API-095","API-090"],extension:true,variant:"extract only name() to selected package and compile implementation",run:async c=>{
    await c.open("Customer.java");const before=sourceSnapshot(c),context=c.actionParams("Customer.java","Customer");
    const info=await c.query("java/checkExtractInterfaceStatus",context,v=>{assert.equal(v.subTypeName,"Customer");assert(v.members.some((m:any)=>m.name==="name"&&m.typeName==="String"&&m.parameters.length===0));});
    const member=info.members.find((m:any)=>m.name==="name"&&m.parameters.length===0),destination=info.destinationResponse.destinations.find((p:any)=>p.displayName==="bench"&&p.project==="benchmark");assert(destination);
    const result=await c.query("java/getRefactorEdit",{command:"extractInterface",context,commandArguments:[[member.handleIdentifier],"Named",destination]},v=>{assert(!v.errorMessage);assert(v.edit);});
    c.applyWorkspaceEdit(result.edit);const entry=Object.entries(c.fixture.files).find(([,f])=>path.basename(f.path)==="Named.java");assert(entry,"new interface missing");
    assert.equal(entry[1].path,path.join(c.fixture.root,"bench/Named.java"),"interface created outside selected package");
    const source=c.text(entry[0]),expected="package bench; public interface Named { String name(); }";
    exactJava(source.replace(/public\s+String\s+name/gu,"String name"),expected);
    const customer=c.text("Customer.java"),expectedCustomer=before["bench/Customer.java"].text.replace("class Customer","class Customer implements Named");
    exactJava(customer.replace(/@Override\s+(?=public\s+String\s+name\s*\()/gu,""),expectedCustomer);
    exactRefactor(before,sourceSnapshot(c),{"bench/Customer.java":customer,"bench/Named.java":source});
    c.assert("interface has only selected member and original implementation is preserved",true);c.assert("refactor preserves exact source membership and unrelated states",true);
    c.compileOracle('package bench; public class HarnessOracle { public static void main(String[] args) throws Exception { Customer c=new Customer(); Named n=c; if(!n.name().equals("Ada") || c.number()!=7 || !c.join("a",2).equals("a2") || Named.class.getDeclaredMethods().length!=1 || Named.class.getDeclaredFields().length!=0 || Named.class.getInterfaces().length!=0 || Named.class.getDeclaredMethod("name").getReturnType()!=String.class) throw new AssertionError("interface contract changed"); c.label="Bea"; if(!n.name().equals("Bea")) throw new AssertionError("interface lost implementation"); } }');
  }},
];
