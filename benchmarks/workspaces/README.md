# Benchmarks

The **Benchmarks** check and one updating PR comment show the same table.
Correctness gates the check; timing changes are advisory. Green/red indicators
mean lower/higher latency, not a statistically established improvement/regression.

## CI

Relevant PR updates measure only the PR's JVMD revision: five processes, three
fixed targets, two warm-up rounds and 20 warm rounds. Superseded runs are cancelled.
Main runs publish reusable baselines. No baseline server is rebuilt in a PR run.

The baseline is the nearest compatible successful main result within the first
100 commits of the PR base history and latest 100 successful main benchmark runs.
Fixture, harness, dependency, JDK/Node, runner image/CPU and sampling configuration
must match. Missing, expired or incompatible data shows **Baseline unavailable**.

Preparation uses normal production paths and completes before query timing:
open documents, clear diagnostics and an unrelated sentinel. Readiness does not
query timed targets. First requests and explicitly warmed requests have separate
rows. Exact source locations, complete references and current signatures are
checked independently. Wrong/stale/incomplete responses never count as successes.
Warm p95 requires at least 20 samples; first-request p95 is unavailable.

A separate trusted `workflow_run` publisher reads only bounded JSON from the
read-only measurement job. It never checks out or executes PR code. It records
an immutable completed check for each run/attempt and updates the existing PR
comment only if the head and run are still current. Build failures and missing
results produce a failing check rather than preserving an old passing table.

GitHub activates this publisher only once its workflow is on **main**. After
initial merge, the main benchmark establishes the first baseline; subsequent
PR runs publish their comparison automatically. Until activation, the measurement
job still runs and verifies results, but automatic PR publication is unavailable.

## Read results without downloads

Results live in GitHub Checks, not tracked files, branches or release assets.
The table's **Result JSON** link points to `GET /repos/maxjay/jvmd/check-runs/{id}`.
`output.text` contains the schema-2 JSON inside its `json` fenced block, including
rows, deltas, baseline revision, correctness, configuration and per-process data.

For the latest PR, obtain `head.sha` from `GET /repos/maxjay/jvmd/pulls/{number}`,
then call `GET /repos/maxjay/jvmd/commits/{sha}/check-runs?check_name=Benchmarks&filter=all`.
Use the greatest `(run_id, attempt)` from the records, including running/failed
results. Do not substitute the latest successful result for the current run.

GitHub [retains check data for 400 days, then deletes it after a 10-day archive period](https://docs.github.com/en/pull-requests/reference/status-checks#retention-of-checks).
The small transfer artifact expires after seven days; it is internal transport,
not the reporting interface. Agents normally read the Checks API, not artifacts.

## Local and diagnostic runs

Use JDK 25.0.4.1+1 and Node 24.21.0; CI pins their installation and Maven bootstrap.

```sh
mvn -B -DskipTests install
bash jvmd-dist/assemble.sh
python benchmarks/workspaces/compile.py --repo "$PWD" --java-home "$JAVA_HOME" \
  --dependencies "$PWD/jvmd-dist/target/image/lib/jvmd" --output target/bench-build
python benchmarks/workspaces/run.py --repo "$PWD" --build target/bench-build/build.json \
  --java-home "$JAVA_HOME" --resolvers "$PWD/jvmd-dist/target/image/lib/jvmd/resolvers" \
  --servers jvmd --root target/bench-run --runs 1 --samples 2
python benchmarks/workspaces/summarize.py target/bench-run target/result.json
python -m unittest discover -s benchmarks/workspaces -p 'test_*.py'
```

Use a new ignored output directory each time. `run.py` and `verify.py` remain the
single execution and correctness paths. Production regressions use the existing
Maven/Checkpoints test suite, not a separate benchmark test launcher.

Manual workflow modes: `jdtls` compares actual prepared JVMD/JDTLS servers;
`attribution` records JVMD stages/JFR; `overhead` compares tracing disabled/enabled.
JDTLS is pinned to 1.61.0 with matching local-library and dependency-source setup.
These modes do not run on ordinary PR updates. They are not full-editor benchmarks.

Locally, use `--servers jvmd jdtls --jdtls "$JDTLS_HOME"`, `--mode attribution`, or
`--overhead`. Diagnostic trace/profile exports are available for seven days in
manual-run artifacts. Standard `trace.json` opens in Perfetto; `attribution.json`
links samples to spans and production methods. Do not sum nested/overlapping spans
or treat sampled allocations as retained heap. Server and bridge CPU/RSS are
separate; process totals include preparation. No forced GC is used.
