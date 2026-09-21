# Main, Maven updates, source Merkle state and JDTLS

Historical benchmark narrative reports were removed during consolidation. The harness and generated artifacts remain the executable evidence.

record three repetitions against actual main and pinned JDTLS, including the
memory/query tradeoffs and the distinct source-Merkle experiment.
separates cold indexing, persisted restart and warm queries, including JVM launch.
The [seed allocation follow-up](../../docs/performance/2026-09-19-seed-optimization.md)
adds five-repetition seed comparisons, bidirectional persisted-index checks, JFR
allocation evidence and a refreshed JDTLS comparison.

These are distinct measurements. Maven discovery uses `IndexService.scan()` and
the artifact inventory; it does not call `RocksWorkspaceState`. Source Merkle
updates are measured separately against full source reconciliation. No Maven
repository speedup should be attributed to Merkle roots from these measurements.

## Reproduce the main comparison

Use pinned Temurin 25.0.4.1+1. Create a detached checkout of the actual main commit;
do not select the SQLite setting in the new branch as a substitute for old code.

```sh
git worktree add --detach /tmp/jvmd-main-baseline e651e9f8db50f3a6ecd4d9fb19c1d4681196708d
python benchmarks/index-updates/run.py \
  --main /tmp/jvmd-main-baseline --rocks "$PWD" \
  --java-home /path/to/jdk-25.0.4.1+1 \
  --dependencies /path/to/release/lib/jvmd \
  --root /tmp/jvmd-index-updates
```

The dependency directory may also be a populated Maven repository. Required
versions are listed in `run.py` and checked against the branch POMs. Dependency
JARs are copied and checksummed before measurements. Core/index sources from each
unchanged checkout are compiled separately with identical flags; Rocks also
compiles its provider. Module descriptors are omitted for the classpath harness,
as in the existing forked tests. Production source modifications are rejected.

Every worker uses its own 1 GiB JVM and state directory. Three repetitions
alternate backend order and run serially. No OS-cache flush is attempted. Run
on the intended filesystem; container overlayfs results do not establish durable
WSL performance. `--smoke` uses small fixtures for validating the harness only.

- One JAR, 950 classes, 380,000 symbols: repeat the large fresh-seed measurement
  against actual main, retaining both field and type-query metrics.
- 128 JARs, 1,024 classes, 379,904 symbols, cross-JAR references, one SNAPSHOT:
  fresh seed, restart, warm scan, metadata-only edit, same-content touch, release
  replacement, addition, deletion, SNAPSHOT replacement preserving mtime, unchanged
  post-update scan and restart. Fixture creation/mutation is outside scan timing.
- Source state: 1,000 and 10,000 files; 20 paired edits per repetition. Known-file
  timing includes hashing that file. Every incremental root must equal a full
  reconciliation; updates must write one leaf, six directory/child records and
  one module record. A classpath-only change must write only module metadata.

`indexed_delta` is the service's change counter, not a literal parser-call counter
(Rocks also increments it when paths are deleted). SNAPSHOTs are intentionally
rehashed on every scan. Missing old-main timing counters are not reconstructed or
presented as measured values. Old-main deletion failures are retained as correctness
findings rather than silently removing that scenario. Replacement and restart
queries are checked explicitly. Peak RSS for update workers is a process lifetime
peak that also includes fixture mutations; use seed workers for isolated RSS comparison.

Scan, workspace-load and query write traffic are recorded separately; end-to-end
write totals include temporary query files as well as index publication.

Outputs include raw worker JSON/logs, source revisions, compiled class hashes,
dependency/harness hashes, fixture manifests, operation counters and summaries.

## JDTLS comparison

Pin Eclipse JDTLS 1.61.0 (`jdt-language-server-1.61.0-202609031315.tar.gz`) from the
[official milestone archive](https://download.eclipse.org/jdtls/milestones/1.61.0/).
Verify its published SHA-256:
`338e7e73d61836651ba2453919a0d34fa763eb4e7c03342092309bffb8934c64`.

```sh
python benchmarks/index-updates/jdtls.py \
  --jdtls /path/to/extracted-jdtls --archive /path/to/jdtls.tar.gz \
  --java-home /path/to/jdk-25.0.4.1+1 \
  --repository /tmp/jvmd-index-updates/m2-128/pristine \
  --artifacts 128 --classes 8 --root /tmp/jvmd-jdtls-updates
```

Repeat with `single-380000/pristine`, `--artifacts 1 --classes 950`. Each fresh
JDTLS process imports an Eclipse Java project referencing every fixture JAR;
Maven/Gradle import and automatic builds are disabled. Its first dependency-type
search must return every generated class; subsequent exact type queries must
return one class per artifact. Restart uses the same persisted JDTLS workspace.
The client follows the [official launcher instructions](https://github.com/eclipse-jdtls/eclipse.jdt.ls#running-from-the-command-line)
and enables binary-class content support so library types are included.

**Scope:** JVMD measurements call its index-service API. JDTLS measurements use
LSP and include Eclipse/project/JDK startup. Query matching is equivalent for
the generated class names, but transport and server duties differ. JDTLS's type
search is not an equivalent test of JVMD's binary field/method search, symbolic
relationship store or global `.m2` inventory. Report these limitations alongside
the numbers; do not describe the result as a general language-server ranking.

## Cold, reopened and warm query optimization

JDT also indexes binary methods, fields and references upfront. Its workspace
type-search handler waits for indexing readiness. Do not describe this comparison
as preindexed JVMD versus an unindexed JDTLS.

For a before/after comparison of two Rocks revisions, check out the baseline
in a separate worktree and run:

```sh
python benchmarks/index-updates/query-performance.py \
  --baseline /path/to/baseline --current "$PWD" \
  --java-home /path/to/jdk-25.0.4.1+1 \
  --dependencies /path/to/release/lib/jvmd \
  --root /tmp/jvmd-query-performance
```

This runs three fresh and three persisted-state reopen workers for each revision
and fixture. It records the parent process's time from launch until an explicit
all-types-ready marker, including JVM launch. The inner service timer remains
available for phase attribution. Each worker also records three broad warm type
queries after the existing repeated exact-type and field queries. Result identities
must agree across implementations and restart. Repeat `jdtls.py` with
`--warm-type-queries 3` to measure broad warm queries on the same fixtures.

## Seed allocation profiling

Pass `--runs 5` to `query-performance.py` for the five-repetition seed experiment.
Both production checkouts must be committed. Once the unprofiled run completes,
use its exact compiled classes and fixture for a separate diagnostic profile:

```sh
python benchmarks/index-updates/profile-seed.py \
  --benchmark-root /tmp/jvmd-query-performance \
  --java-home /path/to/jdk-25.0.4.1+1 \
  --root /tmp/jvmd-seed-profile
```

This runs one fresh 380,000-symbol JFR worker per implementation serially, exports
allocation/CPU/GC samples, and records commands and hashes. Allocation weights are
sample estimates, not retained heap. Use unprofiled repetitions for speed claims.
The `classpaths.json` input must come from a trusted local benchmark run.

## Post-merge allocation and bounded-page investigation

The [post-merge report](../../docs/performance/2026-09-19-compact-grams-and-pages.md)
compares merged main `dce59413` with compact grams, streaming merge postings and
ID-based rejection of candidates outside a result page. It includes unchanged
workloads and the observed full-result query tradeoffs.

`dependencies.py` accepts the same `--baseline`, `--current`, `--java-home`,
`--dependencies`, `--root` and `--runs` inputs as the query performance harness.
It indexes the five pinned public dependency JARs themselves and compares a fixed
query mix after fresh seed and persisted reopen.

`pages.py --benchmark-root /path/to/query-performance-output --java-home /path/to/jdk
--root /new/output --runs 5 --samples 5` reuses that run's exact compiled classes.
Each worker opens a private copy of the baseline's one-JAR persisted state,
compares complete 20-row pages at early/middle positions, and records symbol-read
counters when available. This prevents fresh-build artifact-ID differences from
being mistaken for a query correctness difference. Page timing excludes index
startup and reference-result hashing. Use only trusted local benchmark roots.

Run all timing and profiling processes serially. `profile-seed.py` remains a
separate diagnostic allocation/CPU measurement, not a latency gate.
