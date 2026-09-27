import assert from "node:assert/strict";
import {mkdirSync,writeFileSync,readFileSync} from "node:fs";
import {spawnSync} from "node:child_process";
import path from "node:path";
import {type Fixture,sha} from "./fixture.ts";
export const DEPENDENCY_USE='package bench;\npublic class DependencyUse { public String value(dep.Library library) { return library.original(); } }\n';
export function dependencyFixture(attach=true){return (fixture:Fixture,javaHome:string)=>{
  const dir=path.join(fixture.root,"lib");mkdirSync(dir,{recursive:true});
  const build=path.join(fixture.root,"..","dependency-build");mkdirSync(build,{recursive:true});
  const run=(tool:string,args:string[])=>{const r=spawnSync(path.join(javaHome,"bin",tool),args,{encoding:"utf8",timeout:30000});assert.equal(r.status,0,r.stderr);};
  const archive=(destination:string,root:string)=>run("jar",["--create","--file",destination,"--no-manifest","--date=2020-01-01T00:00:00Z","-C",root,"."]);
  for(const version of ["A","B"]){const sourceDir=path.join(build,version,"sources"),classes=path.join(build,version,"classes");mkdirSync(path.join(sourceDir,"dep"),{recursive:true});mkdirSync(classes,{recursive:true});
    const source=`package dep;\n/** LIBRARY_DOC_${version} */\npublic class Library {\n    /** MEMBER_DOC_${version} */ public String ${version==="A"?"original":"next"}() { return "${version}"; }\n}\n`;
    const file=path.join(sourceDir,"dep/Library.java");writeFileSync(file,source);run("javac",["--release","17","-proc:none","-g:none","-d",classes,file]);
    archive(path.join(dir,`library-${version}.jar`),classes);archive(path.join(dir,`library-${version}-sources.jar`),sourceDir);
    writeFileSync(file,source.replaceAll("DOC_"+version,"DOC_ATTACHED_V2"));archive(path.join(dir,"library-"+version+"-sources-v2.jar"),sourceDir);
  }
  const cp=path.join(fixture.root,".classpath");writeFileSync(cp,readFileSync(cp,"utf8").replace('</classpath>',`<classpathentry kind="lib" path="lib/library-A.jar"${attach?' sourcepath="lib/library-A-sources.jar"':""}/></classpath>`));
  fixture.classpath=[path.join(dir,"library-A.jar")];
  writeFileSync(path.join(build,"inputs.json"),JSON.stringify({schemaVersion:1,compilerRelease:17,jarDate:"2020-01-01T00:00:00Z",artifacts:Object.fromEntries(["library-A.jar","library-B.jar","library-A-sources.jar","library-A-sources-v2.jar","library-B-sources.jar","library-B-sources-v2.jar"].map(name=>[name,sha(readFileSync(path.join(dir,name)))]))},null,2));
};}
