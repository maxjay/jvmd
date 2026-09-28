import test from "node:test";
import assert from "node:assert/strict";
import path from "node:path";
import os from "node:os";
import {mkdtempSync,rmSync,existsSync,readFileSync} from "node:fs";
import {createFixture} from "../harness/fixture.ts";
import {ScenarioContext} from "../harness/ScenarioContext.ts";
import {invalidationCases} from "../scenarios/invalidation.ts";

test("independent compiler preserves duplicate filenames in different packages and isolates successive snapshots",{skip:!process.env.JAVA_HOME},async()=>{
  const root=mkdtempSync(path.join(os.tmpdir(),"compiler-layout-"));
  try{
    const def=invalidationCases.find(c=>c.id==="INV/namespace-remove")!,fixture=createFixture(path.join(root,"fixture"),def.fixture);
    def.prepare!(fixture,process.env.JAVA_HOME!);
    const c=new ScenarioContext({notify:()=>1n} as any,fixture,"jvmd",1000,1,1);
    c.compileOracle('package bench; public class HarnessOracle { public static void main(String[] args) { if(!Integer.valueOf(42).equals(new NamespaceUse().call())) throw new AssertionError(); } }');
    const first=c.assertions.find(a=>a.name==="independent javac validation");
    assert(existsSync(path.join(root,first.oracleDirectory,"sources/bench/Shadow.java")));
    assert(existsSync(path.join(root,first.oracleDirectory,"sources/external/Shadow.java")));
    c.deleteDisk("LocalShadow.java");
    c.compileOracle('package bench; public class HarnessOracle { public static void main(String[] args) { if(!"external".equals(new NamespaceUse().call())) throw new AssertionError(); } }');
    const checks=c.assertions.filter(a=>a.name==="independent javac validation");
    assert.notEqual(checks[0].oracleDirectory,checks[1].oracleDirectory);
    assert(!existsSync(path.join(root,checks[1].oracleDirectory,"classes/bench/Shadow.class")));
    assert(readFileSync(path.join(root,first.oracleDirectory,"sources/bench/Shadow.java"),"utf8").includes("int marker"));
    assert(c.assertions.every(a=>a.passed));
  }finally{rmSync(root,{recursive:true,force:true});}
});
