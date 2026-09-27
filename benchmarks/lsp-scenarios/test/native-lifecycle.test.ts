import {test} from "node:test";
import assert from "node:assert/strict";
import {spawn} from "node:child_process";
import {mkdtempSync,readFileSync,rmSync} from "node:fs";
import os from "node:os";
import path from "node:path";
import {NativeDaemon} from "../harness/nativeDaemon.ts";

test("an already failed daemon cannot become a clean lifecycle shutdown",async()=>{
  const directory=mkdtempSync(path.join(os.tmpdir(),"native-shutdown-"));
  const daemon=new NativeDaemon({profile:"direct",image:directory,javaHome:directory,repository:directory,state:path.join(directory,"state"),output:path.join(directory,"output"),trace:false,heapMb:256});
  try{
    daemon.process=spawn(process.execPath,["-e","process.exit(7)"],{stdio:"ignore"});
    daemon.exited=new Promise(resolve=>daemon.process.once("exit",resolve));await daemon.exited;
    await assert.rejects(()=>daemon.close(),/unclean native daemon shutdown/);
    const exit=JSON.parse(readFileSync(path.join(directory,"output/exit.json"),"utf8"));
    assert.equal(exit.code,7);assert.equal(exit.forced,false);
  }finally{rmSync(directory,{recursive:true,force:true});}
});
