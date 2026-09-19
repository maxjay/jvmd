# Shared-workspace editor benchmark

`run.py` exercises the production JVMD Application, Dispatcher, framing, RPC client
and LSP bridge. The only substitute is stdin/stdout for the daemon's Unix socket.
JDTLS uses its usual stdio endpoint. Neither server uses AOT. Both use a 1 GiB heap.

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
`--sources 128` scales source discovery and references. `--profile` enables JFR
for JVMD; keep profiled runs separate from reported unprofiled latency medians.

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

The September 19 campaign pins Temurin 25.0.4.1+1, JDTLS 1.61.0 and Maven 3.9.9.
Its [report](../../docs/performance/2026-09-19-editor-reuse.md) links exact build,
fixture, toolchain and protocol evidence, including unsuccessful attempts.
Profiles are separate from the reported unprofiled latency samples. Only the
selected CPU/allocation JFR event export is published, since a raw JFR can also
contain initial environment and system properties.

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
