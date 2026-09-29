import {test} from "node:test";
import assert from "node:assert/strict";
import {mkdtempSync,mkdirSync,writeFileSync,rmSync} from "node:fs";
import path from "node:path";
import os from "node:os";
import {pathToFileURL} from "node:url";
import {ScenarioContext,SETTINGS} from "../harness/ScenarioContext.ts";
import {createFixture} from "../harness/fixture.ts";
import {GENERATED_ROOTS,PROTO_USE} from "../harness/protobuf.ts";
import {protobufCases} from "../scenarios/protobuf.ts";

test("protobuf command success without generated types fails the actual case",async()=>{
  const root=mkdtempSync(path.join(os.tmpdir(),"protobuf-noop-"));
  try{
    const fixture=createFixture(root,{"ProtoUse.java":PROTO_USE},"src/main/java");
    for(const dir of GENERATED_ROOTS)mkdirSync(path.join(root,dir),{recursive:true});
    writeFileSync(path.join(root,".project"),"<projectDescription><name>renamed-project</name><natures><nature>org.eclipse.buildship.core.gradleprojectnature</nature></natures></projectDescription>");
    const commands:any[]=[];
    const client={events:[],exchanges:[],notify:()=>process.hrtime.bigint(),journal:()=>{},request:async(method:string,params:any)=>{
      commands.push(params);const id=commands.length,time=String(process.hrtime.bigint());
      return {id,startNs:time,endNs:time,result:params.command==="java.project.getAll"?[pathToFileURL(root).href]:null};
    }} as any;
    const c=new ScenarioContext(client,fixture,"jdtls",100,1,1);
    await assert.rejects(()=>protobufCases[0].run(c),/exactly their declared Java outputs/u);
    assert.deepEqual(commands.at(-1).arguments,[["renamed-project"]]);
    assert.equal(c.operations.at(-1).outcome,"pass","successful command is retained independently from failed generated-type assertion");
    assert(c.assertions.some(a=>a.passed===false));
  }finally{rmSync(root,{recursive:true,force:true});}
});

test("per-fixture settings agree across initialize, notification and configuration requests",async()=>{
  const root=mkdtempSync(path.join(os.tmpdir(),"protobuf-settings-"));
  try{
    const fixture=createFixture(root);fixture.settings=structuredClone(SETTINGS);
    fixture.settings.java.import.gradle={enabled:true,offline:{enabled:true},home:"/pinned/gradle"};
    const calls:any[]=[],client={request:async(method:string,params:any)=>{calls.push({method,params});return {result:{capabilities:{}},endNs:"1"};},notify:(method:string,params:any)=>calls.push({method,params})} as any;
    const c=new ScenarioContext(client,fixture,"jvmd",100,1,1);await c.initialize();
    const values=await client.onServerRequest("workspace/configuration",{items:[{section:"java.import.gradle"},{section:"java.import.maven"}]});
    assert.deepEqual(values,[fixture.settings.java.import.gradle,{enabled:false}]);
    assert.deepEqual(calls[0].params.initializationOptions.settings,fixture.settings);
    assert.deepEqual(calls[2].params.settings,fixture.settings);
    assert.equal(SETTINGS.java.import.gradle.enabled,false,"case-specific import must not leak into other cases");
  }finally{rmSync(root,{recursive:true,force:true});}
});
