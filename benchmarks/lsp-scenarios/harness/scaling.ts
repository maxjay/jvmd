import assert from "node:assert/strict";
import {mkdirSync,writeFileSync,readFileSync,rmSync} from "node:fs";
import {spawnSync} from "node:child_process";
import path from "node:path";
import {pathToFileURL} from "node:url";
import {type Fixture,sha,inventory} from "./fixture.ts";
export const scalingAxes={sources:[16,64,256],modules:[1,4,16],artifacts:[1,8,32],members:[1,16,64],reach:[1,16,64]} as const;
export type ScalingAxis=keyof typeof scalingAxes;
export type ScalingSize={sources:number;modules:number;artifacts:number;members:number;reach:number};
export const scalingBaseline:ScalingSize={sources:256,modules:1,artifacts:1,members:1,reach:1};

/** No timing results influence fixture sizes. Sources means unrelated padding
 * files; 64 callers and the four semantic control files remain constant. */
export function scalingModel(size:ScalingSize){
  for(const [key,value] of Object.entries(size))assert(Number.isInteger(value)&&value>=1&&value<=1024,"invalid scaling control: "+key);
  assert(size.modules<=size.sources&&size.reach<=64);
  const files:Record<string,string>={
    "modules/module_0/src/bench/Provider.java":'package bench; public class Provider { public String marker = "saved";\n'+Array.from({length:size.members},(_,i)=>` public int scaleMember${i}(){return ${i};}\n`).join("")+"}\n",
    "modules/module_0/src/bench/StableProvider.java":'package bench; public class StableProvider { public String marker = "stable"; }\n',
    "modules/module_0/src/bench/Probe.java":'package bench; public class Probe { public Object read(Provider p){return p.marker;} public void enumerate(Provider p){p.scaleMember0();} }\n',
    "modules/module_0/src/bench/UnrelatedBody.java":'package bench; public class UnrelatedBody { public int value(){return 1;} }\n',
  };
  const edges:{from:string;to:string}[]=[];
  for(let i=0;i<64;i++){
    const provider=i<size.reach?"Provider":"StableProvider",file=`modules/module_0/src/bench/Caller${i}.java`;
    files[file]=`package bench; public class Caller${i} { public Object read(${provider} p){return p.marker;} }\n`;
    edges.push({from:file,to:`modules/module_0/src/bench/${provider}.java`});
  }
  for(let i=0;i<size.sources;i++){
    const module=i%size.modules,prefix=`modules/module_${module}/`;
    files[`${prefix}src/padding/Padding${i}.java`]=`package padding; public class Padding${i} { public int value(){return ${i};} }\n`;
  }
  return {schemaVersion:1,size,files,edges,counts:{sourceFiles:Object.keys(files).length,paddingFiles:size.sources,projects:size.modules,
    buildModulesIncludingAggregator:size.modules+1,dependencyArtifacts:size.artifacts,queriedMembers:size.members,editedDependencyReach:size.reach,callerEdges:64},
    sourceBytes:Object.values(files).reduce((n,s)=>n+Buffer.byteLength(s),0),sourceHash:sha(JSON.stringify(files)),
    query:"Provider.marker hover has fixed result semantics except the members axis, which enumerates all scaleMember methods",
    controls:"zero-change payload; unrelated method-body change; provider API change; each starts from an independent fixture"};
}
export function prepareScaling(fixture:Fixture,javaHome:string,size:ScalingSize){
  const model=scalingModel(size),root=fixture.root;
  rmSync(path.join(root,"bench"),{recursive:true,force:true});fixture.files={};
  const prefs=readFileSync(path.join(root,".settings/org.eclipse.jdt.core.prefs"),"utf8");
  for(const name of [".project",".classpath",".settings"])rmSync(path.join(root,name),{recursive:true,force:true});
  const pomHeader='<project><modelVersion>4.0.0</modelVersion><groupId>bench.scaling</groupId><version>1</version>';
  const metadata:Record<string,string>={"pom.xml":pomHeader+'<artifactId>parent</artifactId><packaging>pom</packaging><modules>'+Array.from({length:size.modules},(_,i)=>`<module>modules/module_${i}</module>`).join("")+'</modules></project>'};
  for(let i=0;i<size.modules;i++){
    const base=`modules/module_${i}/`;
    metadata[base+"pom.xml"]=pomHeader+`<artifactId>module_${i}</artifactId><properties><maven.compiler.release>17</maven.compiler.release></properties><build><sourceDirectory>src</sourceDirectory></build><dependencies>`+(!i?Array.from({length:size.artifacts},(_,j)=>`<dependency><groupId>bench.scaling</groupId><artifactId>extra-${j}</artifactId><version>1</version></dependency>`).join(""):"")+"</dependencies></project>";
    metadata[base+".project"]=`<projectDescription><name>scale_${i}</name><buildSpec><buildCommand><name>org.eclipse.jdt.core.javabuilder</name><arguments/></buildCommand></buildSpec><natures><nature>org.eclipse.jdt.core.javanature</nature></natures></projectDescription>`;
    metadata[base+".classpath"]='<classpath><classpathentry kind="src" path="src"/><classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>'+(!i?Array.from({length:size.artifacts},(_,j)=>`<classpathentry kind="lib" path="lib/extra-${j}.jar"/>`).join(""):"")+'<classpathentry kind="output" path="bin"/></classpath>';
    metadata[base+".settings/org.eclipse.jdt.core.prefs"]=prefs;
  }
  for(const [relative,text] of Object.entries({...metadata,...model.files})){
    const file=path.join(root,relative);mkdirSync(path.dirname(file),{recursive:true});writeFileSync(file,text);
    if(relative.endsWith(".java"))fixture.files[relative]={path:file,uri:pathToFileURL(file).href,text};
  }
  const build=path.join(root,"..","scaling-oracle");mkdirSync(build);const commands:any[]=[];
  const env={...process.env,JAVA_HOME:javaHome,LC_ALL:"C"};for(const key of ["JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS","CLASSPATH"])delete env[key];
  const run=(tool:string,args:string[])=>{const command=[path.join(javaHome,"bin",tool),...args],startNs=String(process.hrtime.bigint());
    const r=spawnSync(command[0],command.slice(1),{encoding:"utf8",env,timeout:60000});commands.push({command,startNs,endNs:String(process.hrtime.bigint()),status:r.status,stdout:r.stdout,stderr:r.stderr});assert.equal(r.status,0,r.stderr);};
  const depSources:string[]=[];
  for(let i=0;i<size.artifacts;i++){const file=path.join(build,`deps/extra${i}/Artifact.java`);mkdirSync(path.dirname(file),{recursive:true});writeFileSync(file,`package extra${i}; public class Artifact { public int value(){return ${i};} }\n`);depSources.push(file);}
  run("javac",["--release","17","-proc:none","-g:none","-d",path.join(build,"classes"),...depSources]);mkdirSync(path.join(root,"modules/module_0/lib"));
  for(let i=0;i<size.artifacts;i++)run("jar",["--create","--file",path.join(root,`modules/module_0/lib/extra-${i}.jar`),"--no-manifest","--date=2020-01-01T00:00:00Z","-C",path.join(build,"classes"),`extra${i}`]);
  const repository=path.join(root,"..","repository");
  for(let i=0;i<size.artifacts;i++){
    const dir=path.join(repository,`bench/scaling/extra-${i}/1`);mkdirSync(dir,{recursive:true});
    writeFileSync(path.join(dir,`extra-${i}-1.jar`),readFileSync(path.join(root,`modules/module_0/lib/extra-${i}.jar`)));
    writeFileSync(path.join(dir,`extra-${i}-1.pom`),pomHeader+`<artifactId>extra-${i}</artifactId></project>`);
  }
  // Validate isolated copies; javac never writes to or warms the server fixture.
  const copySources=Object.entries(model.files).map(([relative,text])=>{const file=path.join(build,"source",relative);mkdirSync(path.dirname(file),{recursive:true});writeFileSync(file,text);return file;});
  run("javac",["--release","17","-proc:none","-d",path.join(build,"validated"),...copySources]);
  fixture.classpath=Array.from({length:size.artifacts},(_,i)=>path.join(root,`modules/module_0/lib/extra-${i}.jar`));
  fixture.workspaceFolders=Array.from({length:size.modules},(_,i)=>({name:`scale_${i}`,uri:pathToFileURL(path.join(root,`modules/module_${i}`)).href}));
  fixture.preparation={...model,kind:"controlled-scaling",commands,artifactInputs:inventory(path.join(root,"modules/module_0/lib")),
    repositoryInputs:inventory(repository),javaRelease:readFileSync(path.join(javaHome,"release"),"utf8"),javaSha256:sha(readFileSync(path.join(javaHome,"bin/java")))};
  writeFileSync(path.join(build,"preparation.json"),JSON.stringify(fixture.preparation,null,2)+"\n");
}
