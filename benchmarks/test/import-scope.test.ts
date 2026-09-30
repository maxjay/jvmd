import {test} from "node:test";
import assert from "node:assert/strict";
import {mkdtempSync,rmSync} from "node:fs";
import os from "node:os";
import path from "node:path";
import {createFixture} from "../harness/fixture.ts";
import {prepareImportScope,importTargets,validateImportScopeEdit,organizedImportSource,scopeSource} from "../harness/importScope.ts";
import {position} from "../harness/oracles.ts";

function setup(){
  const tmp=mkdtempSync(path.join(os.tmpdir(),"jvmd-import-scope-")),fixture=createFixture(path.join(tmp,"case","fixture"),path.join(tmp,"repo"));prepareImportScope(fixture);
  const before=Object.values(fixture.files).map(f=>({uri:f.uri,text:f.text,version:1,open:true}));
  const edit=(uris:string[])=>({changes:Object.fromEntries(uris.map(uri=>{const f=before.find(f=>f.uri===uri)!;return [uri,[{range:{start:position(f.text,0),end:position(f.text,f.text.length)},newText:f.text.replace("import java.util.Set;\n","")}]];}))});
  return {tmp,fixture,before,edit};
}
for(const scope of ["folder","project"] as const)test(scope+" import scope includes all exact selected files",()=>{
  const f=setup();try{const targets=importTargets(f.fixture,scope);assert.equal(targets.length,scope==="folder"?2:5);const result=validateImportScopeEdit(f.fixture.root,f.before,f.edit(targets),targets);assert.equal(result.size,f.before.length);}finally{rmSync(f.tmp,{recursive:true,force:true});}
});
for(const name of ["BenchmarkScopePrefix.java","BenchmarkScopeSubstring.java","BenchmarkScopeSibling.java"])test("folder scope rejects spill into "+name,()=>{
  const f=setup();try{const targets=importTargets(f.fixture,"folder");assert.throws(()=>validateImportScopeEdit(f.fixture.root,f.before,f.edit([...targets,f.fixture.files[name].uri]),targets),/excluded/u);}finally{rmSync(f.tmp,{recursive:true,force:true});}
});
test("scope rejects omitted selected imports and edits to another imported project",()=>{
  const f=setup();try{const targets=importTargets(f.fixture,"folder");assert.throws(()=>validateImportScopeEdit(f.fixture.root,f.before,f.edit(targets.slice(1)),targets),/exactly the used import/u);
    const bad=f.edit(targets);bad.changes[f.fixture.preparation!.peer.files["BenchmarkScopeForeign.java"].uri]=[];
    assert.throws(()=>validateImportScopeEdit(f.fixture.root,f.before,bad,targets),/outside fixture/u);
  }finally{rmSync(f.tmp,{recursive:true,force:true});}
});
test("scope oracle preserves literal whitespace and all class-body bytes",()=>{
  const before=scopeSource("bench.selected","BenchmarkScopeOne"),good=before.replace("import java.util.Set;\n","");
  organizedImportSource(before,good);organizedImportSource(before,good.replace("import java.util.List;\n","\nimport java.util.List;\n\n"));
  for(const bad of [good.replace("keeps spaces","keepsspaces"),good.replace("public static","public  static"),good.replace('List.of(','List.copyOf(')])assert.throws(()=>organizedImportSource(before,bad),/class body/u);
});
test("scope rejects source deletion and makes repeats byte-exact",()=>{
  const f=setup();try{const targets=importTargets(f.fixture,"project"),first=validateImportScopeEdit(f.fixture.root,f.before,f.edit(targets),targets),stable=[...first.values()];
    validateImportScopeEdit(f.fixture.root,stable,{changes:{}},targets,true);
    assert.throws(()=>validateImportScopeEdit(f.fixture.root,f.before,{documentChanges:[{kind:"delete",uri:targets[0]}]},targets),/membership/u);
    const changed={changes:{[targets[0]]:[{range:{start:{line:0,character:0},end:{line:0,character:0}},newText:"\n"}]}};
    assert.throws(()=>validateImportScopeEdit(f.fixture.root,stable,changed,targets,true),/stable file/u);
  }finally{rmSync(f.tmp,{recursive:true,force:true});}
});
