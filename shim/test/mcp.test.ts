import { test } from "node:test";
import assert from "node:assert/strict";
import { McpBridge } from "../src/mcp.ts";

test("MCP maps the Java catalog and preserves bounded error continuations",async()=>{
  const seen:any[]=[];
  const envelope={tier:2,source:"live",truncated:true,cursor:"budget:one:1",warnings:[],result:{body:"🧪".repeat(3000)}};
  const client:any={call:async(method:string,params:any)=>{
    seen.push([method,params]);if(method==="session.open")return {result:{session:"s1"}};
    return {result:{catalog:[{method:"symbol.find",tool:{name:"find",inputSchema:{type:"object"}}}]}};
  },raw:async(method:string,params:any)=>{seen.push([method,params]);return {error:{code:-32004,message:"verify_failed",data:envelope}};}};
  const bridge=new McpBridge(async()=>client,"/repo");
  const init=await bridge.handle({jsonrpc:"2.0",id:1,method:"initialize",params:{protocolVersion:"2025-11-25"}});assert.equal(init?.result.protocolVersion,"2025-11-25");
  assert.equal(await bridge.handle({jsonrpc:"2.0",method:"notifications/initialized"}),undefined);
  const list=await bridge.handle({jsonrpc:"2.0",id:2,method:"tools/list"});assert.equal(list?.result.tools[0].name,"find");
  const answer=await bridge.handle({jsonrpc:"2.0",id:3,method:"tools/call",params:{name:"diagnostics",arguments:{verified:true,cursor:"budget:one:1"}}});
  assert.equal(answer?.result.isError,true);const output=JSON.parse(answer?.result.content[0].text);assert.equal(output.cursor,"budget:one:1");assert.equal(output.error.code,-32004);
  assert.ok(Buffer.byteLength(JSON.stringify(answer?.result))<65536);
  assert.deepEqual(seen.at(-1),["mcp.invoke",{session:"s1",name:"diagnostics",arguments:{verified:true,cursor:"budget:one:1"}}]);
  assert.equal(seen.filter(x=>x[0]==="session.open").length,1);
});
