import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {addProject,addDependency,sha,type Fixture} from "./fixture.ts";
import {installArtifact,caseGroup} from "./maven.ts";
import {symbolDeclarations,exactSearchSymbols} from "./symbols.ts";

export const FOREIGN_SYMBOL="package bench;\nclass BenchmarkForeign {}\n";
export const BINARY_SYMBOL="package binary;\npublic class BenchmarkBinary {}\n";
/** A second Maven project and a class-only dependency give every filter something to exclude. */
export function prepareSymbolFilters(fixture:Fixture,javaHome:string){
  const other=addProject(fixture,"secondary-fixture","benchmark_secondary",{"Foreign.java":FOREIGN_SYMBOL});
  const dependency={groupId:caseGroup(fixture),artifactId:"benchmark-symbols",version:"1"};
  const {jar}=installArtifact(fixture.repository,javaHome,dependency,{"binary/BenchmarkBinary.java":BINARY_SYMBOL},{attachSources:false,debug:false});
  addDependency(fixture,dependency);
  fixture.preparation={kind:"symbol-filter-controls",other:{root:other.root,sources:other.files},binary:{jar,jarSha256:sha(readFileSync(jar))}};
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
    assert.equal(decodeURIComponent(uri.pathname),"/benchmark-symbols-1.jar/binary/BenchmarkBinary.class");assert(uri.search.length>1,"binary symbol lacks its opaque class-file handle");
    assert.deepEqual(row.location.range,{start:{line:0,character:0},end:{line:0,character:0}},"unattached class-file anchor differs");
  }
}
