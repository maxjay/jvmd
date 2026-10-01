## Strict task A11: warm-restart scaling

Regenerate: `mvn -pl jvmd-tests test -Dtest=WarmRestartScalingBenchmark -DexcludedGroups=corpus` (run at `513639c`).
Random-DAG fixture; a no-change warm restart diagnoses every unit.

```
A11 units=1000 javac=0 restores=1000 stats=2032 (bound 2032) enumerations=0 bytesHashed=0 wall=5929.6 ms
A11 units=5000 javac=0 restores=5000 stats=10032 (bound 10032) enumerations=0 bytesHashed=0 wall=29163.8 ms
A11 wall ratio 5000/1000 = 4.92
```

The stat bound is `2 x files + directories`; both sizes meet it exactly, with zero directory enumerations and zero bytes
hashed. The 5,000 / 1,000 wall-time ratio is 4.92 (limit 6).
