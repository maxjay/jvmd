/** Production LSP/RPC stack with a pipe adapter for environments without Unix sockets. */
import { AsyncLocalStorage } from 'node:async_hooks';
import { spawn } from 'node:child_process';
import { Duplex } from 'node:stream';
import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

const config=JSON.parse(await readFile(process.argv[2], 'utf8'));
const { RpcClient, Framing, encode }=await import(pathToFileURL(config.repo+'/shim/src/transport.ts').href);
const { LspBridge, collect }=await import(pathToFileURL(config.repo+'/shim/src/lsp.ts').href);
const child=spawn(config.command[0],config.command.slice(1),{stdio:['pipe','pipe','inherit']});
const exited=new Promise<number|null>(resolve=>child.once('exit',resolve));
const stream=new Duplex({read(){},write(chunk,encoding,callback){child.stdin.write(chunk,encoding,callback);}});
child.stdout.on('data',chunk=>stream.push(chunk));child.stdout.on('end',()=>stream.push(null));
child.on('error',error=>stream.destroy(error));
const client=new RpcClient(stream as any);
const traceContext=config.trace?new AsyncLocalStorage<any>():null;
// One fixture workflow spans the bridge's RPCs and delayed diagnostics. Never shipped.
if(config.trace){
  // RpcClient writes framed messages; decorate only this benchmark-owned transport.
  const write=stream.write.bind(stream);
  stream.write=((chunk:any,...args:any[])=>{
    const bytes=Buffer.isBuffer(chunk)?chunk:Buffer.from(chunk);
    const boundary=bytes.indexOf('\r\n\r\n');
    if(boundary>=0){const message=JSON.parse(bytes.subarray(boundary+4).toString());message._jvmdTrace=traceContext?.getStore()||config.trace;return write(encode(message),...args);}
    return write(chunk,...args);
  }) as any;
}
const send=(message:any)=>process.stdout.write(encode(message));
const bridge=new LspBridge(async()=>client,config.root,send,()=>{});
let queue=Promise.resolve();
async function handle(message:any){
  if(message.method==='benchmark/traceContext'){
    if(config.trace)Object.assign(config.trace,message.params);
    send({jsonrpc:'2.0',id:message.id,result:null});return;
  }
  if(message.method==='jvmd/request'){
    try { send({jsonrpc:'2.0',id:message.id,result:await collect(client,message.params.method,message.params.params||{})}); }
    catch(error){send({jsonrpc:'2.0',id:message.id,error:(error as any).rpc||{code:-32603,message:String(error)}});}return;
  }
  await bridge.handle(message);
  if(message.method==='exit'){
    await bridge.close();
    await client.call('daemon.shutdown');child.stdin.end();await exited;process.stdin.destroy();
  }
}
const framing=new Framing('headers',(message:any)=>{queue=queue.then(()=>traceContext?traceContext.run({...config.trace},()=>handle(message)):handle(message)).catch(error=>{console.error(error);child.kill();process.exitCode=1;process.stdin.destroy();});});
process.stdin.on('data',chunk=>framing.push(chunk));
