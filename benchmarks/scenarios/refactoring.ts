import assert from "node:assert/strict";
import path from "node:path";
import {mkdirSync,writeFileSync} from "node:fs";
import {pathToFileURL,fileURLToPath} from "node:url";
import {type CaseDefinition} from "../harness/ScenarioContext.ts";
import {exactJava,exactRefactor,sourceSnapshot} from "../harness/refactors.ts";
import {MAIN} from "../harness/fixture.ts";
export const refactoringCases:CaseDefinition[]=[
  {id:"REF-03/move-resource",family:"REF-03",apis:["API-093","API-094"],extension:true,variant:"move class to another package and apply exact reference updates",
    prepare:fixture=>{const file=path.join(fixture.root,MAIN,"destination/Anchor.java");mkdirSync(path.dirname(file),{recursive:true});const text="package destination; public class Anchor {}\n";writeFileSync(file,text);fixture.files["Anchor.java"]={path:file,uri:pathToFileURL(file).href,text};},
    run:async c=>{await c.open("Customer.java");await c.open("Use.java");const before=sourceSnapshot(c),source=c.file("Customer.java").uri;
      const choices=await c.query("java/getMoveDestinations",{moveKind:"moveResource",sourceUris:[source]},v=>{assert(!v.errorMessage);assert(v.destinations.some((p:any)=>p.displayName==="destination"));});
      const destination=choices.destinations.find((p:any)=>p.displayName==="destination"&&p.project==="benchmark");assert(destination);assert.equal(path.resolve(fileURLToPath(destination.uri)),path.join(c.fixture.root,MAIN,"destination"));
      const result=await c.query("java/move",{moveKind:"moveResource",sourceUris:[source],destination,updateReferences:true},v=>{assert(!v.errorMessage);assert(v.edit);});
      c.applyWorkspaceEdit(result.edit);const moved=Object.entries(c.fixture.files).find(([,f])=>f.path===path.join(c.fixture.root,MAIN,"destination/Customer.java"));assert(moved,"class was not moved to selected destination");
      exactRefactor(before,sourceSnapshot(c),{
        ["bench/Customer.java"]:null,["destination/Customer.java"]:before["bench/Customer.java"].text.replace("package bench;","package destination;"),
        ...Object.fromEntries(["Use.java","Generate.java"].map(name=>["bench/"+name,before["bench/"+name].text.replace("package bench;","package bench;\nimport destination.Customer;")]))});
      c.assert("move has exact destination package and all reference updates",true);c.assert("refactor preserves exact source membership and unrelated states",true);
      c.compileOracle('package bench; import destination.Customer; public class HarnessOracle { public static void main(String[] args) { Customer c=new Customer(); Use u=new Use(); if(!c.name().equals("Ada") || c.number()!=7 || !c.join("a",2).equals("a2") || !u.read(c).equals("Ada") || u.count(c)!=7 || u.twice(c)!=14 || !u.join(c).equals("a2")) throw new AssertionError("move changed behaviour"); } }');
    }},
  {id:"REF-03/extract-interface",family:"REF-03",apis:["API-095","API-090"],extension:true,variant:"extract only name() to selected package and compile implementation",run:async c=>{
    await c.open("Customer.java");const before=sourceSnapshot(c),context=c.actionParams("Customer.java","Customer");
    const info=await c.query("java/checkExtractInterfaceStatus",context,v=>{assert.equal(v.subTypeName,"Customer");assert(v.members.some((m:any)=>m.name==="name"&&m.typeName==="String"&&m.parameters.length===0));});
    const member=info.members.find((m:any)=>m.name==="name"&&m.parameters.length===0),destination=info.destinationResponse.destinations.find((p:any)=>p.displayName==="bench"&&p.project==="benchmark");assert(destination);
    const result=await c.query("java/getRefactorEdit",{command:"extractInterface",context,commandArguments:[[member.handleIdentifier],"Named",destination]},v=>{assert(!v.errorMessage);assert(v.edit);});
    c.applyWorkspaceEdit(result.edit);const entry=Object.entries(c.fixture.files).find(([,f])=>path.basename(f.path)==="Named.java");assert(entry,"new interface missing");
    assert.equal(entry[1].path,path.join(c.fixture.root,MAIN,"bench/Named.java"),"interface created outside selected package");
    const source=c.text(entry[0]),expected="package bench; public interface Named { String name(); }";
    exactJava(source.replace(/public\s+String\s+name/gu,"String name"),expected);
    const customer=c.text("Customer.java"),expectedCustomer=before["bench/Customer.java"].text.replace("class Customer","class Customer implements Named");
    exactJava(customer.replace(/@Override\s+(?=public\s+String\s+name\s*\()/gu,""),expectedCustomer);
    exactRefactor(before,sourceSnapshot(c),{["bench/Customer.java"]:customer,["bench/Named.java"]:source});
    c.assert("interface has only selected member and original implementation is preserved",true);c.assert("refactor preserves exact source membership and unrelated states",true);
    c.compileOracle('package bench; public class HarnessOracle { public static void main(String[] args) throws Exception { Customer c=new Customer(); Named n=c; if(!n.name().equals("Ada") || c.number()!=7 || !c.join("a",2).equals("a2") || Named.class.getDeclaredMethods().length!=1 || Named.class.getDeclaredFields().length!=0 || Named.class.getInterfaces().length!=0 || Named.class.getDeclaredMethod("name").getReturnType()!=String.class) throw new AssertionError("interface contract changed"); c.label="Bea"; if(!n.name().equals("Bea")) throw new AssertionError("interface lost implementation"); } }');
  }},
];
