# PR62 consolidated review reconciliation

The supplied `JVMD-PR62-consolidated-review.md` reviews `aefad3bda1d647100b56f11b2d6035d89deaf5d0` against `33a9032d0a22f87f2ee306f5e2d935190db6b691`. The repair checkout started clean at that exact head on 7 October 2026. This register records independently checked findings and repair evidence; it is not an approval verdict. PR62 remains a draft.

## Findings

| Finding | Current reconciliation |
| --- | --- |
| F01 | Reproduced under both digests using native class files. Fixed: equal definer sums discharge T/presence only; consumed N answers are checked after provider changes. Native success/failure, positive/zero N and T-only reuse controls pass. |
| F02 | Independently reproduced after fixing F01. Fixed: body discovery accepts separate presence and exact definer deltas, queries type-wide T/N prefixes for a changed winner and deduplicates overlapping seeks. Body N carries actual sums, unlike header N zero predicates. More publication/churn coverage follows F06. |
| F03 | Reproduced before repair. Fixed: immutable BV values for LOCAL/BROOT entries, streamed CF content, and atomic compare-and-publish of BROOT/current bindings/current reverse keys/history sequence. Retained roots resolve values by entry hash. Fault cuts, concurrent publishers, reopen and historical inherited LOCAL values are tested. |
| F04 | Accepted open proof obligation. Existing owner-load coverage is insufficient for either direction of the required query-level theorem. Native diagnostics, query capture and strict two-sided coverage work remain open. |
| F05 | Accepted work-bound correction. The called Valid fallback scans its grouped proof; its actual bound is now explicit in §7.2. No changed-only API or production LIVE scheduler is claimed. Frontier-based validation remains future work, not a hidden assumption of this cold driver. |
| F06 | Fixed: versioned current-only body reverse index, committed atomically with BROOT. Queries do no root membership probes. BROOT and the adjacent LROOT history counters are read/incremented once in their atomic publication. Churn/reopen tests check one current hit and zero membership reads after 6,000 retired consumers. |
| F07 | Partly fixed: streamed body writes use run-scoped node dedup without storage probes/re-hashing; exact GEN hits are checked before building, and fresh generated manifests no longer probe storage for every node. Explicit parent manifests use apply. Processor jar/config rereads, class-loader setup and whole-own eviction remain open. |
| F08 | Fixed: pending and selected maps retain Entry references, not class payloads. Immutable bytes are flushed before sharing references. Rocks tests with 16/64 one-MiB outputs show released source arrays and zero CF payload reads during selection/publication. F11 separately tracks full-selection metadata work. |
| F09 | Reproduced independently. Contract correction: no deterministic local rewrite theorem for cap-only runs. Regression retains canonical root/conservation checks and measures the linear adversary. No change to tree encoding or canonical chunking. |
| F10 | Fixed: one binding per ProcessorPlan; type queries use own/DD/DS/DC point reads and choose the first matching origin in that exact ordered binding. Package metadata/presence uses persisted PB; the cold builder visits each exact PM package catalog once. No global k-to-PM equivalence is assumed. Equal-k/different-source-metadata order tests and irrelevant-route-length counters pass. |
| F11 | Fixed API and cold integration: BM names persistent unit-manifest and record-reference-count trees. commitUnitsDelta reads changed/retired manifests and touched counts only; unchanged selections and shared CF/RS survive without inventory scans or payload reads. The cold driver initializes per-unit state. Empty-delta, last-owner removal, conflicts and independent full-root comparisons pass. |
| F12 | Fixed: rooted F-prefix traversal prunes unrelated subtrees and passes the yielded entry as membership evidence. The production row planner is tested with one F row and 0/1,000/10,000 unrelated records, including corruption rejection. |
| F13 | Fixed: PM contains path plus a PE content identity. Each PE node stores one declaration and ordered child IDs, emitted once per header Element. Decoding is lazy and memoized; direct nested lookup and parent traversal share the same detached declaration. SOURCE annotations, declaration order, parameters, records, docs and literal-dollar names retain existing native parity coverage. |
| F14 | Member lookup fixed using native name-indexed scopes and a task-local owner/name cache, preserving javac's visibility filter. The 52 body collector regressions pass. Aggregate pool-manager resource bounds remain open. |
| F15 | Output.apply now accepts changed/retired unit results, verifies prior entries and rejects collisions without reading unchanged CF bytes. The reuse harness uses changed-unit publication and Output.apply; it still scans proofs and is not a production LIVE driver. Its large measurement is pending after F07 setup fixes. Cold full OUT construction remains legitimate. |
| F16 | Fixed selective decoding: exact get copies only its selected value; range sums skip all value payloads while checking their bounds. This removes allocation, not underlying stored-node fetch bytes. Out-of-line metadata is tracked separately under F13. |
| F17 | Fixed: tail boundary inspection reuses the decoded leaf/children in fallback. Replacement, append, delete-last and absent deletion read each visited node once and match canonical full builds. |
| F18 | Replaced unmatched-run quadratic LCS matrices with bidirectional Myers frontiers and recursive splitting. Workspace is linear; alignment time remains O((n+m)D), potentially quadratic for large edit distance. Prefix/suffix trimming is an optimization, not the sole algorithm. Independent duplicate-rich small LCS checks and chunk-misaligned work-axis regressions pass. |

## First repair batch evidence

F01 baseline: two digest cases failed because oldProofValid was true with equal DD/DS/DC/owner T projections and unequal N. After F01 alone, the same two cases failed at body discovery because candidates were empty (F02). After both fixes, `BodyProofTest`, `BodyReverseTest` and `HeaderReverseTest`: **36 tests passed**.

Core/planning integration: `Review62TreeTest`, `ContentTreeTest`, `ContentTreeEditTest`, `Stage3Test`: **46 tests passed**. After ordered-diff repair, core plus `DefinerStateTest`: **52 tests passed**, including 1,200 randomized small-input comparisons against an independent LCS oracle across both digests. `BodyPlanningTest`: **2 tests passed**, exercising the actual production planner reflectively.

| Work axis | Repair measurement |
| --- | --- |
| F16 unrelated payload 0 / 64 KiB / 1 MiB | Exact get about 288–344 bytes allocated per query; range sum 512 bytes. Allocation no longer grows with payload. |
| F17 32 / 128 / 1,024 / 4,096 entries, four edit forms | Every visited node identity fetched once. Canonical roots match independent full builds. |
| F18 4,096-element cap-shift front deletion | 4,096 element comparisons; about 1.5 MiB allocated, versus the review's roughly 65 MiB matrix allocation. |
| F18 front/middle/end insert/delete and seven separated substitutions | Correct edit counts, at most 4,097 measured comparisons in these fixtures, no quadratic table. |
| F09 cap-only first deletion, 4,096 / 16,384 entries | SHA-256 emits 33 / 131 nodes; SHA3 emits 35 / 132. Linear work is explicitly retained in the contract. |

Initial logs are local `review62-*.log` files. A frozen final-head validation report is still required after the remaining repairs. Earlier native byte/diagnostic and read-oracle results are historical evidence, not newly passing gates.

## Publication repair evidence

`review62-rooted-publication-gate.log`: **102 tests passed** across body generation/planning/reverse, current body churn, header reverse, processor plans/source declarations, production cold boot, Stage3 and Rocks reopen. The failing-before F03 case was reproduced with both digests. Fault injection stops before and after every put/node write/flush/sync/root-publication boundary; reopening always resolves a complete old or new snapshot, and the retained old snapshot remains readable. A competing-publisher test admits exactly one root transition. The inherited-LOCAL test reboots headers in the same store and resolves every old value through both retained LOCAL and BROOT.

F06 churn retains 6,000 obsolete consumers across 24 publications: a live-prefix lookup returns one key with one prefix hit and zero point reads, in memory and RocksDB including reopen. The old 501-entry tree still verifies. F08 streams 16 and 64 MiB of synthetic output payloads to RocksDB; the original arrays are reclaimable and final selection performs zero CF reads. These isolate storage/publication work; they are not a native class-byte or complete LIVE benchmark.
## Selection and processor-model evidence

`review62-binding-selection-gate.log`: **141 tests passed**, including 52 collector tests, publication/output regressions, processor plans and source-origin order, Rocks reopen and Stage3. `review62-graph-manifests-gate.log`: **115 tests passed**, including all 64 processor attribution cases and generated-output tests after PE/PB changes.

F11: one changed source among 128/1,024/8,192 units reads 29/32/49 nodes with SHA-256 and 32/42/45 with SHA3, with zero CF reads. Retiring the last shared owner removes the selected entry; retiring an earlier owner preserves it. Empty delta publishes no root or records. F10: 100 fresh type/package-name queries take 800 node reads with SHA-256 and 900 with SHA3, unchanged with 0/128/4,096 extra route origins. F13: nesting depths 4/16/64 produce 11/35/131 PE nodes totaling 20,314/25,492/60,438 bytes under both digests; PM declaration values stay below 128 bytes, the top lookup reads one PE, and every traversed PE is read at most once. Intrinsic nested binary-name length is included; no constant-size-key theorem is claimed.

The intermediate 2,000-file Lombok measurement was stopped while still in cold attribution: a thread dump showed repeated per-file class-loader initialization, confirming the remaining F07 setup issue. It will be rerun after that repair; no result is claimed from the interrupted run.
