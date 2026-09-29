import assert from "node:assert/strict";
import path from "node:path";
import {mkdirSync,readFileSync,writeFileSync} from "node:fs";
import {spawnSync} from "node:child_process";
import {type Fixture,sha,jdkIdentity} from "./fixture.ts";
import {type PreparationOptions} from "./ScenarioContext.ts";
import {DIAGNOSTIC_SOURCES,DIAGNOSTIC_CHANGED_PROVIDER,diagnosticPolicy} from "./diagnostics.ts";

export function diagnosticCompilerOracle(witness:any){
  for(const stage of ["before","after"]){assert.equal(witness[stage]?.signal,null);assert.equal(witness[stage]?.error,"");}
  assert.equal(witness.before.status,0,"baseline diagnostic sources must compile");
  assert.equal(witness.after.status,1,"changed provider must make the unchanged caller invalid");
  assert.match(witness.after.stderr,/^DiagnosticCaller\.java:2:89: compiler\.err\.prob\.found\.req: \(compiler\.misc\.inconvertible\.types: java\.lang\.String, int\)$/mu);
  assert.equal((witness.after.stderr.match(/compiler\.err\./gu)??[]).length,1,"unrelated compiler errors cannot prove provider freshness");
}
export function prepareDiagnostics(fixture:Fixture,javaHome:string,options:PreparationOptions={}){
  assert(Number.isInteger(options.timeoutMs)&&options.timeoutMs!>0,"diagnostic timeout must be declared before launch");
  const preparation:any={kind:"diagnostic-provider-witness",status:"preparing",diagnosticPolicy:diagnosticPolicy(options.timeoutMs!),
    timingScope:"independent before/after compiler witnesses before server launch; only notifications between document trigger and diagnostic admission; OS caches uncontrolled"};
  fixture.preparation=preparation;
  const root=path.resolve(fixture.root,"../diagnostic-oracle");mkdirSync(root,{recursive:true});
  try{
    preparation.jdk=jdkIdentity(javaHome);preparation.witness={};
    for(const [name,text] of Object.entries(DIAGNOSTIC_SOURCES))assert.equal(readFileSync(fixture.files[name].path,"utf8"),text,"compiler and LSP source differ");
    for(const stage of ["before","after"]){
      const directory=path.join(root,stage);mkdirSync(directory);
      const sources={...DIAGNOSTIC_SOURCES,...(stage==="after"?{"DiagnosticProvider.java":DIAGNOSTIC_CHANGED_PROVIDER}:{})};
      for(const [name,text] of Object.entries(sources))writeFileSync(path.join(directory,name),text);
      const command=[path.join(preparation.jdk.home,"bin/javac"),"--release","17","-proc:none","-XDrawDiagnostics","-d",path.join(directory,"classes"),...Object.keys(sources).map(n=>path.join(directory,n))];
      const env={...process.env,JAVA_HOME:preparation.jdk.home,LC_ALL:"C"};
      for(const key of ["JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS","CLASSPATH"])delete env[key];
      const startNs=String(process.hrtime.bigint()),result=spawnSync(command[0],command.slice(1),{cwd:directory,env,encoding:"utf8",timeout:30000});
      preparation.witness[stage]={command,sourceHashes:Object.fromEntries(Object.entries(sources).map(([n,t])=>[n,sha(t)])),startNs,endNs:String(process.hrtime.bigint()),status:result.status,signal:result.signal,stdout:result.stdout,stderr:result.stderr,error:String(result.error??"")};
    }
    diagnosticCompilerOracle(preparation.witness);preparation.status="verified";
  }catch(error){preparation.status="failed";preparation.error=String(error);throw error;}
  finally{writeFileSync(path.resolve(fixture.root,"../diagnostic-compiler.json"),JSON.stringify(preparation,null,2)+"\n");}
}
