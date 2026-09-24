import { test } from "node:test";
import assert from "node:assert/strict";
import { LspBridge, collect } from "../src/lsp.ts";
import { setTimeout as delay } from "node:timers/promises";

function envelope(value:any){return {tier:2,source:"live",truncated:false,cursor:null,warnings:[],result:{value}};}
test("LSP returns the client's root alias in locations and rename edits",async()=>{
  const sent:any[]=[];
  const client:any={call:async(method:string,params:any)=>{
    if(method==="session.open")return {result:{session:"s1",root:"/physical/project"}};
    if(params.method==="initialize")return envelope({capabilities:{}});
    if(params.method==="textDocument/definition")return envelope({uri:"file:///physical/project/Example.java",range:{}});
    return envelope({changes:{"file:///physical/project/Example.java":[]},documentChanges:[{textDocument:{uri:"file:///physical/project/Example.java",version:3},edits:[]},{kind:"rename",oldUri:"file:///physical/project/Example.java",newUri:"file:///physical/project/New.java"}]});
  }};
  const bridge=new LspBridge(async()=>client,"/linked/project",message=>sent.push(message));
  await bridge.handle({jsonrpc:"2.0",id:1,method:"initialize",params:{}});
  await bridge.handle({jsonrpc:"2.0",id:2,method:"textDocument/definition",params:{}});
  assert.equal(sent.at(-1).result.uri,"file:///linked/project/Example.java");
  await bridge.handle({jsonrpc:"2.0",id:3,method:"textDocument/rename",params:{}});
  const result=sent.at(-1).result;
  assert.deepEqual(Object.keys(result.changes),["file:///linked/project/Example.java"]);
  assert.equal(result.documentChanges[0].textDocument.uri,"file:///linked/project/Example.java");
  assert.equal(result.documentChanges[0].textDocument.version,3);
  assert.equal(result.documentChanges[1].newUri,"file:///linked/project/New.java");
  await bridge.close();
});
test("LSP synchronizes notifications in order and debounces diagnostics for 200 ms",async()=>{
  const seen:any[]=[],sent:any[]=[];let version=0;
  const client:any={call:async(method:string,params:any)=>{
    seen.push([method,params,Date.now()]);
    if(method==="session.open")return {result:{session:"s1"}};
    if(method==="document.open"||method==="document.change"){version=params.version;return envelope({});}
    if(method==="lsp.request")return envelope(params.method==="initialize"?{capabilities:{hoverProvider:true}}:{contents:{kind:"markdown",value:"current "+version}});
    if(method==="lsp.diagnostics")return envelope({uri:params.uri,version,diagnostics:[]});
    return envelope({});
  }};
  const bridge=new LspBridge(async()=>client,"/repo",message=>sent.push(message));
  await bridge.handle({jsonrpc:"2.0",id:1,method:"initialize",params:{rootUri:"file:///repo",capabilities:{}}});
  await bridge.handle({jsonrpc:"2.0",method:"textDocument/didOpen",params:{textDocument:{uri:"file:///repo/Example.java",languageId:"java",version:1,text:"class Example {}"}}});
  for(let v=2;v<=4;v++)await bridge.handle({jsonrpc:"2.0",method:"textDocument/didChange",params:{textDocument:{uri:"file:///repo/Example.java",version:v},contentChanges:[{text:"class Example { int value="+v+"; }"}]}});
  const changed=Date.now();await delay(80);assert.equal(seen.filter(x=>x[0]==="lsp.diagnostics").length,0);
  await bridge.handle({jsonrpc:"2.0",id:2,method:"textDocument/hover",params:{textDocument:{uri:"file:///repo/Example.java"},position:{line:0,character:6}}});
  assert.match(sent.find(x=>x.id===2).result.contents.value,/current 4/);await delay(260);await bridge.drained();
  const diagnostics=seen.filter(x=>x[0]==="lsp.diagnostics");assert.equal(diagnostics.length,1);assert.ok(diagnostics[0][2]-changed>=190);
  assert.equal(sent.filter(x=>x.method==="textDocument/publishDiagnostics").length,1);assert.equal(sent.at(-1).params.version,4);await bridge.close();
});
test("LSP suppresses diagnostics superseded while javac is running",async()=>{
  const sent:any[]=[];let finish:(value:any)=>void=()=>{},started:(value?:unknown)=>void=()=>{};
  const analyzing=new Promise(resolve=>started=resolve);
  const client:any={call:async(method:string,params:any)=>{
    if(method==="session.open")return {result:{session:"s1"}};
    if(method==="lsp.diagnostics"){started();return new Promise(resolve=>finish=resolve);}
    return envelope(params.method==="initialize"?{capabilities:{}}:{});
  }};
  const bridge=new LspBridge(async()=>client,"/repo",message=>sent.push(message));
  await bridge.handle({jsonrpc:"2.0",id:1,method:"initialize",params:{}});
  await bridge.handle({jsonrpc:"2.0",method:"textDocument/didOpen",params:{textDocument:{uri:"file:///repo/A.java",languageId:"java",version:1,text:"class A {}"}}});
  await analyzing;
  const change=bridge.handle({jsonrpc:"2.0",method:"textDocument/didChange",params:{textDocument:{uri:"file:///repo/A.java",version:2},contentChanges:[{text:"class A {int n;}"}]}});
  finish(envelope({uri:"file:///repo/A.java",version:1,diagnostics:[{message:"old"}]}));await change;assert.equal(sent.filter(x=>x.method==="textDocument/publishDiagnostics").length,0);await bridge.close();
});
test("LSP coalesces stale completion requests before they reach the daemon",async()=>{
  const sent:any[]=[];let release:(value:any)=>void=()=>{},started:(value?:unknown)=>void=()=>{},completionCalls=0;
  const firstStarted=new Promise(resolve=>started=resolve);
  const client:any={call:async(method:string,params:any)=>{
    if(method==="session.open")return {result:{session:"s1"}};
    if(method==="lsp.request"&&params.method==="initialize")return envelope({capabilities:{}});
    if(method==="lsp.request"&&params.method==="textDocument/completion"){
      completionCalls++;
      if(completionCalls===1){started();return new Promise(resolve=>release=resolve);}
      return envelope({isIncomplete:false,items:[{label:"latest"}]});
    }
    return envelope({});
  }};
  const bridge=new LspBridge(async()=>client,"/repo",message=>sent.push(message));
  await bridge.handle({jsonrpc:"2.0",id:1,method:"initialize",params:{}});
  const params=(character:number)=>({textDocument:{uri:"file:///repo/A.java"},position:{line:0,character}});
  const first=bridge.handle({jsonrpc:"2.0",id:2,method:"textDocument/completion",params:params(1)});
  await firstStarted;
  const second=bridge.handle({jsonrpc:"2.0",id:3,method:"textDocument/completion",params:params(2)});
  const third=bridge.handle({jsonrpc:"2.0",id:4,method:"textDocument/completion",params:params(3)});
  release(envelope({isIncomplete:false,items:[{label:"stale"}]}));
  await Promise.all([first,second,third]);
  assert.equal(completionCalls,2);
  assert.equal(sent.find(x=>x.id===2).error.code,-32800);
  assert.equal(sent.find(x=>x.id===3).error.code,-32800);
  assert.equal(sent.find(x=>x.id===4).result.items[0].label,"latest");
  await bridge.close();
});
test("LSP cancelRequest cancels an in-flight completion response",async()=>{
  const sent:any[]=[];let release:(value:any)=>void=()=>{},started:(value?:unknown)=>void=()=>{};
  const firstStarted=new Promise(resolve=>started=resolve);
  const client:any={call:async(method:string,params:any)=>{
    if(method==="session.open")return {result:{session:"s1"}};
    if(method==="lsp.request"&&params.method==="initialize")return envelope({capabilities:{}});
    if(method==="lsp.request"&&params.method==="textDocument/completion"){started();return new Promise(resolve=>release=resolve);}
    return envelope({});
  }};
  const bridge=new LspBridge(async()=>client,"/repo",message=>sent.push(message));
  await bridge.handle({jsonrpc:"2.0",id:1,method:"initialize",params:{}});
  const pending=bridge.handle({jsonrpc:"2.0",id:2,method:"textDocument/completion",params:{textDocument:{uri:"file:///repo/A.java"},position:{line:0,character:1}}});
  await firstStarted;
  await bridge.handle({jsonrpc:"2.0",method:"$/cancelRequest",params:{id:2}});
  release(envelope({isIncomplete:false,items:[{label:"stale"}]}));await pending;
  assert.equal(sent.find(x=>x.id===2).error.code,-32800);
  assert.equal(sent.some(x=>x.id===2&&x.result),false);
  await bridge.close();
});
test("LSP reassembles UTF-16 and nested array fragments without duplicating values",async()=>{
  const values=[
    {...envelope({items:[{text:"A😀"}]}),truncated:true,cursor:"budget:x:1"},
    {...envelope({items:[{text:"B"}]}),truncated:false,cursor:null}
  ];
  for(let i=0;i<2;i++)values[i].result._jvmd_segments={"/payload/value/items":{kind:"array",start:0,end:1,total:1},"/payload/value/items/0/text":{kind:"string",start:i===0?0:3,end:i===0?3:4,total:4}};
  let calls=0;const response=await collect({call:async()=>values[calls++]} as any,"lsp.request",{session:"s1"});
  assert.equal(calls,2);assert.deepEqual(response.result.value,{items:[{text:"A😀B"}]});
});

test("LSP streams large references through bounded native partial-result notifications",async()=>{
  const sent:any[]=[],locations=Array.from({length:1300},(_,line)=>({uri:"file:///repo/Example.java",range:{start:{line,character:0},end:{line,character:4}}}));
  const client:any={call:async(method:string,params:any)=>method==="session.open"?{result:{session:"s1"}}:envelope(params.method==="initialize"?{capabilities:{}}:locations)};
  const bridge=new LspBridge(async()=>client,"/repo",message=>sent.push(message));
  await bridge.handle({jsonrpc:"2.0",id:1,method:"initialize",params:{}});
  await bridge.handle({jsonrpc:"2.0",id:2,method:"textDocument/references",params:{textDocument:{uri:"file:///repo/Example.java"},position:{line:0,character:0},partialResultToken:"partial-2"}});
  const chunks=sent.filter(x=>x.method==="$/progress");assert.ok(chunks.length>1);assert.deepEqual(chunks.flatMap(x=>x.params.value),locations);
  assert.deepEqual(sent.at(-1).result,[]);for(const message of sent)assert.ok(Buffer.byteLength(JSON.stringify(message))<=65536);await bridge.close();
});

test("LSP completion waits for its own pending document mutation",async()=>{
  const sent:any[]=[];let release:(value:any)=>void=()=>{},started:(value?:unknown)=>void=()=>{},completionCalls=0;
  const changing=new Promise(resolve=>started=resolve);
  const client:any={call:async(method:string,params:any)=>{
    if(method==="session.open")return {result:{session:"s1"}};
    if(method==="lsp.request"&&params.method==="initialize")return envelope({capabilities:{}});
    if(method==="document.change"){started();return new Promise(resolve=>release=resolve);}
    if(method==="lsp.request"&&params.method==="textDocument/completion"){completionCalls++;return envelope({isIncomplete:false,items:[{label:"after-change"}]});}
    return envelope({});
  }};
  const bridge=new LspBridge(async()=>client,"/repo",message=>sent.push(message));
  await bridge.handle({jsonrpc:"2.0",id:1,method:"initialize",params:{}});
  await bridge.handle({jsonrpc:"2.0",method:"textDocument/didOpen",params:{textDocument:{uri:"file:///repo/A.java",languageId:"java",version:1,text:"class A {}"}}});
  const change=bridge.handle({jsonrpc:"2.0",method:"textDocument/didChange",params:{textDocument:{uri:"file:///repo/A.java",version:2},contentChanges:[{text:"class A { int n; }"}]}});
  await changing;
  const completion=bridge.handle({jsonrpc:"2.0",id:2,method:"textDocument/completion",params:{textDocument:{uri:"file:///repo/A.java"},position:{line:0,character:10}}});
  await delay(20);assert.equal(completionCalls,0);
  release(envelope({}));await Promise.all([change,completion]);
  assert.equal(completionCalls,1);assert.equal(sent.find(x=>x.id===2).result.items[0].label,"after-change");
  await bridge.close();
});
test("LSP interactive request in B waits for a preceding mutation in A",async()=>{
  const sent:any[]=[];let release:(value:any)=>void=()=>{},started:(value?:unknown)=>void=()=>{},definitionCalls=0;
  const changing=new Promise(resolve=>started=resolve);
  const client:any={call:async(method:string,params:any)=>{
    if(method==="session.open")return {result:{session:"s1"}};
    if(method==="lsp.request"&&params.method==="initialize")return envelope({capabilities:{}});
    if(method==="document.change"&&params.path==="/repo/A.java"){started();return new Promise(resolve=>release=resolve);}
    if(method==="lsp.request"&&params.method==="textDocument/definition"){definitionCalls++;return envelope([]);}
    return envelope({});
  }};
  const bridge=new LspBridge(async()=>client,"/repo",message=>sent.push(message));
  await bridge.handle({jsonrpc:"2.0",id:1,method:"initialize",params:{}});
  await bridge.handle({jsonrpc:"2.0",method:"textDocument/didOpen",params:{textDocument:{uri:"file:///repo/A.java",languageId:"java",version:1,text:"class A {}"}}});
  const change=bridge.handle({jsonrpc:"2.0",method:"textDocument/didChange",params:{textDocument:{uri:"file:///repo/A.java",version:2},contentChanges:[{text:"class A { int value; }"}]}});
  await changing;
  const definition=bridge.handle({jsonrpc:"2.0",id:2,method:"textDocument/definition",params:{textDocument:{uri:"file:///repo/B.java"},position:{line:0,character:0}}});
  await delay(20);assert.equal(definitionCalls,0,"B must not query before the preceding A mutation is accepted");
  release(envelope({}));await Promise.all([change,definition]);
  assert.equal(definitionCalls,1);assert.deepEqual(sent.find(x=>x.id===2).result,[]);
  await bridge.close();
});

test("LSP preserves rapid same-document mutation order without a global lane",async()=>{
  const finished:number[]=[];
  const client:any={call:async(method:string,params:any)=>{
    if(method==="session.open")return {result:{session:"s1"}};
    if(method==="lsp.request"&&params.method==="initialize")return envelope({capabilities:{}});
    if(method==="document.change"){await delay(params.version===2?30:params.version===3?10:0);finished.push(params.version);return envelope({});}
    if(method==="lsp.diagnostics")return envelope({uri:params.uri,version:4,diagnostics:[]});
    return envelope({});
  }};
  const bridge=new LspBridge(async()=>client,"/repo",()=>{});
  await bridge.handle({jsonrpc:"2.0",id:1,method:"initialize",params:{}});
  await bridge.handle({jsonrpc:"2.0",method:"textDocument/didOpen",params:{textDocument:{uri:"file:///repo/A.java",languageId:"java",version:1,text:"class A {}"}}});
  const changes=[2,3,4].map(version=>bridge.handle({jsonrpc:"2.0",method:"textDocument/didChange",params:{textDocument:{uri:"file:///repo/A.java",version},contentChanges:[{text:"class A { int n="+version+"; }"}]}}));
  await Promise.all(changes);assert.deepEqual(finished,[2,3,4]);
  await bridge.close();
});
test("LSP background diagnostics do not block a successful completion",async()=>{
  const sent:any[]=[];let release:(value:any)=>void=()=>{},started:(value?:unknown)=>void=()=>{};
  const analyzing=new Promise(resolve=>started=resolve);
  const client:any={call:async(method:string,params:any)=>{
    if(method==="session.open")return {result:{session:"s1"}};
    if(method==="lsp.request"&&params.method==="initialize")return envelope({capabilities:{}});
    if(method==="lsp.diagnostics"){started();return new Promise(resolve=>release=resolve);}
    if(method==="lsp.request"&&params.method==="textDocument/completion")return envelope({isIncomplete:false,items:[{label:"valid"}]});
    return envelope({});
  }};
  const bridge=new LspBridge(async()=>client,"/repo",message=>sent.push(message));
  await bridge.handle({jsonrpc:"2.0",id:1,method:"initialize",params:{}});
  await bridge.handle({jsonrpc:"2.0",method:"textDocument/didOpen",params:{textDocument:{uri:"file:///repo/A.java",languageId:"java",version:1,text:"class A {}"}}});
  await analyzing;
  const completion=bridge.handle({jsonrpc:"2.0",id:2,method:"textDocument/completion",params:{textDocument:{uri:"file:///repo/A.java"},position:{line:0,character:5}}});
  const winner=await Promise.race([completion.then(()=>"completion"),delay(100).then(()=>"timeout")]);
  assert.equal(winner,"completion");assert.equal(sent.find(x=>x.id===2).result.items[0].label,"valid");
  release(envelope({uri:"file:///repo/A.java",version:1,diagnostics:[]}));await bridge.drained();await bridge.close();
});
test("LSP diagnostics in one document do not block another document's completion",async()=>{
  const sent:any[]=[];let release:(value:any)=>void=()=>{},started:(value?:unknown)=>void=()=>{};
  const analyzing=new Promise(resolve=>started=resolve);
  const client:any={call:async(method:string,params:any)=>{
    if(method==="session.open")return {result:{session:"s1"}};
    if(method==="lsp.request"&&params.method==="initialize")return envelope({capabilities:{}});
    if(method==="lsp.diagnostics"&&params.uri==="file:///repo/A.java"){started();return new Promise(resolve=>release=resolve);}
    if(method==="lsp.request"&&params.method==="textDocument/completion")return envelope({isIncomplete:false,items:[{label:"B"}]});
    return envelope({});
  }};
  const bridge=new LspBridge(async()=>client,"/repo",message=>sent.push(message));
  await bridge.handle({jsonrpc:"2.0",id:1,method:"initialize",params:{}});
  await bridge.handle({jsonrpc:"2.0",method:"textDocument/didOpen",params:{textDocument:{uri:"file:///repo/A.java",languageId:"java",version:1,text:"class A {}"}}});
  await analyzing;
  const completion=bridge.handle({jsonrpc:"2.0",id:2,method:"textDocument/completion",params:{textDocument:{uri:"file:///repo/B.java"},position:{line:0,character:0}}});
  const winner=await Promise.race([completion.then(()=>"completion"),delay(100).then(()=>"timeout")]);
  assert.equal(winner,"completion");assert.equal(sent.find(x=>x.id===2).result.items[0].label,"B");
  release(envelope({uri:"file:///repo/A.java",version:1,diagnostics:[]}));await bridge.drained();await bridge.close();
});
test("LSP cancellation while waiting for a mutation avoids the daemon call",async()=>{
  const sent:any[]=[];let release:(value:any)=>void=()=>{},started:(value?:unknown)=>void=()=>{},completionCalls=0;
  const changing=new Promise(resolve=>started=resolve);
  const client:any={call:async(method:string,params:any)=>{
    if(method==="session.open")return {result:{session:"s1"}};
    if(method==="lsp.request"&&params.method==="initialize")return envelope({capabilities:{}});
    if(method==="document.change"){started();return new Promise(resolve=>release=resolve);}
    if(method==="lsp.request"&&params.method==="textDocument/completion"){completionCalls++;return envelope({isIncomplete:false,items:[]});}
    return envelope({});
  }};
  const bridge=new LspBridge(async()=>client,"/repo",message=>sent.push(message));
  await bridge.handle({jsonrpc:"2.0",id:1,method:"initialize",params:{}});
  await bridge.handle({jsonrpc:"2.0",method:"textDocument/didOpen",params:{textDocument:{uri:"file:///repo/A.java",languageId:"java",version:1,text:"class A {}"}}});
  const change=bridge.handle({jsonrpc:"2.0",method:"textDocument/didChange",params:{textDocument:{uri:"file:///repo/A.java",version:2},contentChanges:[{text:"class A {int n;}"}]}});
  await changing;
  const completion=bridge.handle({jsonrpc:"2.0",id:2,method:"textDocument/completion",params:{textDocument:{uri:"file:///repo/A.java"},position:{line:0,character:1}}});
  await bridge.handle({jsonrpc:"2.0",method:"$/cancelRequest",params:{id:2}});
  release(envelope({}));await Promise.all([change,completion]);
  assert.equal(completionCalls,0);assert.equal(sent.find(x=>x.id===2).error.code,-32800);
  await bridge.close();
});
test("LSP shutdown suppresses an in-flight stale diagnostic publication",async()=>{
  const sent:any[]=[];let release:(value:any)=>void=()=>{},started:(value?:unknown)=>void=()=>{};
  const analyzing=new Promise(resolve=>started=resolve);
  const client:any={call:async(method:string,params:any)=>{
    if(method==="session.open")return {result:{session:"s1"}};
    if(method==="lsp.request"&&params.method==="initialize")return envelope({capabilities:{}});
    if(method==="lsp.diagnostics"){started();return new Promise(resolve=>release=resolve);}
    return envelope({});
  }};
  const bridge=new LspBridge(async()=>client,"/repo",message=>sent.push(message));
  await bridge.handle({jsonrpc:"2.0",id:1,method:"initialize",params:{}});
  await bridge.handle({jsonrpc:"2.0",method:"textDocument/didOpen",params:{textDocument:{uri:"file:///repo/A.java",languageId:"java",version:1,text:"class A {}"}}});
  await analyzing;
  const shutdown=bridge.handle({jsonrpc:"2.0",id:2,method:"shutdown",params:{}});
  release(envelope({uri:"file:///repo/A.java",version:1,diagnostics:[{message:"obsolete"}]}));await shutdown;
  assert.equal(sent.filter(x=>x.method==="textDocument/publishDiagnostics").length,0);
  assert.equal(sent.find(x=>x.id===2).result,null);await bridge.close();
});

test("LSP defers same-document diagnostics while interactive work is active",async()=>{
  const sent:any[]=[];let release:(value:any)=>void=()=>{},started:(value?:unknown)=>void=()=>{},diagnosticCalls=0;
  const completionStarted=new Promise(resolve=>started=resolve);
  const client:any={call:async(method:string,params:any)=>{
    if(method==="session.open")return {result:{session:"s1"}};
    if(method==="lsp.request"&&params.method==="initialize")return envelope({capabilities:{}});
    if(method==="lsp.request"&&params.method==="textDocument/completion"){started();return new Promise(resolve=>release=resolve);}
    if(method==="lsp.diagnostics"){diagnosticCalls++;return envelope({uri:params.uri,version:1,diagnostics:[]});}
    return envelope({});
  }};
  const bridge=new LspBridge(async()=>client,"/repo",message=>sent.push(message));
  await bridge.handle({jsonrpc:"2.0",id:1,method:"initialize",params:{}});
  await bridge.handle({jsonrpc:"2.0",method:"textDocument/didOpen",params:{textDocument:{uri:"file:///repo/A.java",languageId:"java",version:1,text:"class A {}"}}});
  const completion=bridge.handle({jsonrpc:"2.0",id:2,method:"textDocument/completion",params:{textDocument:{uri:"file:///repo/A.java"},position:{line:0,character:5}}});
  await completionStarted;await delay(260);assert.equal(diagnosticCalls,0);
  release(envelope({isIncomplete:false,items:[{label:"done"}]}));await completion;
  await delay(260);assert.equal(diagnosticCalls,1);
  await bridge.close();
});

test("LSP does not pre-admit unrelated diagnostics ahead of active interactive work",async()=>{
  let release:(value:any)=>void=()=>{},started:(value?:unknown)=>void=()=>{},diagnosticCalls=0;
  const completionStarted=new Promise(resolve=>started=resolve);
  const client:any={call:async(method:string,params:any)=>{
    if(method==="session.open")return {result:{session:"s1"}};
    if(method==="lsp.request"&&params.method==="initialize")return envelope({capabilities:{}});
    if(method==="lsp.request"&&params.method==="textDocument/completion"){started();return new Promise(resolve=>release=resolve);}
    if(method==="lsp.diagnostics"){diagnosticCalls++;return envelope({uri:params.uri,version:1,diagnostics:[]});}
    return envelope({});
  }};
  const bridge=new LspBridge(async()=>client,"/repo",()=>{});
  await bridge.handle({jsonrpc:"2.0",id:1,method:"initialize",params:{}});
  await bridge.handle({jsonrpc:"2.0",method:"textDocument/didOpen",params:{textDocument:{uri:"file:///repo/A.java",languageId:"java",version:1,text:"class A {}"}}});
  const completion=bridge.handle({jsonrpc:"2.0",id:2,method:"textDocument/completion",params:{textDocument:{uri:"file:///repo/B.java"},position:{line:0,character:0}}});
  await completionStarted;await delay(260);assert.equal(diagnosticCalls,0);
  release(envelope({isIncomplete:false,items:[{label:"B"}]}));await completion;
  await delay(260);assert.equal(diagnosticCalls,1);
  await bridge.close();
});
