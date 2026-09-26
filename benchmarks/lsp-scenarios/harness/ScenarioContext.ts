import assert from "node:assert/strict";
import {pathToFileURL} from "node:url";
import {readFileSync,writeFileSync,mkdirSync} from "node:fs";
import path from "node:path";
import {spawnSync} from "node:child_process";
import {ProtocolClient,now,type Exchange} from "./ProtocolClient.ts";
import {type Fixture,sha} from "./fixture.ts";
import {applyTextEdits,offset,type Position} from "./oracles.ts";

export const CAPABILITIES={
  general:{positionEncodings:["utf-16"]},
  workspace:{configuration:true,workspaceFolders:true,applyEdit:true,workspaceEdit:{documentChanges:true,resourceOperations:["create","rename","delete"]},
    symbol:{resolveSupport:{properties:["location.range"]}}},
  textDocument:{publishDiagnostics:{versionSupport:true},completion:{completionItem:{snippetSupport:false,resolveSupport:{properties:["documentation","detail","additionalTextEdits"]}}},
    documentSymbol:{hierarchicalDocumentSymbolSupport:true},codeAction:{codeActionLiteralSupport:{codeActionKind:{valueSet:["quickfix","refactor","source"]}},resolveSupport:{properties:["edit"]}},
    semanticTokens:{requests:{full:true},tokenTypes:["namespace","type","class","enum","interface","struct","typeParameter","parameter","variable","property","enumMember","event","function","method","macro","keyword","modifier","comment","string","number","regexp","operator","decorator"],tokenModifiers:["declaration","definition","readonly","static","deprecated","abstract","async","modification","documentation","defaultLibrary"],formats:["relative"]}},
};
export const SETTINGS={java:{autobuild:{enabled:true},import:{maven:{enabled:false},gradle:{enabled:false}},signatureHelp:{enabled:true},inlayHints:{parameterNames:{enabled:"all"}},referencesCodeLens:{enabled:true},implementationsCodeLens:{enabled:true}}};
export type CaseDefinition={id:string;family:string;apis:string[];method?:string;capability?:string;variant:string;
  run:(c:ScenarioContext)=>Promise<void>;freshnessRequired?:boolean;correctnessOnly?:boolean;extension?:boolean;command?:string;fixture?:Record<string,string>};
export class ScenarioContext {
  client:ProtocolClient;fixture:Fixture;server:string;timeout:number;warmup:number;samples:number;
  javaHome=process.env.JAVA_HOME??"";
  private origins=new WeakMap<object,{method:string;state:string;requestId:number}>();
  capabilities:any={};operations:any[]=[];assertions:any[]=[];documents=new Map<string,{text:string;version:number;incarnation:number}>();
  versions=new Map<string,number>();incarnations=new Map<string,number>();
  seriesExpectations:any[]=[];
  registrations:any[]=[];serverActions:any[]=[];initializedNs?:string;
  constructor(client:ProtocolClient,fixture:Fixture,server:string,timeout:number,warmup:number,samples:number){
    Object.assign(this,{client,fixture,server,timeout,warmup,samples});
    this.client=client;this.fixture=fixture;this.server=server;this.timeout=timeout;this.warmup=warmup;this.samples=samples;
    client.onServerRequest=async(method,params)=>{
      this.serverActions.push({method,params,timeNs:String(now())});
      if(method==="workspace/configuration")return (params?.items??[]).map((item:any)=>String(item.section??"").split(".").filter(Boolean).reduce((v:any,key:string)=>v?.[key],SETTINGS));
      if(method==="workspace/workspaceFolders")return [{uri:pathToFileURL(fixture.root).href,name:"benchmark"}];
      if(method==="client/registerCapability"){this.registrations.push(...params.registrations);return null;}
      if(method==="client/unregisterCapability")return null;
      if(["window/workDoneProgress/create","workspace/inlayHint/refresh","workspace/codeLens/refresh"].includes(method))return null;
      if(method==="window/showMessageRequest")return null;
      if(method==="workspace/applyEdit"){
        try{this.applyWorkspaceEdit(params.edit);return {applied:true};}catch(e){return {applied:false,failureReason:String(e)};}
      }
      if(method==="workspace/executeClientCommand")throw new Error("client command requires explicit case handler: "+params?.command);
      throw new Error("unsupported server-to-client request: "+method);
    };
  }
  file(name:string){const f=this.fixture.files[name];assert(f,"unknown fixture file: "+name);return f;}
  async initialize(){
    const row=await this.client.request("initialize",{processId:process.pid,rootUri:pathToFileURL(this.fixture.root).href,
      workspaceFolders:[{uri:pathToFileURL(this.fixture.root).href,name:"benchmark"}],capabilities:CAPABILITIES,
      initializationOptions:{settings:SETTINGS,extendedClientCapabilities:{classFileContentsSupport:true,advancedOrganizeImportsSupport:true}}},this.timeout);
    assert(!row.error,"initialize failed: "+JSON.stringify(row.error));this.capabilities=row.result.capabilities;this.initializedNs=row.endNs;
    this.client.notify("initialized",{});this.client.notify("workspace/didChangeConfiguration",{settings:SETTINGS});
    if(this.server==="jdtls")await this.client.notification("language/status",p=>p?.type==="ServiceReady",0,this.timeout);
  }
  async open(name:string,text=this.file(name).text){
    const f=this.file(name),old=this.documents.get(f.uri);
    assert(!old,"document already open: "+name);
    const version=(this.versions.get(f.uri)??0)+1,incarnation=(this.incarnations.get(f.uri)??0)+1;
    this.versions.set(f.uri,version);this.incarnations.set(f.uri,incarnation);
    this.documents.set(f.uri,{text,version,incarnation});
    const trigger=this.client.notify("textDocument/didOpen",{textDocument:{uri:f.uri,languageId:"java",version,text}});
    return {uri:f.uri,text,version,trigger};
  }
  change(name:string,text:string){
    const f=this.file(name),old=this.documents.get(f.uri);assert(old,"document must be open");
    const version=old.version+1;this.versions.set(f.uri,version);this.documents.set(f.uri,{...old,text,version});
    const trigger=this.client.notify("textDocument/didChange",{textDocument:{uri:f.uri,version},contentChanges:[{text}]});
    return {version,trigger};
  }
  close(name:string){const f=this.file(name);assert(this.documents.has(f.uri),"not open");this.client.notify("textDocument/didClose",{textDocument:{uri:f.uri}});this.documents.delete(f.uri);}
  text(name:string){const f=this.file(name);return this.documents.get(f.uri)?.text??readFileSync(f.path,"utf8");}
  state(){return [...this.documents].map(([uri,d])=>({uri,version:d.version,incarnation:d.incarnation,sha256:sha(d.text)}));}
  async query(method:string,params:any,oracle:(result:any)=>void,state="first_use",trigger?:bigint,freshnessWitness?:string){
    const before=this.state();
    const originMethod:Record<string,string>={"completionItem/resolve":"textDocument/completion","codeLens/resolve":"textDocument/codeLens","codeAction/resolve":"textDocument/codeAction",
      "callHierarchy/incomingCalls":"textDocument/prepareCallHierarchy","callHierarchy/outgoingCalls":"textDocument/prepareCallHierarchy",
      "typeHierarchy/supertypes":"textDocument/prepareTypeHierarchy","typeHierarchy/subtypes":"textDocument/prepareTypeHierarchy"};
    let origin:any;
    if(originMethod[method]){
      origin=this.origins.get(params.item??params);
      this.assert("opaque item belongs to this client, endpoint and document state",!!origin&&origin.method===originMethod[method]&&origin.state===JSON.stringify(before),{method,origin});
    }
    const row=await this.client.request(method,params,this.timeout);
    if(!row.error){const items=method==="textDocument/completion"?(Array.isArray(row.result)?row.result:row.result?.items):row.result;
      if(Array.isArray(items))for(const item of items)if(item&&typeof item==="object")this.origins.set(item,{method,state:JSON.stringify(before),requestId:row.id});
    }
    const record:any={schemaVersion:1,clockDomain:"client",requestEventId:this.client.events.find(e=>e.direction==="send"&&e.message.id===row.id)?.sequence,responseEventId:this.client.events.find(e=>e.direction==="receive"&&e.message.id===row.id)?.sequence,operationId:"op-"+(this.operations.length+1),method,state,requestId:row.id,startNs:row.startNs,endNs:row.endNs,
      latencyMs:Number(BigInt(row.endNs)-BigInt(row.startNs))/1e6,stateBefore:before,
      originRequestId:origin?.requestId,rawResult:row.result,error:row.error,outcome:row.error?(row.error.kind==="timeout"?"timeout":"protocol_error"):"pass",
      freshness:{status:freshnessWitness?"verified":"not_applicable",witness:freshnessWitness??"unchanged fixture; semantic oracle checked"}};
    if(trigger!==undefined){record.triggerNs=String(trigger);record.transitionMs=Number(BigInt(row.endNs)-trigger)/1e6;}
    const validationStart=now();
    if(!row.error)try{oracle(row.result);}catch(e){record.outcome="incorrect";record.assertionError=String(e);record.freshness={status:"contradicted",reason:String(e)};}
    record.validationMs=Number(now()-validationStart)/1e6;this.operations.push(record);
    assert.equal(record.outcome,"pass",`${method}: ${record.assertionError??JSON.stringify(row.error)}`);
    return row.result;
  }
  async series(method:string,params:any,oracle:(result:any)=>void){
    this.seriesExpectations.push({method,firstOperationIndex:this.operations.length,firstUse:1,warmup:this.warmup,steady:this.samples});
    const first=await this.query(method,params,oracle,"first_use");
    for(let i=0;i<this.warmup;i++)await this.query(method,params,oracle,"warmup");
    for(let i=0;i<this.samples;i++)await this.query(method,params,oracle,"steady");return first;
  }
  async transition(method:string,params:()=>any,oracle:(result:any)=>void,trigger:bigint,witness:string){
    const deadline=Date.now()+this.timeout;let attempt=0;
    for(;;){
      attempt++;
      try{return await this.query(method,params(),oracle,attempt===1?"changed_immediate":"changed_retry_"+attempt,trigger,witness);}
      catch(error){if(Date.now()>=deadline||attempt>=100)throw error;await new Promise(resolve=>setTimeout(resolve,20));}
    }
  }
  async setupQuery(method:string,params:any){const r=await this.client.request(method,params,this.timeout);assert(!r.error,method+": "+JSON.stringify(r.error));return r.result;}
  applyWorkspaceEdit(edit:any){
    const changes:any[]=[...Object.entries<any[]>(edit?.changes??{}).map(([uri,edits])=>({textDocument:{uri,version:null},edits})),...(edit?.documentChanges??[])];
    assert(changes.length>0,"workspace edit empty");
    for(const change of changes){
      assert(!change.kind,"resource edit requires a dedicated fixture handler");
      const uri=change.textDocument?.uri,d=this.documents.get(uri);assert(d,"edit targets unopened fixture document");
      assert(change.textDocument.version==null||change.textDocument.version===d.version,"stale workspace edit");
      const text=applyTextEdits(d.text,change.edits),f=Object.values(this.fixture.files).find(f=>f.uri===uri);assert(f);
      this.change(Object.keys(this.fixture.files).find(k=>this.fixture.files[k]===f)!,text);
    }
  }
  compileOracle(){
    const root=path.join(this.fixture.root,"..","oracle");mkdirSync(root,{recursive:true});
    const sources=Object.keys(this.fixture.files).map(name=>{const file=path.join(root,name);writeFileSync(file,this.text(name));return file;});
    const started=now();const result=spawnSync(path.join(this.javaHome,"bin/javac"),["-proc:none","--release","17","-d",path.join(root,"classes"),...sources],{encoding:"utf8",timeout:30000});
    this.assertions.push({name:"independent javac validation",passed:result.status===0,elapsedMs:Number(now()-started)/1e6,stdout:result.stdout,stderr:result.stderr,error:String(result.error??"")});
    assert.equal(result.status,0,"edited fixture does not compile: "+result.stderr);
  }
  actionParams(name:string,token:string){const f=this.file(name),start=this.text(name).indexOf(token);assert(start>=0);
    const before=this.text(name).slice(0,start),line=before.split("\n").length-1,character=before.length-before.lastIndexOf("\n")-1;
    return {textDocument:{uri:f.uri},range:{start:{line,character},end:{line,character:character+token.length}},context:{diagnostics:[]}};
  }
  execute(command:string,args:any[],oracle:(result:any)=>void,state="first_use"){
    return this.query("workspace/executeCommand",{command,arguments:args},oracle,state);
  }
  assert(name:string,condition:boolean,detail?:any){this.assertions.push({name,passed:condition,detail});assert(condition,name);}
}
