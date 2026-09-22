# Development workflow benchmarks

`workflows.py` extends this harness's generated workspaces, protocol client,
resource sampler, independent verifier, build provenance and dashboard. It runs
development actions through actual VS Code providers or the existing engine
transport. `run.py`, `modules.py` and `prefix.py` remain **engine comparisons**;
direct JDTLS traffic is not a measurement of the complete VS Code product.

The product fixture is a host reactor (application, dependent and independent
modules) and a library in separate Git repositories. Both products resolve the
same local Maven coordinates. An external dependency includes its matching
source JAR. Fixture generation, Git commits and JAR timestamps are deterministic.
The ordinary-Java fixture requires no processors or resource generators.

## Commands and boundaries

Build with the `compile.py` command below, or use `--reuse-production-jars` after
assembling JVMD. Define `BENCH_JDK`, `BENCH_BUILD`, `JVMD_RESOLVERS`, `VSCODE`,
`JAVA_EXTENSIONS`, `MAVEN_CACHE` and a new `BENCH_RESULTS` directory. The prepared
cache is inventoried with JAR/POM hashes and copied equally before timing; local fixture coordinates are removed
from that copy. Maven is offline during measurement. Do not precompile fixture
sources to conceal import or runtime compilation costs.

```sh
# Small deterministic correctness smoke. Linux requires a working display/Xvfb.
xvfb-run -a python benchmarks/workspaces/workflows.py --repo "$PWD" \
  --build "$BENCH_BUILD/build.json" --java-home "$BENCH_JDK" \
  --resolvers "$JVMD_RESOLVERS" --vscode "$VSCODE" \
  --extensions "$JAVA_EXTENSIONS" --dependency-cache "$MAVEN_CACHE" \
  --root "$BENCH_RESULTS" --workflow language --smoke

# Evidence: use the same arguments, a new output directory, and replace
# --smoke with --runs 5 --samples 20 --reopen. Products run serially in rotating order.
# Runtime/debug/hot swap: --workflow runtime (independent fresh process).
# Prefix/backspace, references, rename preview and revert: --workflow coverage.
# Coverage also checks the external source JAR and its declaration range.
# Scaling: --sources 128 increases the independent fixture source population.
# Typing/navigation before readiness: --workflow background. A separate JVMD
# attribution run records whether requests actually overlap index.scan spans.

# Attribution: same action, separate process; never headline comparison timings.
# Add --engines vscode-jvmd --mode attribution, using a new output directory.

# Retained-view and multiple-workspace diagnostics, explicitly engine-only.
python benchmarks/workspaces/workflows.py --repo "$PWD" \
  --build "$BENCH_BUILD/build.json" --java-home "$BENCH_JDK" \
  --resolvers "$JVMD_RESOLVERS" --root "$RETENTION_RESULTS" \
  --engines engine-jvmd --mode retention --runs 1 --samples 5 \
  --edits 20 --workspaces 3

# Alternating instrumentation-disabled/stage-JFR-enabled process pairs.
python benchmarks/workspaces/workflows.py --repo "$PWD" \
  --build "$BENCH_BUILD/build.json" --java-home "$BENCH_JDK" \
  --resolvers "$JVMD_RESOLVERS" --root "$OVERHEAD_RESULTS" \
  --engines engine-jvmd --overhead --runs 5 --samples 20

python benchmarks/workspaces/verify.py "$BENCH_RESULTS" \
  "$BENCH_RESULTS/verification.json" --workflows
python benchmarks/workspaces/summarize.py "$BENCH_RESULTS" \
  "$BENCH_RESULTS/summary.json" --workflows
node benchmarks/workspaces/dashboard.mjs \
  --comparison "$BENCH_RESULTS/summary.json" \
  --verification "$BENCH_RESULTS/verification.json" \
  --output "$BENCH_RESULTS/dashboard.html"
```

The CI workflow pins VS Code 1.104.2, Red Hat Java 1.47.0, Java Debug 0.58.4,
Java Test 0.43.2, Temurin 25.0.4.1+1, Maven 3.9.16 and Node 24.21.0. Its JDK/editor archives are
hash checked. Reports record extension versions, server JAR hashes, commands,
fixture identities and exact source/build provenance. JVMD engine/adapter JVMs
use a documented 1 GiB maximum heap. Java uses its ordinary extension settings;
normal import, autobuild and debug build-before-launch remain enabled. This is a
Linux configuration; do not combine it with Windows, WSL or other filesystems.
Pull requests run one-process correctness smoke with two warm requests. Dispatch
the workflow with `evidence=true` for five paired processes and 20 warm requests;
the same distinction applies to instrumentation overhead. Timing observations
have no hard CI thresholds. Application-level editor updates are disabled and
loaded extension versions must match the recorded manifests.

The JVMD benchmark extension reuses the shipped RpcClient/LspBridge and existing
`run.start`/`debug.op`. It refuses to run with competing Java providers loaded.
It adds no DAP server. JVMD runtime output is read from the existing bounded
DebugSession buffer by the benchmark executable. Java runtime operations use
the installed debugger's real DAP session. Their readiness boundaries are
recorded separately. Neither provider completion nor debug-protocol readiness
is a measurement of pixels appearing in the editor. JVMD dependent diagnostics
are explicitly requested; Java diagnostics use the normal automatic builder.

For practical project import/readiness, reuse `jvmd-tests/corpus/fetch.sh` and
prepare its dependencies with the pinned Maven dependency-plugin `go-offline`
goal. Run `--workflow project --project jvmd-tests/corpus/petclinic` with both
actual VS Code comparators. The fixture builder checks the corpus script's pin,
rejects tracked modifications and copies sources/resources without `target/`
outputs. Its independent probe requires `Owner.getPets()` completion and the
exact declaration. This scenario measures project readiness, not PetClinic's
server launch or generated-resource correctness.

## Verification and interpretation

Every action retains all attempts, first-response time, time to first correct
result, retry count, timeout and document revision. Fixtures independently
specify signatures, exact source selections/ranges, references, rename edits,
run output and debugger state. Launch acceptance and successful redefine
responses are insufficient: execution must produce the changed marker, and hot
swap must preserve the process identity. Failed runs remain visible and do not
contribute fast correct samples. Summaries use medians of process medians;
within-process p95 requires at least 20 correct samples per process. Five cold
processes do not establish a useful cold p95 or p99.

Comparison mode records external timing and Linux process-tree CPU/RSS samples.
All extension hosts, bridges, servers and observed compiler children are in
scope; debuggee PIDs are reported separately. Sampling can miss short-lived
processes and their final counters. RSS includes native memory and is not live
heap. Per-action resource intervals use an IPC clock-alignment bracket with its
uncertainty, not subtraction of unrelated clock origins. External open-to-ready
includes process startup; driver readiness begins inside the extension host.

Attribution opts into `RequestScope` spans and JFR (`profile`, stack depth 128).
Actor queues record enqueue and execution separately. Startup and background
artifact scans retain the opening workflow's cause, with actual inventory,
hash and parsed-class counts. Detached publication
keeps causal IDs without keeping request memoized values alive. The benchmark
bridge preserves the dispatch cause of deferred callbacks. `trace.json` is a
standard Chrome/Perfetto trace; `profile-events.json` contains selected CPU,
allocation, GC and wait events. `attribution.json` matches samples to the
innermost executing span on the same JFR thread. Unmatched/carrier-thread samples
stay unattributed. Parent/child durations overlap and must not be summed; neither
may stage p95 values be summed to explain an end-to-end p95. Inspect the actual
slow invocation. Javac enter/attribute is combined because this boundary does
not accurately separate those phases. Response encoding covers envelope
conversion, not the entire pipe framing/write operation.

Platform-thread CPU/allocation counters are inclusive and scoped to that thread.
Unavailable virtual-thread or queue counters are `-1`, not zero. JFR allocation
weights are statistical estimates of allocated bytes, never retained heap.
Stage-only overhead runs have a separate recording configuration and label;
they are distinct from both uninstrumented comparison and full profiling.

Retention mode holds bounded leases from the actual WorkspaceBindings path
while making checked edits, releases them, repeats edits, opens additional
workspaces and closes/reopens them. Explicit GC requests occur only in this
diagnostic mode. It reports heap, nonheap, GC and workspace counters separately
from RSS. Selected snapshots include live class counts/shallow bytes and HotSpot
native-memory tracking categories. These exclude object contents; native JNI
allocations may remain untracked. This is not a dominator/retained-size analysis or proof that all memory
will remain bounded over arbitrarily long sessions. The final control workspace
remains open while collecting heap observations.

Known measurement boundaries: there is no IntelliJ adapter, product retained-heap
adapter, processor/resource-generation
scenario, structural-hot-swap scenario or
visible-UI oracle yet. Product `--reopen` preserves the same workspace paths, editor profile and server state, then checks the persisted document revision. Engine persisted-reopen coverage also exists in `run.py`. The report schema
can carry another actual IDE's actions, attempts, resources and provenance;
there is no speculative comparator plugin interface. Unsupported and unexecuted
work must be listed explicitly in the PR and results, not replaced with zeroes.

Background mode starts real typing before readiness while ordinary import and
indexing remain enabled. Attribution reports the union of actual request/index
overlap; a run with no observed overlap does not establish indexing contention.
Competitor internal indexing is not attributed. Source-JAR navigation reports
JVMD's provider location readiness separately from Java's source-content provider;
the benchmark does not add an editor content provider for JVMD.

## Controlled engine comparison

`run.py` exercises the production JVMD Application, Dispatcher, framing, RPC client
and LSP bridge. The only substitute is stdin/stdout for the daemon's Unix socket.
JDTLS uses its usual stdio endpoint. Neither server uses AOT. Both use a 1 GiB heap.
The harness is a black-box LSP client: it does not import, link to, instrument, or
otherwise depend on JDTLS implementation code. JDTLS is only identified by the
distribution passed with `--jdtls`.

Prerequisites: Python 3.11+, JDK 25, Node 24 with native TypeScript support, a
JDTLS distribution, built JVMD dependency JARs and Maven resolver bundles.

```sh
python benchmarks/workspaces/compile.py \
  --repo "$PWD" --java-home "$BENCH_JDK" \
  --dependencies "$JVMD_DEPENDENCIES" --output "$BENCH_BUILD"
python benchmarks/workspaces/run.py \
  --repo "$PWD" --build "$BENCH_BUILD/build.json" --java-home "$BENCH_JDK" \
  --jdtls "$JDTLS_HOME" --resolvers "$JVMD_RESOLVERS" \
  --repository "$BENCH_REPOSITORY" --root "$BENCH_RESULTS"
```

Every output directory must be new. `compile.py` records source, class and
dependency hashes. `run.py` records commands, environment, complete LSP request
and response messages, per-request timings and daemon counters. Workers execute
serially; server order rotates between repetitions. Capture stdout/stderr too.

The default fixture uses unchanged Jackson annotations/core/databind, RocksDB JNI
and SQLite JDBC JARs under local `fixture:dependencyN:1` coordinates. Each needs a
matching minimal POM. Projects are generated with 24 Java files and equivalent
Maven/Eclipse classpaths. Their wrapper properties declare Maven 3; the benchmark
does not execute a Maven build or download dependencies. JDTLS Maven/Gradle import
and automatic builds are disabled. All library bytes and hashes are recorded.
The supplied classpaths must resolve the selected binary type and expression.

There are three distinct workspaces in one resident JVMD process, then a restart
with persisted index state and a fourth new workspace. JDTLS runs in both shared
and separate-process configurations. All startup and upfront indexing time is
reported. The JDTLS readiness check requires all generated source types to be
indexed before accepting clean file diagnostics. Normal diagnostic debounce is
included for both servers. No OS cache flushing is performed.

Each workspace checks source/binary hover and completion, signature help,
definition, references, rename preview and document symbols, first and warm.
Eight distinct unsaved edits also exercise source invalidation, completion and
signature help. An erroneous edit must report `missingValue`; restoration must
clear it. References and rename must cover exactly the generated source count;
renames must contain the requested replacement, and files on disk remain intact.
Dependency search consumes all native pages and checks distinct matching type
identities. Its response payloads differ between servers, so its timing is not a
pure index-engine comparison.

Use `--dependency-type fixture.a0.Type0 --binary-expression marker0
--binary-member marker0 --query Type0 --jdtls-query Type0 --expected-results N`
for the generated index fixture (check its actual package and member names).
`--sources 128` scales source discovery and references. `--profile` enables the
same JFR settings for JVMD and JDTLS; keep profiled runs separate from reported
unprofiled latency medians.

`EditorProfile` is a narrower changing-document workload for attributing compiler
CPU/allocation costs; it is not an end-to-end JVMD/JDTLS comparison. It takes a
source directory, output JFR path and dependency JAR paths. Print JFR stacks with
`jfr print --json --stack-depth 128 --events
jdk.ExecutionSample,jdk.ObjectAllocationSample ...`.

`test.py` compiles repository tests against a recorded build and executes selected
classes without substituting production implementations. Test and profile output
directories contain the exact commands used.

To reproduce the fixture matrix, first compile each revision into a separate
directory, then generate one common fixture repository. All workers are serial.

```sh
python benchmarks/workspaces/fixture.py --build "$BENCH_BUILD/build.json" \
  --dependencies "$JVMD_DEPENDENCIES" --root "$BENCH_FIXTURES"
python benchmarks/workspaces/matrix.py --repo "$PWD" \
  --before "$BENCH_BEFORE/build.json" --after "$BENCH_BUILD/build.json" \
  --java-home "$BENCH_JDK" --jdtls "$JDTLS_HOME" --resolvers "$JVMD_RESOLVERS" \
  --fixtures "$BENCH_FIXTURES" --root "$BENCH_RESULTS"
python benchmarks/workspaces/verify.py "$BENCH_RESULTS" "$BENCH_VERIFICATION"
python benchmarks/workspaces/summarize.py "$BENCH_RESULTS" "$BENCH_SUMMARY"
python benchmarks/workspaces/compare.py "$BENCH_SUMMARY" "$BENCH_COMPARISON" \
  --candidate after --baseline jdtls-shared --markdown "$BENCH_COMPARISON.md"
node benchmarks/workspaces/dashboard.mjs --comparison "$BENCH_COMPARISON" \
  --verification "$BENCH_VERIFICATION" --output "$BENCH_DASHBOARD.html"
python benchmarks/workspaces/added.py --repo "$PWD" \
  --build "$BENCH_BUILD/build.json" --java-home "$BENCH_JDK" \
  --jdtls "$JDTLS_HOME" --resolvers "$JVMD_RESOLVERS" \
  --repository "$BENCH_FIXTURES/multi" --root "$BENCH_ADDED_RESULTS"
python benchmarks/workspaces/profile.py --build "$BENCH_BUILD/build.json" \
  --repository "$BENCH_FIXTURES/real" --root "$BENCH_PROFILE_RESULTS"
```

For a merged-main comparison use `--modes main after jdtls-shared` with main as
`--before`. The larger variant uses `--fixture-names multi --sources 128
--workspaces 2 --samples 3 --runs 2`. Do not reuse an output directory for another
revision or workload; only identical completed matrix workers may be resumed.

Both JDTLS paths enable `classFileContentsSupport`, which is needed for searching
dependency types. The readiness checks distinguish source readiness from full
dependency search readiness. Search retries include all elapsed waiting time and
must return the complete expected identities; an empty fast response is a failure.

The independent verifier also runs against extracted archives: original absolute
file URIs are mapped to the included generated workspace sources. Every timed
editor response must exist in the closed trace, and all definition/reference/rename
ranges are checked against those sources.

`dashboard.mjs` turns only the independently verified comparison artifacts into a
small, self-contained HTML report. It has no package dependencies, performs no
benchmark work itself, and escapes fixture and metric labels before rendering.
On pull-request runs, the merge-review workflow also creates or updates one
`github-actions` comment containing the comparison table and a link to download
the dashboard, traces, resource samples, and verification artifacts.
Performance-tagged JUnit tests are excluded from the deterministic checkpoint
gate. Merge review records their equivalents as observations instead: an
unprofiled timing/resource matrix plus a separate JFR sampled-allocation matrix.
The dashboard labels latency in milliseconds, CPU in seconds, and memory,
allocation, and disk I/O in MiB; sampled allocation is not retained heap.

The merge-review gate uses `fixture.py --fixture-names review` as its correctness
corpus instead of cloning a separate source repository. Its checked oracle covers
192 generated source files and 64 dependency JARs (1,024 binary classes), while the workload checks
the same hover, completion, signature, navigation, references, rename, symbols,
unsaved edits, diagnostics, and dependency identities against JVMD and JDTLS.
Large reference sets use standard LSP partial-result progress and are reassembled
by both the timed client and the independent trace verifier; the workload does not
relax JVMD's 64 KiB editor-response budget.
Every generated fixture records expected dependency identities in `fixture.json`;
the matrix consumes that manifest instead of relying on fixture-name conventions.
The action generates only this corpus and runs servers serially. It does not use a
short timeout or accept an incomplete run: every protocol chunk, worker, and the
independent verifier must complete successfully before the dashboard is produced.

Rewriting the Python coordinator in another language would not materially shorten
this gate: compilation, JVM startup, indexing, and checked LSP requests dominate
the run. The fast path instead downloads JDTLS concurrently with a parallel Maven
build, skips test compilation already covered by the checkpoint action, reuses the
built JVMD JARs rather than compiling production sources twice, excludes the test
module from the reactor, avoids unused fixtures, and keeps one complete correctness
repetition. The full suite remains the place for statistically meaningful performance measurements.

The benchmark harnesses remain the executable source for reproducing workspace comparisons; historical narrative reports were removed during consolidation.

Profiles are separate from the unprofiled latency samples. Only selected CPU/allocation JFR event exports should be published, since a raw JFR can also contain initial environment and system properties.

## Full timing and allocation suite from a branch

`compile.py` compiles the checkout supplied by `--repo` and writes its Git revision,
tree, dirty state, source hashes, dependency hashes, compiler command, and class
hashes to `build.json`. Thus the candidate need only be the currently checked-out
branch; no benchmark code is compiled into the shipped server. Use a second clean
worktree for `--before`, or pass the same build if only JVMD/JDTLS comparison is
needed.

Run the matrix twice. The first run is the authoritative latency/resource run;
the second enables JFR and is the allocation/CPU profile. Profiling perturbs
latency and those timings must not be merged with the first run.

```sh
# Unprofiled wall-clock latency, peak RSS, CPU, disk I/O, correctness and traces.
python benchmarks/workspaces/matrix.py --repo "$PWD" \
  --before "$BENCH_BEFORE/build.json" --after "$BENCH_BUILD/build.json" \
  --java-home "$BENCH_JDK" --jdtls "$JDTLS_HOME" --resolvers "$JVMD_RESOLVERS" \
  --fixtures "$BENCH_FIXTURES" --root "$BENCH_RESULTS"
python benchmarks/workspaces/verify.py "$BENCH_RESULTS" "$BENCH_VERIFICATION"
python benchmarks/workspaces/summarize.py "$BENCH_RESULTS" "$BENCH_SUMMARY"
python benchmarks/workspaces/compare.py "$BENCH_SUMMARY" "$BENCH_COMPARISON" \
  --markdown "$BENCH_COMPARISON.md"

# Separate sampled-allocation run for both servers through the same LSP workload.
python benchmarks/workspaces/matrix.py --repo "$PWD" \
  --before "$BENCH_BEFORE/build.json" --after "$BENCH_BUILD/build.json" \
  --java-home "$BENCH_JDK" --jdtls "$JDTLS_HOME" --resolvers "$JVMD_RESOLVERS" \
  --fixtures "$BENCH_FIXTURES" --root "$BENCH_PROFILE_RESULTS" --profile
python benchmarks/workspaces/summarize.py "$BENCH_PROFILE_RESULTS" "$BENCH_PROFILE_SUMMARY"
python benchmarks/workspaces/compare.py "$BENCH_PROFILE_SUMMARY" "$BENCH_PROFILE_COMPARISON"
```

Every server process gets an external 20 ms Linux `/proc` sampler. It includes
descendants (the JVMD Node bridge launches the JVM), and reports peak RSS/processes/
threads plus observed CPU and disk I/O in `process*/resources.json`. With
`--profile`, both JVM commands receive the same JFR profile settings. The harness
publishes allocation weights, sample counts, and the 25 largest allocated classes;
these are estimates of allocated bytes, not retained heap. Raw recordings stay in
the result directory. `summarize.py` includes these resource values and
`compare.py` calculates JVMD/JDTLS ratios without discarding individual workers.

The workload covers cold start/import, a second resident workspace, persisted
restart, dependency search, first/warm hover, completion, signature help,
definition, references, rename, document symbols, per-edit typing latency, and
error/recovery diagnostics. Shared and isolated JDTLS modes separate daemon reuse
from one-process-per-workspace behavior. The existing `modules.py` workload adds
saved multi-module API/body changes, while `prefix.py` adds per-character
completion; run both when investigating those specialized paths.

For the complete run (core matrix, independent verification, summaries,
JVMD/JDTLS comparisons, a separate allocation matrix, saved multi-module edits,
and per-character completion), use the orchestration command:

```sh
python benchmarks/workspaces/suite.py --repo "$PWD" \
  --before "$BENCH_BEFORE/build.json" --after "$BENCH_BUILD/build.json" \
  --java-home "$BENCH_JDK" --jdtls "$JDTLS_HOME" --resolvers "$JVMD_RESOLVERS" \
  --fixtures "$BENCH_FIXTURES" --root "$BENCH_SUITE_RESULTS"
```

The suite fails on the first invalid response or worker failure and only writes
`complete.json` after every selected step succeeds. `commands.json` and per-step
logs make the run replayable. `--skip-profile` and `--skip-specialized` support a
shorter smoke run without changing the default complete coverage.

Before a long run, validate the reporting code with:

```sh
python -m unittest benchmarks/workspaces/test_harness.py
```

`modules.py` exercises four Maven/Eclipse projects (base → core → app, plus an
independent module), with 32 files per project. It enables JDTLS autobuild and
alternates saved base-method body and return-type changes. Completion must show
the current return type in the unchanged consumer; app/independent completions
must remain correct. A signature flip must publish changed/cleared consumer
diagnostics. JVMD's lazy dependent diagnostics are requested with a consumer
`didSave`; body-only changes may retain an unchanged diagnostic set. The timed
metric is time to a correct editor response, not completion of a full JDT build.

```sh
python benchmarks/workspaces/modules.py --repo "$PWD" \
  --before "$BENCH_BEFORE/build.json" --after "$BENCH_BUILD/build.json" \
  --java-home "$BENCH_JDK" --jdtls "$JDTLS_HOME" --resolvers "$JVMD_RESOLVERS" \
  --root "$BENCH_MODULE_RESULTS"
```

The release-platform optimization retains javac's selected `--release` API view
for an identical validated option list. It uses JDK internals already tied to the
pinned compiler; the harness and production launch configuration include the
required `javac.main` and `javac.platform` exports. Platform resources are bounded
per compiler context and closed on reconfiguration, recycle, callback failure or
shutdown. Historical-release agreement and API-change tests cover this boundary.
# Per-character completion

`prefix.py` compares two recorded JVMD builds with JDTLS, rotating their order
over three repetitions and using the real-library and 128-JAR fixtures. It sends
actual document changes for each character of `twice` and `getFactory`/`marker0`.
Each of eight chains changes surrounding source first, so first-character misses
are measured separately from prefix growth. Chains start at two characters;
backspacing to one character must still return the target. Empty-token behavior
is covered by the cache regression test, because JDTLS's ranked incomplete page
may omit the target on an empty large receiver. JVMD replacement ranges and prefix filtering are checked
on every response. Full protocol traces, individual samples and cache counters
are retained. Use the same path arguments as `matrix.py`, with a fresh `--root`.

The result cache is deliberately bounded: one candidate set per module, at most
256 rows / 256 KiB, and source contexts of at most 256 files. Larger contexts
continue through normal javac analysis. Source identities, unsaved declarations,
classpath identities and compiler context must all agree before reuse.

### Performance ownership

Performance evidence lives in this benchmark harness, not in `jvmd-tests` and not behind JUnit
`perf` tags. The merge-review matrix exercises the assembled JVMD server through the editor bridge
in a long-lived process: it primes each operation once, records subsequent warm hover, completion,
signature-help, definition, references, rename-preview and document-symbol requests, applies
unsaved typing edits, and measures error/restore diagnostics. It also restarts against persisted
state and repeats the same oracle-checked workload under JFR for sampled allocation, CPU, RSS and
I/O measurements.

Component index measurements remain in `benchmarks/index-updates`. Ordinary JUnit sources contain
only deterministic correctness tests; adding a timing assertion or a `perf` tag there is not the
mechanism for benchmark coverage.

### Semantic-state component evidence

The merge-review report and dashboard also show a separate **JVMD semantic state: base versus
candidate** table. These are JVMD-only experiments, so their values are never placed in JDTLS
columns or included in the LSP win count. The table includes both zero-cache and retained-cache
workspace queries, source identity/handle/name queries, cold Java allocation, source publication
and disk allocation. Cold-start regressions remain visible.

CI builds the exact PR base alongside the exact PR head and runs `benchmarks/semantic-state/run.sh`.
It passes the resulting summary to `compare.py --semantic-state`; the JSON comparison carries that
section into the HTML dashboard, workflow summary and PR evidence comment. Raw component samples
are uploaded with the LSP evidence. Manual workflow runs compare with the preceding commit.

Benchmark/report changes on a PR now trigger a fresh report. Ordinary implementation pushes still
skip this expensive workflow, as before; open/reopen/ready-for-review and manual runs remain available.
For a local report, generate the supplementary input with:

```sh
python benchmarks/semantic-state/summarize.py /path/to/component-results semantic-summary.json \
  --base-sha BASE_COMMIT --head-sha CANDIDATE_COMMIT
python benchmarks/workspaces/compare.py lsp-summary.json comparison.json \
  --markdown comparison.md --semantic-state semantic-summary.json
```

For local semantic-state measurements, package both revisions with JDK 25, then run:

```sh
JAVA_HOME=/path/to/jdk25 benchmarks/semantic-state/run.sh \
  '/absolute/baseline/*' '/absolute/candidate/*' /absolute/new-results
```

Include each revision's production and dependency JARs in its classpath directory.
The harness checks both cache budgets, source results and migration against a
baseline-written store; it measures component work, not editor latency.

Keep generated results, traces, dashboards, recordings, downloads and copied
workspaces outside tracked source, normally under `target/workflow-benchmarks/`.
CI evidence is uploaded as artifacts; inspect the run's retention/expiry before
linking it. Raw JFRs/heap dumps can contain environment or source data: export only
selected diagnostic events from sanitized fixtures. Task status belongs in the PR,
with commands and execution evidence in linked artifacts and commit history.
