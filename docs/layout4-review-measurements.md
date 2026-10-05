# LAYOUT 4 controlled Stage 1 comparison

This addresses PR 59 review item 6. The base is `617dbfd8` (LAYOUT 3, immediately before Appendix A); head is this review-fix PR including the original Appendix A changes. Each runs the identical `Stage1Measurement` harness against the same `C:/Users/Max/.m2/repository` and Temurin 25.0.4.1+1-LTS image. Both digests use four workers, B=32, CAP=128 and `-Xmx6g`, in separate Maven test JVMs at base and head. The store is in memory; these are computation/node-production measurements, not disk throughput.

The inventory is **Digest of sorted (zstr location || exact byte hash)**, including the 70 JDK module locations, and a separate digest of `lib/modules` is recorded. Each run verifies the inventory and JDK image again after boot. Base and head match exactly for each digest: 445 jars + 70 modules = 515 locations, 103,443 class parser calls, zero faults. No count or commutative sum substitutes for this identity.

| Digest | Revision | Wall ms | Leaves | Nodes produced | Unique nodes written | Node bytes |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| SHA-256 | base | 3077 | 493 | 124006 | 97,610 | 539,417,153 |
| SHA-256 | head | 2998 | 491 | 128054 | 100,019 | 537,492,682 |
| SHA3-256 | base | 3651 | 493 | 123687 | 97,372 | 539,332,342 |
| SHA3-256 | head | 3730 | 491 | 127864 | 99,850 | 537,502,824 |

The unique node count grows by 2.47% / 2.54%; node bytes fall by 0.36% / 0.34% for SHA-256 / SHA3-256. Head writes 334 unique AL records as well (two full Roots each, 138 bytes per record); these are records, not ContentTree nodes. Leaves fall from 493 to 491 because the resolution-only identity shares APIs previously separated by tail bytes. Wall times are single samples with JVM/GC and file-cache effects, not a speed claim. SHA-256 runs before SHA3-256 inside each JVM. Sampled heap is used heap without forced GC, not retained memory; baseline and peak are in the raw reports.

## Nodes by tree

`NodeSink.named` labels each tree at construction; `Written` forwards a `nodeBuilt` observation **before** global hash deduplication. The observer counts every produced node and the unique hashes/bytes for that tree, rather than guessing tree ownership from shared `N|` storage. Production stores inherit a no-op observer; no bytes, identities or persistence behavior depend on it. Nodes shared across tree kinds can appear in more than one per-tree unique count, so these columns need not add to the globally unique total. The callback and labels are identical at base and head; the base-only patch is retained below.

| Tree | SHA-256 base unique | SHA-256 head unique | SHA3-256 base unique | SHA3-256 head unique |
| --- | ---: | ---: | ---: | ---: |
| T | 26,431 | 25,778 | 26,324 | 25,733 |
| N | 28,892 | 28,754 | 28,826 | 28,747 |
| E | 38,825 | 36,418 | 38,740 | 36,321 |
| O | 3,460 | 3,495 | 3,480 | 3,458 |
| A | — | 3,135 | — | 3,147 |
| EA | — | 2,439 | — | 2,444 |
| MACHINE | 5 | 5 | 5 | 5 |

## Exact evidence and reproduction

- [Base SHA-256](measurements/layout4-base-SHA-256.txt), [head SHA-256](measurements/layout4-head-SHA-256.txt).
- [Base SHA3-256](measurements/layout4-base-SHA3-256.txt), [head SHA3-256](measurements/layout4-head-SHA3-256.txt).
- [Base instrumentation patch](measurements/layout4-base-instrumentation.patch). Apply it with `git apply --unidiff-zero` to a clean checkout of `617dbfd8`, then copy `jvmd-tests/src/test/java/dev/jvmd/tests/boot/Stage1Measurement.java` from this PR. It changes no projection or codec at base.

Run sequentially at base and head, using the same JDK, repository and workers; set `jvmd.measure.revision` to identify the revision:

```powershell
mvn -B -pl jvmd-tests -am test '-Dtest=Stage1Measurement' '-DexcludedGroups=none' '-Dsurefire.failIfNoSpecifiedTests=false' '-Djvmd.measure.repository=C:\Users\Max\.m2\repository' '-Djvmd.measure.workers=4' '-Djvmd.measure.revision=<revision>' '-DargLine=-Xmx6g'
```

Each run writes `jvmd-tests/target/stage1-measurement-{digest}.txt`. Compare the full inventory/JDK identities before comparing costs. The baseline patch and harness are measurement tooling, not a retained alternative implementation of resolution identities.
