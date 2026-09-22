#!/usr/bin/env node
/** Build a dependency-free, self-contained dashboard from checked benchmark output. */
import { readFile, writeFile } from 'node:fs/promises';
import { parseArgs } from 'node:util';

const escape = value => String(value).replace(/[&<>"']/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
})[character]);

export function renderDashboard(comparison, verification, allocation = null) {
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
  process.stdout.write(JSON.stringify({ output: values.output, fixtures: Object.keys(comparison.fixtures).length })+'\n');
}

if (process.argv[1] && import.meta.url === new URL(`file://${process.argv[1]}`).href) main();
