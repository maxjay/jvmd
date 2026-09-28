import assert from "node:assert/strict";
import path from "node:path";
import {spawnSync,type ChildProcess} from "node:child_process";
import {fileURLToPath} from "node:url";
import {readFileSync} from "node:fs";
const script=fileURLToPath(new URL("../../workspaces/cgroup_resources.py",import.meta.url));
export class LifetimeResources {
  output:string;start:any;result:any;roots:number[]=[];closed=false;
  constructor(output:string,parent=process.env.JVMD_BENCH_CGROUP_ROOT){
    this.output=path.resolve(output);this.start=this.call("prepare",...(parent?["--parent",parent]:[]));
  }
  private call(action:string,...args:string[]){
    const result=spawnSync("python3",[script,action,"--output",this.output,...args],{encoding:"utf8",timeout:10000,maxBuffer:4*1024*1024});
    assert.equal(result.status,0,"lifetime resource collector failed: "+result.stderr);return JSON.parse(result.stdout);
  }
  command(command:string[],role:"server"|"bridge"){
    return this.start.availability==="ready"?["python3",script,"exec","--output",this.output,"--role",role,"--",...command]:command;
  }
  track(child:ChildProcess){if(child.pid)this.roots.push(child.pid);}
  finish(){if(!this.closed){this.closed=true;this.result=this.call("finish","--pids",JSON.stringify(this.roots));}return this.result;}
}
export function auditLifetimeResources(directory:string,metadata:any,processLifecycle:any[]){
  const read=(name:string)=>JSON.parse(readFileSync(path.join(directory,"resources",name),"utf8"));
  assert.deepEqual(metadata.lifetimeResources.start,read("lifetime-start.json"),"launch resource owner differs from capture");
  assert.deepEqual(metadata.lifetimeResources.result,read("lifetime-resources.json"),"launch resource result differs from capture");
  const roots=[...(metadata.daemonProcessLifecycle??[]),...processLifecycle].filter(e=>e.event==="spawned").map(e=>e.pid);
  const run=spawnSync("python3",[script,"audit","--output",path.join(directory,"resources"),"--pids",JSON.stringify(roots)],{encoding:"utf8",timeout:10000,maxBuffer:4*1024*1024});
  assert.equal(run.status,0,"lifetime resource audit failed: "+run.stderr);return JSON.parse(run.stdout);
}
