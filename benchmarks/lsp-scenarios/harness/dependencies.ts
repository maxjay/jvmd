import assert from "node:assert/strict";
import {mkdirSync,writeFileSync,readFileSync} from "node:fs";
import {spawnSync} from "node:child_process";
import path from "node:path";
import {dependencySource} from "./dependencySource.ts";
import {jdkIdentity} from "./jdkSwitch.ts";
import {type Fixture,sha} from "./fixture.ts";
export const DEPENDENCY_USE='package bench;\npublic class DependencyUse { public String value(dep.Library library) { return library.original(); } }\n';
export function dependencyFixture(attach=true){return (fixture:Fixture,javaHome:string)=>{
  const dir=path.join(fixture.root,"lib");mkdirSync(dir,{recursive:true});
  const build=path.join(fixture.root,"..","dependency-build");mkdirSync(build,{recursive:true});
  const commands:any[]=[],env={...process.env,JAVA_HOME:javaHome,LC_ALL:"C"};
  for(const key of ["JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS","CLASSPATH"])delete env[key];
  const jdk=jdkIdentity(javaHome);
  const run=(tool:string,args:string[])=>{const command=[path.join(javaHome,"bin",tool),...args],startNs=String(process.hrtime.bigint());const r=spawnSync(command[0],command.slice(1),{env,encoding:"utf8",timeout:30000});
    commands.push({command,startNs,endNs:String(process.hrtime.bigint()),status:r.status,signal:r.signal,stdout:r.stdout,stderr:r.stderr,error:String(r.error??"")});assert.equal(r.status,0,r.stderr);return r.stdout;};
  const archive=(destination:string,root:string)=>run("jar",["--create","--file",destination,"--no-manifest","--date=2020-01-01T00:00:00Z","-C",root,"."]);
  for(const version of ["A","B"]){const sourceDir=path.join(build,version,"sources"),classes=path.join(build,version,"classes");mkdirSync(path.join(sourceDir,"dep"),{recursive:true});mkdirSync(classes,{recursive:true});
    const source=dependencySource(version);
    const file=path.join(sourceDir,"dep/Library.java");writeFileSync(file,source);run("javac",["--release","17","-proc:none","-g:none","-d",classes,file]);
    archive(path.join(dir,`library-${version}.jar`),classes);assert.deepEqual(run("jar",["--list","--file",path.join(dir,`library-${version}.jar`)]).trim().split("\n"),["dep/","dep/Library.class"]);archive(path.join(dir,`library-${version}-sources.jar`),sourceDir);
    writeFileSync(file,source.replaceAll("DOC_"+version,"DOC_ATTACHED_V2"));archive(path.join(dir,"library-"+version+"-sources-v2.jar"),sourceDir);
  }
  const cp=path.join(fixture.root,".classpath");writeFileSync(cp,readFileSync(cp,"utf8").replace('</classpath>',`<classpathentry kind="lib" path="lib/library-A.jar"${attach?' sourcepath="lib/library-A-sources.jar"':""}/></classpath>`));
  fixture.classpath=[path.join(dir,"library-A.jar")];
  fixture.preparation={schemaVersion:1,kind:"dependency-source",status:"verified",attached:attach,jdk,commands,sourceA:dependencySource(),sourceUpdated:dependencySource("A",true),compilerRelease:17,jarDate:"2020-01-01T00:00:00Z",artifacts:Object.fromEntries(["library-A.jar","library-B.jar","library-A-sources.jar","library-A-sources-v2.jar","library-B-sources.jar","library-B-sources-v2.jar"].map(name=>[name,sha(readFileSync(path.join(dir,name)))]))};
  writeFileSync(path.join(build,"inputs.json"),JSON.stringify(fixture.preparation,null,2)+"\n");
};}
