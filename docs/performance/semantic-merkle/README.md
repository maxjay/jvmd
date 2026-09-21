# Semantic Merkle implementation: measured, not accepted

Baseline: `ae23fd1f44573bd3427c0e77f967150173669a2f`.
Measured production: `227598c73559be34a89af406e59b7ca5f5d97b90`.
Production tree: `ec4af169a44bb3f023f6852cbe611631eaf75759`.
[Draft PR #8](https://github.com/maxjay/jvmd/pull/8) remains unmerged.

The API ownership Merkle implementation and consumer migration are implemented and
benchmarked. The primary edit path is substantially faster and allocates less.
The overall replacement is **not accepted**: small-workspace warm latency regresses,
several other intervals remain unresolved, and readable production code increased
by **900 lines**. A dedicated workflow also fails an existing intermittent completion
assertion. Other passing tests do not waive these failures.

## Evidence availability

The local campaign completed: 20 independent alternating JVM pairs, all 40 workers
passing their output assertions, plus three separate retained-heap pairs. The source
hashes of the measured build were checked against the published production commit.

The execution environment disconnected while saving the final report, before the raw
evidence bundle was committed. This report preserves the combined numbers already
read from the completed campaign; `observed-summary.json` contains those rounded
observations, not raw samples. Raw campaigns, per-worker p95/min/max, build manifests,
test logs, and the local report generator still require recovery/publication.
Do not claim that the full evidence archive is already available in GitHub.
Recovery locations and failed gates are recorded in the append-only progress document.

## All 34 latency scenarios

Ten initial pairs plus the predeclared ten-pair confirmation, with identical candidate-9
and baseline classes. Requests within a worker are reduced to a median first. Changes
are medians of paired ratios, not ratios of the two displayed medians. Lower is better.
95% bootstrap intervals describe these workers, not production request-tail latency.

| Scenario | Baseline median ms | Candidate median ms | Paired change [95% interval] |
| --- | ---: | ---: | ---: |
| cold_jvm_32/cold_workspace | 829.668 | 818.964 | -0.5% [-3.4, 5.2] |
| cold_jvm_32/warm | 2.896 | 3.315 | 12.7% [5.3, 26.0] |
| cold_jvm_32/body_edit | 32.245 | 28.539 | -18.5% [-22.8, -8.5] |
| cold_jvm_32/api_edit | 72.205 | 69.252 | -2.4% [-13.8, 7.4] |
| cold_jvm_32/rename_preview | 6.417 | 7.153 | 7.4% [-2.9, 25.9] |
| navigation_128/cold_workspace | 307.050 | 328.267 | 7.8% [1.1, 21.9] |
| navigation_128/warm | 2.821 | 3.137 | 8.9% [-3.2, 15.8] |
| navigation_128/body_edit | 26.342 | 19.933 | -28.4% [-37.1, -18.9] |
| navigation_128/api_edit | 59.865 | 55.462 | -2.9% [-16.0, 10.7] |
| navigation_128/rename_preview | 2.545 | 2.807 | 17.3% [-3.0, 41.9] |
| navigation_512/cold_workspace | 702.318 | 663.755 | -3.0% [-11.1, 8.3] |
| navigation_512/warm | 5.517 | 4.321 | -26.0% [-33.4, -4.5] |
| navigation_512/body_edit | 46.454 | 23.613 | -47.2% [-52.8, -39.7] |
| navigation_512/api_edit | 74.101 | 54.760 | -27.4% [-32.7, -17.1] |
| navigation_512/rename_preview | 4.828 | 4.523 | -2.6% [-16.5, 8.3] |
| real_core/cold_workspace | 1056.398 | 1099.747 | 3.1% [-5.4, 15.1] |
| real_core/warm | 0.212 | 0.224 | 3.7% [-14.5, 21.8] |
| real_core/position_edit | 45.701 | 9.689 | -78.8% [-84.0, -76.9] |
| validation_512/precise_epoch | 0.001 | 0.001 | 0.0% [-1.8, 3.2] |
| validation_512/coarse_disk | 1.188 | 1.155 | -1.9% [-14.7, 4.8] |
| lifecycle_65/cold_modules | 59.540 | 58.119 | 1.1% [-19.3, 17.6] |
| lifecycle_65/warm_modules | 0.446 | 0.329 | -13.1% [-32.8, 3.6] |
| lifecycle_65/diagnostics_warm | 0.299 | 0.216 | -18.1% [-30.0, -1.5] |
| lifecycle_65/body_edit | 34.865 | 5.053 | -84.9% [-86.3, -83.6] |
| lifecycle_65/cross_module_api | 32.817 | 29.458 | -5.9% [-21.2, -0.1] |
| lifecycle_65/diagnostics_after_api | 0.465 | 0.457 | 11.0% [-6.1, 27.6] |
| lifecycle_65/documentation_position | 29.040 | 30.657 | 6.1% [0.2, 13.9] |
| lifecycle_65/buffer_open | 7.598 | 5.483 | -29.0% [-34.5, -22.9] |
| lifecycle_65/buffer_api | 27.279 | 28.736 | 6.5% [2.5, 17.0] |
| lifecycle_65/buffer_close | 27.548 | 28.178 | 6.1% [-6.3, 16.2] |
| lifecycle_65/file_add | 23.612 | 25.565 | 3.5% [-5.1, 19.1] |
| lifecycle_65/file_delete | 23.647 | 24.221 | 1.6% [-6.9, 18.5] |
| lifecycle_65/context_change | 22.747 | 23.745 | 4.3% [-2.3, 16.6] |
| lifecycle_65/context_restore | 23.853 | 26.983 | 11.1% [-5.1, 24.7] |

The primary latency and allocation gates pass; overall no-regression acceptance fails.
An interval allowing a material regression remains unresolved. Earlier candidate-5/6/7/8
campaigns are not pooled with candidate-9. No JDTLS comparison is claimed.

## Allocation and memory

512-file body-edit allocation medians: **17.98 → 7.65 MB**, paired **−57.5%**.
512-file API-edit allocation: **24.55 → 14.12 MB**, paired **−42.5%**.
65-file cross-module API-edit allocation: **12.17 → 9.98 MB**, paired **−18.0%**.
128-file cold allocation: **44.45 → 39.92 MB**, paired **−10.2%**.

Whole-worker medians: peak RSS **483.84 → 469.05 MiB**; GC time **126.5 → 122.0 ms**;
GC collections **12 → 10**. These totals include warmup and untimed correctness work;
RSS includes native memory.

Three separate forced-GC before/after pairs use 256 files, 12 edits and 13 reader snapshots:

| Whole-JVM live heap | Baseline MiB | Candidate MiB |
| --- | ---: | ---: |
| After initial snapshot | 25.60 | 26.14 |
| With 13 readers | 20.06 | 18.46 |
| After releasing old readers | 17.81 | 18.46 |

These are not object-exact graph sizes. Unrelated compiler/runtime state can be collected
between phases, which explains initial heap exceeding later heap. Snapshot sharing helps
this body-edit fixture; heap after releasing readers is about 3.6% higher. No universal
memory reduction is claimed. Admission estimates are not treated as measured heap.

## Implemented architecture and replacement

| Previous responsibility | Replacement |
| --- | --- |
| Reconstruct API identity from display maps in multiple consumers | Capture DeclarationContract from javac; publish one SemanticApi per file |
| Independent file API hashes | Compose declaration/type ownership into file, module and workspace roots |
| Rebuild global navigation and edge indexes after edits | Replace changed contributions in immutable persistent maps |
| Scan every occurrence for references/rename | Target-symbol postings and override adjacency |
| Serialize full navigation output for admission sizing | Per-fragment structural estimates |
| Rebuild navigation reverse dependency maps | Shared DependencyGraph complete/partial operations |
| Discard result structure during compiler revalidation | Compare fresh contributions with prior immutable snapshots |
| Separate source hash observation in navigation | Shared Documents / FileStateRegistry observation |

ApiFingerprint is removed. Diagnostics, module API propagation and source-index
publication consume the captured file identity. Workspace API root equality gates
navigation dependency propagation. Positions, references and local diagnostics still
refresh on API-equal edits. The sparse public protocol shape is preserved.

This is an **API ownership DAG across snapshots**, with separate persistent navigation.
It is not a content-addressed identity for every compiler output. Javac still parses and
attributes changes; green syntax trees are not implemented. Diagnostic pending-change
handling, filesystem observation and Rocks invalidation retain distinct lifecycles.
An equal navigation API root does not eliminate every diagnostic reverse traversal.

Task-owned SymbolIdentity is shared across batch capture. Fingerprint history holds a weak
reuse link to prior SemanticApi objects, so it cannot retain full graphs after snapshot
owners release them. There is no global interner.

## Algebraic fingerprint extension

SemanticNode.withChild(key, value) is the unordered unique-key replacement boundary.
It has the membership index and both old and new contributions. Consumers depend on
logical children and identity, not internal Merkle tree shape. An accumulator can later
replace aggregate calculation inside that boundary.

Identities include **domain, schema and algorithm**. Keys are bound to values. Ordered
payload fields use length-delimited encoding; classpath, parameter and record-component
order remain significant. Reference cycles use symbol IDs rather than ownership links.
A new algorithm must have a distinct identity namespace and persistence misses.

No accumulator cryptography is implemented now. A future engine must specify collision
resistance, multiplicity and replacement/deletion laws. O(1) aggregate arithmetic would
not eliminate membership-index maintenance or compiler work.

## Remaining cost and complexity

Avoiding global projection, aggregation and JSON sizing removes substantial edit work.
Immutable map path copies, identities and contribution comparisons introduce other costs.
They are not automatically cheaper for startup, broad changes or small queries.

Intermediate measurements found redundant contribution replacement and discarded roots
during revalidation; both were removed. Sharing task symbol identity improved broad
lifecycle cases. The precise cause of the remaining small-query regression is not yet
established. It must not be dismissed as noise or blamed entirely on hashing.

The same Google Java Format 1.30.0 was applied to audit copies of all affected handwritten
production sources, including added helpers and deleted classes: **5,404 → 6,304 lines**.
This **+900** fails the user's overall reduction requirement. Removed repeated work does
not excuse increased implementation complexity. No unrelated deletion or formatting trick
counts as a fix.

## Correctness and CI

- **55/55 focused local tests pass**, including 11 new identity/API/reader/protocol/fence tests.
- Exact production commit passes [Checkpoints](https://github.com/maxjay/jvmd/actions/runs/35548645908)
  and [Distributions](https://github.com/maxjay/jvmd/actions/runs/35548645915).
- The [benchmark workflow](https://github.com/maxjay/jvmd/actions/runs/35548645946) fails its
  CompletionPrefixCacheTest.changedReleaseAndNewSourceNamesCannotReuseOldCandidates precheck
  twice, before timing begins. It is not a passing workflow or a timing failure.
- A diagnostic repetition of that test and the timestamp-preserving-edit test fails 3/20
  repetitions on the untouched baseline: one matching new-source failure and two timestamp
  failures. Candidate-9 fails 0/20 in that diagnostic run. This establishes baseline
  intermittency, not a fix or a waiver.
- Intermediate fixture/compile failures and candidate-4's completion failure were retained
  locally. Assertions were not weakened. A heap-fixture assertion was corrected before
  the final identical benchmark harness.

## Coverage and limits

The expanded baseline was measured before production edits. Both sides use pinned
Temurin 25.0.4.1+1, G1, 256 MiB initial / 1 GiB maximum heap, identical dependencies and
Java benchmark sources, with no AOT. Serial workers alternate before/after order.

Coverage includes startup, warm navigation, body/API edits, rename, unchanged baseline
core sources, precise/coarse validation, diagnostics, buffers, file lifecycle, context
changes, old readers and agreement with clean-cache output.

Navigation includes Application/Dispatcher, not LSP/socket transport or Maven resolution.
Lifecycle uses two source roots in one Analyzer; module-actor scheduling has correctness
tests, not timed coverage here. Precise validation uses controlled epochs, not a native
watcher throughput benchmark. documentation_position also restores the API type in two
cycles; it is not a pure documentation benchmark. One cross_module_api cycle keeps the
same API type. Completion latency and green-tree parsing are outside this benchmark scope.

Build/run instructions remain in benchmarks/semantic-state/README.md.
Implementation steps 1–5 are completed. Measurement ran, but raw evidence publication,
correctness acceptance, no-regression acceptance and overall simplification remain open.
The environment must be recovered to finish publishing the evidence and continue work.
