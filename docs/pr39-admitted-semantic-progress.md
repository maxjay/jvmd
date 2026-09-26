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
