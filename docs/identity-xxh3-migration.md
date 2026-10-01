# Task: replace SHA-256 runtime identities with XXH3-128

**Repo:** `maxjay/jvmd` at `c8fcb9f` · **Modules touched:** `jvmd-core`, `jvmd-index`, `jvmd-index-rocks`, `jvmd-analyzer`, `jvmd-tests` (and `jvmd-lsp`/`jvmd-dist` only for type fallout)
**Spec alignment:** *JVMD Persistence and Incremental Semantic Architecture*: SHA-256 stays the integrity/CAS hash. Aggregates are not trust anchors (§15–16). Proof keys must be restart-stable (Phase 6).

---

## 0. What this task is, in one paragraph

jvmd computes its *semantic identities* (Merkle nodes, treap priorities, accumulator contributions, fact identities, type identities, proof keys) with SHA-256. It does this through **three separate canonical encoders**, a `MessageDigest` plus `DataOutputStream` plus `DigestOutputStream` per call, `toString()`/UTF-8 conversion of every part, a `BigInteger` modulo the secp256k1 prime for every accumulator contribution, and 64-character hex `String`s held as fields. The memory report attributes 22–25% of first-use reference allocation to digests and 9–11% to accumulators, plus 27–40% of warm-request cost to identity recomputation. This task replaces that machinery with **one encoder, one 128-bit identity value type (`Id128`, two `long`s), XXH3-128 (seed 0) as the function, and accumulators made of two wrapping `long` lanes**. Everything that is about **trust, integrity, external formats, file content, or on-disk names stays SHA-256** and is not touched.

## 1. Is this a net loss of lines?

**No, not if you count the hash function itself.** Honest estimate for production code (house style):

| Change | Lines |
|---|---|
| `Xxh3.java`: XXH3-128, one-shot `byte[]` input, seed 0 only | **+180 to +250** |
| `Id128.java`: record of two longs, compare, hex, bytes | +35 to +45 |
| `IdentityEncoder.java`: the single canonical encoder | +60 to +80 |
| Delete `CanonicalDigestWriter` (30), `Hash256` (41) | −71 |
| Delete `LiveStateTree` private encoder, `Fingerprint`, dead v1 `contribution`/`fixedHex`, `FIELD`, BigInteger priority | −25 to −35 |
| Delete `CompilerInputs.compose` encoder body | −10 |
| `AlgebraicAccumulator` 49 → ~30, `FIELD`×3 and `point()` removed | −25 |
| ~25 `.hex()` / `fromHex` round-trips and `isBlank()` sentinels removed in P4 | −20 to −40 |
| **Net, production** | **about +100 to +150** |
| Net production **excluding `Xxh3.java`** | **about −80 to −120** |
| Tests (vectors, equivalence oracle, guardrails) | +150 to +250 |

So the identity layer gets smaller and simpler, but the primitive costs more lines than it removes. The only way to get an overall net loss is to take a library (OpenHFT `zero-allocation-hashing`). That is **rejected**: it reads memory through `sun.misc.Unsafe`, whose memory-access methods warn on JDK 24+ and are scheduled for removal, and it adds a jlink/JPMS dependency to a daemon that currently has none for this. A self-contained ~200-line function, checked against reference vectors, is the cheaper long-term choice.

The value comes from **runtime and memory, plus consistency**: 3 encoders become 1, 2 identity types (`Hash256` and hex `Fingerprint`) become 1, and BigInteger disappears.

---

## 2. Decisions (do not relitigate during implementation)

1. **Function:** XXH3-128, **seed 0, default secret**, which is exactly `XXH3_128bits(data, len)`. Domain separation is already carried in the encoded bytes, so a seed adds nothing. Seed 0 also lets vectors be checked against any stock tool (`xxhsum -H2`, Python `xxhash.xxh3_128`).
2. **Value type:** `public record Id128(long hi, long lo) implements Comparable<Id128>`, in `dev.jvmd.core`.
   - **Canonical byte form** is big-endian `hi` then `lo`. This matches `XXH128_canonicalFromHash` and the hex printed by `xxhsum` and Python.
   - `hex()` returns 32 lowercase chars. `@JsonValue` uses `hex()` and `@JsonCreator` uses `fromHex`. `fromHex` **rejects** any length other than 32.
   - `compareTo` is `Long.compareUnsigned(hi)`, then `lo`.
   - `toString()` returns `hex()`. The record's own `equals`/`hashCode` are fine. Use `(int)(lo ^ lo>>>32)` if you override `hashCode`.
3. **One encoder (`IdentityEncoder`)** replaces `CanonicalDigestWriter`, `LiveStateTree.digest/write` and `CompilerInputs.compose/write`. It writes into a growable `byte[]` (start at 256 bytes, double on demand) and calls `Xxh3.hash128(buf, 0, len)` once.
   - No `MessageDigest`, no streams, no `ThreadLocal` (virtual threads).
   - Public surface:
     - `static Id128 of(String domain, Object... parts)`, the drop-in for `CanonicalDigestWriter.digest`.
     - A typed builder for hot paths: `IdentityEncoder.begin(domain).str(s).num(long).id(Id128).bytes(b).seq(n)…finish()`.
   - **Both front doors must produce identical bytes for the same logical values.** This is tested.
4. **Wire format v3 of the encoder** (all integers little-endian, using a `MethodHandles.byteArrayViewVarHandle(long[].class / int[].class, LITTLE_ENDIAN)`):

   | Tag | Meaning | Payload |
   |---|---|---|
   | `0x03` | string-like scalar | `int32 charCount`, then UTF-16LE code units, written char by char with **no `getBytes`** |
   | `0x01` | `Object[]` | `int32 count`, then elements |
   | `0x02` | `Collection` in iteration order | `int32 count`, then elements |
   | `0x06` | map (`SortedMap` / `LinkedHashMap` in iteration order) | `int32 count`, then key, value, key, value… |
   | `0x04` | `Id128` | `int64 hi`, `int64 lo` |
   | `0x05` | `byte[]` | `int32 len`, then bytes |

   - The domain is written first as a `0x03` scalar.
   - **Scalars keep today's equivalence classes.** `null` encodes exactly like `""`. `Integer`/`Long`/`Short`/`Byte` are written as their **decimal text** under tag `0x03`, with digits emitted straight into the buffer and no `Long.toString` allocation. So `5`, `5L` and `"5"` stay equal, as they are today. `Boolean` and `Enum` are written as `toString()` text. `Path` is written as `toString()`. Any other object falls back to `toString()`.
   - **Determinism asserts** (Java `assert`, so they run under Surefire's `-ea` and cost nothing in production) fail on:
     - any `Set` that is not a `SortedSet`;
     - any `Map` that is not a `SortedMap` or `LinkedHashMap`;
     - any array other than `Object[]` or `byte[]`;
     - any `Record` reaching the `toString` fallback.

   Each of these is a latent nondeterminism or waste bug in today's encoders. For example, `CompilerInputs.write` would `toString` an `Object[]` to `[Ljava.lang.Object;@hash`, and `LiveStateTree` would `toString` a record.
5. **Accumulator:** `record Value(long a, long b, long count)`.
   - `contribution(domain, key, value)`: `Id128 h = IdentityEncoder.of("aggregate-contribution-v3", domain, key, value)`, then `(h.hi, h.lo, 1)`.
   - `plus`/`minus` are lane-wise wrapping `+`/`-` (Z/2⁶⁴ × Z/2⁶⁴) and `count ± 1`.
   - `identity(domain) = IdentityEncoder.of("aggregate-v3", domain, count, a, b)`. Pass `a`/`b` through the typed builder as `num`, not as boxed `Long`s.
   - **No BigInteger, no prime field.**
6. **Treap priorities** (`LiveStateTree.MerkleMap`, `ClasspathSequence`, `ResidentSemanticState`):
   - `long priority = IdentityEncoder.of("<existing-domain>-v2", key).lo()`.
   - Compare with `Long.compareUnsigned`, and **break ties by key `compareTo`**. Do not rely on ties being impossible.
   - Store the priority as a `long` field, not boxed.
7. **Threat model, written into the class Javadoc:** XXH3 is a non-cryptographic hash. Runtime identities assume workspace content does not attack its own language server. Merkle identities are the structural authority, and accumulators are fast filters, never trust anchors (spec §15–16). Anything that crosses a trust boundary keeps SHA-256.
   - Future hardening, which is **not part of this task**: swap the function inside `IdentityEncoder` for SipHash-2-4-128 with a per-install key stored in the state dir. That is a one-file change because there is one encoder.
8. **Restart stability** (spec Phase 6): identities must be a pure function of their inputs. No seed randomisation, no `hashCode()`, no `System.identityHashCode`, no `Locale`-dependent formatting, no platform byte order.

---

## 3. Call-site classification

Reason codes for **KEEP SHA-256**:

- **INT**: integrity or checksum.
- **EXT**: external or protocol format.
- **FS**: filesystem or CAS object names (renaming orphans state).
- **CONTENT**: file or document content hash compared against disk, a Maven checksum, or a client precondition.
- **ROCKS**: persisted Rocks key derivation, out of scope.

### 3a. SWITCH to `IdentityEncoder` / `Id128`

| Site | Today | Phase |
|---|---|---|
| `jvmd-core/CanonicalDigestWriter` (all 60+ callers: Analyzer ×25, SemanticType ×8, ResidentSemanticState ×6, SemanticUnitMerkle ×5, IndexStore ×3, ClasspathSequence ×3, NamespaceResolutionProofs ×3, WorkspaceSemanticIdentity ×2, AlgebraicAccumulator ×2, SemanticReadViews, SemanticFact, ResolutionFact, QueryProof, ArtifactIndexFormat ×1 each) | SHA-256 → `Hash256` | P2 |
| `Hash256` as a type: 158 uses in 22 files, all semantic identities | 32-byte array + object | P2 (rename to `Id128`, then delete `Hash256`) |
| `LiveStateTree` private `digest`/`write`/`fingerprint`, hex `Fingerprint` record | 2nd encoder, hex strings | P2 (encoder), P4 (`Fingerprint` → `Id128`) |
| `CompilerInputs.compose` (users: Analyzer ×6, LiveEnvironmentState, Application, RocksWorkspaceState, LocalArtifacts) | 3rd encoder, hex string | P2 (returns `Id128.hex()`), P4 (returns `Id128`) |
| `AlgebraicAccumulator` (secp256k1 field, BigInteger) | BigInteger sum | P3 |
| `LiveStateTree.priority`, `ClasspathSequence` priority (line 287), `ResidentSemanticState.point()` (line 446, which uses `Hash256.sha256` on concatenated strings, a 4th ad-hoc encoding) | 256-bit BigInteger | P3 |
| `LiveStateTree.contribution` v1, `fixedHex`, `FIELD` | **dead code** | P2: delete |
| `ResidentSemanticState.EMPTY` / `EMPTY_HASH` (`Hashing.sha256(new byte[0])` hex, the "897 empty-hash copies") | hex constant | P4 → one `Id128` constant |
| `SemanticDeclaration` lines 51/52/54 (api / namespace / documentation identities) | `Hashing.sha256(String concat)` hex | P4 |
| `SemanticFact.apiIdentity/namespaceIdentity/documentationIdentity` and `SymbolDescription.documentationIdentity` (String, `""` = absent) | 3 hex Strings per fact | P4 → nullable `Id128`, `null` = absent |
| `ResidentSemanticState.apiIdentity()` and `hierarchyApi` (`.hex()`, `SemanticReadViews:38` `fromHex`) | hex round-trips | P4 |
| `ApiFingerprint.of` fallback (line 37: JSON-serialise, then SHA-256) and main path `.hex()` | JSON + SHA | P4 (feed the sorted `TreeMap`s to the encoder) |
| `Analyzer:1183` publisher semantic key (JSON-serialise list, then SHA-256) | JSON + SHA | P4 |
| `Analyzer:1372` joined-identities hash | String join + SHA | P4 |

### 3b. KEEP SHA-256 (do **not** touch)

| Site | Reason |
|---|---|
| `Hashing.sha256(Path)` everywhere: IndexService 244/354, IndexStore:122, CodePass:27, RocksIndexStore:177, AnnotationProcessing 84/86, Runtime/InProcess/EvaluationCompiler, CompiledEvaluation | CONTENT/INT |
| `IndexService:228` SHA-1 sidecar check, `IndexService:231` `directoryHash` | EXT/INT |
| `ArtifactIndexFormat:27` `Key.cacheKey`, `:54` `documentationKey`, `:183/:358` body checksum | INT/FS (generation keys) |
| `SstSorter` digest, `RocksArtifactRepository:150/179` verification | INT |
| `FileStateRegistry:31` | CONTENT |
| `DiagnosticSnapshots:22` manifest name, `:34/:89` CAS objects | FS/INT |
| `Application:272/826`, `ModuleAnalyzerRegistry:45`, `AnnotationProcessing:46` directory names | FS |
| `Application:667` client `sha256` field, `TextEdits:32`, `Documents:44`, `CompilerInputs:76`, `LocalArtifacts:57` | EXT/CONTENT |
| All Analyzer/Parser/Focusing/SemanticFacts/IndexedFileManager/Application:491 **source-text** hashes (Analyzer 772, 821, 825, 1132, 1273, 1280, 1311, 1579, 1629, 1642, 1693, 2326, …) | CONTENT: they meet disk hashes and `Document.hash` (see §7 follow-up: *hash once*, not *hash differently*) |
| `Analyzer:2317` semantic-tokens `resultId` (test pins 64 chars) | EXT |
| `SymbolIdentity:110` `local <12 hex>` SCIP local symbol names | EXT (changes public symbol strings) |
| `RocksWorkspaceState:81` / `RocksSemanticInvalidation:229-230` module/path key prefixes, `BindingFacts:37`, `RocksIndexStore:164` | ROCKS |
| `AnnotationProcessing:41/122`, `ResponseBudget:21`, `ArtifactIndexFormat.Key` | FS / out of scope |

### 3c. Persisted and protocol-visible fallout (must be handled in P2)

- **`ArtifactIndexFormat`**: `writeResolution` (line 251) writes 32 raw identity bytes, `readResolution` (268–270) reads `Hash256.BYTES`, and validation (around 334) skips 32. Change all three to 16 (`hi`, `lo` as two `writeLong`). **Bump `FORMAT_VERSION` 1→2 and `INDEXER_VERSION` `jvmd-index-v9`→`jvmd-index-v10`.** The Rocks generation name (`RocksIndexStorageProvider:10`) includes both, so MACHINE rebuilds into a fresh generation and the old one is reclaimed by the migration manager. Do not write a reader for the old layout.
- **`StoredArtifact.resolutionIdentity`** (hex JSON in Rocks; `RocksIndexStore:181` writes it, `:309` parses it): now 32 hex chars and parsed by `Id128.fromHex`. The generation bump guarantees no old rows are read.
- **Self-invalidating persisted strings**: RocksWorkspaceState module fingerprint, and DiagnosticSnapshots `context`/`classpath`/`api`. These are compared by equality, and 32-hex can never equal 64-hex, so the first run after upgrade is a clean miss. No schema bump is needed. Add one test asserting that a v2-era snapshot is a *miss*, not an error.
- **Protocol `resolution_identity`** (RocksIndexStore 411/434, LspFacade 75–134): an opaque string that is round-tripped as a precondition. Width goes from 64 to 32. Before merging, grep `docs/` and `docs/api/*.json` for any documented width or pattern. None was found at `c8fcb9f`. Update docs if one appears.
- **Tests**: no test pins a SHA-256 *value* for a semantic identity. `hasSize(64)` exists only on `resultId` (kept) and `artifact.sha256()` (kept). These 8 test files use `Hash256` and change type only: MachineWorkspaceIdentityTest, SemanticProofDagTest, CompletionContextResolverTest, ClasspathSequenceTest, QueryProofTest, MachineSemanticQueryProofTest, SemanticReadViewTest, NamespaceResolutionProofsTest.

---

## 4. Phases and progress

Tick each box when it is done and green. Each phase is **one commit** and builds and passes `mvn -q verify` on its own. Do not start a phase until the previous one is merged or green.

### P0: Baseline and oracle (no production change)
- [x] Record the baseline: run the memory harness from the memory report (seed + first-use references + warm request on the 462-JAR corpus). Save the allocation-by-category table to `docs/perf/identity-baseline.md`.
  - *Done with the in-repo `IdentityAllocationHarness` on a 211-jar corpus plus resident and live-tree phases; the 462-JAR harness is not in the repo. See the baseline doc for the method.*
- [x] Copy today's `CanonicalDigestWriter`, `LiveStateTree.digest/write` and `CompilerInputs.write` into **test sources** as `LegacyEncoders` (the oracle). They are deleted in P5.
- [x] Write the **equivalence-class oracle test** (`IdentityEquivalenceTest`). Generate ~10k random part-lists from a small alphabet: strings including `""`, `null`, ints/longs equal to some strings' decimal text, nested lists, `byte[]`, sorted maps. For every pair (p, q), assert `legacy(p).equals(legacy(q)) == next(p).equals(next(q))`. Run it **separately for each legacy encoder**, over only the input types that encoder handled deterministically:
  - `CanonicalDigestWriter`: scalars, `Object[]`, `Collection`, `Hash256`/`Id128`, `byte[]`.
  - `LiveStateTree` and `CompilerInputs`: scalars, `Collection`, and `SortedMap` for `CompilerInputs` only.

  Before P2 `next` is just `legacy`, so the test is trivially green. It becomes meaningful in P2.

  There is one intentional divergence, and the test excludes it: an identity object passed to the `LiveStateTree`/`CompilerInputs` encoders used to be hashed as its hex text. It is now tag `0x04`, so it no longer equals its own hex string.

### P1: Primitive (`Xxh3`, `Id128`), nothing else
- [ ] `jvmd-core/.../Xxh3.java`: `static Id128 hash128(byte[] b, int off, int len)`. Port it from the reference `xxhash.h` (`XXH3_128bits`, scalar path) covering all four length classes: 0–16, 17–128, 129–240, and >240 (stripes/accumulate/scramble/merge). Use the default 192-byte secret.
  - Read input with little-endian `VarHandle`s.
  - Use `Math.unsignedMultiplyHigh` for the 64×64→128 multiply.
  - No allocation besides the returned `Id128`. The >240 path uses an 8-`long` accumulator on the stack: declare 8 locals or a `long[8]` (one allocation is acceptable for >240 only).
- [ ] `jvmd-core/.../Id128.java` as specified in §2.2.
- [ ] `Xxh3VectorsTest`: all vectors in §6, plus the requirement that `Id128.fromHex(x.hex()).equals(x)` and that `fromHex` rejects 64-char input.
- [ ] Microbenchmark sanity (JMH not required; a loop in a test tagged `perf` is enough): hashing 64 bytes must not allocate beyond the result. Check with `ThreadMXBean.getThreadAllocatedBytes` over 1M iterations; the budget is under 40 bytes/op.

### P2: One encoder; `Hash256` → `Id128`
- [ ] `IdentityEncoder` per §2.3–2.4, with both front doors. Add a test that both produce identical results on the oracle corpus.
- [ ] Switch the oracle test's `next` to `IdentityEncoder`. It **must be green**. If it is red, you changed an equivalence class: fix the encoder, not the test.
- [ ] Mechanically rename `Hash256` → `Id128` in all 22 production files and 8 test files. `CanonicalDigestWriter.digest(` → `IdentityEncoder.of(` (keep argument lists identical).
- [ ] `LiveStateTree`: delete its private `digest`/`write`. `fingerprint(domain, parts)` becomes `new Fingerprint(IdentityEncoder.of(domain, parts).hex())` (the type change waits for P4). Delete the dead `contribution` v1, `fixedHex` and `FIELD`.
- [ ] `CompilerInputs.compose` delegates to `IdentityEncoder.of(version, components).hex()`. Delete its `write`.
- [ ] Leave domain strings at call sites **unchanged**. The function change already separates eras, and renaming 60 literals is noise. Only the accumulator and priority domains get new names, in P3.
- [ ] Persisted fallout per §3c: 16-byte resolution identity, `FORMAT_VERSION=2`, `INDEXER_VERSION="jvmd-index-v10"`, plus the stale-snapshot miss test.
- [ ] Delete `CanonicalDigestWriter.java` and `Hash256.java`.

### P3: Accumulators and priorities without BigInteger
- [ ] Rewrite `AlgebraicAccumulator` per §2.5. Keep the public method names (`contribution`, `plus`, `minus`, `identity`, `ZERO`) so callers do not change.
- [ ] Treap priorities to `long` with the unsigned compare and key tie-break (§2.6) in LiveStateTree, ClasspathSequence and ResidentSemanticState. `ResidentSemanticState.point()` is deleted, and the priority goes through `IdentityEncoder`.
- [ ] Property tests for each treap: insert/remove sequences in random orders converge to the **same root identity** as a bulk build of the final set (history independence). Include a forced-tie test using a test-only priority function.
- [ ] Accumulator algebra tests: commutativity, `x.plus(c).minus(c) == x`, `identity` changes on count change even when the lanes are equal, and range-sum equals fold.
- [ ] Remove every `import java.math.BigInteger` from production.

### P4: Typed identities instead of hex strings
- [ ] `LiveStateTree.Fingerprint` → `Id128`. Delete the record. Its `.value()` callers (~10) take `Id128`. `RocksWorkspaceState` persists `hex()`.
- [ ] `CompilerInputs.compose` returns `Id128`. Its users keep `Id128` until a persistence or protocol boundary, where they call `hex()`.
- [ ] `SemanticFact` / `SymbolDescription` / `SemanticSnapshot` / `SemanticDelta` / `SemanticUnitState`: `apiIdentity`, `namespaceIdentity` and `documentationIdentity` become `Id128`, nullable, with `null` meaning absent. Replace `isBlank()` checks with `== null`. `SemanticDeclaration` lines 51–54 build them with the typed builder (no string concatenation).
- [ ] `ResidentSemanticState`: `EMPTY`/`EMPTY_HASH` become one `static final Id128`. `apiIdentity()`, `hierarchyApi` and `structural` return `Id128`. Delete `SemanticReadViews:38` `fromHex`.
- [ ] `ApiFingerprint`: feed the sorted declaration maps to `IdentityEncoder` instead of JSON, and return `Id128`. The same applies to `Analyzer:1183` and `:1372`.
- [ ] **Protocol and persistence boundaries are the only places that call `hex()`/`fromHex`.** Grep and confirm.

### P5: Guardrails
- [ ] Add these rules to `ForbiddenIdentifiersTest` (or a sibling `IdentityBoundaryTest`) over `src/main`:
  - `java.math.BigInteger` is forbidden everywhere.
  - `MessageDigest` is allowed only in `Hashing.java`, `ArtifactIndexFormat.java`, `SstSorter.java`, `RocksArtifactRepository.java`, `IndexService.java`, `FileStateRegistry.java`.
  - `Xxh3.` is allowed only in `IdentityEncoder.java` and `Id128.java`.
  - `CanonicalDigestWriter` and `Hash256` must not appear.
  - `HexFormat` is allowed only in `Id128.java`, `Hashing.java` and the files on the `MessageDigest` allowlist.
- [ ] Allocation-budget test (tag `perf`):
  - `SemanticType.identity()` on a 4-deep generic type costs ≤ N bytes.
  - `ResolutionFact.identity()` costs ≤ M bytes.
  - One accumulator `contribution` + `plus` costs ≤ K bytes.
  - Set N, M and K at **2×** the measured post-P4 value so the test catches regressions, not noise.
- [ ] Delete `LegacyEncoders` and the oracle test's legacy half. Keep the vector, algebra and history-independence tests.

### P6: Measure and report
- [ ] Rerun the P0 harness. Write `docs/perf/identity-after.md` with the same table, side by side.
- [ ] Acceptance: in the first-use references run, **digest + accumulator allocation drops by ≥ 80%** of its baseline bytes. Warm-request identity cost falls measurably. Report the real number; do not round in our favour.
- [ ] Report the actual production line delta (`git diff --stat` against the P0 commit, `src/main` only), split into `Xxh3.java` and everything else.

---

## 5. Instructions

### Do
- Treat **equivalence classes as the contract.** Two inputs that produced equal identities before must produce equal identities after, and unequal inputs must stay unequal. The oracle test proves this. Where an old encoder was *nondeterministic* (`Object[]` in `CompilerInputs`, records via `toString`, unsorted `Set`/`Map`), fix the caller to pass a deterministic form and note it in the commit message.
- Keep call-site diffs mechanical in P2: same argument lists, a new class name, a new return type. Save semantic edits for P3/P4.
- Write numbers to the buffer without allocating: emit decimal digits directly for the scalar tag, and use `num(long)` in the typed builder.
- Use the typed builder in the four hot paths: `SemanticType.identity()`, `ResolutionFact.identity()`, `SemanticFact` fact identity, and `AlgebraicAccumulator`. Use the varargs front door everywhere else.
- Put the threat-model paragraph (§2.7) in the Javadoc of `IdentityEncoder` and `AlgebraicAccumulator`, replacing the old "controlling SHA-256 field values" wording in `LiveStateTree`.
- Break treap priority ties by key. Compare priorities unsigned.
- Run the whole suite at every phase boundary. Tick the checklist in this file as you go and leave a one-line note per phase in §8.

### Do NOT
- **Do not** switch anything in §3b to XXH3. That includes source-text content hashes, even though they are hot. They are compared against disk hashes, Maven checksums and client preconditions. Their fix is *hash once* (§7), a separate ticket.
- **Do not** add a dependency (no `zero-allocation-hashing`, no `lz4-java`, no Guava hashing). Do not use JNI. Do not use `sun.misc.Unsafe`.
- **Do not** implement streaming XXH3, XXH3-64, seeded variants or custom secrets. Use one function, seed 0.
- **Do not** truncate identities to 64 bits anywhere, including priorities' *inputs*. Priorities use `lo` of a full 128-bit identity, and identities stay 128-bit.
- **Do not** use `hashCode()`, `System.identityHashCode`, `Objects.hash`, `String.format`, `Locale`, `ByteOrder.nativeOrder()` or random seeds anywhere in identity derivation.
- **Do not** introduce `ThreadLocal` buffers or object pools. Use one short-lived `byte[]` per identity.
- **Do not** keep `Hash256` as an alias or add an adapter between `Hash256` and `Id128`. Delete it.
- **Do not** write a reader for the old 32-byte artifact layout or migrate old generations in place. The version bump rebuilds them.
- **Do not** memoize `SemanticType.identity()` or restructure records in this task (§7). Measure first.
- **Do not** change `RocksSemanticInvalidation` key derivation or `POSTING_SCHEMA`.
- **Do not** combine phases into one commit, and do not reformat unrelated code.
- **Do not** weaken or delete a failing test to get green. A red oracle test means the encoder is wrong.

---

## 6. Reference vectors (XXH3-128, seed 0, canonical big-endian hex)

Input for length *n*: `data[i] = (byte)(i % 251)` for `i` in `[0, n)`. These were generated with Python `xxhash.xxh3_128_hexdigest`. Regenerate them yourself to cross-check with `python3 -c "import xxhash;print(xxhash.xxh3_128_hexdigest(bytes(i%251 for i in range(N))))"`.

| n | XXH3-128 |
|---|---|
| 0 | `99aa06d3014798d86001c324468d497f` |
| 1 | `a6cd5e9392000f6ac44bdff4074eecdb` |
| 2 | `6a4a5274c1b0d3add6645fc3051a9457` |
| 3 | `e3b55f57945a17cf5f4299fc161c9cbb` |
| 4 | `eb70bf5fc779e9e6a6111d53e80a3db5` |
| 7 | `61ce291bc3a4357ddbb207821e6d5efe` |
| 8 | `e1e4432a62217fe4cfd50c61c8bb98c1` |
| 9 | `16c769d83e4aebce907931979dca3746` |
| 15 | `301a9f754e8f569a0017ea4be19bc787` |
| 16 | `72950631827607e2842812cc870dcae2` |
| 17 | `685bc458b37d057fc06e233df7729217` |
| 63 | `bb8d4c458fac1f120302a39b74a9cf52` |
| 64 | `9c6e140a465545e590c1971ddb04ce74` |
| 127 | `d5add870c9c9e00f060c2e3ddf0f2fb9` |
| 128 | `14792fc3af88dc6c05321a0b64d67b41` |
| 129 | `dd5e74ac6b45f54ebc30b63382b09a3b` |
| 200 | `cb0395310643ba0edd97e9af3609d9f5` |
| 240 | `65b5be86da5540e7c92b68e16f83bbb6` |
| 241 | `1da1cb61bcb8a2a102e8cd95421c6d02` |
| 255 | `65652759c081c563074191baf9c49567` |
| 256 | `96c36c85d00e5bc544f5d90dacde463a` |
| 1023 | `4325711b0ed4d742d3d91d80ac495685` |
| 1024 | `d0ac1f7b93bf57b9e5d78bafa45b2aa5` |
| 1025 | `2882ebca04ec915ce95c42288f28186e` |
| 4096 | `e12cd72144990fe57135ffa504f1bc71` |
| 65536 | `f5e7bc5d3d8675bfaaae63800707a868` |
| 1000003 | `ff7880a76b3ad0273bd135bb217f309d` |
| `"abc"` (ASCII) | `06b05ab6733a618578af5f94892f3950` |

The lengths cover every branch boundary: 0, 1–3, 4–8, 9–16, 17–128, 129–240, >240, and block/stripe edges at 1024 and 1025.

## 7. Explicitly out of scope (file as follow-ups, do not do here)

1. **Hash once.** The same editor text is SHA-256'd up to ~10 times per edit across Analyzer/Parser/Focusing/SemanticFacts, and `Document` already carries its hash. Thread `Document.hash` through. This keeps the algorithm and removes repeated work.
2. **`SemanticType.identity()` memoisation.** It recomputes recursively and uncached. Decide after P6 numbers.
3. **Keyed hardening** (SipHash-2-4-128 with a per-install key) if the threat model ever changes. It is a one-file change in `IdentityEncoder`.
4. **Rocks key derivation** (`moduleKey`, posting prefixes) to 16 raw bytes. This needs a `POSTING_SCHEMA` bump and has its own migration story.
5. **`ResponseBudget` request identity, and `resultId`.** Both are JSON-serialisation dominated, not hash dominated.

## 8. Progress log

| Phase | Commit | Date | Notes (one line) |
|---|---|---|---|
| P0 | see git log | 2026-10-01 | In-repo harness (211 jars + resident + live tree); oracle over 3 legacy encoders, 10k samples each. |
| P1 | | | |
| P2 | | | |
| P3 | | | |
| P4 | | | |
| P5 | | | |
| P6 | | | |
