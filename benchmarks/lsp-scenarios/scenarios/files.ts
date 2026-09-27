import assert from "node:assert/strict";
import path from "node:path";
import {readFileSync,writeFileSync,existsSync} from "node:fs";
import {pathToFileURL,fileURLToPath} from "node:url";
import {type CaseDefinition,type ScenarioContext} from "../harness/ScenarioContext.ts";
import {exactLocations,range,position,hoverOracle,selected} from "../harness/oracles.ts";
import {createFixture,inventory} from "../harness/fixture.ts";
const at=(c:ScenarioContext,file:string,token:string)=>({textDocument:{uri:c.file(file).uri},position:position(c.text(file),c.text(file).indexOf(token)+1)});
const normalize=(uri:string)=>path.resolve(fileURLToPath(uri));
const dynamic='package bench;\npublic class BenchmarkExternalProvider { public String value() { return "first"; } }\n';
export const fileCases:CaseDefinition[]=[
  ...["create","modify","delete"].flatMap(mutation=>["external","search"].map(route=>({id:`DOC-02/${route}-${mutation}`,family:"DOC-02",apis:["API-015"],capability:route==="search"?"workspaceSymbolProvider":"definitionProvider",fixture:{"ExternalUse.java":"package bench;\npublic class ExternalUse { public Object read(BenchmarkExternalProvider d) { return d.value(); } }\n",...(mutation==="create"?{}:{"BenchmarkExternalProvider.java":dynamic})},variant:`independently reset ${route} probe for external ${mutation}`,run:async(c:ScenarioContext)=>{
    await c.open("ExternalUse.java");
    const definition=(v:any)=>exactLocations(v,[{uri:c.file("BenchmarkExternalProvider.java").uri,range:range(c.text("BenchmarkExternalProvider.java"),"value")}]);
    const symbol=(v:any)=>{assert(Array.isArray(v));const rows=v.filter(r=>r.name==="BenchmarkExternalProvider");assert.equal(rows.length,1);
      assert.equal(rows[0].kind,5);exactLocations(rows.map(r=>r.location),[{uri:c.file("BenchmarkExternalProvider.java").uri,range:range(c.text("BenchmarkExternalProvider.java"),"BenchmarkExternalProvider")}]);};
    if(mutation!=="create"){
      if(route==="search")await c.query("workspace/symbol",{query:"BenchmarkExternalProvider"},symbol,"baseline");
      else{
        await c.query("textDocument/definition",at(c,"ExternalUse.java","value()"),definition,"baseline");
        await c.query("textDocument/hover",at(c,"ExternalUse.java","value()"),v=>hoverOracle(v,"value","String"),"baseline");
      }
    }
    const since=c.client.notifications.length;
    const trigger=mutation==="create"?c.createDisk("BenchmarkExternalProvider.java","bench/BenchmarkExternalProvider.java",dynamic):mutation==="modify"?
      c.writeDisk("BenchmarkExternalProvider.java","\n"+dynamic.replace('String value() { return "first";','int value() { return 19;')):c.deleteDisk("BenchmarkExternalProvider.java");
    if(route==="search")await c.transition("workspace/symbol",()=>({query:"BenchmarkExternalProvider"}),mutation==="delete"?v=>assert.deepEqual(v,[]):symbol,trigger,mutation==="delete"?"deleted BenchmarkExternalProvider is absent from symbol search":"BenchmarkExternalProvider symbol has exact current URI and declaration range");
    else await c.transition("textDocument/definition",()=>at(c,"ExternalUse.java","value()"),mutation==="delete"?v=>exactLocations(v,[]):definition,trigger,"external "+mutation+" changes exact caller target");
    if(route==="external"&&mutation!=="delete"){
      await c.transition("textDocument/hover",()=>at(c,"ExternalUse.java","value()"),v=>hoverOracle(v,"value",mutation==="modify"?"int":"String"),trigger,"external provider return type follows disk content");c.compileOracle();
    }else if(route==="external"){
      const diag=await c.client.notification("textDocument/publishDiagnostics",p=>p.uri===c.file("ExternalUse.java").uri&&p.diagnostics?.some((d:any)=>String(d.message).includes("BenchmarkExternalProvider")),since,c.timeout);
      c.assert("deletion invalidates caller type",diag.params.diagnostics.some((d:any)=>selected(c.text("ExternalUse.java"),d.range)==="BenchmarkExternalProvider"));
      c.recordOperation({schemaVersion:1,operationId:"external-delete-diagnostic",method:"textDocument/publishDiagnostics",state:"changed_diagnostic",outcome:"pass",startNs:String(trigger),endNs:diag.timeNs,triggerNs:String(trigger),transitionMs:Number(BigInt(diag.timeNs)-trigger)/1e6,rawResult:diag.params,freshness:{status:"verified",witness:"previously resolved provider removed; new BenchmarkExternalProvider error at exact caller range"}});
    }
    c.assert("exactly one external mutation",c.mutations.filter(m=>m.kind.startsWith("external_")).length===1);
  }}))),
  {id:"REF-01/file-rename",family:"REF-01",apis:["API-016"],capability:"workspace.fileOperations.willRename",variant:"rename public provider file, apply server edit, update callers and compile",run:async c=>{
    await c.open("Customer.java");await c.open("Use.java");const before=c.file("Customer.java").uri,after=pathToFileURL(path.join(c.fixture.root,"bench/Client.java")).href,files=[{oldUri:before,newUri:after}];
    const edit=await c.query("workspace/willRenameFiles",{files},v=>assert(v?.documentChanges||v?.changes));c.applyWorkspaceEdit(edit);
    if(existsSync(fileURLToPath(before)))c.applyWorkspaceEdit({documentChanges:[{kind:"rename",oldUri:before,newUri:after}]});
    const trigger=c.client.notify("workspace/didRenameFiles",{files});
    const entry=Object.entries(c.fixture.files).find(([,f])=>f.uri===after);assert(entry,"renamed provider absent");
    c.assert("public declaration renamed",c.text(entry[0]).includes("public class Client"));c.assert("caller type updated",c.text("Use.java").includes("Client customer"));c.compileOracle();
    await c.transition("textDocument/definition",()=>at(c,"Use.java","number()"),v=>exactLocations(v,[{uri:after,range:range(c.text(entry[0]),"number")}]),trigger,"definition uses renamed URI");
  }},
  {id:"PRJ-01/workspace-folders",family:"PRJ-01",apis:["API-021","API-025"],command:"java.project.getAll",variant:"add and remove a second independent workspace; exact project set",run:async c=>{
    const other=createFixture(path.join(c.fixture.root,"..","second-workspace"));const project=path.join(other.root,".project");writeFileSync(project,readFileSync(project,"utf8").replace("<name>benchmark</name>","<name>benchmark_second</name>"));
    c.mutations.push({kind:"new_workspace",root:other.root,inputs:inventory(other.root)});
    const folder={uri:pathToFileURL(other.root).href,name:"benchmark_second"},expected=[c.fixture.root,other.root].sort();
    const added=c.client.notify("workspace/didChangeWorkspaceFolders",{event:{added:[folder],removed:[]}});
    await c.transition("workspace/executeCommand",()=>({command:"java.project.getAll",arguments:[]}),v=>assert.deepEqual(v.map(normalize).sort(),expected),added,"second imported project visible");
    const removed=c.client.notify("workspace/didChangeWorkspaceFolders",{event:{added:[],removed:[folder]}});
    await c.transition("workspace/executeCommand",()=>({command:"java.project.getAll",arguments:[]}),v=>assert.deepEqual(v.map(normalize),[c.fixture.root]),removed,"second project removed");
  }},
  {id:"PRJ-01/import-membership",family:"PRJ-01",apis:["API-027","API-025"],command:"java.project.changeImportedProjects",variant:"explicit remove/reimport updates project membership",run:async c=>{
    const uri=pathToFileURL(c.fixture.root).href;await c.execute("java.project.changeImportedProjects",[[],[],[uri]],v=>assert.equal(v,null));
    await c.transition("workspace/executeCommand",()=>({command:"java.project.getAll",arguments:[]}),v=>assert(!v.map(normalize).includes(c.fixture.root)),BigInt(c.operations.at(-1).startNs),"removed project absent");
    await c.execute("java.project.changeImportedProjects",[[],[uri],[]],v=>assert.equal(v,null));
    await c.transition("workspace/executeCommand",()=>({command:"java.project.getAll",arguments:[]}),v=>assert.deepEqual(v.map(normalize),[c.fixture.root]),BigInt(c.operations.at(-1).startNs),"reimported project restored");
  }},
  {id:"GEN-01/module-info",family:"GEN-01",apis:["API-108"],command:"java.project.createModuleInfo",sourceDirectory:"src",variant:"module descriptor exports exact fixture package and compiles",run:async c=>{
    const uri=await c.execute("java.project.createModuleInfo",[pathToFileURL(c.fixture.root).href],v=>assert.equal(normalize(v),path.join(c.fixture.root,"src/module-info.java")));
    c.registerFile("module-info.java",fileURLToPath(uri));const text=c.text("module-info.java");c.assert("module identity and export",/module\s+benchmark\s*\{/u.test(text)&&/exports\s+bench\s*;/u.test(text));c.compileOracle();
  }},
  ...["validate","refresh"].map(route=>({id:"DIA-01/explicit-"+route,family:"DIA-01",apis:[route==="validate"?"API-109":"API-110","API-111"],extension:route==="validate",command:route==="refresh"?"java.project.refreshDiagnostics":undefined,variant:"explicit validation of uniquely broken current source",
    run:async(c:ScenarioContext)=>{await c.open("Customer.java");const since=c.client.notifications.length,text=c.text("Customer.java").replace("return 7;","return uniqueExplicitError;");const changed=c.change("Customer.java",text);
      const trigger=route==="validate"?c.client.notify("java/validateDocument",{textDocument:{uri:c.file("Customer.java").uri}}):changed.trigger;
      if(route==="refresh")await c.execute("java.project.refreshDiagnostics",[c.file("Customer.java").uri,"thisFile",false,true],v=>assert.equal(v,null));
      const diag=await c.client.notification("textDocument/publishDiagnostics",p=>p.uri===c.file("Customer.java").uri&&p.diagnostics?.some((d:any)=>String(d.message).includes("uniqueExplicitError")),since,c.timeout);
      c.assert("explicit validation marks changed expression",diag.params.diagnostics.some((d:any)=>selected(text,d.range)==="uniqueExplicitError"));
      c.recordOperation({schemaVersion:1,operationId:"explicit-diagnostic",method:"textDocument/publishDiagnostics",state:"changed",outcome:"pass",startNs:String(changed.trigger),endNs:diag.timeNs,triggerNs:String(changed.trigger),transitionMs:Number(BigInt(diag.timeNs)-changed.trigger)/1e6,rawResult:diag.params,freshness:{status:"verified",witness:"unique changed error at exact current range"},validationTriggerNs:String(trigger)});
    }})),
];
