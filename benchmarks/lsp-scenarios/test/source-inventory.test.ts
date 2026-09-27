import {test} from "node:test";
import assert from "node:assert/strict";
import {mkdtempSync,writeFileSync,rmSync} from "node:fs";
import {spawnSync} from "node:child_process";
import os from "node:os";
import path from "node:path";
import {sourceInventory} from "../harness/sourceInventory.ts";

test("source snapshots detect added, changed and removed files with exact paths",()=>{
  const root=mkdtempSync(path.join(os.tmpdir(),"jvmd-inputs-"));
  try{
    assert.equal(spawnSync("git",["init","-q",root]).status,0);
    writeFileSync(path.join(root,"tracked.ts"),"before");
    assert.equal(spawnSync("git",["-C",root,"add","tracked.ts"]).status,0);
    const initial=sourceInventory(root);
    const added="new source\nfile.ts";writeFileSync(path.join(root,added),"added");
    const withAdded=sourceInventory(root);assert(added in withAdded);assert.notDeepEqual(withAdded,initial);
    rmSync(path.join(root,added));assert.deepEqual(sourceInventory(root),initial);
    writeFileSync(path.join(root,"tracked.ts"),"changed");assert.notDeepEqual(sourceInventory(root),initial);
    rmSync(path.join(root,"tracked.ts"));assert.deepEqual(sourceInventory(root),{});
  }finally{rmSync(root,{recursive:true,force:true});}
});
