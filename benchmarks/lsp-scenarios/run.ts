import { LspScenarioHarness } from "./harness/LspScenarioHarness.ts";
import CompletionScenario from "./scenarios/CMP-01-completion.ts";

await LspScenarioHarness.beforeAll();
try {
  for (const Scenario of [CompletionScenario]) {
    await new Scenario().execute();
  }
} finally {
  await LspScenarioHarness.afterAll();
}
