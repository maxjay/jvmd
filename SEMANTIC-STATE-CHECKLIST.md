# Semantic state — active implementation checklist

Baseline: `ae23fd1f44573bd3427c0e77f967150173669a2f`.
Branch: `architecture/semantic-state`; PR #8 stays a draft until acceptance.
This checklist supersedes the overlapping historical A–E/R/S lists. Their evidence
remains in git history and the append-only `SEMANTIC-STATE-PROGRESS.md`.
The first architecture attempt and source-hash trial remain archived and unaccepted.

## Outcome

One detached semantic contract captured once, composed into declaration/type, file,
module and workspace identities, consumed by diagnostics, navigation and persistence.
Incremental navigation replaces affected contributions and shares unchanged state.
Identity composition must permit a future algebraic fingerprint for unordered collections.
The completed replacement must reduce readable production code and duplicated ownership,
with measured end-to-end latency and allocation improvement. No cosmetic line reduction.

## Execution

- [x] 1. Preserve previous attempts and restore production to the exact baseline.
- [x] 2. Finish the benchmark/correctness matrix, record the immutable baseline and
  per-path acceptance criteria before production edits. Keep raw samples and failures.
- [x] 3. Capture authoritative detached declaration contracts. Compose deterministic,
  versioned Merkle identities with explicit ordered/keyed-set semantics. Remove API
  projection from presentation maps and duplicate per-consumer fingerprinting.
- [x] 4. Compose file/module/workspace API roots with structural sharing. Reuse unchanged
  contracts. Keep text, API, references, positions, docs and compiler context distinct.
  Provide an explicit old/new keyed-contribution boundary for future accumulators.
- [x] 5. Migrate navigation and diagnostics/index publication to the shared results.
  Replace whole-workspace graph aggregation/JSON sizing with changed-file contributions.
  Remove the superseded state, hash/serialization and invalidation implementations.
- [ ] 6. Verify correctness: clean-analysis agreement, API boundaries, dependencies,
  buffers, file lifecycle, classpath/context changes, negative lookups, old readers,
  publication races, persistence restart and cache eviction. Run required repository gates.
- [ ] 7. Run the complete identical paired suite and separate retained-memory/profile
  campaigns. Investigate regressions; preserve every result. Report latency first.
- [ ] 8. Audit readable production lines and responsibility ownership against baseline.
  Publish the implementation/evidence, append progress, and close only passed gates.

## Good rules

- Capture compiler semantics once while javac owns the elements; share immutable detached data.
- Canonical identities include domain, schema and algorithm. Keyed collections encode key
  and value; unordered membership differs from ordered sequences and from multisets.
- Ownership composition is acyclic. Reference/dependency cycles use stable symbol IDs.
- An API-equal edit still refreshes local references, diagnostics, positions and documentation.
- Preserve context, ordered classpath, negative-lookup fallback and publication revision fences.
- Complete dependency observations replace prior edges; partial observations remain conservative.
- Changed paths update changed contributions and ancestors; unchanged subtrees are reused.
- Keep persistence and live observations explicit. Equal roots do not detect unobserved disk edits.
- Validate captured source and namespace inputs, including negative lookups; delivered watcher
  epochs alone are not proof of current filesystem contents.
- Every abstraction names the code/state it replaces. Use descriptive names and normal formatting.
- Benchmark before production edits, compare independent alternating JVM pairs, report all paths.
- Keep correctness, mechanism implementation and performance acceptance separately visible.

## Bad rules

- No hashing presentation maps, recursively hashing cyclic references, or unordered classpaths.
- No XOR-only accumulator or implicit cross-algorithm equality. No global interning by default.
- No root hash over a freshly rebuilt global aggregate advertised as incremental composition.
- No additional authoritative cache/store beside the old one as the completed migration.
- No stale answers, weakened checks, hidden slow scenarios, compressed names or line-count games.
- No parser/database replacement or implementing speculative accumulator cryptography in this scope.
- No blanket speed claims: javac and required external-change validation still perform real work.

Details and predeclared acceptance: `docs/semantic-state.md` and
`benchmarks/semantic-state/README.md`. Append progress continuously; commit coherent steps.

## Current result — production ad9ce2a

Implementation steps 1–5 are complete. Acceptance steps 6–8 remain open.

| Gate | Completed evidence | Remaining failure or uncertainty |
| --- | --- | --- |
| 6. Correctness | 35/35 focused local tests; new overview contracts pass on baseline and candidate; current Checkpoints and Distributions pass | Dedicated benchmark CI fails new-source completion. A delayed-notification probe reproduces both stale dependency results and stale source catalogues on baseline and candidate. |
| 7. Performance and memory | 20 paired JVM comparisons, all 37 scenarios; raw samples/logs published; three separate retained-heap pairs | Primary edit latency/allocation pass. Small-query latency and other intervals remain unresolved; isolated cached overview regresses versus prior candidate. Initial heap +17.7% needs attribution. |
| 8. Consolidation and final acceptance | Removed duplicate overview scanner/encoder and unnecessary API graph construction; same-formatter audit retained | 70 fewer lines than prior candidate, still +830 versus original baseline. Net reduction is unmet. PR remains a draft. |

Next work, in order:

1. Establish completion latency/allocation baselines, then repair source/namespace validation
   with deterministic delayed-notification tests. The new-source failure occurs even on a
   cache miss, so changing completion-cache admission alone is insufficient.
2. Attribute initial heap growth and remaining small-query/cold costs before choosing further
   replacements. Keep all failed/uncertain timing cases visible.
3. Consolidate remaining duplicated semantic representation/ownership, count all affected
   readable production code, and benchmark each complete replacement. No formatting tricks.
4. Re-run required acceptance gates on the resulting exact production revision; publish the
   evidence and update this checklist before considering readiness.

Current full report: `docs/performance/semantic-merkle/README.md`.
Every transition and failure remains in `SEMANTIC-STATE-PROGRESS.md`; older rounded evidence
is separated from regenerated raw campaigns. No approval or merge is implied.
