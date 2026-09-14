/** Implements 4.9: LSP lifecycle, native replies and debounced diagnostics over the same daemon. */
import { fileURLToPath } from "node:url";
import path from "node:path";
import { RpcClient, type Message } from "./transport.ts";

const MAX_BYTES=64*1024;
function rpcError(code:number,message:string,data?:any){return Object.assign(new Error(message),{rpc:{code,message,data}});}
function pointer(parts:string[]){return "/"+parts.map(p=>p.replaceAll("~","~0").replaceAll("/","~1")).join("/");}
/** Reconstructs bounded daemon fragments by their declared UTF-16 or array offsets. */
export async function collect(client:RpcClient,method:string,params:any) {
  let response:any,firstCursor:string|undefined,total=0,pages=0;
  const result:any={payload:{},warnings:[]},strings=new Map<string,Map<number,string>>();
  function merge(target:any,source:any,local:string[],absolute:string[],segments:any):any {
    const segment=segments[pointer(local)];
    if(segment?.kind==="string"){
      if(segment.total>32*1024*1024)throw rpcError(-32005,"Continuation string exceeds memory budget");
      const key=pointer(absolute);let pieces=strings.get(key);if(!pieces){pieces=new Map();strings.set(key,pieces);}pieces.set(segment.start,source);
      return source;
    }
    if(Array.isArray(source)){
      const offset=segment?.kind==="array"?segment.start:0;
      if((segment?.total??source.length)>8*1024*1024)throw rpcError(-32005,"Continuation array exceeds memory budget");
      const output=Array.isArray(target)?target:[];for(let i=0;i<source.length;i++)output[offset+i]=merge(output[offset+i],source[i],[...local,String(i)],[...absolute,String(offset+i)],segments);return output;
    }
    if(source&&typeof source==="object"){
      const output=target&&typeof target==="object"&&!Array.isArray(target)?target:{};
      for(const [key,value] of Object.entries(source))if(key!=="_jvmd_segments")output[key]=merge(output[key],value,[...local,key],[...absolute,key],segments);return output;
    }
    return source;
  }
  let cursor=params.cursor;
  do{
    response=await client.call(method,{...params,...(cursor?{cursor}:{})});total+=Buffer.byteLength(JSON.stringify(response));if(total>64*1024*1024||++pages>2048)throw rpcError(-32005,"Continuation exceeds memory budget");
    const segments=response.result?._jvmd_segments||{};merge(result,{payload:response.result,warnings:response.warnings},[],[],segments);
    cursor=response.cursor;if(!firstCursor&&cursor?.startsWith("budget:"))firstCursor=cursor;
  }while(response.truncated&&cursor?.startsWith("budget:"));
  for(const [key,pieces] of strings){
    const parts=key.slice(1).split("/").map(p=>p.replaceAll("~1","/").replaceAll("~0","~"));let destination=result;
    for(const part of parts.slice(0,-1))destination=destination[part];destination[parts.at(-1)!]=[...pieces.entries()].sort((a,b)=>a[0]-b[0]).map(([,text])=>text).join("");
  }
  return {...response,result:result.payload,warnings:result.warnings,firstCursor};
}
export class LspBridge {
  private queue:Promise<unknown>=Promise.resolve();
  private session?:string;
  private initialized=false;
  private stopping=false;
  private capabilities:any={};
  private timers=new Map<string,ReturnType<typeof setTimeout>>();
  private versions=new Map<string,number>();
  private generations=new Map<string,number>();
  constructor(getClient:()=>Promise<RpcClient>,root:string,send:(message:Message)=>void,exit:(code:number)=>void=code=>{process.exitCode=code;}){this.getClient=getClient;this.root=root;this.send=send;this.exit=exit;}
  getClient:()=>Promise<RpcClient>;root:string;send:(message:Message)=>void;exit:(code:number)=>void;
  handle(message:Message):Promise<void>{
    const uri=message.params?.textDocument?.uri;
    if(uri&&["textDocument/didOpen","textDocument/didChange","textDocument/didClose","textDocument/didSave"].includes(message.method||""))this.generations.set(uri,(this.generations.get(uri)||0)+1);
    if(message.method==="$/cancelRequest")return Promise.resolve();
    const work=this.queue.then(()=>this.process(message));this.queue=work.catch(()=>{});return work;
  }
  private async process(message:Message) {
    const id=message.id,method=message.method||"",params=message.params||{};const request=id!==undefined;
    try{
      if(method==="exit"){await this.close();this.exit(this.stopping?0:1);return;}
      if(method==="initialize"){
        if(this.initialized)throw rpcError(-32600,"LSP is already initialized");
        if(params.capabilities?.general?.positionEncodings&&!params.capabilities.general.positionEncodings.includes("utf-16"))throw rpcError(-32602,"jvmd requires the LSP UTF-16 position encoding");
        this.capabilities=params.capabilities||{};if(params.rootUri)this.root=fileURLToPath(params.rootUri);else if(params.rootPath)this.root=path.resolve(params.rootPath);
        const client=await this.getClient();const openParams:any={root:this.root};if(params.workspaceFolders?.length)openParams.manifest={roots:params.workspaceFolders.map((folder:any)=>fileURLToPath(folder.uri))};
        this.session=(await client.call("session.open",openParams)).result.session;
        const result=await collect(client,"lsp.request",{session:this.session,method:"initialize",params,client:this.capabilities});
        this.initialized=true;this.send({jsonrpc:"2.0",id,result:result.result.value});return;
      }
      if(!this.initialized)throw rpcError(-32002,"Server not initialized");
      if(method==="shutdown"){this.stopping=true;this.clearTimers();this.send({jsonrpc:"2.0",id,result:null});return;}
      if(this.stopping)throw rpcError(-32600,"Server has shut down");
      if(method==="initialized"||method==="$/setTrace")return;
      const client=await this.getClient();
      if(method.startsWith("textDocument/did")){
        const document=params.textDocument||{},uri=document.uri;if(typeof uri!=="string")throw rpcError(-32602,"Document URI is required");
        const common={session:this.session,path:fileURLToPath(uri)};
        if(method==="textDocument/didOpen"){
          if(document.languageId!=="java")return;await client.call("document.open",{...common,text:document.text,version:document.version});this.versions.set(uri,document.version);this.schedule(uri);
        }else if(method==="textDocument/didChange"){
          await client.call("document.change",{...common,version:document.version,changes:params.contentChanges});this.versions.set(uri,document.version);this.schedule(uri);
        }else if(method==="textDocument/didClose"){
          const timer=this.timers.get(uri);if(timer)clearTimeout(timer);this.timers.delete(uri);await client.call("document.close",common);this.versions.delete(uri);this.send({jsonrpc:"2.0",method:"textDocument/publishDiagnostics",params:{uri,diagnostics:[]}});
        }else if(method==="textDocument/didSave"){this.schedule(uri);}
        else throw rpcError(-32601,"Method not found: "+method);return;
      }
      if(!request)return;
      const response=await collect(client,"lsp.request",{session:this.session,method,params,client:this.capabilities});this.reply(id,method,params,response.result.value,response.firstCursor);
    }catch(error){
      const problem=(error as any).rpc||{code:-32603,message:(error as Error).message};
      if(request)this.send({jsonrpc:"2.0",id,error:problem});
      else this.send({jsonrpc:"2.0",method:"window/logMessage",params:{type:1,message:problem.message}});
    }
  }
  private reply(id:Message["id"],method:string,params:any,value:any,cursor?:string){
    const response={jsonrpc:"2.0",id,result:value};
    if(Buffer.byteLength(JSON.stringify(response))<=MAX_BYTES){this.send(response);return;}
    if(method==="textDocument/completion"){
      const items:any[]=[];for(const item of value.items){items.push(item);if(Buffer.byteLength(JSON.stringify({jsonrpc:"2.0",id,result:{isIncomplete:true,items}}))>MAX_BYTES){items.pop();break;}}
      this.send({jsonrpc:"2.0",id,result:{isIncomplete:true,items}});return;
    }
    const token=params.partialResultToken,semantic=method==="textDocument/semanticTokens/full";const items=semantic?value.data:Array.isArray(value)?value:undefined;
    if(token!==undefined&&items&&(semantic||["textDocument/documentSymbol","textDocument/definition","textDocument/references"].includes(method))){
      let batch:any[]=[];const send=()=>{if(batch.length)this.send({jsonrpc:"2.0",method:"$/progress",params:{token,value:semantic?{data:batch}:batch}});batch=[];};
      const group=semantic?5:1;
      for(let i=0;i<items.length;i+=group){const next=items.slice(i,i+group);if(Buffer.byteLength(JSON.stringify({jsonrpc:"2.0",method:"$/progress",params:{token,value:semantic?{data:[...batch,...next]}:[...batch,...next]}}))>MAX_BYTES){if(!batch.length)throw rpcError(-32005,"One editor result exceeds the response budget",{cursor});send();}batch.push(...next);}send();
      this.send({jsonrpc:"2.0",id,result:semantic?{data:[],resultId:value.resultId}:[]});return;
    }
    throw rpcError(-32005,"Editor result exceeds 64 KiB; request partial results",{cursor});
  }
  private schedule(uri:string){
    const previous=this.timers.get(uri);if(previous)clearTimeout(previous);const generation=this.generations.get(uri),version=this.versions.get(uri);
    const timer=setTimeout(()=>{
      this.timers.delete(uri);
      const work=this.queue.then(async()=>{
        if(this.stopping||this.generations.get(uri)!==generation||!this.versions.has(uri))return;
        const result=await collect(await this.getClient(),"lsp.diagnostics",{session:this.session,uri});
        if(this.generations.get(uri)!==generation||this.versions.get(uri)!==version)return;
        const value=result.result.value;const diagnostics:any[]=[];
        for(const diagnostic of value.diagnostics){diagnostics.push(diagnostic);if(Buffer.byteLength(JSON.stringify({jsonrpc:"2.0",method:"textDocument/publishDiagnostics",params:{...value,diagnostics}}))>MAX_BYTES-2048){diagnostics.pop();break;}}
        if(diagnostics.length<value.diagnostics.length)diagnostics.push({range:{start:{line:0,character:0},end:{line:0,character:0}},severity:2,code:"jvmd.diagnosticBudget",source:"jvmd",message:(value.diagnostics.length-diagnostics.length)+" further diagnostics exceed the display budget; fix the displayed problems to see more."});
        this.send({jsonrpc:"2.0",method:"textDocument/publishDiagnostics",params:{...value,diagnostics}});
      });
      this.queue=work.catch(error=>{this.send({jsonrpc:"2.0",method:"window/logMessage",params:{type:1,message:"Diagnostics failed: "+error.message}});});
    },200);this.timers.set(uri,timer);
  }
  private clearTimers(){for(const timer of this.timers.values())clearTimeout(timer);this.timers.clear();}
  async close(){this.clearTimers();if(this.session){const client=await this.getClient();for(const uri of this.versions.keys())await client.call("document.close",{session:this.session,path:fileURLToPath(uri)});this.versions.clear();}}
  async drained(){await this.queue;}
}
