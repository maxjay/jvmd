# Issue #36 — Semantic Query Checklist

Base: `eb45487f08a986d3a5ef2acd3666177e332cdc9b`
Branch: `issue-36-semantic-query`

This checklist is authoritative. A box is checked only when the implementation exists, focused correctness tests pass, required proof/benchmark evidence exists, and the corresponding progress entry has been appended.

## Checkpoint 0 — exact baseline
- [x] Current `main` SHA recorded and measured as the exact subject.
- [x] Dedicated Issue #36 proof harness/workflow frozen before production edits.
- [x] CMP-01 incomplete `project.` failure reproduced.
- [x] Query scenarios measured: prefix narrowing, parameter, field, static, chained, generic fallback, deep/wide hierarchy, access filtering, unsaved edit.
- [x] Semantic mutation scenarios measured: body-only, unrelated/relevant API range, exact-symbol, overload, hierarchy.
- [x] Namespace and negative-resolution scenarios measured.
- [x] Ordered classpath scenarios measured: content change, irrelevant/relevant dependency, insert/remove/reorder.
- [x] Machine/workspace composition scenarios measured.
- [x] Query-side javac counters captured.
- [x] Allocation/JFR and retained/peak memory evidence captured.
- [x] Baseline progress entry appended with run/artifact links.
- [x] PR body contains the frozen baseline evidence.

## Checkpoint 1 — canonical proof representation
- [x] Canonical immutable proof primitives defined.
- [x] Deterministic, domain-separated identities.
- [x] Compact structured representation; no opaque javac-owned state retained.
- [x] Equality and changed-component tests.

## Checkpoint 2 — ordered classpath Merkle sequence
- [x] Canonical exact-order sequence root.
- [x] Single-leaf content diff.
- [x] Insertion and removal diff.
- [x] Reorder diff.
- [x] Exact-order equality tests.

## Checkpoint 3 — compositional machine/workspace identities
- [x] Machine artifact semantic roots composed without flattening.
- [x] Workspace dependency root derives only from its ordered dependencies.
- [x] Unrelated machine artifact change leaves unrelated workspace dependency root equal.
- [x] Workspace/module identity composes semantic domains explicitly.
- [x] Structural-root propagation is separated from semantic invalidation.

## Checkpoint 4 — semantic read view
- [x] One read contract spans live, local/workspace, and machine/JDK facts.
- [x] Deterministic precedence is LIVE > LOCAL > MACHINE.
- [x] Exact symbol lookup.
- [x] Direct-supertype lookup.
- [x] Semantic identity lookup.
- [x] Callers do not depend on backing-store origin.

## Checkpoint 5 — machine owner/member ranges
- [x] Existing Rocks postings evaluated before adding storage.
- [x] Bounded owner-member query.
- [x] Prefix/range query and deterministic ordering.
- [x] Bounded pagination/cursor where required.
- [x] Hierarchy traversal supported.
- [x] Dependency completion does not require full resident promotion.

## Checkpoint 6 — completion probe boundary
- [x] Dedicated incomplete-source probe component.
- [x] `receiver.`.
- [x] `receiver.pre`.
- [x] Whitespace/newline continuation.
- [x] Expression-statement-safe attributable probe.
- [x] No fixture/Maven-specific hacks.

## Checkpoint 7 — tiered CompletionContextResolver
- [x] Tier 0 detached context reuse.
- [x] Tier 1 local lexical receiver resolution.
- [x] Tier 1 indexed receiver/type resolution.
- [x] Straightforward chained resolution.
- [x] Tier 2 bounded javac fallback.
- [x] Fallback returns semantic context, not the candidate universe.

## Checkpoint 8 — proof-backed document contexts
- [x] Lexical/document context proof.
- [x] Receiver proof.
- [x] Resolution/search-path proof.
- [x] Hierarchy proof.
- [x] Accessibility-context proof.
- [x] Namespace proof.
- [x] Ordered classpath proof.

## Checkpoint 9 — exact-symbol / overload / member-range proofs
- [x] Exact symbol fact identity.
- [x] Overload-group identity.
- [x] Member-range identity.
- [x] Unrelated member leaves queried range proof equal.
- [x] Relevant member changes queried range proof.
- [x] Prefix narrowing reuses receiver/hierarchy/classpath proof.

## Checkpoint 10 — namespace / negative proofs
- [x] Current-package namespace proof.
- [x] Explicit-import proof.
- [x] Wildcard-import proof.
- [x] `java.lang` proof where relevant.
- [x] Negative lookup captures exact searched domains.
- [x] Unchanged negative lookup reuses without resolution work.
- [x] Newly resolvable name invalidates only the relevant proof.

## Checkpoint 11 — semantic dependency proof DAG
- [x] Consumers reference precise semantic proofs.
- [x] Changed proofs enqueue direct consumers.
- [x] Equal derived proof stops propagation.
- [x] Coarse file closure retained only where precise proof is unavailable.
- [x] Exact-symbol dependency regression.
- [x] Overload dependency regression.
- [x] Hierarchy dependency/fixed-point regression.

## Checkpoint 12 — classpath proof integration
- [x] C content update changes ordered classpath root with a narrow diff.
- [x] Unreferenced C change does not invalidate unrelated query proof.
- [x] Relevant C change invalidates affected proof only.
- [x] Dependency insertion handled precisely.
- [x] Dependency removal handled precisely.
- [x] Reorder handles precedence/ambiguity correctly.
- [x] Winning search-prefix proof retained when later changes cannot affect the winner.
- [x] Production `Analyzer.configure()` invokes ordered classpath diff/search-proof reconciliation before any broad detached-state reset.
- [x] Lazy `validatedInputs()` classpath replacement uses the same proof-first reconciliation before document/accessibility/resident fencing.
- [x] Changed `CLASSPATH_SEARCH` leaves propagate through the existing `SemanticUpdatePolicy.Live / ProofDag`; equal leaves stop propagation.
- [x] Precise classpath transitions do not widen `environmentChanged` to all files; unknown/uncovered environment transitions retain the conservative fallback.
- [x] Permanent production-boundary A/B/C regression proves diff intervals, reconsidered/equal/changed search proofs, ProofDag consumers, javac queries and resident semantic mutation/unit counts.

## Checkpoint 13 — uncertainty/generation audit
- [x] Lost-history/overflow/corruption paths inspected.
- [x] Unnecessary broad epoch walks identified.
- [x] O(1) generation fences used where safe.
- [x] Conservative correctness preserved.
- [x] No unrelated tree redesign.

## Checkpoint 14 — javac minimization
- [x] Simple dependency receiver: zero query-side javac.
- [x] Simple local receiver: zero query-side javac where maintained facts suffice.
- [x] Prefix narrowing: zero javac.
- [x] Warm unchanged completion: zero javac.
- [x] Complex expression: at most one bounded semantic-context fallback.
- [x] No completion-time `Elements.getAllMembers()`.
- [x] No dependency hierarchy discovery through javac when indexed proof suffices.

## Checkpoint 15 — CMP-01 correctness
- [x] Normal incomplete `project.` returns expected MavenProject candidates.
- [x] `completionItem/resolve` remains valid.
- [x] Repeated completion uses maintained state.
- [x] Semantic oracle remains unchanged.
- [x] LSP scenario is correct.

## Checkpoint 16 — mutation/invalidation proof
- [x] Body-only edit.
- [x] Unrelated API member.
- [x] Relevant API member/range.
- [x] Exact-symbol irrelevant/relevant changes.
- [x] Overload irrelevant/relevant changes.
- [x] Hierarchy fixed-point stop.
- [x] Namespace irrelevant/relevant changes.
- [x] Negative lookup unchanged/newly-valid.
- [x] Unsaved source.
- [x] Module switching.
- [x] Machine/workspace composition.

## Checkpoint 17 — final frozen before/after proof
- [x] Exact final subject SHA recorded.
- [x] Same frozen harness used; only subject SHA changed.
- [x] Correctness table.
- [x] Latency table.
- [x] Allocation table.
- [x] Javac-use table.
- [x] Proof-propagation table.
- [x] Classpath-diff table.
- [x] Evidence artifacts/run links.
- [x] Regressions disclosed.

Checkpoint-17 evidence: run `36250899729`, subject `e0445dc8b5186f997483e277fac2e656392056a5`.
The progress entry explicitly distinguishes frozen measurements, unavailable selectors,
and supplemental permanent proof assertions; unavailable values are not reported as zero.

## Checkpoint 18 — cleanup
- [x] Temporary Issue #36 workflow deleted.
- [x] Temporary JFR/proof harness deleted.
- [x] Proof-only counters deleted.
- [x] Permanent semantic regressions retained; no wall-clock assertions.
- [x] Checklist fully reconciled.
- [x] Final append-only progress entry added.
- [x] PR body contains final architecture/results/limitations.
- [x] Exact-head Tests green.
- [x] Exact-head Benchmarks green.
- [x] Exact-head LSP scenarios green where applicable.

## Definition of done
- [x] Incomplete-source completion is correct without turning javac into the ordinary member database.
- [x] Maintained semantic state is queried directly across LIVE / LOCAL / MACHINE layers.
- [x] Reusable conclusions carry precise semantic proof dependencies.
- [x] Fine-grained invalidation is proof-driven for exact symbols, overloads, member ranges, namespaces, negative resolution, hierarchy and classpath resolution.
- [x] Semantic propagation stops on proof equality.
- [x] Coarse invalidation remains only as a conservative fallback.
- [x] Exact same-harness before/after evidence is complete and all temporary proof plumbing is removed.

Closeout: cleanup head `03f3ca68d7b27145bb0b360cfbf84edcb01600f9` passed all three
normal workflows. The final documentation-only head keeps the same production, tests
and workflow content; its exact-head validation links are maintained in PR #39.
