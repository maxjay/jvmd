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
  assert.match(sent.find(x=>x.id===2).result.contents.value,/current 4/);await delay(180);await bridge.drained();
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
