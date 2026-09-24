import { appendFileSync, readFileSync } from "node:fs";

if(process.argv.length<4)throw new Error("usage: compare.ts <jdtls-report> <jvmd-report>");
const jdtls=JSON.parse(readFileSync(process.argv[2],"utf8"));
const jvmd=JSON.parse(readFileSync(process.argv[3],"utf8"));

function fmt(value:any){return value===null||value===undefined?"-":Number(value).toFixed(2);}
function mb(kb:any){return kb===null||kb===undefined?"-":(Number(kb)/1024).toFixed(1);}
function completionCorrect(report:any,state:"firstUse"|"steady"){
  const value=report.correctness?.operationCorrectness?.completion?.[state];
  return Array.isArray(value)?value.every(Boolean):Boolean(value);
}
function timing(report:any,state:"firstUse"|"steady"){
  if(state==="firstUse")return report.operations.completion.firstUse.metrics.latencyMs;
  return report.operations.completion.stats;
}

if(jdtls.phaseModel.defaults.warmup!==jvmd.phaseModel.defaults.warmup)
  throw new Error("asymmetric operation warmup");
if(jdtls.phaseModel.defaults.steady_samples!==jvmd.phaseModel.defaults.steady_samples)
  throw new Error("asymmetric steady sample count");

const startup=[
  ["Initialize",jdtls.lifecycle.initializeMs,jvmd.lifecycle.initializeMs],
  ["Process → workspace ready",jdtls.lifecycle.processToWorkspaceReadyMs,jvmd.lifecycle.processToWorkspaceReadyMs],
  ["Workspace ready → documents admitted",jdtls.lifecycle.documentAdmissionMs,jvmd.lifecycle.documentAdmissionMs],
  ["Process → first correct completion",jdtls.coldEndToEnd.firstCorrectResultMs,jvmd.coldEndToEnd.firstCorrectResultMs],
  ["Process → first completion (diagnostic timing)",jdtls.coldEndToEnd.diagnosticMs,jvmd.coldEndToEnd.diagnosticMs],
];
const query=[
  ["completion","first_use",fmt(timing(jdtls,"firstUse")),fmt(timing(jvmd,"firstUse")),
    completionCorrect(jdtls,"firstUse")?"correct":"incorrect",
    completionCorrect(jvmd,"firstUse")?"correct":"incorrect"],
  ["completion","steady",
    "p50 "+fmt(timing(jdtls,"steady").p50Ms)+" / p95 "+fmt(timing(jdtls,"steady").p95Ms),
    "p50 "+fmt(timing(jvmd,"steady").p50Ms)+" / p95 "+fmt(timing(jvmd,"steady").p95Ms),
    completionCorrect(jdtls,"steady")?"correct":"incorrect",
    completionCorrect(jvmd,"steady")?"correct":"incorrect"],
];
const memory=[
  ["Workspace ready RSS",jdtls.lifecycle.memory.workspace_ready.totalKb,jvmd.lifecycle.memory.workspace_ready.totalKb],
  ["After first-use RSS",jdtls.lifecycle.memory.post_first_use.totalKb,jvmd.lifecycle.memory.post_first_use.totalKb],
  ["After steady RSS",jdtls.lifecycle.memory.post_steady.totalKb,jvmd.lifecycle.memory.post_steady.totalKb],
];

const lines=[
  "## LSP phase comparison",
  "",
  "### Startup / readiness",
  "",
  "| Metric | JDTLS ms | JVMD ms |",
  "| --- | ---: | ---: |",
  ...startup.map(row=>"| "+row[0]+" | "+fmt(row[1])+" | "+fmt(row[2])+" |"),
  "",
  "### Query latency",
  "",
  "| Operation | State | JDTLS | JVMD | JDTLS correctness | JVMD correctness |",
  "| --- | --- | ---: | ---: | --- | --- |",
  ...query.map(row=>"| "+row.join(" | ")+" |"),
  "",
  "Warmup samples are retained in each raw report and excluded from steady p50/p95. An incorrect result is never treated as an equivalent performance result; its timing is diagnostic only.",
  "",
  "### Memory",
  "",
  "| State | JDTLS MB | JVMD MB |",
  "| --- | ---: | ---: |",
  ...memory.map(row=>"| "+row[0]+" | "+mb(row[1])+" | "+mb(row[2])+" |"),
  "",
];
console.log(lines.join("\n"));
if(process.env.GITHUB_STEP_SUMMARY)appendFileSync(process.env.GITHUB_STEP_SUMMARY,lines.join("\n")+"\n");
