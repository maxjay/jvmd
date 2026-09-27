/** Deliberately wrong LSP peer for subprocess gate tests. Never used for performance. */
import {mkdirSync,writeFileSync,existsSync,readFileSync} from "node:fs";
import path from "node:path";
import {Framing,encode} from "../../../shim/src/transport.ts";
const send=(v:any)=>process.stdout.write(encode({jsonrpc:"2.0",...v}));
const documents=new Map<string,string>();let completionCount=0,delayedTypePreparation=false;
const originalDocuments=new Map<string,string>();
const pos=(text:string,at:number)=>{const prefix=text.slice(0,at);return {line:prefix.split("\n").length-1,character:at-prefix.lastIndexOf("\n")-1};};
const span=(text:string,at:number,length:number)=>({start:pos(text,at),end:pos(text,at+length)});
const mode=process.argv[2];
const persistedRoot=process.env.JVMD_CONFIG?path.join(path.dirname(process.env.JVMD_CONFIG),"store"):undefined;
const persistedMarker=persistedRoot?path.join(persistedRoot,"mock-state.json"):undefined;
const reopened=!!persistedMarker&&existsSync(persistedMarker);
if(mode.startsWith("persisted-")&&persistedRoot&&mode!=="persisted-empty"){mkdirSync(persistedRoot,{recursive:true});if(!reopened)writeFileSync(persistedMarker!,JSON.stringify({bufferType:"int"}));}

if(mode==="shutdown-hang")setInterval(()=>{},1000);
const framing=new Framing("headers",message=>{
  const {id,method,params}=message;
  if(method==="initialized"){send({method:"language/status",params:{type:"ServiceReady"}});return;}
  if(method==="textDocument/didOpen"){documents.set(params.textDocument.uri,params.textDocument.text);originalDocuments.set(params.textDocument.uri,params.textDocument.text);}
  if(method==="textDocument/didChange")documents.set(params.textDocument.uri,params.contentChanges[0].text);
  if(method==="exit"){if(mode!=="shutdown-hang")process.exit(mode==="shutdown-nonzero"||(mode==="persisted-seed-shutdown"&&!reopened)?1:0);return;}
  if(id===undefined)return;
  if(method==="initialize"){send({id,result:{capabilities:{hoverProvider:true,documentSymbolProvider:true,workspaceSymbolProvider:true,typeHierarchyProvider:true,renameProvider:{prepareProvider:true},completionProvider:{resolveProvider:true},definitionProvider:true,callHierarchyProvider:true,codeLensProvider:{resolveProvider:true},executeCommandProvider:{commands:["java.project.resolveWorkspaceSymbol","java.edit.handlePasteEvent","java.navigate.openTypeHierarchy","java.navigate.resolveTypeHierarchy"]}}}});return;}
  if(method==="shutdown"){if(mode!=="shutdown-hang")send({id,result:null});return;}
  if(mode.startsWith("persisted-")&&method==="textDocument/hover"){
    const source=documents.get(params.textDocument.uri)??"",type=reopened&&mode==="persisted-leak"?"int":source.includes("public int label")?"int":"String";
    send({id,result:{contents:{kind:"markdown",value:type+" label"}}});return;
  }
  if(mode.startsWith("selection-")){
    if(method==="textDocument/completion"){send({id,result:{items:[{label:"name()",kind:2,data:{token:"original"},...(mode==="selection-missing"?{}:{command:{title:"selected",command:"java.completion.onDidSelect",arguments:["original"]}})}],isIncomplete:false}});return;}
    if(method==="completionItem/resolve"){send({id,result:{...params,documentation:"NAME_DOC_V1"}});return;}
    if(method==="workspace/executeCommand"){
      if(mode==="selection-command-error")send({id,error:{code:-32603,message:"selection failed"}});
      else if(params.command==="java.completion.onDidSelect"&&JSON.stringify(params.arguments)==='["original"]')send({id,result:null});
      else send({id,error:{code:-32602,message:"wrong original selection arguments"}});return;
    }
  }
  if(mode.startsWith("symbols-")&&["textDocument/documentSymbol","java/extendedDocumentSymbol","workspace/symbol","java/searchSymbols","workspace/executeCommand"].includes(method)){
    const uri=[...documents.keys()].find(u=>u.endsWith("/SearchCase.java"))!,source=documents.get(uri)!;
    const rows=[...source.matchAll(/(interface|class) (Benchmark\w+)[^\n]*/gu)].map(m=>({name:m[2],kind:m[1]==="interface"?11:5,location:{uri,range:span(source,m.index+m[1].length+1,m[2].length)}}));
    if(method==="workspace/symbol"||method==="java/searchSymbols"){send({id,result:rows});return;}
    if(method==="workspace/executeCommand"){
      const item=JSON.parse(params.arguments[0]);
      if(!["Class","Interface"].includes(item.kind)){send({id,error:{code:-32602,message:"workspace symbol command kind must use enum name"}});return;}
      send({id,result:{...item,kind:item.kind==="Class"?5:11}});return;
    }
    const types=[...source.matchAll(/(interface|class) (Benchmark\w+)[^\n]*/gu)].map(m=>{
      const row:any={name:m[2],kind:m[1]==="interface"?11:5,range:span(source,m.index,m[0].length),selectionRange:span(source,m.index+m[1].length+1,m[2].length),uri,children:[]};
      if(row.kind===11){const at=source.indexOf("int local();");row.children.push({name:"local()",kind:6,range:span(source,at,12),selectionRange:span(source,at+4,5),uri});
        if(method==="java/extendedDocumentSymbol"&&mode!=="symbols-missing-inherited"){
          const parent="package bench;\npublic interface SymbolParent { int inherited(); }\n",at=parent.indexOf("int inherited();");
          row.children.push({name:"inherited()",kind:6,range:span(parent,at,16),selectionRange:span(parent,at+4,9),uri:mode==="symbols-wrong-inherited-uri"?uri:uri.replace("SearchCase.java","SymbolParent.java")});
        }
      }return row;
    });send({id,result:types});return;
  }
  if(method==="java/checkToStringStatus"){
    send({id,result:{type:"Generate",fields:[{name:"name",type:"String",bindingKey:"selected-name"}],exists:false}});return;
  }
  if(method==="java/generateToString"){
    const uri=[...documents.keys()].find(uri=>uri.endsWith("/Generate.java"))!,source=documents.get(uri)!;
    const at=source.lastIndexOf("}"),changes:any={[uri]:[{range:span(source,at,0),newText:"    public String toString() { return name; }\n"}]};
    if(mode==="generation-foreign-edit")changes[uri.replace("/Generate.java","/Unrelated.java")]=[{range:span("",0,0),newText:"// unintended edit\n"}];
    send({id,result:{changes}});return;
  }
  if(method==="workspace/executeCommand"&&params.command.startsWith("java.navigate.")){
    if(!params.arguments.every((argument:any)=>typeof argument==="string")){
      send({id,error:{code:-32602,message:"legacy hierarchy arguments must be JSON strings"}});return;
    }
    const uri=[...documents.keys()].find(uri=>uri.endsWith("/Hierarchy.java"))!,current=documents.get(uri)!;
    const source=mode==="stale-legacy-hierarchy"&&params.command==="java.navigate.resolveTypeHierarchy"?originalDocuments.get(uri)!:current;
    const types=[...source.matchAll(/interface (\w+)(?: extends (\w+))? \{\}/gu)];
    const direction=mode==="hierarchy-wrong-direction"?2:JSON.parse(params.arguments[1]),depth=JSON.parse(params.arguments[2])+(mode==="hierarchy-wrong-depth"?1:0);
    const item=(name:string,remaining:number):any=>{
      const type=types.find(m=>m[1]===name)!;const result:any={name,kind:11,uri,range:span(source,type.index,type[0].length),selectionRange:span(source,type.index+10,name.length),data:{token:"issued-"+id}};
      if(remaining>0){
        if(direction!==0)result.parents=type[2]?[item(type[2],remaining-1)]:[];
        if(direction!==1)result.children=types.filter(t=>t[2]===name).map(t=>item(t[1],remaining-1));
      }
      return result;
    };
    const request=JSON.parse(params.arguments[0]),focus=params.command==="java.navigate.openTypeHierarchy"?(current.split("\n")[request.position.line].includes("interface Child")?"Child":"Base"):request.name;
    send({id,result:item(focus,depth)});return;
  }
  if(method==="textDocument/prepareTypeHierarchy"||method.startsWith("typeHierarchy/")){
    const uri=[...documents.keys()].find(uri=>uri.endsWith("/Hierarchy.java"))!,current=documents.get(uri)!;
    let source=mode==="stale-type-hierarchy"&&method!=="textDocument/prepareTypeHierarchy"?originalDocuments.get(uri)!:current;
    if(mode==="delayed-type-preparation"&&method==="textDocument/prepareTypeHierarchy"&&current!==originalDocuments.get(uri)&&!delayedTypePreparation){source=originalDocuments.get(uri)!;delayedTypePreparation=true;}
    const item=(name:string)=>{const start=source.indexOf("class "+name),end=source.indexOf("\n",start);return {name,kind:5,uri,range:span(source,start,end-start),selectionRange:span(source,start+6,name.length),data:{token:"issued-"+id}};};
    if(method==="textDocument/prepareTypeHierarchy"){const line=current.split("\n")[params.position.line];send({id,result:[item(line.includes("class Child")?"Child":"Base")]});return;}
    const parent=source.includes("Child extends Base")?"Base":"UnrelatedType";
    send({id,result:method==="typeHierarchy/supertypes"?[item(parent)]:parent==="Base"?[item("Child")]:[]});return;
  }
  if(method==="textDocument/prepareRename"||method==="textDocument/rename"){
    const invalid=params.textDocument.uri.endsWith("/Customer.java"),prepare=method==="textDocument/prepareRename";
    if(mode==="rename-reject-all"||invalid&&mode==="rename-rejection"){
      send({id,error:{code:-32600,message:"Renaming this element is not supported."}});return;
    }
    if(invalid&&mode==="rename-internal-error"){send({id,error:{code:-32603,message:"Internal rename failure"}});return;}
    if(invalid&&mode==="rename-missing-method"){send({id,error:{code:-32601,message:"Rename not supported"}});return;}
    if(invalid&&mode!=="rename-wrong-result"){send({id,result:mode==="rename-empty-edit"?{}:null});return;}
    if(prepare){const source=documents.get(params.textDocument.uri)!;send({id,result:span(source,source.indexOf("number"),6)});return;}
    const changes:Record<string,any[]>={};
    for(const [uri,source] of documents)if(uri.endsWith("/Customer.java")||uri.endsWith("/Use.java")){
      changes[uri]=[];
      for(let at=source.indexOf("number()");at>=0;at=source.indexOf("number()",at+8))changes[uri].push({range:span(source,at,6),newText:params.newName});
    }
    send({id,result:{changes}});return;
  }
  if(method==="textDocument/completion"){
    completionCount++;
    if(mode==="hang")return;
    const items=(mode==="wrong-warmup"&&completionCount===2?[]:["name","number"]).map(label=>({label,kind:2}));
    if(mode==="delayed-provider"&&completionCount>=3)items.push({label:"next",kind:2});
    // stale-provider never returns next(), despite receipt of the provider edit.
    send({id,result:{items,isIncomplete:false}});return;
  }
  if(method==="textDocument/definition"){
    const uri=[...documents.keys()].find(uri=>uri.endsWith("/Customer.java"));
    send({id,result:[{uri,range:{start:{line:0,character:0},end:{line:0,character:1}}}]});return;
  }
  if(method==="workspace/executeCommand"&&params.command==="java.edit.handlePasteEvent"){
    const paste=JSON.parse(params.arguments[0]),uri=paste.location.uri;
    const changes={[uri]:[{range:{start:{line:1,character:0},end:{line:1,character:0}},newText:mode==="missing-paste-import"?"":"import java.awt.List;\n"}]};
    send({id,result:{insertText:paste.text,additionalEdit:{changes}}});return;
  }
  if(method==="textDocument/codeLens"||method==="codeLens/resolve"){
    const uri=[...documents.keys()].find(uri=>uri.endsWith("/Calls.java"))!,current=documents.get(uri)!;
    const text=mode==="stale-lens"&&method==="codeLens/resolve"?originalDocuments.get(uri)!:current;
    const declaration=span(current,current.indexOf("int b()")+4,1);
    if(method==="textDocument/codeLens"){send({id,result:[{range:declaration,data:{opaque:"current-lens"}}]});return;}
    const start=text.indexOf("return "),end=text.indexOf(";",start),refs=[];
    for(let at=text.indexOf("b()",start);at>=0&&at<end;at=text.indexOf("b()",at+3))refs.push({uri,range:span(text,at,3)});
    if(mode==="duplicate-lens-reference"&&refs.length>1)refs[1]=refs[0];
    send({id,result:{...params,command:{title:`${refs.length} reference${refs.length===1?"":"s"}`,command:"java.show.references",arguments:[uri,declaration.start,refs]}}});return;
  }
  if(method==="textDocument/prepareCallHierarchy"||method.startsWith("callHierarchy/")){
    const uri=[...documents.keys()].find(uri=>uri.endsWith("/Calls.java"))!;
    const current=documents.get(uri)!,text=mode==="stale-call-graph"&&method!=="textDocument/prepareCallHierarchy"?originalDocuments.get(uri)!:current;
    const item=(name:string)=>{const at=text.indexOf("int "+name+"()")+4,start=text.indexOf("public int "+name+"()"),end=text.indexOf("}",start)+1;return {name,kind:6,uri,range:span(text,start,end-start),selectionRange:span(text,at,1)};};
    if(method==="textDocument/prepareCallHierarchy"){send({id,result:[item("b")]});return;}
    const incoming=method==="callHierarchy/incomingCalls",start=text.indexOf("return ",text.indexOf("int "+(incoming?"a":"b")+"()")),end=text.indexOf(";",start),token=incoming?"b()":"c()";
    const ranges=[];for(let at=text.indexOf(token,start);at>=0&&at<end;at=text.indexOf(token,at+token.length))ranges.push(span(text,at,token.length));
    send({id,result:ranges.length?[{[incoming?"from":"to"]:item(incoming?"a":"c"),fromRanges:ranges}]:[]});return;
  }
  send({id,error:{code:-32601,message:"unsupported by fake peer"}});
});
process.stdin.on("data",chunk=>framing.push(chunk));
