import assert from "node:assert/strict";
import { LspScenarioHarness } from "../harness/LspScenarioHarness.ts";

type CompletionResponse = { items?: any[] } | any[] | null;

export default class CompletionScenario extends LspScenarioHarness {
  readonly id = "CMP-01";\n  readonly name = "Complete and resolve a candidate";

  protected async scenario() {
    const receiver = await this.open(
      "impl/maven-core/src/main/java/org/apache/maven/project/MavenProject.java",
    );
    const caller = await this.open(
      "impl/maven-core/src/main/java/org/apache/maven/project/DefaultMavenProjectHelper.java",
    );

    const marker = "/*BENCH_CURSOR*/";
    const callerWithProbe = insertBeforeLastBrace(caller.text, `
    private void benchmarkCompletion(MavenProject project) {
        project.${marker}
    }
`);

    const offset = callerWithProbe.indexOf(marker);
    assert(offset >= 0);
    const before = callerWithProbe.slice(0, offset).split("\n");
    const position = { line: before.length - 1, character: before.at(-1)!.length };

    this.change(caller.uri, callerWithProbe.replace(marker, ""));

    const completion = () => this.request<CompletionResponse>("textDocument/completion", {
      textDocument: { uri: caller.uri },
      position,
      context: { triggerKind: 2, triggerCharacter: "." },
    });

    const first = await this.measure(completion, normaliseCompletion);
    const repeated = await this.measure(completion, normaliseCompletion);

    this.change(
      receiver.uri,
      insertBeforeLastBrace(receiver.text, "    public void benchmarkAddedMethod() {}\n"),
    );

    const afterUnsavedEdit = await this.measure(completion, normaliseCompletion);

    return { first, repeated, afterUnsavedEdit };
  }
}

function insertBeforeLastBrace(source: string, text: string) {
  const end = source.lastIndexOf("}");
  assert(end >= 0);
  return source.slice(0, end) + "\n" + text + source.slice(end);
}

function normaliseCompletion(response: CompletionResponse) {
  const items = Array.isArray(response) ? response : response?.items ?? [];
  return items
    .map(item => ({
      label: item.label,
      kind: item.kind ?? null,
      insertText: item.textEdit?.newText ?? item.insertText ?? item.label,
    }))
    .sort((a, b) =>
      a.label.localeCompare(b.label) ||
      String(a.kind).localeCompare(String(b.kind)) ||
      a.insertText.localeCompare(b.insertText)
    );
}
