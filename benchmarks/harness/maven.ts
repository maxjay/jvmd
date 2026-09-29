import assert from "node:assert/strict";
import {existsSync,mkdirSync,mkdtempSync,readFileSync,rmSync,writeFileSync} from "node:fs";
import {spawnSync} from "node:child_process";
import os from "node:os";
import path from "node:path";

export type Dependency={groupId:string;artifactId:string;version:string;scope?:string};
export type Pom={groupId:string;artifactId:string;version:string;release:number;dependencies:Dependency[]};

// Pinned so both servers and the prewarmed repository agree on every plugin, offline.
const PLUGINS:[string,string][]=[["maven-clean-plugin","3.4.0"],["maven-resources-plugin","3.3.1"],["maven-compiler-plugin","3.13.0"],
  ["maven-surefire-plugin","3.5.2"],["maven-jar-plugin","3.4.2"],["maven-install-plugin","3.1.3"],["maven-deploy-plugin","3.1.3"]];

export function pomXml(p:Pom){
  const deps=p.dependencies.map(d=>`<dependency><groupId>${d.groupId}</groupId><artifactId>${d.artifactId}</artifactId><version>${d.version}</version>${d.scope?`<scope>${d.scope}</scope>`:""}</dependency>`).join("");
  return `<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>${p.groupId}</groupId><artifactId>${p.artifactId}</artifactId><version>${p.version}</version>
  <properties><maven.compiler.release>${p.release}</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
  <dependencies>${deps}</dependencies>
  <build><pluginManagement><plugins>${PLUGINS.map(([a,v])=>`<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>${a}</artifactId><version>${v}</version></plugin>`).join("")}</plugins></pluginManagement></build>
</project>
`;
}

/** Group id private to one case directory, so artifacts a case installs or mutates never meet another case's. */
export const caseGroup=(fixture:{root:string})=>"bench."+path.basename(path.dirname(fixture.root)).toLowerCase().replace(/[^a-z0-9]+/gu,"_");
export const artifactDir=(repository:string,d:Dependency)=>path.join(repository,...d.groupId.split("."),d.artifactId,d.version);
export const artifactJar=(repository:string,d:Dependency,classifier="")=>path.join(artifactDir(repository,d),`${d.artifactId}-${d.version}${classifier?"-"+classifier:""}.jar`);

function tool(javaHome:string,name:string,args:string[],cwd?:string){
  const env={...process.env,JAVA_HOME:javaHome,LC_ALL:"C"};for(const k of ["JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS","CLASSPATH"])delete env[k];
  const r=spawnSync(path.join(javaHome,"bin",name),args,{env,cwd,encoding:"utf8",timeout:60000});
  assert.equal(r.status,0,`${name} ${args.join(" ")}: ${r.stderr||r.error}`);return r.stdout;
}

/** Compiles sources into a jar laid out as a Maven repository artifact, with an optional -sources.jar. */
export function installArtifact(repository:string,javaHome:string,d:Dependency,sources:Record<string,string>,{attachSources=true,debug=true,dependencies=[] as Dependency[]}={}){
  const work=mkdtempSync(path.join(os.tmpdir(),"jvmd-bench-artifact-")),src=path.join(work,"src"),classes=path.join(work,"classes");
  try{
    const files=Object.entries(sources).map(([relative,text])=>{const file=path.join(src,relative);mkdirSync(path.dirname(file),{recursive:true});writeFileSync(file,text);return file;});
    mkdirSync(classes,{recursive:true});
    const classpath=dependencies.map(x=>artifactJar(repository,x)).join(path.delimiter);
    tool(javaHome,"javac",["--release","17","-proc:none",...(debug?["-g"]:[]),...(classpath?["-classpath",classpath]:[]),"-d",classes,...files]);
    const dir=artifactDir(repository,d);mkdirSync(dir,{recursive:true});
    const jar=artifactJar(repository,d),archive=(target:string,root:string)=>tool(javaHome,"jar",["--create","--file",target,"--no-manifest","--date=2020-01-01T00:00:00Z","-C",root,"."]);
    archive(jar,classes);
    const sourcesJar=attachSources?artifactJar(repository,d,"sources"):undefined;
    if(sourcesJar)archive(sourcesJar,src);else rmSync(artifactJar(repository,d,"sources"),{force:true});
    writeFileSync(path.join(dir,`${d.artifactId}-${d.version}.pom`),pomXml({groupId:d.groupId,artifactId:d.artifactId,version:d.version,release:17,dependencies}));
    return {jar,sourcesJar};
  }finally{rmSync(work,{recursive:true,force:true});}
}

/** Maven settings pointing at the benchmark repository, fully offline. */
export function settingsXml(repository:string,file:string){
  writeFileSync(file,`<settings><localRepository>${repository}</localRepository><offline>true</offline></settings>\n`);return file;
}

/** Fills the repository with every pinned plugin by building a throwaway project once, online. */
export function prepareRepository(repository:string,javaHome:string,mvn="mvn"){
  const marker=path.join(repository,".jvmd-bench-ready");
  if(existsSync(marker)&&readFileSync(marker,"utf8")===pomXml(TEMPLATE))return;
  const work=mkdtempSync(path.join(os.tmpdir(),"jvmd-bench-template-"));
  try{
    mkdirSync(path.join(work,"src/main/java/bench"),{recursive:true});mkdirSync(path.join(work,"src/test/java/bench"),{recursive:true});
    writeFileSync(path.join(work,"pom.xml"),pomXml(TEMPLATE));
    writeFileSync(path.join(work,"src/main/java/bench/Template.java"),"package bench; public class Template {}\n");
    const env={...process.env,JAVA_HOME:javaHome};
    const r=spawnSync(mvn,["-B","-q","-Dmaven.repo.local="+repository,"clean","install"],{cwd:work,env,encoding:"utf8",timeout:600000});
    assert.equal(r.status,0,"preparing the Maven repository failed:\n"+r.stdout+r.stderr);
    writeFileSync(marker,pomXml(TEMPLATE));
  }finally{rmSync(work,{recursive:true,force:true});}
}
const TEMPLATE:Pom={groupId:"bench.template",artifactId:"template",version:"1",release:17,dependencies:[]};
