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
