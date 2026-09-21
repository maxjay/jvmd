## JVMD semantic state: base versus candidate

JVMD-only component experiments; these are not LSP timings or a JDTLS comparison. 128 files / 31 workspace requests; 256 files / 3,072 source symbols. Medians of three process medians; publication and disk are single samples. Workspace facts are supplied without javac. Allocation is Java thread allocation, not retained heap or native memory. After / before below 1 is lower; cold-start and storage regressions are included.

Base: `563ecc473fe9c033ece775686645972baca8d41a` · candidate: `20ad41cd25c55a96aaa2e3733012e2290f2bc63b`

| Metric | Unit | Base JVMD | Candidate JVMD | After / before | Repetitions |
|---|---|---:|---:|---:|---:|
| Zero-cache workspace: cold lookup | ms | 258.187 | 413.652 | 1.602 | 3 |
| Zero-cache workspace: warm lookup | ms | 5.407 | 0.870 | 0.161 | 3 |
| Zero-cache workspace: file loads / 31 requests | count | 3968.000 | 128.000 | 0.032 | 3 |
| 16 MiB workspace: cold lookup | ms | 286.822 | 428.571 | 1.494 | 3 |
| 16 MiB workspace: warm lookup | ms | 0.746 | 0.816 | 1.093 | 3 |
| 16 MiB workspace: file loads / 31 requests | count | 128.000 | 128.000 | 1.000 | 3 |
| Source cold identity | ms | 69.195 | 40.137 | 0.580 | 3 |
| Source cold Java allocation | B | 6693984.000 | 1944944.000 | 0.291 | 3 |
| Source warm identity | ms | 0.104 | 0.089 | 0.854 | 3 |
| Source warm handle | ms | 0.136 | 0.081 | 0.593 | 3 |
| Source warm prefix | ms | 3.478 | 0.185 | 0.053 | 3 |
| Source warm exact | ms | 3.603 | 0.320 | 0.089 | 3 |
| Source publication | ms | 176.995 | 486.920 | 2.751 | 1 |
| Index disk allocation | KiB | 1136.000 | 2944.000 | 2.592 | 1 |
