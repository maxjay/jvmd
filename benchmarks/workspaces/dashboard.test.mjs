import test from 'node:test';
import assert from 'node:assert/strict';
import { renderDashboard } from './dashboard.mjs';

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
