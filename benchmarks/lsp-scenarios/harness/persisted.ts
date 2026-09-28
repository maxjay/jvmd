import assert from "node:assert/strict";
import path from "node:path";
import {existsSync,statSync} from "node:fs";
import {inventory} from "./fixture.ts";
import {SOURCES} from "./fixture.ts";
import {hoverOracle} from "./oracles.ts";
export function persistedDirectory(runtime:string,server:string,profile:string){return path.join(runtime,server==="jdtls"?"workspace":profile==="product"?"cache/jvmd":"store");}
export function persistedSnapshot(directory:string){
  assert(existsSync(directory)&&statSync(directory).isDirectory(),"server created no persisted state directory");
  const startNs=String(process.hrtime.bigint()),files=inventory(directory);assert(Object.keys(files).length,"persisted state is empty");
  return {directory,files,startNs,endNs:String(process.hrtime.bigint()),bytes:Object.keys(files).reduce((n,p)=>n+statSync(path.join(directory,p)).size,0)};
}
export function persistedStateOracle(evidence:any){
  assert(evidence?.seedStopped===true,"seed process did not stop cleanly");
  assert(evidence.afterSeed&&evidence.beforeReopen,"persisted snapshots missing");
  assert.equal(evidence.afterSeed.directory,evidence.beforeReopen.directory,"reopen changed state directory");
  assert(Object.keys(evidence.afterSeed.files).length&&Number.isInteger(evidence.afterSeed.bytes)&&evidence.afterSeed.bytes>0,"persisted state lacks bytes");
  for(const hash of Object.values(evidence.afterSeed.files))assert(typeof hash==="string"&&/^[a-f0-9]{64}$/u.test(hash),"invalid persisted hash");
  for(const snapshot of [evidence.afterSeed,evidence.beforeReopen])assert(BigInt(snapshot.endNs)>=BigInt(snapshot.startNs),"negative snapshot interval");
  assert.deepEqual(evidence.afterSeed.files,evidence.beforeReopen.files,"persisted files changed before reopen");
  assert.equal(evidence.afterSeed.bytes,evidence.beforeReopen.bytes);
  assert(BigInt(evidence.beforeReopen.startNs)>=BigInt(evidence.afterSeed.endNs));
  assert(BigInt(evidence.reopenStartedNs)>=BigInt(evidence.beforeReopen.endNs));
}
export function persistedSessionOracle(report:any,seed:any,seedEvents:any[],events:any[]){
  persistedStateOracle(report.persistedEvidence);assert.equal(seed.outcome,"pass");assert.equal(seed.finalized,true);
  assert.equal(seed.launch.machineState,"empty");assert.equal(report.launch.machineState,"persisted");
  assert.equal(seed.launch.stateDirectory,report.launch.stateDirectory);assert.equal(seed.launch.workspaceRoot,report.launch.workspaceRoot);
  assert.equal(report.persistedEvidence.afterSeed.directory,persistedDirectory(seed.launch.stateDirectory,report.server,seed.launch.profile));
  const stopped=seed.processLifecycle.at(-1);assert.equal(stopped.event,"stdio_closed");assert.equal(stopped.code,0);
  assert(seed.processLifecycle.some((e:any)=>e.event==="process_exit"&&e.code===0&&!e.signal));
  assert(!seed.processLifecycle.some((e:any)=>["forced_kill","shutdown_deadline","process_error"].includes(e.event)));
  assert(BigInt(report.persistedEvidence.afterSeed.startNs)>=BigInt(stopped.timeNs));
  assert(BigInt(report.launch.launchStartedNs)>=BigInt(report.persistedEvidence.reopenStartedNs));
  const sent=(rows:any[],method:string)=>rows.filter(e=>e.direction==="send"&&e.message.method===method);
  const seedOpen=sent(seedEvents,"textDocument/didOpen"),opened=sent(events,"textDocument/didOpen");assert.equal(seedOpen.length,1);assert.equal(opened.length,1);
  const saved=SOURCES["Customer.java"],overlay=saved.replace('public String label = "Ada";','public int label = 7;').replace('return label;','return String.valueOf(label);');
  assert.equal(seedOpen[0].message.params.textDocument.text,saved);assert.equal(opened[0].message.params.textDocument.text,saved);
  assert.equal(opened[0].message.params.textDocument.uri,seedOpen[0].message.params.textDocument.uri);
  const changes=sent(seedEvents,"textDocument/didChange");assert.equal(changes.length,1);assert.deepEqual(changes[0].message.params.contentChanges,[{text:overlay}]);
  assert.equal(sent(seedEvents,"textDocument/didSave").length,0);assert.equal(sent(events,"textDocument/didChange").length,0);
  assert.equal(sent(seedEvents,"textDocument/didClose").length,1);
  const initialize=sent(events,"initialize");assert.equal(initialize.length,1);assert(BigInt(initialize[0].timeNs)>BigInt(stopped.timeNs));
  for(const [rows,state,type] of [[seed.operations,"saved_baseline","String"],[seed.operations,"changed_settled","int"],[report.operations,"after_reopen","String"]] as const){
    const ops=rows.filter((o:any)=>o.method==="textDocument/hover"&&o.state===state);assert.equal(ops.length,1);assert.equal(ops[0].outcome,"pass");hoverOracle(ops[0].rawResult,"label",type);
  }
}
