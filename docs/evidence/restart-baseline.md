## Restart attribution (Phase 10)

> Not headline evidence: this is the first pass's 160-unit linear chain, the topology the strict task rules out as
> headline evidence. See [restart-scenarios.md](restart-scenarios.md), [warm-restart-scaling.md](warm-restart-scaling.md)
> and [real-project.md](real-project.md).

| Metric | cold (empty state) | warm (racy window) | warm restart | relocated checkout |
|---|---:|---:|---:|---:|
| persisted observation load | 1.3 ms | 2.1 ms | 2.0 ms | 1.3 ms |
| observations restored | 0 | 163 | 163 | 163 |
| first correct diagnostics (one unit) | 219.5 ms | 71.7 ms | 32.1 ms | 41.0 ms |
| all units diagnostics | 8382.6 ms | 1338.0 ms | 1211.1 ms | 1281.6 ms |
| wall time (process start → all units) | 9121.7 ms | 1435.6 ms | 1263.9 ms | 1356.1 ms |
| background memo write drain | 2.2 ms | 0.0 ms | 0.0 ms | 0.0 ms |
| javac queries | 160 | 0 | 0 | 0 |
| attributed memo restores | 0 | 160 | 160 | 160 |
| attributed memo writes | 160 | 0 | 0 | 0 |
| memo validation failures | 0 | 0 | 0 | 0 |
| memo bytes read / persisted | 0.00 MB / 0.39 MB | 0.39 MB / 0.00 MB | 0.39 MB / 0.00 MB | 0.39 MB / 0.00 MB |
| files hashed | 163 | 160 | 0 | 160 |
| bytes hashed | 148.20 MB | 0.13 MB | 0.00 MB | 0.13 MB |
| file stat checks | 44776 | 56705 | 56545 | 56705 |
| restart hash reuse | 0 | 3 | 163 | 3 |
| racy rejections | 0 | 160 | 0 | 0 |
| directory enumerations | 18 | 18 | 18 | 18 |

Latencies are p50 / p95 in µs.

- 160 units in 8 packages, a dependency chain between consecutive units, 4 units with type errors
- warm (racy window) re-observes files whose first observation fell inside the 2 s timestamp window; the next restart reuses them
- relocated checkout reuses LOCAL memos by logical identity; file observations are path-keyed, so its files are hashed once
