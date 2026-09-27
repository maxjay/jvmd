import assert from "node:assert/strict";
import path from "node:path";
import {readFileSync,writeFileSync,existsSync} from "node:fs";
import {pathToFileURL,fileURLToPath} from "node:url";
import {type CaseDefinition,type ScenarioContext} from "../harness/ScenarioContext.ts";
import {range,position,offset,selected,applyTextEdits,chooseMethod,completionOracle,exactLocations} from "../harness/oracles.ts";
import {planWorkspaceEdit} from "../harness/workspaceEdit.ts";

const imports="package bench;\nimport java.util.Set;\nimport java.util.List;\npublic class Imports { public List<String> values; }\n";
const clean="package bench;\npublic class Clean { public String toString() { return \"ok\"; } }\n";
const action=(c:ScenarioContext,file:string,token:string)=>c.actionParams(file,token);
const format={tabSize:4,insertSpaces:true};
const nonemptyEdit=(v:any)=>assert(v&&(v.changes||v.documentChanges),"workspace edit missing");
function organized(c:ScenarioContext){const s=c.text("Imports.java");c.assert("used List import retained",s.includes("import java.util.List;"));c.assert("unused Set import removed",!s.includes("import java.util.Set;"));c.assert("field preserved",s.includes("public List<String> values;"));}
function preview(c:ScenarioContext,edit:any){return planWorkspaceEdit(c.fixture.root,Object.keys(c.fixture.files).map(name=>({uri:c.file(name).uri,text:c.text(name),version:c.documents.get(c.file(name).uri)?.version??null,open:c.documents.has(c.file(name).uri)})),edit);}
export const editingCases:CaseDefinition[]=[
  ...["request","command","save"].map(route=>({id:"FMT-02/imports-"+route,family:"FMT-02",apis:[route==="request"?"API-081":route==="command"?"API-082":"API-014"],
    extension:route!=="save",command:route==="command"?"java.edit.organizeImports":undefined,capability:route==="save"?"textDocumentSync.willSaveWaitUntil":undefined,
    fixture:{"Imports.java":imports},variant:"organize and apply; used import retained; unused import removed",
    run:async(c:ScenarioContext)=>{await c.open("Imports.java");
      if(route==="request"){const edit=await c.query("java/organizeImports",action(c,"Imports.java","Imports"),nonemptyEdit);c.applyWorkspaceEdit(edit);}
      else if(route==="command"){
        const before=c.serverActions.length;await c.execute("java.edit.organizeImports",[c.file("Imports.java").uri],v=>assert(v!==false));
        c.assert("organize command requested client edit application",c.serverActions.slice(before).some(x=>x.method==="workspace/applyEdit"));
      }else{const edits=await c.query("textDocument/willSaveWaitUntil",{textDocument:{uri:c.file("Imports.java").uri},reason:1},v=>assert(Array.isArray(v)&&v.length>0));c.change("Imports.java",applyTextEdits(c.text("Imports.java"),edits));}
      organized(c);c.compileOracle();
    }})),
  {id:"FMT-02/cleanup",family:"FMT-02",apis:["API-083"],extension:true,fixture:{"Clean.java":clean},variant:"configured addOverride cleanup; idempotence",run:async c=>{
    await c.open("Clean.java");const edit=await c.query("java/cleanup",{uri:c.file("Clean.java").uri},nonemptyEdit);c.applyWorkspaceEdit(edit);
    c.assert("override annotation added to existing method",/@Override\s+public String toString/u.test(c.text("Clean.java")));c.assert("method body preserved",c.text("Clean.java").includes('return "ok";'));c.compileOracle();
    await c.query("java/cleanup",{uri:c.file("Clean.java").uri},v=>{if(v&&(v.changes||v.documentChanges)){const after=preview(c,v);assert.equal(after.get(c.file("Clean.java").uri)?.text,c.text("Clean.java"));}},"idempotence");
  }},
  {id:"FMT-01/string",family:"FMT-01",apis:["API-080"],command:"java.edit.stringFormatting",variant:"raw source formatting preserves tokens and is stable",run:async c=>{
    const source=c.file("Format.java").text;
    const formatted=await c.execute("java.edit.stringFormatting",[source,null,"0"],v=>{assert.equal(typeof v,"string");assert.equal(v.replace(/\s/gu,""),source.replace(/\s/gu,""));assert.notEqual(v,source);});
    await c.execute("java.edit.stringFormatting",[formatted,null,"0"],v=>assert.equal(v,formatted),"idempotence");
  }},
  {id:"FMT-03/string-paste",family:"FMT-03",apis:["API-084"],command:"java.edit.handlePasteEvent",variant:"escape a quoted string and compile applied paste",fixture:{"Paste.java":'package bench;\npublic class Paste { public String text = ""; }\n'},run:async c=>{
    await c.open("Paste.java");const source=c.text("Paste.java"),at=source.indexOf('""')+1,r={start:position(source,at),end:position(source,at)};
    const edit=await c.execute("java.edit.handlePasteEvent",[JSON.stringify({location:{uri:c.file("Paste.java").uri,range:r},text:'say "hello"',copiedDocumentUri:null,formattingOptions:format})],v=>assert.equal(v?.insertText,'say \\"hello\\"'));
    c.change("Paste.java",applyTextEdits(source,[{range:r,newText:edit.insertText}]));if(edit.additionalEdit)c.applyWorkspaceEdit(edit.additionalEdit);c.compileOracle();
  }},
  {id:"FMT-03/file-paste",family:"FMT-03",apis:["API-020"],command:"java.project.resolveText",variant:"file path inferred from package and public type",run:async c=>{
    const source="package bench; public class Pasted {}";
    await c.execute("java.project.resolveText",[path.join(c.fixture.root,"bench"),source],v=>{
      assert.equal(typeof v,"string");const file=v.startsWith("file:")?fileURLToPath(v):v;assert.equal(path.resolve(file),path.join(c.fixture.root,"bench/Pasted.java"));
    });
  }},
  {id:"FMT-03/semicolon",family:"FMT-03",apis:["API-085"],command:"java.edit.smartSemicolonDetection",variant:"semicolon suggested after constructor call",fixture:{"Semi.java":"package bench;\npublic class Semi {\n    private String text = new String()\n}\n"},run:async c=>{
    await c.open("Semi.java");const text=c.text("Semi.java"),end=text.indexOf("String()")+8;
    const result=await c.execute("java.edit.smartSemicolonDetection",[JSON.stringify({uri:c.file("Semi.java").uri,position:position(text,end-1)})],v=>{assert.equal(v?.uri,c.file("Semi.java").uri);assert.deepEqual(v.position,position(text,end));});
    c.change("Semi.java",applyTextEdits(text,[{range:{start:result.position,end:result.position},newText:";"}]));c.compileOracle();
  }},
  {id:"CMP-01/selection-command",family:"CMP-01",apis:["API-046"],command:"java.completion.onDidSelect",variant:"select the exact returned name member",run:async c=>{
    await c.open("Customer.java");await c.open("Use.java");const text=c.text("Use.java");
    const list=await c.query("textDocument/completion",{textDocument:{uri:c.file("Use.java").uri},position:position(text,text.indexOf("name()")+1)},v=>completionOracle(v,["name"]));
    const item=chooseMethod(list,"name");assert.equal(item.command?.command,"java.completion.onDidSelect");
    await c.execute(item.command.command,item.command.arguments,v=>assert(v!==false));
    await c.query("completionItem/resolve",item,v=>assert(JSON.stringify(v.documentation).includes("NAME_DOC_V1")),"after_selection");
  }},
  {id:"REF-02/quickfix",family:"REF-02",apis:["API-088","API-089"],capability:"codeActionProvider",fixture:{"Quick.java":"package bench;\npublic class Quick { public List<String> values; }\n"},variant:"resolve actions by effect; apply missing List import",run:async c=>{
    const opened=await c.open("Quick.java");const publication=await c.client.notification("textDocument/publishDiagnostics",v=>v.uri===opened.uri&&v.diagnostics?.some((d:any)=>String(d.message).includes("List")),0,c.timeout);
    const params=action(c,"Quick.java","List");params.context={diagnostics:publication.params.diagnostics,only:["quickfix"]} as any;
    const actions=await c.query("textDocument/codeAction",params,v=>assert(Array.isArray(v)&&v.length>0));let chosen:any;
    for(const candidate of actions.filter((a:any)=>a.kind?.startsWith("quickfix"))){
      const resolved=candidate.edit?candidate:await c.query("codeAction/resolve",candidate,v=>assert(v&&v.kind===candidate.kind),"resolve_candidate");
      let edit=resolved.edit;
      if(!edit&&resolved.command?.command==="java.apply.workspaceEdit")edit=resolved.command.arguments?.[0];
      if(edit&&preview(c,edit).get(opened.uri)?.text.includes("import java.util.List;")){chosen={resolved,edit};break;}
    }
    c.assert("a quick fix imports the independently expected java.util.List",!!chosen);
    c.applyWorkspaceEdit(chosen.edit);c.assert("field survives quick fix",c.text("Quick.java").includes("public List<String> values;"));c.compileOracle();
  }},
  {id:"REF-02/no-action",family:"REF-02",apis:["API-088"],capability:"codeActionProvider",variant:"no quick fix on clean package declaration",run:async c=>{
    await c.open("Customer.java");const params=action(c,"Customer.java","package bench;");params.context={diagnostics:[],only:["quickfix"]} as any;
    await c.series("textDocument/codeAction",params,v=>assert.deepEqual(v,[]));
  }},
  {id:"REF-03/change-signature",family:"REF-03",apis:["API-090","API-091"],extension:true,variant:"rename method through signature refactor and update callers",run:async c=>{
    await c.open("Customer.java");await c.open("Use.java");const context=action(c,"Customer.java","join");
    const info=await c.query("java/getChangeSignatureInfo",context,v=>{assert.equal(v.methodName,"join");assert.equal(v.returnType,"String");assert.deepEqual(v.parameters.map((x:any)=>x.name),["left","right"]);assert(v.methodIdentifier);});
    const result=await c.query("java/getRefactorEdit",{command:"changeSignature",context,options:format,commandArguments:[info.methodIdentifier,false,"combine",info.modifier,info.returnType,info.parameters,info.exceptions??[],false]},v=>{assert(!v?.errorMessage);nonemptyEdit(v?.edit);});
    c.applyWorkspaceEdit(result.edit);c.assert("declaration renamed",c.text("Customer.java").includes("combine(String"));c.assert("caller updated",c.text("Use.java").includes('customer.combine("a", 2)'));c.compileOracle();
  }},
  {id:"REF-03/extract-selection",family:"REF-03",apis:["API-090","API-092"],extension:true,variant:"infer and extract arithmetic expression",run:async c=>{
    await c.open("Format.java");const context=action(c,"Format.java","1+2"),text=c.text("Format.java");
    const choices=await c.query("java/inferSelection",{command:"extractVariable",context},v=>assert(Array.isArray(v)&&v.some((x:any)=>text.slice(x.offset,x.offset+x.length)==="1+2")));
    const selection=choices.find((x:any)=>text.slice(x.offset,x.offset+x.length)==="1+2");
    const result=await c.query("java/getRefactorEdit",{command:"extractVariable",context,commandArguments:[selection],options:format},v=>{assert(!v?.errorMessage);nonemptyEdit(v?.edit);});
    c.applyWorkspaceEdit(result.edit);const after=c.text("Format.java");c.assert("expression extracted to local int",/int\s+\w+\s*=\s*1\s*\+\s*2/u.test(after));c.assert("method returns extracted local",/return\s+\w+\s*;/u.test(after));c.compileOracle();
  }},
];
