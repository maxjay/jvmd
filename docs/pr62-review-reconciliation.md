# PR62 consolidated review reconciliation

The supplied `JVMD-PR62-consolidated-review.md` reviews `aefad3bda1d647100b56f11b2d6035d89deaf5d0` against `33a9032d0a22f87f2ee306f5e2d935190db6b691`. The repair checkout started clean at that exact head on 7 October 2026. This register records independently checked findings and repair evidence; it is not an approval verdict. PR62 remains a draft.

## Findings

| Finding | Current reconciliation |
| --- | --- |
| F01 | Reproduced under both digests using native class files. Fixed: equal definer sums discharge T/presence only; consumed N answers are checked after provider changes. Native success/failure, positive/zero N and T-only reuse controls pass. |
| F02 | Independently reproduced after fixing F01. Fixed: body discovery accepts separate presence and exact definer deltas, queries type-wide T/N prefixes for a changed winner and deduplicates overlapping seeks. Body N carries actual sums, unlike header N zero predicates. More publication/churn coverage follows F06. |
| F03 | Confirmed in publication code: mutable records precede BROOT. Immutable historical values and atomic current publication repair pending. |
| F04 | Accepted open proof obligation. Existing owner-load coverage is insufficient for either direction of the required query-level theorem. Native diagnostics, query capture and strict two-sided coverage work remain open. |
| F05 | Accepted work-bound correction. The called Valid fallback scans its grouped proof; its actual bound is now explicit in §7.2. No changed-only API or production LIVE scheduler is claimed. Frontier-based validation remains future work, not a hidden assumption of this cold driver. |
| F06 | Confirmed: raw body history plus per-consumer BROOT checks, and sequential history-number search. Repair pending with F03. |
| F07 | Confirmed processor jar/config rereads, whole-own eviction, node existence probes and duplicate validation work. Repair pending. |
| F08 | Confirmed generation pending/selection maps retain complete class payloads. Streaming provisional content repair pending. |
| F09 | Reproduced independently. Contract correction: no deterministic local rewrite theorem for cap-only runs. Regression retains canonical root/conservation checks and measures the linear adversary. No change to tree encoding or canonical chunking. |
| F10 | Confirmed source binding scans origin T trees on a first query and is recreated per task. Indexed origin-aware binding repair pending. |
| F11 | Confirmed LOCAL-to-BROOT diff enumerates the body inventory and full selected values are rehashed. Changed-selection publication repair pending. |
| F12 | Fixed: rooted F-prefix traversal prunes unrelated subtrees and passes the yielded entry as membership evidence. The production row planner is tested with one F row and 0/1,000/10,000 unrelated records, including corruption rejection. |
| F13 | Confirmed recursive projections duplicate descendants in ancestor PM values. Shared declaration representation repair pending. |
| F14 | Confirmed repeated whole-member enumeration and per-active-scope worker-manager allocation. Native name-scoped lookup and aggregate resource accounting repair pending. |
| F15 | Accepted evidence boundary. A fresh cold build may construct complete OUT; the Lombok harness's avoided javac count does not establish delta-only total work or production LIVE integration. Incremental output/publication APIs remain to be addressed with F11. |
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
