# Input identity consolidation — measured delivery

## Revision and prerequisite evidence

Baseline main: `9ac81c4bdded8de18537569debf02e789c24fe27`.
Measured local production head: `5fae72760a4486e9b914c821aa6cdd25aa7b5212`.
Published equivalent production head: `9f14f41d2cd1bf0672927059a10d3fbea2e0c4b8`.
Both have tree `4622173acaf6f9d5d72289ef83fef1c7b15c352a`.
The following delivery commit adds evidence, reporting and four test assertions;
it does not change measured production code. GitHub publication recreated commit
metadata because authenticated git push was unavailable; every published tree
was checked against its local tree. [Publication mapping](input-validation/publication.json).

PRs #17 and #18 were merged before the baseline; this is not stacked. Inspection
and baseline regressions verified #18's declaration ownership, preservation of
committed owners after failure, and independent caller read leases. Those repairs
were not reimplemented as input identity. FileSemanticContribution,
SemanticUpdatePolicy, indexed read views and compiler-thread ownership remain.

## Resulting ownership and deleted paths

| Responsibility | Authoritative owner | Consumers / distinction |
|---|---|---|
| Disk contents and directory membership observations | FileStateRegistry shared default | Bounded validated content entries; tracked directory listings. Explicit injected registries remain possible. |
| Unsaved contents | Session Documents | Immutable overlay objects; sessions never share unsaved text. |
| Identified compiler inputs | Module/session CompilerInputs | SourceIdentity, MembershipIdentity, EnvironmentIdentity and capture-time observation fence. |
| Compiler configuration | Existing module/context construction | Ordered roots/classpath, options/release, JDK/platform, processor/module/patch paths feed CompilerInputs. |
| Accepting compiler results | CompilerPool plus Analyzer publication boundary | Captured text is checked; final inputs must match before accepting. |
| Accepting indexed navigation revisions | WorkspaceBindings | Captured snapshots validate before fact commit; failed/superseded rebuilds keep committed owner inventory. |
| Dependency invalidation | Existing SemanticUpdatePolicy | Body content changes do not become environment changes; namespace/API rules remain conservative. |
| Persisted evidence | Existing RocksWorkspaceState / IndexStore | Shared collection/composition; existing Merkle algorithm retained. Publication carries analyzed source hash. |
| Accepted semantic output | Existing revisions and FileSemanticContribution | An output belongs to inputs; it is not another input or graph identity. |

Deleted/replaced production paths: WorkspaceBindings' private stamp/hash LRU,
Inputs recipe and ValidationToken; Analyzer's independently built source maps and
classpath/environment recipe; IndexedFileManager's binary stamp algorithm and
both WatchServices; LocalArtifacts' private hash LRU and recursive source/output
walks; RocksWorkspaceState's separate source collection/environment recipe;
Application's persisted-root token authority and WorkspaceBindingContexts plumbing.
Default disk registries now share observations instead of hashing the same JDK or
JAR independently. Already loaded binary-byte validation and artifact source/output
timestamp ordering remain because they protect different inputs.

Production Java diff against the baseline: **442 additions, 417 deletions, net +25**.
This is a small increase, not shrinkage. The new shared directory observations,
explicit captured-text checks, configuration composition and supersession boundary
replace several independent policies while adding missing race/overlay/environment
coverage. No formatting compression or relocation into tests is counted as removal.

## Fast path and reconciliation contract

On supported Unix providers, file identity retains the baseline size, mtime, ctime
and inode checks. Known directories are checked with no-follow metadata; unchanged
listings are reused, and only changed directories are enumerated. Every known
file still has a metadata check. This does not assume that watcher epochs or queued
notifications prove freshness: the competing watchers have been removed.

Opening/editing an overlay changes live observation immediately. Its text wins over
disk, including new files. Saving does not discard an active overlay. Closing uses
current disk text (or removes a never-saved file). Reverting returns the content
identity but still supersedes work captured before that overlay transition.
Different sessions have distinct Documents and snapshots. Body changes remain
source changes; membership/environment and semantic dependency invalidation are
separate. Known content changes copy the affected snapshot map once, not per warm
query. `sameInputs` permits content-equivalent reuse; the observation fence rejects
intervening edits during work. Supplied source text is checked against captured
hashes, and publication never relabels old facts with a later reread.

Startup, changed configuration/root inventories, explicit `reconcile()` (including
observation uncertainty/overflow), evicted file observations and unsupported
metadata use conservative recovery. Unsupported providers rehash/re-enumerate;
unchanged hashes do not manufacture perpetual supersession. Decoded fact/byte cache
eviction does not clear the input snapshot. Reconciliation clears observations,
not the last accepted semantic result or committed owner metadata. Concurrent
listing changes are retried with a bound and then rejected explicitly.

Environment composition is versioned, length-prefixed and streamed into the digest.
Lists preserve meaningfully ordered classpath/options; genuine maps/sets are sorted.
Actual JDK release/modules/ct.sym contents, configured compiler paths and relevant
source-root configuration are included. Persisted module fingerprints are version 2.
Paths remain absolute/normalized; no relocation-independent reuse is claimed and
source traversal does not start following directory symlinks.

## Correctness

Baseline phase 3/4: **123 passed**. Measured candidate: **132 passed** with the same
1 GiB heap. Final focused run: **9 CompilerInputs tests and 3 RocksWorkspaceState
tests passed**, including the added independent environment assertions. Reporting
checks: 4 Python harness tests, 2 trend tests and 4 Node dashboard tests passed.
Raw Maven logs are committed. Remote CI was not yet complete when this report was
written; see the PR checks for its actual result. No complete local all-phase build
or local JDTLS comparison is claimed.

Focused coverage includes unchanged work counts, body/API reuse, creation/deletion/
rename and namespace resolution, new overlays/edit/save/close/revert, two sessions,
external edits/preserved timestamps/atomic replacement, classpath replacement/order,
options/platform/processor/generated composition, restart/reconciliation and an
unsupported filesystem provider, source/environment changes inside compiler callbacks,
blocked asynchronous publication, failed navigation rebuild/retry, ownership/read
leases and existing decoded-cache eviction regressions. Processor/generated tests
use controlled identity fixtures; they do not run every build toolchain.

The existing LSP matrix independently verified **6 workers, 12 workspaces,
504 editor responses, 144 definition/reference/rename responses, 3,120 source ranges**
and 12 dependency-identity sets. [Verification](input-validation/current/e2e-verification.json).

## Component measurements

Three serial alternating baseline/candidate repetitions; identical 128-source
fixture, JDK 25.0.4.1, dependencies and 1 GiB heap. Values below are medians of
per-process p50/p95 or counts. Cold and mutation scenarios each have one sample per
process, so their p50/p95 coincide and do not estimate a latency tail. Validation
is timed separately from detached navigation-result construction and javac.
Counts span the validation plus detached-navigation operations; metadata counts are
instrumented Java Files calls, not all underlying filesystem syscalls.

| Measurement | Baseline | Candidate | Change |
|---|---:|---:|---:|
| unchanged/validation_ms/p50 (ms) | 2.423 | 2.109 | -12.9% |
| unchanged/validation_ms/p95 (ms) | 3.783 | 3.365 | -11.0% |
| unchanged/navigation_ms/p50 (ms) | 1.645 | 0.877 | -46.6% |
| unchanged/navigation_ms/p95 (ms) | 3.502 | 1.521 | -56.6% |
| unchanged/thread_allocated_bytes (bytes) | 31,306,208.000 | 30,678,000.000 | -2.0% |
| unchanged/file_loads (count) | 0.000 | 0.000 | — |
| unchanged/work.source_enumerations (count) | 120.000 | 0.000 | -100.0% |
| unchanged/work.classpath_enumerations (count) | 80.000 | 0.000 | -100.0% |
| unchanged/work.files_hashed (count) | 0.000 | 0.000 | — |
| unchanged/work.bytes_hashed (bytes) | 0.000 | 0.000 | — |
| unchanged/work.metadata_checks (count) | 15,560.000 | 16,440.000 | +5.7% |
| unchanged/work.identity_map_rebuilds (count) | 120.000 | 0.000 | -100.0% |

Unchanged warm work meets the zero-enumeration/rehash/map-rebuild invariant. Metadata checks increase 5.7%; they remain linear in tracked inputs. Allocation above is owner-thread allocation across both component operations, not whole-process or native allocation.

| Measurement | Baseline | Candidate | Change |
|---|---:|---:|---:|
| cold/validation_ms/p50 (ms) | 67.897 | 244.225 | +259.7% |
| body/validation_ms/p50 (ms) | 2.170 | 1.794 | -17.3% |
| api/validation_ms/p50 (ms) | 1.847 | 1.855 | +0.5% |
| membership/validation_ms/p50 (ms) | 1.682 | 4.504 | +167.8% |
| environment/validation_ms/p50 (ms) | 2.389 | 2.692 | +12.7% |
| reconciliation/validation_ms/p50 (ms) | 2.445 | 132.353 | +5313.5% |

Initial validation now verifies JDK file contents and is slower. Explicit candidate
reconciliation hashes 133 files / 156,886,840 bytes in this fixture. **The baseline
has no explicit reconcile operation and the harness does nothing there**; its
2.445 ms is not equivalent recovery work. Process restart is compared below.
All mutation counts, bytes and allocations are in the raw component summary.

Real diagnostics (includes validation, javac when needed and result construction):

| Measurement | Baseline | Candidate | Change |
|---|---:|---:|---:|
| real-diagnostics/cold/diagnostics_ms/p50 (ms) | 765.584 | 938.859 | +22.6% |
| real-diagnostics/unchanged/diagnostics_ms/p50 (ms) | 0.928 | 1.089 | +17.4% |
| real-diagnostics/body/diagnostics_ms/p50 (ms) | 19.698 | 24.620 | +25.0% |
| real-diagnostics/api/diagnostics_ms/p50 (ms) | 85.117 | 77.329 | -9.1% |
| real-diagnostics/membership/diagnostics_ms/p50 (ms) | 43.820 | 49.489 | +12.9% |
| real-diagnostics/environment/diagnostics_ms/p50 (ms) | 99.546 | 102.140 | +2.6% |
| real-diagnostics/unchanged/diagnostics_ms/p95 (ms) | 2.056 | 1.314 | -36.1% |
| real-diagnostics/unchanged/thread_allocated_bytes (bytes) | 5,657,872.000 | 10,363,928.000 | +83.2% |

Warm diagnostics invokes javac **0 → 0** times and reuses **40 → 40** results.
A body edit analyses **1 → 1** file and reuses the dependent result; an API edit
analyses **2 → 2** files. Membership/environment scenarios analyse **2 → 2**.
The diagnostics p50 and allocation regressions are real observations, despite the
lower validation work and p95. This is not a claim of universal latency improvement.

## Existing end-to-end benchmark

Uninstrumented baseline/candidate JVMD, three alternating serial repetitions of
the existing `real` fixture, 32 sources, one resident workspace, three samples,
two edits and restart. Both retain the 1 GiB heap. Below are medians across workers;
latencies are milliseconds unless the row names another unit. No run is omitted.
The pinned JDTLS download timed out at proxy CONNECT locally; the existing CI
JVMD/JDTLS job remains enabled. These local columns are both JVMD.

| Metric | Baseline JVMD | Candidate JVMD | Change |
|---|---:|---:|---:|
| fresh | 3,027.899 | 3,229.916 | +6.7% |
| new_workspace | 3,027.899 | 3,229.916 | +6.7% |
| restart | 1,866.592 | 2,021.214 | +8.3% |
| dependency_search | 23.053 | 34.584 | +50.0% |
| measured_work | 7,575.441 | 8,109.992 | +7.1% |
| peak_rss_mib | 488.828 | 487.801 | -0.2% |
| cpu_seconds | 23.210 | 24.710 | +6.5% |
| disk_read_mib | 6.000 | 6.000 | +0.0% |
| disk_write_mib | 47.297 | 47.320 | +0.0% |
| preindex | 1,700.178 | 1,857.918 | +9.3% |
| first_hover | 112.873 | 119.348 | +5.7% |
| warm_hover | 8.308 | 8.439 | +1.6% |
| first_binary_hover | 25.189 | 21.927 | -12.9% |
| warm_binary_hover | 4.554 | 5.712 | +25.4% |
| first_binary_completion | 89.709 | 101.902 | +13.6% |
| warm_binary_completion | 5.746 | 8.217 | +43.0% |
| first_signature_help | 13.582 | 16.642 | +22.5% |
| warm_signature_help | 9.049 | 11.577 | +27.9% |
| first_definition | 4.212 | 5.245 | +24.5% |
| warm_definition | 4.239 | 5.579 | +31.6% |
| first_completion | 11.992 | 17.042 | +42.1% |
| warm_completion | 4.531 | 5.939 | +31.1% |
| first_references | 200.458 | 217.687 | +8.6% |
| warm_references | 8.654 | 8.354 | -3.5% |
| first_rename_preview | 38.511 | 46.803 | +21.5% |
| warm_rename_preview | 11.413 | 13.720 | +20.2% |
| first_document_symbols | 22.405 | 18.270 | -18.5% |
| warm_document_symbols | 3.907 | 4.686 | +19.9% |
| typing_completion | 10.554 | 11.555 | +9.5% |
| typing_binary_completion | 19.146 | 23.032 | +20.3% |
| typing_signature_help | 10.565 | 11.788 | +11.6% |
| diagnostics_error | 222.516 | 224.993 | +1.1% |
| diagnostics_restore | 213.346 | 213.599 | +0.1% |

Total measured work regressed **7.1%**, restart **8.3%**; several navigation and
completion paths also regressed. Peak RSS was effectively unchanged (-0.2%).
This small matrix is evidence, not a confidence interval or broad workload claim.
No end-to-end allocation profile was run locally; owner-thread component allocation
must not be presented as an equivalent whole-process measurement.

## Evidence, reproduction and remaining limits

[Reproduction instructions](../../benchmarks/input-validation/README.md),
[current raw records](input-validation/current),
[component summary](input-validation/current/input-summary.json),
[end-to-end summary](input-validation/current/e2e-summary.json),
[historical measurements](input-validation/historical).
The compressed LSP archive contains commands, messages, resource records and sources
needed by the existing independent verifier; databases and dependency binaries are
excluded. Build manifests record source/dependency hashes. Harness hashes and Java
version are recorded in `environment.json`. The initial baseline checkpoint remains
in `baseline-initial.json`. Historical preliminary runs are superseded by current
measurements; in particular `paired-results` predates the authoritative known-edit
notification in the diagnostics fixture and is not body-reuse acceptance evidence.

The component summary is attached to the existing merge-review report/dashboard in
a distinct JVMD-only section; it does not contribute to JDTLS win counts. CI uploads
the raw component results alongside existing editor correctness evidence.

Remaining limits: metadata observation is O(tracked inputs), and a changed snapshot
still copies its map once. Unsupported providers intentionally do more work. The
shared disk registry remains bounded at the existing 32,768 file observations;
eviction can cause conservative rehashes. Directory observations live with the
registry rather than decoded caches. More scalable inventory structures, faster
fingerprints, a global cross-store transaction and universal latency optimization
are outside this consolidation. No new backend, semantic policy, fact format,
prolly tree or algebraic fingerprint was introduced. The observed cold, allocation
and editor latency costs remain visible review tradeoffs, not hidden successes.
