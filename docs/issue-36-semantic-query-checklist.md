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
- [ ] Exact symbol fact identity.
- [ ] Overload-group identity.
- [ ] Member-range identity.
- [ ] Unrelated member leaves queried range proof equal.
- [ ] Relevant member changes queried range proof.
- [ ] Prefix narrowing reuses receiver/hierarchy/classpath proof.

## Checkpoint 10 — namespace / negative proofs
- [ ] Current-package namespace proof.
- [ ] Explicit-import proof.
- [ ] Wildcard-import proof.
- [ ] `java.lang` proof where relevant.
- [ ] Negative lookup captures exact searched domains.
- [ ] Unchanged negative lookup reuses without resolution work.
- [ ] Newly resolvable name invalidates only the relevant proof.

## Checkpoint 11 — semantic dependency proof DAG
- [ ] Consumers reference precise semantic proofs.
- [ ] Changed proofs enqueue direct consumers.
- [ ] Equal derived proof stops propagation.
- [ ] Coarse file closure retained only where precise proof is unavailable.
- [ ] Exact-symbol dependency regression.
- [ ] Overload dependency regression.
- [ ] Hierarchy dependency/fixed-point regression.

## Checkpoint 12 — classpath proof integration
- [ ] C content update changes ordered classpath root with a narrow diff.
- [ ] Unreferenced C change does not invalidate unrelated query proof.
- [ ] Relevant C change invalidates affected proof only.
- [ ] Dependency insertion handled precisely.
- [ ] Dependency removal handled precisely.
- [ ] Reorder handles precedence/ambiguity correctly.
- [ ] Winning search-prefix proof retained when later changes cannot affect the winner.

## Checkpoint 13 — uncertainty/generation audit
- [ ] Lost-history/overflow/corruption paths inspected.
- [ ] Unnecessary broad epoch walks identified.
- [ ] O(1) generation fences used where safe.
- [ ] Conservative correctness preserved.
- [ ] No unrelated tree redesign.

## Checkpoint 14 — javac minimization
- [ ] Simple dependency receiver: zero query-side javac.
- [ ] Simple local receiver: zero query-side javac where maintained facts suffice.
- [ ] Prefix narrowing: zero javac.
- [ ] Warm unchanged completion: zero javac.
- [ ] Complex expression: at most one bounded semantic-context fallback.
- [ ] No completion-time `Elements.getAllMembers()`.
- [ ] No dependency hierarchy discovery through javac when indexed proof suffices.

## Checkpoint 15 — CMP-01 correctness
- [ ] Normal incomplete `project.` returns expected MavenProject candidates.
- [ ] `completionItem/resolve` remains valid.
- [ ] Repeated completion uses maintained state.
- [ ] Semantic oracle remains unchanged.
- [ ] LSP scenario is correct.

## Checkpoint 16 — mutation/invalidation proof
- [ ] Body-only edit.
- [ ] Unrelated API member.
- [ ] Relevant API member/range.
- [ ] Exact-symbol irrelevant/relevant changes.
- [ ] Overload irrelevant/relevant changes.
- [ ] Hierarchy fixed-point stop.
- [ ] Namespace irrelevant/relevant changes.
- [ ] Negative lookup unchanged/newly-valid.
- [ ] Unsaved source.
- [ ] Module switching.
- [ ] Machine/workspace composition.

## Checkpoint 17 — final frozen before/after proof
- [ ] Exact final subject SHA recorded.
- [ ] Same frozen harness used; only subject SHA changed.
- [ ] Correctness table.
- [ ] Latency table.
- [ ] Allocation table.
- [ ] Javac-use table.
- [ ] Proof-propagation table.
- [ ] Classpath-diff table.
- [ ] Evidence artifacts/run links.
- [ ] Regressions disclosed.

## Checkpoint 18 — cleanup
- [ ] Temporary Issue #36 workflow deleted.
- [ ] Temporary JFR/proof harness deleted.
- [ ] Proof-only counters deleted.
- [ ] Permanent semantic regressions retained; no wall-clock assertions.
- [ ] Checklist fully reconciled.
- [ ] Final append-only progress entry added.
- [ ] PR body contains final architecture/results/limitations.
- [ ] Exact-head Tests green.
- [ ] Exact-head Benchmarks green.
- [ ] Exact-head LSP scenarios green where applicable.

## Definition of done
- [ ] Incomplete-source completion is correct without turning javac into the ordinary member database.
- [ ] Maintained semantic state is queried directly across LIVE / LOCAL / MACHINE layers.
- [ ] Reusable conclusions carry precise semantic proof dependencies.
- [ ] Fine-grained invalidation is proof-driven for exact symbols, overloads, member ranges, namespaces, negative resolution, hierarchy and classpath resolution.
- [ ] Semantic propagation stops on proof equality.
- [ ] Coarse invalidation remains only as a conservative fallback.
- [ ] Exact same-harness before/after evidence is complete and all temporary proof plumbing is removed.
