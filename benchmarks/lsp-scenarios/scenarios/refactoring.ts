import assert from "node:assert/strict";
import path from "node:path";
import {mkdirSync,writeFileSync} from "node:fs";
import {pathToFileURL,fileURLToPath} from "node:url";
import {type CaseDefinition,type ScenarioContext} from "../harness/ScenarioContext.ts";
import {position,selected,exactLocations,range} from "../harness/oracles.ts";
import {exactLegacyHierarchy,legacyHierarchyArguments,type TypeGraph} from "../harness/hierarchy.ts";
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
  {id:"ENV-01/source-path",family:"ENV-01",apis:["API-033","API-034","API-035"],command:"java.project.addToSourcePath",sourceDirectory:"src",variant:"add/remove independent source root; exact reported membership",run:async c=>{
    const directory=path.join(c.fixture.root,"extra");c.createDisk("Extra.java","extra/other/Extra.java","package other; public class Extra { public int uniqueValue(){return 51;} }\n");
    const uri=pathToFileURL(directory).href;
    await c.execute("java.project.addToSourcePath",[uri],v=>assert.equal(v.status,true));
    const sourcePaths=(v:any)=>{assert.equal(v.status,true);return v.data.map((x:any)=>path.resolve(x.path));};
    await c.execute("java.project.listSourcePaths",[],v=>assert(sourcePaths(v).includes(directory)),"after_add");
    await c.open("Extra.java");await c.query("textDocument/documentSymbol",{textDocument:{uri:c.file("Extra.java").uri}},v=>assert(JSON.stringify(v).includes("uniqueValue")),"new_source_visibility");c.close("Extra.java");
    await c.execute("java.project.removeFromSourcePath",[uri],v=>assert.equal(v.status,true));
    await c.execute("java.project.listSourcePaths",[],v=>assert(!sourcePaths(v).includes(directory)),"after_remove");
  }},
  {id:"REF-03/move-resource",family:"REF-03",apis:["API-093","API-094"],extension:true,sourceDirectory:"src",variant:"move class to another package and apply exact reference updates",
    prepare:fixture=>{const file=path.join(fixture.root,"src/destination/Anchor.java");mkdirSync(path.dirname(file),{recursive:true});const text="package destination; public class Anchor {}\n";writeFileSync(file,text);fixture.files["Anchor.java"]={path:file,uri:pathToFileURL(file).href,text};},
    run:async c=>{await c.open("Customer.java");await c.open("Use.java");const source=c.file("Customer.java").uri;
      const choices=await c.query("java/getMoveDestinations",{moveKind:"moveResource",sourceUris:[source]},v=>{assert(!v.errorMessage);assert(v.destinations.some((p:any)=>p.displayName==="destination"));});
      const destination=choices.destinations.find((p:any)=>p.displayName==="destination"&&p.project==="benchmark");assert(destination);assert.equal(path.resolve(fileURLToPath(destination.uri)),path.join(c.fixture.root,"src/destination"));
      const result=await c.query("java/move",{moveKind:"moveResource",sourceUris:[source],destination,updateReferences:true},v=>{assert(!v.errorMessage);assert(v.edit);});
      c.applyWorkspaceEdit(result.edit);const moved=Object.entries(c.fixture.files).find(([,f])=>f.path===path.join(c.fixture.root,"src/destination/Customer.java"));assert(moved,"class was not moved to selected destination");
      c.assert("moved package declaration",c.text(moved[0]).includes("package destination;"));c.assert("caller imports moved class",c.text("Use.java").includes("import destination.Customer;"));c.compileOracle();
    }},
  {id:"REF-03/extract-interface",family:"REF-03",apis:["API-095","API-090"],extension:true,variant:"extract only name() to selected package and compile implementation",run:async c=>{
    await c.open("Customer.java");const context=c.actionParams("Customer.java","Customer");
    const info=await c.query("java/checkExtractInterfaceStatus",context,v=>{assert.equal(v.subTypeName,"Customer");assert(v.members.some((m:any)=>m.name==="name"&&m.typeName==="String"&&m.parameters.length===0));});
    const member=info.members.find((m:any)=>m.name==="name"&&m.parameters.length===0),destination=info.destinationResponse.destinations.find((p:any)=>p.displayName==="bench"&&p.project==="benchmark");assert(destination);
    const result=await c.query("java/getRefactorEdit",{command:"extractInterface",context,commandArguments:[[member.handleIdentifier],"Named",destination]},v=>{assert(!v.errorMessage);assert(v.edit);});
    c.applyWorkspaceEdit(result.edit);const entry=Object.entries(c.fixture.files).find(([,f])=>path.basename(f.path)==="Named.java");assert(entry,"new interface missing");
    const source=c.text(entry[0]);c.assert("interface contains selected signature only",/interface\s+Named/u.test(source)&&/String\s+name\s*\(\s*\)/u.test(source)&&!source.includes("number(")&&!source.includes("join("));
    c.assert("original type implements generated interface",/class\s+Customer\s+implements\s+Named/u.test(c.text("Customer.java")));c.compileOracle();
  }},
];
