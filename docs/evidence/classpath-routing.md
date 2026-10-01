## Classpath routing (Phase 13)

| Metric | real classpath (26 jars) | synthetic (300 jars) |
|---|---:|---:|
| types | 8782 | 122041 |
| flat fold rebuild | 2.4 ms | 30.7 ms |
| filter build | 10.2 ms | 21.1 ms |
| filter bytes | 0.01 MB | 0.15 MB |
| routing map entries | 8782 | 121987 |
| routing map heap (est.) | 0.80 MB | 11.17 MB |
| lookup: routing map | 0.2 / 0.5 | 0.5 / 0.9 |
| lookup: sequential search | 1.0 / 2.2 | 13.7 / 31.0 |
| lookup: per-artifact filters | 1.9 / 3.1 | 8.6 / 15.7 |
| canonical checks / lookup (filters) | 1.04 | 2.21 |
| lookup: filters + session memo | 2.0 / 3.5 | 8.7 / 16.0 |
| session memo entries | 4006 | 4925 |
| flat fold after one-slot edit | 3.5 ms | 30.7 ms |

Latencies are p50 / p95 in µs.

- filters use 10 bits/name with no false negatives; a filter is trusted only when bound to its slot
- a persisted hot routing map would replace one flat fold per restart/classpath edit; compare its rebuild cost above with the materialisation criterion (§28)
