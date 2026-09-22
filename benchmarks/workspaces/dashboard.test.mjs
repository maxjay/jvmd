import test from 'node:test';
import assert from 'node:assert/strict';
import { renderDashboard, renderWorkflowDashboard } from './dashboard.mjs';

test('workflow dashboard exposes failures and unavailable metrics without inventing profiles', () => {
  const html=renderWorkflowDashboard({provenance:{build:{revision:'abc',dirty:false}},verification:{complete:false},
    aggregation:'separate processes',rows:[{action:'run_output',engine:'vscode-jvmd',mode:'comparison',correct:0,failures:1,processes:[{}]}],
    invocations:[{workflow:'runtime-0',outcome:'timed-out',directory:'worker',fixture:'../fresh/fixture/fixture.json',
      actions:[{name:'run_output',outcome:'wrong',attempts:[{output:'<unsafe>'}]}]}]});
  assert.match(html,/0 correct \/ 1 failed/);
  assert.match(html,/unavailable/);
  assert.match(html,/not measured/);
  assert.match(html,/&lt;unsafe&gt;/);
  assert.match(html,/worker\/\.\.\/fresh\/fixture\/fixture.json/);
  assert.doesNotMatch(html,/0\.00 MiB/);
});

test('renders comparison and correctness evidence safely', () => {
  const comparison = { candidate: 'after', baseline: 'jdtls-shared', fixtures: {
    '<small>': { hover: { jvmd: 2, jdtls: 4, jvmd_over_jdtls: .5 } }
  }};
  const html = renderDashboard(comparison, { workers: 2, editor_responses: 42, ranges: 18 });
  assert.match(html, /42/);
  assert.match(html, /0\.50×/);
  assert.match(html, /&lt;small&gt;/);
  assert.doesNotMatch(html, /<td><strong><small>/);
});

test('rejects an empty comparison', () => {
  assert.throws(() => renderDashboard({ fixtures: {} }, {}), /no metrics/);
});

test('labels timing and sampled allocation with explicit units', () => {
  const timing = { candidate: 'after', baseline: 'jdtls-shared', fixtures: {
    review: { hover: { jvmd: 2, jdtls: 4, jvmd_over_jdtls: .5, unit: 'ms' } }
  }};
  const allocation = { fixtures: { review: {
    sampled_allocated_mib: { jvmd: 12, jdtls: 30, jvmd_over_jdtls: .4, unit: 'MiB' }
  }}};
  const html = renderDashboard(timing, {}, allocation);
  assert.match(html, /2\.00 ms/);
  assert.match(html, /12\.00 MiB/);
  assert.match(html, />allocation</);
});

test('keeps JVMD component measurements out of the JDTLS win count', () => {
  const comparison = { candidate: 'after', baseline: 'jdtls-shared', fixtures: {
    review: { hover: { jvmd: 2, jdtls: 4, jvmd_over_jdtls: .5, unit: 'ms' } }
  }, semantic_state: { base_sha: 'base', head_sha: 'head', interpretation: '<separate>', rows: [
    { metric: 'Cold <lookup>', unit: 'ms', before: 10, after: 20, after_over_before: 2, repetitions: 3 }
  ] }};
  const html = renderDashboard(comparison, {});
  assert.match(html, /JVMD wins<\/small><b>1 \/ 1/);
  assert.match(html, /Base JVMD/);
  assert.match(html, /Cold &lt;lookup&gt;/);
  assert.match(html, /2\.000/);
  assert.match(html, /&lt;separate&gt;/);
});
