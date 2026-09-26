import {test} from "node:test";
import assert from "node:assert/strict";
import {mkdtempSync,readFileSync,writeFileSync,rmSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {spawnSync} from "node:child_process";
import {CONTRACT,diagnosticDecision,counterDelta,intervalUnion,legacyFailures,measured} from "../harness/contracts.ts";

for (const v of CONTRACT.diagnosticVectors) test("shared diagnostic contract: "+v.name,()=>{
  assert.equal(diagnosticDecision(v.mode,v.params,v.uri,v.version,v.incarnation,v.eventIncarnation).kind,v.expected);
});
test("missing metrics are unavailable; counter resets are not zero work",()=>{
  assert.equal(measured(undefined,"bytes").value,null);
  assert.equal(measured(NaN,"bytes").status,"unavailable");
  assert.equal(counterDelta({owner:"a",epoch:1,values:{x:2}},{owner:"a",epoch:2,values:{x:2}},"x").status,"unavailable");
  assert.equal(counterDelta({owner:"a",epoch:1,values:{x:2}},{owner:"a",epoch:1,values:{x:1}},"x").status,"contradicted");
});
test("inclusive spans use interval union; mixed clocks and inverted spans fail",()=>{
  const s=(a:number,b:number,clockDomain="jvm")=>({startNs:BigInt(a),endNs:BigInt(b),clockDomain});
  assert.equal(intervalUnion([s(0,10),s(2,4),s(8,12)],"jvm"),12n);
  assert.throws(()=>intervalUnion([s(3,1)],"jvm"));
  assert.throws(()=>intervalUnion([s(0,1,"node")],"jvm"));
});
test("actual comparator writes diagnostic output and exits nonzero for incorrect warmup",()=>{
  const root=mkdtempSync(join(tmpdir(),"jvmd-gate-"));
  try {
    const a=JSON.parse(readFileSync(new URL("../../evidence/historical-36269400226/jdtls-report.json",import.meta.url),"utf8"));
    const b=JSON.parse(readFileSync(new URL("../../evidence/historical-36269400226/jvmd-report.json",import.meta.url),"utf8"));
    assert.deepEqual(legacyFailures(a),[]);assert.deepEqual(legacyFailures(b),[]);
    b.correctness.operationCorrectness.completion.warmup[0]=false;
    const left=join(root,"left.json"),right=join(root,"right.json");
    writeFileSync(left,JSON.stringify(a));writeFileSync(right,JSON.stringify(b));
    const result=spawnSync(process.execPath,["benchmarks/lsp-scenarios/compare.ts",left,right],{encoding:"utf8"});
    assert.notEqual(result.status,0);assert.match(result.stdout,/LSP phase comparison/);
    assert.match(result.stderr,/warmup\[0\]/);
    assert.equal(JSON.parse(readFileSync(right,"utf8")).correctness.operationCorrectness.completion.warmup[0],false);
  } finally {rmSync(root,{recursive:true,force:true});}
});
