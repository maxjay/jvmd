import test from "node:test";
import assert from "node:assert/strict";
import {classpathScopeOracle} from "../harness/projectScope.ts";
import {exactSourceSymbol} from "../harness/oracles.ts";
import {range} from "../harness/oracles.ts";
test("main/test classpath oracle rejects leaked tests, missing outputs and duplicate dependencies",()=>{
  const fixture:any={root:"/w/1-jvmd-case/fixture",repository:"/repo"},group="/repo/bench/1_jvmd_case";
  const main=group+"/main-only/1/main-only-1.jar",test_=group+"/test-only/1/test-only-1.jar",out=fixture.root+"/target/classes",testOut=fixture.root+"/target/test-classes";
  const value=(paths:string[])=>({projectRoot:"file://"+fixture.root,modulepaths:[],classpaths:paths});
  classpathScopeOracle(value([out,main]),fixture,"runtime");classpathScopeOracle(value([testOut,out,main,test_]),fixture,"test");
  for(const paths of [[out,main,test_],[out],[out,main,main]])assert.throws(()=>classpathScopeOracle(value(paths),fixture,"runtime"));
  assert.throws(()=>classpathScopeOracle(value([out,main,test_]),fixture,"test"));
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
