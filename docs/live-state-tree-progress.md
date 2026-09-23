# Live State Tree — Progress

Base: `4ac68eaf77a9c0ecb271a78366f442ec9fa6e2c5`
Branch: `refactor/live-state-tree`

## Architectural invariant

### Existing architecture

```text
REQUEST
  |
  v
reconstruct workspace state
  |
  +-- enumerate
  +-- observe
  +-- hash
  +-- sort
  |
  v
derive identity
```

### Target architecture

```text
MUTATION
  |
  v
live state tree
  |
  +-- Merkle
  +-- algebraic aggregates
  +-- semantic fingerprints
  +-- epoch
  |
  v
REQUEST
  |
  v
read identities
```

### Combined state node

```text
             NODE
    +----------------------+
    | Merkle               |
    | MembershipAggregate  |
    | ContentAggregate     |
    | ApiAggregate         |
    | NamespaceAggregate   |
    | Epoch                |
    +----------+-----------+
               |
         child nodes/leaves
```

### Mutation propagation

```text
A.java edit
    |
    v
new leaf contribution
    |
    +-- content changed
    +-- API maybe changed
    +-- namespace maybe changed
    |
    v
algebraic aggregates update
    |
    v
Merkle ancestors update
    |
    v
epoch increments
```

## Starting-point findings

The existing implementation already has pieces that must be consolidated rather than duplicated:

- `CompilerInputs.capture()` reconstructs source membership/content identities by inventorying source roots and observing source candidates.
- `CompilerPool.execute()` validates at the end of a compiler operation by comparing the starting snapshot with a newly captured snapshot.
- `Analyzer.completionKey()` starts with `Context.toString()`, then sorts and appends every other source path/hash.
- `FileSemanticContribution` and `SemanticUpdatePolicy` already define canonical source/API/dependency semantics and will remain the semantic source of truth.
- `RocksWorkspaceState` already implements persistent directory/module Merkle fingerprints, but its full update path still reconstructs source state through `CompilerInputs`. It must become persistence for the canonical live model, not a second live-state owner.
- `MavenResolver` only memoizes a Resolution within one `RequestScope`; unchanged later RPCs still invoke the resolver boundary.

## Baseline

Status: **in progress**.

Evidence must come from the existing real Apache Maven `CMP-01` completion scenario. Temporary instrumentation may be added only to measure the current computational shape and will be removed in Phase 6.

| Metric | Before | After |
| --- | ---: | ---: |
| warm completion latency | pending | pending |
| warm completion allocation | pending | pending |
| metadata checks/request | pending | pending |
| source entries visited/request | pending | pending |
| hashes/request | pending | pending |
| completion-key entries visited | pending | pending |
| Maven resolver calls on unchanged request | pending | pending |
| project-model inputs validated/request | pending | pending |
| relevant API edit latency | pending | pending |
| body-only edit reuse | pending | pending |

Baseline change: none yet.
Tests: none yet.
PR-local measurement: pending.
Remaining discrepancy: current request path still reconstructs identities.
Commit: pending.

## Phase 1 — canonical state primitives

Status: **not started**.

Baseline evidence: pending baseline above.
Change: pending.
Tests: pending.
PR-local measurement: not applicable; no consumers change in Phase 1.
Remaining discrepancy: all request consumers still use the old state path until Phases 3–5.
Commit: pending.

## Phase 2 — live source state

Status: **not started**.

Baseline evidence: pending.
Change: pending.
Tests: pending.
PR-local measurement: pending mutation-cost evidence.
Remaining discrepancy: pending.
Commit: pending.

## Phase 3 — CompilerInputs cutover

Status: **not started**.

Baseline evidence: pending.
Change: pending.
Tests: pending.
PR-local measurement: pending.
Remaining discrepancy: pending.
Commit: pending.

## Phase 4 — completion identity cutover

Status: **not started**.

Baseline evidence: pending.
Change: pending.
Tests: pending.
PR-local measurement: pending.
Remaining discrepancy: pending.
Commit: pending.

## Phase 5 — project-model identity

Status: **not started**.

Baseline evidence: pending.
Change: pending.
Tests: pending.
PR-local measurement: pending.
Remaining discrepancy: pending.
Commit: pending.

## Phase 6 — delete superseded machinery

Status: **not started**.

Baseline evidence: n/a.
Change: pending.
Tests: full regression suite pending.
PR-local measurement: final rerun pending.
Remaining discrepancy: pending.
Commit: pending.

## Remaining complexity

To be filled from the implemented code, not inferred from percentage changes:

- O(workspace): pending
- O(module): pending
- O(dependency closure): pending
- O(result size): pending
