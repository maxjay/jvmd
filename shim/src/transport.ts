/** Implements 4.1 and 12.3: UTF-8 Content-Length RPC over the resident daemon's Unix socket. */
import net from "node:net";
import { spawn } from "node:child_process";
import { readFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

export type Message = { jsonrpc: string; id?: string | number | null; method?: string; params?: any; result?: any; error?: any };
export type RpcCaller = { call(method:string,params?:any):Promise<any> };
export class Framing {
  private buffer = Buffer.alloc(0);
  private length: number | null = null;
  constructor(privateMode: "headers" | "lines", receive: (message: Message) => void) { this.mode=privateMode;this.receive=receive; }
  mode: "headers" | "lines";
  receive: (message: Message) => void;
  push(chunk: Buffer) {
    this.buffer=Buffer.concat([this.buffer,chunk]);
    for (;;) {
      if(this.mode==="lines") {
        const end=this.buffer.indexOf(10);
        if(end<0)break;
        if(end>16*1024*1024)throw new Error("Input message exceeds 16 MiB");
        const line=this.buffer.subarray(0,end).toString("utf8").trim();this.buffer=this.buffer.subarray(end+1);
        if(line)this.receive(JSON.parse(line));
        continue;
      }
      if(this.length===null) {
        const end=this.buffer.indexOf("\r\n\r\n");
        if(end<0){if(this.buffer.length>8192)throw new Error("Header exceeds 8 KiB");break;}
        const headers=this.buffer.subarray(0,end).toString("ascii").split("\r\n");
        const lengths=headers.filter(h=>/^content-length:/i.test(h));
        if(lengths.length!==1||!/^content-length:\s*\d+\s*$/i.test(lengths[0]))throw new Error("Invalid Content-Length");
        this.length=Number(lengths[0].split(":")[1]);
        if(!Number.isSafeInteger(this.length)||this.length<0||this.length>16*1024*1024)throw new Error("Invalid message length");
        this.buffer=this.buffer.subarray(end+4);
      }
      if(this.buffer.length<this.length)break;
      const body=this.buffer.subarray(0,this.length);this.buffer=this.buffer.subarray(this.length);this.length=null;
      this.receive(JSON.parse(body.toString("utf8")));
    }
    if(this.buffer.length>16*1024*1024)throw new Error("Input message exceeds 16 MiB");
  }
}
export function encode(message: Message) {
  const body=Buffer.from(JSON.stringify(message),"utf8");
  return Buffer.concat([Buffer.from("Content-Length: "+body.length+"\r\n\r\n"),body]);
}
export class RpcClient {
  private next=0;
  private pending=new Map<number,{resolve:(message:Message)=>void;reject:(error:Error)=>void;timer:ReturnType<typeof setTimeout>}>();
  onNotification: (message: Message) => void = ()=>{};
  constructor(socket: net.Socket) {
    this.socket=socket;
    const framing=new Framing("headers",message=>{
      if(typeof message.id==="number"&&this.pending.has(message.id)){const pending=this.pending.get(message.id)!;this.pending.delete(message.id);clearTimeout(pending.timer);pending.resolve(message);}
      else if(message.method)this.onNotification(message);
    });
    socket.on("data",chunk=>{try{framing.push(chunk);}catch(error){socket.destroy(error as Error);}});
    socket.on("error",error=>this.fail(error));socket.on("close",()=>this.fail(new Error("Daemon connection closed")));
  }
  socket: net.Socket;
  private fail(error: Error) { for(const p of this.pending.values()){clearTimeout(p.timer);p.reject(error);}this.pending.clear(); }
  raw(method: string,params: any = {},timeoutMs=360000): Promise<Message> {
    if(this.socket.destroyed)return Promise.reject(new Error("Daemon connection is closed"));
    const id=++this.next;
    return new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>{this.pending.delete(id);reject(new Error("Daemon request timed out: "+method));},timeoutMs);
      this.pending.set(id,{resolve,reject,timer});this.socket.write(encode({jsonrpc:"2.0",id,method,params}));
    });
  }
  async call(method: string,params: any = {}) {
    const response=await this.raw(method,params);
    if(response.error){const error=new Error(response.error.message) as Error & {rpc:any};error.rpc=response.error;throw error;}
    return response.result;
  }
  close(){this.socket.destroy();}
}
/**
 * LSP calls may overlap. Each busy daemon connection is kept out of the idle set so
 * concurrent calls use independent Unix connections instead of inheriting a socket FIFO.
 */
export class RpcPool implements RpcCaller {
  private idle:RpcClient[]=[];
  private all=new Set<RpcClient>();
  private closed=false;
  private create:()=>Promise<RpcClient>;
  private maxIdle:number;
  constructor(create:()=>Promise<RpcClient>,maxIdle=4){this.create=create;this.maxIdle=maxIdle;}
  async call(method:string,params:any={}){
    const client=this.idle.pop()||await this.open();
    try{return await client.call(method,params);}
    finally{this.release(client);}
  }
  private async open(){
    if(this.closed)throw new Error("RPC pool is closed");
    const client=await this.create();
    if(this.closed){client.close();throw new Error("RPC pool is closed");}
    this.all.add(client);return client;
  }
  private release(client:RpcClient){
    if(this.closed||client.socket.destroyed||this.idle.length>=this.maxIdle){
      this.all.delete(client);client.close();return;
    }
    this.idle.push(client);
  }
  close(){if(this.closed)return;this.closed=true;for(const client of this.all)client.close();this.all.clear();this.idle=[];}
}
export async function defaults() {
  let config:any={};
  try{config=JSON.parse(await readFile(process.env.JVMD_CONFIG||path.join(os.homedir(),".config/jvmd/config.json"),"utf8"));}
  catch(error){if((error as NodeJS.ErrnoException).code!=="ENOENT")throw error;}
  const uid=process.getuid!();
  return {socket:config.socket||path.join(process.env.XDG_RUNTIME_DIR||path.join(os.tmpdir(),"jvmd-"+uid),"jvmd-"+uid+".sock"),launcher:process.env.JVMD_LAUNCHER||"jvmd"};
}
async function connectOnce(socketPath: string):Promise<net.Socket> {
  return new Promise((resolve,reject)=>{
    const socket=net.createConnection(socketPath);
    socket.once("error",reject);
    socket.once("connect",()=>{socket.removeListener("error",reject);resolve(socket);});
  });
}
export async function connect(socketPath:string,launcher:string):Promise<RpcClient> {
  try{return new RpcClient(await connectOnce(socketPath));}
  catch(error){if(!["ENOENT","ECONNREFUSED"].includes((error as NodeJS.ErrnoException).code||""))throw error;}
  const child=spawn(launcher,[],{detached:true,stdio:"ignore",env:{...process.env,JVMD_SOCKET:socketPath}});
  let launchError:Error|undefined;child.once("error",error=>{launchError=error;});child.unref();
  const deadline=Date.now()+10000;
  while(Date.now()<deadline){
    if(launchError)throw launchError;
    await new Promise(resolve=>setTimeout(resolve,50));
    try{return new RpcClient(await connectOnce(socketPath));}
    catch(error){if(!["ENOENT","ECONNREFUSED"].includes((error as NodeJS.ErrnoException).code||""))throw error;}
  }
  throw new Error("Daemon did not create its socket within 10 seconds");
}
