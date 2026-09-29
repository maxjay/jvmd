import assert from "node:assert/strict";
import path from "node:path";
import {spawnSync} from "node:child_process";
import {mkdirSync,readFileSync,writeFileSync,realpathSync,existsSync} from "node:fs";
import {type Fixture,sha,jdkIdentity} from "./fixture.ts";
import {SETTINGS,type PreparationOptions} from "./ScenarioContext.ts";

export const JDK_PROBE=`package bench;
import java.util.List;
public class JdkProbe {
    public static String first(List<String> values) { return values.getFirst(); }
    public static void main(String[] args) {
        if (!first(List.of("one", "two")).equals("one")) throw new AssertionError("wrong first item");
        System.out.println("first=one");
    }
}
`;
export const VM_LOCATION="org.eclipse.jdt.ls.core.vm.location";
export const canonicalJdkHome=(home:string)=>existsSync(home)?realpathSync(home):path.resolve(home);
export function jdkUpdateOracle(value:any,home:string){
  assert.equal(value?.success,true,"project rejected selected JDK");
  assert.equal(canonicalJdkHome(value.message),canonicalJdkHome(home),"JDK acknowledgement names another home");
}
export function vmInventoryOracle(value:any,homes:string[]){
  assert(Array.isArray(value),"VM inventory must be an array");
  for(const home of homes)assert(value.some(v=>typeof v.path==="string"&&canonicalJdkHome(v.path)===canonicalJdkHome(home)&&typeof v.version==="string"&&v.version.length),"selected VM absent from installed VM inventory: "+home);
}
export function compilerWitnessOracle(witness:any){
  assert.equal(witness.old.status,1,"JDK 17 must reject the new platform API");
  assert.equal(witness.old.error,"");assert.equal(witness.old.signal,null);
  assert.match(witness.old.stderr,/compiler\.err\.cant\.resolve[^\n]*getFirst/u,"old compiler failed for a reason other than the selected method");
  assert.equal((witness.old.stderr.match(/compiler\.err\./gu)??[]).length,1,"old compiler has unrelated source errors");
  assert.equal(witness.new.status,0,"new JDK must accept the same source");assert.equal(witness.new.error,"");assert.equal(witness.new.signal,null);
  assert.equal(witness.runtime.status,0,"new API runtime behaviour failed");assert.equal(witness.runtime.error,"");assert.equal(witness.runtime.signal,null);
  assert.equal(witness.runtime.stdout,"first=one\n");
}
export function prepareJdkSwitch(fixture:Fixture,javaHome:string,options:PreparationOptions={}){
  assert(options.alternateJavaHome,"PRJ-02/jdk-switch requires --alternate-java-home pointing to a full JDK 17; missing tools are not unsupported capability");
  const evidence=path.resolve(fixture.root,"../jdk-switch-toolchain.json"),preparation:any={kind:"two-real-jdks",status:"preparing",sourceSha256:sha(JDK_PROBE),
    compilerPolicy:"same source, -source 17 -target 17, native platform of each JDK; no --release substitution",
    timingScope:"independent compiler witnesses run before server launch; OS caches uncontrolled; no compiler or metadata polling between switch acknowledgement and immediate API probe"};
  fixture.preparation=preparation;
  try{
    const old=jdkIdentity(options.alternateJavaHome),next=jdkIdentity(javaHome);preparation.jdks={old,new:next};
    assert.equal(old.major,17,"alternate JDK must be version 17");assert(next.major>=21,"server JDK must expose List.getFirst (JDK 21 or later)");assert.notEqual(old.home,next.home,"switch requires distinct JDK homes");
    assert.equal(readFileSync(fixture.files["JdkProbe.java"].path,"utf8"),JDK_PROBE,"compiler and LSP source differ");
    const root=path.resolve(fixture.root,"../jdk-switch-oracle");mkdirSync(root,{recursive:true});
    const source=path.join(root,"JdkProbe.java");writeFileSync(source,JDK_PROBE);
    const execute=(home:string,args:string[],tool:string)=>{
      const command=[path.join(home,"bin",tool),...args],env={...process.env,JAVA_HOME:home,LC_ALL:"C"};
      for(const key of ["JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS","CLASSPATH"])delete env[key];
      const startNs=String(process.hrtime.bigint()),result=spawnSync(command[0],command.slice(1),{cwd:root,env,encoding:"utf8",timeout:30000});
      return {command,startNs,endNs:String(process.hrtime.bigint()),status:result.status,signal:result.signal,stdout:result.stdout,stderr:result.stderr,error:String(result.error??"")};
    };
    const compile=(home:string,label:string)=>execute(home,["-proc:none","-source","17","-target","17","-XDrawDiagnostics","-d",path.join(root,label),source],"javac");
    preparation.witness={old:compile(old.home,"old"),new:compile(next.home,"new"),runtime:execute(next.home,["-cp",path.join(root,"new"),"bench.JdkProbe"],"java")};
    compilerWitnessOracle(preparation.witness);
    fixture.settings=structuredClone(SETTINGS);
    fixture.settings.java.configuration={runtimes:[{name:"JavaSE-17",path:old.home,default:true},{name:"JavaSE-"+next.major,path:next.home,default:false}]};
    // Disable release emulation explicitly: this case changes the project VM's
    // platform API while source and class-file language levels stay at 17.
    const prefs=path.join(fixture.root,".settings/org.eclipse.jdt.core.prefs");
    writeFileSync(prefs,readFileSync(prefs,"utf8")+"org.eclipse.jdt.core.compiler.release=disabled\n");
    preparation.status="verified";
  }catch(error){preparation.status="failed";preparation.error=String(error);throw error;}
  finally{writeFileSync(evidence,JSON.stringify(preparation,null,2)+"\n");}
}
