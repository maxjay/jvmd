import assert from "node:assert/strict";
import path from "node:path";
import {mkdirSync,readFileSync,writeFileSync} from "node:fs";
import {spawnSync} from "node:child_process";
import {pathToFileURL,fileURLToPath} from "node:url";
import {type Fixture,sha} from "./fixture.ts";
import {jdkIdentity} from "./jdkSwitch.ts";
import {exactLocations,range} from "./oracles.ts";

export function addSource(fixture:Fixture,name:string,relative:string,text:string){
  const file=path.join(fixture.root,relative);mkdirSync(path.dirname(file),{recursive:true});writeFileSync(file,text);
  fixture.files[name]={path:file,uri:pathToFileURL(file).href,text};
}
export function exactSourceSymbol(value:any,file:{uri:string;text:string},name:string,present=true){
  assert(Array.isArray(value));assert.equal(value.length,present?1:0,"unexpected root-specific symbol membership");
  if(present){assert.equal(value[0].name,name);assert.equal(value[0].kind,5);exactLocations(value.map(v=>v.location),[{uri:file.uri,range:range(file.text,name)}]);}
}
export const TEST_SOURCE="package bench; public class TestRootWitness { public int testOnly() { return new Customer().number(); } }\n";
export function prepareProjectRoots(fixture:Fixture){
  addSource(fixture,"TestRootWitness.java","src/test/java/bench/TestRootWitness.java",TEST_SOURCE);
  writeFileSync(path.join(fixture.root,".classpath"),'<classpath><classpathentry kind="src" path="src/main/java"/><classpathentry kind="src" path="src/test/java" output="bin-test"><attributes><attribute name="test" value="true"/></attributes></classpathentry><classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/><classpathentry kind="output" path="bin"/></classpath>');
}
export function classpathScopeOracle(value:any,root:string,scope:string){
  assert.equal(path.resolve(fileURLToPath(value.projectRoot)),path.resolve(root));assert.deepEqual(value.modulepaths,[]);
  const expected=["bin","lib/main.jar",...(scope==="test"?["bin-test","lib/test.jar"]:[])].map(p=>path.join(root,p)).sort();
  assert.deepEqual(value.classpaths.map((p:string)=>path.resolve(p)).sort(),expected,"classpath includes wrong scope or omits a declared output/dependency");
}
export function prepareClasspathScopes(fixture:Fixture,javaHome:string){
  prepareProjectRoots(fixture);const root=path.resolve(fixture.root,"../scope-compiler");mkdirSync(root,{recursive:true});mkdirSync(path.join(fixture.root,"lib"));
  const preparation:any={kind:"main-test-scopes",status:"preparing",jdk:jdkIdentity(javaHome),commands:[],sources:{},artifacts:{}};fixture.preparation=preparation;
  const env={...process.env,JAVA_HOME:javaHome,LC_ALL:"C"};for(const key of ["JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS","CLASSPATH"])delete env[key];
  const run=(tool:string,args:string[],expected=0)=>{const command=[path.join(javaHome,"bin",tool),...args],startNs=String(process.hrtime.bigint()),r=spawnSync(command[0],command.slice(1),{env,encoding:"utf8",timeout:30000});
    const record={command,startNs,endNs:String(process.hrtime.bigint()),status:r.status,signal:r.signal,stdout:r.stdout,stderr:r.stderr,error:String(r.error??"")};preparation.commands.push(record);assert.equal(r.status,expected,r.stderr);return record;};
  try{
    for(const [scope,name,value] of [["main","MainOnly",31],["test","TestOnly",47]]){
      const source="package scope; public class "+name+" { public static int value() { return "+value+"; } }\n",file=path.join(root,name+".java"),classes=path.join(root,String(scope));
      writeFileSync(file,source);preparation.sources[String(name)]=source;run("javac",["--release","17","-proc:none","-d",classes,file]);
      const jar=path.join(fixture.root,"lib",scope+".jar");run("jar",["--create","--file",jar,"--no-manifest","--date=2020-01-01T00:00:00Z","-C",classes,"."]);preparation.artifacts[String(scope)]=sha(readFileSync(jar));
    }
    const main=path.join(fixture.root,"lib/main.jar"),test=path.join(fixture.root,"lib/test.jar"),probe=path.join(root,"ScopeOracle.java");
    const mainProbe='class ScopeOracle { int value(){return scope.MainOnly.value();} }';writeFileSync(probe,mainProbe);preparation.sources.mainProbe=mainProbe;
    run("javac",["--release","17","-proc:none","-classpath",main,"-d",path.join(root,"main-probe"),probe]);
    const testProbe='class ScopeOracle { int value(){return scope.MainOnly.value()+scope.TestOnly.value();} }';writeFileSync(probe,testProbe);preparation.sources.testProbe=testProbe;
    const rejected=run("javac",["--release","17","-proc:none","-XDrawDiagnostics","-classpath",main,"-d",path.join(root,"rejected-probe"),probe],1);
    assert(rejected.stderr.includes("TestOnly")&&(rejected.stderr.match(/compiler\.err\./gu)??[]).length===1,"main-only rejection must identify only the test dependency");
    run("javac",["--release","17","-proc:none","-classpath",[main,test].join(path.delimiter),"-d",path.join(root,"test-probe"),probe]);
    const cp=path.join(fixture.root,".classpath");writeFileSync(cp,readFileSync(cp,"utf8").replace('</classpath>','<classpathentry kind="lib" path="lib/main.jar"/><classpathentry kind="lib" path="lib/test.jar"><attributes><attribute name="test" value="true"/></attributes></classpathentry></classpath>'));
    fixture.classpath=[main,test];preparation.status="verified";
  }catch(error){preparation.status="failed";preparation.error=String(error);throw error;}
  finally{writeFileSync(path.join(root,"witness.json"),JSON.stringify(preparation,null,2)+"\n");}
}
