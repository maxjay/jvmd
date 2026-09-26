/** Deliberately wrong LSP peer for subprocess gate tests. Never used for performance. */
import {Framing,encode} from "../../../shim/src/transport.ts";
const send=(v:any)=>process.stdout.write(encode({jsonrpc:"2.0",...v}));
const documents=new Map<string,string>();let completionCount=0;
const mode=process.argv[2];
const framing=new Framing("headers",message=>{
  const {id,method,params}=message;
  if(method==="textDocument/didOpen")documents.set(params.textDocument.uri,params.textDocument.text);
  if(method==="textDocument/didChange")documents.set(params.textDocument.uri,params.contentChanges[0].text);
  if(method==="exit"){process.exit(0);return;}
  if(id===undefined)return;
  if(method==="initialize"){send({id,result:{capabilities:{completionProvider:{resolveProvider:true},definitionProvider:true}}});return;}
  if(method==="shutdown"){send({id,result:null});return;}
  if(method==="textDocument/completion"){
    completionCount++;
    const items=(mode==="wrong-warmup"&&completionCount===2?[]:["name","number"]).map(label=>({label,kind:2}));
    // stale-provider never returns next(), despite receipt of the provider edit.
    send({id,result:{items,isIncomplete:false}});return;
  }
  if(method==="textDocument/definition"){
    const uri=[...documents.keys()].find(uri=>uri.endsWith("/Customer.java"));
    send({id,result:[{uri,range:{start:{line:0,character:0},end:{line:0,character:1}}}]});return;
  }
  send({id,error:{code:-32601,message:"unsupported by fake peer"}});
});
process.stdin.on("data",chunk=>framing.push(chunk));
