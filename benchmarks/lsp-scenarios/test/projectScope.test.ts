import test from "node:test";
import assert from "node:assert/strict";
import {classpathScopeOracle,exactSourceSymbol} from "../harness/projectScope.ts";
import {range} from "../harness/oracles.ts";
test("main/test classpath oracle rejects leaked tests, missing outputs and duplicate dependencies",()=>{
  const value=(paths:string[])=>({projectRoot:"file:///fixture",modulepaths:[],classpaths:paths.map(p=>"/fixture/"+p)});
  classpathScopeOracle(value(["bin","lib/main.jar"]),"/fixture","runtime");
  classpathScopeOracle(value(["bin","bin-test","lib/main.jar","lib/test.jar"]),"/fixture","test");
  for(const paths of [["bin","lib/main.jar","lib/test.jar"],["bin"],["bin","lib/main.jar","lib/main.jar"]])assert.throws(()=>classpathScopeOracle(value(paths),"/fixture","runtime"));
  assert.throws(()=>classpathScopeOracle(value(["bin","lib/main.jar","lib/test.jar"]),"/fixture","test"));
});
test("root visibility rejects same-name foreign roots, use sites and retained removed types",()=>{
  const file={uri:"file:///fixture/extra/RootWitness.java",text:"class RootWitness { RootWitness self; }"};
  const row={name:"RootWitness",kind:5,location:{uri:file.uri,range:range(file.text,"RootWitness")}};
  exactSourceSymbol([row],file,"RootWitness");exactSourceSymbol([],file,"RootWitness",false);
  assert.throws(()=>exactSourceSymbol([{...row,location:{...row.location,uri:"file:///foreign/RootWitness.java"}}],file,"RootWitness"));
  assert.throws(()=>exactSourceSymbol([{...row,location:{...row.location,range:range(file.text,"RootWitness",10)}}],file,"RootWitness"));
  assert.throws(()=>exactSourceSymbol([row],file,"RootWitness",false));
  assert.throws(()=>exactSourceSymbol([row,row],file,"RootWitness"));
});
