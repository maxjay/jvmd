# Daemon boot and persistence assessment: cold construction versus warm restoration

Investigation only. No production behaviour is changed on this branch. The only production-source changes are
observation hooks, and they are off unless `-Djvmd.profile.boot_events` is set (see [§9](#9-reproduction-and-closeout)).

| | |
|---|---|
| Subject (frozen) | `0f7c825d7b49fe7ba24d26606517bb8208b72e72`, *Persistence and incremental semantic results (#55)*. This was `main` when the assessment started and has not moved since. |
| Instrumentation revision that produced the principal numbers | `ac56c71` (gated hooks plus harness). Run [37004267057](https://github.com/maxjay/jvmd/actions/runs/37004267057), attempt 1. |
| Supplementary run | [37005215565](https://github.com/maxjay/jvmd/actions/runs/37005215565): harness `fee553e`, same instrumentation. Adds the G1 pause log and the native-library size. |
| Final instrumentation | `33ed106`. Identical to `ac56c71` when observation is enabled. When it is disabled, every mark's arguments are now guarded. Verification run [37006026335](https://github.com/maxjay/jvmd/actions/runs/37006026335), on a different runner, reproduces every verdict (see the [checkpoint log](#checkpoint-log)). |
| Harness | `benchmarks/boot/` and `.github/workflows/daemon-boot-profile.yml` |
| Compact evidence | [`docs/boot-evidence/summary.json`](boot-evidence/summary.json). Full per-boot evidence (events, `/proc` samples, JFR, collapsed stacks, flame graphs, exports) is in each run's `boot-*` artifacts, retained for 30 days. |
| Runner | GitHub-hosted `ubuntu-24.04`: 4 vCPU Intel Xeon 6973P-C, 16 GiB RAM, kernel 6.17 (Azure). One runner per scenario. All five control pairs ran on the same runner. |
| JDK / RocksDB | Temurin 25.0.4.1+1 (sha256 `dbb69839…`), product jlink image with trained AOT cache; `rocksdbjni-10.10.1.1` |
| Profiler | async-profiler 4.3 (sha256 `69a16462…`), started with the process (`-agentpath`), stopped after the post-ready window |

Evidence labels: **OBSERVED** (counter, process event, file, dump or recorded result), **ATTRIBUTED** (sampled stack,
instrumented stage, ownership analysis), **INFERRED** (interpretation requiring assumptions), **PROPOSED** (not implemented or measured).

---

## A. Scope and equivalence verdict

**Verdict.** For the domain that native boot actually constructs, cold and warm boot reach the same declared
semantic state:

- **Equivalence:** all five control pairs, and every profiling and scaling pair, are `EQUIVALENT_BY_REUSE`.
- **Warm work:** warm boot parsed 0 artifacts. It re-hashed 34 SNAPSHOT JARs as freshness evidence, which the SNAPSHOT policy requires.
- **Persistence at ready:** what cold boot persists at DOMAIN_READY is enough for this reuse without an orderly shutdown. Process-restart recovery after SIGKILL was tested; power-loss durability was not.

Measured end to end, from process spawn to DOMAIN_READY:

| | median | range | n |
|---|---:|---:|---:|
| Cold | 37.4 s | 36.4–40.6 s | 5 |
| Warm | 2.743 s | 2.732–2.746 s | 5 |

Of the warm 2.743 s, 2.000 s is the configured initial scan delay.

**The product contract does not pass for the full intended domain.** It passes only for the *machine
dependency-signature domain*:

- **LOCAL not restored:** native boot does not restore or prepare the LOCAL layer.
  **TARGET GAP: local semantic restoration is demand-driven, not part of native boot.**
- **Not seeded at native boot:** JDK platform facts and code-body/reference edges.
- **Fails the completeness rule (§22.3):** one completeness signal is not bound to the data it certifies. After
  the metadata store was lost, the daemon reported `persisted_index_complete=true` and READY at 0.40 s with an empty
  artifact set ([F3](#f3)).

### BootDomainSpec

Machine-readable: [`benchmarks/boot/domain-spec.json`](../benchmarks/boot/domain-spec.json). Summary:

| Field | Frozen definition |
|---|---|
| Input universe | Every regular `*.jar` under `m2_repo`, except `*-javadoc.jar`. Here that is the apache/maven `5cd1b60` repository prepared outside every measurement: **462 binary JARs, 0 `-sources.jar`, 159,360,212 bytes** (OBSERVED, `input-manifest.json`; per-JAR sha256 in `fixture-jar-sha256.txt`). `*.jar.sha1` files are read for checksum verification. |
| Platform | jdk_home = the pinned JDK. No JDK facts are indexed at native boot: `indexJdk` is reached only from `ensureSignatureEdges`, `Documentation` and `CodePass`, which are all session requests. |
| Configuration | Product defaults: `heap_ceiling_mb=1024` → generation (admission) budget 128 MiB; Rocks native budget 64 MiB; `min(4, cpus)` = 4 index readers; initial scan delay 2 s; periodic scan every 60 s; `awaitRepositoryScan=false`; no `-Xmx`; index format generation `format-1-jdk25-jvmd-index-v9`. Launch is equivalent to the product launcher: image `bin/java -XX:AOTCache=…/jvmd.aot -Xlog:aot`, with isolated `jvmd.config`, `jvmd.state` and `jvmd.socket`, plus `-Djvmd.profile.boot_events`/`boot_dump`. |
| Result families | Per binary artifact:<ul><li>an immutable signature generation (symbols, binary keys, SCIP, names, owner/name paths, trigram postings, outgoing and reverse relationships, class references, checksummed manifest);</li><li>an artifact manifest;</li><li>an inventory entry;</li><li>activation (active manifest plus `VALIDATED`).</li></ul>Documentation members where `-sources.jar` exist: none in the corpus, so the small fixture covers them. |
| LOCAL scope | `NOT_EXERCISED_BY_NATIVE_BOOT`. `SemanticMemoStore` is constructed without reading records. AttributedMemos, workspace bindings, LOCAL artifacts and module state are created by session requests. |
| LIVE scope | Empty in both: no session, no documents. |
| Required completeness | Every binary has a manifest and an immutable generation; every `-sources.jar` that has a binary has documentation joined; the inventory holds every path; the format generation is activated and validated; the scan recorded no fault. |
| Ready predicate | **DOMAIN_READY** is the first `REPOSITORY_RECONCILIATION_FINISHED` boot event with `reconciled=true`. It is emitted in the completion stage of `IndexService.start()`'s readiness future. That stage runs after the initial scan returned with `lastScanComplete`: every reader future joined, the inventory completed, the candidate validated and activated, removed paths reconciled, and the link pass returned. The event carries in-flight evidence. PRODUCT_READY (stdout `READY`) and SESSION_CAPABLE are milestones only. |
| Persistence contract | Below in [D](#d-persistence-and-representation-ledger). Tested: process-restart recovery from the state a SIGKILL at DOMAIN_READY + 5 s leaves. Not tested: power loss. |

### Coverage

| Family | State |
|---|---|
| Binary signature facts, artifact manifests, repository inventory, completeness/activation | **REQUIRED_AND_ESTABLISHED.** Cold and warm exports are equal family by family. Reachable generations are compared by a digest of every key and value of every record (OBSERVED). |
| Documentation members | **REQUIRED_AND_ESTABLISHED** on the fixture (OBSERVED: `Greeter#greet` and `Shape#area` doc text found after cold and after warm). **Not exercised by the corpus**, which has 0 `-sources.jar`. |
| JDK platform facts | **NOT_EXERCISED_BY_NATIVE_BOOT** |
| Code-body / reference edges (`mode=code`) | **NOT_EXERCISED_BY_NATIVE_BOOT** |
| LOCAL memos, workspace bindings, LOCAL artifacts | **NOT_EXERCISED_BY_NATIVE_BOOT** (TARGET GAP) |
| Global relationship linking | **INTENTIONALLY_OUT_OF_DOMAIN**: `resolveGlobalRelationships` is a no-op; targets stay symbolic until a workspace query. |
| ClasspathSequence, classpath/context proofs, ResidentSemanticState, MachineDependencyState, ProofDag | **NOT_EXERCISED_BY_NATIVE_BOOT.** The classpath observation caches are built lazily per workspace. ResidentSemanticState and ProofDag are reached only through `Analyzer`. `MachineDependencyState` has no caller outside its own file. The runtime views of the store's semantic work and observation caches are equal between cold and warm. |

---

## B. Headline table

The principal run is five control pairs. Each runs the product image with the AOT cache on one runner. "n/o" means not observable in that mode.

| Metric | Cold boot | Warm boot | Interpretation |
|---|---:|---:|---|
| Complete declared domain reached | 5/5 | 5/5 | Machine-signature domain only. LOCAL, JDK and code edges are not part of native boot. |
| Equal semantic state established | n/a | **5/5 `EQUIVALENT_BY_REUSE`** | Exports EQUAL, runtime views EQUAL, runtime-vs-disk EQUAL (OBSERVED) |
| Process → PRODUCT_READY | 37,417 ms (36,400–40,584) | **513 ms** (491–520) | Milestone only. Warm READY precedes reconciliation by policy. |
| Process → semantic complete | 37,405 ms | 2,743 ms | The scan returned with the inventory complete |
| Process → persistence complete | 37,405 ms | 2,743 ms | All required publication is synchronous inside the scan (see [D](#d-persistence-and-representation-ledger)) |
| **Process → DOMAIN_READY** | **37,405 ms** (36,390–40,574) | **2,743 ms** (2,732–2,746) | Headline. Includes the 2,000 ms configured initial delay in both. |
| CPU user / system | 83,990 / 1,450 ms | 1,730 / 220 ms | OS process totals at detection. Parallel CPU exceeds elapsed time. |
| Cumulative Java allocation | **35.1 GB** (35.12, 35.09) | **79 MB** (79.2, 79.2) | Full-JDK `counters` mode. `getTotalThreadAllocatedBytes` includes virtual-thread readers; it agrees with the G1-log lower bound of 33.5 GB to the last pause. n/o in the jlink image (no `jdk.management`). |
| Peak heap / RSS | 1,286 MB heap before a pause, 1,624 MB committed; **VmHWM 2,043 MB** (1,905–2,221) | 29 MB heap before its single pause, 252 MB committed; **VmHWM 207 MB** (205–209) | Heap from the G1 log (run 4, counters mode). VmHWM is the kernel high-water mark. |
| Live heap at ready (retention run) | **5.2 MB** after a forced full GC (904 MB used before it) | **5.2 MB** (44 MB used before) | Separate retention run; the procedure alters the heap. Cold RSS is committed garbage heap, not retained state. |
| Bytes read / hashed / decoded | 436 MB read from disk; 159.4 MB SHA-256 + 154.3 MB SHA-1 hashed; 72,892 class models parsed | 0 B from disk; 5.0 MB hashed (34 SNAPSHOT JARs); 462 manifests decoded; 86,560 ZIP entries enumerated | Different quantities. Page cache not dropped in either scenario. |
| Physical bytes written / final storage | 690.8 MB / 436.7 MB (SST 435.2 MB) | 16.5 MB (15.4 MB is the native library) / 436.8 MB | `write_bytes` from `/proc/pid/io` |
| Artifacts parsed / reused / republished | 462 / 0 / 462 | 0 / 462 (428 by stamp, 34 by content hash) / 34 identical manifests rewritten | [F10](#f10) |
| Semantic results restored / recomputed | 0 / 867,654 symbols, 1,159,791 edges | 462 manifests restored; 0 recomputed | |
| Identity/tree constructions and comparisons | 462 artifact resolution identities (`CanonicalDigestWriter`); 0 classpath sequences; no Merkle comparison | 0 identities recomputed (restored from manifests); 0 classpath sequences | |
| Required pending/active publications at endpoint | 0 (active artifacts 0, readers 0 active/0 queued, builds in flight 0, admission units 0, source publisher empty) | 0 | OBSERVED in the DOMAIN_READY event |
| Default-configuration failures | 0 | 0 | All 54 boots of the principal run reached DOMAIN_READY, except the three that were made to fail on purpose: two C3 corruption controls and the C4 kill |

Ratios (cold, principal run; different quantities):

| Ratio | Value | Calculation |
|---|---:|---|
| A<sub>Java</sub> / B<sub>final storage</sub> | **80** | 35.1 GB / 436.7 MB |
| B<sub>physical writes</sub> / B<sub>final storage</sub> | **1.58** | 690.8 / 436.7 MB |
| B<sub>final storage</sub> / B<sub>input JARs</sub> | **2.73** | The stored index is larger than its inputs |

---

## C. Stage tables

### Cold boot (pair 1, control mode)

Wall-clock milestones, OBSERVED:

| Interval | ms | What runs |
|---|---:|---|
| spawn → `main` | 81 | JVM start with AOT cache |
| `main` → storage open begin | 122 | `Config`, `Application` constructor, file-observation journal load (1 ms, absent) |
| storage open | 225 | Native library load + `RocksMemory` 95 ms; repository DB create 109 ms; others ≤ 9 ms each |
| **configured initial delay** | **2,002** | `jvmd.index.scan.initial_delay_seconds=2` |
| discovery | 61 | `Files.walk(m2Repo)`: 462 JARs |
| **skeletons** | **38,037** | 462 × `indexJar` on 4 virtual readers |
| docs phase | 0 | No `-sources.jar` |
| inventory complete + candidate validation + activation | 32 | `validateCandidate` reuses the in-process verification set (462 reuses, 0 passes) |
| reconcile paths, link (no-op), return | 13 | |
| **DOMAIN_READY** | **40,574** | |
| PRODUCT_READY | +10 | READY is printed after the reconciliation that cold boot joins |

The skeleton phase is 38.0 s × 4 readers = 152.1 thread-seconds. Exclusive reader-time accounting
(instrumented spans, ATTRIBUTED) closes to within 0.6 s; pair 2 closes to within 0.6 s as well:

| Reader stage | thread-s | share | Notes |
|---|---:|---:|---|
| **Admission wait** (`acquireArtifact` semaphore) | **56.4** | **37%** | 56 of 462 acquisitions waited over 1 ms. Peak 4 artifacts and 128 of 128 units in flight. |
| Publication `publishBinary` | 73.3 | 48% | Broken down below |
| ↳ record preparation (keys, encoded symbols, trigram postings) | 13.8 | | 12.1 M sort input records, 116.7 M gram occurrences, 4.46 M posting blocks |
| ↳ external-sort spill (gzip runs) | 17.3 | | **230.3 MB of spill files** written and deleted |
| ↳ merge + `SstFileWriter` (block build, Snappy) | 29.3 | | |
| ↳ staged-SST verification (`SstFileReader.verifyChecksum`) | 2.9 | | |
| ↳ ingestion (move, serialised) | 3.8 | | |
| ↳ fsync | 0.03 | | |
| ↳ manifest write (`sync=true`) + install + remainder | 6.2 | | |
| Canonical facts (`ArtifactIndexFormat.from`, class references) | 12.1 | 8% | 867,654 symbols; resolution facts and digests |
| Parse (`BinaryReader.read`) | 9.0 | 6% | 72,892 class models |
| Hash (SHA-1 check + SHA-256 identity) | 0.48 | 0.3% | 2 full reads of 428 JARs and 1 of 34 |
| Inventory observe | 0.23 | | 462 unsynced writes |
| Admission estimate (JAR open + entry enumeration) | 0.08 | | |
| **Unattributed** | **0.6** | | Span boundaries and scheduling |

Sampled CPU (async-profiler `cpu`, cold, 120,821 samples, ATTRIBUTED):

| Inclusive frame | % of samples |
|---|---:|
| `IndexService.indexJar` | 70.0 |
| `RocksArtifactRepository.writeSst` | 48.0 |
| `SstSorter.writeTo`/`merge` | 23.7 / 18.9 |
| `SstSorter.spill` | 12.4 |
| `rocksdb::BlockBasedTableBuilder::Add` | 12.4 |
| `ArtifactIndexFormat.from` | 11.0 |
| C2 compiler threads | 20.3 |

Top self frames are G1 evacuation (3.9%), the RocksDB library (3.5%), `inflate_fast` (3.2%), Snappy compression (3.0%),
and `deflate_fast`/`longest_match`/`slide_hash`, which is gzip of the sort spill (about 6%).

Sampled allocation (`alloc`, 35.5 GB sampled weight; sampled weights, not exact bytes):

| Inclusive frame | % |
|---|---:|
| `RocksArtifactRepository.writeSst` | 53.5 |
| `ArtifactIndexFormat.from` | 21.8 |
| `BinaryReader.read` | 20.5 |
| `GramPostings.add` | 16.9 |
| `ResolutionFact.canonical` | 15.9 |
| `ArtifactIndexFormat.encodeSymbol` | 14.5 |
| `CanonicalDigestWriter.digest` | 9.7 |

By type: `byte[]` 46.5%, `int[]` 12.9%.

G1 (run 4, counters mode, cold): 65 pauses (63 young, 2 mixed), 1,757 ms total pause before DOMAIN_READY.

### Warm boot (pair 1, control mode)

| Interval | ms | What runs |
|---|---:|---|
| spawn → `main` | 79 | |
| `main` → storage open begin | 107 | |
| **storage open** | **331** | Native library extraction and load + `RocksMemory` 92 ms; repository DB open 14 ms (462 SST files); inventory 7; semantic DBs 6; **metadata store 208**, of which **restore 193** (462 JSON decodes + 462 `repository.contains`) |
| SESSION_CAPABLE / PRODUCT_READY | 519 / 520 | `persisted_index_complete=true`, so READY does not wait |
| **configured initial delay** | **2,002** | |
| discovery | 59 | |
| skeletons (all fast path) | 146 | Summed across readers: estimate JAR open 156 ms, inventory observe 174 ms, SNAPSHOT re-hash 14 ms |
| inventory complete, reconcile, link, return | 21 | Activation already validated: no verification pass |
| **DOMAIN_READY** | **2,746** | |

Without the 2,000 ms delay, warm boot spends about 0.75 s: 0.52 s to READY and about 0.23 s of scan.

Sampled CPU (warm, 2,639 samples, ATTRIBUTED):

| Inclusive frame | % of samples |
|---|---:|
| JIT compiler threads | 60.4 |
| `RocksIndexStorage.<init>` | 15.2 |
| ↳ `RocksIndexStore.<init>` | 10.0 |
| ↳ ↳ `RocksArtifactRepository.contains` → `TableCache::FindTable` → `BlockBasedTable::Open` | 5.8 |
| ↳ `RocksMemory.<init>` (library load) | 4.2 |
| `indexJar` | 9.1 |

Sampled allocation (warm, 55 MB weight): **`RocksArtifactAdmission.acquireArtifact` is 49.9%** (ZIP central-directory
parsing, `JarFile$JarFileEntry`), on artifacts that are then never parsed ([F5](#f5)).

### Scaling (counters mode, full JDK, one pair per size; run 3)

| Subset | JARs | Input | Cold DOMAIN_READY | Cold alloc | Cold CPU user | Admission wait | Final storage | Warm DOMAIN_READY | Warm restore | Warm alloc |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 25% | 115 | 38.4 MB | 16.3 s | 8.48 GB | 47.8 s | 9.3 s | 105.5 MB | 2.92 s | 127 ms | 45.6 MB |
| 50% | 230 | 79.1 MB | 26.9 s | 17.43 GB | 80.1 s | 30.5 s | 220.7 MB | 2.95 s | 169 ms | 56.8 MB |
| 100% | 462 | 159.4 MB | 45.6 s | 35.19 GB | 113.9 s | 67.7 s | 435.2 MB | 3.20 s | 275 ms | 78.2 MB |

- **Cold allocation is proportional to input bytes:** 221 B per input byte at all three sizes. Storage is 2.75× input.
- **Admission waiting grows faster than input:** 9.3 → 30.5 → 67.7 s. So cold elapsed time grows faster than CPU.
- **Warm cost grows slowly with artifact count:** manifest restore, JAR enumeration and inventory observation. The fixed 2 s delay dominates.

These are three sizes; a linear fit does not establish asymptotic complexity. Source inspection found no nested
per-artifact × per-artifact work on either path.

### Bounded causal experiments (SUPPLEMENTARY, counters mode, one runner, run 3)

Each changes one default. They do not replace the default results above.

| Run | DOMAIN_READY | PRODUCT_READY | CPU user | VmHWM | Admission wait (thread-s) |
|---|---:|---:|---:|---:|---:|
| Cold, default (128 MiB budget), two runs | 50.5 s / 48.8 s | | 124.8 / 125.3 s | 2,218 / 2,125 MB | 72.5 / 72.7 |
| Cold, `generation_budget_mb=512` | **37.7 s (−24%)** | | 129.7 s | **2,590 MB (+19%)** | 0.0 |
| Cold, `generation_budget_mb=32` | 61.3 s (+23%) | | 104.9 s | 1,708 MB | 163.0 |
| Warm, default | 3.29 s | 0.97 s | | | |
| Warm, `initial_delay_seconds=0` | **1.35 s** | 1.02 s | | | |
| Warm, `awaitRepositoryScan=true` (existing option) | 3.36 s | **3.38 s** | | | |

**Admission waiting is on the cold critical path** (ATTRIBUTED and causally confirmed): removing it saves about 12 s
of 49.6 s, at the cost of about 19% more peak RSS.

The default warm DOMAIN_READY is about 1.9 s longer than with no scan delay. READY moves by only +0.05 s when the scan starts immediately.

---

## D. Persistence and representation ledger

### Stores at the endpoint

Paths are under `index-v2/generations/format-1-jdk25-jvmd-index-v9/` unless stated.

| Store | Producer (boot) | Logical contents | Physical | Identity | Warm reader | Validation at warm boot | Publication / durability | Missing or corrupt (tested) |
|---|---|---|---|---|---|---|---|---|
| `db/` artifact repository | `publishBinary` → `RocksArtifactRepository.publish` | Immutable per-artifact signature generations: 12.1 M records | **462 SST files**, 435.2 MB, one per ingestion; `max_open_files=128` | Content-addressed cacheKey: sha256 of binary sha, format, indexer, JDK feature, mode | Read on demand by queries. At boot, only `contains(cacheKey)` per manifest. | Builder hashes every record into the manifest; staged SST checksum-verified before ingest. At warm boot: manifest-key presence only. RocksDB open checks that the MANIFEST's files exist and have the right sizes. | SST fsync'd, then ingested by move. Durable at return (INFERRED from RocksDB ingestion semantics; fsync measured at 26 ms total). | **Deleted or truncated SST: RocksDB open fails and the daemon exits with code 1. No READY; no false state; no self-repair.** (OBSERVED) |
| `store/` metadata | `publishArtifactData`, `publishPath` | `A|id` → `StoredArtifact` JSON (path, key, docsKey, codeKey, resolution identity, counts); `U|`, `next-*` | WAL + memtable at SIGKILL; 1 L0 SST after warm-open recovery | Numeric handles (not semantic) | `RocksIndexStore.<init>` decodes **all** manifests into a `TreeMap` | `repository.contains` for each manifest | **`WriteOptions.setSync(true)`**: durable per write | **Deleted: daemon READY at 0.40 s with `persisted_index_complete=true` and no artifacts.** The scan rebuilt it 2.1 s later. Final state equal ([F3](#f3)). |
| `inventory/` | `RocksArtifactInventory.observe` / `completeScan` | `P|path` → gav, kind, cacheKey, sha, stamp (size, mtime, ctime, fileKey), scan generation; `R|cacheKey` refcounts | WAL (unsynced) | Scan generation numbers are history (excluded from comparison) | Read by `completeScan`/`validateCandidate` only, not by reuse decisions | n/a | **Default `WriteOptions`: WAL without sync.** Process-restart recoverable (OBSERVED via SIGKILL); not power-loss durable (not tested). | Not exercised |
| `semantic-state/`, `workspace-state/` | Session requests only | | Empty in both | | | | | Not exercised |
| `active.manifest`, `VALIDATED` | `completeScan` → `markValidated`, `activate` | Completeness of the format generation | Text files | Generation name | `scanCompleted()` at open | Presence only | fsync + directory fsync + atomic rename | **`VALIDATED` removed: `persisted_index_complete=false`, READY waits for the scan (2.47 s ≥ reconciliation 2.46 s), 0 re-parse, final state equal.** (OBSERVED) |
| `file-observations-v1.bin` | Not written by native boot | | Absent after boot (OBSERVED) | | Loaded at construction (absent) | | Flushed by thresholds or `close()` | Not exercised |
| `local-memo-v1/`, `apt/` | Not written by native boot | | Absent (OBSERVED) | | | | | TARGET GAP |
| `/tmp/librocksdbjni<random>.so` (outside the state directory) | `RocksDB.loadLibrary` on every start | Native library | **15,377,032 bytes written per process start** (OBSERVED) | | | | | |

### Shutdown versus ready

A cold daemon was observed through its orderly shutdown (OBSERVED):

- **Timing:** `daemon.shutdown` took 332 ms to process exit.
- **Files touched:** compared with the listing at DOMAIN_READY, shutdown changed only Rocks `LOG`, `MANIFEST-*` and two WAL files (+1,808 bytes). It added no SST and changed no semantic record.
- **Warm from the graceful state:** equal to the cold-ready state, with the same work counters.

**No information required for warm reuse is published only by `close()`.** The primary pairs start warm from the
**cold-ready recoverable state** (SIGKILL after DOMAIN_READY + 5 s). The graceful variant is secondary
(`REQUIRES_SHUTDOWN_PUBLICATION` is not needed).

Copy noninterference (OBSERVED):

- **Snapshot copy:** after SIGKILL, the state directory lists the same files, sizes and mtimes as at DOMAIN_READY + 5 s
  (`exit_vs_ready`: 0 added, 0 removed, 0 changed). The snapshot is a `cp -a` of that stopped directory.
- **Exporter:** on a validation copy, the read-only exporter left the tree hash unchanged (`42b6f16e…` before and
  after), and a repeated export was identical. The warm boot never starts from an exported copy.

### Representation lifetimes (cold, per artifact)

```text
JAR bytes (page cache)
  → read ×3: SHA-1 check, SHA-256 identity, BinaryReader inflation
  → BinaryReader.Content (class models, symbols, edges)
  → ArtifactData (SymbolRecord + ResolutionFact, canonical JSON metadata, relationships) + class-reference set
  → SstSorter entries: key/value byte[] (≤ 3 MiB buffer); trigram GramPostings blocks (≤ 1 MiB)
  → gzip spill runs (230 MB per corpus) → k-way merge (fan-in 32) → posting blocks
  → SstFileWriter blocks (Snappy) → staged .sst → fsync → full checksum read-back (≈ every SST byte; read_bytes 436 MB, INFERRED) → ingest (move)
  → StoredArtifact JSON → WriteBatch(sync) → in-memory TreeMap
```

- **Materialisation:** each artifact is materialised whole as `Content` and as `ArtifactData`. The sort stage is streamed but bounded per build.
- **Buffers held at once:** up to four builds hold these buffers concurrently. That is what the admission budget is meant to bound.
- **Retention:** the retained result of all of this is 462 `StoredArtifact` records, 33 KB of live heap.

On warm boot the representation is the persisted `StoredArtifact` JSON → `StoredArtifact` → `TreeMap` once.
Repository records are not decoded at boot. They are read only when a query needs them.

---

## E. Validation-ownership findings

Ranked by demonstrated impact. Classes:
- correctness/readiness;
- resource/forward-progress;
- repeated computation;
- missing persistence;
- representation churn;
- necessary cost;
- unresolved.

<a id="f1"></a>**F1 — Admission estimate serialises cold construction (resource/forward-progress).** Cold, subject `0f7c825`.

- **Route:** `IndexService.indexJar:177` → `RocksArtifactAdmission.acquireArtifact`. The estimate is 8 MiB + Σ(uncompressed `.class` size × 12 + 1 KiB), capped at the 128-unit budget. A fair semaphore holds the permit across hash, parse, facts and publication.
- **Work:** 462 acquisitions, 6,855 estimated units in total. 56–60 acquisitions waited over 1 ms, for 56.4 s of a 152 s reader budget (37%), with all 4 readers occupied. The budget also bounds RSS: 32 MiB gives VmHWM 1.7 GB; 512 MiB gives 2.6 GB.
- **Causal test:** a 512 MiB budget removes the wait and cuts cold DOMAIN_READY by about 24%; 32 MiB adds 23%.
- **Uncertainty resolved:** none. This is a resource policy, not a proof obligation. The factor 12 is an unvalidated heuristic. Peak heap before GC is 1.29 GB and committed heap 1.62 GB, so the budget does not bound real memory tightly in either direction.
- **PROPOSED:** calibrate the estimate against measured per-artifact peaks. Sort buffers are already bounded to 4 MiB per build, so the estimate need not charge 12× of class bytes to the sort. Possibly separate parse memory from publication memory.
- **Soundness:** no semantic effect. Regression: the existing admission and memory tests, plus a bounded-RSS assertion on the fixture.
- **Expected benefit:** bounded above by the 12 s measured with the wait removed. A partial calibration gains less.

<a id="f2"></a>**F2 — Per-artifact publication builds the index through a gzip external sort (representation churn / necessary cost).** Cold.

- **Route:** `writeSst` → `SstSorter`, with a 4 MiB buffer of which 1 MiB is for grams.
- **Work:** 12.1 M input records and 9.7 M run records. 230 MB of gzip spill written and read back, for 435 MB of final SST. Thread time: preparation 13.8 s, spill 17.3 s, merge + SST 29.3 s.
- **CPU and allocation:** about 48% of cold CPU samples and about 54% of sampled allocation are in `writeSst`. gzip spill (deflate) is about 6% of CPU self time.
- **Physical writes:** 690.8 MB for 436.7 MB of final storage. Spill accounts for 230 MB, the native library for 15 MB, and Rocks logs, WAL and manifests for most of the rest.
- **Necessary or not:** sorting is necessary to build SSTs. Spilling is a consequence of the per-build budget.
- **PROPOSED (needs measurement):** size the sort buffer from the admitted estimate instead of the fixed 4 MiB, and spill uncompressed or with a cheaper codec. Proof required: identical SST content digests, which the exporter already checks. No result-identity change.

<a id="f3"></a>**F3 — Persisted completeness is not bound to the manifests it certifies (correctness/readiness).** Warm, after damage.

- **Route:** `Application.initializeIndex:1097` sets `persistedIndexComplete = storage.scanCompleted()`. `RocksIndexStorage.scanCompleted()` checks `active.manifest == candidate` and that the `VALIDATED` file exists. It does not check the `store/` database whose manifests `validateCandidate` verified.
- **Observed:** with `store/` deleted, RocksDB recreated it empty. The daemon reported `persisted_index_complete=true` and printed READY at 0.40 s with 0 artifacts. Reconciliation started 2 s later, re-parsed both JARs and restored an equal state at 2.53 s.
- **Mitigation and residual risk:** index-tier answers in that window carry the `index_reconciling` warning (`Application` decorator). But a session-capable daemon reporting a complete persisted index has none, which violates "a persisted completion bit must not make a partial domain look complete".
- **PROPOSED:** bind activation to the metadata it validated. One option is to persist the manifest count or digest (or the store's sequence number) with `VALIDATED` and compare it at open. On mismatch, treat the index as incomplete, which runs the existing scan-gated path.
- **Regression:** the c3 metadata-store-deleted control must report `persisted_index_complete=false` and gate READY.

<a id="f4"></a>**F4 — Unreferenced immutable generations are never deleted (missing reclamation; storage growth).** Warm after an input change.

- **Route:** `RocksArtifactInventory.completeScan` returns the cache keys whose refcount reached 0. `RocksIndexStorage.completeScan` passes them on. `IndexService.scan:137` discards the return value. Nothing deletes from `db/`.
- **Observed (c2):** after one offline JAR change, the warm state equals a clean cold build of the changed inputs on every reachable family. It retains one extra **unreferenced** generation, the old content, which the comparator reports. The inventory refcount for it is gone.
- **Consequence:** storage grows with every content change, and SNAPSHOT rebuilds are the common case. This is a correctness-neutral leak; readers cannot reach it.
- **PROPOSED:** delete unreferenced generations after activation, by key range. Proof required: no manifest or alias references the key, and the deletion is ordered after the referencing manifests are durable.
- **Regression:** c2 must show `repository.unreferenced_generations` empty.

<a id="f5"></a>**F5 — Warm boot opens and enumerates every JAR to estimate memory for work it will not do (redundant re-derivation).** Warm.

- **Route:** `indexJar:177` acquires admission, which opens the `JarFile` and enumerates all entries, *before* the stamp fast path at `:180` decides that nothing will be parsed. The same happens for `indexSources:254`.
- **Work:** N = 462 enumerations and 86,560 entries per warm boot; U = 0 artifacts needed the estimate (none parsed). R = N/U is undefined: every enumeration is redundant.
- **Cost:** 136–212 ms thread-summed on 4 readers; **about 50% of warm sampled allocation**.
- **PROPOSED:** decide the fast path (store lookup and stat) first, and acquire admission only when hashing, parsing or publication follows. Admission bounds only parse and publication memory, which the fast path never allocates.
- **Proof:** unchanged counters and equal exports in the pairs; the estimate is still taken before every parse.
- **Expected benefit:** at most about 50 ms of warm elapsed time. Small.

<a id="f6"></a>**F6 — Warm restore validates every manifest by opening its SST (repeated physical validation).** Warm.

- **Route:** `RocksIndexStore.<init>:61` decodes each `A|` record (Jackson) and calls `repository.contains(cacheKey)`. That is a point lookup on the manifest key, which forces `TableCache::FindTable` → `BlockBasedTable::Open` for each of the 462 distinct SST files. The table cache holds 128.
- **Cost:** `restore.metadata_store` 183–193 ms in the control pairs (260 ms in the full-JDK counters mode); that is about 36% of the 0.51–0.52 s to READY. It scales with artifacts (127 → 169 → 275 ms across 25/50/100%).
- **Uncertainty resolved:** does the generation a manifest names exist? RocksDB open already fails when any MANIFEST-listed SST is missing or truncated (c3, OBSERVED). Repository keys are never deleted at this subject (F4). So the per-manifest probe detects only a logically absent key in a present database.
- **PROPOSED (needs a decision):** keep the check but make it cheap and owned once. For example, validate presence through the inventory/activation evidence written in the same scan, or probe lazily on first use as a safe miss. Must not be removed without that argument.
- **Expected benefit:** up to about 0.2 s of the time to READY. Not on DOMAIN_READY's critical path beyond that.

<a id="f7"></a>**F7 — Configured initial scan delay dominates warm DOMAIN_READY (scheduling).**

- **Cost:** 2,000 ms of 2,743 ms (73%), and present on cold as well.
- **Experiment:** with a 0 s delay, warm DOMAIN_READY is 1.35 s and READY moves by +0.05 s.
- **Status:** product policy, unchanged here, and reported separately as the brief requires. A policy review rather than a repair.

<a id="f8"></a>**F8 — The RocksDB JNI library is extracted on every process start (repeated work outside the state directory).** Both scenarios.

- **Observed:** a 15,377,032-byte `/tmp/librocksdbjni<random>.so` is written per start, which is 93% of warm `write_bytes`. Library load plus `RocksMemory` takes 92–131 ms.
- **PROPOSED:** ship the extracted library in the jlink image and load it through `java.library.path`. INFERRED: `NativeLibraryLoader` tries the library path before extracting. Proof required: same library hash; no extraction in `/proc/pid/maps`.

<a id="f9"></a>**F9 — Three full passes over every JAR on cold (repeated computation, necessary in kind).**

- **Passes:** SHA-1 against `.sha1` (154.3 MB), SHA-256 identity (159.4 MB) and `BinaryReader` inflation.
- **Cost:** hashing is 0.48 thread-s, 0.3% of reader time. Not worth a repair now.
- **INFERRED, not exercised by the corpus:** `indexSources` re-parses the binary JAR it was paired with (`IndexService:272`). With sources present, every binary would be parsed twice.

<a id="f10"></a>**F10 — SNAPSHOT JARs: re-hashed every warm boot (necessary) and identical manifests rewritten (redundant).**

- **Re-hash:** 34 SNAPSHOT JARs, 5.0 MB, 12–15 ms per warm boot. The fast path excludes SNAPSHOT by policy, so this is required freshness evidence.
- **Rewrite:** `publishPath` then writes an identical `StoredArtifact` with `sync=true` and reinstalls it (`store.metadata_installs` = 34).
- **PROPOSED:** skip the write when the record is equal.

**Checks that do avoid repeated work (OBSERVED):**

- **Candidate activation:** reuses the publisher's in-process verification set. Cold: 462 reuses, 0 extra passes.
- **Warm validation:** the persisted `VALIDATED` marker avoids any re-verification at warm boot (0 passes).
- **Resolution identities:** artifact resolution identities are persisted in the manifest and not recomputed warm.
- **Observation refreshes:** `refreshSemanticObservations` runs once per install (462 cold, 34 warm) over empty caches.
- **Not reached at native boot:** Merkle roots, algebraic aggregates, classpath sequences and LOCAL memo certificates. None is present in name but preceded by reconstruction on this path.

**Not found on the native boot path:** the historical per-call `reactorRoot()` inference and the package, static-key and drain multiplications. `AttributedMemos` is not reached at native boot, so they are outside this domain.

### Validation-ownership ledger (material costs)

| Value / claim | Producer → owner | Validity / invalidator | Consumer route | Calls (N) | Distinct states needing it (U) | Cost | Class |
|---|---|---|---|---:|---:|---|---|
| Artifact memory estimate | `acquireArtifact` → admission semaphore | per JAR content | every `indexJar`/`indexSources` | 462 | cold 462 / warm 0 | cold 56 s wait; warm 0.14–0.21 thread-s and ~50% of warm allocation | cold: resource waiting (F1); warm: redundant (F5) |
| Generation exists for manifest | `publish` → repository | key immutable; file loss caught by RocksDB open | `RocksIndexStore.<init>` | 462 per warm boot | 1 database open | 0.18–0.26 s | partly redundant physical validation (F6) |
| JAR unchanged | stat (size, mtime) vs manifest | external file change | `indexJar` fast path | 462 | 462 | small | required external observation |
| JAR stamp evidence recorded | `Stamp.read` (2 stats) → inventory `P|` | scan generation | `observe` | 462 unsynced writes per boot | 462 | 0.17–0.23 thread-s | necessary under the current stale-detection design |
| SNAPSHOT content | SHA-1 + SHA-256 | file content | `indexJar` | 34 per warm boot | 34 | 12–15 ms | required (policy) |
| SNAPSHOT manifest rewrite | `publishPath` | none: identical | `indexJar` | 34 | 0 | 34 sync writes | redundant (F10) |
| Publication verified | `verifyStagedSst` → `verifiedPublications` | process | `validateCandidate` | 462 | 462 | 2.9 thread-s cold; 0 warm | necessary; ownership reused correctly |
| Completeness | `VALIDATED` + active manifest | not bound to `store/` | `scanCompleted()` | 1 | 1 | — | correctness gap (F3) |
| Native library present | extraction to `/tmp` | per process | `RocksDB.loadLibrary` | 1 per start | 0 after the first install | 15.4 MB, about 0.1 s | redundant (F8) |

---

## F. Recommended next task

Smallest independent repairs, by demonstrated impact over risk:

1. **F3: bind activation completeness to the metadata it validated.** Small, correctness-relevant. The regression is the existing c3 control. Do this first.
2. **F1: recalibrate the admission estimate.** Up to about 24% of cold boot is measured. Keep the budget semantics, and validate peak RSS on the corpus at the default heap ceiling. Report the time/RSS trade-off rather than only raising the default.
3. **F4: delete unreferenced generations after activation.** Storage correctness under churn; the c2 control is the regression.
4. **F5 + F10: move admission after the fast-path decision, and skip identical manifest rewrites.** Small and local. The pairs' exports and counters prove there is no semantic change.
5. **F8: ship the extracted RocksDB library in the image.** Removes 15 MB of writes and about 0.1 s per start.
6. **F2 and F6: measure before changing.** Sort-buffer sizing or the spill codec; one owned generation-presence check.

**Keep unchanged:**
- immutable content-addressed generations;
- staged-SST verification before ingestion;
- synced manifest writes;
- the `VALIDATED`/active-manifest activation protocol (extended, not replaced);
- the stamp fast path;
- SNAPSHOT re-hashing;
- READY gating when persisted state is incomplete;
- the initial-delay *policy*, until product owners decide.

**Separately scoped (not this task):** native-boot restoration of LOCAL state, JDK platform facts and code edges. That needs its own domain definition. Any study of boot plus workspace preparation must be named as such.

---

## G. Answers to the brief's questions

1. **At true cold completion:**
   - one immutable signature generation per binary JAR (462; 867,654 symbols; 1,159,791 relationships);
   - 462 artifact manifests;
   - a complete inventory;
   - an activated and validated format generation;
   - an in-memory `TreeMap` of manifests, 5.2 MB of live heap in total.

   No LOCAL, JDK, code-edge, classpath-sequence or resident view exists.
2. **All of it is persisted at that moment.** Manifests and activation are synced; generations are fsync'd and ingested; the inventory is in an unsynced WAL. Shutdown adds nothing semantic.
3. **Warm recovers** all 462 manifests and the activation. It recomputes nothing semantic. It re-acquires stamp evidence for all 462 JARs and content evidence for the 34 SNAPSHOT JARs, and rewrites 34 identical manifests.
4. **Equivalent** for the declared machine-signature domain: `EQUIVALENT_BY_REUSE` in 5 of 5 pairs, on disk and in the runtime views.
5. **Not part of native boot:** LOCAL memos and workspace bindings (TARGET GAP), JDK facts, code-body edges, documentation when no `-sources.jar` exists, and classpath/proof structures.
6. **Cold:**
   - *wall time:* admission waiting (37% of reader time) and SST publication (48%);
   - *CPU:* `writeSst` (48%) and JIT (20%);
   - *allocation:* `writeSst` (54%), facts (22%) and parsing (21%);
   - *memory peak:* committed G1 heap (1.6 GB) from 35 GB of churn; live state is 5 MB;
   - *storage traffic:* 435 MB SST plus 230 MB spill.

   **Warm:**
   - *wall time:* the 2 s configured delay (73%), then storage open and manifest restore;
   - *CPU:* JIT (60%);
   - *allocation:* JAR enumeration for admission (50%);
   - *storage traffic:* native-library extraction (15.4 MB of 16.5 MB).
7. **New evidence:**
   - stat of every JAR;
   - content hashes of SNAPSHOT JARs;
   - RocksDB open checks.

   **Repeated:**
   - the per-manifest generation probe (F6);
   - JAR enumeration on the fast path (F5);
   - identical manifest rewrites (F10);
   - library extraction (F8).
8. **Avoiding work:** content-addressed reuse (stamp fast path), the persisted `VALIDATED` marker, and reuse of the in-process verification set at activation. Merkle comparisons, algebraic deltas and structural sharing are not reached at native boot.
9. **Present in name but preceded by reconstruction:** none on this path. The resident and Merkle structures are simply not built at native boot.
10. **Smallest safe next repair:** F3. Evidence: c3 metadata-store-deleted must report `persisted_index_complete=false` and gate READY. c1 and the control pairs must stay `EQUIVALENT_BY_REUSE` with unchanged warm counters.

---

## Correctness controls (OBSERVED, run 3)

| Control | Result |
|---|---|
| C1 no change (fixture: binary JAR, `-sources.jar`, `-javadoc.jar`, a second JAR) | `EQUIVALENT_BY_REUSE`. Expected facts hold after cold and after warm: 7 Greeter symbols; `greet` and `area` documentation text; `-javadoc.jar` excluded. |
| C2 one offline input change | Warm equals a clean cold build of the changed inputs (reachable state and runtime views EQUAL). It differs from the original cold in the changed artifact; no stale data accepted. 1 JAR parsed, 1 reused. One unreferenced generation retained (F4). |
| C3 repository SST deleted / truncated | RocksDB open fails and the daemon exits with code 1: safe failure, no READY. The daemon does not repair itself; the index directory must be removed. |
| C3 `VALIDATED` removed | `persisted_index_complete=false`; READY gated on the scan; 0 re-parse; final state EQUAL |
| C3 metadata store deleted | **READY at 0.40 s with `persisted_index_complete=true` and no manifests** (F3). Final state EQUAL after the scan. |
| C4 incomplete cold publication (SIGKILL 2 s after discovery) | Partial store reported `complete=false` (3 manifests). Restart: `persisted_index_complete=false`, READY gated (45.86 s ≥ 45.85 s), 3 reused, 459 parsed, final state EQUAL to a reference cold. |
| C5 comparator sensitivity | 13 unit cases detect: a changed fact with equal counts, a missing result, reordered classpath slots, PARTIAL vs COMPLETE, a missing reachable generation; an unreferenced generation is reported but not equated |
| C6 observer noninterference | Tree hash unchanged by export; repeated export identical; warm boots never start from an exported copy |

Existing focused tests (racy timestamps, unsupported observation providers, logical identity, UNKNOWN) were not re-run
beyond the `jvmd-core`, `jvmd-index` and `jvmd-index-rocks` module tests in the build job. Power-loss durability is unverified.

## Measurement notes and limits

- **Clocks:** both the JVM's `System.nanoTime` and Node's `process.hrtime` read CLOCK_MONOTONIC, so spawn and in-process events are subtracted directly. MAIN_ENTERED at 79–84 ms after spawn confirms the correlation. `/proc` values are read when the harness detects the event: median 12.6 ms after it, range 1.0–25.6 ms over 54 boots.
- **Allocation counter:** available only in the full-JDK `counters` mode (the jlink image omits `jdk.management`). Those runs use no AOT cache and are slower: cold 46.6–50.5 s, warm 3.2–3.3 s. Their times are not mixed with the control pairs.
- **Run-to-run variation:** cold times differ between runners, 36–51 s across scenarios. Only same-runner comparisons are used for effects, and the five pairs ran on one runner. Five pairs do not support p95/p99.
- **Page cache:** not dropped in either scenario. The warm store is page-cache resident after the stopped-process copy, so warm reads 0 bytes from disk. A warm boot with a cold OS cache was not measured.
- **AOT cache:** in the product-mode boots of the verification run, the daemon's own `aot_cache` status is `used` and `aot.log` shows "Opened AOT cache …/jvmd.aot" (OBSERVED). The full-JDK counters mode reports "no cache configured", as intended. A plain `IMAGE/bin/java -version` without `-XX:AOTCache` (the build job's provenance step) logs "Loading static archive failed" for the image's default CDS archive. That run does not involve the jvmd cache, and it was not investigated further.
- **Provenance field:** `production_diff_vs_subject` in the build provenance of runs up to `33ed106` is empty because its pathspec did not match. The production diff against the subject is the gated instrumentation listed in [§9](#9-reproduction-and-closeout). The workflow now uses a glob pathspec.
- **Wall-clock profile:** dominated by parked platform threads. Virtual-thread readers that block on the admission semaphore unmount, so their waiting is measured by the instrumented spans, not by wall samples.

## 9. Reproduction and closeout

```text
# CI (what produced every number here): push to the investigation branch, or dispatch
.github/workflows/daemon-boot-profile.yml   scenarios: pairs,controls,shutdown,scaling,experiments,counters,cpu,wall,alloc,native,retention

# Local, after `mvn -B -q -DskipTests install && bash jvmd-dist/assemble.sh && bash jvmd-dist/train-aot.sh`
node benchmarks/boot/boot.ts pairs    --mode control --pairs 5 --image jvmd-dist/target/image --java-home $JAVA_HOME \
     --repository <prepared m2 repository> --output <dir> [--asprof <async-profiler-4.3>]
node benchmarks/boot/boot.ts profile  --mode cpu|wall|alloc|native|retention|counters [--repeat N] ...
node benchmarks/boot/boot.ts controls|shutdown|experiments ...
node benchmarks/boot/boot.ts scaling  --fractions 25,50,100 --mode counters ...
python3 benchmarks/boot/reduce.py <dir>/<scenario>        # per-scenario reduction.json / reduction.md
python3 benchmarks/boot/aggregate.py <dir>                # cross-scenario aggregate.json
java -cp "IMAGE/lib/jvmd/*" benchmarks/boot/StoreExport.java <stopped state copy> out.json [--full]
python3 benchmarks/boot/compare.py a.json b.json out.json [--runtime-a A/milestones.jsonl --runtime-b B/milestones.jsonl]
python3 benchmarks/boot/test_compare.py
```

**Production-source changes on this branch.** All of them are observation only, off by default:

- `BootEvents` (new, `jvmd-core`);
- milestone marks in `Application`, `UnixServer` and `IndexService`;
- counters and timers in `RocksArtifactAdmission`, `RocksArtifactRepository`, `RocksIndexStorage` and `RocksIndexStore`;
- in-memory diagnostic views read only with `-Djvmd.profile.boot_dump=true`.

When `-Djvmd.profile.boot_events` is absent:

- every mark is behind `if(BootEvents.ENABLED)`, which is a static final `false`;
- counters return immediately;
- `nanos()` returns 0.

No cache, storage, invalidation, readiness or validation behaviour is changed. They are kept, opt-in, so that later
repairs can be measured against the same counters.

## Checklist

- [x] 0 Subject and domain frozen: `0f7c825`; BootDomainSpec; corpus inventory; no scope substitution
- [x] 1 Actual startup traced: cold and warm routes ([C](#c-stage-tables), [D](#d-persistence-and-representation-ledger)); TARGET GAP and readiness gap (F3) identified
- [x] 2 Measurement validated: ready observer with in-flight evidence; publication provenance (SIGKILL-at-ready copy, listing equality); comparator sensitivity; observer noninterference
- [x] 3 Control pairs measured: 5 default pairs, all values reported, 0 failures
- [x] 4 Profiles collected: CPU, wall, allocation (×2 each); NMT, smaps; retention (heap info, histogram, threads); counters/GC (×2 + run 4)
- [x] 5 Equivalence assessed: family-by-family exports, runtime views, runtime-vs-disk; recomputation disclosed
- [x] 6 Repeated work attributed: F1–F10 with N/U, routes and classes
- [x] 7 Controls and scaling complete: C1–C6; 25/50/100% series; bounded experiments
- [x] 8 Report and reproduction verified: reductions consumed from CI artifacts; commands above are the ones CI executes
- [x] 9 Assessment-only closeout: no production fix; instrumentation gated; profiling revision `ac56c71` recorded

## Checkpoint log

Append-only.

- **2026-10-02:** Run 37002554861, harness shakedown. Every daemon exited in about 25 ms because `-Xlog:aot` pointed into a not-yet-created state directory. Fixed by `mkdir -p`, as the launcher does.
- **2026-10-02:** Run 37002789362, first complete matrix. Found:
  - the `daemon.status` envelope was unwrapped incorrectly, so status timings were missing;
  - the G1 regex missed most pauses;
  - the c2 difference was an orphaned generation.

  The comparator now separates reachable from unreferenced generations.
- **2026-10-02:** Run 37004267057 at `ac56c71`: **principal matrix** (all numbers above unless stated).
- **2026-10-02:** Run 37005215565 at `fee553e` (same instrumentation): G1 pause log; native-library mapping and size.
- **2026-10-02:** `33ed106`: all mark arguments gated; compact evidence summary replaces the full CI aggregates in the tree.
- **2026-10-02:** Run 37006026335 at `33ed106`: full verification matrix on another runner, all jobs green.
  - **Control pairs:** cold DOMAIN_READY median 45.97 s (45.54–46.37 s); warm 2.926 s (2.904–2.969 s); warm READY 0.636 s. The cold runs are slower on this runner, as noted above.
  - **Equivalence:** all 19 pairs `EQUIVALENT_BY_REUSE`.
  - **Controls:** c2 warm equals a clean cold; c4 equal and gated.
  - **F3 reproduced:** READY at 0.389 s with `persisted_index_complete=true`.
  - **Admission budget:** 512 MiB gives 33.97 s against default runs of 50.40/40.81 s; 32 MiB gives 53.96 s.
  - **Scan delay:** warm with delay 0 is 1.07 s against 3.21 s by default.
  - **AOT cache:** confirmed used.

  Its full aggregate is in branch history (`f72770d`); `summary.json` keeps its key values under `verification_run`.
