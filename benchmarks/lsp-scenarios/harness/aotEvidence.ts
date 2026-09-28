import assert from "node:assert/strict";
import path from "node:path";
import {readFileSync,writeFileSync,existsSync} from "node:fs";
import {sha} from "./fixture.ts";

/** Mirrors the production AotStatus log contract; cache-file presence is not use. */
export function aotFromLog(log:string){
  const lines=log.split(/\r?\n/u);
  const rejection=lines.find(line=>/unable|cannot|failed|mismatch|does not match/iu.test(line));
  if(rejection)return {status:"rejected",reason:rejection};
  const witness=lines.find(line=>line.includes("Opened archive")||line.includes("Mapped static"));
  if(witness)return {status:"accepted",witness};
  return {status:"unavailable",reason:"AOT log does not confirm archive mapping or a rejection"};
}
export function captureAot(runtime:string,resourceDirectory:string,image:string){
  const file=path.join(runtime,"cache/jvmd/aot.log"),requestedCache=path.join(image,"lib/jvmd/jvmd.aot");
  const common={requested:true,requestedCache,cachePresent:existsSync(requestedCache),observation:"launcher log copied after measured requests and daemon termination; no warming status call"};
  if(!existsSync(file))return {...common,status:"unavailable",reason:"shipped launcher AOT log absent"};
  const bytes=readFileSync(file);writeFileSync(path.join(resourceDirectory,"aot.log"),bytes);
  return {...common,...aotFromLog(bytes.toString("utf8")),logFile:"resources/aot.log",logSha256:sha(bytes)};
}
export function auditAot(directory:string,metadata:any){
  if(!metadata.aot?.logFile){
    assert(!["accepted","rejected"].includes(metadata.aot?.status),"actual AOT disposition requires preserved launcher log");
    return;
  }
  assert.equal(metadata.profile,"product","runtime AOT evidence requires shipped product launcher");
  assert.equal(metadata.aot.logFile,"resources/aot.log","unexpected AOT log path");
  const bytes=readFileSync(path.join(directory,metadata.aot.logFile));
  assert.equal(sha(bytes),metadata.aot.logSha256,"AOT log bytes differ from observation");
  const expected=aotFromLog(bytes.toString("utf8"));
  for(const key of ["status","reason","witness"])assert.deepEqual(metadata.aot[key],expected[key],"AOT disposition differs from launcher log");
}
