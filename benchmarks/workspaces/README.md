# Shared-workspace editor benchmark

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

The merge-review gate uses `fixture.py --fixture-names review` as its correctness
corpus instead of cloning a separate source repository. Its checked oracle covers
512 generated source files and 64 dependency JARs (1,024 binary classes), while the workload checks
the same hover, completion, signature, navigation, references, rename, symbols,
unsaved edits, diagnostics, and dependency identities against JVMD and JDTLS.
Every generated fixture records expected dependency identities in `fixture.json`;
the matrix consumes that manifest instead of relying on fixture-name conventions.
The action generates only this corpus and runs servers serially. It does not use a
short timeout or accept partial results: every worker and the independent verifier
must complete successfully before the dashboard is produced.

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
