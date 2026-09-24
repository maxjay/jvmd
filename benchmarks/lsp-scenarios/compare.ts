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
  return report.operations.completion.correctStats??report.operations.completion.stats;
}
function displayTiming(report:any,state:"firstUse"|"steady"){
  const correct=completionCorrect(report,state);
  if(state==="firstUse"){
    const value=fmt(report.operations.completion.firstUse.metrics.latencyMs);
    return correct?value:value+" diagnostic";
  }
  const stats=correct
    ?(report.operations.completion.correctStats??report.operations.completion.stats)
    :report.operations.completion.stats;
  const value="p50 "+fmt(stats.p50Ms)+" / p95 "+fmt(stats.p95Ms);
  return correct?value:value+" diagnostic";
}

if(jdtls.phaseModel.defaults.warmup!==jvmd.phaseModel.defaults.warmup)
  throw new Error("asymmetric operation warmup");
if(jdtls.phaseModel.defaults.steady_samples!==jvmd.phaseModel.defaults.steady_samples)
  throw new Error("asymmetric steady sample count");

const startup=[
  ["Initialize request",jdtls.lifecycle.initializeMs,jvmd.lifecycle.initializeMs],
  ["Process → initialize response",jdtls.lifecycle.processToInitializeResponseMs,jvmd.lifecycle.processToInitializeResponseMs],
  ["JDTLS ServiceReady",jdtls.lifecycle.serviceReadyMs,null],
  ["JVMD machine index ready",null,jvmd.lifecycle.machineIndexReadyMs],
  ["Process → documents admitted",jdtls.lifecycle.documentsAdmittedFromProcessMs,jvmd.lifecycle.documentsAdmittedFromProcessMs],
  ["Document admission interval",jdtls.lifecycle.documentAdmissionMs,jvmd.lifecycle.documentAdmissionMs],
  ["Process → first correct completion",jdtls.coldEndToEnd.firstCorrectResultMs,jvmd.coldEndToEnd.firstCorrectResultMs],
  ["Process → first completion (diagnostic timing)",jdtls.coldEndToEnd.diagnosticMs,jvmd.coldEndToEnd.diagnosticMs],
];
const query=[
  ["completion","first_use",displayTiming(jdtls,"firstUse"),displayTiming(jvmd,"firstUse"),
    completionCorrect(jdtls,"firstUse")?"correct":"incorrect",
    completionCorrect(jvmd,"firstUse")?"correct":"incorrect"],
  ["completion","steady",displayTiming(jdtls,"steady"),displayTiming(jvmd,"steady"),
    completionCorrect(jdtls,"steady")?"correct":"incorrect",
    completionCorrect(jvmd,"steady")?"correct":"incorrect"],
];
const memory=[
  ["Pre-document admission RSS",jdtls.lifecycle.memory.pre_document_admission.totalKb,jvmd.lifecycle.memory.pre_document_admission.totalKb],
  ["After first-use RSS",jdtls.lifecycle.memory.post_first_use.totalKb,jvmd.lifecycle.memory.post_first_use.totalKb],
  ["After steady RSS",jdtls.lifecycle.memory.post_steady.totalKb,jvmd.lifecycle.memory.post_steady.totalKb],
  ["Steady peak RSS",jdtls.lifecycle.memory.steady_peak.totalKb,jvmd.lifecycle.memory.steady_peak.totalKb],
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
  "JDTLS ServiceReady and JVMD machine-index-ready are intentionally shown as separate native milestones; no equivalence is claimed. Warmup samples are retained in each raw report and excluded from steady p50/p95. An incorrect result is never treated as equivalent performance; its timing is diagnostic only.",
  "",
  "Document admission boundary — JDTLS: "+jdtls.lifecycle.documentAdmission.boundary+"; JVMD: "+jvmd.lifecycle.documentAdmission.boundary+".",
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
