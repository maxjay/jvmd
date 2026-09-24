import { test } from "node:test";
import assert from "node:assert/strict";
import net from "node:net";
import { mkdtemp, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { Framing, encode, RpcClient, RpcPool } from "../src/transport.ts";

test("fragmented UTF-8 headers and back-to-back messages retain byte framing",()=>{
  const expected=[{jsonrpc:"2.0",id:1,result:{text:"🧪λ\n"}},{jsonrpc:"2.0",id:2,result:{ok:true}}],actual:any[]=[];
  const framing=new Framing("headers",message=>actual.push(message));const bytes=Buffer.concat(expected.map(encode));
  for(const byte of bytes)framing.push(Buffer.from([byte]));
  assert.deepEqual(actual,expected);
  assert.throws(()=>new Framing("headers",()=>{}).push(Buffer.from("Content-Length: 2\r\nContent-Length: 2\r\n\r\n{}")),/Invalid Content-Length/);
});
test("concurrent RPC replies are correlated across a real Unix socket",async()=>{
  const directory=await mkdtemp(path.join(os.tmpdir(),"jvmd-shim-")),socketPath=path.join(directory,"daemon.sock");
  const server=net.createServer(socket=>{
    const messages:any[]=[];
    const reader=new Framing("headers",message=>{messages.push(message);if(messages.length===2)for(const request of messages.reverse())socket.write(encode({jsonrpc:"2.0",id:request.id,result:{method:request.method}}));});
    socket.on("data",chunk=>reader.push(chunk));
  });
  await new Promise<void>(resolve=>server.listen(socketPath,resolve));
  const socket=net.createConnection(socketPath);await new Promise<void>(resolve=>socket.once("connect",resolve));const client=new RpcClient(socket);
  try{const [one,two]=await Promise.all([client.call("one"),client.call("two")]);assert.equal(one.method,"one");assert.equal(two.method,"two");}
  finally{client.close();await new Promise<void>(resolve=>server.close(()=>resolve()));await rm(directory,{recursive:true,force:true});}
});

test("RPC pool lets an interactive call bypass a busy daemon connection",async()=>{
  const directory=await mkdtemp(path.join(os.tmpdir(),"jvmd-shim-pool-")),socketPath=path.join(directory,"daemon.sock");
  let connections=0,release:(()=>void)=()=>{},started:(()=>void)=()=>{};
  const slowStarted=new Promise<void>(resolve=>started=resolve);
  const server=net.createServer(socket=>{
    connections++;
    const reader=new Framing("headers",request=>{
      if(request.method==="slow"){
        started();release=()=>socket.write(encode({jsonrpc:"2.0",id:request.id,result:{method:"slow"}}));return;
      }
      socket.write(encode({jsonrpc:"2.0",id:request.id,result:{method:request.method}}));
    });
    socket.on("data",chunk=>reader.push(chunk));
  });
  await new Promise<void>(resolve=>server.listen(socketPath,resolve));
  const pool=new RpcPool(async()=>{
    const socket=net.createConnection(socketPath);await new Promise<void>((resolve,reject)=>{socket.once("connect",resolve);socket.once("error",reject);});return new RpcClient(socket);
  });
  try{
    const slow=pool.call("slow");await slowStarted;
    const fast=pool.call("fast");
    const winner=await Promise.race([fast.then(result=>result.method),new Promise<string>(resolve=>setTimeout(()=>resolve("timeout"),200))]);
    assert.equal(winner,"fast");assert.ok(connections>=2);
    release();assert.equal((await slow).method,"slow");
  }finally{pool.close();await new Promise<void>(resolve=>server.close(()=>resolve()));await rm(directory,{recursive:true,force:true});}
});

test("RPC pool bounds live sockets and queues excess calls",async()=>{
  const directory=await mkdtemp(path.join(os.tmpdir(),"jvmd-shim-pool-cap-")),socketPath=path.join(directory,"daemon.sock");
  let connections=0,maxConnections=0,thirdCalls=0;
  const releases=new Map<string,()=>void>();
  const starts=new Map<string,()=>void>();
  const started=(name:string)=>new Promise<void>(resolve=>starts.set(name,resolve));
  const slowOneStarted=started("slow-one"),slowTwoStarted=started("slow-two");
  const server=net.createServer(socket=>{
    connections++;maxConnections=Math.max(maxConnections,connections);
    socket.on("close",()=>connections--);
    const reader=new Framing("headers",request=>{
      if(request.method==="slow-one"||request.method==="slow-two"){
        starts.get(request.method)?.();
        releases.set(request.method,()=>socket.write(encode({jsonrpc:"2.0",id:request.id,result:{method:request.method}})));
        return;
      }
      if(request.method==="third")thirdCalls++;
      socket.write(encode({jsonrpc:"2.0",id:request.id,result:{method:request.method}}));
    });
    socket.on("data",chunk=>reader.push(chunk));
  });
  await new Promise<void>(resolve=>server.listen(socketPath,resolve));
  const pool=new RpcPool(async()=>{
    const socket=net.createConnection(socketPath);
    await new Promise<void>((resolve,reject)=>{socket.once("connect",resolve);socket.once("error",reject);});
    return new RpcClient(socket);
  },2,2);
  try{
    const one=pool.call("slow-one"),two=pool.call("slow-two");
    await Promise.all([slowOneStarted,slowTwoStarted]);
    const third=pool.call("third");
    await new Promise(resolve=>setTimeout(resolve,50));
    assert.equal(maxConnections,2);assert.equal(thirdCalls,0,"third call must wait instead of opening a third socket");
    releases.get("slow-one")?.();
    assert.equal((await third).method,"third");assert.equal(maxConnections,2);
    releases.get("slow-two")?.();
    assert.equal((await one).method,"slow-one");assert.equal((await two).method,"slow-two");
  }finally{pool.close();await new Promise<void>(resolve=>server.close(()=>resolve()));await rm(directory,{recursive:true,force:true});}
});
