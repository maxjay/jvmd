import assert from "node:assert/strict";
import path from "node:path";
import {pathToFileURL} from "node:url";
import {mkdirSync,writeFileSync,readFileSync} from "node:fs";
import {spawnSync} from "node:child_process";
import {createFixture,inventory,sha,type Fixture} from "./fixture.ts";
import {symbolDeclarations,exactSearchSymbols} from "./symbols.ts";

export const FOREIGN_SYMBOL="package bench;\nclass BenchmarkForeign {}\n";
export const BINARY_SYMBOL="package binary;\npublic class BenchmarkBinary {}\n";
export function prepareSymbolFilters(fixture:Fixture,javaHome:string){
  const other=createFixture(path.resolve(fixture.root,"../secondary-fixture"),{"Foreign.java":FOREIGN_SYMBOL},"src");
  const project=path.join(other.root,".project");writeFileSync(project,readFileSync(project,"utf8").replace("<name>benchmark</name>","<name>benchmark_secondary</name>"));
  fixture.workspaceFolders=[{uri:pathToFileURL(fixture.root).href,name:"benchmark"},{uri:pathToFileURL(other.root).href,name:"benchmark_secondary"}];
  const build=path.resolve(fixture.root,"../symbol-filter-build"),source=path.join(build,"src/binary/BenchmarkBinary.java"),classes=path.join(build,"classes");
  mkdirSync(path.dirname(source),{recursive:true});mkdirSync(classes,{recursive:true});mkdirSync(path.join(fixture.root,"lib"));writeFileSync(source,BINARY_SYMBOL);
  const jar=path.join(fixture.root,"lib/benchmark-symbols.jar"),commands:any[]=[];
  const run=(tool:string,args:string[])=>{
    const command=[path.resolve(javaHome,"bin",tool),...args],startNs=String(process.hrtime.bigint()),result=spawnSync(command[0],command.slice(1),{encoding:"utf8",timeout:30000});
    commands.push({command,startNs,endNs:String(process.hrtime.bigint()),status:result.status,signal:result.signal,stdout:result.stdout,stderr:result.stderr,error:String(result.error??"")});
    assert.equal(result.status,0,"symbol-filter fixture tool failed: "+result.stderr);return result.stdout;
  };
  fixture.preparation={kind:"symbol-filter-controls",commands,other:{root:other.root,inputs:inventory(other.root),sources:other.files,identity:sha(JSON.stringify(inventory(other.root)))},
    binary:{source:BINARY_SYMBOL,sourceSha256:sha(BINARY_SYMBOL),jar,sourceAttachment:false},
    tools:Object.fromEntries(["release","bin/java","bin/javac","bin/jar"].map(p=>[p,sha(readFileSync(path.join(javaHome,p)))]))};
  try{
    // Strip SourceFile too: the expected binary URI identifies a .class file.
    run("javac",["--release","17","-proc:none","-g:none","-d",classes,source]);
    run("jar",["--create","--file",jar,"--no-manifest","--date=2020-01-01T00:00:00Z","-C",classes,"."]);
    const entries=run("jar",["--list","--file",jar]).trim().split("\n");assert.deepEqual(entries,["binary/","binary/BenchmarkBinary.class"]);
    Object.assign(fixture.preparation.binary,{jarSha256:sha(readFileSync(jar)),classSha256:sha(readFileSync(path.join(classes,"binary/BenchmarkBinary.class"))),entries});
    const cp=path.join(fixture.root,".classpath");writeFileSync(cp,readFileSync(cp,"utf8").replace("</classpath>",'<classpathentry kind="lib" path="lib/benchmark-symbols.jar"/></classpath>'));fixture.classpath=[jar];
    fixture.preparation.status="verified";
  }catch(error){fixture.preparation.status="failed";fixture.preparation.error=String(error);throw error;}
  finally{writeFileSync(path.join(build,"inputs.json"),JSON.stringify(fixture.preparation,null,2)+"\n");}
}
export type FilterExpectation={sources:ReturnType<typeof symbolDeclarations>;binary:boolean;limit?:number};
export function exactFilteredSymbols(value:any,expect:FilterExpectation){
  assert(Array.isArray(value),"filtered symbols missing");
  const names=[...expect.sources.map(s=>s.name),...(expect.binary?["BenchmarkBinary"]:[])];
  assert.equal(new Set(value.map(row=>row.name)).size,value.length,"duplicate filtered symbol");
  if(expect.limit!==undefined){assert(names.length>expect.limit,"limit control has too few matching declarations");assert.equal(value.length,expect.limit,"result limit ignored or underfilled");assert(value.every(row=>names.includes(row.name)),"limited result has an unrelated declaration");}
  else assert.deepEqual(value.map(row=>row.name).sort(),names.sort(),"project/source filter includes excluded or omits selected declarations");
  const sourceRows=value.filter(row=>row.name!=="BenchmarkBinary");
  exactSearchSymbols(sourceRows,expect.limit===undefined?expect.sources:expect.sources.filter(s=>sourceRows.some(row=>row.name===s.name)));
  for(const row of value.filter(row=>row.name==="BenchmarkBinary")){
    assert(expect.binary,"source-only result contains binary symbol");assert.equal(row.kind,5);
    const uri=new URL(row.location.uri);assert.equal(uri.protocol,"jdt:");assert.equal(uri.host,"contents");
    assert.equal(decodeURIComponent(uri.pathname),"/benchmark-symbols.jar/binary/BenchmarkBinary.class");assert(uri.search.length>1,"binary symbol lacks its opaque class-file handle");
    assert.deepEqual(row.location.range,{start:{line:0,character:0},end:{line:0,character:0}},"unattached class-file anchor differs");
  }
}
