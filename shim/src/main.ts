/** Implements 4.1 and 4.8: one client process, with diagnostics written only to stderr. */
import path from "node:path";
import { connect, defaults, Framing } from "./transport.ts";
import { McpBridge } from "./mcp.ts";

const args=process.argv.slice(2);
function option(name:string){const index=args.indexOf(name);if(index<0)return undefined;if(!args[index+1]||args[index+1].startsWith("--"))throw new Error("Missing "+name);return args[index+1];}
const settings=await defaults(),root=path.resolve(option("--root")||process.cwd());
const socket=option("--socket")||settings.socket,launcher=option("--launcher")||settings.launcher;
let client:ReturnType<typeof connect>|undefined;
const bridge=new McpBridge(()=>client??=connect(socket,launcher),root);
if(args.includes("--lsp"))throw new Error("LSP facade is not installed in this checkpoint");
const active=new Set<Promise<void>>();
const framing=new Framing("lines",message=>{
  const request=bridge.handle(message).then(response=>{if(response)process.stdout.write(JSON.stringify(response)+"\n");});
  active.add(request);void request.finally(()=>active.delete(request));
});
process.stdin.on("data",chunk=>{try{framing.push(chunk);}catch(error){process.stdout.write(JSON.stringify({jsonrpc:"2.0",id:null,error:{code:-32700,message:(error as Error).message}})+"\n");}});
process.stdin.on("end",()=>{void Promise.allSettled([...active]).then(()=>client?.then(value=>value.close()));});
for(const signal of ["SIGINT","SIGTERM"] as const)process.on(signal,()=>{void client?.then(value=>value.close());process.exit(0);});
