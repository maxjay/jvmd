import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync,existsSync,mkdtempSync,mkdirSync,writeFileSync,rmSync} from "node:fs";
import {spawnSync} from "node:child_process";
import {tmpdir} from "node:os";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {mergeCatalogue,finishCatalogue} from "../harness/catalogueReduction.ts";

const outcomes=JSON.parse(readFileSync(new URL("../../measurement-contract.json",import.meta.url),"utf8")).outcomes;
const counts=(rows:any[])=>Object.fromEntries(outcomes.map((outcome:string)=>[outcome,rows.filter(row=>row.outcome===outcome).length]));
function fixture() {
  const plan={schemaVersion:1,caseIds:["A/one","B/two"],servers:["jvmd","jdtls"],shards:2,blocks:1,warmup:1,samples:2,timeoutMs:30000,profile:"product"};
  const registry=plan.caseIds.map(id=>({id,apis:[id],family:id[0]}));
  const catalogue={apis:plan.caseIds.map(id=>({id,method:id,testUse:"Scenario target"})),scenarios:[]};
  const sourceInputs={"runner.ts":"b".repeat(64)};
  const packets=plan.caseIds.map((caseId,i)=>{
    const cases=plan.servers.map(server=>({caseId,server,block:1,outcome:"pass",operations:[{operationId:"op-1",outcome:"pass"}]}));
    return {source:`/shard-${i+1}`,inventorySha256:"f".repeat(64),manifest:{schemaVersion:1,revision:"a".repeat(40),sourceTree:"c".repeat(40),sourceInputs,finalSourceInputs:sourceInputs,
      sourceDrift:false,registry,contract:1,capabilities:{hover:true},settings:{},resourcePolicy:{},caseSealPolicy:"seal",
      environment:{node:"v24.21.0",platform:"linux",arch:"x64"},plan:{...plan,caseIds:[caseId],timeout:30000}},
      catalogue,variantContract:{schemaVersion:1,families:[]},reduction:{cases,
        summary:{schemaVersion:1,planned:2,executed:2,outcomes:counts(cases),complete:true,integrityIssues:[]},
        coverage:plan.caseIds.map(id=>({apiId:id,method:id,role:"Scenario target",caseIds:[id],
          observations:id===caseId?cases.map(row=>({...row,declaredTarget:true,eventIds:[1]})):[],
          dispositions:id===caseId?cases.map(row=>({...row})):[]}))}};
  });
  return {plan,packets};
}
const passingVariants=()=>({complete:true,issues:[],rows:[{outcome:"pass"}],gaps:[]});
const finish=(f:any)=>finishCatalogue(mergeCatalogue(f.plan,f.packets),passingVariants());
function failCase(packet:any,outcome:string) {
  packet.reduction.cases[0].outcome=outcome;
  for(const api of packet.reduction.coverage)if(api.dispositions.length)api.dispositions[0].outcome=outcome;
  packet.reduction.summary.outcomes=counts(packet.reduction.cases);
  packet.reduction.summary.complete=false;
}

test("catalogue assembles all cases without pooling clocks, operation IDs or timings",()=>{
  const f=fixture(),before=JSON.stringify(f),result=finish(f);
  assert.equal(result.summary.complete,true);assert.equal(result.summary.planned,4);
  assert.equal(result.summary.outcomes.pass,4);assert.equal(result.summary.publicComparativePerformance,false);
  assert.equal(result.coverage[0].observations.length,2);
  assert.deepEqual(result.cases.map(row=>row.source),["/shard-1","/shard-1","/shard-2","/shard-2"]);
  assert.equal(JSON.stringify(f),before);assert.equal((result.summary as any).medianMs,undefined);
});
test("missing shard keeps its planned cases as not_run",()=>{
  const f=fixture();f.packets.pop();const r=finish(f);
  assert.equal(r.summary.planned,4);assert.equal(r.summary.executed,2);assert.equal(r.summary.outcomes.not_run,2);
  assert.equal(r.summary.complete,false);assert.equal(r.summary.allCasesRecorded,false);
});
test("duplicate shard cannot replace a missing partition or inflate successes",()=>{
  const f=fixture();f.packets[1]=structuredClone(f.packets[0]);const r=finish(f);
  assert.equal(r.summary.complete,false);assert.equal(r.summary.outcomes.pass,2);assert.equal(r.summary.outcomes.not_run,2);
  assert(r.summary.integrityIssues.some(issue=>issue.includes("duplicate")));
});
test("product failure and later successful operation remain a failed case",()=>{
  const f=fixture();failCase(f.packets[0],"incorrect");const r=finish(f);
  assert.equal(r.summary.outcomes.incorrect,1);assert.equal(r.summary.complete,false);
  assert.equal(r.summary.integrityValid,true);assert.equal(r.cases[0].recordedOutcome,"incorrect");
});
test("source, runtime and retry-policy mismatches block assembly",()=>{
  for(const mutation of [
    (p:any)=>p.manifest.revision="d".repeat(40),
    (p:any)=>p.manifest.environment.node="v22.23.2",
    (p:any)=>p.manifest.plan.extraRetry=1,
    (p:any)=>p.manifest.sourceInputs={"other.ts":"e".repeat(64)},
    (p:any)=>p.manifest.sourceDrift=true,
  ]) {const f=structuredClone(fixture());mutation(f.packets[1]);assert.equal(finish(f).summary.integrityValid,false);}
});
test("false successful denominator cannot conceal a canonical case failure",()=>{
  const f=fixture();f.packets[0].reduction.cases[0].outcome="incorrect";const r=finish(f);
  assert.equal(r.summary.outcomes.incorrect,1);assert.equal(r.summary.complete,false);
  assert(r.summary.integrityIssues.some(issue=>issue.includes("denominator mismatch")));
});
test("case overlap between distinct named partitions fails",()=>{
  const f=fixture();f.packets[1].reduction.cases[0].caseId="A/one";const r=finish(f);
  assert.equal(r.summary.complete,false);assert(r.summary.integrityIssues.some(issue=>issue.includes("duplicate case")));
});
test("full case counts do not erase a raw-artifact integrity failure",()=>{
  const f=fixture();f.packets[0].reduction.summary.integrityIssues.push("case: hash mismatch" as never);
  f.packets[0].reduction.summary.complete=false;const r=finish(f);
  assert.equal(r.summary.allCasesRecorded,true);assert.equal(r.summary.integrityValid,false);
  assert.equal(r.summary.complete,false);assert(r.summary.integrityIssues.includes("/shard-1: case: hash mismatch"));
});
test("failed replay keeps unavailable shard explicit and all missing cases in denominator",()=>{
  const f:any=fixture();f.packets[0]={source:"/shard-1",error:"corrupt manifest"};const r=finish(f);
  assert.equal(r.summary.outcomes.not_run,2);assert.equal(r.summary.complete,false);
  assert(r.summary.integrityIssues.some(issue=>issue.includes("replay unavailable")));
});
test("a required variant failure cannot be voted away by passing cases",()=>{
  const f=fixture(),merged=mergeCatalogue(f.plan,f.packets);
  const r=finishCatalogue(merged,{complete:false,issues:[],rows:[{outcome:"missing_evidence"}],gaps:["required-edit"]});
  assert.equal(r.summary.complete,false);assert.deepEqual(r.summary.uncoveredRequiredVariants,["required-edit"]);
  assert.equal(finishCatalogue(merged,{complete:true,issues:[],rows:[],gaps:[]}).summary.complete,false);
});
test("API evidence must match the same server, block and case as its passing disposition",()=>{
  const f=fixture();f.packets[0].reduction.coverage[0].observations[0].caseId="B/two";
  const r=finish(f);assert.equal(r.summary.complete,false);assert.deepEqual(r.summary.uncoveredTargetApiIds,["A/one"]);
});
test("unsupported is not a success without canonical capability evidence",()=>{
  const f=fixture();failCase(f.packets[0],"unsupported");f.packets[0].reduction.summary.complete=true;
  const r=finish(f);assert.equal(r.summary.outcomes.unsupported,1);assert.equal(r.summary.complete,false);
});
test("empty and duplicate authoritative case plans are rejected",()=>{
  const f=fixture();for(const caseIds of [[],["A/one","A/one"]])assert.throws(()=>mergeCatalogue({...f.plan,caseIds},f.packets),/invalid declared catalogue plan/);
});
test("actual catalogue CLI preserves its failed report and leaves raw input untouched",()=>{
  const root=mkdtempSync(path.join(tmpdir(),"catalogue-gate-"));
  try {
    const input=path.join(root,"raw"),output=path.join(root,"audit"),plan=path.join(root,"plan.json");mkdirSync(input);
    writeFileSync(path.join(input,"original.txt"),"unmodified failed capture");writeFileSync(plan,JSON.stringify(fixture().plan));
    const result=spawnSync(process.execPath,[...process.execArgv,fileURLToPath(new URL("../reduce-catalogue.ts",import.meta.url)),plan,output,input],{encoding:"utf8",timeout:30000});
    assert.equal(result.status,1,result.stderr);const summary=JSON.parse(readFileSync(path.join(output,"summary.json"),"utf8"));
    assert.equal(summary.complete,false);assert.equal(summary.planned,4);assert.equal(summary.outcomes.not_run,4);
    assert(existsSync(path.join(output,"report.md")));assert(existsSync(path.join(output,"checksums.sha256")));
    assert.equal(readFileSync(path.join(input,"original.txt"),"utf8"),"unmodified failed capture");
  } finally {rmSync(root,{recursive:true,force:true});}
});
