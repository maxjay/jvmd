import {test} from "node:test";
import assert from "node:assert/strict";
import {mkdtempSync,readFileSync,writeFileSync,rmSync} from "node:fs";
import {spawnSync} from "node:child_process";
import os from "node:os";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {exactLocations,applyTextEdits,chooseMethod,completionEffect,range,offset} from "../harness/oracles.ts";
import {ScenarioContext} from "../harness/ScenarioContext.ts";
import {createFixture} from "../harness/fixture.ts";

const runner=fileURLToPath(new URL("../run.ts",import.meta.url));
const fake=fileURLToPath(new URL("./fake-server.ts",import.meta.url));
for(const [mode,caseId] of [["wrong-warmup","CMP-01/first-repeat"],["stale-provider","CMP-01/api-edit"],["wrong-range","NAV-01/definition"],["stale-call-graph","REL-01/incoming-add"],["stale-call-graph","REL-01/outgoing-remove"],["stale-lens","VIEW-03/code-lens-add"],["stale-lens","VIEW-03/code-lens-remove"],["duplicate-lens-reference","VIEW-03/code-lens"]]){
  test("actual runner rejects "+mode+" in "+caseId+" and retains raw evidence",()=>{
    const tmp=mkdtempSync(path.join(os.tmpdir(),"jvmd-gate-"));
    try{
      const command=path.join(tmp,"command.json"),output=path.join(tmp,"run");
      writeFileSync(command,JSON.stringify([process.execPath,fake,mode]));
      const run=spawnSync(process.execPath,[runner,"--servers","jvmd","--profile","custom","--command-json",command,"--output",output,"--only",caseId],{encoding:"utf8",timeout:15000});
      assert.equal(run.error,undefined,run.stderr);assert.equal(run.status,1,run.stdout+run.stderr);
      const summary=JSON.parse(readFileSync(path.join(output,"summary.json"),"utf8"));
      assert.equal(summary.complete,false);assert.equal(summary.outcomes.incorrect,1);
      const caseRoot=path.join(output,"01-jvmd-"+caseId.replaceAll("/","-"));
      const rows=readFileSync(path.join(caseRoot,"operations.jsonl"),"utf8").trim().split("\n").map(l=>JSON.parse(l));
      assert.equal(rows.at(-1).outcome,"incorrect");
      if(mode==="wrong-warmup"){assert.equal(rows[0].outcome,"pass");assert.equal(rows[1].state,"warmup");}
      if(mode==="stale-lens"||mode==="stale-call-graph"){
        assert(rows.some(r=>r.state==="baseline"&&r.outcome==="pass"),"stale test never established a correct baseline");
        assert(rows.some(r=>r.state==="changed_immediate"&&r.outcome==="incorrect"),"stale test did not fail after mutation");
      }
      assert(readFileSync(path.join(caseRoot,"events.jsonl"),"utf8").includes('"direction":"receive"'));
    }finally{rmSync(tmp,{recursive:true,force:true});}
  });
}
test("location oracle rejects wrong range even when URI agrees",()=>{
  const expected=[{uri:"file:///A.java",range:range("class A {}","A")}];
  assert.throws(()=>exactLocations([{uri:"file:///A.java",range:range("class A {}","class")}],expected));
});
test("UTF-16 edits reject split characters, overlaps and missing import edits",()=>{
  assert.throws(()=>offset("a😀b",{line:0,character:2}));
  assert.equal(offset("a😀b",{line:0,character:3}),3);
  const source="class C { List values; }";
  assert.throws(()=>applyTextEdits(source,[{range:range(source,"List"),newText:"Set"},{range:range(source,"List"),newText:"Map"}]));
  const item={label:"List",textEdit:{range:range(source,"List"),newText:"List"},additionalTextEdits:[{range:{start:{line:0,character:0},end:{line:0,character:0}},newText:"import java.util.List;\n"}]};
  assert(completionEffect(source,item,{line:0,character:12}).startsWith("import java.util.List;"));
  assert(!completionEffect(source,{...item,additionalTextEdits:[]},{line:0,character:12}).startsWith("import java.util.List;"));
});
test("completion resolve selection rejects ambiguous overloads",()=>{
  assert.throws(()=>chooseMethod([{label:"join(String)"},{label:"join(int)"}],"join"));
  assert.equal(chooseMethod([{label:"join(String)"},{label:"join(int)"}],"join",/String/u).label,"join(String)");
});
test("reopen advances incarnation and never reuses a version",async()=>{
  const tmp=mkdtempSync(path.join(os.tmpdir(),"jvmd-state-"));
  try{
    const client={next:0,notify:()=>process.hrtime.bigint()} as any;
    const c=new ScenarioContext(client,createFixture(tmp),"jvmd",100,2,20);
    const first=await c.open("Customer.java");c.change("Customer.java",c.text("Customer.java")+"\n");c.close("Customer.java");
    const second=await c.open("Customer.java");assert(second.version>first.version+1);
    assert.equal(c.documents.get(second.uri)?.incarnation,2);
  }finally{rmSync(tmp,{recursive:true,force:true});}
});
