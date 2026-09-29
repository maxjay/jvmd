import assert from "node:assert/strict";
import path from "node:path";
import {pathToFileURL} from "node:url";
import {mkdirSync,readFileSync,writeFileSync,existsSync} from "node:fs";
import {spawnSync} from "node:child_process";
import {createFixture,inventory,sha,type Fixture} from "./fixture.ts";
import {SETTINGS,type ScenarioContext} from "./ScenarioContext.ts";
import {jdkIdentity} from "./fixture.ts";
import {selected} from "./oracles.ts";

export const buildSource=(name:string,value:string)=>`package bench;\npublic class ${name} { public static int value() { return ${value}; } public static void main(String[] args) { System.out.println(value()); } }\n`;
export const BUILD_SOURCE=buildSource("BuildProbe","7"),BUILD_CHANGED=buildSource("BuildProbe","13"),BUILD_ERROR=buildSource("BuildProbe","missingBuildValue");
export const BUILD_PEER=buildSource("BuildPeer","73"),BUILD_PEER_ERROR=buildSource("BuildPeer","missingScopeValue");
export type BuildVariant="full"|"unchanged"|"changed"|"error";
export type BuildScope="workspace"|"projects";
export function buildParams(scope:BuildScope,root:string,full:boolean){return scope==="workspace"?full:{identifiers:[{uri:pathToFileURL(root).href}],isFullBuild:full};}
export function buildTree(root:string){const out=path.join(root,"bin");return existsSync(out)?inventory(out):{};}
function execute(javaHome:string,tool:string,args:string[],cwd:string){
  const command=[path.join(javaHome,"bin",tool),...args],env={...process.env,JAVA_HOME:javaHome,LC_ALL:"C"};
  for(const key of ["JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS","CLASSPATH"])delete env[key];
  const startNs=String(process.hrtime.bigint()),r=spawnSync(command[0],command.slice(1),{cwd,env,encoding:"utf8",timeout:30000});
  return {command,startNs,endNs:String(process.hrtime.bigint()),status:r.status,signal:r.signal,stdout:r.stdout,stderr:r.stderr,error:String(r.error??"")};
}
export function buildRuntimeOracle(result:any,expected:number){
  assert.equal(result.status,0,"server-built class must execute successfully");assert.equal(result.signal,null);assert.equal(result.error,"");assert.equal(result.stdout,expected+"\n","compiled behaviour is stale or from the wrong project");
}
export function snapshotBuildClass(root:string,name:string,directory:string){
  const source=path.join(root,"bin/bench/"+name+".class");assert(existsSync(source),"server-built class missing: "+source);
  const readStartNs=String(process.hrtime.bigint()),bytes=readFileSync(source),readEndNs=String(process.hrtime.bigint());
  const file=path.join(directory,"bench",name+".class");mkdirSync(path.dirname(file),{recursive:true});writeFileSync(file,bytes,{flag:"wx"});
  return {source,path:file,classpath:directory,sha256:sha(bytes),bytes:bytes.length,readStartNs,readEndNs,
    boundary:"class bytes observed after build response; not proof of their state at response receipt"};
}
export function verifyBuildOutput(c:ScenarioContext,root:string,name:string,expected:number){
  const directory=path.resolve(c.fixture.root,"../build-output-oracle",`${c.operations.length+1}-${c.assertions.length+1}`),snapshot={...snapshotBuildClass(root,name,directory),artifactPath:path.relative(path.resolve(c.fixture.root,".."),path.join(directory,"bench",name+".class"))};
  const result=execute(path.resolve(c.javaHome),"java",["-cp",snapshot.classpath,"bench."+name],directory);
  const unchanged=sha(readFileSync(snapshot.path))===snapshot.sha256;
  c.assertions.push({name:"server-built class has expected behaviour",passed:unchanged&&result.status===0&&result.stdout===expected+"\n",detail:{root,name,expected,operationId:"op-"+(c.operations.length+1),snapshot,result}});
  assert(unchanged,"preserved class snapshot changed during validation");
  buildRuntimeOracle(result,expected);
}
export function buildDiagnosticOracle(params:any,uri:string,source:string,token:string){
  assert.equal(params?.uri,uri);assert(Array.isArray(params.diagnostics));
  const errors=params.diagnostics.filter((d:any)=>d.severity===1||d.severity===undefined);
  assert.equal(errors.length,1,"build must diagnose only its unique missing symbol");
  assert(String(errors[0].message).includes(token),"wrong build diagnostic cause");assert.equal(selected(source,errors[0].range),token,"wrong build diagnostic range");
}
export function prepareBuilds(fixture:Fixture,javaHome:string,variant:BuildVariant){
  const other=createFixture(path.resolve(fixture.root,"../build-peer"),{"BuildPeer.java":BUILD_PEER},"src");
  const project=path.join(other.root,".project");writeFileSync(project,readFileSync(project,"utf8").replace("<name>benchmark</name>","<name>benchmark_peer</name>"));
  fixture.workspaceFolders=[{uri:pathToFileURL(fixture.root).href,name:"benchmark"},{uri:pathToFileURL(other.root).href,name:"benchmark_peer"}];
  fixture.settings=structuredClone(SETTINGS);fixture.settings.java.autobuild.enabled=false;
  if(variant==="error"){writeFileSync(fixture.files["BuildProbe.java"].path,BUILD_ERROR);fixture.files["BuildProbe.java"].text=BUILD_ERROR;}
  const preparation:any={kind:"independent-two-project-build",status:"preparing",variant,autobuild:false,peer:{root:other.root,files:other.files,inputs:inventory(other.root)},
    policy:"fresh projects per case; unchanged/changed establish an explicit full baseline; scope controls follow target timing; output hashes never imply zero compiler work",witnesses:[]};
  fixture.preparation=preparation;const oracle=path.resolve(fixture.root,"../build-oracle");mkdirSync(oracle);
  try{
    preparation.jdk=jdkIdentity(javaHome);
    for(const [label,name,source,value,token] of [["before","BuildProbe",BUILD_SOURCE,7,null],["changed","BuildProbe",BUILD_CHANGED,13,null],["peer","BuildPeer",BUILD_PEER,73,null],["error","BuildProbe",BUILD_ERROR,null,"missingBuildValue"],["peer-error","BuildPeer",BUILD_PEER_ERROR,null,"missingScopeValue"]] as const){
      const root=path.join(oracle,label);mkdirSync(root);const file=path.join(root,name+".java");writeFileSync(file,source);
      const compile=execute(preparation.jdk.home,"javac",["--release","17","-proc:none","-XDrawDiagnostics","-d",path.join(root,"classes"),file],root);
      const witness:any={label,name,source,sourceSha256:sha(source),compile};preparation.witnesses.push(witness);
      assert.equal(compile.signal,null);assert.equal(compile.error,"");assert.equal(compile.status,token?1:0);
      if(token){assert(compile.stderr.includes("compiler.err.cant.resolve")&&compile.stderr.includes(token));assert.equal((compile.stderr.match(/compiler\.err\./gu)??[]).length,1);}
      else{witness.runtime=execute(preparation.jdk.home,"java",["-cp",path.join(root,"classes"),"bench."+name],root);buildRuntimeOracle(witness.runtime,value!);}
    }
    preparation.status="verified";
  }catch(error){preparation.status="failed";preparation.error=String(error);throw error;}
  finally{writeFileSync(path.resolve(fixture.root,"../build-compiler.json"),JSON.stringify(preparation,null,2)+"\n");}
}
