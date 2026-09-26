# Fully admitted semantic reads — child of PR #39

This investigation is stacked on `issue-36-semantic-query`, not `main`.

## Checkpoint 0 — exact starting state

- Parent PR #39: `cd7d904656414905e23f4858159c53ff8f42f551`.
- Current main: `eb45487f08a986d3a5ef2acd3666177e332cdc9b`.
- Child branch: `pr39-admitted-semantic-hotpath`, created from that exact parent.
- Parent Tests 36252122469, Benchmarks 36252122474, LSP scenarios 36252122483 all passed again on rerun attempt 2 before production changes.
- Parent normal CMP evidence: JVMD steady 695.70/699.57 ms; JDTLS 67.34/128.91 ms. These are historical observations, not the controlled A/B.

## Checkpoints 1–4 — pending measurement

The disposable profiling workflow builds one instrumented binary and runs two fresh servers with identical preparation and oracle. Only waiting for exact-version diagnostics differs. Both retain ordinary mutation causality. Each uses one first completion, two discarded warmups and twenty steady samples. The normal/frozen LSP harness is unchanged.

No production optimization has been made. Nested RequestScope stages record wall time, platform-thread CPU and allocation; JFR retains blocking and sampling evidence. Temporary instrumentation will be removed after the final proof.

## Fresh exact-parent baseline (attempt 2)

LSP artifact `10912791561`, SHA-256 `261957c0632f11dfac10683aef7edb4a8ac7cb0f386578ab6656561f7d628558`:

| Server | Steady p50 / p95 ms | Correctness |
|---|---:|---|
| JVMD | 372.371 / 389.059 | First, resolve, edit, 2/2 warmups, 20/20 steady |
| JDTLS | 31.769 / 52.241 | First, resolve, edit, 2/2 warmups, 20/20 steady |

This confirms the fully admitted slow path remains, but does not yet isolate the admission multiplier or its cause.

Prepared artifact `10912865952`, SHA-256 `9bf0692678f6278fb9f07be0d66147518c962aff5e33866c221319aad1ce4b6c`:

| Operation | Server | p50 / p95 ms | Correct |
|---|---|---:|---:|
| completion | jdtls | 6.643 / 11.521 | 300/300 |
| completion | jvmd | 4.192 / 5.871 | 300/300 |
| definition | jdtls | 3.231 / 6.597 | 300/300 |
| definition | jvmd | 3.596 / 4.919 | 300/300 |
| dependency_definition | jdtls | 3.370 / 7.216 | 300/300 |
| dependency_definition | jvmd | 5.537 / 7.895 | 300/300 |
| hover | jdtls | 4.214 / 8.254 | 300/300 |
| hover | jvmd | 4.934 / 7.207 | 300/300 |
| references | jdtls | 14.002 / 25.504 | 300/300 |
| references | jvmd | 5.016 / 7.214 | 300/300 |

## Local preparation and validation

- Exact parent builds with the pinned Temurin 25.0.4.1+1.
- Full Apache Maven fixture at `5cd1b60264101080c712accd605180a4bd9222e0` builds and installs; fixture tracked files remain unchanged.
- Instrumented throwaway checkout compiles and assembles successfully.
- Existing `MaintainedCompletionContextTest` and `CompletionPrefixCacheTest`: 20/20 tests pass with tracing enabled.
- Normal LSP harness tests: 9/9 pass. The normal/frozen harness and oracle have no edits.
- Profiling is applied by a disposable script in CI. No production implementation has been optimized or changed in the child commit.

## Current blockers — not completed evidence

The normal local LSP launch fails with `EPERM` on Unix socket connection; an independent local socket listener also returns `EPERM`. No A/B timing is claimed from this failed launch. Replacing the transport would not satisfy the task's exact real-LSP experiment, so no surrogate is substituted.

The child push was rejected by automatic approval review. After verifying the public `maxjay/jvmd` destination, repository ownership, account identity, and push permission, review still required explicit end-user-authored authorization to publish code and a workflow; the attached task was not accepted as that authorization. No connector or other route was used to bypass the block.

The profiling workflow and local commit are ready for that authorization. Checkpoints 1–10 remain open. In particular, root cause, production repair, permanent work-count regressions, final frozen proof, cleanup, and final child-head CI are not claimed complete.

## Controlled before experiment — checkpoints 1–4

The publication block above was resolved by the user's explicit instruction to publish through the GitHub connector. Child draft PR #46 targets `issue-36-semantic-query`.

Run `36262285004`, subject `62fd8ae2384eb20e5de994d2c4b8598e2d8e7515`, artifact `10912372782`, SHA-256 `080f7c1f26391d60560e2e9bd541e51085efb408d06fa9351549cbc9697b130e` measured one instrumented production binary with two fresh servers. The sole harness difference was exact-version diagnostic waiting. Both modes passed first completion, resolve, edited completion, two warmups and all twenty steady samples.

| Same binary / same fixture | Steady client p50 / p95 ms | Server `lsp.request` p50 ms | Request CPU p50 ms | Request allocation p50 bytes |
|---|---:|---:|---:|---:|
| MUTATION_VISIBLE | 23.706 / 27.885 | 18.768 | 16.608 | 3,153,988 |
| EXACT_ADMITTED | 696.546 / 701.623 | 691.995 | 689.854 | 3,017,480 |

Inclusive nested stages are not additive. In EXACT_ADMITTED, `currentDocumentContextProof` accounted for 684.279 ms wall / 684.184 ms CPU. Classpath search consumed 445.564 ms and accessibility consumed 235.889 ms (two persisted exact-symbol lookups: 235.772 ms). Together these explain about 98% of request time. QueryProof's digest itself cost 0.038 ms. Namespace reconciliation cost 2.124 ms. Warm requests performed zero javac/bindings, but called source-current helpers twice and reconciled 109 package directories per request. Queue time was 0.074 ms. This is CPU work in persisted lookup, not scheduler/lock waiting or expensive hash comparison.

The interactive semantic state was identical: three resident units, 258 facts, one document context, one accessibility entry, eight proof dependencies, zero stale units/uncertainty, semantic epoch 3, input epoch 1201 and environment epoch 1887. The domains were document scope, receiver, resolution path, hierarchy, accessibility, classpath search and two namespace entries. The analyzer had no precise classpath-search cache because the module compiler classpath did not exactly match the persisted workspace sequence.

The persisted state differed: before the warm loop MUTATION_VISIBLE had no `facts.publish` events; EXACT_ADMITTED had two owner replacements writing 2,704 records. The admitted warm request issued 128 source-overlay posting seeks, visiting only two postings, for 672.006 ms. JFR samples in the twenty warm request intervals include eight native `RocksIterator.seek0Jni` samples and no session-thread park/monitor events. Source-overlay absence was repeatedly sought in the shared Rocks state even for artifacts that had never received source records. The combination of admission-populated persistence and repeated proof reconstruction causes the state-dependent multiplier. A speculative member-range scan was not the measured cause and is not being changed.

## Production repair — checkpoint 5

Document proof validation now compares individual identities from their existing owners. It does not create a new QueryProof, reconcile package directories, walk/recover hierarchy, or repair source semantics. Source mutation settlement remains at the input boundary, including completion of already-dequeued watcher publication. Initial semantic admission still performs the necessary discovery and compiler work.

The Rocks owner maintains bounded subscriptions for exact symbols/types (including proven absence), classpath winner/negative searches and classpath structure. Durable artifact/source publication and workspace ordering changes refresh subscribed results under the same metadata monitor. A failed refresh removes the old observation before reconstruction. Read-only observation access distinguishes an evicted/unobserved key (UNKNOWN) from a maintained negative result; UNKNOWN rejects the document certificate and enters ordinary admission. No cached proof-valid boolean is introduced.

SourceOverlay maintains artifact membership from one startup key-prefix pass and from publication. A negative membership answer avoids an unnecessary native seek. Removal may conservatively retain membership; it cannot produce a false negative, including after reopening or legacy migration.

### Mutation coverage audit

| Domain | Maintained owner / transition | Read validation / conservative behavior |
|---|---|---|
| Document text / focused lexical scope | Documents content plus existing focused scope identity | Changed text rechecks scope and import plan; no semantic repair |
| Source API, exact symbols, overload/member ranges | ResidentSemanticState admission and existing source ProofDag leaves | Existing changed/fallback consumers invalidate; exact identities remain checked |
| Source body only | Canonical detached API/resolution identities | Equality preserves semantic consumers; source freshness remains fenced |
| Source membership, namespace, negative search | LiveSourceState mutation journal, settled watcher and sourceMembershipChanged leaves | Each searched type identity is checked, including proven absence; no per-read directory reconciliation |
| Hierarchy | Resident maintained hierarchy aggregates and uncertainty generation | Direct identity comparison, no ancestor repair/walk during validation |
| Persisted exact/type facts | Rocks installed/source publication/removal under metadata monitor | Refresh subscriptions before publication returns; missing subscription is UNKNOWN |
| Classpath replacement/order/winner | Existing ClasspathSequence/SearchProofs and Rocks workspace/publication transitions | Maintained winner/negative identity; existing precise DAG reconciliation still applies |
| Module/model/options/processor configuration | Existing analyzer owner/context identity and CompilerInputs environment | Existing context reset and conservative invalidation retained |
| Generated output / JDK / preserved-timestamp archive edits | Existing verified environment and FileStateRegistry identities | Existing exact freshness checks and unproven-environment fallback retained |
| Lost source history / uncertain environment | Existing source/environment uncertainty and resident generation fences | No equality invented; stale/unknown certificate enters admission |
| Observation eviction/storage failure | Bounded observation owner | Old result unavailable; read cannot lazily reconstruct it as part of proof validation |

This is direct maintained-identity validation, not cache-presence-only validity. Existing ProofDag propagation remains the primary precise mutation path; dynamic leaf comparisons retain coverage for context-specific receiver/accessibility dependencies. Each observable persisted answer is either updated atomically with the owning mutation or unavailable. LIVE values retain precedence over LOCAL and MACHINE. Therefore an equal proof can be accepted without repeating the searches that established its identities.

For an unchanged admitted context, validation is O(number of proof dependencies), plus hashing supplied document bytes, and bounded resident result access. Initial subscriptions and mutation refreshes perform the persisted searches. Existing compiler-input verification remains outside the semantic proof check. A cold/evicted observation explicitly returns to admission rather than being silently reconstructed by validation.

## Local repair validation — checkpoint 6 in progress

The production build passes. Existing targeted completion/source/classpath tests pass (41 before read-only observation enforcement; 39 in the subsequent overlapping suite, including two new regressions). `MaintainedSemanticObservationTest` exercises exact diagnostic admission, twenty repeated completions, repeated configure, zero new QueryProof builds/bindings/source repair/hierarchy recovery/package reconciliation, zero persisted observation/structure builds, uncertainty recovery, classpath reorder and negative source-overlay publication/removal. Counts are asserted, never elapsed-time thresholds.

Broader local suites, after-repair real-LSP A/B, normal benchmark/LSP comparison, frozen proof, cleanup and final exact-head gates remain pending. No repaired latency claim is made yet.

The expanded local phase-3/4 run completed 225 tests: 223 passed; the existing process-tree timeout test exceeded its five-second bound by 106 ms, and the JDK-documentation test hit a corrupt local `lib/src.zip` (`ZipException: zip END header not found`). The targeted semantic suite and the disposable instrumented production build passed. These two local failures are recorded rather than called green; exact-head CI uses the independently checksum-pinned complete JDK.
