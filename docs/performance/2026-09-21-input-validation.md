# Input identity consolidation — measured delivery

Current result: see **Final late-review verification (2026-09-22)** below; preceding sections are historical.

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
checks: 4 Python harness tests, 3 trend tests and 4 Node dashboard tests passed.
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

Delivery follow-up: [PR #21](https://github.com/maxjay/jvmd/pull/21) is open.
Main advanced with reporting-only #20 (`6129dbf`); the branch includes that merge.
Reporting tests were rerun after merging it; measured production is unchanged.
Remote Checkpoints, Distributions and Merge Review are pending at this checkpoint.

## Review repair validation (supersedes the delivery head above)

Measured repair production: local `e86d311`, published
`09f69d9ab478067f3533e5f1655ce3e9c1134084` (identical source tree).
All four review findings are addressed: environment inventories follow compiler
directory links with cycle rejection, additional javac input paths are observed,
unused directory inventories have a 128-entry LRU bound, and CI baseline builds
use baseline dependencies. Source traversal still defaults to no-follow. Eviction
is observable and forces reconciliation on reuse without clearing semantic state.
The bound limits retained root/suffix/follow-mode trees, not their byte size.
Compiler module-source patterns conservatively observe the containing tree.

135 phase 3/4 tests passed, including three new regression tests. Three serial
alternating component repetitions against the original baseline still show zero
warm source/classpath enumerations, files hashed and identity-map rebuilds.
Validation p50: 1.919 → 1.692 ms; p95: 2.915 → 2.868 ms. Real diagnostics p50:
0.876 → 1.031 ms. Body/API javac counts remain 1/2 in both revisions. These new
timings supersede the earlier component timing sample; the existing historical
records remain intact. End-to-end latency has not been rerun locally for these
repairs; the new CI benchmark will supply its own evidence.

[Repair raw measurements and test log](input-validation/review). The overall
production Java diff is now +487/-417, net +70; the earlier +25 refers to the
pre-review delivery. The prior head passed Checkpoints, Distributions and Merge
Review, including hosted JDTLS correctness. New-head CI is pending publication.

## Bounded finish: complete environments and observation fences (2026-09-22)

This section supersedes earlier implementation counts and performance conclusions;
older measurements above and in `input-validation/review` remain historical evidence.

### Provenance and correctness

Immediate pre-repair baseline: `0347d7ab58a9c176ac3be027d36c0f2a72209459`, tree
`b2e3127b4a7102d0f8a79415d753ace3c5d723e5`. Original task-4 baseline:
`9ac81c4bdded8de18537569debf02e789c24fe27`, tree
`982af99691d162be5f6e7b9ad578812ff41426d6`. Fetched main:
`6129dbf445932cf67f8375a4c869c07277285f44`; intervening #20 changes are benchmark
reporting, not production changes. #17/#18 remain integrated; this repair does not
replace their policy, owner inventory, fact retention, or caller-lease fixes.

Tested local production: `67cf8dcef166598ccc40178a5d859cdc60c17d86`.
Published production: `82bf709b3a70b6b35dfde617dea1eef61eeb80cb`.
Both have exactly tree `c28aa74d6dea5765772c5b553ce152e21a7941d3`;
GitHub publication creates different commit metadata, not different files.
Subsequent evidence-only commits do not change measured production.

`InputBoundaryRepairTest` converts the supplied probes to assertions in phase-4:

- `patchModuleChangesInvalidateRealNavigation`: real javac builds the valid java.base
  patch twice; changed option-path bytes change the environment, navigation replaces
  its revision and agrees with fresh attribution about the incompatible return type.
- `navigationKeepsModuleEnvironmentsOrderedAndInvalidatesOnlyTheirOwners`: ordered
  conflicting API jars, two module contexts, only the affected module reloads.
- `overlappingSourceAndEnvironmentHaveStableFencesAndCompile`: stable role evidence,
  successful real compiler query, genuine overlay and disk changes still observed.
- `closedOverlayTransitionsSupersedeWithoutPreventingCompletedReuse`: lifecycle
  transitions and edit/revert change fences, content equality still permits reuse,
  unrelated transitions do not invalidate; callback transition supersedes and retry succeeds.
- `nestedAnalysisUsesOneStartAndOneEndCapture`: two captures for attribution, one
  additional capture for the next unchanged diagnostic hit.

The original three tests failed on baseline production (3 assertion failures,
0 errors, 0 skipped); the final phase-3/4 gate passes 140 tests, including the
preceding contribution/policy, committed-owner, failed rebuild, eviction, and lease
regressions. RocksWorkspaceStateTest passes 3 tests. Reporting checks pass 4 Python
harness tests, 3 trend tests and 4 Node dashboard tests. Exact logs are committed in
[input-validation/finish](input-validation/finish). Discovery over every `*test*.py`
was initially an invalid invocation because `test.py` is an argument-driven benchmark
entrypoint; the named reporting test programs above were then run successfully.

### Surviving ownership and removed work

| Boundary | Responsibility after repair |
| --- | --- |
| FileStateRegistry | Shared validated disk contents and inventories; conservative metadata/reconciliation rules retained |
| Documents | Session overlays; weakly retained module transition subscriptions, no closed-file history |
| CompilerInputs | Source/membership/environment identities and role-qualified observation evidence |
| CompilerPool / Analyzer | One configured effective environment, captured start description, fresh end fence before accepting javac output |
| WorkspaceBindings | Per-module compiler snapshots associated with owned facts; final cross-module fence before committing navigation revision |
| IndexedFileManager | Validates the complete environment supplied by its owning compiler, not an independent configuration |
| SemanticUpdatePolicy / FileSemanticContribution | Semantic dependency invalidation and accepted keyed facts, unchanged ownership |

Navigation's ordinary-classpath union and generation-only recipe are gone. Its
explicit-inventory API now requires complete Configuration; application navigation
uses the configured analyzers' actual module snapshots. File-manager validation no
longer constructs an options-empty environment. Compiler options/source roots and
option-supplied paths are defined once by the existing shared boundary. Module order
inside each environment remains meaningful; distinct module environments are not
flattened into an unordered union.

Source-overlay and disk-environment evidence occupy separate roles in the same
CompilerInputs owner. An unchanged overlapping path cannot overwrite its other role's
token. Documents subscriptions retain a monotonic transition count while their module
owner lives; roots/current files define relevance. Weak keys allow discarded module
owners to be collected and are expunged on subsequent subscription/map operations.
Closing an overlay does not erase its transition evidence. Input-content equality is
still distinct from operation-fence equality.

Analyzer now passes its captured immutable description into nested diagnostics,
bindings and compiler operations. CompilerPool performs the fresh end capture before
returning accepted output. Navigation's separate end capture is still necessary:
several module operations can complete before the navigation revision is committed.
The former per-request boolean freshness memo and repeated nested captures are gone.
Actual environment replacement recreates javac's standard file-manager delegate too;
otherwise its retained patch-module option state caused a duplicate-patch failure.
Cumulative work counters survive that replacement.

Production diff against immediate baseline: **172 added / 86 deleted, net +86**.
Against original task-4 baseline: **616 added / 460 deleted, net +156**. This is an
increase, not shrinkage. Per-module fact/environment association, role evidence and
bounded-lifetime transition subscriptions require additional code; the removed
consumer recipes and redundant captures are concrete responsibility reductions.
Benchmark-only InputNavigation bridges old/new APIs for identical harness execution;
there is no legacy production validator retained beside the repaired contract.

### Measurement method

Three serial, alternating baseline/candidate repetitions in isolated processes for
each comparison; same fixtures, harness, dependencies, JDK Temurin 25.0.4.1+1,
`-Xmx1g`, compiler exports and state preparation. Maven 3.9.16; Node 24.19.0.
Component fixture: 128 sources; 40 unchanged operations. Editor fixture: existing
`real` workspace matrix, 32 generated sources, 1 workspace plus restart, 3 warm
samples, 2 edits. Each editor comparison and the separate allocation run checked
504 responses, 3120 ranges and 12 dependency identity sets across 6 workers.
Cold costs are reported separately. Build manifests include source/dependency hashes;
commands, raw results, traces, fixtures and verification summaries are archived.

Component numbers are medians of per-process p50/p95 or work counts, not editor
latency. Thread allocation is exact for the benchmark owner thread, not total memory.
Metadata counts instrument selected Java Files calls; instance `toRealPath` work and
other native syscalls are not included. Internal JVMD counters have no JDTLS analogue.
Original baseline had no CompilerInputs counter: zero capture counts there mean
unavailable, not zero work. Editor table uses the existing reporting flow's median
of worker medians. `editor-warm-distributions.json` additionally retains pooled
post-first samples (18 per operation/comparison side) and nearest-rank p95; samples
within a process are not independent experiments. Three repetitions support no claim
of statistical certainty.

| Immediate baseline → repaired component | Before | After |
| --- | ---: | ---: |
| Unchanged input validation p50 / p95, ms | 2.206 / 4.517 | 1.576 / 3.186 |
| Unchanged detached navigation p50 / p95, ms | 1.151 / 1.821 | 0.998 / 1.788 |
| Warm real diagnostics p50 / p95, ms | 0.873 / 1.317 | 1.034 / 1.923 |
| Warm diagnostics owner-thread allocation, bytes / 40 requests | 10,370,664 | 10,312,440 |
| Warm diagnostics captures / metadata checks | 40 / 5,360 | 40 / 5,360 |
| Warm source/classpath enumerations, bytes hashed, map rebuilds | 0 | 0 |
| Body diagnostics captures / metadata checks | 12 / 1,599 | 3 / 402 |
| Body diagnostics p50, ms / allocated bytes | 19.507 / 3,553,648 | 15.433 / 1,262,720 |
| API diagnostics captures / metadata checks | 22 / 2,934 | 4 / 540 |
| API diagnostics p50, ms / allocated bytes | 70.817 / 8,559,712 | 52.248 / 4,015,008 |
| Membership diagnostics p50, ms | 37.713 | 24.912 |
| Environment diagnostics p50, ms | 74.109 | 70.651 |
| Cold diagnostics p50, ms | 900.137 | 817.276 |
| Cold input validation p50, ms | 228.447 | 236.868 |

Body edits still compile one file and reuse the dependent diagnostic; API changes
compile two. The full raw component summary includes enumeration, hashed bytes,
metadata, map reconstruction, captures, allocations and javac counts for every phase.
Detached body navigation regresses 5.217→7.597 ms (+45.6%) and allocated bytes
1,094,400→1,226,048; this is not hidden by the validation improvement.

| Real LSP workload (ms unless stated) | Immediate baseline | Repaired | Original baseline | Repaired in original pair |
| --- | ---: | ---: | ---: | ---: |
| Warm references | 7.580 | 8.525 | 6.989 | 10.064 |
| Warm completion | 5.630 | 5.082 | 5.241 | 4.857 |
| Warm definition | 5.109 | 5.060 | 6.642 | 5.618 |
| Warm binary completion | 6.623 | 7.901 | 7.283 | 7.711 |
| Warm rename preview | 10.735 | 13.703 | 12.453 | 13.384 |
| Diagnostics error / restore | 230.288 / 214.658 | 217.895 / 213.700 | 228.140 / 215.048 | 224.445 / 214.300 |
| Fresh setup | 2,992.494 | 2,972.465 | 2,909.485 | 3,082.393 |
| Restart | 2,013.573 | 2,016.584 | 1,823.825 | 2,001.134 |
| Total measured work | 7,840.015 | 7,957.756 | 7,338.977 | 7,803.627 |
| Peak process-tree RSS, MiB | 489.016 | 491.355 | 482.797 | 491.930 |

Pooled warm p50/p95 against immediate baseline: references 9.684/21.059→9.148/20.077
ms; completion 5.319/8.100→5.601/19.280 ms; definition 5.330/8.922→5.823/9.530 ms.
These include restarted-workspace samples and differ from the existing worker-median
aggregation; completion's worse tail is visible. All operation distributions and
per-process min/max remain in raw summaries.

Separate JFR-profiled processes estimate 1,494.019→1,467.593 MiB allocated across
whole daemon runs (sample weights, all Java threads). Profiled timings are not mixed
with the unprofiled latency table. Resource summaries preserve recording hashes and
sample counts; `profile-stacks.json.gz` retains inclusive sampled CPU/allocation stacks.
Raw JFR recordings are omitted, following the existing reporting flow's data-minimizing
policy; they can be regenerated with `--profile`. These samples are neither retained
heap measurements nor proof of per-request allocation reductions.

### Remaining costs and limits

Warm diagnostics allocation versus original baseline remains worse:
5,917,888→10,312,440 bytes per 40 requests (+74.3%), with p50 0.795→0.901 ms and
p95 1.086→1.789 ms in that pairing. The shared contract observes all tracked source
metadata (5,360 checks versus 280 counted calls), replacing the old 40 source
enumerations and narrower validation. Warm hits already have one capture; deleting
it or assuming a quiet watcher is sufficient would weaken correctness. Registry
attribute-map allocation and conservative metadata observation remain real costs.
Profiles show allocation under CompilerInputs in filesystem attributes/maps and
missing-path exceptions; inclusive samples cover setup as well as requests, so they
do not establish a precise cause for each latency regression.

Navigation now captures each actual configured module environment instead of a
reduced union. It reuses unchanged merged identity maps, but still visits relevant
module contexts and validates before accepting a combined result. This necessary
work, metadata allocation and request noise are plausible contributors to references
and rename costs; no causal percentage is claimed. Total measured editor work is
+1.5% versus immediate baseline and +6.3% versus original. This repair fixes correctness
and removes redundant nested validation; it does **not** establish an overall editor
speedup or lower total memory. Further metadata representation work is deferred rather
than introducing another cross-request cache or weakening freshness guarantees.

Local JDTLS distribution acquisition was unavailable; local alternating measurements
are JVMD-only. Hosted Merge Review runs the existing JVMD/JDTLS correctness comparison.
CI results for the exact published production commit are recorded in the final
progress checkpoint and PR description; prior green CI is not substituted for the
new regression execution.

### Reproduction

Use the checked-in harness at the tested revision. Create separate checkouts for
0347d7a, 9ac81c4 and 67cf8dc (or identical-tree published 82bf709), build each checkout's
runtime dependencies, then run `prepare.py --repo CHECKOUT --dependencies LIB
--java-home JDK --output BUILD` and `run.py BASE/build.json HEAD/build.json OUTPUT
--runs 3`; run `summarize.py OUTPUT SUMMARY.json`. Repeat with original baseline. Each archive's
`commands.json` records exact compiler/JVM invocations and source fixture preparation.
For LSP use `workspaces/compile.py` per checkout and `workspaces/matrix.py --repo REPO
--before BASE/build.json --after HEAD/build.json --java-home JDK --jdtls JDTLS
--resolvers RESOLVERS --fixtures FIXTURES --root OUTPUT --runs 3 --sources 32
--workspaces 1 --samples 3 --edits 2 --fixture-names real --modes main after`.
Run `verify.py OUTPUT VERIFICATION.json` and `summarize.py OUTPUT SUMMARY.json`; use a separate output and `--profile`
for allocation. The archived matrix manifests preserve exact machine paths/flags.
Run phase-3/4 with `mvn -B -pl jvmd-tests -am -Dgroups=phase-3,phase-4
-Djvmd.resolvers=RESOLVERS -DargLine=-Xmx1g test`; baseline assertion run substitutes
`-Dtest=InputBoundaryRepairTest -Dsurefire.failIfNoSpecifiedTests=false` for groups.
The failing baseline test source is in the first repair commit (published e3c25ae);
use that source with 0347d7a production to reproduce the original three failures.

Final CI: [Checkpoints](https://github.com/maxjay/jvmd/actions/runs/35670736591),
[Distributions](https://github.com/maxjay/jvmd/actions/runs/35670736629), and
[Merge Review](https://github.com/maxjay/jvmd/actions/runs/35670736583) all passed on
published production 82bf709b3a70b6b35dfde617dea1eef61eeb80cb. The latter includes
hosted JVMD/JDTLS correctness and semantic-state/input-validation evidence. Raw test
logs are gzip-compressed without changing their contents.

## Final late-review verification (2026-09-22)

**This is the current result.** The bounded-finish section above describes the
surviving ownership/repairs; its earlier revision and measurement tables are historical.
Three additional inline comments arrived during that work. All seven review threads
are now resolved. No replacement PR or parallel observation owner was introduced.

Final benchmarked production/harness: local
`12b917a5898a4a3ed461182b6bb3aaa42a50723d`, published
`c0924f5d57a3e2ba02169f4b77567e54d19aa7f7`, identical full tree
`984a3ea1a132157ba241553757753435f323e9e7`. Immediate and original baselines remain
0347d7ab58a9c176ac3be027d36c0f2a72209459 and
9ac81c4bdded8de18537569debf02e789c24fe27. Subsequent changes add only evidence and
strengthen a regression to reconcile an environment inventory inside a real compiler
callback; measured production/harness files are unchanged.

Disk evidence formerly compared evictable Entry references. FileStateRegistry now
returns matching hash/evidence atomically and CompilerInputs compares their values.
A normal hit returns the existing observation object; eviction/reconciliation cannot
manufacture a transition solely by allocating another object. Genuine stamp/content
changes still differ. Environment inventory observations carry immutable directory
stamp/child evidence separately from the member list. Create/delete reversion changes
the fence even when the environment content identity returns to the previous value.
Equal reconstruction after eviction stays equal. No second registry or hash was added.

Source inventory retains its existing membership and supplied-byte validation, not
unfiltered directory-stamp invalidation: an initial broader attempt counted compiler
persistence writes underneath source roots as input changes, which existing tests
correctly rejected. The new directory evidence is consumed for environment paths.
The implementation still uses conservative metadata observation, not watcher quietness.
The third comment, obsolete evidence retention, was already repaired by separate role
maps and pruning against each role's current inputs; it now has an explicit assertion.

New normal phase-4 regressions:

- `evictedDiskObservationsDoNotManufactureSupersession`: forced registry removal,
  full reconciliation (including environment inventory), unchanged equality, successful
  real compiler callback, and a subsequent genuine source change.
- `revertedDirectoryMembershipStillSupersedesCompilerWork`: equal contents with changed
  fence, stable next capture, real compiler supersession, successful retry. Directory
  time is changed deterministically; no sleeps or timing race.
- `observationsReleasePathsRemovedFromEitherRole`: deleted source/environment paths
  are absent from both role evidence sets.

On c2b1669 production the focused 8-test suite had exactly **2 assertion failures,
0 errors and 0 skips**; pruning already passed. Final **143 phase-3/4 tests** pass,
plus 3 Rocks workspace and 11 reporting tests. [Checkpoints](https://github.com/maxjay/jvmd/actions/runs/35672018976),
[Distributions](https://github.com/maxjay/jvmd/actions/runs/35672019034) and
[Merge Review](https://github.com/maxjay/jvmd/actions/runs/35672018975) all pass on
c0924f5, including hosted semantic-state, input-validation and JVMD/JDTLS correctness.
Final raw evidence and logs are in [finish/late-review](input-validation/finish/late-review).
The complete baseline/repair chronology is retained rather than overwritten.

Production LOC versus immediate baseline: **218 added / 106 deleted, net +112**;
versus original baseline: **645 added / 463 deleted, net +182**. Extra late-review code
represents value-stable observation evidence and directory metadata evidence inside
existing owners. There is no shrinkage claim.

All workloads were repeated with the same method described above: three serial
alternating isolated runs, same JDK/dependency hashes/fixtures/1 GiB heap, separate
cold/warm phases and separate JFR run. Each editor comparison and profile run again
verified 504 responses, 3120 ranges and 12 dependency identity sets. The existing
harness additionally counts directory-evidence rebuilds; warm count is zero. This
counter is absent in older implementations and is not a JDTLS metric.

| Immediate baseline → final component | Before | After |
| --- | ---: | ---: |
| Input validation p50 / p95, ms | 2.140 / 3.446 | 1.568 / 2.662 |
| Detached navigation p50 / p95, ms | 1.071 / 1.624 | 0.961 / 1.497 |
| Warm real diagnostics p50 / p95, ms | 0.858 / 1.410 | 1.321 / 2.480 |
| Warm diagnostic bytes / 40 requests, owner thread | 10,372,064 | 9,949,624 |
| Warm diagnostic captures / metadata checks | 40 / 5,360 | 40 / 5,360 |
| Warm enumerations / hashed bytes / identity-map rebuilds | 0 / 0 / 0 | 0 / 0 / 0 |
| Body diagnostic captures / metadata checks | 12 / 1,599 | 3 / 402 |
| Body diagnostics p50, ms / allocated bytes | 21.095 / 3,550,176 | 24.870 / 1,238,576 |
| API diagnostics p50, ms | 65.827 | 53.564 |
| Membership diagnostics p50, ms | 35.384 | 23.494 |
| Environment diagnostics p50, ms | 82.517 | 65.538 |
| Cold diagnostics p50, ms | 887.376 | 828.870 |
| Cold input validation p50, ms | 243.026 | 220.693 |
| Explicit reconciliation validation p50, ms | 133.775 | 129.173 |

Body/API javac counts remain 1/2, preserving dependent diagnostic reuse. Detached body
navigation regresses 4.695→6.606 ms. The warm diagnostic latency regression is not
hidden by lower allocation or fewer captures elsewhere. Against original baseline,
warm diagnostic allocation remains **5,752,592→9,949,624 bytes (+73.0%)**; p50/p95
0.760/1.429→0.848/1.384 ms in that separate pairing. Repaired timings vary across
pairings; these few repetitions support no statistical or universal speedup claim.

| Real LSP (ms unless stated) | Immediate | Final | Original | Final in original pair |
| --- | ---: | ---: | ---: | ---: |
| Warm references | 8.854 | 8.705 | 6.872 | 8.848 |
| Warm completion | 5.101 | 5.042 | 5.203 | 5.767 |
| Warm definition | 6.287 | 4.493 | 4.326 | 5.654 |
| Warm binary completion | 6.209 | 6.684 | 5.823 | 7.467 |
| Warm rename preview | 11.402 | 12.998 | 11.303 | 13.227 |
| Diagnostics error / restore | 224.634 / 213.878 | 220.493 / 212.467 | 223.884 / 213.361 | 221.425 / 213.039 |
| Fresh setup | 2,973.550 | 3,025.823 | 2,810.126 | 2,893.686 |
| Restart | 1,904.018 | 1,968.262 | 1,791.097 | 1,878.696 |
| Total measured work | 7,767.382 | 7,796.263 | 7,213.601 | 7,544.792 |
| Peak process-tree RSS, MiB | 476.477 | 501.277 | 488.383 | 487.336 |

Total measured editor work is **+0.4% versus immediate baseline, +4.6% versus original**.
Whole-run JFR sampled allocation is 1,499.106→1,465.844 MiB in the separate profile
run, not retained heap or per-request allocation. Raw summaries retain all operations,
per-process distributions, metadata/enumeration/hash/map/capture work and cold costs;
`editor-warm-distributions.json` also reports pooled p50/p95. `profile-stacks.json.gz`
contains inclusive allocation/CPU samples, with recording hashes in resource reports.

The remaining diagnosis is unchanged: warm metadata traversal still visits all tracked
inputs, and navigation validates actual module environments rather than a reduced
union. No safe single warm capture was removed. Remaining metadata allocation and
editor regressions are limitations, not claims of completion of a separate future
performance redesign. Original baseline reconciliation is a harness no-op, so its
1.691 ms versus final 126.557 ms is not equivalent work; cold original identity checks
also omitted the platform hashing now required (47.723→213.732 ms). Both differences
remain explicit rather than being presented as like-for-like speed regressions.

`finish/late-review/reproduce.sh` records final commands. Supply equivalent checkout,
JDK, resolver and dependency directories for another machine; build manifests and
archived `commands.json` record all input hashes and exact runtime flags. Verification
syntax is `workspaces/verify.py OUTPUT VERIFICATION.json`; summarization takes both
input directory and output JSON. An initial verification invocation omitted its output
argument after a completed matrix; it was corrected and verification succeeded before
continuing. No failed correctness result was skipped or discarded.
