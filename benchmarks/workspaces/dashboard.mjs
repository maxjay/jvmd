#!/usr/bin/env node
/** Build a dependency-free, self-contained dashboard from checked benchmark output. */
import { readFile, writeFile } from 'node:fs/promises';
import { parseArgs } from 'node:util';

const escape = value => String(value).replace(/[&<>"']/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
})[character]);

export function renderWorkflowDashboard(summary) {
  const value = (number, unit = 'ms') => number === null || number === undefined ? 'unavailable' : `${Number(number).toFixed(2)} ${unit}`;
  const overview = summary.rows.map(row => `<tr><td>${escape(row.action)}</td><td>${escape(row.engine)}</td><td>${escape(row.mode)}</td>
<td>${row.correct} correct / ${row.failures} failed</td><td>${value(row.p50_ms)}</td><td>${value(row.p95_ms)}</td>
<td>${row.processes.length}</td><td>${value(row.min_process_p50_ms)}–${value(row.max_process_p50_ms)}</td></tr>`).join('');
  const invocations = [...summary.invocations].sort((a,b) => (b.api_edit_to_correct_ms||0)-(a.api_edit_to_correct_ms||0)).map(run => {
    const stages = run.trace?.traceEvents || [];
    const actions = run.actions.map(action => `<tr><td>${escape(action.name)}<br><small>${escape(action.id||'')}</small></td><td>${escape(action.outcome)}</td>
<td>${value(action.first_response_ms)}</td><td>${value(action.time_to_correct_ms)}</td><td>${action.retry_count ?? 0}</td>
<td><details><summary>${action.attempts.length} recorded attempts</summary><pre>${escape(JSON.stringify({attempts:action.attempts,resources:action.resources},null,2))}</pre></details></td></tr>`).join('');
    const profiles=run.attribution?.groups||[];
    const stageRows = [...stages].sort((a,b)=>b.dur-a.dur).map(stage => `<tr><td>${escape(stage.name)}</td><td>${escape(stage.args.method)}</td>
<td>${escape(stage.args.invocation||'unassigned')}</td><td>${stage.args.span} / ${stage.args.parent}</td><td>${stage.tid}</td><td>${value(stage.ts/1000)}</td><td>${value(stage.dur/1000)}</td>
<td>${value(stage.args.threadCpuNanos<0?null:stage.args.threadCpuNanos/1e6)}</td><td>${value(stage.args.threadAllocatedBytes<0?null:stage.args.threadAllocatedBytes/1048576,'MiB')}</td>
<td>${escape(stage.args.cache)}</td><td>${escape(JSON.stringify(stage.args.work))}</td><td><details><summary>Matching samples</summary><pre>${escape(JSON.stringify(profiles.filter(p=>p.span===stage.args.span),null,2))}</pre></details></td></tr>`).join('');
    const base=escape(run.directory);
    return `<details><summary>${escape(run.workflow)} — ${escape(run.outcome)} — API edit ${value(run.api_edit_to_correct_ms)}</summary>
<p>${escape(run.boundary)}. ${escape(run.runtime_boundary||'Runtime not measured')}. ${escape(run.error||'')}</p>
<p>Whole invocation CPU ${value(run.resources?.cpu_seconds_observed,'s')}; peak combined RSS ${value(run.resources ? run.resources.peak_rss_bytes/1048576 : null,'MiB')}.
Allocated bytes in comparison: unavailable. Retained heap: unavailable.</p>
<p><a href="${base}/report.json">Raw results</a> · <a href="${base}/fixture/fixture.json">Fixture and expectations</a> · <a href="${base}/command.json">Reproduction command</a> · <a href="${base}/resources.json">Resource scope</a>
${stages.length ? ` · <a href="${base}/trace.json">Perfetto / Chrome timeline</a> · <a href="${base}/profile-events.json">CPU, allocation, GC and waits</a> · <a href="${base}/attribution.json">Samples matched to stages</a>` : ' · Internal stages: not attributed'}</p>
<div class="scroll"><table><thead><tr><th>Action</th><th>Outcome</th><th>First response</th><th>Time to correct</th><th>Retries</th><th>Evidence</th></tr></thead><tbody>${actions}</tbody></table></div>
${stages.length ? `<p>Stages below belong to this invocation. Times use one JVM clock. Parent/child and parallel intervals overlap; durations and inclusive thread counters must not be summed. Queue CPU/allocation and virtual-thread counters are unavailable. Open the standard trace in Perfetto to inspect overlap.</p>
<div class="scroll"><table><thead><tr><th>Stage</th><th>RPC</th><th>Action ID</th><th>Span / parent</th><th>Thread</th><th>Start</th><th>Wall</th><th>Thread CPU</th><th>Thread allocation</th><th>Cache</th><th>Actual work</th><th>Profiles</th></tr></thead><tbody>${stageRows}</tbody></table></div>` : ''}
<details><summary>Routing, correctness and limits</summary><pre>${escape(JSON.stringify({routing:run.routing,verification:run.verification,unmeasured:run.unmeasured,profiles:run.profiles},null,2))}</pre></details></details>`;
  }).join('');
  return `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>JVMD development workflows</title>
<style>body{font:15px/1.5 system-ui;margin:32px;background:#101724;color:#e8edf7}h1{font-size:32px}a{color:#79d1ef}table{border-collapse:collapse;width:100%}td,th{padding:9px;text-align:left;border-bottom:1px solid #344054;vertical-align:top}th{color:#9facbf}details{padding:14px;border:1px solid #344054;margin:12px 0}summary{cursor:pointer;font-weight:600}pre{white-space:pre-wrap;max-height:420px;overflow:auto;font-size:12px}.scroll{overflow:auto}p{max-width:1100px}.failed{color:#ffa188}</style>
<h1>Development actions → verified results → measured work</h1>
<p class="failed">${summary.verification.complete ? 'All included workers independently verified.' : 'Failures or unavailable runs are present. No winner is inferred.'}</p>
<p>${escape(summary.aggregation)} Product results use the actual pinned VS Code. Engine rows are separate. Provider and debug-protocol readiness do not measure visible UI completion.</p>
<div class="scroll"><table><thead><tr><th>Action</th><th>Comparator</th><th>Mode</th><th>Correctness</th><th>p50</th><th>p95</th><th>Processes</th><th>Process p50 range</th></tr></thead><tbody>${overview}</tbody></table></div>
<h2>Select an invocation</h2><p>Slow API-edit invocations appear first. Failed attempts remain visible. Profiled timings are excluded from comparison rows.</p>${invocations}
<h2>Exact provenance</h2><pre>${escape(JSON.stringify(summary.provenance,null,2))}</pre></html>`;
}

export function renderDashboard(comparison, verification, allocation = null) {
  if (comparison.kind === 'workflows') return renderWorkflowDashboard(comparison);
  const collect = (result, suite, selected = () => true) => Object.entries(result?.fixtures || {}).flatMap(([fixture, metrics]) =>
    Object.entries(metrics).filter(([metric]) => selected(metric)).map(([metric, values]) => ({ fixture, suite, metric, ...values })));
  const rows = [
    ...collect(comparison, 'timing', metric => metric !== 'sampled_allocated_mib'),
    ...collect(allocation, 'allocation', metric => ['sampled_allocated_mib', 'peak_rss_mib', 'cpu_seconds'].includes(metric)),
  ];
  if (!rows.length) throw new Error('comparison contains no metrics');
  const faster = rows.filter(row => row.jvmd_over_jdtls !== null && row.jvmd_over_jdtls < 1).length;
  const verified = Number(verification.editor_responses || 0);
  const ranges = Number(verification.ranges || 0);
  const tableRows = rows.map(row => {
    const ratio = row.jvmd_over_jdtls;
    const ratioText = ratio === null ? 'n/a' : `${ratio.toFixed(2)}×`;
    const width = ratio === null ? 0 : Math.min(100, ratio * 50);
    const tone = ratio !== null && ratio <= 1 ? 'good' : 'slow';
    const unit = row.unit || 'ms';
    return `<tr><td><strong>${escape(row.fixture)}</strong></td><td>${escape(row.suite)}</td><td>${escape(row.metric)}</td>`+
      `<td>${Number(row.jvmd).toFixed(2)} ${escape(unit)}</td><td>${Number(row.jdtls).toFixed(2)} ${escape(unit)}</td>`+
      `<td class="ratio ${tone}"><span style="width:${width.toFixed(1)}%"></span>${ratioText}</td></tr>`;
  }).join('\n');
  const componentSection = (semantic, title) => semantic ? `<section><h2>${escape(title)}</h2>
<p class="note">${escape(semantic.interpretation)}</p>
<p class="note">Base <code>${escape(semantic.base_sha)}</code> · candidate <code>${escape(semantic.head_sha)}</code></p>
<div class="table"><table><thead><tr><th>Metric</th><th>Unit</th><th>Base JVMD</th><th>Candidate JVMD</th><th>After / before</th><th>Repetitions</th></tr></thead><tbody>
${semantic.rows.map(row => `<tr><td>${escape(row.metric)}</td><td>${escape(row.unit)}</td><td>${Number(row.before).toFixed(3)}</td><td>${Number(row.after).toFixed(3)}</td><td>${row.after_over_before === null ? 'n/a' : Number(row.after_over_before).toFixed(3)}</td><td>${Number(row.repetitions)}</td></tr>`).join('\n')}
</tbody></table></div></section>` : '';
  const semanticSection = componentSection(comparison.semantic_state, 'JVMD semantic state: base versus candidate') + componentSection(comparison.input_validation, 'JVMD input validation: base versus candidate');
  const generated = new Date().toISOString();
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>JVMD review benchmark</title><style>
:root{color-scheme:dark;--bg:#0b1020;--panel:#151c31;--line:#293552;--text:#f3f6ff;--muted:#9eabc7;--cyan:#58d5e8;--green:#72e6a6;--orange:#ffb86b}*{box-sizing:border-box}
body{margin:0;background:radial-gradient(circle at 15% 0,#172a4a 0,transparent 38%),var(--bg);color:var(--text);font:14px/1.5 ui-sans-serif,system-ui,sans-serif}.wrap{max-width:1120px;margin:auto;padding:48px 20px}header{margin-bottom:28px}h1{font-size:clamp(28px,5vw,48px);margin:0;letter-spacing:-.04em}header p,.note{color:var(--muted)}.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:12px;margin:24px 0}.card{background:linear-gradient(145deg,#18223a,var(--panel));border:1px solid var(--line);border-radius:14px;padding:18px}.card b{display:block;font-size:26px;color:var(--cyan)}.card small{color:var(--muted);text-transform:uppercase;letter-spacing:.08em}.table{overflow:auto;background:var(--panel);border:1px solid var(--line);border-radius:14px}table{width:100%;border-collapse:collapse;min-width:720px}th,td{text-align:right;padding:11px 14px;border-bottom:1px solid var(--line)}th{color:var(--muted);font-size:12px;text-transform:uppercase}th:first-child,th:nth-child(2),th:nth-child(3),td:first-child,td:nth-child(2),td:nth-child(3){text-align:left}.ratio{position:relative;min-width:130px}.ratio span{position:absolute;left:8px;top:25%;height:50%;border-radius:4px;opacity:.18;background:currentColor}.good{color:var(--green)}.slow{color:var(--orange)}footer{margin-top:18px;color:var(--muted);font-size:12px}code{color:var(--cyan)}
</style></head><body><main class="wrap"><header><p>MERGE-READINESS EVIDENCE</p><h1>JVMD <span style="color:var(--cyan)">vs JDTLS</span></h1><p>Both servers ran the same black-box LSP workload. Lower ratios are better.</p></header>
<section class="cards"><div class="card"><small>Verified responses</small><b>${verified.toLocaleString()}</b></div><div class="card"><small>Validated source ranges</small><b>${ranges.toLocaleString()}</b></div><div class="card"><small>JVMD wins</small><b>${faster} / ${rows.length}</b></div><div class="card"><small>Workers checked</small><b>${Number(verification.workers || 0)}</b></div></section>
<div class="table"><table><thead><tr><th>Fixture</th><th>Suite</th><th>Metric</th><th>JVMD</th><th>JDTLS</th><th>JVMD / JDTLS</th></tr></thead><tbody>${tableRows}</tbody></table></div>
<p class="note">Correctness includes complete protocol traces, dependency identity agreement, definitions, references, rename edits, and source ranges. Latency is milliseconds, CPU is seconds, and memory/I/O are MiB. Sampled allocation is estimated allocated bytes, not retained heap.</p>
${semanticSection}
<footer>Generated ${escape(generated)} · candidate <code>${escape(comparison.candidate)}</code> · baseline <code>${escape(comparison.baseline)}</code></footer></main></body></html>`;
}

async function main() {
  const { values } = parseArgs({ options: {
    comparison: { type: 'string' }, verification: { type: 'string' }, allocation: { type: 'string' }, output: { type: 'string' }
  }});
  for (const name of ['comparison', 'verification', 'output']) if (!values[name]) throw new Error(`--${name} is required`);
  const [comparison, verification, allocation] = await Promise.all([
    readFile(values.comparison, 'utf8').then(JSON.parse), readFile(values.verification, 'utf8').then(JSON.parse),
    values.allocation ? readFile(values.allocation, 'utf8').then(JSON.parse) : null,
  ]);
  await writeFile(values.output, renderDashboard(comparison, verification, allocation));
  process.stdout.write(JSON.stringify({ output: values.output, fixtures: Object.keys(comparison.fixtures || {}).length })+'\n');
}

if (process.argv[1] && import.meta.url === new URL(`file://${process.argv[1]}`).href) main();
