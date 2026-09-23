# Live State Tree — Checklist

Base: `4ac68eaf77a9c0ecb271a78366f442ec9fa6e2c5`

A phase is complete only when the previous ownership path is either removed or explicitly marked temporary with the next removal step.

## Baseline — real Apache Maven
- [ ] Instrument the existing Apache Maven completion scenario without adding a new permanent scenario.
- [ ] Capture CompilerInputs capture calls, source candidates inspected, metadata checks, files/bytes hashed, inventory calls and directory enumerations.
- [ ] Capture completion-key source entries visited/sorted and key material bytes.
- [ ] Capture Maven resolver calls/cache hits, project-model inputs checked/bytes hashed, Resolution JSON bytes where measurable.
- [ ] Capture request latency, request allocation and peak heap/RSS with the same mechanism used after the change.
- [ ] Record evidence in progress; keep measurement machinery temporary.

## Phase 1 — canonical state primitives
- [ ] Define path-bound leaf identities for membership/content/API/namespace.
- [ ] Define a collision-resistant algebraic aggregate abstraction; document its cancellation/collision semantics. Raw XOR is forbidden.
- [ ] Define cryptographic Merkle structural identity using the same canonical state, not a parallel state owner.
- [ ] Define monotonic transition epoch independent of final fingerprint equality.
- [ ] Reuse existing semantic contribution meaning for API/namespace rather than creating consumer-specific fingerprints.
- [ ] Add executable update/invariant tests, including body-only, API, add/remove and A→B→A.
- [ ] No consumer cutover in this phase.

## Phase 2 — live source state
- [ ] Feed disk/source membership observations into canonical live state.
- [ ] Feed Documents open/change/close overlays into canonical live state.
- [ ] Preserve module/package/subtree identities.
- [ ] Maintain content/membership/API/namespace independently.
- [ ] Add uncertainty state and reconciliation path; reconciliation is not a request-time default.
- [ ] Make existing Rocks workspace persistence derive from/consume the canonical model rather than owning a second Merkle definition.
- [ ] Prove mutations update only the information domains that changed.

## Phase 3 — CompilerInputs cutover
- [ ] Replace request-time source-universe reconstruction with constant-sized canonical state identities.
- [ ] Capture state + epoch at compiler transaction start.
- [ ] Validate at compiler-safe boundary using identities/epoch, not Map<Path,String> reconstruction.
- [ ] Remove source-wide warm request validation.
- [ ] Keep source text correctness for javac reads without rebuilding the world.
- [ ] Run the same Apache Maven measurements and record before/after evidence.

## Phase 4 — completion identity cutover
- [ ] Remove Context.toString() from completion key construction.
- [ ] Remove all-source sort/path/hash concatenation from completion key construction.
- [ ] Use caller identity + environment + membership/namespace + semantic API identity.
- [ ] Completion key construction is O(1) with respect to workspace file count.
- [ ] Unrelated body edit can reuse completion where semantic dependencies are unchanged.
- [ ] Relevant API edit invalidates and exposes the new member.
- [ ] Add/remove source invalidates membership/namespace as appropriate.
- [ ] Record before/after Apache Maven evidence.

## Phase 5 — project-model identity
- [ ] Maintain POM/settings/.mvn/config/project-model identity on change.
- [ ] Retain resident Resolution while accepted project-model identity remains current.
- [ ] Remove Maven resolution/graph reconstruction/JSON roundtrip from unchanged interactive requests.
- [ ] POM/model edit refreshes Resolution and installs the new accepted identity.
- [ ] Record unchanged-request and POM-edit counts.

## Phase 6 — delete superseded machinery
- [ ] Remove duplicate exhaustive validation.
- [ ] Remove old identity builders.
- [ ] Remove redundant caches made unnecessary by canonical state.
- [ ] Remove dead compatibility paths that only served the previous model.
- [ ] Remove temporary counters/profiling/workflow changes/scripts used only for PR proof.
- [ ] Keep only lasting correctness/invariant tests and operationally useful observability.

## Final acceptance
- [ ] Warm unchanged request state lookup is O(1) with respect to workspace size.
- [ ] Global algebraic mutation is O(1).
- [ ] Merkle update is O(log N) or bounded structural depth.
- [ ] Semantic invalidation is proportional to the genuinely affected semantic graph.
- [ ] Warm-path allocation no longer constructs source snapshots, sorted source copies, Context.toString()-sized material, concatenated source/hash keys or unchanged Resolution graphs.
- [ ] Required scenarios A–H are covered by permanent correctness tests where appropriate and PR-local performance evidence where appropriate.
- [ ] Final report includes absolute before/after metrics and names every remaining O(workspace), O(module), O(dependency closure) and O(result size) operation.
