/** Implements 4.8: MCP stdio is a thin adapter over the daemon's frozen Java tool catalog. */
import type { Message, RpcClient } from "./transport.ts";

export class McpBridge {
  private session:Promise<string>|undefined;
  private initialized=false;
  constructor(client:()=>Promise<RpcClient>,root:string){this.client=client;this.root=root;}
  client:()=>Promise<RpcClient>;
  root:string;
  private workspace(){
    this.session??=this.client().then(client=>client.call("session.open",{root:this.root})).then(envelope=>{
      const session=envelope.result?.session;if(!session)throw new Error("Workspace did not open");return session;
    });
    return this.session;
  }
  async handle(message:Message):Promise<Message|undefined>{
    const id=message.id??null;
    if(message.jsonrpc!=="2.0"||typeof message.method!=="string"||(message.params!==undefined&&(message.params===null||typeof message.params!=="object"||Array.isArray(message.params))))
      return {jsonrpc:"2.0",id,error:{code:-32600,message:"Invalid Request"}};
    if(message.id===undefined)return undefined;
    try{
      const params=message.params||{};let result:any;
      if(message.method==="initialize"){
        const supported=["2024-11-05","2025-03-26","2025-06-18","2025-11-25"];
        this.initialized=true;result={protocolVersion:supported.includes(params.protocolVersion)?params.protocolVersion:"2025-11-25",capabilities:{tools:{listChanged:false}},serverInfo:{name:"jvmd",version:"0.1.0"},instructions:"Use diagnostics with verified=true before declaring an edit complete. Continue truncated results with the same tool arguments and returned cursor."};
      }else if(message.method==="ping")result={};
      else{
        if(!this.initialized)return {jsonrpc:"2.0",id,error:{code:-32000,message:"Initialize the MCP connection first"}};
        if(!["tools/list","tools/call"].includes(message.method))return {jsonrpc:"2.0",id,error:{code:-32601,message:"Method not found"}};
        const client=await this.client(),session=await this.workspace();
        if(message.method==="tools/list"){
          if(params.cursor)return {jsonrpc:"2.0",id,error:{code:-32602,message:"Invalid tool-list cursor"}};
          const response=await client.call("mcp.tools",{session});result={tools:response.result.catalog.map((entry:any)=>entry.tool)};
        }else{
          const response=await client.raw("mcp.invoke",{session,name:params.name,arguments:params.arguments||{}});
          const envelope=response.error?{...response.error.data,error:{code:response.error.code,message:response.error.message}}:response.result;
          result={content:[{type:"text",text:JSON.stringify(envelope)}],isError:Boolean(response.error)};
          if(Buffer.byteLength(JSON.stringify(result))>65536)throw new Error("Daemon exceeded the MCP response budget");
        }
      }
      return {jsonrpc:"2.0",id,result};
    }catch(error){
      const failure=error as Error & {rpc?:any};
      return {jsonrpc:"2.0",id,error:{code:failure.rpc?.code||-32603,message:failure.message,data:failure.rpc?.data}};
    }
  }
}
