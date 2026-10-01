import {test} from "node:test";
import assert from "node:assert/strict";
import {mkdirSync,mkdtempSync,writeFileSync} from "node:fs";
import os from "node:os";
import path from "node:path";
import {generate} from "../persistence.ts";
import {render} from "../compare.ts";

test("the synthetic fixtures are RestartScenarioTest's: java.util.Random with the same seed",()=>{
  // Lines printed by SyntheticProjects.generate(topology,700,SEED) in Java.
  const line=(u:any)=>`${u.pkg}.${u.name} [${u.calls.join(", ")}]`;
  const dag=generate("RANDOM_DAG",700),hub=generate("HUB",700),layered=generate("LAYERED",700);
  assert.equal(line(dag[1]),"d1.D1 [d0.D0]");assert.equal(line(dag[4]),"d4.D4 []");assert.equal(line(dag[698]),"d23.D698 [d17.D217, d7.D257]");
  assert.equal(line(hub[2]),"h2.H2 [hub.Hub]");assert.equal(line(hub[49]),"h24.H49 [hub.Hub]");
  assert.equal(line(layered[99]),"l1.L1_29 [l0.L0_42, l0.L0_45]");assert.equal(line(layered[699]),"l9.L9_69 [l8.L8_16, l8.L8_2]");
});

test("the before/after report pairs sessions by side and flags differing diagnostics",()=>{
  const dir=mkdtempSync(path.join(os.tmpdir(),"before-after-"));
  const row=(label:string,first:number,javac:number,digest:string,extra:any={})=>({scenario:"real",label,units:977,readyMs:1000,openMs:2000,firstAnswerMs:first,
    diagnoseMs:first/2,totalMs:first*2,javac,processorRuns:null,persistence:null,peakRssBytes:1<<30,allocatedBytes:1<<30,diagnostics:3,digest,files:{},...extra});
  for(const [side,rows] of [["base",[row("cold",100_000,184,"a"),row("A9 no-change restart",100_000,184,"b")]],
    ["head",[row("cold",110_000,184,"a"),row("A9 no-change restart",3_000,0,"c",{persistence:{restores:977,refusals:{}}})]]] as const){
    mkdirSync(path.join(dir,"before-after-real",side+"-real"),{recursive:true});
    writeFileSync(path.join(dir,"before-after-real",side+"-real","persistence.json"),JSON.stringify({meta:{label:side},rows}));
  }
  const text=render(dir,{base:"c8fcb9f0",head:"abf0a240"});
  assert.match(text,/\| cold \| 977 \| 100\.0 s \| 110\.0 s \| 1\.1× slower .*\| 184 \| 184 \| – \| identical \|/u);
  assert.match(text,/\| cold \| 1\.0 s \| 1\.0 s \| 2\.0 s \| 2\.0 s \| 3\.3 min \| 3\.7 min \| 1\.00 G \| 1\.00 G \| = \|/u,"no change reads as =");
  assert.match(text,/\| A9 no-change restart \| 977 \| 100\.0 s \| 3\.0 s \| 33\.3× faster .*\| 184 \| 0 \| 977 \| \*\*differ\*\* \|/u);
});
