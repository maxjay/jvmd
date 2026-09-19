# Seed allocation and merge follow-up — 2026-09-19

The large-JAR seed now takes **9.366 s versus 10.253 s**, an **8.7% reduction**
in service open plus scan time. Every one of the five paired repetitions is faster,
with non-overlapping observed ranges: 8.795–9.517 s optimized versus
9.715–10.803 s baseline. This is a generated-fixture result, not a statistical
confidence interval or a corporate-repository acceptance claim.

For 128 smaller JARs, 3.581 s versus 3.628 s is effectively unchanged: ranges
and paired results overlap, including slower optimized repetitions. This change
helps the single artifact's allocation and external-merge bottleneck; the
multi-JAR fixture never spills its sort buffers.

## Implementation and provenance

The preceding [query optimization](2026-09-19-query-optimization.md) is present
on **both** sides. This experiment measures additional seed work, not the earlier
1700 ms-to-9 ms type-query improvement.

- Baseline: GitHub `aecbf0de468b2621f082ff4fa2f02f676a61906c`, local
  `42b9ec204ae962a3f7fbec21394d64fb0e0aa9ce`, tree
  `05b08954778dfdfabf679a98d4793b722e01758b`.
- Optimized: GitHub `0c8b5a50922e9279b1cc98750a29f0daab490f8b`, local
  `4e642f5e23686c4de1a24e37f8966e7ff02ca8d7`, tree
  `fb4fff2547d7a0b54e728f396de18b4496555efd`.
- Five alternating repetitions per implementation and fixture, fresh index and
  persisted restart: **40 isolated JVMD processes**, each with a 1 GiB heap.
- Temurin 25.0.4.1+1, AMD EPYC 9V74, eight allocated CPUs, 15,032,385,536-byte
  container memory cap, volatile overlayfs, no application AOT or OS-cache flush.
- Fixtures: one JAR with 950 classes/380,000 symbols and 128 JARs with
  1,024 classes/379,904 symbols. Exact JAR, dependency, harness and compiled-class
  hashes accompany the raw reports. The harness now also hashes `SstSorter`
  and `GramPostings` and accepts an explicit repetition count.

The implementation changes are:

1. Bind the sorter to one validated artifact namespace. Construct relative UTF-8
   keys once; prepend the namespace only when writing the final SST. Previously,
   every input key constructed the full hash prefix before the sorter copied it
   away. Documentation generations use the same path.
2. Reuse each symbol's hexadecimal ID, the temporary gram-set tables and the
   checksum length buffer. The checksum retains the same big-endian length framing.
3. Reuse a posting block's original key for its final ID, including singleton
   blocks. Continue consuming the current merge run while it precedes every other
   run, avoiding repeated priority-queue removal and insertion for that range.

Memory budgets, admission limits, sorting order, posting validation and the
persisted format remain unchanged. Packed ranges still expand when interleaved;
ordering and duplicate checks remain in force. The changes remove repeated work
rather than skipping indexed facts or validation.

## Timing and memory

Medians below are from five independent workers per cell. Fresh seed means
service open plus scan; process readiness additionally includes JVM launch,
workspace registration and the first complete type query.

| Measurement | Previous Rocks | Optimized Rocks |
| --- | ---: | ---: |
| One JAR: fresh seed | 10.253 s | 9.366 s |
| One JAR: fresh process to searchable types | 10.400 s | 9.495 s |
| One JAR: persisted process to searchable types | 0.603 s | 0.601 s |
| 128 JARs: fresh seed | 3.628 s | 3.581 s |
| 128 JARs: fresh process to searchable types | 3.845 s | 3.822 s |
| 128 JARs: persisted process to searchable types | 0.775 s | 0.849 s |
| One JAR: fresh peak RSS | 855.5 MB | 873.3 MB |
| 128 JARs: fresh peak RSS | 688.1 MB | 646.3 MB |

The 128-JAR fresh-seed ranges are 3.283–3.847 s baseline and 3.288–3.808 s
optimized. No material multi-JAR seed or restart improvement is established.
Single-JAR peak RSS increases despite lower allocation churn; these measurements
do not support a blanket lower-memory claim. MB uses decimal bytes.

| Type-query measurement | Previous Rocks | Optimized Rocks |
| --- | ---: | ---: |
| One JAR: broad warm query after fresh build | 8.965 ms | 9.246 ms |
| One JAR: broad warm query after restart | 9.105 ms | 9.949 ms |
| 128 JARs: broad warm query after fresh build | 26.307 ms | 20.003 ms |
| 128 JARs: broad warm query after restart | 25.625 ms | 26.241 ms |
| One JAR: exact query p95 after fresh build | 0.445 ms | 0.451 ms |
| One JAR: exact query p95 after restart | 0.546 ms | 0.577 ms |
| 128 JARs: exact query p95 after fresh build | 17.954 ms | 18.214 ms |
| 128 JARs: exact query p95 after restart | 23.698 ms | 22.477 ms |

These are short fixed warm-up batches: 31 exact type queries, the existing field
query batch, then three broad queries whose complete returned rows must equal the
first broad response. Each broad timing is the median of five worker medians;
each exact p95 is the median of five per-worker p95s. They are not long-running
throughput or statistically established tail-latency measurements. Query planning
is unchanged in this follow-up; query results are mixed.

## Where the large-JAR time went

| Instrumented phase | Previous Rocks | Optimized Rocks |
| --- | ---: | ---: |
| Record preparation | 2.717 s | 2.468 s |
| Initial sort and spill | 1.667 s | 1.478 s |
| Merge and SST construction | 3.502 s | 3.108 s |

These are medians of individual phase counters, not a partition that must sum to
the median wall time. The 128-JAR workers build multiple artifacts concurrently,
so their summed phase counters must not be read as elapsed time.

Both large-JAR implementations process 2,765,194 sort input records and
4,085,898 temporary-run records. Spill bytes are essentially identical at 33.025 MB.
Accounted peak sort/gram buffer bytes stay at 4,194,047 under the unchanged
4,194,304-byte budget. This optimization reduces allocation and queue work;
it does not eliminate the external sort or reduce the indexed corpus.

One fresh-seed JFR per implementation, run separately from timed workers, estimates
**6.194 GB versus 4.904 GB allocated**, approximately **21% less allocation churn**.
It records 48 versus 38 garbage collections. These are sampled allocation estimates,
not retained memory, and profiled elapsed times are excluded from the latency table.
The aggregate reduction is consistent with removing repeated key/copy allocations
and gram-set tables; sampling does not isolate a causal percentage for each edit.

The main remaining costs are still record preparation and merge/SST construction.
The optimized allocation profile still attributes about 646 MB to gram creation,
478 MB to temporary-run byte reads and 287 MB to posting decoding. Some allocation
stacks are truncated or outside JVMD. Further gram representation and merge-buffer
changes should be measured separately. Intra-artifact parallel construction remains
an experiment to evaluate after those costs; simply adding artifact-reader threads
will not accelerate a fixture containing one JAR.

## Correctness and compatibility

All **75 selected tests pass**: 47 storage and 28 integration tests. Existing tests
exercise overlapping posting blocks across multiple merge passes, 64 KiB budgets,
Unicode and owner-boundary grams, corrupt/truncated postings, SQLite agreement,
pagination, aliases, publication failures and reopen behavior. New sorter tests
independently compute the original checksum framing, compare every full SST row
under spilling and non-spilling budgets, and reject duplicates across separate runs
while cleaning up temporary files.

All 40 timed workers report zero faults and matching type identity hashes. All
20 normal reopen workers rebuild zero artifacts. Four additional compatibility
workers open each implementation's complete persisted fixture using the other
implementation; both directions and both fixtures return matching identities and
pass the normal type/field query checks with zero rebuilds and zero faults.

The PR remains ready for review. CI status and the final JDTLS comparison follow
below. Existing corporate-repository, durable WSL, total-memory and broader
concurrency/failure-injection acceptance work remains separate.

## Final JDTLS comparison

JDTLS 1.61.0 was rerun serially on byte-identical fixture JARs with three fresh and
three restart processes per fixture, 12 workers total. The distribution SHA-256
is `338e7e73d61836651ba2453919a0d34fa763eb4e7c03342092309bffb8934c64`.
JVMD cells below retain the five-run medians from the controlled before/after
experiment; JDTLS cells are three-run medians.

| Process launch to complete type results | Optimized JVMD | JDTLS |
| --- | ---: | ---: |
| One JAR, fresh index | 9.495 s | 7.925 s |
| One JAR, persisted restart | 0.601 s | 3.835 s |
| 128 JARs, fresh index | 3.822 s | 7.988 s |
| 128 JARs, persisted restart | 0.849 s | 3.628 s |

| Broad warm type query | Optimized JVMD API | JDTLS LSP |
| --- | ---: | ---: |
| One JAR, after fresh build | 9.246 ms | 222.090 ms |
| One JAR, after restart | 9.949 ms | 216.341 ms |
| 128 JARs, after fresh build | 20.003 ms | 124.446 ms |
| 128 JARs, after restart | 26.241 ms | 119.065 ms |

JDTLS still wins fresh single-JAR readiness. JVMD benefits clearly from reopening
its persisted index in this test. **Both systems index upfront**, including binary
members; the pinned-source audit and scope correction remain in the
[query report](2026-09-19-query-optimization.md#corrected-jdtls-interpretation).
Both readiness clocks include JVM launch, but JVMD calls its direct index API
while JDTLS includes LSP, Eclipse/project/JDK startup and different response
representations. These ratios do not isolate index-engine speed or establish a
general language-server ranking. JDTLS warm queries follow exact-type queries;
JVMD also runs its field-query batch before those warm queries.

## CI and reproduction

Measured code `0c8b5a50` passed all four native packaging/AOT/relocation jobs and
the Windows installer in [Distributions 35446737297](https://github.com/maxjay/jvmd/actions/runs/35446737297).
The full checkpoint job also passed; its serialized corpus job remains pending
in [Checkpoints 35446737295](https://github.com/maxjay/jvmd/actions/runs/35446737295).
The final evidence-only commit triggers independent CI. No gate was weakened.

```sh
git worktree add --detach /tmp/jvmd-seed-baseline aecbf0de468b2621f082ff4fa2f02f676a61906c
# Run from a checkout containing optimized production commit 0c8b5a50.
python benchmarks/index-updates/query-performance.py \
  --baseline /tmp/jvmd-seed-baseline --current "$PWD" \
  --java-home /path/to/jdk-25.0.4.1+1 \
  --dependencies /path/to/release/lib/jvmd \
  --root /tmp/jvmd-seed-comparison --runs 5
python benchmarks/index-updates/profile-seed.py \
  --benchmark-root /tmp/jvmd-seed-comparison \
  --java-home /path/to/jdk-25.0.4.1+1 \
  --root /tmp/jvmd-seed-profile
python benchmarks/index-updates/jdtls.py \
  --jdtls /path/to/jdtls --archive /path/to/jdtls.tar.gz \
  --java-home /path/to/jdk-25.0.4.1+1 \
  --repository /tmp/jvmd-seed-comparison/single-380000/pristine \
  --artifacts 1 --classes 950 --runs 3 --warm-type-queries 3 \
  --root /tmp/jvmd-jdtls-seed-single
```

Repeat JDTLS with `m2-128/pristine`, `--artifacts 128 --classes 8` and a new output
root. Run workers serially. See the [harness README](../../benchmarks/index-updates/README.md)
for dependency resolution and scope details.

Raw evidence:

- [Five-repetition before/after matrix](2026-09-19-seed-optimization.json)
- [Diagnostic allocation/CPU profiles and commands](2026-09-19-seed-allocation-profile.json)
- [Bidirectional persisted-index compatibility](2026-09-19-seed-compatibility.json)
- [JDTLS one-JAR run](2026-09-19-jdtls-seed-single-380000.json)
- [JDTLS 128-JAR run](2026-09-19-jdtls-seed-m2-128.json)
- [Validation and CI snapshot](2026-09-19-seed-validation.json)

The [actual-main/Maven-update/source-Merkle experiment](2026-09-19-final-comparison.md)
remains a separate earlier measurement; main and source Merkle were not rerun in
this seed follow-up. Maven artifact reuse and source Merkle updates remain distinct
mechanisms. Do not combine old and new runs into a newly measured main speedup.
