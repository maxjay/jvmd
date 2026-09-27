/** Deliberately wrong LSP peer for subprocess gate tests. Never used for performance. */
import {Framing,encode} from "../../../shim/src/transport.ts";
const send=(v:any)=>process.stdout.write(encode({jsonrpc:"2.0",...v}));
const documents=new Map<string,string>();let completionCount=0;
const originalDocuments=new Map<string,string>();
const pos=(text:string,at:number)=>{const prefix=text.slice(0,at);return {line:prefix.split("\n").length-1,character:at-prefix.lastIndexOf("\n")-1};};
const span=(text:string,at:number,length:number)=>({start:pos(text,at),end:pos(text,at+length)});
const mode=process.argv[2];
const framing=new Framing("headers",message=>{
  const {id,method,params}=message;
  if(method==="textDocument/didOpen"){documents.set(params.textDocument.uri,params.textDocument.text);originalDocuments.set(params.textDocument.uri,params.textDocument.text);}
  if(method==="textDocument/didChange")documents.set(params.textDocument.uri,params.contentChanges[0].text);
  if(method==="exit"){process.exit(0);return;}
  if(id===undefined)return;
  if(method==="initialize"){send({id,result:{capabilities:{completionProvider:{resolveProvider:true},definitionProvider:true,callHierarchyProvider:true}}});return;}
  if(method==="shutdown"){send({id,result:null});return;}
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
  if(method==="textDocument/prepareCallHierarchy"||method.startsWith("callHierarchy/")){
    const uri=[...documents.keys()].find(uri=>uri.endsWith("/Calls.java"))!;
    const current=documents.get(uri)!,text=mode==="stale-call-graph"&&method!=="textDocument/prepareCallHierarchy"?originalDocuments.get(uri)!:current;
    const item=(name:string)=>{const at=text.indexOf("int "+name+"()")+4;return {name,kind:6,uri,range:span(text,at,1),selectionRange:span(text,at,1)};};
    if(method==="textDocument/prepareCallHierarchy"){send({id,result:[item("b")]});return;}
    const incoming=method==="callHierarchy/incomingCalls",start=text.indexOf("return ",text.indexOf("int "+(incoming?"a":"b")+"()")),end=text.indexOf(";",start),token=incoming?"b()":"c()";
    const ranges=[];for(let at=text.indexOf(token,start);at>=0&&at<end;at=text.indexOf(token,at+token.length))ranges.push(span(text,at,token.length));
    send({id,result:ranges.length?[{[incoming?"from":"to"]:item(incoming?"a":"c"),fromRanges:ranges}]:[]});return;
  }
  send({id,error:{code:-32601,message:"unsupported by fake peer"}});
});
process.stdin.on("data",chunk=>framing.push(chunk));
