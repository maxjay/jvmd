# Semantic state progress — append only

Checklist: `SEMANTIC-STATE-CHECKLIST.md`.
Each entry records the change, validation, limitations and next work. Historical entries stay intact.

## 2026-09-20 — 001: implementation started

- Pinned `ae23fd1f44573bd3427c0e77f967150173669a2f` after fetching the optimisation branch.
- Created isolated branch `architecture/semantic-state`, based on `perf/compact-grams-merge`.
- Added checklist with required invariants, prohibited shortcuts and explicit acceptance gates.
- Initial source review found local symbol-kind leakage in API hashing, snapshot epoch adoption
  after validation, accumulated dependency edges, and whole-workspace navigation reaggregation.
- Validation environment: the prior extracted JDK 25 module image is incomplete. Checking its
  retained archive before restoring a separate toolchain. No source changes have been made yet.
- Completed: A0. Next: A1 baseline and A2 typed contracts.

## 2026-09-20 — 002: reproduced baseline defects and measured navigation

- The checksum-pinned JDK archive is intact. Restoring its full module image immediately before
  validation permits compilation; the workspace retained an incomplete extracted module image.
- Compiled all 130 production source files directly with javac 25 and existing packaged dependencies.
  Maven dependency retrieval is blocked by the current network environment; CI remains the Maven gate.
- Added a standalone production-path probe and retained `docs/performance/semantic-state/before.json`.
- Baseline reproduces both defects: `local_rename_api_equal=false` and
  `publication_race_stale_reuse=true`. These are now execution findings, not only source concerns.
- Measured cold/warm/body/API navigation at 128 and 512 files, three runs each. No JFR, AOT or
  claims about real-project performance. The probe checks the expected incoming reference.
- Implementing typed declaration contracts and revision fences next; gates remain unticked until
  the updated probe and targeted regressions pass.

## 2026-09-20 — 003: typed contracts and persistent navigation implemented

- Typed declaration contracts now drive API identity; diagnostics identity/schema advance to v3.
  Bounded structural interning retains at most 8,192 contracts. It never trusts hash equality alone.
- Source inventory/identities now use Documents-owned SourceSnapshots and FileStateRegistry.
  Its sequence/deltas describe successive requested views, not an authoritative filesystem epoch.
  Existing precise-root watcher epochs remain the fast-validation evidence; coarse roots still scan.
- Shared DependencyGraph operations distinguish complete replacement from partial accumulation.
  Analyzer and navigation use it; persisted Rocks semantic invalidation is still a separate wrapper.
- Published navigation uses persistent AVL maps and per-file postings. API-equal edits still update
  references, locations and docs. Budget admission uses incremental per-fragment size estimates.
- All production classes compile directly with javac 25. Standalone regressions passed 5,140 checks:
  typed contracts, persistent map oracle/old versions, interner collisions, dependency replacement,
  cold/warm/peek publication races, additive Merkle adoption, classpath order, memory admission,
  preserved timestamps, buffer changes, hierarchy, rename, deletion and negative lookup recovery.
  Recovery output exactly matches a separate application's cold attribution.
- Before/after probe passed. Body edits update 1 file; API edits update 17 (declaration + 16 consumers).
  At 512 files the body-edit median changed from 56.13 to 39.08 ms, but cold from 599.32 to 1216.97 ms,
  warm from 4.42 to 16.59 ms, and API edits from 63.14 to 95.13 ms. Three samples, synthetic fixture.
  These regressions are open performance work, not a successful overall speed claim.
- Completed local gates A1–A4, B1–B4, C1–C4, D1. Maven/CI and persistence/design documentation
  remain open. Added a portable standalone runner and a required checkpoint step for those checks.

## 2026-09-20 — 004: remote checkpoints pass; costs and boundaries documented

- Published draft PR #8 against `perf/compact-grams-merge`:
  https://github.com/maxjay/jvmd/pull/8 . Initial implementation is remote `46b947ec`.
- Checkpoints run 35534874482 completed successfully: Maven compile/package, standalone semantic
  regressions, runtime/AOT assembly, resolver, index/Rocks, compiler, processing, overlays,
  documentation, runtime/hot swap, protocol/rename and LSP/editor gates all passed.
- Added phase timing counters. The loader dominates the profiled cold/API cost; navigation
  maintenance for the 512-file body edit is below 1 ms. Source validation is still proportional
  to workspace size on coarse roots. Repeated timings vary materially; all raw runs are retained.
- Avoided constructing contracts for other fragments' reference copies. Normalized inventory roots.
  Added explicit conservative package/module metadata invalidation and a package-annotation test.
- Follow-up production compilation and 5,141 standalone checks pass locally. (Restricting contracts
  to owned declarations changes the number of interner assertions; prior counts are historical.)
- Added `docs/semantic-state.md` and the performance report. Amended `docs/design.md` to identify
  Rocks as the shipped default and the old SQLite physical schema as historical/provider-specific.
- Added open E1–E3 performance/heap/consolidation gates. Source observations are view-specific;
  current navigation is structurally shared, not Merkle-addressed or durably cross-store atomic.
- Completed D2 and D4. D3 awaits CI for the follow-up code; the preceding implementation's complete
  checkpoint success must not be represented as validation of this later commit.

## 2026-09-20 — 005: inherited private ownership corrected before acceptance

- A targeted compiler experiment found that a private nested superclass can expose public
  members through a public subtype. Its return-type change broke a consumer while leaving
  the initial typed fingerprint unchanged. Corrected the contract rule before merge.
- Captured hidden ancestor contracts and their non-private members/accessibly inherited nested
  types when reached from accessible declarations. Unexposed private/local implementation stays
  excluded. Added both identity assertions and actual dependent-diagnostic invalidation coverage.
- Contract schema is now `declaration-contract-v2`; diagnostic context/schema advance to v4 so
  snapshots from the interim draft cannot restore the old unsound identity.
- All 105 production source classes compile locally and the standalone suite passes 5,150 checks.
  The final production probe passes; retained `final.json` and appended its measurements to the
  performance report. This is correctness evidence, not closure of the open performance gates.
- Correction to entry 002: direct baseline compilation covered 99 production `dev/...` Java
  sources, not 130. Six production classes were added. Maven CI additionally compiles module
  descriptors, providers and tests; its results are recorded separately.
- Follow-up `e6302ae4` passed every test step in Checkpoints run 35535326180; artifact upload was
  completing at observation. Initial commit also passed all four Linux/macOS distribution builds
  and the Windows installer check. The inheritance correction requires its own CI run next.

## 2026-09-20 — 006: final implementation validated and delivered as a draft

- Exact final source commit: `56a9506a33af1290578fe46025aa348ae8be7bfd`.
- Checkpoints run 35535719185 completed successfully, including the standalone semantic suite,
  Maven build, AOT assembly, resolver, index/Rocks, compiler, processing, overlays, documentation,
  runtime/hot swap, protocol/rename and LSP/editor gates:
  https://github.com/maxjay/jvmd/actions/runs/35535719185 .
- Distribution run 35535719182 passed Linux x64/arm64, macOS x64/arm64 and the Windows installer
  check; release publishing was skipped as expected for a PR:
  https://github.com/maxjay/jvmd/actions/runs/35535719182 .
- Closed D3. All implementation gates A–D are complete. The final evidence update changes only
  documentation/checklist state; the source validated above is unchanged.
- E1–E3 remain open: representative paired performance, allocation/retained-heap validation and
  the next consolidation decision. Current synthetic results do not establish an overall speedup.
  The PR remains a draft and has not been merged: https://github.com/maxjay/jvmd/pull/8 .
- Next concrete work is measurement against representative workspaces with those gates preserved;
  parser/database replacement and durable cross-store roots remain separate design decisions.

## 2026-09-20 — 007: acceptance reopened; diagnosis and replacement now come first

- The user challenged the performance regressions and the claim that the architecture had been
  consolidated. That challenge is valid: shared types/helpers and successful correctness tests
  do not establish one owner for computation or eliminate repeated work across consumers.
- Superseding entry 006's overall completion status: the implementation and recorded CI evidence
  remain, but consolidation/performance acceptance is incomplete. No production code was changed
  or reverted during this checklist revision. Original baseline remains `ae23fd1f`; draft source
  comparison point remains `56a9506a`.
- Added required recovery sequence R0–R6: pin evidence; trace ownership/repeated work; establish
  controlled paired timing/allocation/heap measurements; isolate the minimum correctness fixes;
  design the smallest complete replacement with an explicit removal list; implement/measure
  each replacement separately; validate the final matrix and ownership ledger.
- Reopened A1, B1, C3, C4, D1 and D3 with explicit missing acceptance evidence. Narrowly completed
  correctness/implementation items retain their checks. E1–E3 are now prerequisite acceptance
  gates, including actual removal of redundant work, rather than a promise of another future slice.
- Required each abstraction to name the work it retires. Persistent maps and interning are
  optional implementation choices whose CPU/memory tradeoffs must earn their place. Mandatory
  correctness fences and conservative filesystem reconciliation must not be removed for timings.
- Concrete extra operations identified earlier remain cost hypotheses, not isolated explanations
  of the reported slowdown percentages. Existing three-sample unpaired timings do not settle
  causality. No new performance measurement is claimed by this documentation change.
- Next action: R1's call-path/ownership ledger, followed by R2's controlled comparisons before
  production refactoring. PR #8 remains a draft.

## 2026-09-20 — 008: readable simplification and semantic Merkle integration required

- The user requires lower overall code size/complexity through good structure and elimination of
  repeated responsibilities, explicitly rejecting shorter names, compressed formatting or other
  line-count shortcuts. Made this an acceptance requirement, not a cosmetic cleanup task.
- Added E4: compare consistently formatted handwritten production code across all affected modules
  against `ae23fd1f`, count new helpers, and identify the duplicated representations/logic/validity
  decisions actually removed. Readability, public behaviour, safety checks, diagnostics and test
  coverage stay intact. Moving equivalent complexity into generation/dependencies is not removal.
- Added E5 and the required semantic Merkle design: deterministic/versioned ownership roots from
  declaration/type contracts through file, module and workspace API identities; separate source,
  reference, documentation and location validity; reuse unchanged subtrees and stop API propagation
  at unchanged roots while retaining consumer/context/fallback correctness.
- Required canonical encoding and ordering, separate cyclic reference edges, and evidence of
  avoided scans/hashes/publications. Current typed fingerprints and AVL snapshots do not meet this
  target by themselves. The existing filesystem Merkle guarantees must be incorporated explicitly.
- R4 must now name the Merkle composition and the old hash/invalidation paths it replaces; R6
  includes the E4/E5 audits. Green parsing, custom storage and durable cross-store publication stay
  separate decisions; this does not defer the required in-process semantic Merkle consolidation.
- This update changes requirements/documentation only. No code reduction, performance gain or
  semantic DAG implementation is claimed. The next implementation work still begins with R1/R2
  ownership tracing and controlled measurement, then the smallest complete measured replacement.


## 2026-09-20 — 009: restart at the requested baseline

- User explicitly requested reverting to `ae23fd1f44573bd3427c0e77f967150173669a2f`
  and establishing proper before/change/after benchmarks before further architecture work.
- Preserved `9611f45` as `archive/semantic-state-first-attempt`; pushed revert commit
  `7c16c60547586e970a46382aedb928f5a5c97983`. Its entire tree equals the requested
  baseline (`git diff --exit-code ae23fd1f 7c16c60`), without rewriting branch history.
- Restored only the checklist and append-only log after that exact restore. Old completion
  ticks are historical and do not describe the restarted code. Added S0–S5 as the active
  sequence; readable simplification and semantic Merkle outcomes remain required.
- Next: build and record the measurement contract, run the unchanged baseline, then choose
  a small replacement based on measured duplicate work. No production edits made yet.


## 2026-09-20 — 010: benchmark suite prepared before production changes

- Added `benchmarks/semantic-state/suite.py`, `NavigationBenchmark.java`, and the
  measurement contract. Production remains byte-for-byte at `ae23fd1f`.
- Uses separate recorded builds, third-party-only dependency classpaths, checksum-pinned
  Temurin 25.0.4.1+1, serial alternating independent JVMs, fixed baseline real-source
  fixtures, JVM-wide allocation, GC, RSS, work counters and correctness assertions.
- Covers dispatcher navigation at 128/512 files, a separate 32-file cold-JVM workload,
  real JVMD core sources, body/API/position edits, rename preview, and explicitly
  labelled precise-epoch/coarse-validation mechanisms. Broad Merkle acceptance remains open.
- Pilot passes the complete workload and output checks. Initial preparation hit an
  incomplete saved JDK and unavailable `/usr/bin/time`; restored a checksum-verified
  JDK and use Linux `/proc/self/status` for process high-water RSS. Neither failed
  setup produced a usable performance sample. Raw pilot/setup directories are retained.
- The README fixes ten-pair sampling, primary-workload selection, gain/regression rules
  and uncertainty reporting before changes. Next: ten-run baseline and separate profile.


## 2026-09-20 — 011: baseline measured; first replacement selected

- Completed ten untouched-baseline JVM runs plus a separate JFR profile. All suite
  correctness checks pass. Raw inputs/samples are in `baseline.json.gz`; summary
  and selected profile attribution are in `docs/performance/semantic-state-restart`.
- Baseline 512-file medians: cold 623.45 ms / 182.22 MB allocated, warm 4.08 ms,
  body edit 47.25 ms, API edit 68.25 ms. These are this suite's values; they are not
  interchangeable with the archived three-run experiment.
- Profile and code inspection identify duplicate source observation in WorkspaceBindings.
  Recorded the ownership ledger and replacement proposal before touching production.
- Primary target is >=10% lower 512-file cold-request allocation, with its paired
  interval below zero. Replace navigation's separate stamp/hash cache with Documents'
  existing sourceHash, including one Unix metadata read per normal validation.
- Preserve before/after read stamps and provider fallback. Semantic Merkle composition,
  coarse enumeration, API projection and publication-fence fixes remain open.


## 2026-09-20 — 012: first candidate implemented and measured, acceptance pending

- Candidate deletes WorkspaceBindings' private Stamp record, hash cache, hashing method
  and eviction path; navigation observes sources through Documents.sourceHash. The
  shared FileStateRegistry reads Unix file kind and change metadata together while
  retaining the read-stability check and non-Unix fallback. Production diff: +13/-23
  lines, net -10; no formatting/name compression or moved production helpers.
- Added four focused ownership/provider/buffer tests. All 29 tests in the selected
  identity, navigation, API invalidation and completion classes pass. An initial test
  setup lacked a valid resolver bundle; restored it from checksum-verified distribution
  artifact 10612489698 and reran the complete selected set. Failed setup logs remain.
- Added a dedicated GitHub workflow for repeated paired benchmarks and evidence upload.
- First ten-pair comparison meets the primary allocation target (~19% less allocated
  on 128/512-file cold navigation), but latency is mixed. In particular, the 128-file
  API-edit paired median is +17%, interval roughly +8% to +28%; several other intervals
  cross the predeclared regression boundary. This is not accepted as an optimization.
- Running the one predeclared additional ten-pair campaign and a separate after-profile.
  The original comparison is retained and will be combined, not replaced by a better run.


## 2026-09-20 — 013: retain benchmarks, archive the unaccepted candidate

- Combined all 20 alternating JVM pairs (40 JVMs) without discarding the first campaign.
  The primary 512-file cold allocation changed from 183.20 MB to 148.14 MB, paired
  median -18.99% with interval [-19.06%, -18.95%]. The 128-file allocation is -19.29%.
- Latency remains inconclusive against the preregistered regression boundary for several
  edit paths, including 128-file API edits (medians 50.17 -> 53.77 ms; paired change
  +11.8%, interval [-2.5%, +18.4%]) and real-module position edits. This is not a claim
  that every path is slower, nor sufficient evidence to accept the candidate.
- Preserved the exact candidate production files and four new tests in commit
  `2a3beb3750b878264c76525f02bd9e35f157b5d0` on `experiment/source-identity-ownership`.
  Verified the two production files against the build's recorded source hashes first.
- Restored working-branch production and repository tests to `ae23fd1f`; the candidate's
  new ownership tests stay with the experiment. All 25 selected baseline tests pass
  after restoration; all 29 candidate tests had passed. No test was weakened to accept
  the candidate. The current CI benchmark workflow uses the applicable existing tests.
- Published the complete scenario table, all baseline/comparison samples, build metadata,
  profile attributions, commands and test output in `docs/performance/semantic-state-restart`.
  Before/after medians and paired-ratio statistics are explicitly distinguished.
- Retain the reusable suite and dedicated GitHub workflow. S4/S5 are complete for this
  experiment; S3 and E1–E5 remain open. Production code reduction on the restarted
  working branch is zero because the unaccepted change was removed. The semantic
  Merkle DAG is not implemented by this restart. PR #8 remains a draft.

## 2026-09-20 — 014: latency is the primary objective

- The user emphasised latency after the allocation-focused first trial. Updated the
  checklist and benchmark contract so the next primary outcome must be end-to-end
  latency, selected and profiled before implementation. Allocation/code reduction
  alone cannot qualify an optimization for acceptance.
- The archived trial's latency was mixed: 128-file body/API medians rose from
  22.70/50.17 ms to 24.44/53.77 ms; 512-file medians fell from 43.35/65.37 ms to
  41.17/63.59 ms. The 512-file warm path had a paired improvement interval below zero,
  but most apparent gains were inconclusive and other paths allowed regressions.
  There is no accepted latency improvement on the working branch.
- Require latency-first reports, all cold/warm/edit/rename scenarios, variability and
  descriptive sample p95. Tail-latency claims need suitable additional coverage.
  Set per-path regression budgets before the next trial; do not reuse the first
  trial's blanket 1 ms floor to excuse regressions in already-fast warm requests.
- Historical criteria and results are preserved. This is a requirement update only;
  production remains at ae23fd1f and semantic Merkle implementation remains open.

## 2026-09-20 — 015: complete semantic replacement and accumulator extension boundary

- User confirmed shared semantic Merkle architecture and requested extensibility for future
  algebraic fingerprints, plus a full before/implementation/after benchmark sequence.
- Replaced overlapping active checklists with one eight-step checklist. Historical evidence
  remains unchanged. Production is still exactly ae23fd1f; no accepted DAG implementation.
- Recorded domain/schema/algorithm identity, ordered versus keyed unordered collections,
  key-bound contributions and old/new replacement semantics. Accumulator cryptography remains
  a future implementation choice; the semantic consumers will not depend on a SHA string.
- Added two-module lifecycle, diagnostic, buffer, file add/delete, context and old-reader
  scenarios to the reusable suite, and a separate retained-live-heap campaign. Fixed latency
  and allocation targets plus per-path regression budgets before production edits.
- Building and measuring the expanded untouched baseline next.

## 2026-09-20 — 016: expanded baseline completed before production edits

- All ten baseline JVMs completed the expanded suite with correctness assertions intact.
- `navigation_512/cold_workspace` baseline median: 673.313 ms.
- `navigation_512/warm` baseline median: 4.423 ms.
- `navigation_512/body_edit` baseline median: 43.330 ms.
- `navigation_512/api_edit` baseline median: 69.191 ms.
- `lifecycle_65/cross_module_api` baseline median: 31.929 ms.
- Raw campaign and exact build retained under semantic-dag/baseline; separate profile/live-heap run started.
- Production remains ae23fd1f at this checkpoint. Begin the complete semantic/consumer replacement.

## 2026-09-21 — 017: semantic capture and incremental consumers implemented; validation in progress

- Removed ApiFingerprint's per-consumer filtering/sorting/JSON projection. Bindings now captures
  detached DeclarationContract values once and supplies a SemanticApi to Analyzer, navigation
  and source-index publication. Versioned persisted diagnostic schema to reject old identities.
- Added canonical keyed Merkle ownership composition with declaration/type, file, module and
  workspace nodes. Unchanged file APIs reuse prior nodes, and updates share unrelated modules.
  Domain/schema/algorithm identity plus keyed replacement is the future accumulator boundary.
- Replaced whole-workspace aggregate construction and JSON admission serialization with
  persistent navigation contributions and structural size estimates. Shared dependency graph
  operations now replace complete observations while retaining partial edges conservatively.
- First functional paired pilot passed the full benchmark output assertions. It is not a
  statistical acceptance result. Local-edit work fell; full rebuild/query paths still needed
  attention. Replaced repeated contributor scans with ordered declaration ownership selection
  and migrated rename/occurrence queries to symbol postings rather than global iteration.
- First correctness compile hit ambiguous generic AssertJ overloads; corrected explicit types.
  Next run passed 41/43 tests: one new fixture had incorrect source roots, and an existing
  multi-module test requires Maven version detection unavailable in this container PATH.
  Fixing fixture/environment without weakening assertions. Failed logs retained.
- Separate baseline heap assertion mistakenly required exactly one type-name match (constructors
  share that name); corrected it to require the class and reran successfully. Latency workload
  code is unchanged; before-v2 records the corrected harness hash.

## 2026-09-21 — 018: integrated candidate passes focused correctness; paired campaign started

- Candidate-5 passes 53/53 focused tests, including nine new semantic-tree/map/contract tests,
  API invalidation, references, rename/static imports, persisted diagnostics, module changes,
  completion, source overlays and index publication. Workspace API root equality now gates
  reverse navigation propagation; roots are not only status metadata.
- Navigation queries use symbol postings, and fragment replacement shares the persistent
  map instead of copying all fragments. Canonical priorities apply to semantic aggregates;
  navigation maps use random treap priorities because their internal shape is not an identity.
- Preserved compiler context and publication fences. Fixed an empty-string constant identity
  distinction found during review. No global interner or new parser/database added.
- One immediate external-edit completion assertion failed in candidate-4; the unchanged
  baseline test and candidate-5 full selection passed. WatchService delivery timing is a
  hypothesis, not an established cause or a claimed fix. Failure evidence remains retained.
- Initial same-formatter audit is +845 production lines: the total code-reduction gate is
  unmet despite removed projections/aggregation. No line-count acceptance is claimed.
- Started the complete ten-pair baseline/candidate campaign. Production source/build hashes
  are retained; numerical performance acceptance remains pending.

## 2026-09-21 — 019: first complete comparison; revise broad invalidation costs

- Completed ten alternating baseline/candidate-5 pairs (20 independent JVMs), all benchmark
  output checks passed. Primary 512-file body-edit latency medians: 51.302 -> 23.031 ms;
  paired median -55.8%, 95% interval [-60.7%, -48.6%]. Allocation: 17.98 -> 7.71 MB (-57.1%).
- 512-file API edit: 76.199 -> 59.612 ms, paired -23.1%; warm navigation 4.476 -> 3.731 ms;
  rename 5.177 -> 4.020 ms. Real-source location edit: 58.670 -> 8.785 ms.
- Full acceptance fails: 65-file lifecycle cold/API/buffer/context paths have regressions
  or unresolved intervals. Documentation_position is also an API reversal in this fixture
  (it restores Number after the prior Integer edit); it must not be reported as a pure doc edit.
- Inspection found navigation removing/reinserting all contributions of reanalysed files,
  even when edges/occurrences/symbol rows were equal. Replacing this with per-contribution
  deltas; keep candidate-5 raw evidence. Module-root equality and file membership gates remain.
- Three separate forced-GC before/after pairs completed. Publish all live-heap measurements
  separately from latency, with full-JVM scope and estimate limitations.
- Pushed measured implementation as 1eac607 (tree matches local 5e329d3), preceded by a23d5c5
  benchmark contract. PR remains draft; code-reduction and full performance acceptance open.

## 2026-09-21 — 020: retain structure through revalidation; fix graph lifetime

- Candidate-7 passed 54 focused tests; candidate-8 and candidate-9 pass 55. Added
  exact sparse API response assertions, context revalidation reuse, and deleted-file
  removal with old-reader isolation. No public response fields added for internal identity.
- Candidate-6/7/8 each completed ten alternating pairs with all output assertions intact.
  Preserve all campaigns; none passes full performance acceptance. Candidate-7 512-file
  body edits: 43.932 -> 22.352 ms; API edits: 68.884 -> 48.997 ms. Its lifecycle cold:
  53.606 -> 74.756 ms, buffer API: 28.828 -> 33.234 ms. Regressions remain visible.
- Candidate-8 reuses prior navigation and semantic roots even when namespace/context
  changes require complete attribution. Deleted files remove their contributions explicitly.
  A full compiler revalidation does not require throwing away immutable result structure.
- Diagnostic phase trace shows remaining broad-edit time mostly inside analysis/capture,
  plus roughly 3–5 ms of navigation maintenance in the sampled run. It is not a statistical
  attribution or proof of a single cause. Profiled/diagnostic timings stay out of comparisons.
- Candidate-9 shares task-owned SymbolIdentity across a compiler batch and compares captured
  declaration contracts before reconstructing ownership paths. Analyzer fingerprint history
  now has weak reuse links: it cannot independently retain the full detached graphs after
  admitted snapshots release them. Fingerprint history remains available for correctness.
- Same-formatter production audit: 5,404 baseline lines vs 6,304 candidate lines, net +900.
  This fails the requested overall reduction. Removed duplicate work does not excuse this
  failed gate; no unrelated deletion, shorthand or formatting trick is counted as progress.
- Candidate-9 complete paired campaign started. Earlier published 1eac607 passed Checkpoints,
  Distributions and Semantic state benchmarks; that does not certify these follow-up edits.

## 2026-09-21 — 021: complete measurements; acceptance fails; evidence publication interrupted

- Published candidate-9 production as 227598c73559be34a89af406e59b7ca5f5d97b90.
  All compiled production hashes were checked against that commit before disconnection.
- Ten initial plus ten confirmation pairs completed; all 40 benchmark workers pass output
  assertions. 512-file body edit: 46.454 -> 23.613 ms; paired -47.2% [-52.8%, -39.7%].
  API edit: 74.101 -> 54.760 ms; paired -27.4% [-32.7%, -17.1%].
  Body allocation: 17.98 -> 7.65 MB. The primary gates pass.
- Overall latency gate fails: 32-file warm 2.896 -> 3.315 ms, paired +12.7% [5.3%,26.0%].
  Other startup/small-query/lifecycle intervals remain unresolved. All 34 latency scenarios
  are preserved in the GitHub report; no selection of a favourable campaign.
- Same-formatter code audit is +900 production lines. Overall simplification is not achieved.
- Three separate forced-GC pairs completed. With 13 readers, whole-JVM live heap medians
  20.06 -> 18.46 MiB; after releasing readers 17.81 -> 18.46 MiB. No blanket memory claim.
- 55 focused tests pass. Exact production Checkpoints 35548645908 and Distributions
  35548645915 pass. Benchmark workflow 35548645946 fails the same completion precheck
  on both attempts; timing never starts in that workflow.
- Untouched-baseline repetition reproduces the new-source failure once and timestamp-edit
  failure twice in 20 two-test repetitions. Candidate-9 has zero failed repetitions.
  This proves baseline intermittency, not a correctness fix or waiver.
- The execution environment disconnected (environment_offline) while saving the final report.
  Production was already published. The raw evidence bundle and report generator were local,
  not committed. GitHub now preserves the observed summary and this recovery checkpoint.
  Do not mark full evidence publication complete or claim the raw archives are uploaded.
- Recovery workspace: /workspace/scratch/7164a0d102d4/jvmd-review.
  Raw campaign root: /workspace/scratch/7164a0d102d4/semantic-dag.
  Relevant folders: comparison-9, confirmation-9, combined-9, retained-9, candidate-9,
  tests-9, readability-9, completion-repetition. Earlier campaigns 5–8, baseline and
  profile-6 remain there. Prepared but uncommitted evidence is under
  docs/performance/semantic-merkle; report generator is benchmarks/semantic-state/report.py.
- On recovery, preserve local uncommitted evidence before reconciling the GitHub docs commit.
  Publish manifests/raw campaigns/allocation/p95/GC/RSS and failure logs, verify the report
  generator's mixed-build/forced-GC rejection, and continue unresolved latency/complexity work.
  PR #8 stays draft and unmerged. No algebraic engine, parser or database expansion now.

## 2026-09-21 — 022: recover published state and regenerate missing evidence

- The resumed environment restored an older local checkout (8321d2a), not the workspace
  that produced candidate-9. The semantic-dag directory and its raw archives are absent.
  Preserved the old checkout history, fetched 7e564126, and created continue/semantic-state
  locally; the publication target remains architecture/semantic-state / draft PR #8.
- The earlier observed summary remains historical. New campaigns will be named recovery
  campaigns, not represented as recovery of the original individual samples.
- Restored the exact ae23fd1 baseline worktree. Downloaded and checksum-verified the same
  Temurin 25.0.4.1+1 archive and restored test/formatter dependencies. The restored JDK
  image in the scratch workspace was truncated; rebuilding tool files outside that restored
  directory before measuring. Keep the failed build logs separate from timing evidence.
- Before more production edits: rerun the full baseline/published comparison and profile
  repeated warm navigation, then name the redundant ownership/code that a change removes.
  Keep existing latency, correctness and readable-code acceptance criteria unchanged.

## 023 — Recovered measurements and overview baseline

- [x] Regenerated the published production versus `ae23fd1` comparison: ten alternating pairs, twenty workers, all assertions passed. The old raw files remain unavailable; this is a new campaign on the recovered host.
- [x] Preserve complete campaign/build/source/dependency hashes and samples, worker commands/logs, summary and checksums under `docs/performance/semantic-merkle/recovery/`.
- [x] 512-file body-edit medians on this host: 137.439 → 47.816 ms; paired change −61.2% [−67.4, −56.7]. Small warm results remain uncertain; do not combine these samples with the previous host's rounded summary.
- [x] Added overview benchmark before production edits: cold, warm, and documentation/position edits on 100 methods. Captured ten baseline/published pairs with the new identical harness. Published position-edit allocation is 10.26 MB versus baseline 8.13 MB; paired +26.2%.
- [x] Isolated warm-reference JFR diagnostic completed. JSON serialization, input validation and reference collection dominate visible work; no evidence that warm API hashing explains the remaining latency. Diagnostic timings are excluded from acceptance.
- [ ] Consolidate overview declaration capture with Bindings. Preserve depth, unresolved declarations, sparse protocol fields, current docs and positions. Remove the duplicate compiler-tree pass and measure the result.
- [ ] Overall acceptance remains open: unresolved latency, readable production line increase, and completion CI failure.

## 024 — Consolidate overview capture; latency acceptance still open

- [x] Removed Analyzer's independent declaration scanner, token-location logic and row encoder. Bindings now supplies the overview rows and dependencies in one traversal, using its existing declaration encoder. Overview does not construct an unused declaration-contract/Merkle API graph. Full binding capture retains the shared semantic graph.
- [x] Reference collection filters selected roles before sorting and makes one immutable result, removing the temporary all-target result and second copy.
- [x] Added contract tests for source order/depth, unresolved rows, generic identities, sparse JSON fields, compact constructors, and current docs/positions. Both tests pass against the untouched baseline and candidate. Focused candidate selection passes 35/35 after restoring the missing Maven resolver bundles; retain the initial environment failure log.
- [x] Same-formatter production audit: 6,304 → 6,234 lines, a real 70-line reduction from the published candidate. Still 830 lines above the 5,404-line original baseline; overall reduction requirement remains unmet.
- [x] Ten alternating overview-only pairs against the preceding published production: position-edit allocation 10.272 → 5.197 MB (−49.4%); cold allocation 38.849 → 33.304 MB (−14.3%). Cold latency 1528.1 → 1362.5 ms, paired −8.6% [−15.2, +1.4]; edits 107.9 → 99.9 ms, paired −8.0% [−29.0, +1.0]. First-five cached latency regresses 1.281 → 2.048 ms, paired +69.4% [+36.4, +115.6]. Do not hide this startup-sequence regression behind allocation gains. These samples are not pooled with the full application suite.
- [x] Published checkpoint `44e009b` passed Checkpoints, Distributions and Semantic state benchmarks (run 35582414639). Previous completion intermittency is not claimed fixed. CI artifact download returned HTTP 403 locally; no CI raw samples are included in the local bundle.
- [ ] Full 37-scenario paired baseline comparison and broader consumer correctness remain to be completed for this production change.

## 025 — Complete confirmation and isolate the remaining validation failure

- [x] Current production `ad9ce2ab6f08b72b2744884d08331928edc48953` matches all 104 recorded production source hashes in the measured build. Baseline remains `ae23fd1f44573bd3427c0e77f967150173669a2f`.
- [x] Finished the initial ten pairs and the predeclared ten-pair confirmation, retaining all forty successful workers and all 37 scenarios. Combined only identical builds/harness/fixtures on the same recovered host. Added a reproducible report command that rejects incomplete or incompatible campaigns.
- [x] Primary 512-file body edit: 137.455 → 53.439 ms; paired −61.7% [−64.5, −56.6]. Allocation: 18.358 → 7.741 MB; paired −57.7%. 512-file warm: 20.001 → 10.852 ms, paired −46.2%; API edits: 193.681 → 116.808 ms, paired −37.9%.
- [ ] No-regression gate remains unresolved. 32-file warm: 6.898 → 8.160 ms, paired +22.0% [−7.6, +36.4]. 512-file cold: 2035.591 → 2178.496 ms, paired +4.7% [+0.7, +14.7]. Other unresolved intervals and every slower case remain in the full report. No more optional repetition is used to search for passing numbers.
- [x] Three separate retained-heap pairs with thirteen readers completed. Whole-JVM median MiB: initial 25.690 → 30.249; with readers 20.162 → 18.583; after release 17.947 → 18.582. Initial +17.7% requires attribution and keeps memory acceptance open. These forced-GC samples are not part of latency statistics.
- [x] Current commit passed Checkpoints (35583766479) and Distributions (35583766468). Dedicated benchmark workflow (35583766553) failed completion test `changedReleaseAndNewSourceNamesCannotReuseOldCandidates:85`; its forty other selected tests, including the new overview tests, passed. No CI timing campaign ran after that failed precheck.
- [x] Broader local consumer run: 24/29 passed; four failures came from missing AOT image/output directory/network processor dependencies, one from the existing timestamp-preserving completion assertion. Preserve all logs; do not describe this run as green. CI Checkpoints covers the assembled-runtime/processor cases.
- [x] Added and ran an isolated delayed-watcher probe on both baseline and candidate. Changed dependency: one computation plus one cache hit returns stale `int` instead of `String`. New source: two computations and zero cache hits still miss the new declaration. The second case is source-catalogue freshness, so merely refusing empty completion-cache admission cannot repair it. This is a controlled reproduction of the mechanism, not a timing benchmark.
- [x] Published raw combined samples/builds, all worker commands/logs, retained-heap campaign, correctness failures, source verification, same-formatter audit and diagnostic outputs. Original lost raw evidence is not relabelled as recovered; historical rounded results remain separate.
- [ ] Acceptance remains open: input validation/completion, small-query latency, initial heap attribution, and +830 readable production lines versus the original baseline. Next correctness work must first benchmark completion and capture its actual source/namespace inputs; notification epochs alone do not prove freshness.
