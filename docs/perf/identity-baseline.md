# Identity cost baseline (SHA-256, before the XXH3 migration)

Measured at `c8fcb9f` plus test-only harness code, for [identity-xxh3-migration.md](../identity-xxh3-migration.md).
The after-migration run is in [identity-after.md](identity-after.md).

## How it was measured

The 462-JAR memory-report harness is not in this repository, so this uses an in-repo harness:
`jvmd-tests/src/test/java/dev/jvmd/tests/IdentityAllocationHarness.java` (tagged `corpus`, so it
does not run in a normal build).

```sh
mvn -B -DskipTests install
mvn -pl jvmd-tests verify -DexcludedGroups= -Dgroups=corpus -Dtest=IdentityAllocationHarness \
    -Dsurefire.failIfNoSpecifiedTests=false -Djvmd.harness.repository=<dir of jars>
```

- **Corpus**: the 211 non-source jars in this build's local Maven repository (Maven, Jackson,
  RocksDB, JUnit, AssertJ, Lombok, MapStruct and their dependencies), copied to a fixed directory
  so both runs see the same files.
- **Phases**: MACHINE seed (index every jar, load one workspace), first-use and warm machine queries
  (member ranges, member-range and hierarchy identities for 328 owners), a synthetic resident
  workspace (1500 source units × 41 facts: admit, query, then 300 unit edits), and the live source
  tree (20k files, then 2k edits).
- **Attribution**: JFR `jdk.ObjectAllocationSample` (20000/s) and `jdk.ExecutionSample` (1 ms).
  Sample weights are scaled to the phase's exact allocated bytes
  (`getTotalThreadAllocatedBytes`, all threads). A sample's category is decided by the first
  identity-machinery frame from the allocation site outwards:
  - *Digest*: `CanonicalDigestWriter`, `Hash256`, `LiveStateTree.digest/write/fingerprint`,
    `CompilerInputs.compose/write` (after the migration: `IdentityEncoder`, `Xxh3`, `Id128`).
  - *Accumulator*: `AlgebraicAccumulator`, `Hash256.unsignedInteger` and the BigInteger treap
    priorities (`LiveStateTree.priority/contribution`, `ClasspathSequence.item`,
    `ResidentSemanticState.point`).
  - *Content hash*: `Hashing` (kept SHA-256, shown for reference only).
- JDK 25.0.3 (Ubuntu build), default G1, 1 run. Small phases (a few hundred CPU samples or fewer)
  are noisy.

## Corpus phases

| Phase | Wall ms | Allocated MB | Digest MB | Accumulator MB | Content-hash MB | Digest+acc share | Identity CPU share |
|---|---:|---:|---:|---:|---:|---:|---:|
| seed (index + workspace) | 33973 | 15130.2 | 1454.3 | 0.0 | 9.3 | 9.6% | 9.1% (14696 samples) |
| first-use references | 1071 | 388.8 | 0.1 | 0.0 | 0.0 | 0.0% | 3.8% (373 samples) |
| warm request (x5) | 1518 | 1013.5 | 0.0 | 0.0 | 0.2 | 0.0% | 0.0% (416 samples) |
| resident seed (60k facts) | 6946 | 6141.9 | 815.9 | 615.6 | 0.0 | 23.3% | 86.8% (5124 samples) |
| resident first-use queries | 17 | 12.9 | 0.0 | 12.9 | 0.0 | 100.0% | 42.9% (7 samples) |
| resident warm edits (300 units) + queries | 1475 | 2100.0 | 2017.4 | 71.7 | 0.0 | 99.5% | 88.1% (1080 samples) |
| live tree seed (20k files) | 1267 | 1194.2 | 35.2 | 1155.4 | 0.0 | 99.7% | 85.9% (764 samples) |
| live tree warm edits (2k) | 82 | 90.1 | 90.1 | 0.0 | 0.0 | 100.0% | 82.5% (63 samples) |

Digest + accumulator across all phases: **6268.6 MB** (4413.0 digest, 1855.6 accumulator) of 26071.6 MB allocated.

Top identity allocation callers per phase:

- seed (index + workspace): SemanticType.identity 864.5 MB; ResolutionFact.&lt;init&gt; 390.1 MB; ArtifactIndexFormat.resolutionIdentity 178.9 MB; ArtifactIndexFormat.writeResolution 17.8 MB
- first-use references: IndexStore.semanticMemberRangeIdentity 0.1 MB; ArtifactIndexFormat.readResolution 0.0 MB
- resident seed (60k facts): ResidentSemanticState$Node.&lt;init&gt; 1132.5 MB; SemanticType.identity 94.5 MB; ResidentSemanticState.contribution 76.9 MB; ResidentSemanticState$Aggregate.add 50.4 MB
- resident first-use queries: ResidentSemanticState.resolutionRange 12.9 MB
- resident warm edits (300 units) + queries: ResidentSemanticState$Node.&lt;init&gt; 2059.4 MB; ResidentSemanticState.contribution 9.1 MB; SemanticType.identity 6.3 MB; ResidentSemanticState$Aggregate.subtract 3.7 MB
- live tree seed (20k files): LiveStateTree$Aggregate.replace 1160.3 MB; LiveStateTree.digest 12.1 MB; LiveStateTree$Aggregate.identity 7.9 MB; LiveStateTree.write 7.0 MB
- live tree warm edits (2k): LiveStateTree.write 89.9 MB

The machine-layer query phases barely touch identities: MACHINE reads stored resolution identities
instead of recomputing them. Identity cost there is paid while seeding.

## Per-operation cost (single thread, after warm-up)

| Operation | bytes/op | ns/op |
|---|---:|---:|
| SemanticType.identity() (4-deep generic) | 5856 | 3238 |
| ResolutionFact construction (identity) | 15448 | 9260 |
| AlgebraicAccumulator contribution + plus | 1600 | 556 |
| 5-part digest (strings, long, identity, list) | 992 | 676 |
