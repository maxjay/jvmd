import assert from "node:assert/strict";
import path from "node:path";
import {spawnSync,type ChildProcess} from "node:child_process";
export class LifetimeResources {
  output:string;start:any;result:any;roots:number[]=[];closed=false;
  private script=path.resolve("benchmarks/workspaces/cgroup_resources.py");
  constructor(output:string,parent=process.env.JVMD_BENCH_CGROUP_ROOT){
    this.output=path.resolve(output);this.start=this.call("prepare",...(parent?["--parent",parent]:[]));
  }
  private call(action:string,...args:string[]){
    const result=spawnSync("python3",[this.script,action,"--output",this.output,...args],{encoding:"utf8",timeout:10000,maxBuffer:4*1024*1024});
    assert.equal(result.status,0,"lifetime resource collector failed: "+result.stderr);return JSON.parse(result.stdout);
  }
  command(command:string[],role:"server"|"bridge"){
    return this.start.availability==="ready"?["python3",this.script,"exec","--output",this.output,"--role",role,"--",...command]:command;
  }
  track(child:ChildProcess){if(child.pid)this.roots.push(child.pid);}
  finish(){if(!this.closed){this.closed=true;this.result=this.call("finish","--pids",JSON.stringify(this.roots));}return this.result;}
}
