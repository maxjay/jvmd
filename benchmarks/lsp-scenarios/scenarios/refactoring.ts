import assert from "node:assert/strict";
import path from "node:path";
import {mkdirSync,writeFileSync} from "node:fs";
import {pathToFileURL,fileURLToPath} from "node:url";
import {type CaseDefinition} from "../harness/ScenarioContext.ts";
import {position,selected,exactLocations,range} from "../harness/oracles.ts";
export const refactoringCases:CaseDefinition[]=[
  {id:"REL-02/legacy-hierarchy",family:"REL-02",apis:["API-069","API-070"],command:"java.navigate.openTypeHierarchy",variant:"legacy hierarchy item provenance and exact parents/children",run:async c=>{
    await c.open("Hierarchy.java");const text=c.text("Hierarchy.java"),params={textDocument:{uri:c.file("Hierarchy.java").uri},position:position(text,text.indexOf("class Base")+7)};
    const check=(v:any)=>{assert.equal(v.name,"Base");assert.equal(v.uri,c.file("Hierarchy.java").uri);assert.equal(selected(text,v.selectionRange),"Base");};
    const item=await c.execute("java.navigate.openTypeHierarchy",[JSON.stringify(params),2,0],check);
    await c.series("workspace/executeCommand",{command:"java.navigate.resolveTypeHierarchy",arguments:[JSON.stringify(item),2,1]},v=>{check(v);assert.deepEqual(v.children.map((x:any)=>x.name),["Child"]);assert.deepEqual(v.parents.map((x:any)=>x.name).sort(),["Object","Root"]);
      for(const child of v.children){assert.equal(child.uri,c.file("Hierarchy.java").uri);assert.equal(selected(text,child.selectionRange),"Child");}});
  }},
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
