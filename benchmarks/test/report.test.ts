import {test} from "node:test";
import assert from "node:assert/strict";
import {withReference,compare,renderMarkdown,statuses,type Summary} from "../report.ts";

const summary=(servers:string[],rows:[string,string,string][],perf:any[]=[]):Summary=>({schema:1,meta:{revision:"abc12345",dirty:false,date:"",jdk:'openjdk version "25.0.4.1"',cpus:4,runs:1,servers,reference:undefined},
  families:[{id:"CMP-01",name:"Complete"}],outOfScope:[],cases:rows.map(([id,server,outcome])=>({id,family:"CMP-01",server,scope:"supported",outcome})) as any,performance:perf,lifecycle:{}});

test("a JVMD-only run takes JDTLS rows from main's summary and says so",()=>{
  const main=summary(["jvmd","jdtls"],[["CMP-01/a","jvmd","pass"],["CMP-01/a","jdtls","stale"],["CMP-01/gone","jdtls","pass"]],[{server:"jdtls",endpoint:"textDocument/completion",phase:"first",medianMs:20,p95Ms:20,medianAllocBytes:1,samples:1}]);
  const pr=withReference(summary(["jvmd"],[["CMP-01/a","jvmd","pass"]]),main);
  assert.deepEqual(pr.meta.servers,["jvmd","jdtls"]);assert.equal(pr.meta.reference.revision,"abc12345");
  assert.deepEqual(pr.cases.map(c=>c.id+"/"+c.server),["CMP-01/a/jvmd","CMP-01/a/jdtls"],"JDTLS rows only for cases this run has");
  assert.equal(pr.performance.length,1);assert.match(renderMarkdown(pr),/JDTLS from main abc12345/u);
  assert.equal(withReference(main,summary(["jvmd"],[])),main,"a full run keeps its own JDTLS numbers");
});
test("since main lists regressions, fixes and large allocation moves, or says nothing changed",()=>{
  const perf=(alloc:number)=>[{server:"jvmd",endpoint:"textDocument/hover",phase:"repeat",medianMs:5,p95Ms:5,medianAllocBytes:alloc,samples:10}];
  const main=summary(["jvmd"],[["CMP-01/a","jvmd","pass"],["CMP-01/b","jvmd","incorrect"]],perf(4<<20));
  const now=summary(["jvmd"],[["CMP-01/a","jvmd","incorrect"],["CMP-01/b","jvmd","pass"]],perf(8<<20));
  const change=compare(now,main)!;
  assert.deepEqual([change.regressed.map(c=>c.id),change.fixed.map(c=>c.id),change.moves.map(m=>m.endpoint)],[["CMP-01/a"],["CMP-01/b"],["textDocument/hover"]]);
  assert.match(renderMarkdown(now,change),/\| ❌ \| `CMP-01\/a` \| pass \| wrong answer \|[\s\S]*\| ✅ \| `CMP-01\/b` \| wrong answer \| pass \|/u);
  assert.match(renderMarkdown(main,compare(main,main)),/^No change since main \(abc12345\)\.$/mu);
});
test("the PR checks line shows each count and its change since main",()=>{
  const main=summary(["jvmd"],[["CMP-01/a","jvmd","pass"],["CMP-01/b","jvmd","unsupported"]]);
  const now=summary(["jvmd"],[["CMP-01/a","jvmd","pass"],["CMP-01/b","jvmd","pass"]]);
  now.cases[1].scope="roadmap";main.cases[1].scope="roadmap";
  assert.deepEqual(statuses(now,main),[{context:"jvmd / scenarios",description:"Contract 1/1 · Roadmap 1/1 (+1) vs main"}]);
  assert.deepEqual(statuses(now),[{context:"jvmd / scenarios",description:"Contract 1/1 · Roadmap 1/1"}]);
  assert.match(renderMarkdown(now,compare(now,main),main),/^\*\*Contract\*\* 1\/1 correct · \*\*Roadmap\*\* 1\/1 \(\+1\) scenarios$/mu);
});
