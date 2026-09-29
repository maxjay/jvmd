# JVMD roadmap

How much of an IDE's Java language server JVMD covers, how correct it is, and what it
costs, measured against JDTLS (the server behind VS Code's Java extension).

The suite runs 175 editor scenarios from the 28 families in
`JDTLS_API_and_Test_Scenarios.xlsx`: standard LSP plus the `java.*` API that vscode-java
calls. Each scenario checks the exact answer against a small Java fixture, including
whether it reflects the latest edit, and records latency and allocation per request.

It is a progress report, not a gate. CI never fails on its results.

## Output

The runner prints a progress line per scenario, then a readout:

```text
jvmd roadmap
573205d2 · jdk 25.0.4.1 · 4 cpu · 1 run

  jvmd   ███████▒░░░░░░░░░░░░░░░░░░░░░░   42/175   24%   128 missing · 5 attention
  jdtls  █████████████████████████▒▒▒▒▒  147/175   84%

attention
  case                             result   detail
  DIA-01/provider-edit             timeout  no correct textDocument/publishDiagnostics after provider changed
  ENV-02/class-content             wrong    textDocument/definition: dependency definition must identify one target
```

`report.txt` adds per-family progress, the endpoints JVMD doesn't implement yet (ranked
by how many scenarios each unblocks), latency and allocation next to JDTLS, and where
JDTLS itself falls short. `report.md` is the same text for GitHub, with the long
sections folded. `results.json` has every request.

In CI the readout goes to the job summary and to one PR comment kept up to date. PRs
also get a `since main` section: scenarios fixed (`+`), regressed (`-`, also raised as
a warning on the run), and latency or allocation moves over 25% (`▲` worse, `▼` better).

## Locally

```sh
mvn -B -DskipTests install && bash jvmd-dist/assemble.sh
node benchmarks/roadmap/run.ts --output /tmp/roadmap --java-home "$JAVA_HOME" --jdtls-home "$JDTLS_HOME"
```

| Option | |
|---|---|
| `--servers jvmd` | Skip JDTLS. |
| `--only CMP-01,NAV-02/references-true` | Families or single scenarios. `--list` prints them. |
| `--runs 3` | Repeat everything for steadier numbers. |
| `--baseline old/results.json` | Compare with an earlier run. |
| `--alternate-java-home`, `--gradle-home`, `--protoc`, `--protobuf-java` | Tools a few scenarios need (JDK switching, protobuf generation). |

`node --test benchmarks/roadmap/test/*.test.ts` tests the harness against a scripted fake
server; no real servers needed.

## How it measures

- **Not implemented** is decided by JVMD itself: a capability or command it doesn't
  advertise, or a "method not found" reply to a `java/...` request. Implement the
  endpoint and its scenarios start checking answers, with no change here.
- Every scenario gets a fresh server and a fresh copy of its fixture. Setup isn't timed.
- Both servers run on the same JDK with a 1 GB heap.
- Allocation comes from a small agent (`harness/agent`) in each server JVM that reports
  total allocated bytes before and after a request. It counts the whole JVM, so
  background work is included. For JVMD it covers the daemon, not child `javac` or
  resolver processes. JVMD runs on the full JDK here, since its packaged image leaves out
  `jdk.management`.
- *After change* latency is the time from an edit to the first correct answer.
