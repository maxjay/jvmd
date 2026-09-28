import test from "node:test";
import assert from "node:assert/strict";
import path from "node:path";
import os from "node:os";
import {mkdtempSync,rmSync} from "node:fs";
import {createFixture} from "../harness/fixture.ts";
import {ScenarioContext} from "../harness/ScenarioContext.ts";
import {invalidationCases,classpathMetadata} from "../scenarios/invalidation.ts";
import {range} from "../harness/oracles.ts";

test("overload change requires the new declaration even when the old same-name member still exists",async()=>{
  const def=invalidationCases.find(c=>c.id==="INV/overload-add")!;
  const root=mkdtempSync(path.join(os.tmpdir(),"overload-invalidation-"));
  try{
    const fixture=createFixture(root,def.fixture),c=new ScenarioContext({notify:()=>1n} as any,fixture,"jvmd",1000,1,1);
    const old={uri:fixture.files["OverloadProvider.java"].uri,range:range(fixture.files["OverloadProvider.java"].text,"choose")};
    c.series=async(_method,_params,oracle)=>{oracle([old]);};
    c.transition=async(_method,_params,oracle)=>{assert.throws(()=>oracle([old]),/location identities/u);oracle([{uri:old.uri,range:range(c.text("OverloadProvider.java"),"choose",c.text("OverloadProvider.java").indexOf("String choose"))}]);};
    c.compileOracle=()=>{};
    await def.run(c);
    assert(c.assertions.every(a=>a.passed));
  }finally{rmSync(root,{recursive:true,force:true});}
});

test("namespace cases keep independent initial states and require the distinguishing current type",async()=>{
  for(const mutation of ["add","remove","unrelated"]){
    const def=invalidationCases.find(c=>c.id==="INV/namespace-"+mutation)!,root=mkdtempSync(path.join(os.tmpdir(),"namespace-invalidation-"));
    try{
      const fixture=createFixture(root,def.fixture);def.prepare!(fixture,"unused");
      const c=new ScenarioContext({notify:()=>1n} as any,fixture,"jvmd",1000,1,1);
      c.series=async(_method,_params,oracle)=>{oracle({contents:(mutation==="remove"?"int":"String")+" marker"});};
      c.transition=async(_method,_params,oracle)=>{oracle({contents:(mutation==="add"?"int":"String")+" marker"});
        if(mutation!=="unrelated")assert.throws(()=>oracle({contents:(mutation==="add"?"String":"int")+" marker"}),/hover type wrong/u);};
      c.compileOracle=()=>{};
      await def.run(c);assert.equal(c.mutations.length,1);assert(c.assertions.every(a=>a.passed));
    }finally{rmSync(root,{recursive:true,force:true});}
  }
});

test("ordered Maven and Eclipse declarations select the same two fixed archive identities",()=>{
  const a=classpathMetadata(["A","B"]),b=classpathMetadata(["B","A"]);
  for(const name of [".classpath","pom.xml"]){
    const left=name==="pom.xml"?"library-a":"library-A.jar",right=name==="pom.xml"?"library-b":"library-B.jar";
    assert(a[name].indexOf(left)<a[name].indexOf(right));
    assert(b[name].indexOf(right)<b[name].indexOf(left));
    assert.equal(a[name].split(left).length,2);assert.equal(b[name].split(right).length,2);
  }
  assert.equal(invalidationCases.length,7);assert.equal(new Set(invalidationCases.map(c=>c.id)).size,7);
});
