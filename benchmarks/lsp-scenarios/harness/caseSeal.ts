import assert from "node:assert/strict";
import path from "node:path";
import {readFileSync,writeFileSync} from "node:fs";
import {inventory,sha} from "./fixture.ts";

const identity=(manifest:any,report:any)=>({revision:manifest.revision,sourceInputs:manifest.sourceInputs,
  plan:manifest.plan,caseId:report.caseId,server:report.server,block:report.block});

/** Seal before starting the next case; the suite still requires its final seal. */
export function sealCase(directory:string,manifest:any,report:any){
  assert.equal(report.finalized,true,"only finalized cases can be sealed");
  writeFileSync(path.join(directory,"case-seal.json"),JSON.stringify({schemaVersion:1,
    identitySha256:sha(JSON.stringify(identity(manifest,report))),files:inventory(directory)},null,2)+"\n");
}

export function auditCaseSeal(directory:string,manifest:any,report:any){
  const seal=JSON.parse(readFileSync(path.join(directory,"case-seal.json"),"utf8"));
  assert.equal(seal.schemaVersion,1,"unknown case seal schema");
  assert.equal(report.finalized,true,"sealed case is not finalized");
  assert.equal(seal.identitySha256,sha(JSON.stringify(identity(manifest,report))),"case seal identity differs");
  const actual=inventory(directory);delete actual["case-seal.json"];
  assert.deepEqual(actual,seal.files,"case bytes differ from finalization checkpoint");
}
