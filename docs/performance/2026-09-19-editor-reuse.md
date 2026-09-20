# Editor reuse and JDTLS comparison

**Correctness validation in progress:** the measured production revision `1d240e6` passed distribution builds but failed the checkpoint and corpus gates. Follow-up fixes are being validated for dependency bytecode reference publication and API fingerprint restoration. These measurements do not establish readiness to merge.

## Evidence and scope

103 timed workers, 324 workspace openings, 23616 checked editor requests. Milliseconds, lower is better. Tables use medians of worker summaries, not pooled request medians. The committed JSON retains worker values, ranges, identities, counters and profile aggregates. **The final raw protocol traces, JFR recordings and build manifests were lost during workspace maintenance after their archive upload failed.** The aggregate JSON was recovered from GitHub and committed unchanged. The original description inside that JSON predates the loss; this report supersedes its claim of independent auditability. Harnesses remain in `benchmarks/workspaces` for rerunning.

The measured final commit is `1d240e667ea9575db847b7324128e6312dbff516` (tree `f5d9337fe2f5e2d751c6fd74468b5f6d26b6e3f3`). Main is `dce594130a0ec0f2f213293c8429fa26051dd453`; earlier PR is `43cb57e4d4f3da09c5c993c9bf6cd1f193cba668`; byte cache is `6a01e45d8081d1b02f9edeca665e4006dda041c9`; context reuse is `643419ccce7483edf2a4a96ae6480c84ec8b2150`. Local profile revision `647e43734e517034d657dd13d44d39b6f4b1b517` has the same final tree. Added-JAR measurements use `acc4cd15ccbed330ffc01ddc27e45bf28ddceb5f`, before the final completion-only validation change.

## What changed and why

The profile identified repeated binary loading, javac context setup, release-platform setup and method-body parsing. This PR retains validated class bytes and release-platform state across ordinary source edits, reuses a parse-only focusing context, and caches a bounded detached completion candidate set across prefix growth. Cache admission checks the surrounding source, other source identities, compiler options and classpath content; changed inputs and prefix broadening recompute. Module descriptors and package metadata retain stronger invalidation. Workspace semantic graphs batch attribution and reuse detached results for navigation and references.

The dependency index amortizes binary discovery and search across workspaces. It does not precompute application-specific type binding. javac still attributes changed code; JDT's incremental builder remains competitive on dependent API edits.

## Complete regular workload matrix

Real uses actual library JARs with a generated client. Single and multi are generated projects; these are not full real-project Maven import timings. Each regular fixture has three repetitions per mode. Shared modes open three new roots in one server and reopen after restart; isolated JDTLS uses separate servers.

### real

| Metric (ms) | Main | Earlier PR | Byte cache | Context reuse | Measured final | JDTLS shared | JDTLS isolated |
|---|---:|---:|---:|---:|---:|---:|---:|
| fresh | 2859.60 | 2826.84 | 2813.65 | 2898.18 | 2678.74 | 7411.78 | 7508.17 |
| new workspace | 729.09 | 302.38 | 315.48 | 295.95 | 288.39 | 689.84 | 7384.37 |
| restart | 2058.67 | 1677.70 | 1665.12 | 1711.90 | 1624.44 | 7408.59 | 7388.43 |
| dependency search | 12.49 | 13.31 | 12.92 | 19.15 | 14.03 | 24.41 | 28.65 |
| measured work | 17023.40 | 13083.90 | 12783.10 | 12606.03 | 9270.87 | 25669.49 | 41288.46 |
| preindex | 1758.87 | 1733.98 | 1698.89 | 1711.07 | 1618.21 | — | — |
| first hover | 511.89 | 49.51 | 45.11 | 57.76 | 43.82 | 8.76 | 159.05 |
| warm hover | 2.08 | 2.10 | 2.17 | 2.24 | 2.18 | 3.28 | 10.28 |
| first binary hover | 20.35 | 15.33 | 18.39 | 28.28 | 8.30 | 6.84 | 19.55 |
| warm binary hover | 1.89 | 1.96 | 2.00 | 1.72 | 1.74 | 4.06 | 10.85 |
| first binary completion | 30.70 | 30.12 | 27.84 | 34.75 | 25.02 | 46.41 | 224.10 |
| warm binary completion | 16.62 | 15.98 | 16.92 | 20.64 | 3.19 | 16.41 | 38.52 |
| first signature help | 13.86 | 12.88 | 18.90 | 13.38 | 6.32 | 12.04 | 57.59 |
| warm signature help | 13.58 | 13.06 | 12.24 | 13.01 | 4.82 | 5.61 | 11.61 |
| first definition | 3.31 | 2.65 | 2.79 | 2.63 | 2.65 | 2.84 | 12.36 |
| warm definition | 1.71 | 1.81 | 1.63 | 1.94 | 1.78 | 2.42 | 5.37 |
| first completion | 16.02 | 15.25 | 15.95 | 18.75 | 6.15 | 4.61 | 10.52 |
| warm completion | 12.29 | 12.66 | 18.14 | 13.82 | 1.84 | 3.85 | 7.11 |
| first references | 398.37 | 52.91 | 59.76 | 57.35 | 34.81 | 33.26 | 70.05 |
| warm references | 4.36 | 3.97 | 4.53 | 5.75 | 3.52 | 22.53 | 28.80 |
| first rename preview | 6.22 | 5.57 | 7.23 | 7.55 | 5.17 | 56.47 | 146.74 |
| warm rename preview | 5.16 | 4.91 | 6.18 | 5.11 | 4.98 | 49.49 | 58.09 |
| first document symbols | 21.90 | 21.96 | 18.51 | 14.87 | 5.13 | 1.16 | 6.64 |
| warm document symbols | 1.97 | 2.29 | 1.94 | 1.99 | 1.90 | 0.67 | 1.16 |
| typing completion | 15.44 | 15.16 | 15.75 | 16.64 | 5.19 | 4.37 | 6.09 |
| typing binary completion | 36.19 | 34.71 | 28.98 | 18.52 | 7.75 | 14.12 | 20.16 |
| typing signature help | 15.68 | 15.95 | 15.76 | 15.75 | 5.08 | 10.16 | 12.88 |
| diagnostics error | 229.19 | 235.24 | 228.47 | 225.90 | 213.73 | 479.63 | 478.77 |
| diagnostics restore | 206.10 | 206.14 | 207.75 | 206.90 | 206.21 | 488.86 | 505.76 |

### multi

| Metric (ms) | Main | Earlier PR | Byte cache | Context reuse | Measured final | JDTLS shared | JDTLS isolated |
|---|---:|---:|---:|---:|---:|---:|---:|
| fresh | 4095.30 | 4166.56 | 4117.81 | 3772.19 | 4107.11 | 7672.83 | 7805.55 |
| new workspace | 332.27 | 330.13 | 332.81 | 339.20 | 323.14 | 673.65 | 7801.49 |
| restart | 1836.59 | 1816.38 | 1809.50 | 1835.51 | 1829.68 | 4129.99 | 7511.73 |
| dependency search | 137.73 | 90.15 | 90.73 | 98.31 | 88.68 | 62.70 | 109.26 |
| measured work | 16716.51 | 15138.96 | 14968.05 | 14548.34 | 12089.71 | 22076.57 | 41992.23 |
| preindex | 3008.65 | 3099.16 | 3030.33 | 2730.16 | 3048.57 | — | — |
| first hover | 49.40 | 50.93 | 51.51 | 66.06 | 46.13 | 13.31 | 147.09 |
| warm hover | 3.72 | 3.66 | 3.77 | 4.22 | 4.50 | 3.40 | 8.23 |
| first binary hover | 17.23 | 16.48 | 16.83 | 19.32 | 10.59 | 4.30 | 12.83 |
| warm binary hover | 3.77 | 3.80 | 3.48 | 4.36 | 4.38 | 3.02 | 6.86 |
| first binary completion | 16.87 | 17.87 | 16.20 | 18.56 | 10.27 | 10.64 | 94.74 |
| warm binary completion | 14.51 | 14.85 | 13.90 | 19.85 | 3.97 | 5.33 | 11.43 |
| first signature help | 15.10 | 14.20 | 24.46 | 16.11 | 8.18 | 9.85 | 48.72 |
| warm signature help | 14.53 | 14.49 | 14.83 | 17.41 | 7.59 | 5.28 | 11.36 |
| first definition | 4.50 | 5.21 | 5.09 | 5.31 | 4.12 | 2.78 | 7.05 |
| warm definition | 3.46 | 3.77 | 3.62 | 3.69 | 3.78 | 1.98 | 4.65 |
| first completion | 39.55 | 30.40 | 19.67 | 18.33 | 9.21 | 5.51 | 14.07 |
| warm completion | 14.53 | 15.28 | 21.31 | 15.48 | 3.53 | 4.75 | 7.85 |
| first references | 365.71 | 49.56 | 56.40 | 55.54 | 42.40 | 30.77 | 84.09 |
| warm references | 7.24 | 6.84 | 6.52 | 6.98 | 7.16 | 24.09 | 38.59 |
| first rename preview | 9.21 | 7.77 | 10.31 | 8.06 | 8.64 | 61.47 | 204.37 |
| warm rename preview | 7.73 | 7.46 | 8.91 | 7.97 | 9.45 | 57.76 | 64.55 |
| first document symbols | 23.45 | 14.86 | 16.29 | 25.80 | 9.29 | 1.18 | 7.53 |
| warm document symbols | 3.82 | 3.29 | 3.45 | 3.31 | 4.13 | 0.63 | 1.08 |
| typing completion | 18.66 | 17.92 | 18.59 | 17.59 | 7.75 | 3.98 | 6.01 |
| typing binary completion | 26.47 | 24.11 | 26.04 | 19.80 | 7.71 | 4.16 | 6.20 |
| typing signature help | 17.20 | 15.87 | 19.39 | 18.11 | 6.66 | 8.45 | 11.11 |
| diagnostics error | 232.49 | 236.99 | 234.51 | 223.40 | 216.84 | 445.49 | 484.03 |
| diagnostics restore | 207.86 | 208.51 | 208.59 | 208.76 | 208.02 | 477.31 | 480.33 |

### single

| Metric (ms) | Main | Earlier PR | Byte cache | Context reuse | Measured final | JDTLS shared | JDTLS isolated |
|---|---:|---:|---:|---:|---:|---:|---:|
| fresh | 9984.86 | 9332.44 | 9031.49 | 9241.16 | 9038.42 | 7649.63 | 7778.53 |
| new workspace | 283.87 | 273.53 | 282.64 | 287.09 | 287.00 | 658.02 | 7608.78 |
| restart | 1551.25 | 1567.65 | 1662.29 | 1636.73 | 1545.12 | 7374.25 | 7772.53 |
| dependency search | 12.50 | 12.07 | 14.38 | 11.90 | 11.09 | 22.11 | 29.99 |
| measured work | 20250.80 | 18715.46 | 18436.05 | 18304.73 | 15265.45 | 24374.90 | 40419.76 |
| preindex | 8939.56 | 8311.17 | 8062.37 | 8247.75 | 8070.65 | — | — |
| first hover | 46.16 | 42.00 | 48.51 | 51.05 | 37.01 | 7.03 | 139.45 |
| warm hover | 1.93 | 1.86 | 1.87 | 2.38 | 1.91 | 2.72 | 7.47 |
| first binary hover | 13.20 | 15.16 | 14.00 | 16.67 | 6.28 | 5.05 | 14.40 |
| warm binary hover | 1.78 | 1.76 | 1.87 | 1.93 | 1.57 | 2.76 | 5.19 |
| first binary completion | 20.07 | 16.00 | 19.37 | 16.44 | 6.21 | 8.72 | 98.18 |
| warm binary completion | 11.98 | 12.60 | 13.03 | 14.15 | 1.75 | 4.02 | 8.82 |
| first signature help | 12.13 | 17.92 | 13.31 | 15.55 | 4.85 | 8.40 | 42.02 |
| warm signature help | 12.53 | 13.85 | 13.03 | 13.08 | 4.02 | 4.72 | 9.20 |
| first definition | 2.41 | 2.73 | 2.59 | 2.72 | 1.82 | 2.08 | 6.33 |
| warm definition | 1.57 | 1.80 | 1.57 | 1.57 | 1.46 | 1.75 | 3.83 |
| first completion | 15.24 | 22.50 | 18.95 | 15.19 | 5.95 | 5.02 | 10.04 |
| warm completion | 13.28 | 16.20 | 12.44 | 13.06 | 1.58 | 3.52 | 8.21 |
| first references | 335.92 | 46.36 | 52.47 | 61.54 | 35.08 | 21.81 | 65.10 |
| warm references | 3.03 | 3.74 | 3.66 | 5.30 | 4.37 | 15.73 | 36.45 |
| first rename preview | 4.91 | 5.54 | 6.19 | 5.33 | 6.67 | 55.26 | 157.58 |
| warm rename preview | 4.32 | 5.42 | 6.01 | 5.65 | 4.89 | 47.46 | 51.46 |
| first document symbols | 12.91 | 13.09 | 19.46 | 23.56 | 6.20 | 1.21 | 8.15 |
| warm document symbols | 1.81 | 1.80 | 1.83 | 1.64 | 1.72 | 0.67 | 0.97 |
| typing completion | 14.38 | 16.56 | 16.64 | 15.38 | 4.93 | 4.36 | 5.83 |
| typing binary completion | 21.38 | 23.68 | 21.91 | 15.36 | 5.55 | 4.56 | 6.37 |
| typing signature help | 14.91 | 16.68 | 16.68 | 15.04 | 4.42 | 7.15 | 9.81 |
| diagnostics error | 232.68 | 230.87 | 227.14 | 225.22 | 216.07 | 453.14 | 461.28 |
| diagnostics restore | 207.52 | 206.56 | 206.27 | 206.61 | 206.24 | 459.60 | 479.57 |

### Larger multi-module fixture: 128 source files

Two repetitions per mode.

| Metric (ms) | Main | Earlier PR | Context reuse | Measured final | JDTLS shared |
|---|---:|---:|---:|---:|---:|
| fresh | 4670.12 | 3993.91 | 4107.23 | 4002.79 | 7728.88 |
| new workspace | 376.43 | 346.11 | 333.27 | 343.69 | 713.32 |
| restart | 1886.96 | 1877.34 | 1895.34 | 1861.70 | 4368.61 |
| dependency search | 155.71 | 122.33 | 141.37 | 98.88 | 88.89 |
| measured work | 21534.17 | 13734.20 | 13494.99 | 11373.27 | 20061.66 |
| preindex | 3522.56 | 2867.12 | 2984.75 | 2952.50 | — |
| first hover | 51.34 | 74.42 | 60.88 | 45.89 | 10.17 |
| warm hover | 5.42 | 6.38 | 4.38 | 4.46 | 3.17 |
| first binary hover | 18.04 | 22.39 | 17.45 | 10.02 | 3.63 |
| warm binary hover | 4.26 | 4.85 | 4.21 | 4.75 | 2.93 |
| first binary completion | 21.23 | 42.34 | 18.80 | 10.47 | 8.05 |
| warm binary completion | 15.76 | 18.55 | 16.28 | 5.08 | 4.96 |
| first signature help | 15.22 | 16.47 | 16.91 | 8.39 | 11.70 |
| warm signature help | 16.50 | 16.32 | 17.92 | 8.46 | 6.14 |
| first definition | 7.12 | 5.16 | 5.36 | 5.20 | 3.10 |
| warm definition | 4.30 | 4.47 | 4.02 | 4.57 | 2.55 |
| first completion | 16.73 | 19.49 | 18.24 | 10.21 | 5.90 |
| warm completion | 15.64 | 15.35 | 14.11 | 4.55 | 4.53 |
| first references | 2581.25 | 258.54 | 213.34 | 191.19 | 60.49 |
| warm references | 10.79 | 15.75 | 18.00 | 21.22 | 57.58 |
| first rename preview | 18.83 | 23.79 | 20.29 | 25.16 | 88.87 |
| warm rename preview | 27.74 | 24.21 | 19.10 | 20.66 | 73.28 |
| first document symbols | 25.51 | 18.12 | 20.10 | 10.18 | 1.41 |
| warm document symbols | 8.21 | 4.40 | 5.42 | 4.17 | 0.57 |
| typing completion | 17.26 | 20.39 | 21.66 | 9.08 | 4.23 |
| typing binary completion | 25.83 | 29.48 | 24.06 | 9.13 | 4.53 |
| typing signature help | 17.82 | 19.17 | 19.53 | 6.89 | 8.08 |
| diagnostics error | 231.40 | 251.97 | 225.69 | 219.25 | 498.46 |
| diagnostics restore | 213.11 | 212.32 | 211.44 | 209.55 | 473.45 |

## Prefix typing, including cache misses and backspace

Three rotating repetitions per fixture/mode; eight chains each for source and binary members. Each chain starts at two characters after a surrounding-source change, grows character by character, then backspaces to one. Times include document-change send through checked completion response. No empty-selector target-ranking assumption. “Before” is `872487af1fff4eb987f06406af98658a1b3e7599`. Unlike the earlier typing workload, these chains specifically test edits to the identifier token. Completion-list clients may filter locally instead of sending every character; this is server round-trip timing, not an editor UX prediction.

### real token edits

| Metric (ms) | Before | Measured final | JDTLS |
|---|---:|---:|---:|
| source initial | 14.79 | 9.92 | 8.13 |
| source growth | 7.46 | 3.43 | 7.05 |
| source backspace | 7.39 | 7.97 | 12.47 |
| binary initial | 13.93 | 11.86 | 23.52 |
| binary growth | 7.55 | 3.16 | 14.86 |
| binary backspace | 8.64 | 10.87 | 23.18 |

### multi token edits

| Metric (ms) | Before | Measured final | JDTLS |
|---|---:|---:|---:|
| source initial | 14.87 | 12.76 | 6.74 |
| source growth | 11.22 | 5.34 | 6.97 |
| source backspace | 11.08 | 11.67 | 13.51 |
| binary initial | 15.17 | 12.55 | 6.91 |
| binary growth | 9.17 | 5.16 | 6.30 |
| binary backspace | 7.70 | 11.22 | 6.55 |

### Complete typing sequences

Each sequence includes the initial miss, all growth requests and the backspace miss. Values are medians of per-worker chain medians. Source chains have five requests; binary chains have ten for real and seven for multi.

| Fixture / chain | Before (ms) | Measured final (ms) | JDTLS (ms) |
|---|---:|---:|---:|
| real / source | 45.17 | 28.67 | 42.57 |
| real / binary | 85.67 | 49.44 | 172.68 |
| multi / source | 59.06 | 41.93 | 41.09 |
| multi / binary | 71.02 | 50.25 | 46.82 |

## Saved base-module API changes

Four Maven/Eclipse modules, 32 files each, base→core→app plus independent. Two repetitions, eight saved body/signature changes. JDTLS autobuild enabled. Consumer diagnostics requested lazily for JVMD; not full-builder completion timing. “Before” is merged main. These are correctness-checked lazy consumer responses, not timings of complete background builds. The separate CI diagnostics failure on API restoration is under investigation and limits the generality of this successful benchmark.

| Metric (ms) | Main | Measured final | JDTLS |
|---|---:|---:|---:|
| ready | 1774.17 | 1722.47 | 7013.22 |
| first core | 76.22 | 75.93 | 123.21 |
| warm core | 23.05 | 6.62 | 7.64 |
| first app | 47.96 | 35.16 | 14.18 |
| warm app | 26.55 | 7.80 | 12.27 |
| first independent | 43.76 | 42.31 | 7.29 |
| warm independent | 26.18 | 6.29 | 7.09 |
| body change to correct | 26.91 | 12.66 | 13.62 |
| body core | 26.68 | 12.36 | 13.43 |
| body app | 21.70 | 10.29 | 5.78 |
| body independent | 17.10 | 4.04 | 5.58 |
| signature change to correct | 18.56 | 15.30 | 11.17 |
| signature core | 18.36 | 15.08 | 11.04 |
| signature app | 18.84 | 13.06 | 5.31 |
| signature independent | 21.44 | 5.90 | 5.08 |

## Newly added dependency JAR

| Metric | JVMD (ms) | JDTLS shared (ms) |
|---|---:|---:|
| New workspace ready | 366.06 | 700.88 |
| Complete dependency search | 102.09 | 91.71 |

The added-JAR run checked the new 129-type result and the old session's 128-type result. JVMD recorded one additional index hash, one additional classpath hash and no additional repository scan. The real final run retained five classpath hashes across the three resident roots and restart. Search drains every 20-row page and checks identities; server payloads differ.

## Cost analysis and remaining gaps

The 160-edit in-process profile estimated 6809.4 MB allocated before versus 368.5 MB final, a 94.59% reduction. This is allocation sampling, **not disk storage or retained heap**. It is distinct from the earlier index storage reduction. The final profile deliberately changed surrounding source: zero prefix-cache hits, one release-platform initialization and 479 reuses. Class-byte loads fell from 4,160 to 26. JFR overhead is excluded from the latency tables.

Warm real source completion is 1.84 ms versus 3.85 ms; warm binary completion is 3.19 versus 16.41 ms. The complete generated multi typing sequences and saved-signature workload still favor JDTLS. A continuous advantage on every operation is not demonstrated.

First reference attribution remains costly: the larger fixture takes 191.19 ms versus 60.49 ms; repeated references take 21.22 versus 57.58 ms. Reuse benefits unchanged graphs, while edits require re-attribution.

The existing marker-source layout cache means “no caching” was too broad. The new cache removes repeated semantic work during prefix narrowing. Misses still run flow analysis. Eager documentation summaries, missing completion-item resolve, no-op cancellation, absent unimported-type index completion and case-sensitive prefix-only matching remain. The bounded prefix cache falls back to normal analysis above 256 source files or oversized inputs/results. Non-Unix filesystems may require conservative rehashing; Windows and remote-filesystem latency are unmeasured.

## Method and interpretation

Linux, 8 CPU EPYC 9V74, approximately 14 GiB quota; Temurin 25.0.4.1+1, JDTLS 1.61.0, Maven 3.9.9, Node 24.19, 1 GiB server heaps, no AOT and no OS cache flush. A pipe adapter uses production Application/Dispatcher/RpcClient/LspBridge because Unix-domain sockets were unavailable. JDTLS uses fixed offline classpaths with import disabled; auto-build is enabled for saved-module edits and disabled for ordinary editor workloads. Normal diagnostic readiness includes each server's debounce (JVMD about 200 ms, JDTLS about 500 ms).

Cohorts were successive except rotating prefix runs. Small repetition counts do not establish statistical significance. First references follow hover/completion; rename follows references. Measured work sums selected operations and excludes idle and shutdown. “Fresh” includes server/workspace startup; “preindex” records index preparation where applicable; “new workspace” measures roots opened after server warm-up. The JSON contains the underlying worker summaries and ranges. No values were extrapolated to unseen projects.

Harness: [benchmarks/workspaces](../../benchmarks/workspaces/README.md). Data: [editor reuse summary](2026-09-19-editor-reuse-summary.json). Earlier storage/index comparisons remain in [compact grams and pages](2026-09-19-compact-grams-and-pages.md).
