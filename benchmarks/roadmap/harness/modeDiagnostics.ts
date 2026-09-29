import assert from "node:assert/strict";
import path from "node:path";
import {mkdirSync,readFileSync,writeFileSync} from "node:fs";
import {spawnSync} from "node:child_process";
import {type Fixture,sha} from "./fixture.ts";
import {jdkIdentity} from "./fixture.ts";
import {addSource} from "./projectScope.ts";
import {offset} from "./oracles.ts";

export const LOOSE_VALID="package bench; public class LooseMode { public int value() { return 7; } }\n";
export const LOOSE_SEMANTIC=LOOSE_VALID.replace("return 7;","return uniqueModeMissing;");
export const LOOSE_SYNTAX=LOOSE_VALID.replace("return 7;","return 1 + ;");
export const COMPILER_SOURCE="package bench; public record CompilerMode(int value) {}\n";
export const modeKinds=["loose_suppressed","loose_semantic","loose_syntax","compiler_valid","compiler_rejected"] as const;
export type ModeKind=typeof modeKinds[number];
export const isModeKind=(kind:string):kind is ModeKind=>(modeKinds as readonly string[]).includes(kind);
export const modeSource=(kind:ModeKind)=>kind==="loose_syntax"?LOOSE_SYNTAX:kind.startsWith("loose_")?LOOSE_SEMANTIC:COMPILER_SOURCE;
export const modeDiagnosticPolicy=(timeoutMs:number)=>({deadlineMs:timeoutMs,maxPublications:1000,clock:"client monotonic",start:"recorded document or compiler-setting trigger",end:"first semantically current publication",admission:"exact document version/incarnation; compiler rejection also requires positive changed-language witness",retries:"none: observe notifications without readiness polling",terminalFailures:"wrong current errors remain failures; versionless exhaustion remains unavailable"});
export function modeDiagnosticOracle(kind:ModeKind,source:string,errors:any[]){
  assert.equal(source,modeSource(kind),"diagnostic source differs from independent mode witness");
  if(kind==="compiler_valid"||kind==="loose_suppressed"){assert.equal(errors.length,0,"current source has errors excluded by its declared mode");return "pass";}
  // The source version does not change when the compiler setting changes.
  if(kind==="compiler_rejected"&&!errors.length)return "pending_provider_generation";
  assert(errors.length>0,"expected a positive diagnostic in the selected mode");
  const token=kind==="loose_semantic"?"uniqueModeMissing":kind==="loose_syntax"?"1 + ;":"public record CompilerMode(int value)";
  const first=source.indexOf(token),last=first+token.length;
  for(const d of errors){const a=offset(source,d.range.start),b=offset(source,d.range.end);assert(a>=first&&b<=last&&b>a,"mode error is outside its independently selected construct");}
  if(kind==="loose_semantic"){assert.equal(errors.length,1);assert.equal(offset(source,errors[0].range.start),first);assert.equal(offset(source,errors[0].range.end),last);assert(errors[0].message.includes("uniqueModeMissing"));}
  if(kind==="compiler_rejected")assert(errors.some(d=>/record|source|compliance|16/iu.test(d.message)),"missing changed-language diagnostic");
  return "pass";
}
export function modeCompilerOracle(witness:any,kind:"loose"|"compiler"){
  const expected=kind==="loose"?{valid:[LOOSE_VALID,0],semantic:[LOOSE_SEMANTIC,1],syntax:[LOOSE_SYNTAX,1]}:{accepted:[COMPILER_SOURCE,0],rejected:[COMPILER_SOURCE,1]};
  for(const [stage,[source,status]] of Object.entries(expected)){const row=witness[stage];assert.equal(row?.source,source);assert.equal(row.sourceSha256,sha(String(source)));assert.equal(row.command[row.command.indexOf("--release")+1],kind==="compiler"&&stage==="rejected"?"11":"17");assert.equal(row.status,status);assert.equal(row.signal,null);assert.equal(row.error,"");
    if(status===1){assert.equal((row.stderr.match(/compiler\.err\./gu)??[]).length,1,"independent rejection has unrelated errors");assert.match(row.stderr,stage==="semantic"?/uniqueModeMissing/u:stage==="syntax"?/compiler\.err\.illegal\.start\.of\.expr/u:/compiler\.err\.feature\.not\.supported\.in\.source.*records/u);}}
}
export function prepareModeDiagnostics(kind:"loose"|"compiler",syntaxOnly=false){return (fixture:Fixture,javaHome:string,options:any={})=>{
  assert(options.timeoutMs>0);const preparation:any={kind:"diagnostic-mode",mode:kind,syntaxOnly,status:"preparing",diagnosticPolicy:modeDiagnosticPolicy(options.timeoutMs),witness:{}};fixture.preparation=preparation;
  if(kind==="loose")addSource(fixture,"LooseMode.java","loose/bench/LooseMode.java",LOOSE_VALID);
  else assert.equal(readFileSync(fixture.files["CompilerMode.java"].path,"utf8"),COMPILER_SOURCE);
  preparation.initialInputs={sourceUri:fixture.files[kind==="loose"?"LooseMode.java":"CompilerMode.java"].uri,classpath:readFileSync(path.join(fixture.root,".classpath"),"utf8"),preferences:readFileSync(path.join(fixture.root,".settings/org.eclipse.jdt.core.prefs"),"utf8")};
  const root=path.resolve(fixture.root,"../mode-compiler");mkdirSync(root,{recursive:true});
  try{
    preparation.jdk=jdkIdentity(javaHome);const env={...process.env,JAVA_HOME:javaHome,LC_ALL:"C"};for(const key of ["JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS","CLASSPATH"])delete env[key];
    const rows=kind==="loose"?[["valid",LOOSE_VALID,17],["semantic",LOOSE_SEMANTIC,17],["syntax",LOOSE_SYNTAX,17]]:[["accepted",COMPILER_SOURCE,17],["rejected",COMPILER_SOURCE,11]];
    for(const [stage,source,release] of rows){const dir=path.join(root,String(stage));mkdirSync(dir);const file=path.join(dir,kind==="loose"?"LooseMode.java":"CompilerMode.java");writeFileSync(file,String(source));
      const command=[path.join(javaHome,"bin/javac"),"--release",String(release),"-proc:none","-XDrawDiagnostics","-d",path.join(dir,"classes"),file],startNs=String(process.hrtime.bigint()),r=spawnSync(command[0],command.slice(1),{env,encoding:"utf8",timeout:30000});
      preparation.witness[String(stage)]={command,source,sourceSha256:sha(String(source)),startNs,endNs:String(process.hrtime.bigint()),status:r.status,signal:r.signal,stdout:r.stdout,stderr:r.stderr,error:String(r.error??"")};
    }
    modeCompilerOracle(preparation.witness,kind);preparation.status="verified";
  }catch(error){preparation.status="failed";preparation.error=String(error);throw error;}
  finally{writeFileSync(path.join(root,"witness.json"),JSON.stringify(preparation,null,2)+"\n");}
};}

