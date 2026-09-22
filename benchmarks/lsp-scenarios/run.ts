import { LspScenarioHarness } from "./harness.ts";
import CompletionScenario from "./completion.ts";

await LspScenarioHarness.beforeAll();
try {
  for (const Scenario of [CompletionScenario]) {
    await new Scenario().execute();
  }
} finally {
  await LspScenarioHarness.afterAll();
}
