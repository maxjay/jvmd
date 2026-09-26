import { LspScenarioHarness } from "./harness/LspScenarioHarness.ts";
import CompletionScenario from "./scenarios/CMP-01-completion.ts";
import { main } from "./suite.ts";

if(process.argv.length>2){
  await main();
}else{
await LspScenarioHarness.beforeAll();
try {
  for (const Scenario of [CompletionScenario]) {
    await new Scenario().execute();
  }
} finally {
  await LspScenarioHarness.afterAll();
}
}
