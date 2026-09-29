import {test} from "node:test";
import assert from "node:assert/strict";
import {mkdtempSync,rmSync} from "node:fs";
import os from "node:os";
import path from "node:path";
import {exactLocations,applyTextEdits,chooseMethod,completionEffect,range,offset} from "../harness/oracles.ts";
import {ScenarioContext} from "../harness/ScenarioContext.ts";
import {createFixture} from "../harness/fixture.ts";
import {runFake} from "./runFake.ts";

for(const [mode,caseId] of [["wrong-warmup","CMP-01/first-repeat"],["stale-provider","CMP-01/api-edit"],["wrong-range","NAV-01/definition"],["stale-call-graph","REL-01/incoming-add"],["stale-call-graph","REL-01/outgoing-remove"],["stale-lens","VIEW-03/code-lens-add"],["stale-lens","VIEW-03/code-lens-remove"],["duplicate-lens-reference","VIEW-03/code-lens"]])
  test("a wrong answer in "+caseId+" ("+mode+") is reported as needing attention, without failing the run",()=>{
    const r=runFake(mode,caseId);
    try{
      assert.equal(r.status,0,r.log);const result=r.case();assert.equal(result.outcome,"incorrect");
      if(mode==="wrong-warmup"){assert.equal(result.operations[0].outcome,"pass");assert.equal(result.operations[1].state,"warmup");}
      if(mode.startsWith("stale-")&&mode!=="stale-provider")assert(result.operations.some((o:any)=>o.state==="baseline"&&o.outcome==="pass"),"no correct baseline before the change");
      assert.match(r.markdown(),new RegExp("### Needs attention[\\s\\S]*\\| `"+caseId.replace("/","\\/")+"` \\| wrong answer \\|","u"));
      assert.match(r.markdown(),new RegExp("--only "+caseId.replace("/","\\/")),"reproduce command missing");
    }finally{r.cleanup();}
  });
test("an answer that is only correct after retries is stale, and the time to correct is recorded",()=>{
  const r=runFake("delayed-provider","CMP-01/api-edit");
  try{
    assert.equal(r.status,0,r.log);const ops=r.case().operations;assert.equal(r.case().outcome,"stale");
    assert.equal(ops.find((o:any)=>o.state==="changed_immediate").outcome,"stale");
    const correct=ops.find((o:any)=>o.state==="changed_retry"&&o.outcome==="pass");assert(correct&&correct.transitionMs>=correct.latencyMs);
  }finally{r.cleanup();}
});
test("a java/ request answered with MethodNotFound is not implemented, and the report names the endpoint",()=>{
  const r=runFake("correct","NAV-03/extended-first-repeat");
  try{
    assert.equal(r.status,0,r.log);const result=r.case();assert.equal(result.outcome,"unsupported");assert.equal(result.missing,"java/extendedDocumentSymbol");
    assert.match(r.markdown(),/<b>Roadmap<\/b>: 1 endpoints not implemented over LSP \(1 already in JVMD's own API\)[\s\S]*\| `java\/extendedDocumentSymbol` \| 1 \| `overview` \|/u);
  }finally{r.cleanup();}
});
test("passing requests reach the latency table; allocation is blank when the peer has no probe",()=>{
  const r=runFake("correct","CMP-01/first-repeat");
  try{
    assert.equal(r.status,0,r.log);const ops=r.case().operations;
    assert.deepEqual(ops.map((o:any)=>o.state),["first_use","warmup","steady","steady"]);assert(ops.every((o:any)=>o.allocatedBytes===null&&o.latencyMs>=0));
    assert.match(r.markdown(),/\| `completion` \| first \| [\d.]+ ms \| – \| – \| – \|/u);
    assert.match(r.markdown(),/\| CMP-01 \| Complete and resolve a candidate \| ✅ 1\/1 \|/u);
    assert.match(r.markdown(),/^\*\*Contract\*\* 1\/1 correct · \*\*Roadmap\*\* 0\/0 scenarios$/mu);
    assert.match(r.report(),/^  jvmd +contract +1\/1 +roadmap +0\/0 /mu);
  }finally{r.cleanup();}
});
test("against a baseline, fixes and regressions are highlighted and annotated but the run still succeeds",()=>{
  const base=runFake("correct","CMP-01/first-repeat");
  const r=runFake("wrong-warmup","CMP-01/first-repeat",{args:["--baseline",path.join(base.output,"summary.json")],env:{GITHUB_ACTIONS:"true"}});
  try{
    assert.equal(r.status,0,r.log);
    assert.match(r.markdown(),/### Since main[\s\S]*\| ❌ \| `CMP-01\/first-repeat` \| pass \| wrong answer \|/u);
    assert.match(r.log,/::warning title=Benchmark regression::CMP-01\/first-repeat passed on main, now incorrect/u);
  }finally{base.cleanup();r.cleanup();}
});
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
    const c=new ScenarioContext(client,createFixture(path.join(tmp,"fixture"),path.join(tmp,"repo")),"jvmd",100,2,20);
    const first=await c.open("Customer.java");c.change("Customer.java",c.text("Customer.java")+"\n");c.close("Customer.java");
    const second=await c.open("Customer.java");assert(second.version>first.version+1);
    assert.equal(c.documents.get(second.uri)?.incarnation,2);
  }finally{rmSync(tmp,{recursive:true,force:true});}
});
