import {test} from "node:test";
import assert from "node:assert/strict";
import {mkdtempSync,rmSync,mkdirSync,symlinkSync} from "node:fs";
import path from "node:path";
import os from "node:os";
import {createFixture} from "../harness/fixture.ts";
import {ScenarioContext} from "../harness/ScenarioContext.ts";
import {JDK_PROBE,VM_LOCATION,jdkUpdateOracle,vmInventoryOracle,compilerWitnessOracle,prepareJdkSwitch} from "../harness/jdkSwitch.ts";
import {projectCases} from "../scenarios/projects.ts";

// Synthetic compiler records are used only to exercise the scenario's control
// flow. Real distributions and compiler outputs belong to the saved pilot.
const result={status:0,signal:null,error:"",stdout:"",stderr:""};
const witness={old:{...result,status:1,stderr:"JdkProbe.java:4:65: compiler.err.cant.resolve.location.args: kindname.method, getFirst, , , (compiler.misc.location.1: kindname.variable, values, java.util.List<java.lang.String>)\n1 error\n"},new:result,runtime:{...result,stdout:"first=one\n"}};
test("JDK update rejects successful-looking failure objects and wrong homes",()=>{
  jdkUpdateOracle({success:true,message:"/jdk/new"},"/jdk/new");
  for(const wrong of [false,null,{}, {success:false,message:"/jdk/new"},{success:true,message:"/jdk/old"}])assert.throws(()=>jdkUpdateOracle(wrong,"/jdk/new"));
});
test("VM inventory requires actual versioned path entries, not a matching substring",()=>{
  vmInventoryOracle([{path:"/jdk/old",version:"17"},{path:"/jdk/new",version:"25"}],["/jdk/old","/jdk/new"]);
  for(const value of [{path:"/jdk/new",version:"25"},[{path:"/jdk/newer",version:"25"}],[{name:"/jdk/new",path:"/other",version:"25"}],[{path:"/jdk/new"}]])assert.throws(()=>vmInventoryOracle(value,["/jdk/new"]));
});
test("JDK identity accepts a symlink to the selected installation but rejects another directory",()=>{
  const root=mkdtempSync(path.join(os.tmpdir(),"jdk-alias-"));
  try{
    const actual=path.join(root,"actual"),alias=path.join(root,"alias"),other=path.join(root,"other");
    mkdirSync(actual);mkdirSync(other);symlinkSync(actual,alias,"dir");
    vmInventoryOracle([{path:actual,version:"25"}],[alias]);jdkUpdateOracle({success:true,message:actual},alias);
    assert.throws(()=>vmInventoryOracle([{path:other,version:"25"}],[alias]),/absent/u);
    assert.throws(()=>jdkUpdateOracle({success:true,message:other},alias),/another home/u);
  }finally{rmSync(root,{recursive:true,force:true});}
});
test("compiler witness rejects arbitrary old failure, extra errors, new failure and wrong behaviour",()=>{
  compilerWitnessOracle(witness);
  for(const patch of [{old:{...witness.old,stderr:"javac: command not found"}},{old:{...witness.old,stderr:witness.old.stderr+"compiler.err.unrelated"}},
    {old:{...witness.old,signal:"SIGTERM"}},{old:{...witness.old,status:0}},{new:{...result,status:1}},{runtime:{...result,stdout:"first=two\n"}}])
    assert.throws(()=>compilerWitnessOracle({...witness,...patch}));
});
test("missing alternate JDK is a preparation error, not unsupported server capability",()=>{
  assert.throws(()=>prepareJdkSwitch({} as any,"/jdk/new"),/requires --alternate-java-home/u);
});
for(const mode of ["correct","stale-api-once","false-ack","wrong-environment","empty-baseline"])test("JDK-switch scenario rejects concealed platform failure: "+mode,async()=>{
  const root=mkdtempSync(path.join(os.tmpdir(),"jdk-switch-"));
  try{
    const definition=projectCases.find(c=>c.id==="PRJ-02/jdk-switch")!,fixture=createFixture(root,{"JdkProbe.java":JDK_PROBE});
    fixture.preparation={status:"verified",jdks:{old:{home:"/jdk/old"},new:{home:"/jdk/new"}},witness};
    let selected="/jdk/old",missed=false;const requests:any[]=[];
    const client={events:[],journal:()=>{},notify:()=>process.hrtime.bigint(),request:async(method:string,params:any)=>{
      requests.push({method,params});let value:any;
      if(method==="workspace/executeCommand"){
        if(params.command==="java.vm.getAllInstalls")value=[{path:"/jdk/old",version:"17"},{path:"/jdk/new",version:"25"}];
        if(params.command==="java.project.updateJdk"){selected=params.arguments[1];value={success:!(mode==="false-ack"&&selected==="/jdk/new"),message:selected};}
        if(params.command==="java.project.getSettings")value=Object.fromEntries(params.arguments[1].map((key:string)=>[key,key===VM_LOCATION?mode==="wrong-environment"?"/jdk/old":selected:key.endsWith(".release")?"disabled":"17"]));
      }else{
        let labels=mode==="empty-baseline"?[]:["get"];
        if(selected==="/jdk/new"){
          if(mode==="stale-api-once"&&!missed)missed=true;else labels.push("getFirst");
        }
        value={items:labels.map(label=>({label})),isIncomplete:false};
      }
      const time=String(process.hrtime.bigint());return {id:requests.length,startNs:time,endNs:time,result:value};
    }} as any;
    const c=new ScenarioContext(client,fixture,"jdtls",100,1,2);c.javaHome="/jdk/new";
    if(["false-ack","wrong-environment","empty-baseline"].includes(mode))await assert.rejects(()=>definition.run(c));else await definition.run(c);
    const changed=c.operations.find(o=>o.state==="jdk_change");
    if(mode==="empty-baseline"){assert.equal(changed,undefined);assert.equal(c.operations.at(-1).outcome,"incorrect");return;}
    assert(changed);
    if(mode==="false-ack"){assert.equal(changed.outcome,"incorrect");assert.equal(c.mutations.length,0);return;}
    const immediate=c.operations[c.operations.indexOf(changed)+1];
    assert.equal(immediate.method,"textDocument/completion","metadata warmed the server before the first API probe");
    assert.equal(immediate.state,"changed_immediate");assert.equal(immediate.triggerNs,changed.startNs);
    if(mode==="stale-api-once"){
      assert.equal(immediate.outcome,"stale");
      assert(c.operations.some(o=>/^changed_/u.test(o.state)&&o.outcome==="pass"));
    }else if(mode==="wrong-environment")assert.equal(c.operations.at(-1).outcome,"incorrect");
    else assert(c.operations.every(o=>o.outcome==="pass"));
    assert.equal(c.documents.get(fixture.files["JdkProbe.java"].uri)?.version,1);
  }finally{rmSync(root,{recursive:true,force:true});}
});
