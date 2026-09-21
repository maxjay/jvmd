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
- [ ] 5. Migrate navigation and diagnostics/index publication to the shared results.
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
