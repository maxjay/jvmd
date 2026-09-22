import test from "node:test";
import assert from "node:assert/strict";
import { renderDashboard } from "./dashboard.mjs";

test("failed operations and unavailable attribution are explicit and escaped", () => {
  const html = renderDashboard({
    schema: 2,
    provenance: { build: { revision: "abc" } },
    verification: { complete: false },
    aggregation: "samples",
    scope: "server memory",
    rows: [
      {
        operation: "definition",
        targets: ["<target>"],
        state: "first",
        server: "jvmd",
        mode: "comparison",
        outcomes: { wrong: 1 },
        samples: 0,
      },
    ],
    invocations: [
      {
        directory: "worker",
        outcome: "failed",
        actions: [
          {
            id: "query",
            operation: "definition",
            target: "0",
            state: "first",
            outcome: "wrong",
            result: "<unsafe>",
          },
        ],
      },
    ],
  });
  assert.match(html, /wrong/);
  assert.match(html, /unavailable/);
  assert.match(html, /not attributed/);
  assert.match(html, /&lt;target&gt;/);
  assert.match(html, /&lt;unsafe&gt;/);
  assert.doesNotMatch(html, /0.000 ms/);
});
test("rejects superseded result schema", () =>
  assert.throws(() => renderDashboard({ schema: 1 }), /schema 2/));
