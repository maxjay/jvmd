import { test } from "node:test";
import assert from "node:assert/strict";
import { PHASE_MODEL, elapsedMs, latencyStats, orderedMilestones } from "../harness/phases.ts";

test("canonical phase model preserves first use before warmup and steady",()=>{
  assert.deepEqual(PHASE_MODEL.states,[
    "startup","machine_index","initialize","service_ready","session_open","workspace_resolution",
    "workspace_index","document_admission","first_use","warmup","steady",
  ]);
  assert.equal(PHASE_MODEL.defaults.warmup,2);
  assert.equal(PHASE_MODEL.defaults.steady_samples,20);
});

test("steady statistics exclude discarded warmups by construction",()=>{
  const warmup=[1000,1000];
  const steady=Array.from({length:20},(_,i)=>i+1);
  const stats=latencyStats(steady);
  assert.equal(stats.samples,20);
  assert.equal(stats.p50Ms,10);
  assert.equal(stats.p95Ms,19);
  assert.equal(warmup.length,PHASE_MODEL.defaults.warmup);
});

test("milestones and cold elapsed time use one monotonic timeline",()=>{
  const milestones={spawn:100,initialize:200,ready:300,admitted:400,firstStart:500,firstEnd:900};
  assert.equal(orderedMilestones(milestones,["spawn","initialize","ready","admitted","firstStart","firstEnd"]),true);
  assert.equal(elapsedMs(milestones.spawn,milestones.firstEnd),0.0008);
  assert.equal(orderedMilestones({...milestones,admitted:50},["spawn","initialize","ready","admitted","firstStart","firstEnd"]),false);
});
