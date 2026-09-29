# JVMD benchmarks

How well JVMD serves an editor, measured against JDTLS (the server behind VS Code's Java
extension) on Maven projects:

- **Contract**: the LSP endpoints JVMD documents in [docs/integration.md](../docs/integration.md).
  A wrong or late answer here is a JVMD bug.
- **Roadmap**: what an IDE needs that JVMD does not offer over LSP yet, from the 28 scenario
  families in `JDTLS_API_and_Test_Scenarios.xlsx`. Where JVMD already has the feature in its own
  API (for example the call graph through `references`), the report says so.
- **Lifecycle**: a real Maven project (pinned apache/maven): cold machine index, workspace open,
  queries, reconnect to the warm daemon, and daemon restart. JDTLS: cold import and restart.
- **Latency and allocation** for every request, next to JDTLS.

It is a report, never a gate: CI does not fail on its results.

## How it runs JVMD

As users run it. One resident daemon per machine indexes the local Maven repository once. Each
case opens its own Maven project through a fresh `jvmd-lsp` adapter and closes its session
afterwards. JDTLS gets a fresh process and workspace per case. Both use the same JDK, a 1 GB
heap, and the same offline repository.

Every fixture is a plain Maven project (`pom.xml`, `src/main/java`). Dependencies are real
artifacts in the repository, with or without `-sources.jar`. Project changes are made the way a
developer makes them: by editing `pom.xml` on disk.

## Running it

```sh
mvn -B -DskipTests install && bash jvmd-dist/assemble.sh
node benchmarks/run.ts --output /tmp/bench --jdtls-home "$JDTLS_HOME"
```

The first run fills the fixture repository with the pinned Maven plugins, so it needs the network
and `mvn` once. After that everything is offline.

| Option | |
|---|---|
| `--servers jvmd` | JVMD only. |
| `--only ENV-02,CMP-01/first-repeat` | Families or single cases. `--list` prints them. |
| `--project DIR --project-repository DIR` | Add the lifecycle on a real Maven checkout, built into that repository. |
| `--baseline summary.json` | Compare with an earlier run (main). |
| `--reference FILE` | Where JDTLS columns come from when JDTLS isn't run (default `reference/jdtls.json`). |
| `--write-reference FILE` | Add this run's JDTLS results to the reference, by case. |
| `--runs 3` | Repeat for steadier numbers. |
| `--repository DIR` | Reuse a fixture repository between runs. |

The output directory holds `report.md` (the PR comment), `report.txt` (the terminal summary),
`summary.json` (what later runs compare against) and `results.json` with every request.

`node --test benchmarks/test/*.test.ts` tests the harness against a scripted fake server.

## In CI

`jvmd-benchmarks.yml` runs on PRs that touch JVMD or the suite, on `main`, and nightly.

- **JDTLS 1.61.0 is a fixed reference.** It was measured once and checked in as
  `reference/jdtls.json`; CI only ever runs JVMD. A newly added scenario is measured on JDTLS once
  (`--servers jdtls --only <case> --write-reference benchmarks/reference/jdtls.json`), which adds
  its row and leaves the rest alone. Until then it shows "not measured" for JDTLS.
- **PRs** compare with main's last `summary.json`.
- Two lines in the PR's checks list carry the headline numbers and their change since main,
  like `jvmd / scenarios — Contract 38/41 · Roadmap 12/108 (+2) vs main` and
  `jvmd / cold start — Apache Maven: index 24 s · open 3.1 s`. They are always green.
- One PR comment is kept up to date: a headline, what changed since main (with a reproduce
  command for anything broken), then the lifecycle, latency, roadmap and family tables folded
  underneath. Regressions are also raised as warnings on the run.

## Scope

A case is `supported` when its endpoint is in JVMD's documented contract (`harness/contract.ts`),
otherwise `roadmap`. Workbook APIs that belong to JDTLS's Eclipse or Gradle project model
(importing Eclipse projects, editing `.classpath` or JDT preferences, Gradle protobuf generation,
the legacy type hierarchy command) are out of scope, each with its reason in `contract.ts`.

"Not implemented" is decided by JVMD itself: a capability or command it doesn't advertise, or a
"method not found" reply. Implementing an endpoint turns its cases on with no change here.

## Measurement notes

- Allocation comes from a small agent (`harness/agent`) in each server JVM that reports total
  allocated bytes before and after a request. It covers the whole JVM, background work included.
  For JVMD it is the daemon, not child `javac` or resolver processes. The daemon runs on the full
  JDK here because the packaged image omits `jdk.management`.
- *Edit* latency is the time from an edit to the first correct answer. Answers that are wrong at
  first and right later are reported as stale.
- Diagnostics are only accepted for the exact current document version. A server that publishes
  them without a version can't be checked, and is reported that way.
