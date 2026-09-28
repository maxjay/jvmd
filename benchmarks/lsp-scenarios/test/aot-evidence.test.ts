import test from "node:test";
import assert from "node:assert/strict";
import path from "node:path";
import os from "node:os";
import {mkdtempSync,mkdirSync,writeFileSync,rmSync} from "node:fs";
import {aotFromLog,captureAot,auditAot} from "../harness/aotEvidence.ts";

test("runtime rejection takes precedence over an earlier archive-open message",()=>{
  assert.equal(aotFromLog('[info][aot] Opened archive a\n[error][aot] Loading static archive failed.').status,"rejected");
  assert.equal(aotFromLog('[info][aot] Mapped static archive').status,"accepted");
  assert.equal(aotFromLog('cache exists').status,"unavailable");
});
test("cache presence cannot prove acceptance and copied raw logs reject forged dispositions",()=>{
  const root=mkdtempSync(path.join(os.tmpdir(),"aot-evidence-"));
  try{
    const runtime=path.join(root,"runtime"),resources=path.join(root,"resources"),image=path.join(root,"image");
    mkdirSync(path.join(runtime,"cache/jvmd"),{recursive:true});mkdirSync(resources);mkdirSync(path.join(image,"lib/jvmd"),{recursive:true});
    writeFileSync(path.join(image,"lib/jvmd/jvmd.aot"),"a file is not acceptance");
    assert.equal(captureAot(runtime,resources,image).status,"unavailable");
    writeFileSync(path.join(runtime,"cache/jvmd/aot.log"),'[error][aot] classpath mismatch');
    const metadata={profile:"product",aot:captureAot(runtime,resources,image)};
    assert.equal(metadata.aot.status,"rejected");auditAot(root,metadata);
    metadata.aot.status="accepted";assert.throws(()=>auditAot(root,metadata),/disposition differs/u);
    metadata.aot.status="rejected";writeFileSync(path.join(resources,"aot.log"),'Opened archive');
    assert.throws(()=>auditAot(root,metadata),/log bytes differ/u);
  }finally{rmSync(root,{recursive:true,force:true});}
});
