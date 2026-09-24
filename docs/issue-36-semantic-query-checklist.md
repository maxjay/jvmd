# Issue #36 — Semantic Query Checklist

Base: `eb45487f08a986d3a5ef2acd3666177e332cdc9b`
Branch: `issue-36-semantic-query`

This checklist is authoritative. A box is checked only when the implementation exists, focused correctness tests pass, required proof/benchmark evidence exists, and the corresponding progress entry has been appended.

## Checkpoint 0 — exact baseline
- [ ] Current `main` SHA recorded and measured as the exact subject.
- [ ] Dedicated Issue #36 proof harness/workflow frozen before production edits.
- [ ] CMP-01 incomplete `project.` failure reproduced.
- [ ] Query scenarios measured: prefix narrowing, parameter, field, static, chained, generic fallback, deep/wide hierarchy, access filtering, unsaved edit.
- [ ] Semantic mutation scenarios measured: body-only, unrelated/relevant API range, exact-symbol, overload, hierarchy.
- [ ] Namespace and negative-resolution scenarios measured.
- [ ] Ordered classpath scenarios measured: content change, irrelevant/relevant dependency, insert/remove/reorder.
- [ ] Machine/workspace composition scenarios measured.
- [ ] Query-side javac counters captured.
- [ ] Allocation/JFR and retained/peak memory evidence captured.
- [ ] Baseline progress entry appended with run/artifact links.
- [ ] PR body contains the frozen baseline evidence.

## Checkpoint 1 — canonical proof representation
- [ ] Canonical immutable proof primitives defined.
- [ ] Deterministic, domain-separated identities.
- [ ] Compact structured representation; no opaque javac-owned state retained.
- [ ] Equality and changed-component tests.

## Checkpoint 2 — ordered classpath Merkle sequence
- [ ] Canonical exact-order sequence root.
- [ ] Single-leaf content diff.
- [ ] Insertion and removal diff.
- [ ] Reorder diff.
- [ ] Exact-order equality tests.

## Checkpoint 3 — compositional machine/workspace identities
- [ ] Machine artifact semantic roots composed without flattening.
- [ ] Workspace dependency root derives only from its ordered dependencies.
- [ ] Unrelated machine artifact change leaves unrelated workspace dependency root equal.
- [ ] Workspace/module identity composes semantic domains explicitly.
- [ ] Structural-root propagation is separated from semantic invalidation.

## Checkpoint 4 — semantic read view
- [ ] One read contract spans live, local/workspace, and machine/JDK facts.
- [ ] Deterministic precedence is LIVE > LOCAL > MACHINE.
- [ ] Exact symbol lookup.
- [ ] Direct-supertype lookup.
- [ ] Semantic identity lookup.
- [ ] Callers do not depend on backing-store origin.

## Checkpoint 5 — machine owner/member ranges
- [ ] Existing Rocks postings evaluated before adding storage.
- [ ] Bounded owner-member query.
- [ ] Prefix/range query and deterministic ordering.
- [ ] Bounded pagination/cursor where required.
- [ ] Hierarchy traversal supported.
- [ ] Dependency completion does not require full resident promotion.

## Checkpoint 6 — completion probe boundary
- [ ] Dedicated incomplete-source probe component.
- [ ] `receiver.`.
- [ ] `receiver.pre`.
- [ ] Whitespace/newline continuation.
- [ ] Expression-statement-safe attributable probe.
- [ ] No fixture/Maven-specific hacks.

## Checkpoint 7 — tiered CompletionContextResolver
- [ ] Tier 0 detached context reuse.
- [ ] Tier 1 local lexical receiver resolution.
- [ ] Tier 1 indexed receiver/type resolution.
- [ ] Straightforward chained resolution.
- [ ] Tier 2 bounded javac fallback.
- [ ] Fallback returns semantic context, not the candidate universe.

## Checkpoint 8 — proof-backed document contexts
- [ ] Lexical/document context proof.
- [ ] Receiver proof.
- [ ] Resolution/search-path proof.
- [ ] Hierarchy proof.
- [ ] Accessibility-context proof.
- [ ] Namespace proof.
- [ ] Ordered classpath proof.

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
