import assert from "node:assert/strict";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {MAIN} from "../harness/fixture.ts";
import {SETTINGS,type CaseDefinition,type ScenarioContext} from "../harness/ScenarioContext.ts";
import {range,position,hoverOracle,applyTextEdits,chooseMethod,completionOracle} from "../harness/oracles.ts";
import {noApplicableAction} from "../harness/codeActions.ts";
import {SELECTION_COMMAND} from "../harness/completionSelection.ts";
import {exactRefactor,sourceSnapshot,extractedSource,EXTRACT_SOURCE,EXTRACT_PROBE} from "../harness/refactors.ts";
import {planWorkspaceEdit} from "../harness/workspaceEdit.ts";

const imports="package bench;\nimport java.util.Set;\nimport java.util.List;\npublic class Imports { public List<String> values; }\n";
const clean="package bench;\npublic class Clean { public String toString() { return \"ok\"; } }\n";
const pastedField='    public List<String> names = List.of("pasted");\n';
const pasteTarget="package bench;\npublic class Paste {\n    // PASTE HERE\n}\n";
const action=(c:ScenarioContext,file:string,token:string)=>c.actionParams(file,token);
const format={tabSize:4,insertSpaces:true};
const nonemptyEdit=(v:any)=>assert(v&&(v.changes||v.documentChanges),"workspace edit missing");
function organized(c:ScenarioContext){const s=c.text("Imports.java");c.assert("used List import retained",s.includes("import java.util.List;"));c.assert("unused Set import removed",!s.includes("import java.util.Set;"));c.assert("field preserved",s.includes("public List<String> values;"));}
function preview(c:ScenarioContext,edit:any){return planWorkspaceEdit(c.fixture.root,Object.keys(c.fixture.files).map(name=>({uri:c.file(name).uri,text:c.text(name),version:c.documents.get(c.file(name).uri)?.version??null,open:c.documents.has(c.file(name).uri)})),edit);}
const texts=(c:ScenarioContext)=>Object.fromEntries(Object.keys(c.fixture.files).sort().map(name=>[name,c.text(name)]));
function onlyTargetChanged(c:ScenarioContext,before:Record<string,string>,name:string){
  const after=texts(c);assert.deepEqual(Object.keys(after),Object.keys(before),"cleanup changed fixture membership");
  for(const file of Object.keys(before))if(file!==name)assert.equal(after[file],before[file],"cleanup changed unrelated source: "+file);
  c.assert("cleanup preserves every unrelated source",true);
}
export const editingCases:CaseDefinition[]=[
  ...["request","command","save"].map(route=>({id:"FMT-02/imports-"+route,family:"FMT-02",apis:[route==="request"?"API-081":route==="command"?"API-082":"API-014"],
    extension:route!=="save",command:route==="command"?"java.edit.organizeImports":undefined,capability:route==="save"?"textDocumentSync.willSaveWaitUntil":undefined,
    fixture:{"Imports.java":imports},variant:"independent organize route preserves unrelated code and is stable on repeat",
    run:async(c:ScenarioContext)=>{await c.open("Imports.java");const before=texts(c);
      const run=async(state:string)=>{
        if(route==="request"){
          const edit=await c.query("java/organizeImports",action(c,"Imports.java","Imports"),v=>{if(state==="first_use")nonemptyEdit(v);else if(v)assert.equal(preview(c,v).get(c.file("Imports.java").uri)?.text,c.text("Imports.java"));},state);
          if(edit)c.applyWorkspaceEdit(edit);
        }else if(route==="command"){
          const start=c.serverActions.length;await c.execute("java.edit.organizeImports",[c.file("Imports.java").uri],v=>assert(v!==false),state);
          if(state==="first_use")c.assert("organize command requested client edit application",c.serverActions.slice(start).some(x=>x.method==="workspace/applyEdit"));
        }else{
          const source=c.text("Imports.java"),edits=await c.query("textDocument/willSaveWaitUntil",{textDocument:{uri:c.file("Imports.java").uri},reason:1},v=>{if(state==="first_use")assert(Array.isArray(v)&&v.length>0);else assert.equal(applyTextEdits(source,v??[]),source);},state);
          const after=applyTextEdits(source,edits??[]);if(after!==source)c.change("Imports.java",after);
        }
      };
      await run("first_use");organized(c);onlyTargetChanged(c,before,"Imports.java");
      c.assert("organize imports preserves all non-import fixture code",c.text("Imports.java").replace(/\s/gu,"")===imports.replace("import java.util.Set;","").replace(/\s/gu,""));c.compileOracle();
      const organizedState=texts(c);await run("idempotence");assert.deepEqual(texts(c),organizedState,"repeated organize-imports changed source");c.assert("repeated cleanup preserves exact source state",true);
    }})),
  {id:"FMT-02/cleanup",family:"FMT-02",apis:["API-083"],extension:true,fixture:{"Clean.java":clean},variant:"configured addOverride cleanup; idempotence",run:async c=>{
    await c.open("Clean.java");const before=texts(c),edit=await c.query("java/cleanup",{uri:c.file("Clean.java").uri},nonemptyEdit);c.applyWorkspaceEdit(edit);
    c.assert("override annotation added to existing method",/@Override\s+public String toString/u.test(c.text("Clean.java")));c.assert("method body preserved",c.text("Clean.java").includes('return "ok";'));c.compileOracle();
    onlyTargetChanged(c,before,"Clean.java");
    c.assert("manual cleanup only inserts the configured override annotation",c.text("Clean.java").replace(/\s/gu,"")===clean.replace("public String toString","@Override public String toString").replace(/\s/gu,""));
    const cleanedState=texts(c),again=await c.query("java/cleanup",{uri:c.file("Clean.java").uri},v=>{if(v&&(v.changes||v.documentChanges)){const after=preview(c,v);assert.equal(after.get(c.file("Clean.java").uri)?.text,c.text("Clean.java"));}},"idempotence");
    if(again)c.applyWorkspaceEdit(again);assert.deepEqual(texts(c),cleanedState,"repeated manual cleanup changed source");c.assert("repeated cleanup preserves exact source state",true);
  }},
  {id:"FMT-01/string",family:"FMT-01",apis:["API-080"],command:"java.edit.stringFormatting",variant:"raw source formatting preserves tokens and is stable",run:async c=>{
    const source=c.file("Format.java").text;
    const formatted=await c.execute("java.edit.stringFormatting",[source,null,"0"],v=>{assert.equal(typeof v,"string");assert.equal(v.replace(/\s/gu,""),source.replace(/\s/gu,""));assert.notEqual(v,source);});
    await c.execute("java.edit.stringFormatting",[formatted,null,"0"],v=>assert.equal(v,formatted),"idempotence");
    await c.open("Format.java",formatted);c.compileOracle();
  }},
  {id:"FMT-03/string-paste",family:"FMT-03",apis:["API-084"],command:"java.edit.handlePasteEvent",variant:"escape a quoted string and compile applied paste",fixture:{"Paste.java":'package bench;\npublic class Paste { public String text = ""; }\n'},run:async c=>{
    await c.open("Paste.java");const before=texts(c),source=c.text("Paste.java"),at=source.indexOf('""')+1,r={start:position(source,at),end:position(source,at)};
    const edit=await c.execute("java.edit.handlePasteEvent",[JSON.stringify({location:{uri:c.file("Paste.java").uri,range:r},text:'say "hello"',copiedDocumentUri:null,formattingOptions:format})],v=>assert.equal(v?.insertText,'say \\"hello\\"'));
    const expected=applyTextEdits(source,[{range:r,newText:edit.insertText}]);c.change("Paste.java",expected);if(edit.additionalEdit)c.applyWorkspaceEdit(edit.additionalEdit);
    onlyTargetChanged(c,before,"Paste.java");c.assert("string paste preserves the exact escaped target",c.text("Paste.java")===expected);
    c.compileOracle(String.raw`package bench; public class HarnessOracle { public static void main(String[] args) { if (!new Paste().text.equals("say \"hello\"")) throw new AssertionError("pasted string changed"); } }`);
  }},
  {id:"FMT-03/code-paste",family:"FMT-03",apis:["API-084"],command:"java.edit.handlePasteEvent",variant:"paste copied List field with the exact java.util.List import",
    fixture:{"Paste.java":pasteTarget,"CopySource.java":"package bench;\nimport java.util.List;\npublic class CopySource {\n"+pastedField+"}\n"},
    prepare:fixture=>{fixture.settings=structuredClone(SETTINGS);fixture.settings.java.updateImportsOnPaste={enabled:true};},
    run:async c=>{
      await c.open("CopySource.java");await c.open("Paste.java");const before=texts(c),source=c.text("Paste.java"),r=range(source,"    // PASTE HERE\n");
      const expectedImports=source.replace("package bench;","package bench;\nimport java.util.List;");
      const edit=await c.execute("java.edit.handlePasteEvent",[JSON.stringify({location:{uri:c.file("Paste.java").uri,range:r},text:pastedField,copiedDocumentUri:c.file("CopySource.java").uri,formattingOptions:format})],v=>{
        assert.equal(v?.insertText,pastedField,"pasted field content changed");nonemptyEdit(v.additionalEdit);
        const planned=preview(c,v.additionalEdit);assert.equal(planned.size,Object.keys(before).length,"paste edit changes file membership");
        for(const [name,text] of Object.entries(before)){
          const after=planned.get(c.file(name).uri)?.text;
          if(name==="Paste.java")assert.equal(after?.replace(/\s/gu,""),expectedImports.replace(/\s/gu,""),"paste does not supply only the required import");
          else assert.equal(after,text,"paste changes unrelated source: "+name);
        }
      });
      // The validated additional edit only changes imports before the paste
      // marker. Apply it at its supplied version, then rebase the insertion onto
      // the still-present marker; never accept a stale versioned workspace edit.
      c.applyWorkspaceEdit(edit.additionalEdit);const imported=c.text("Paste.java");
      c.change("Paste.java",applyTextEdits(imported,[{range:range(imported,"    // PASTE HERE\n"),newText:edit.insertText}]));
      onlyTargetChanged(c,before,"Paste.java");
      const expected=expectedImports.replace("    // PASTE HERE\n",pastedField);
      c.assert("code paste preserves content and supplies the exact import",c.text("Paste.java").replace(/\s/gu,"")===expected.replace(/\s/gu,""));
      c.compileOracle('package bench; public class HarnessOracle { public static void main(String[] args) { if (!new Paste().names.equals(java.util.List.of("pasted"))) throw new AssertionError("pasted field changed"); } }');
    }},
  {id:"FMT-03/file-paste",family:"FMT-03",apis:["API-020"],command:"java.project.resolveText",variant:"file path inferred from package and public type",run:async c=>{
    const source="package bench; public class Pasted {}";
    await c.execute("java.project.resolveText",[path.join(c.fixture.root,"bench"),source],v=>{
      assert.equal(typeof v,"string");const file=v.startsWith("file:")?fileURLToPath(v):v;assert.equal(path.resolve(file),path.join(c.fixture.root,MAIN,"bench/Pasted.java"));
    });
  }},
  {id:"FMT-03/semicolon",family:"FMT-03",apis:["API-085"],command:"java.edit.smartSemicolonDetection",variant:"semicolon suggested after constructor call",fixture:{"Semi.java":"package bench;\npublic class Semi {\n    private String text = new String()\n}\n"},run:async c=>{
    await c.open("Semi.java");const text=c.text("Semi.java"),end=text.indexOf("String()")+8;
    const result=await c.execute("java.edit.smartSemicolonDetection",[JSON.stringify({uri:c.file("Semi.java").uri,position:position(text,end-1)})],v=>{assert.equal(v?.uri,c.file("Semi.java").uri);assert.deepEqual(v.position,position(text,end));});
    c.change("Semi.java",applyTextEdits(text,[{range:{start:result.position,end:result.position},newText:";"}]));c.compileOracle();
  }},
  {id:"CMP-01/selection-command",family:"CMP-01",apis:["API-046"],capability:"completionProvider",variant:"select the exact returned name member only when its optional command is offered",run:async c=>{
    await c.open("Customer.java");await c.open("Use.java");const text=c.text("Use.java");
    const list=await c.query("textDocument/completion",{textDocument:{uri:c.file("Use.java").uri},position:position(text,text.indexOf("name()")+1)},v=>completionOracle(v,["name"]));
    const item=chooseMethod(list,"name"),completionOperationId=c.operations.at(-1).operationId;
    if(item.command===undefined)c.notApplicableEvidence={kind:"completion_command_not_offered",endpoint:SELECTION_COMMAND,completionOperationId,selectedItem:item};
    else{assert.equal(item.command?.command,SELECTION_COMMAND);await c.execute(item.command.command,item.command.arguments,v=>assert(v!==false));}
    await c.query("completionItem/resolve",item,v=>assert(JSON.stringify(v.documentation).includes("NAME_DOC_V1")),"after_selection");
    c.assert("selection disposition follows the original returned item",true);
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
    await c.open("Customer.java");const before=c.state(),actions=c.serverActions.length;c.compileOracle();
    await c.query("textDocument/hover",{textDocument:{uri:c.file("Customer.java").uri},position:position(c.text("Customer.java"),c.text("Customer.java").indexOf("number()")+1)},v=>hoverOracle(v,"number","int"),"valid_source_control");
    const params=action(c,"Customer.java","package bench;");params.context={diagnostics:[],only:["quickfix"]} as any;
    await c.series("textDocument/codeAction",params,noApplicableAction);
    c.assert("clean-context actions preserve every source state",JSON.stringify(c.state())===JSON.stringify(before));
    c.assert("no-action requests never apply an edit",!c.serverActions.slice(actions).some(a=>a.method==="workspace/applyEdit"));
  }},
  {id:"REF-02/refactor",family:"REF-02",apis:["API-088","API-089"],capability:"codeActionProvider",fixture:{"RefactorProbe.java":EXTRACT_SOURCE},variant:"discover local extraction by complete edit effect; preserve behaviour and unrelated sources",run:async c=>{
    await c.open("RefactorProbe.java");const before=sourceSnapshot(c),uri=c.file("RefactorProbe.java").uri;
    const params=action(c,"RefactorProbe.java","value + 1");params.context={diagnostics:[],only:["refactor.extract"]} as any;
    const actions=await c.query("textDocument/codeAction",params,v=>assert(Array.isArray(v)&&v.length>0));let chosen:any;
    for(const candidate of actions.filter((a:any)=>a.kind?.startsWith("refactor.extract"))){
      const resolved=candidate.edit?candidate:await c.query("codeAction/resolve",candidate,v=>assert(v&&v.kind===candidate.kind),"resolve_candidate");
      const edit=resolved.edit??(resolved.command?.command==="java.apply.workspaceEdit"?resolved.command.arguments?.[0]:undefined);
      if(!edit)continue;
      let matches=false,reason="";
      try{
        const planned=preview(c,edit);assert.deepEqual([...planned.keys()].sort(),Object.values(c.fixture.files).map(f=>f.uri).sort());
        for(const [name,f] of Object.entries(c.fixture.files))if(f.uri!==uri)assert.equal(planned.get(f.uri)?.text,c.text(name));
        extractedSource(planned.get(uri)!.text);matches=true;
      }catch(error){reason=String(error);}
      c.client.journal("refactor-candidates",{kind:candidate.kind,originalItem:candidate,resolved,selected:matches,reason});
      if(matches){chosen=edit;break;}
    }
    c.assert("discovered action has exactly the selected extraction effect",!!chosen);c.applyWorkspaceEdit(chosen);
    extractedSource(c.text("RefactorProbe.java"));exactRefactor(before,sourceSnapshot(c),{"bench/RefactorProbe.java":c.text("RefactorProbe.java")});
    c.assert("refactor preserves exact source membership and unrelated states",true);c.compileOracle(EXTRACT_PROBE);
  }},
  {id:"REF-03/change-signature",family:"REF-03",apis:["API-090","API-091"],extension:true,variant:"rename method and reverse parameters; exact caller updates and independent behaviour",run:async c=>{
    await c.open("Customer.java");await c.open("Use.java");const before=sourceSnapshot(c),context=action(c,"Customer.java","join");
    const info=await c.query("java/getChangeSignatureInfo",context,v=>{assert.equal(v.methodName,"join");assert.equal(v.returnType,"String");assert.equal(v.modifier,"public");assert.deepEqual(v.parameters.map((x:any)=>[x.name,x.type,x.originalIndex]),[["left","String",0],["right","int",1]]);assert(v.methodIdentifier);});
    const result=await c.query("java/getRefactorEdit",{command:"changeSignature",context,options:format,commandArguments:[info.methodIdentifier,false,"combine",info.modifier,info.returnType,[info.parameters[1],info.parameters[0]],info.exceptions??[],false]},v=>{assert(!v?.errorMessage);nonemptyEdit(v?.edit);});
    c.applyWorkspaceEdit(result.edit);exactRefactor(before,sourceSnapshot(c),{
      "bench/Customer.java":before["bench/Customer.java"].text.replace("join(String left, int right)","combine(int right, String left)"),
      "bench/Use.java":before["bench/Use.java"].text.replace('customer.join("a", 2)','customer.combine(2, "a")')});
    c.assert("chosen signature and every caller match exact independent sources",true);c.assert("refactor preserves exact source membership and unrelated states",true);
    c.compileOracle('package bench; public class HarnessOracle { public static void main(String[] args) { Customer c=new Customer(); Use u=new Use(); if(!c.combine(2,"a").equals("a2") || !c.combine(8,"z").equals("z8") || !u.join(c).equals("a2") || !u.read(c).equals("Ada") || u.count(c)!=7 || u.twice(c)!=14) throw new AssertionError("signature behaviour changed"); } }');
  }},
  {id:"REF-03/extract-selection",family:"REF-03",apis:["API-090","API-092"],extension:true,variant:"infer exact selection and extract a linked local; preserve behaviour and all unrelated sources",run:async c=>{
    await c.open("Format.java");const before=sourceSnapshot(c),context=action(c,"Format.java","1+2"),text=c.text("Format.java");
    const choices=await c.query("java/inferSelection",{command:"extractVariable",context},v=>assert(Array.isArray(v)&&v.some((x:any)=>x.offset===text.indexOf("1+2")&&x.length===3)));
    const selection=choices.find((x:any)=>x.offset===text.indexOf("1+2")&&x.length===3);
    const result=await c.query("java/getRefactorEdit",{command:"extractVariable",context,commandArguments:[selection],options:format},v=>{assert(!v?.errorMessage);nonemptyEdit(v?.edit);});
    c.applyWorkspaceEdit(result.edit);extractedSource(c.text("Format.java"),text,"1+2","$local");
    exactRefactor(before,sourceSnapshot(c),{"bench/Format.java":c.text("Format.java")});
    c.assert("selected expression is the returned extracted local",true);c.assert("refactor preserves exact source membership and unrelated states",true);
    c.compileOracle('package bench; public class HarnessOracle { public static void main(String[] args) { if(new Format().value()!=3) throw new AssertionError("extracted expression changed"); } }');
  }},
];
