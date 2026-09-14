/** Implements 4.1, 4.8 and 4.9: MCP or LSP stdio over one resident daemon connection. */
import path from "node:path";
import { connect, defaults, encode, Framing, type Message } from "./transport.ts";
import { McpBridge } from "./mcp.ts";
import { LspBridge } from "./lsp.ts";

const args=process.argv.slice(2);
function option(name:string){const index=args.indexOf(name);if(index<0)return undefined;if(!args[index+1]||args[index+1].startsWith("--"))throw new Error("Missing "+name);return args[index+1];}
const settings=await defaults(),root=path.resolve(option("--root")||process.cwd());
const socket=option("--socket")||process.env.JVMD_SOCKET||settings.socket,launcher=option("--launcher")||settings.launcher;
let client:ReturnType<typeof connect>|undefined;
const getClient=()=>client??=connect(socket,launcher),lsp=args.includes("--lsp");
function send(message:Message){process.stdout.write(lsp?encode(message):JSON.stringify(message)+"\n");}
const bridge=lsp?new LspBridge(getClient,root,send,code=>{void client?.then(value=>value.close());process.exit(code);}):new McpBridge(getClient,root);
const active=new Set<Promise<void>>();
const framing=new Framing(lsp?"headers":"lines",message=>{
  const request=bridge.handle(message).then(response=>{if(response)send(response);});
  active.add(request);void request.then(()=>active.delete(request),error=>{active.delete(request);process.stderr.write(error.message+"\n");});
});
process.stdin.on("data",chunk=>{try{framing.push(chunk);}catch(error){send({jsonrpc:"2.0",id:null,error:{code:-32700,message:(error as Error).message}});}});
process.stdin.on("end",()=>{void Promise.allSettled([...active]).then(async()=>{if(bridge instanceof LspBridge){await bridge.drained();await bridge.close();}await client?.then(value=>value.close());});});
for(const signal of ["SIGINT","SIGTERM"] as const)process.on(signal,()=>{void client?.then(value=>value.close());process.exit(0);});
