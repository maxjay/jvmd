# Shared-workspace JVMD / JDTLS benchmark — 2026-09-19

JVMD's shared dependency index pays off across genuinely new workspaces. The real-library startup gap came from rereading the same JARs in separate analyzers. Sharing validated classpath identities reduces resident new-workspace readiness from **663 to 291 ms**; shared JDTLS takes **685 ms**. Batching workspace attribution reduces its first references request from **379 to 61 ms**.

JDTLS remains faster at first references and several completion and outline operations. The large single-JAR fresh case also favors JDTLS. These are measured fixture results, not a universal language-server ranking.

The suite completed **57 unprofiled benchmark runs, 222 workspace openings and 11,232 checked editor requests**, plus separate profiles.

## Readiness, including the upfront cost

Milliseconds; lower is better. Fresh JVMD includes daemon launch, indexing the entire supplied repository, then opening the first workspace. A new workspace is a distinct project in an already resident process. Readiness means an open file with clean diagnostics, not a fully attributed workspace graph; first workspace-wide references are measured separately.

| Dependency corpus | JVMD fresh including seed | JDTLS fresh | JVMD new root | JDTLS new root, shared | JDTLS new root, separate |
| --- | ---: | ---: | ---: | ---: | ---: |
| One JAR / 380,000 symbols | 9,625.2 | 7,884.5 | 283.6 | 687.9 | 8,056.3 |
| 128 JARs / 379,904 symbols | 3,895.3 | 8,426.9 | 330.9 | 681.4 | 8,200.5 |
| Five real libraries / 18,997 symbols | 2,837.9 | 8,157.9 | 290.6 | 685.0 | 8,198.0 |

JVMD is approximately **2.1–2.4× faster** for a new root against shared JDTLS. The larger gap against separate JDTLS processes includes server startup. The one-JAR fixture's 8.65-second upfront JVMD seed must be amortized before JVMD wins on cumulative readiness; it is not hidden from the table.

Restarting against persisted state and opening another new root:

| Dependency corpus | JVMD restart + index reopen + new root | JDTLS shared-state restart + new root |
| --- | ---: | ---: |
| One JAR / 380,000 symbols | 1,587.4 | 7,236.1 |
| 128 JARs / 379,904 symbols | 1,862.3 | 7,187.5 |
| Five real libraries / 18,997 symbols | 1,714.3 | 7,228.5 |

JVMD's artifact-hash counters do not increase across resident new roots, and restarted indexes hash zero existing JARs. The final compiler classpath identity cache also reads the five real JARs only once (98,358,301 bytes) across all three resident workspaces, including diagnostics and references. That in-memory cache validates the JARs once again after restarting the daemon.

## Before/after and editor behavior

The before baseline is the previous PR state `a078ad40`, which already contains the compact-gram and bounded-index-page improvements. It is not merged main. The earlier [main comparison](2026-09-19-compact-grams-and-pages.md) remains a separate experiment.

First references request in a new 24-file workspace:

| Corpus | JVMD before | JVMD final | JDTLS shared |
| --- | ---: | ---: | ---: |
| One JAR / 380,000 symbols | 363.5 | 54.8 | 22.1 |
| 128 JARs / 379,904 symbols | 367.6 | 61.1 | 31.7 |
| Five real libraries / 18,997 symbols | 378.6 | 60.6 | 29.2 |

JVMD's first references work is now about 6–7× faster than the previous PR state, although JDTLS still wins the first request. Once detached bindings exist, JVMD is faster at repeated references and rename previews.

Warm operations on the real-library fixture:

| Operation | JVMD before | JVMD final | JDTLS shared |
| --- | ---: | ---: | ---: |
| Source hover | 2.6 | 2.7 | 3.9 |
| Dependency hover | 1.9 | 2.1 | 4.6 |
| Dependency completion | 15.3 | 19.6 | 14.2 |
| Signature help | 12.2 | 12.7 | 6.5 |
| Definition | 1.8 | 1.6 | 2.8 |
| Source completion | 12.5 | 14.9 | 3.5 |
| References | 3.2 | 3.5 | 23.7 |
| Rename preview | 4.9 | 5.4 | 44.3 |
| Document symbols | 1.9 | 2.2 | 0.6 |
| Edit → expected error | 230.8 | 244.5 | 490.6 |
| Fix → clean diagnostics | 206.8 | 207.9 | 490.6 |

This is not a universal warm-query improvement. Real-library source completion rises from 12.5 to 14.9 ms and binary completion from 15.3 to 19.6 ms in this sample. Edit-to-error publication is also slightly slower. Removing compiler invocations changes JIT/GC history; that is a possible contributor, not an established explanation for these regressions.

Full dependency search, consuming every JVMD 20-result page:

| Corpus | JVMD before | JVMD final | JDTLS shared |
| --- | ---: | ---: | ---: |
| One JAR / 380,000 symbols | 1.6 | 1.4 | 17.4 |
| 128 JARs / 379,904 symbols | 52.9 | 38.6 | 28.7 |
| Five real libraries / 18,997 symbols | 1.6 | 1.8 | 20.5 |

Cursor continuation lowers the 128-result search from 52.9 to 38.6 ms, but JDTLS's 28.7 ms is still lower. These APIs have different payloads. The earlier index microbenchmark's large page ratios do not translate into equally large end-to-end editor gains.

## Larger workspace and added-dependency checks

Two serial repetitions of 128 source files and 128 JARs, with three warm samples per operation:

| Measurement | JVMD before | JVMD final | JDTLS shared |
| --- | ---: | ---: | ---: |
| New workspace ready | 388.4 ms | 336.9 ms | 734.2 ms |
| First references | 2,185.5 ms | 266.7 ms | 52.6 ms |
| Warm references | 10.3 ms | 14.2 ms | 41.5 ms |
| Warm rename preview | 19.4 ms | 24.7 ms | 68.8 ms |
| Full dependency search | 83.9 ms | 56.0 ms | 28.7 ms |

The final implementation uses four 32-file compiler batches. Every references and rename response covers all 128 expected files. Its first graph build is **8.2× faster**, while warmed references and rename are slower than the prior JVMD sample and remain faster than JDTLS.

In three additional repetitions, a 129th dependency is installed immediately before opening the third workspace. JVMD is ready in **332.4 ms**, versus **688.2 ms** for shared JDTLS. Both return all 129 expected types. JVMD's index and classpath hash counts rise from 128 to 129: only the new JAR is hashed. The regression test separately disables scanning and confirms immediate searchability without a full repository scan.

## Implemented changes

1. Dependency search continues from the last returned index ID. Previously each RPC page started at the beginning and discarded preceding results. Existing numeric offsets remain accepted; clients must treat returned cursors as opaque.
2. Workspace references build detached bindings in batches, grouped by module and main/test context, capped at 32 files or 1 MiB of source characters. Complete per-file bindings and diagnostics are retained. Compiler objects remain confined to their owning executor.
3. Opening a workspace publishes dependencies introduced since the last background scan. Existing releases reuse their generations; missing JARs become searchable immediately. A regression test reproduced the previous missing result before the fix.
4. An application-owned, bounded cache shares classpath content identities between interactive analyzers, diagnostics actors and workspace binding checks. Hits validate size, modification time, change time and inode. Providers without those attributes conservatively rehash. Streaming buffers are capped at 64 KiB and shrink for small files, replacing whole-JAR arrays.
5. The indexed file manager skips URI conversion when no binary-source overrides exist and skips redundant directory checks on JAR entries.

## Profiles and remaining costs

Separate, matched JFR runs use the real-library corpus and the same editor workload. They are excluded from latency summaries.

| Profiled process | Before allocated bytes, sampled | Final allocated bytes, sampled | Reduction |
| --- | ---: | ---: | ---: |
| Fresh seed + three workspaces | 2,678 MB | 1,586 MB | 41% |
| Restart + new workspace | 769 MB | 406 MB | 47% |

The baseline fresh recording attributes approximately 590 MB of allocation samples to `FileStateRegistry.hash`, consistent with whole-file arrays for repeated reads of a 98 MB classpath. The shared streaming path removes those repeated allocations. These estimates describe allocation, not retained heap or disk storage.

The intermediate real-library run spent about 402 ms in first diagnostics but only 44 ms in the compiler query. That gap prompted the classpath-identity fix. On the first final run, new-root diagnostic work falls to about 33 ms; overall readiness is 274–285 ms including initialization, debounce and transport.

The ordinary **200 ms diagnostics debounce remains unchanged**. Completion and signature help still prepare focused source and execute compiler queries per request. In the final restart profile, 54 of 101 execution samples include `CompilerPool.query`; inclusive samples are not a wall-clock partition. Further useful completion gains need reuse across actual typing edits while preserving scope, overload resolution and invalidation. Caching only repeated identical benchmark requests would not establish that benefit.

Dependency signatures can be shared, but each workspace has distinct source, classpath order, options and unsaved changes. Its bindings must still be established against those inputs. A persistent dependency index does not preserve a live javac task.

## Method and limits

- JDK 25.0.4.1+1, JDTLS 1.61.0, Node 24.19.0, Linux, 1 GiB heaps, no AOT. Node is below the repository's documented 24.21+ recommendation; the exercised bridge ran successfully.
- Host: AMD EPYC 9V74, 8-CPU cgroup quota, 14 GiB memory limit. Serial workers, with local compilation, tests and profiles kept separate from latency runs. No OS page-cache flush.
- Primary corpora: 1 JAR / 380,000 symbols; 128 JARs / 379,904 symbols; 5 real libraries / 18,997 symbols. The real bytes are Jackson annotations 2.22, Jackson core/databind 2.22.2, SQLite JDBC 3.53.4.0 and RocksDB JNI 10.10.1.1. Each primary project has 24 generated source files.
- Three distinct roots share a resident server; a fourth new root opens after restart with persisted state. JDTLS is measured in shared multi-root and independent-process configurations. The isolated restart retains the first server's state.
- JVMD resolves local Maven POMs. JDTLS imports equivalent explicit Eclipse classpaths; Maven/Gradle import and autobuild are disabled. This is an offline editor/classpath comparison, not network dependency resolution, Maven builds or annotation processing.
- Readiness requires clean diagnostics. JDTLS must first discover all generated source types, preventing its early syntax-only mode from being counted as ready. First workspace-wide attribution is timed separately.
- Unix-domain sockets are blocked in the execution environment. A benchmark-only pipe adapter uses the actual JVMD application, dispatcher, framing, RPC client and production LSP bridge. JDTLS uses normal stdio. These are not packaged socket/AOT launch timings.
- Editor checks cover source and binary hover, source and binary completion, signature help, definition, references, rename preview, document symbols, an unsaved error and its correction. References and rename must cover every expected occurrence.
- Dependency search uses JVMD `symbol.find(scope=deps, kinds=class)` and JDTLS `workspace/symbol`. JVMD returns full rows; JDTLS returns lighter symbols. JVMD does not expose `workspace/symbol` over LSP. Timings include complete responses and all JVMD pages. JDTLS prefix matches are filtered to the requested identity pattern after the entire response is received.
- Primary summaries are medians of three run summaries. Each run combines its two resident new roots; warm operation summaries use each root's five-sample median. Larger/added-dependency cases use three warm samples, with repetitions stated above.
- The initial 36-run matrix rotates baseline, intermediate JVMD and both JDTLS configurations. The final shared-identity change receives nine subsequent JVMD runs with rotating corpus order. Final JVMD and JDTLS are therefore measured in successive phases on one host, not perfectly interleaved.
- Final real-library new-root run summaries range from 279.7 to 291.0 ms; shared JDTLS ranges from 627.7 to 688.4 ms. Three runs establish this fixture result, not narrow population confidence intervals.
- `wait4` maximum RSS is not a simultaneous sum of Node and Java memory, so it is not reported as total frontend memory.

## Revisions and validation

- Before: `a078ad40b2f0c45966a42e3c77ed61b0e6200185`.
- Intermediate: `7e6d5ad79720078581d772cd77fcb11475ed1779`, tree `8443495ca3dce807e5eddea3e98c389541462dbc`.
- Final measured production: remote `f7d7baeedea24f633dd1dd94a9ee69b12ca439d2`, local `bbaa049c87f568994b56f7c3f29450d62a54a4eb`, identical tree `67080dec4c59eb3500f8dd70233baf8532cc3b3f`.
- Test-only fixture correction: `e72409da491a106bd18c03e7915411b6bb97426f`; production code is unchanged.
- **21 focused tests pass**, including shared content identities, preserved-timestamp JAR replacement, streaming hash agreement, pagination, missing dependencies, batch equivalence, unsaved API invalidation, actor isolation and LSP behavior.
- All 57 unprofiled runs pass. An additional check verifies **888 first/warm reference and rename responses** against expected filenames and actual source ranges. JDTLS reference ranges may cover an invocation; rename ranges must select exactly the method name.
- [All platform packaging/AOT/relocation jobs and Windows installer pass for the measured production](https://github.com/maxjay/jvmd/actions/runs/35465315463). The subsequent [full checkpoint run](https://github.com/maxjay/jvmd/actions/runs/35465315484) exposed a directory-walk race in `IncrementalDiagnosticsStoreTest`: an unrelated diagnostics snapshot temporary file disappeared during source discovery. The follow-up fix replaces `Files.find` in `Application.sourceFiles` with the existing `FileInventory.matching` visitor, which tolerates concurrent removal while preserving other I/O errors. That existing failing test remains unchanged. CI is rerunning; this small follow-up was not included in the recorded timings because the execution environment is offline. No tests, thresholds or gates were weakened.

## Evidence availability

[Machine-readable aggregates](2026-09-19-shared-workspaces-summary.json) preserve the values directly observed in tool output, including ranges, profiles, counts and revisions.

The execution environment disconnected **after** all benchmark runs and checks completed, while the report bundle was being prepared for upload. A 3,000,744-byte gzip archive of full raw reports had been created locally, but it and the new workspace harness could not be uploaded. The full raw samples, response payloads and recordings are therefore **not attached to this PR**. This limits independent auditing of the aggregate results. No missing samples or hashes have been reconstructed. Existing fixture-generation and index benchmark tools remain in [benchmarks/index-updates](../../benchmarks/index-updates).

The generated fixtures used `RepositoryUpdateBenchmark fixture` parameters `1 950 397 false` and `128 8 368 false`. The real-library classpath retains each library's original bytes under offline fixture coordinates. A complete repeat of the workspace experiment requires recovery of the executed workspace harness; the index-only harness is not a replacement for it.
