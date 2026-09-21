/** Production LSP/RPC stack with a pipe adapter for environments without Unix sockets. */
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
const send=(message:any)=>process.stdout.write(encode(message));
let bridge:any,root=config.root;
const bridges:any[]=[];
function makeBridge(){bridge=new LspBridge(async()=>client,root,send,()=>{});bridges.push(bridge);}
makeBridge();
let queue=Promise.resolve();
async function handle(message:any){
  if(message.method==='jvmd/request'){
    try { send({jsonrpc:'2.0',id:message.id,result:await collect(client,message.params.method,message.params.params||{})}); }
    catch(error){send({jsonrpc:'2.0',id:message.id,error:(error as any).rpc||{code:-32603,message:String(error)}});}return;
  }
  if(message.method==='jvmd/openWorkspace'){root=message.params.root;makeBridge();send({jsonrpc:'2.0',id:message.id,result:null});return;}
  await bridge.handle(message);
  if(message.method==='exit'){
    for(const item of bridges)await item.close();
    await client.call('daemon.shutdown');child.stdin.end();await exited;process.stdin.destroy();
  }
}
const framing=new Framing('headers',(message:any)=>{queue=queue.then(()=>handle(message)).catch(error=>{console.error(error);child.kill();process.exitCode=1;process.stdin.destroy();});});
process.stdin.on('data',chunk=>framing.push(chunk));
