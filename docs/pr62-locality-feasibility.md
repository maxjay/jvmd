# F09 bounded feasibility: a locally parsed ordered Merkle map

7 October 2026. This is the bounded follow-up requested after the [first comparison](pr62-locality-comparison.md). Production remains on the current ContentTree. No new candidate prototype, radix adoption, second production tree, format change or acceptance of the current worst case is part of this work. F09 remains open.

**One candidate merits consideration: a canonical ordered map whose levels are formed by deterministic locally consistent parsing into blocks of two to four symbols.** Its attraction is bounded resynchronisation in the number of entries, including on the CAP and prefix-chain adversaries. Its substantial drawback is packing: the simplest construction stores between about N/3 and N nodes, compared with roughly N/31 for the current scheme on well-distributed boundaries. It also requires an explicitly collision-conditional argument, a specified parser, and a new edit/Diff implementation. This report does not assert a finished production design or a measured speedup.

## Computational model and the lower-bound check

The desired contract needs more precision than “deterministic logarithmic updates.” Here N counts map entries, K is the maximum relevant key length in bytes, V the maximum relevant value length, w the symbol-name width in bits, and H the actual height. Count node fetches/emissions separately from the bytes and CPU needed to inspect, decode, hash and encode them. A hash-addressed store fetch is a logical operation, not a constant-time RocksDB/disk theorem.

Several superficially similar results do not establish the desired contract:

- Andersson–Ottmann report an Ω(N^(1/3)) worst-case search/update lower bound for uniquely represented bounded-outdegree dictionaries in their model. The accessible publisher abstract alone is insufficient to transplant that theorem to an arbitrary bit-addressed Merkle implementation. [Original paper](https://epubs.siam.org/doi/10.1137/S0097539792241102).
- The distinction between uniqueness by **size** and uniqueness by **contents** matters. Golovin explicitly discusses it when explaining those earlier bounds. Our requirement is that equal maps produce equal root encodings, not that all maps of the same size have identical shapes or that RocksDB has a canonical physical layout. [Golovin, §1.3, pp. 13–14](https://www.csd.cmu.edu/sites/default/files/phd-thesis/CMU-CS-08-135.pdf).
- The deterministic dynamic-sequence construction of Mehlhorn–Sundar–Uhrig has a polylogarithmic bound containing dictionary and persistent balanced-tree costs; its full update bound is not O(log N). Its signatures are maintained in a shared naming dictionary. That does not itself give independently built stores identical serialized names. [Original paper, Table 1 and §4.2](https://people.mpi-inf.mpg.de/~mehlhorn/ftp/mehlhorn-sundar-uhrig.pdf).
- The logarithmic update result in *Optimal Dynamic Strings* is randomized, with high-probability guarantees. It cannot be cited as an unconditional deterministic solution. [Original paper](https://arxiv.org/abs/1511.02612).

Two elementary constraints apply directly. Ordered comparison search has N+1 possible insertion gaps and therefore needs Ω(log N) comparisons in the worst case with bounded information per comparison. Reading/hashing a supplied K-byte key and V-byte value costs Ω(K+V); comparing long equal prefixes is not one constant-time operation. Neither constraint rules out a logarithmic **structural** bound with separate byte costs.

There is also a decisive naming limit: fixed-width digests cannot injectively name an unbounded collection of arbitrary encodings. The candidate below assumes no digest collisions among encountered entry/node encodings, as ordinary Merkle identity reasoning already does. This is an explicit computational assumption, not a proof of exact deterministic worst-case behaviour over all byte strings. Collision-free bit labels let the parser use operations beyond comparison-only order tests. The lower-bound literature therefore neither supplies a blanket impossibility verdict for this candidate nor licenses an unconditional O(log N) claim. I have not independently reconstructed the complete Andersson–Ottmann proof; its abstract is not used to claim a theorem about JVMD.

For fixed 256-bit labels, bit inspection uses a bounded number of ordinary machine words. In an asymptotic analysis where w grows, retain the cost of manipulating those words and α=O(log* 2^w); do not replace both by constants while allowing an unbounded universe. The finite 256-bit engineering regime and an unbounded collision-free mathematical model are different contracts.

## The single proposed construction

This is a specification-level decomposition, not implemented code.

1. Sort entries by the existing unsigned byte-key order. Give each entry a domain-separated digest of its **complete canonical encoding**: key, value and algebraic h. Do not use h as its exact symbol name; JVMD deliberately permits value changes at equal h.
2. For a sequence of at least two adjacent-distinct symbol names, apply one fixed locally consistent parser. It partitions the sequence into contiguous blocks of two to four symbols, including specified end-of-sequence handling. A singleton terminates the construction; the empty map has a specified empty root.
3. A bottom block stores the complete entries in order. A higher block stores the ordered child hashes, first keys, counts and sums. Canonical node encoding includes its kind/level and the fixed format identifier. The digest of that exact encoding is the next level's symbol name.
4. Repeat until one root remains. Store the entry h unchanged and sum child contributions using the existing Sum operation. No additional semantic identity is introduced.
5. Editing searches by key, reparses a bounded neighbourhood of the edit at each level, and propagates that changed interval upward. A zipper/cursor retains the neighbouring paths; it must not perform a fresh root search for every neighbouring symbol at every level. Equal node hashes outside the repaired region are reused.

The relevant parser lemma states that adjacent-distinct integer symbols admit 2–4 blocks whose boundary decision depends on ΔL=log* W+6 symbols to the left and ΔR=4 to the right, with fixed padding at sequence ends. This is deterministic local dependence, not an assumption that selected hash bits look random. It is the narrow published result used here. [Nishimoto et al., Lemma 5 and §3](https://arxiv.org/html/1605.01488).

**The adaptation is our proposal, not that paper's data structure.** We replace dictionary-assigned signature numbers by content-derived names, retain ordered map separators and sums, and count encoded-store operations. A map's keys are unique, so disjoint blocks contain different exact entries: under the collision assumption, their names are distinct. Thus the map construction does not need the run-compression machinery used for repeated strings. ContentList permits duplicate elements and is outside this argument. This is one reason the report does not propose a global replacement.

The concrete parser family is deterministic alphabet reduction: distinguish adjacent labels by the first differing bit, reduce the alphabet in a fixed number of rounds determined by W, then use the resulting local colouring to place bounded-gap landmarks. Use the fixed-padding, 2–4-block endpoint variant of Lemma 5. No random minima, sampled priorities or CAP fallback are proposed. This selects a construction rather than asking an implementer to find a different tree.

The parser and label universe must be fixed by format. Choosing rounds from the currently observed maximum name, assigning names in insertion order, salting names per boot, or packing by absolute rank would lose the stated locality or cross-build identity argument. The cited local function is a construction result; an implementation must pin its exact bit operations, padding and tiny-sequence cases. We have not silently treated a paper's mutable signature dictionary as a canonical on-disk encoding.

## Applicable bound and its limits

Let α bound the fixed parser neighbourhood width, including a constant number of blocks to close the repair interval. For 256-bit names, the cited window has log* W=5 and width 16 with the zero-padding convention; implementation repair constants are larger than this window and have not been measured.

**Height.** Every nonterminal level reduces the number of symbols by at least a factor of two. Therefore height is at most ceil(log2 N), apart from the chosen leaf/root convention. This bound depends on population, not distinguishing positions in key bytes.

**Changed structure.** For one inserted, removed or replaced entry, let q_j cover the unmatched interval of symbols at level j after aligning unchanged prefixes and suffixes. Boundary decisions outside its α-neighbourhood see identical inputs. Contracting blocks of at least two gives

    q_(j+1) <= ceil(q_j / 2) + O(α).

Starting with q_0=O(1), the interval stays O(α) at every level. This yields O(α log N) changed blocks and emitted nodes. Prefix/suffix identity, not old rank, is the alignment invariant. Root-height changes only affect the top of this same construction. For d separately processed edits the simple safe bound is O(d α log(N+d)); a better batched bound is not asserted here.

**Reads and CPU.** The same node-fetch bound is feasible with a retained frontier/cursor: acquire the search path once and open the constant-width neighbouring subtree fringe as the repair ascends. A root re-seek for each fringe would add a logarithmic factor. This cursor argument is a design obligation, not a measured implementation. A straightforward evaluation of the local function over an O(α) repair window can cost O(α²) small-symbol operations per level; a linear-window parser might improve the factor. Even with fixed α, neither bound includes variable-length encoding or storage-engine overhead.

**Bytes and allocations.** Write/read bytes are the sums of the actual changed/fetched encodings. With inline full separators and bottom entries of at most four items, a conservative edit bound is

    O(α log(N+1) * (K + w/8 + count-width) + α * (K + V)) bytes,

plus reading/hashing the caller's new entry and any old bytes needed for validation. This permits repeated K-byte separator copies along paths; it does not hide them inside “one node.” Count fields require O(log N) bits in an unbounded model. A straightforward decoder/encoder has cumulative allocation proportional to these visited bytes plus O(α log N) objects; peak retention depends on cursor/buffer implementation and must be measured. The current Java allocation constants cannot be assigned to an unimplemented candidate.

**Queries.** Exact lookup follows one path using the original byte-order separators. A range sum opens the two boundary fringes and adds stored sums of fully enclosed subtrees; bounded fan-out and logarithmic height give O(log N) node fetches, plus compared/fetched bytes. Enumeration includes unavoidable output work. Long common prefixes still cost bytes to compare; this is not a claim of logarithmic byte work independent of key length.

**Diff.** Ordered interval descent can skip equal hashes and refine unequal overlapping intervals even when blocks realign. The changed-structure argument supports local work for a known single-edit pair, but it is not a proof that the current Diff can be reused unchanged, nor an output-optimal bound for arbitrary independently built maps. A cursor-based exact Diff and adversarial cross-build verification would be mandatory before adoption. Reporting changed-node counts alone would not satisfy F09.

## Preservation argument

| Property | Why the candidate can preserve it | Remaining obligation |
| --- | --- | --- |
| Canonical root | Sorted exact entries, fixed labels/parser/encoding; induction determines every level | Freeze parser/end rules; reject history-dependent numbering, seeds and adaptive packing |
| Incremental = fresh | Repair all and only potentially changed local decisions; identical exterior windows retain identical blocks | Prove interval closure, singleton/empty transitions and root-height changes in the concrete editor |
| Ordered ranges | Every block is a contiguous interval in the original key order | Implement key comparison and fringe traversal with actual byte costs |
| Algebraic fingerprints | Same h values and associative Sum, independent of partition | Retain current projection boundaries; sums remain semantic observations, not storage addresses |
| Merkle sharing | Unchanged exact block encodings retain their addresses | Canonical field encoding and appropriate domain separation |
| Exact identity | Hash covers full values and structure, not only projected h | Same explicit collision boundary as other content-addressed records |
| Bounded repair | Local dependence plus at-least-two contraction | Cursor/read proof and implementation; no rank-based CAP fallback |

The loss is not in these semantic properties. It is in storage density, implementation complexity, and the conditional nature of finite hash names.

## Comparison on JVMD domains and adversaries

The [existing measurements](measurements/pr62-locality-comparison.csv) remain the evidence for CAP and prefix-chain adversaries. The new [current-domain profiler](experiments/CurrentTreeDomains.java) runs the existing production Stage 2/3 pipeline on this repository with its JDK and dependencies, then measures the existing ContentTree on the largest actual root in each sampled domain. It implements no candidate parser. [Profiles](measurements/pr62-current-domain-profile.csv) and [operation costs](measurements/pr62-current-domain-costs.csv) separate node work, encoded bytes and thread allocation.

The sample contains **18 domains per digest, 36 root profiles and 216 operation rows**, with **2,992 independent checks**. Both production boots read 593 source units; the existing metadata boundary rejected reuse for 334 units per digest. This is a tree-work measurement, not another native-byte oracle or a successful reuse acceptance gate.

Representative SHA-256 roots (largest by entry count, with deterministic hash tie-breaking):

| Domain | N | Height | Key bytes p99 / max | Leaf nodes / CAP-only cuts | Longest no-hash run |
| --- | ---: | ---: | ---: | ---: | ---: |
| T-binary | 65,861 | 4 | 193 / 419 | 2,071 / 39 | 260 |
| N-binary | 65,861 | 4 | 207 / 432 | 2,021 / 27 | 261 |
| T-source | 3,023 | 3 | 253 / 437 | 96 / 1 | 151 |
| DD | 32,508 | 4 | 104 / 162 | 984 / 24 | 271 |
| LOCAL | 37,297 | 4 | 187 / 233 | 1,204 / 21 | 224 |
| BODIES | 335,828 | 5 | 195 / 240 | 10,835 / 179 | 295 |
| CI-queries | 1,994 | 2 | 59 / 66 | 70 / 1 | 132 |
| OUT | 421 | 3 | 60 / 68 | 18 / 1 | 137 |
| leaf-set | 115 | 1 | 32 / 32 | 1 / 0 | 115 |

The largest binary fact/name roots have 65,861 entries; their leaf cut rates are 3.08% and 3.03%, respectively, near 1/32. Yet real no-hash runs reach 260–261 entries, already beyond CAP. The 115-entry leaf set has no hash cut at all. BODIES has 179 CAP-only leaf cuts in this SHA-256 snapshot; its longest no-hash run is 295 (344 under SHA3). This supports neither a universal Bernoulli law nor a worst-case guarantee. It shows why both actual domains and adversaries are needed.

Middle-entry deletion on the same SHA-256 roots:

| Domain | Reads | Read bytes | Emissions | Emitted bytes | Allocated bytes |
| --- | ---: | ---: | ---: | ---: | ---: |
| T-binary | 7 | 21,845 | 7 | 21,695 | 141,272 |
| N-binary | 7 | 32,883 | 7 | 32,785 | 197,448 |
| T-source | 4 | 20,299 | 4 | 20,140 | 121,592 |
| DD | 5 | 13,303 | 3 | 10,110 | 83,664 |
| LOCAL | 7 | 29,603 | 7 | 29,417 | 200,008 |
| BODIES | 8 | 53,805 | 8 | 53,622 | 358,384 |
| CI-queries | 3 | 17,105 | 3 | 16,988 | 128,152 |
| OUT | 6 | 18,020 | 6 | 17,913 | 133,136 |
| leaf-set | 1 | 8,286 | 1 | 8,214 | 57,912 |

The CSV also includes first/last deletion, value replacement, exact lookup and half-range sum. The largest T value is 12,932 bytes; its largest encoded leaf is 33,848 bytes. Actual BODIES keys reach 240 bytes. The candidate would pay those bytes too, although a 2–4 leaf has fewer neighbours. From the observed SHA-256 BODIES population, its minimum block-node count would be ceil((335,828−1)/3)=111,943 versus 11,178 current nodes; this is a counting lower bound on candidate storage, not a measured implementation.

These are immutable in-memory encoded-node reads with cloned buffers. Emissions count attempted writes, not disk/WAL bytes. Allocation is cumulative thread allocation, median of three runs after two warm-ups per operation; it is not peak heap. Boot, sorting, caller edit construction/hashing, map copies and fresh-build/Diff oracle checks are outside operation counters. Each edit is checked against an independent fresh build and exact changed-key Diff; point/range answers have direct entry oracles. Edits are entry-level tree operations, not whole-file Java mutation measurements. Largest-root selection is not a random sample or a growth-axis proof. X-header-slice uses actual selected LOCAL X entries but is explicitly a constructed slice: the current raw reverse index is not represented as a global production ContentTree. No PM/GEN/PD processor corpus or ContentList bound is inferred. [Reproduction and hashes](measurements/pr62-current-domains.txt).

The current scheme's expected-locality argument remains conditional on sufficiently independent leaf and interior boundary decisions. Its B=32, C=128 mean capped run is about 31.45 under a Bernoulli model. Domain cut frequencies and observed repair work can support a workload description, but cannot prove independence, a tail bound, or resistance to deliberately selected keys. Interior decisions use child hashes and must also be included in any accepted probabilistic contract.

| Input | Current hash/CAP scheme | Proposed local parser, analytical only |
| --- | --- | --- |
| Actual fact/name/owner keys | Measured above; structured owner/name/descriptor prefixes | Structural bound independent of those prefixes; pays their actual encoding/comparison cost |
| Path-addressed LOCAL/X and output paths | Real path length and payload effects are counted | Same byte-order geometry; no flattening or hashing away path ranges |
| Digest-keyed leaf sets | Fixed-width keys; expected boundary model is plausible but not proved by this sample | Fixed-width case fits the symbol model, with significantly smaller blocks |
| CAP-only first deletion, N=65,536 | 531 reads, 526 emissions, 2,927,031 read bytes, 2,926,582 emitted bytes, 26,384,200 allocated bytes (SHA-256) | No rank CAP; O(α log N) structural region even when every Hash64 boundary is absent; **no measured byte/allocation result** |
| Prefix chain, N=1,024 and maximum key length 1,024 | Current tree: 3 reads, 3 emissions, 58,620 read bytes, 354,528 allocated bytes; previous radix: 899 reads and 16,642,200 allocated bytes | O(log N) height rather than one branch per discriminating prefix; still O(K α log N) possible separator bytes, not O(log N) bytes |
| One large payload | Existing leaf can contain up to 128 entries and has no byte cap | At most four bottom entries, but any one value remains arbitrarily large; Ω(V) cannot be removed |

### Packing is the principal practical objection

For an uncompressed 2–4 tree with N entry leaves, the number I of stored block nodes satisfies

    (N-1)/3 <= I <= N-1,

since each internal node contributes between one and three to the total excess child count N-1. At N=65,536 this is **at least 21,845 block nodes**, before optional caches or storage-engine metadata. The earlier current-tree ordinary fixture used 2,170 nodes. Thus even the best occupancy of this simple candidate entails roughly ten times the node count there. This is a hard counting comparison, not a candidate benchmark.

Full entries still occur once at the bottom; hashes, sums, separators, counts, node keys, allocations and database records multiply. Small theoretical update windows could therefore coexist with worse boot time, larger persistent storage and more ordinary query reads. Grouping several parser levels into packed physical nodes might reduce that cost, but root-height-dependent packing can destroy sharing. **Packing is not an approved second candidate or an assumed solution in this report.** It would need its own canonicality/locality argument before being part of a concrete implementation proposal.

## Decision returned to the user

The bounded comparison identifies a plausible decomposition and an explicit conditional structural argument. It does not establish an unconditional deterministic logarithmic exact dictionary, and it does not justify deploying the candidate or accepting the current linear worst case.

My recommendation is to keep production unchanged and, only if explicitly authorised, evaluate **one isolated implementation of this specified 2–4 parser construction**, without packing, another tree search, or production integration. Its purpose would be to test the cursor/Diff/canonicality argument and quantify the known storage penalty, not to pursue a deployable tree through successive redesigns. Acceptance of collision-conditional structural locality must be explicit. If that condition or the minimum node overhead is unacceptable, stop this candidate rather than automatically trying another.

A bounded experiment would have a fixed exit: both digests; actual domain snapshots; CAP-only and prefix-chain axes already used; all singleton/empty/root-height transitions; incremental/fresh equality under mixed histories; ordered point/range/sum/Diff oracles; and reads, writes, bytes, cumulative allocations and retained memory. Failure of any semantic property, growth outside the derived structural region, or unacceptable ordinary-domain costs returns a negative result for a decision. It would not authorise a second prototype.

F04 metadata/read-proof completeness and the remaining F05 lifecycle work proceed independently. The current indexed-validation receipt is already materialised and validated; the unresolved locality of its underlying ContentTree remains explicitly part of F09.
