# Restart from ae23fd1f — current execution status

The user requested a fresh start on 2026-09-20. Commit `7c16c60` restores the exact
file tree of `ae23fd1f44573bd3427c0e77f967150173669a2f`. The abandoned implementation
and its evidence remain on `archive/semantic-state-first-attempt` (`9611f45`).
All implementation ticks below refer to that archived attempt, not this restart.
The quality rules and E1–E5 remain requirements for the completed architecture.

- [x] S0. Preserve the old attempt and restore the exact requested baseline.
- [x] S1. Commit a reusable benchmark suite and predefined acceptance criteria before
  production edits. Record toolchain, source/dependency hashes, fixture and raw samples.
- [x] S2. Run the untouched baseline, trace ownership/duplicate work, and record the
  proposed replacement and falsifiable expected benefit before implementing it.
- [ ] S3. Change one coherent responsibility; preserve correctness, descriptive names
  and readable structure. Remove the old path and measure production-code impact.
- [ ] S4. Run the identical suite after the change, including serial alternating
  baseline/candidate JVM pairs. Report all scenario medians, variability, allocation,
  work counts and failures. Reject or revise an unproven/regressing change.
- [ ] S5. Publish the evidence and actual progress. Keep unimplemented semantic Merkle
  composition, consumer migration and total simplification requirements open.

Repeat S2–S5 for each subsequent production change. Benchmark preparation is not an
architecture gain. Each report names the mechanisms and workloads it does not cover.

---

# Semantic state implementation checklist

Baseline: `ae23fd1f44573bd3427c0e77f967150173669a2f` on `perf/compact-grams-merge`.
Implementation branch: `architecture/semantic-state`.
Evidence and decisions: append to `SEMANTIC-STATE-PROGRESS.md`; never rewrite old entries.

## Current status and next work

**Consolidation and performance acceptance are incomplete.** The earlier A–D completion
record established implementation/correctness evidence, not an accepted architectural or
performance result. The gates reopened below supersede that status without erasing the evidence.
The required execution order is R0–R6 below, before expanding the architecture. PR #8 stays a draft.

## Objective

Reduce repeated observation, validation, semantic capture and publication by assigning each
fact and computation an explicit owner. Consumers reuse results for a validated revision.
The completed consolidation must reduce maintained production code and architectural complexity
relative to the original baseline through removal of duplicated responsibilities and work.
Implement composable semantic Merkle identities as part of that consolidation. Particular map
implementations and interning policies remain choices to justify through measurement.
Preserve javac, the Rocks artifact backend, readable structure and protocol correctness.

## Required recovery sequence

- [x] R0. Pin the comparison points and hold further architectural expansion. Original baseline:
  `ae23fd1f44573bd3427c0e77f967150173669a2f`; implemented draft source:
  `56a9506a33af1290578fe46025aa348ae8be7bfd`. Preserve correctness fixes, tests and raw results.
  Do not revert the whole branch or promote it based on the existing unpaired measurements.
- [ ] R1. Trace cold, unchanged, body-edit and API-edit requests through the actual code.
  Produce an ownership/work ledger: fact or computation, current owners/call sites, consumers,
  freshness guarantees, invocation count, and proposed work to remove. Include Documents,
  FileStateRegistry/SourceSnapshots, watcher epochs, Analyzer/Dependencies/DiagnosticStore,
  WorkspaceBindings, and Rocks workspace/semantic state. Distinguish necessary publication
  fences and context-specific validation from redundant work. Mark cost explanations as
  hypotheses until measured; a shared helper is not proof that a computation happens once.
  Record duplicated representations, independent validity decisions and invalidation paths;
  establish a consistently formatted production-code baseline for the simplification audit.
- [ ] R2. Establish controlled paired measurements before changing production behaviour.
  Use the same pinned JDK, dependencies, fixtures, heap and cache state; separate cold JVM,
  cold workspace and warmed queries. Alternate revision order across independent JVM pairs,
  with at least 10 pairs initially and additional samples only if uncertainty affects a decision.
  Retain all results and environment details. Include the existing synthetic workloads and
  representative real source projects; measure precise-root reuse and coarse-root reconciliation
  separately. Collect phase time, operation counts, allocation and retained heap with a profiling
  run separate from unprofiled timing. Report variability and paired differences, not just medians
  from unrelated runs. Declare workload budgets and material-regression criteria before fixes.
- [ ] R3. Isolate the correctness floor from optional representation changes in disposable
  comparison builds. Preserve local/private/inherited API correctness, publication fences,
  dependency replacement and metadata invalidation. Measure the minimum correct implementation,
  then isolate contract duplication, interning, snapshot bookkeeping, persistent construction
  and size accounting one at a time. Validate comparison builds against the same correctness
  cases; faster stale answers are not a performance target. Attribute necessary correctness
  costs separately from avoidable overhead. No experiment silently replaces the working branch.
- [ ] R4. Specify the smallest complete consolidation from R1–R3 evidence before coding it.
  Name the authoritative source observation/revision owner and its consumer protocol. Define
  exactly which source identities and semantic outputs can be reused, and across which contexts.
  Specify how events, buffers, unknown history and publication revalidation enter that owner.
  List each redundant map, capture/hash, traversal, serialization or invalidation decision to
  retire, with its replacement consumer path. Keep distinct source/API/reference identities;
  do not put every query behind one workspace hash. Do not promise scan-free external-edit
  detection on coarse roots without sufficient evidence. Required fences are not optional overhead.
  Specify the semantic Merkle ownership DAG and consumer changes below. Reuse the existing
  filesystem Merkle observations where their guarantees apply; retire superseded fingerprint
  and invalidation paths instead of installing another authoritative system beside them.
- [ ] R5. Implement and measure one complete replacement at a time. Each coherent commit
  names the old path removed, the invariant preserved and the expected avoided work. Preserve
  protocol shape by deriving compatibility views from authoritative data where appropriate.
  Run focused correctness tests and the affected paired workload. Keep a structural change only
  when its measured CPU/memory tradeoff meets the declared acceptance criteria; simplify or
  remove optional machinery that fails. A persistent structure or interner is not mandatory.
- [ ] R6. Re-run the full workload matrix and required CI on the selected implementation.
  Confirm the intended reductions in observed work as well as latency and memory. Check old
  snapshots, buffers, external edits, negative lookup recovery and cold-analysis agreement.
  Reconcile the ownership ledger with the final code; record every remaining duplicate path
  and its reason. Close consolidation/performance acceptance only when all declared gates pass;
  an unresolved material regression keeps the PR a draft.
  Include E4's readable-code/complexity audit and E5's semantic Merkle behaviour. A lower line
  count alone, or a root hash with unchanged scans behind it, cannot satisfy acceptance.

## Required semantic Merkle design

This is a required architectural outcome, implemented through the measured replacement sequence
above. The current typed fingerprints and persistent AVL maps do not yet satisfy it.

- Compose deterministic, versioned identities from semantic children: declarations/types into
  file API roots, then module and workspace API roots. Keep source text, API meaning, reference
  relationships, documentation and positions distinguishable so each consumer uses the identity
  relevant to its result. Compiler context and other recorded inputs must also remain valid.
- Use acyclic ownership for Merkle composition. Represent cyclic references/dependencies by
  stable symbol IDs and separate edge collections. Define canonical encoding, child ordering,
  schema/domain separation, and ordered classpath identity explicitly. Hashes must not depend
  on incidental insertion history or persistent-tree balancing.
- Compute a changed leaf/contract once per valid observation and reuse unchanged child identities.
  Update affected ownership paths rather than serializing or walking the full semantic aggregate
  to rediscover its root. Immutable semantic nodes and bounded structural sharing must support
  that reuse; any interner must justify its construction, lookup and retained-memory costs.
- An API-equal body edit must stop API propagation while still updating its references and
  source locations. Unrelated module roots remain reusable. A changed API invalidates the
  recorded consumers with conservative handling of incomplete/negative read sets. Equal roots
  do not establish the absence of an unobserved external edit or a changed compiler context.
- Integrate navigation/index publication with these identities through an explicit mapping of
  authoritative facts, derived indexes and reader snapshots. Identify the old per-layer hashing
  and invalidation code removed. Do not describe the existing AVL root as content-addressed,
  or replace Rocks with a custom Merkle B-tree merely to claim a unified index.

Acceptance requires executable behaviour and reduced work, not a diagram: equal semantic inputs
produce equal roots across construction histories; body edits preserve API roots but refresh
references/locations; API edits reach the right consumers; unrelated subtrees are reused; unknown
history and context changes invalidate safely. Verify full-output agreement with clean analysis,
old-reader isolation, and counters showing the scans/hashes/publications actually avoided.

## Good rules — required invariants

- A cached result records the input revision actually read. Check revision fences before
  publication; never attach a newer epoch to an older result.
- Keep semantic contracts separate from documentation, display names and source locations.
- Include accessible declarations, constants, bounds, annotations, inheritance and defaults
  in contract identity; exclude unexposed local/private implementation details deliberately.
  Private ownership alone does not make an inherited member unobservable to consumers.
- Preserve ordered classpath, compiler/JDK, module visibility and processor context identity.
- Share detached immutable data only. Keep javac-owned objects confined to compiler tasks.
- Complete dependency capture replaces old edges; partial capture may conservatively merge.
- Keep negative/name lookup invalidation conservative wherever javac capture is incomplete.
- A body edit still updates references, locations and documentation as appropriate.
- Observe buffers and external files through a documented revision boundary. A watcher is
  evidence about observed changes, not a filesystem transaction or proof of no pending events.
- Unknown/overflowed change history falls back to full validation. Bounded history/cache
  eviction may reduce reuse but must never change answers.
- Persistent updates preserve old reader snapshots; update only affected navigation postings.
- Measure hashing, enumeration, attribution and index maintenance separately. Record baseline
  and after results for the same fixture, plus correctness agreement and memory bounds.
- Every new abstraction must identify the existing representation or repeated computation it
  replaces. A correctness obligation may add unavoidable work; record and measure it separately.
- Reuse observations within a valid revision and semantic context. Revalidate at the required
  boundary; never substitute a cached observation for an unobserved filesystem change.
- Record implementation, correctness validation and performance acceptance separately. Passing
  CI or reducing posting counts alone cannot close architectural/performance acceptance.
- Simplify by giving code clear responsibilities and removing repeated logic/state. Preserve
  descriptive names, normal formatting, explicit invariants and understandable control flow.
- Compare maintained production code across all affected modules with the same formatting and
  counting rules; count new helpers too. Report tests/docs/generated code separately. Keep their
  coverage and explanatory value; their line count is not a target for reduction.
- Tick a gate only after its stated validation passes. Commit coherent steps and update the log.

## Bad rules — prohibited shortcuts

- Do not use the entire workspace root as every semantic query's key.
- Do not hash presentation maps and call that a declaration contract.
- Do not classify every API-equal edit as an unchanged call/reference graph.
- Do not use unordered hashes for Java classpath resolution.
- Do not recursively hash cyclic symbol relationships as ownership children.
- Do not retain mutable javac trees/elements in a shared node cache.
- Do not retain obsolete dependency edges forever after complete analysis.
- Do not call a global reaggregation or serialization step an incremental update.
- Do not turn off conservative validation or weaken tests to obtain better timings.
- Do not replace the parser/database, add native watchers, or implement cryptographic
  accumulators without a measured need and a separately reviewed design.
- Do not claim broad JDTLS superiority or extrapolate small synthetic timings to real projects.
- Do not equate common data structures with unified ownership, or pointer reuse with avoided
  object construction. Measure the actual operations eliminated.
- Do not introduce another cache/epoch/identity store to hide duplicated work before identifying
  its owner. Do not retain two authoritative representations indefinitely as a migration shortcut.
- Do not bundle independent optimizations into one before/after result, cherry-pick a faster run,
  or retroactively loosen acceptance criteria to accommodate the observed regression.
- Do not game line counts with abbreviated names, semicolon-packed statements, removed whitespace
  or comments, code golf, or by moving equivalent complexity into generated code or dependencies.
- Do not remove correctness checks, public behaviour, diagnostic clarity or test coverage to make
  the code smaller. Do not build a generic framework whose adapters preserve all the old machinery.
- Do not call a single hash over a fully rebuilt aggregate an incremental semantic Merkle DAG.
  Changing terminology or adding root hashes without retiring repeated work is not consolidation.

## Implementation and acceptance gates

Checked items retain their demonstrated, narrowly stated results. Reopened items require the
additional evidence below. Follow R0–R6 in order; these sections are not a parallel work queue.

### A. Identity and publication foundations

- [x] A0. Pin the baseline; create this checklist and the append-only progress log.
- [ ] A1. Restore the pinned JDK/build environment; record a reproducible baseline.
  Reopened: the toolchain and original samples exist, but controlled paired evidence is pending R2.
- [x] A2. Introduce a typed immutable declaration-contract model captured from elements.
  Use it for shared API fingerprints; version persisted identities affected by the change.
- [x] A3. Prove local rename, local/anonymous/private nested implementation changes and
  position shifts do not change exported contract identity; prove externally relevant
  changes still invalidate consumers. Keep navigation/documentation outputs current.
- [x] A4. Add revision-fenced WorkspaceBindings publication and deterministic interleaving
  tests, including fast-token adoption and changes during full validation.

### B. Source revisions and dependencies

- [ ] B1. Consolidate workspace source identity/inventory snapshots and changed-file deltas
  behind one revision owner, reusing Documents/FileStateRegistry rather than duplicate stamps.
  Reopened: the current service owns successive requested views, while consumers still repeat
  validation. Acceptance requires the ownership ledger and complete consumer migration in R4–R6.
- [x] B2. Preserve buffers, additions/deletions, timestamp-preserving external edits and
  conservative coarse-root behavior; state precise watcher consistency limits.
- [x] B3. Give complete and partial dependency recording different contracts; replace old
  reverse edges after full analysis and reuse the shared graph operation in navigation.
- [x] B4. Validate source/namespace/context changes and negative-lookup fallback against
  clean analysis. Do not claim member-level lookup precision until captured explicitly.

### C. Incremental navigation snapshots

- [x] C1. Add immutable persistent maps/postings with bounded structural sharing and stable
  symbol ownership. Test replacement/removal and old-reader isolation.
- [x] C2. Update WorkspaceBindings by fragment deltas: symbols, declaration precedence,
  name lookup, edge directions, occurrences, diagnostics and warnings.
- [ ] C3. Replace whole-aggregate JSON sizing with per-fragment retained accounting;
  expose maintenance counters and keep the configured memory admission limit.
  Reopened: fragment accounting and counters exist; allocation cost and the retained estimate
  still need measurement against heap, including old readers. Do not retain JSON sizing by default.
- [ ] C4. Run references/hierarchy/rename regressions and before/after benchmarks. Body
  edits must avoid rebuilding unrelated postings; API edits must preserve correctness.
  Reopened: correctness/posting counts pass; cold, warm, body and API workload acceptance requires
  the controlled comparisons and declared budgets in R2/R6, not merely having benchmark files.

### D. Consolidation and delivery

- [ ] D1. Evaluate bounded canonical reuse of typed contracts; collision-safe structural
  equality, context/owner separation and eviction must preserve correctness.
  Reopened: correctness tests pass, but retained-memory savings must justify lookup/construction
  costs. Remove optional interning if the R3 comparison does not support retaining it.
- [x] D2. Document publication/persistence boundaries and schema versions. Reconcile the
  obsolete SQLite-only design with the shipped artifact backend and new semantic model.
- [ ] D3. Run focused tests and relevant compiler/navigation/index gates; record actual
  results, costs, failures, open risks and any remaining work without silently closing it.
  Reopened as final acceptance: previous CI success remains valid for its exact source commit;
  acceptance of the consolidation additionally requires R6 and E1–E5.
- [x] D4. Commit and push the work; open a draft PR against the optimisation branch and
  record its validation state. Do not merge automatically.

### E. Performance and consolidation acceptance

These are prerequisites for accepting this change, not optional work after declaring it complete.
Their execution is covered by R1–R6 above. PR #8 stays a draft.

- [ ] E1. Run paired measurements on representative projects; separate coarse and precise-root
  validation, cold attribution, API changes and posting maintenance. Address or explicitly accept
  the measured cold/warm/API regressions under predeclared criteria; the synthetic body-edit win
  is insufficient. Do not silently accept a tradeoff on the user's behalf merely to close the gate.
- [ ] E2. Measure allocation and retained heap, including active readers and the contract interner;
  validate the fragment admission estimate against those measurements.
- [ ] E3. Demonstrate the completed consolidation against the ownership ledger: consumers use
  the intended authoritative results and the named redundant work is removed. Document remaining
  boundaries, including persisted Rocks invalidation. A durable global DAG is a separate decision;
  choosing another future slice alone does not satisfy this acceptance gate.
- [ ] E4. Demonstrate a net reduction in maintained handwritten production code and architectural
  complexity relative to `ae23fd1f`. Apply the same formatter/counting rules to comparison copies,
  without reformatting unrelated repository files just to change the numbers. Show which duplicated
  implementations, representations and independent validity decisions disappeared and which useful
  responsibilities remain. Count all new helpers; disclose any complexity shifted to dependencies
  or generation. Inspect readability and maintainability alongside the counts. If the target is
  unmet, report it as unmet; do not compress code or weaken behaviour to tick the box.
- [ ] E5. Implement and validate the semantic Merkle design above as part of the replacement.
  Show deterministic composable roots, local updates, actual propagation stopping and reused
  subtrees, with the retired code paths identified. Measure total work and end-to-end costs;
  API correctness, compiler context validity, reference freshness and fallback guarantees all remain.

## Deliberately separate research decisions

Green/incremental parsing, replacement authenticated storage engines, algebraic accumulators,
and content-defined chunking are deferred pending workload evidence. Durable cross-store
workspace snapshots require an explicit reader/reclamation protocol before advertising restart
reuse of a complete live workspace. This checklist does not relabel existing stores as that protocol.
The in-process semantic Merkle ownership DAG and its integration with current consumers are in
scope above; the separate research decisions do not defer that required consolidation.
