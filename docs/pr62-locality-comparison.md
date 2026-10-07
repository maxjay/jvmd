# F09 locality comparison and decision boundary

7 October 2026. Production baseline: `5e6985e7b98e71d1b0a328619c27a256ebfea33f`.

**F09 remains open. This comparison changes neither production tree code nor the tree node format.** The user requested this comparison before a separate architectural decision. Neither retaining the current scheme nor deploying the prototype below is an accepted resolution.

Subsequent direction: the user retained the stronger objective and requested one bounded feasibility comparison, with assumptions/lower bounds checked before another prototype. The [follow-up](pr62-locality-feasibility.md) now evaluates one locally parsed Merkle-map construction analytically and measures the current tree on actual JVMD domains. No further candidate has been implemented; the alternatives at the end of this earlier report are historical decision context.

The current scheme has useful expected locality under explicit distribution assumptions, but no deterministic resynchronisation bound. A small ordered radix alternative removes rank-shifted CAP cascades and preserves the requested semantic properties. It substitutes a key-length bound for a population bound and has a serious long-prefix-chain counterexample. I do **not** recommend adopting it globally on this evidence.

## What the current code actually guarantees

Let N be the number of entries, C=128 the chunk cap, B=32 the boundary modulus, H the actual tree height, and S the encoded bytes of a tree. Costs must include key/value sizes; C bounds the number of entries or children, not the bytes of a node.

[Chunker](../jvmd-core/src/main/java/dev/jvmd/core/tree/Chunker.java) cuts leaves on `Hash64(key) & 31 == 0` or the 128th entry. Interior cuts use the **child's Merkle hash**, not its first key or semantic sum. [Edit](../jvmd-core/src/main/java/dev/jvmd/core/tree/Edit.java) reuses a subtree only when the new builder is aligned with its boundary. The right tail needs a separate boundary check. Hash64 is deterministic, public FNV-1a plus a finalizer; it is not a secret/random boundary oracle.

### Conditional expected bound

Under a model in which encountered boundary decisions are independent Bernoulli trials with p=1/B, the distance to the next surviving hash cut has mean B. The mean capped chunk length is

    E[min(Geom(1/B), C)] = B * (1 - (1 - 1/B)^C) ≈ 31.45.

A sufficiently independent, unconditioned interior-hash distribution similarly supports expected logarithmic height and local resynchronisation at each level. With fixed C/B, the usual single-edit expectation is O(H) fetched/emitted nodes and O(B*H) decoded/rebuilt entry or child summaries, plus their actual encoded bytes, hashing, payload copies and allocations. The right-tail checks remain part of that cost. At roughly logarithmic H this yields the familiar expected logarithmic edit cost. Sorting/deduplicating a supplied d-entry edit adds O(d log d); it is not free.

This is a **conditional model**, not a proof about every JVMD key stream or a distribution-free guarantee for the particular Hash64. Parent boundaries depend on newly serialized child contents; their independence is an assumption too. The ordinary fixture below illustrates the model but cannot establish it for T/N/X/LOCAL/OUT/PM populations. Claiming expected locality for production therefore still needs workload evidence and a separately accepted contract, including behaviour on deliberately selected keys.

### Worst case

Delete the first entry of a run with no hash cuts. CAP cuts were at ranks C, 2C, ...; they move to ranks C+1, 2C+1, ... in the old stream. There need not be another aligned leaf boundary before end-of-input. One edit can decode/re-encode the whole suffix: Ω(N) entry work and Ω(N/C) leaf reads/writes. The independent Diff then also descends through those unequal chunks.

A safe unconditional work description uses the **visited/emitted encoded tree**, not d log N: an edit can read the old tree and emit the new tree, with byte cost proportional to their visited/emitted encodings. The scheme has no minimum interior fan-out, so this report does not silently promote expected height to a deterministic log N theorem. For the reproduced cap-only fixture, interior heights remain small and the measured suffix cost is linear.

CAP also does not cap bytes: a single large value or key makes a large leaf. Narrow range sums avoid copying unselected payloads, but still fetch the encoded leaf. Range queries use at most boundary paths plus stored subtree sums, O(H) node fetches with bounded child fan-out, **conditional on actual H**, plus encoding sizes. These query properties survive even when an edit was expensive.

Keeping more old nodes, increasing CAP, changing B, or adding write deduplication cannot repair missing alignment. Removing CAP trades the cascade for an unbounded no-cut leaf. Switching leaf cuts to key hashes is not a fix: the implementation already does that. A deterministic treap ordered by a public hash would still need an expected/adversarial qualification.

## Smallest self-contained alternative examined

[LocalityComparison.java](experiments/LocalityComparison.java) implements an **isolated compressed ordered radix tree with C-entry leaves**. It is outside all Maven source sets. It reuses production Entry, Digest, Sum, leaf encoding and child-summary fields. A new interior encoding and new partition/edit/diff traversal are still a foundational change, not a drop-in tuning parameter.

1. Encode each key as ordered nibbles 1..16, with end-of-key 0. This preserves unsigned byte-string order, including empty and prefix keys.
2. At at most C entries, emit one sorted leaf.
3. Otherwise split at the longest common prefix into nonempty next-nibble groups, at most 17 children. Apply the same rule recursively.
4. Hash the canonical encoded node; store each child's exact hash, sum, count and first key.
5. An edit descends one key path. Splitting an overfull leaf examines C+1 entries. Collapse an underfull subtree to one leaf when it falls to C entries; eliminate unary branches. Reuse other child references.

These rules depend only on the entry map, not insertion history, old ranks, balancing choices, seeds or observed workload. The prototype intentionally has no adaptive history-dependent fallback. Ordered key partitions avoid the CAP suffix cascade. Hashing the keys into a trie would lose the required ordered range geometry, so this experiment does not do that.

For maximum key length L bytes, a path has at most 2L+1 discriminating positions; compressed branches have at least two children. A point edit therefore visits O(L+C) nodes in the worst case, including the at-most-C-entry collapse. It visits O(17L) branch child summaries, plus the bounded leaf entries. This is a deterministic **key-length** bound, not O(log N). Exact byte cost includes full key/prefix/reference encodings and values. In this deliberately simple representation, repeated full prefixes/first keys can make path bytes, scanning and allocations O(L²), plus O(C*(L+V+w)) for maximum leaf payload V and identity width w. Path compression skips a long shared stem but cannot skip a branch at each successive prefix.

The small alternative improves the counterexample for bounded-length keys. It does **not** establish the original unqualified deterministic d log N objective when key lengths/branching chains grow. A more elaborate deterministic locally consistent ordered decomposition might change that tradeoff, but no such design is implemented or proved here. A second index, salted hash, rank-balanced rebuild or history-dependent fallback is not smuggled into this candidate.

## Properties retained and costs changed

| Requirement | Current hash/CAP tree | Ordered radix prototype |
| --- | --- | --- |
| Canonical, history-independent root | Deterministic sorted chunking and encoding | Deterministic CAP base case and longest-prefix partitions; split/collapse rules restore precisely that form |
| Incremental = fresh build | Existing Edit uses the same Chunker | Verified after every measured edit, 2,000 mixed history operations per digest, and a shuffled reconstruction |
| Ordered exact/prefix/range queries | Child intervals and boundary paths | Same byte order; prefix/interval queries use child intervals and stored sums |
| Algebraic sums | Same sum of h at every root/subtree | Same Entry.h and Sum implementation; shape changes neither total nor range sums |
| Exact Merkle identity | Hash covers exact serialized content/structure | Same property; root values necessarily differ between formats |
| Merkle sharing | Resumes when content boundaries realign | Unedited prefix subtrees retain their hashes; local split/collapse may rewrite at most the threshold region |
| Diff | Existing production exact content Diff, including shifted chunks | Independent ordered reference descent skips equal hashes; single-edit work measured and 100 independently built map pairs checked per digest |
| Storage/packing | Expected roughly B entries/children per node | Key distribution controls occupancy; more short-key leaf/interior nodes in these fixtures |
| Key-length sensitivity | Key hashing/encoded bytes; actual height usually small in these examples | Bounded by key path length; a chain of discriminating prefixes is expensive |
| Compatibility | Current format retained | Would require a new format generation and an audit of all tree users; no production integration attempted |

The prototype's Diff is a correctness/work experiment, not a proof of optimal generic multi-edit Diff complexity. ContentList has a different ordered-sequence problem and is **not** replaced by a key radix. The experiment does not establish that introducing a second tree kind is the right JVMD architecture.

## Measurements

[Complete CSV](measurements/pr62-locality-comparison.csv) contains 1,176 rows under SHA-256 and SHA3-256. [Reproduction record](measurements/pr62-locality-comparison.txt) pins sources, command, runtime and result hashes.

Both implementations use an instrumented content-addressed in-memory node store with **cloned encoded reads**, so allocations include the returned node buffer, decoding, hashing, encoding, sum arithmetic and store insertion. Reads and bytes count every fetch; emissions include duplicate write attempts; new nodes/bytes count identities absent from the starting store. No RocksDB cache, JNI, compression, WAL, flush or physical disk-I/O claim is made. Allocation is cumulative thread allocation, median of three runs after warm-up, not peak heap or a timing claim.

Existing store construction/copy, sorted fixture generation, input hashing, and independent fresh-build verification are outside each edit/query measurement. Fresh-build rows measure the actual builders over the same prepared entries; they do not claim to include source parsing or sorting. Verification happens after measurements. No result is accepted merely because the two implementations agree: changed entry maps, enumeration, sums and deltas have independent oracles.

### Exact cap-only review fixture: delete the first entry

Keys are sorted big-endian integers starting at zero, filtered only to exclude Hash64 leaf boundaries. Values are empty and h=Digest(key). This reproduces the review's 33/131 and 35/132 emission counts; the 65,536-entry point extends the axis.

| Digest | N | Tree | Reads | Read bytes | Emissions | Emitted bytes | Allocated bytes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| SHA-256 | 4,096 | current | 38 | 183,258 | 33 | 182,809 | 1,653,680 |
| SHA-256 | 4,096 | radix | 4 | 3,336 | 4 | 3,292 | 32,056 |
| SHA-256 | 16,384 | current | 137 | 732,045 | 131 | 731,515 | 6,600,760 |
| SHA-256 | 16,384 | radix | 4 | 3,564 | 4 | 3,520 | 33,824 |
| SHA-256 | 65,536 | current | 531 | 2,927,031 | 526 | 2,926,582 | 26,384,200 |
| SHA-256 | 65,536 | radix | 5 | 4,564 | 5 | 4,520 | 43,544 |
| SHA3-256 | 4,096 | current | 33 | 182,853 | 35 | 182,971 | 1,664,088 |
| SHA3-256 | 4,096 | radix | 4 | 3,336 | 4 | 3,292 | 32,920 |
| SHA3-256 | 16,384 | current | 134 | 731,802 | 132 | 731,596 | 6,640,432 |
| SHA3-256 | 16,384 | radix | 4 | 3,564 | 4 | 3,520 | 34,688 |
| SHA3-256 | 65,536 | current | 531 | 2,927,031 | 526 | 2,926,582 | 26,548,304 |
| SHA3-256 | 65,536 | radix | 5 | 4,564 | 5 | 4,520 | 44,592 |

At 65,536 entries, the current edit plus subsequent Diff reads about **8.78 MB**, allocates about **53.9 MB**, and emits about **2.93 MB**. The radix edit plus Diff reads **13,648 bytes**, allocates roughly **79–81 KB**, and emits **4,520 bytes**. These are exact logical encoded-store costs for the fixture, not end-to-end filesystem costs.

### Ordinary keys, packing and queries

Sequential four-byte keys starting at one, without boundary filtering, with empty values. Representative SHA-256 results:

| N | Tree | Operation | Reads | Read bytes | Emissions | Emitted bytes | Allocated bytes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 4,096 | current | delete-first | 5 | 7,391 | 5 | 7,347 | 56,816 |
| 4,096 | radix | delete-first | 4 | 3,292 | 4 | 3,248 | 31,480 |
| 4,096 | current | get-middle | 3 | 6,176 | 0 | 0 | 15,056 |
| 4,096 | radix | get-middle | 4 | 3,336 | 0 | 0 | 11,032 |
| 4,096 | current | range-half | 5 | 12,715 | 0 | 0 | 47,256 |
| 4,096 | radix | range-half | 6 | 5,277 | 0 | 0 | 21,120 |
| 16,384 | current | delete-first | 5 | 10,267 | 5 | 10,223 | 77,776 |
| 16,384 | radix | delete-first | 4 | 3,520 | 4 | 3,476 | 33,248 |
| 16,384 | current | get-middle | 3 | 7,512 | 0 | 0 | 16,392 |
| 16,384 | radix | get-middle | 4 | 3,564 | 0 | 0 | 11,832 |
| 16,384 | current | range-half | 5 | 17,631 | 0 | 0 | 64,800 |
| 16,384 | radix | range-half | 7 | 6,735 | 0 | 0 | 27,072 |
| 65,536 | current | delete-first | 7 | 8,349 | 7 | 8,305 | 65,272 |
| 65,536 | radix | delete-first | 5 | 4,520 | 5 | 4,476 | 43,064 |
| 65,536 | current | get-middle | 4 | 13,449 | 0 | 0 | 41,432 |
| 65,536 | radix | get-middle | 5 | 4,564 | 0 | 0 | 15,488 |
| 65,536 | current | range-half | 7 | 14,781 | 0 | 0 | 55,312 |
| 65,536 | radix | range-half | 8 | 7,735 | 0 | 0 | 31,232 |

For 65,536 ordinary entries, fresh radix construction uses **4,371 nodes / 3,244,375 encoded bytes**, versus **2,170 / 3,061,368** for the current tree. The radix has about twice the node count and 6% more encoded bytes; build allocation rises from about 15.3 MB to 18.2 MB. A lower edit count does not imply a cheaper full store or lower physical I/O. SHA3 rows and all replacement/append/delete variants are included in the CSV.

### Alternative's negative control: a discriminating prefix chain

Keys are 01, 00 01, 00 00 01, ... in byte order. N and maximum key length grow together. Each radix branch has only two children; compression cannot erase those real branch points. SHA-256, deleting the deepest/first key:

| N | Tree | Reads | Read bytes | Emissions | Emitted bytes | Allocated bytes |
| --- | --- | --- | --- | --- | --- | --- |
| 256 | current | 3 | 21,042 | 3 | 20,745 | 137,456 |
| 256 | radix | 131 | 136,402 | 129 | 135,181 | 1,005,592 |
| 512 | current | 3 | 12,204 | 3 | 11,651 | 74,936 |
| 512 | radix | 387 | 601,682 | 385 | 598,925 | 4,120,096 |
| 1,024 | current | 3 | 58,620 | 3 | 57,555 | 354,528 |
| 1,024 | radix | 899 | 2,515,282 | 897 | 2,509,453 | 16,642,200 |

At N=1,024, an exact middle lookup takes **513 radix reads / 996,654 bytes**, versus **2 current reads / 28,351 bytes**. A half-range sum takes **768 radix reads / 1,789,056 bytes**, versus **3 / 119,581**. Canonical roots and sums still pass. This is a locality failure for any claim independent of key shape, not a correctness failure.

The CSV also varies a common nonbranching prefix by 64/512/4,096 bytes, payloads by 256/1,024/4,096 bytes, and threshold sizes 127/128/129/255/256/257. The radix 129→128 collapse takes 11 reads in that fixture, explicitly counted; threshold reorganization is not assumed free. At a 4,096-byte common prefix and N=4,096, radix first deletion reads 228,572 bytes despite only four node reads. At a 4,096-byte payload it reads 64,732 bytes. Node counts alone would hide both effects.

## Recommendation and explicit decision still needed

Retain the production representation **while evaluating**, with F09 open. This is not acceptance of its current worst case.

The evidence supports three different possible decisions, none inferred here:

- **Keep an expected-locality contract:** explicitly accept the linear cap-only case, justify boundary distributions on actual tree domains, and specify an adversarial work policy. A work-budget abort can limit one operation but does not make a completed edit local; a background full rebuild also does not repair the bound.
- **Consider a bounded-key radix domain:** first establish enforceable key/payload limits and the affected ordered-map domains. Then weigh the packing/read regressions, format change, multiple-tree complexity and production Diff integration. The present prototype is insufficient for global adoption.
- **Keep a stronger deterministic population bound:** pursue a separately approved decomposition study that controls resynchronisation **and** prefix-chain costs while retaining canonical ordered roots, sums, sharing and fresh-build equality. More research/proof is needed; none of those properties should be traded away to make a benchmark green.

My recommendation is to keep the stronger locality objective pending that decision and reject a blanket radix replacement. The experiment shows why a one-line boundary fix is insufficient and why replacing it prematurely would trade one adversary for another. F04/F05 correctness and validation repairs are independent of this decision. F19/F20 materialisation repairs remain in production and are not changed by the experiment.
