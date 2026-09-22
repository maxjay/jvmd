# Prepared JVMD / JDTLS language services

This suite compares normal production LSP requests after both servers have prepared
the same sources, compiler settings, local library relationship and attached dependency
sources. It is a **server comparison**, not an editor or UI benchmark.

`run.py` is the only execution path. `verify.py` is the independent source oracle used
both during execution and on archived responses. `summarize.py` and `dashboard.mjs`
produce the report from those same checked records. Failed operations remain visible
and do not contribute successful latency samples. There are no timing gates.

## Build and run

Use Linux with JDK 25.0.4.1+1, Node 24.21.0 and JDTLS 1.61.0
(`jdt-language-server-1.61.0-202609031315.tar.gz`, SHA-256
`338e7e73d61836651ba2453919a0d34fa763eb4e7c03342092309bffb8934c64`).
The [CI workflow](../../.github/workflows/workflow-benchmarks.yml) pins downloads and
builds the resolver/runtime dependencies through the release Maven bootstrap.
Other installations must record their actual versions; do not combine machines or
native Windows and WSL results. The generated project targets Java 17, with Maven
3.9.9 version detection and equivalent Eclipse Java compiler settings.

```sh
mvn -B -DskipTests install
bash jvmd-dist/assemble.sh
python benchmarks/workspaces/compile.py --repo "$PWD" --java-home "$JAVA_HOME" \
  --dependencies "$PWD/jvmd-dist/target/image/lib/jvmd" --output target/bench-build

# Smoke: one process per server, three deterministic targets per operation.
python benchmarks/workspaces/run.py --repo "$PWD" --build target/bench-build/build.json \
  --java-home "$JAVA_HOME" --jdtls "$JDTLS_HOME" \
  --resolvers "$PWD/jvmd-dist/target/image/lib/jvmd/resolvers" \
  --root target/prepared-smoke --runs 1 --samples 2

python benchmarks/workspaces/verify.py target/prepared-smoke target/prepared-smoke/verification.json
python benchmarks/workspaces/summarize.py target/prepared-smoke target/prepared-smoke/summary.json \
  --markdown target/prepared-smoke/summary.md
node benchmarks/workspaces/dashboard.mjs --summary target/prepared-smoke/summary.json \
  --output target/prepared-smoke/dashboard.html
```

Use a **new output directory** for each run. For evidence, use `--runs 5 --samples 20`.
For a separate diagnostic run, use `--mode attribution --runs 1 --samples 20`;
this records JVMD stages and JFR CPU, allocation, wait and GC events. For instrumentation
overhead, use `--overhead --runs 5 --samples 20`: disabled and stage-only JFR workers
alternate order. It uses the same preparation, targets, warm-up and request path.
Do not mix either profiled or stage-only timings into the unprofiled comparison.

`--targets` changes the deterministic target count and `--sources` changes unrelated
workspace source count. `--warmup` controls the explicit equivalent warm-up rounds
(default 2). `--servers jvmd` permits a bounded regression replay through the same
path; it does not constitute a paired comparison.

To test another production revision, compile using the current `compile.py` with
`--repo` pointing at that checkout, then use the same checkout and resulting build
manifest in `run.py`. Source hashes, class hashes, dependencies and production
revision are recorded. Tracing needs an instrumented revision; disabled comparison
can compile the pipe adapter against uninstrumented production. Source correctness
repairs belong in separate PRs, with separate before/after evidence.

## Preparation and boundaries

The fixture has a host and local library with identical `bench:library:1` selection.
JVMD resolves the Maven graph through its normal workspace manifest; JDTLS imports
the equivalent Eclipse project reference and identical Java 17 binary/source JAR.
The library is not precompiled or installed in the local Maven cache. Only the external
fixture dependency is built before the servers start, with deterministic JAR timestamps.
No dependency downloads occur in timed requests. Fixture identity hashes normalize
only the generated root path; dependency JAR and source hashes are also recorded.

Each fresh process opens every request document and waits for clear diagnostics.
A separate `PrepSentinel.ready` hover checks project-aware readiness; JVMD also waits
for the initial normal repository scan. All preparation queries and opened documents
are recorded, including duration outside query timings. No measured target is queried
by the readiness check. Opening documents and collecting diagnostics do prepare
compiler state; prior measured operations can also share caches. “First” means first
request for that operation/target after preparation, not an untouched compiler.

All first requests run before explicit warm-up; repeated samples then use the same
warm-up count for both servers. Process order alternates; target/operation order rotates
across repetitions. Both servers run serially on the same machine. Fresh server state
is distinct from OS filesystem cache, which is not flushed.

Definitions require the exact source file and identifier range. Dependency definitions
must select the pinned source attachment, not decompiled text or another version.
JDTLS `java/classFileContents` supplies supplementary source evidence **outside** the
definition request latency; its duration and response are retained. References must
contain every independently generated occurrence including the declaration. Protocol
partial results are collected before verification. Identifier and exact invocation
ranges are normalized without allowing missing or duplicate occurrences. Hover and
completion check method signatures; ordering and presentation wording may differ.

Failures (`wrong`, `stale`, `incomplete`, `error`, `timed_out`) are retained, and the
runner continues to the remaining targets. A preparation failure leaves operations
explicitly unexecuted. There is no retry-until-correct loop inside timed requests.

## Interpretation and attribution

The table reports operation/target groups, preparation/warm state, correctness and
sample counts. Medians pool correct requests; per-process medians, ranges and failures
remain available. Warm p95 uses nearest rank only with at least 20 samples. First
request p95 is unavailable. Several targets within a process are not independent
process repetitions. A smoke run cannot establish stable tail latency.

Select an actual diagnostic invocation to see its stage spans, input revision, cache
decisions, work counts and matching CPU/allocation/wait evidence. `trace.json` opens
in Perfetto. Parent/child and overlapping spans are inclusive: never sum them, or
add stage percentiles to manufacture a workflow total. CPU/allocated-byte counters
are for the executing platform thread only; virtual-thread and queue counters are
unavailable. JFR samples match the innermost span on the same Java thread and clock;
other-thread samples remain unassigned. Sample weights are allocation estimates,
not retained heap. Wait evidence complements CPU samples.

Linux `/proc` samples report the Java server separately from its Node bridge. RSS is
not heap, and shared pages can be counted twice in an optional combined process sum.
Process-lifetime CPU/RSS includes preparation. GC heap summaries are separate diagnostic
events, not allocation inferred from heap differences. No forced GC is used.

Production instrumentation is opt-in through `RequestScope` and JFR. Disabled paths
use existing execution and avoid event maps/serialization. Attribution is diagnostic;
review overhead on the same operations before relying on detailed timings. Propose
bounded changes only for evidenced stages, with their exact invocation, work counts,
production methods, correctness constraint and verification command. A 10× speedup
to a serial 10 ms stage can save at most 9 ms of that invocation.

## Evidence and maintenance

Generated records belong under ignored `target/` directories or external output roots.
CI uploads sanitized fixture responses, profiles, traces and manifests as
`prepared-server-evidence` with **30-day retention**. Links to CI artifacts expire;
retain needed baselines elsewhere before expiry. Never commit per-run output or upload
private-workspace recordings. Historical evidence must be verified with its pinned
historical harness; the current tree supports one schema, not legacy execution paths.

Run harness regressions with `python -m unittest discover -s benchmarks/workspaces
-p test_harness.py` and `node --test benchmarks/workspaces/dashboard.test.mjs`.
Extend the existing fixture/oracle and report for a demonstrated need; do not add an
adapter framework or another execution path. Runtime/debugger, full-editor lifecycle,
IntelliJ and retained-object studies are outside this suite.
