#!/usr/bin/env node
/** The prepared-server report, with invocation-level standard trace/profile links. */
import { readFile, writeFile } from "node:fs/promises";
import { parseArgs } from "node:util";
const escape = (value) =>
  String(value).replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const value = (n) => (n == null ? "unavailable" : Number(n).toFixed(3));
const details = (title, data) =>
  `<details><summary>${escape(title)}</summary><pre>${escape(JSON.stringify(data, null, 2))}</pre></details>`;

export function renderDashboard(summary) {
  if (summary.schema !== 2)
    throw new Error("Expected prepared-server schema 2");
  const table = (rows) =>
    `<div class="scroll"><table><thead><tr><th>Operation / targets</th><th>Prepared state</th><th>Server / mode</th><th>Outcomes</th><th>p50 ms</th><th>p95 ms</th><th>Successful samples</th><th>Process medians min–max ms</th></tr></thead><tbody>${rows.map((r) => `<tr><td>${escape(r.operation)} / ${escape(r.targets.join(","))}</td><td>${escape(r.state)}</td><td>${escape(r.server)} / ${escape(r.mode)}</td><td>${escape(JSON.stringify(r.outcomes))}</td><td>${value(r.p50_ms)}</td><td>${value(r.p95_ms)}</td><td>${r.samples}</td><td>${r.process_p50_range_ms?.map(value).join("–") || "unavailable"}${details("Per-process samples", r.processes)}</td></tr>`).join("")}</tbody></table></div>`;
  const revision = summary.provenance.build.revision;
  const runs = summary.invocations
    .map((run) => {
      const base = escape(run.directory);
      const actions = [...run.actions]
        .sort((a, b) => (b.latency_ms || 0) - (a.latency_ms || 0))
        .map((a) => {
          const spans = a.stages || [];
          const methods = Object.fromEntries(
            (a.profile_groups || []).flatMap((g) =>
              Object.entries(g.methods || {}),
            ),
          );
          const links = Object.entries(methods)
            .map(
              ([name, m]) =>
                `<a href="https://github.com/maxjay/jvmd/blob/${escape(revision)}/${escape(m.source)}${m.line > 0 ? "#L" + m.line : ""}">${escape(name)}</a>`,
            )
            .join("<br>");
          return `<details id="${escape(a.id)}"><summary>${escape(a.operation)} / ${escape(a.target)} — ${escape(a.state)} — ${escape(a.outcome)} — ${value(a.latency_ms)} ms</summary><p>Invocation ${escape(a.id)}. RPC union ${value(a.rpc_union_ms)} ms; queue union ${value(a.queue_union_ms)} ms.</p>${details("Response and supplementary correctness evidence", { result: a.result, source_evidence: a.source_evidence, error: a.error })}${spans.length ? details("Actual stages: nesting, thread counters and work", spans) : "<p>Internal stages: not attributed.</p>"}${links}${details("Sampled CPU/allocation and observed waits", a.profile_groups || "not attributed")}</details>`;
        })
        .join("");
      return `<details><summary>${base} — ${escape(run.outcome)} — preparation ${value(run.preparation?.process_start_to_ready_ms)} ms</summary><p>${escape(run.error || run.close_error || run.profile_error || "")}</p><p><a href="${base}/report.json">Raw invocations</a> · <a href="${base}/fixture.json">Independent oracle</a> · <a href="${base}/command.json">Server command</a> · <a href="${base}/resources.json">Server/bridge resources</a>${run.profiles ? ` · <a href="${base}/trace.json">Perfetto timeline</a> · <a href="${base}/attribution.json">Attribution</a> · <a href="${base}/profile-events.json">CPU/allocation/wait/GC events</a>` : ""}</p>${details("Preparation duration, queries and configuration", run.preparation)}${details("Process resources (includes preparation)", run.resources)}${actions}</details>`;
    })
    .join("");
  return `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Prepared JVMD / JDTLS</title><style>body{font:15px/1.5 system-ui;margin:30px;color:#e8edf7;background:#101724}a{color:#79d1ef}td,th{padding:9px;border-bottom:1px solid #344054;text-align:left;vertical-align:top}table{border-collapse:collapse;width:100%}details{border:1px solid #344054;padding:12px;margin:10px 0}summary{cursor:pointer}pre{white-space:pre-wrap;max-height:420px;overflow:auto;font-size:12px}.scroll{overflow:auto}</style><h1>Prepared JVMD / JDTLS language services</h1><p>${summary.verification.complete ? "All required invocations passed." : "Failures or unmeasured requests are present; successful latency excludes them."}</p><p>${escape(summary.aggregation)}</p>${table(summary.rows.filter((r) => r.mode === "comparison"))}<h2>Separate attribution and instrumentation overhead</h2>${table(summary.rows.filter((r) => r.mode !== "comparison"))}<p>${escape(summary.scope)} Inclusive nested/overlapping spans and thread counters must not be summed. Virtual-thread counters are unavailable. CPU/allocation samples are statistical; an empty sample set does not prove zero cost.</p><h2>Invocations (slow requests first within each process)</h2>${runs}${details("Independent verification", summary.verification)}${details("Exact revision and reproduction command", summary.provenance)}</html>`;
}

if (
  process.argv[1] &&
  import.meta.url === new URL(`file://${process.argv[1]}`).href
) {
  const { values } = parseArgs({
    options: { summary: { type: "string" }, output: { type: "string" } },
  });
  if (!values.summary || !values.output)
    throw new Error("--summary and --output are required");
  await writeFile(
    values.output,
    renderDashboard(JSON.parse(await readFile(values.summary, "utf8"))),
  );
}
