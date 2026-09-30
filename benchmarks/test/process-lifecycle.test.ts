import {test} from "node:test";
import assert from "node:assert/strict";
import {mkdtempSync,readFileSync,rmSync} from "node:fs";
import os from "node:os";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {ProtocolClient} from "../harness/ProtocolClient.ts";

const fake=fileURLToPath(new URL("./fake-server.ts",import.meta.url));
for(const mode of ["correct","shutdown-nonzero","shutdown-hang"]){
  test("process journal preserves observed lifecycle for "+mode,async()=>{
    const root=mkdtempSync(path.join(os.tmpdir(),"jvmd-process-"));
    const client=new ProtocolClient([process.execPath,fake,mode],{journalDirectory:root});
    try{
      assert(!((await client.request("initialize",{},5000)).error));
      if(mode==="correct")await client.shutdown(200);
      else await assert.rejects(client.shutdown(200),/unclean shutdown/u);
      const rows=client.processLifecycle,events=rows.map(r=>r.event);
      assert(events.includes("spawned"));assert(events.includes("shutdown_response"));assert(events.includes("exit_notified"));
      const exit=rows.find(r=>r.event==="process_exit");assert(exit,"shutdown returned before process exit was observed");
      assert.equal(events.at(-1),"stdio_closed","shutdown returned before output streams drained");
      if(mode==="shutdown-hang"){assert.equal(exit.signal,"SIGKILL");assert(events.includes("forced_kill"));}
      else{assert.equal(exit.code,mode==="correct"?0:1);assert(!events.includes("forced_kill"));}
      assert.deepEqual(readFileSync(path.join(root,"process.jsonl"),"utf8").trim().split("\n").map(l=>JSON.parse(l)),rows);
      for(let i=1;i<rows.length;i++)assert(BigInt(rows[i].timeNs)>=BigInt(rows[i-1].timeNs));
    }finally{if(client.child.exitCode===null&&client.child.signalCode===null)client.child.kill("SIGKILL");rmSync(root,{recursive:true,force:true});}
  });
}
