import {test} from "node:test";
import assert from "node:assert/strict";
import {mkdtempSync,mkdirSync,readFileSync,writeFileSync,rmSync,existsSync} from "node:fs";
import {spawnSync} from "node:child_process";
import {createHash} from "node:crypto";
import os from "node:os";
import path from "node:path";
import {fileURLToPath} from "node:url";

const script=fileURLToPath(new URL("../package_evidence.py",import.meta.url));
function fixture(){
  const tmp=mkdtempSync(path.join(os.tmpdir(),"jvmd-review-")),root=path.join(tmp,"bundle");mkdirSync(root);
  const files=["manifest.json","catalogue.json","required-variants.json","summary.json","variants.json","cases.jsonl"],bytes="{}\n";
  for(const name of files)writeFileSync(path.join(root,name),bytes);
  const seal=files.map(name=>createHash("sha256").update(bytes).digest("hex")+"  "+name).join("\n")+"\n";
  writeFileSync(path.join(root,"checksums.sha256"),seal);return {tmp,root,seal};
}
test("review packaging refuses unsealed files by default and preserves them only with an explicit failed inventory",()=>{
  const {tmp,root,seal}=fixture();
  try{
    const good=spawnSync("python3",[script,root,path.join(tmp,"good.tar.xz")],{encoding:"utf8"});assert.equal(good.status,0,good.stderr);
    assert.equal(JSON.parse(good.stdout).originalCompleteInventory,"verified");
    writeFileSync(path.join(root,"late-runtime.txt"),"");
    const rejected=spawnSync("python3",[script,root,path.join(tmp,"rejected.tar.xz")],{encoding:"utf8"});
    assert.notEqual(rejected.status,0);assert.match(rejected.stderr,/checksum inventory is incomplete/u);assert(!existsSync(path.join(tmp,"rejected.tar.xz")));
    const kept=spawnSync("python3",[script,root,path.join(tmp,"failed-review.tar.xz"),"--allow-unsealed-files"],{encoding:"utf8"});assert.equal(kept.status,0,kept.stderr);
    const result=JSON.parse(kept.stdout);assert.equal(result.originalCompleteInventory,"failed");assert.equal(result.unsealedFiles["late-runtime.txt"].bytes,0);
    assert.equal(readFileSync(path.join(root,"checksums.sha256"),"utf8"),seal,"packager rewrote the original seal");
    const review=spawnSync("python3",["-c","import sys,tarfile; print(tarfile.open(sys.argv[1]).extractfile('review-manifest.json').read().decode())",path.join(tmp,"failed-review.tar.xz")],{encoding:"utf8"});
    const manifest=JSON.parse(review.stdout);assert.equal(manifest.sourceInventoryComplete,false);assert(manifest.files["late-runtime.txt"]);
  }finally{rmSync(tmp,{recursive:true,force:true});}
});
test("allowing unsealed files never excuses a changed originally sealed payload",()=>{
  const {tmp,root}=fixture();
  try{
    writeFileSync(path.join(root,"manifest.json"),'{"tampered":true}\n');
    const output=path.join(tmp,"bad.tar.xz"),run=spawnSync("python3",[script,root,output,"--allow-unsealed-files"],{encoding:"utf8"});
    assert.notEqual(run.status,0);assert.match(run.stderr,/source hash mismatch/u);assert(!existsSync(output));
  }finally{rmSync(tmp,{recursive:true,force:true});}
});
test("interrupted capture snapshots remain explicitly unsealed and do not create missing reports",()=>{
  const {tmp,root}=fixture();
  try{
    for(const name of ["checksums.sha256","summary.json","variants.json","cases.jsonl"])rmSync(path.join(root,name));
    const output=path.join(tmp,"interrupted.tar.xz");
    const rejected=spawnSync("python3",[script,root,output],{encoding:"utf8"});assert.notEqual(rejected.status,0);assert(!existsSync(output));
    const kept=spawnSync("python3",[script,root,output,"--allow-interrupted-bundle"],{encoding:"utf8"});assert.equal(kept.status,0,kept.stderr);
    assert.equal(JSON.parse(kept.stdout).originalCompleteInventory,"unavailable: interrupted before sealing");
    assert(!existsSync(path.join(root,"checksums.sha256")));assert(!existsSync(path.join(root,"summary.json")));
    const read=spawnSync("python3",["-c","import sys,tarfile; print(tarfile.open(sys.argv[1]).extractfile('review-manifest.json').read().decode())",output],{encoding:"utf8"});
    const manifest=JSON.parse(read.stdout);assert.equal(manifest.originalSealAbsent,true);assert.equal(manifest.sourceInventoryComplete,false);
  }finally{rmSync(tmp,{recursive:true,force:true});}
});
