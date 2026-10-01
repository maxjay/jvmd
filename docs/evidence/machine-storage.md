## MACHINE storage comparison (Phase 11–12)

| Metric | RocksDB | Minimal native | Native + accelerators |
|---|---:|---:|---:|
| build | 11,350 symbols/s | 26,463 symbols/s | 21,729 symbols/s |
| build heap allocation | 2260.84 MB | 1934.10 MB | 2201.13 MB |
| disk bytes | 54.43 MB | 111.34 MB | 118.71 MB |
| bytes/symbol | 570.5 | 1167.0 | 1244.2 |
| open time | 12.9 ms | 70.6 ms (CRC verified) | 67.2 ms (CRC verified) |
| restart → first query | 13.4 ms | 70.9 ms | 67.3 ms |
| exact lookup cold | 12.9 / 25.6 | 19.8 / 43.5 | 18.2 / 62.2 |
| exact lookup hot | 11.0 / 15.9 | 17.6 / 44.7 | 17.2 / 40.3 |
| member prefix (limit 50) | 16.5 / 132.9 | 4.2 / 7.1 | 3.8 / 6.2 |
| range identity (full range) | 20.7 / 156.3 | 6.6 / 68.7 | 5.1 / 47.1 |
| substring search | 2423.1 / 24544.6 | 500.8 / 2659.9 | 62.3 / 702.1 |
| relationship traversal (incoming) | 31.0 / 169.3 | 1363.6 / 3636.5 | 23.1 / 339.3 |
| query heap allocation | 483.08 MB | 1780.34 MB | 110.39 MB |
| RSS (process) | 1234.86 MB | 1455.94 MB | 1463.83 MB |
| major / minor faults (queries) | 0 / 4665 | 0 / 682 | 0 / 20 |
| mapped bytes | n/a (block cache) | 111.34 MB | 118.71 MB |
| open time (checksums verified at publication) |  | 9.3 ms | 8.8 ms |

Latencies are p50 / p95 in µs.

- corpus: 26 jars on the test classpath, 100048 symbols, 156973 relationships
- page cache drop for cold reads: available
- membership filter rejected 206 of 207 absent exact lookups without touching canonical data
- large owners (≥512 members) recorded for checkpoints: 1
- native section bytes: strings=102.27 MB, symbols=3.05 MB, cold=1.53 MB, string_offsets=1.48 MB, edges=1.20 MB, edge_offsets=0.38 MB, owner_offsets=0.38 MB, name_order=0.38 MB, member_order=0.37 MB, owner_aggregates=0.29 MB, identity=0.00 MB
- accelerator bytes (filters + trigram postings + reverse CSR + checkpoints): 7.37 MB
- 64 KiB block compression (deflate, fastest) of native segments: 111.34 MB → 11.67 MB (122.3 B/symbol)
- owner member counts: p50=7 p95=35 p99=98 max=628 (owners=8420, ≥512 members: 1)
- exact lookup, binary search (baseline, 0 B/key): 2.8 / 4.6 µs
- exact lookup, first-char fanout (0.27 B/key): 2.9 / 5.7 µs
- exact lookup, Eytzinger layout (4.00 B/key): 3.0 / 5.9 µs
- exact lookup, in-heap hash table as MPH proxy (~48 B/key on heap): 0.9 / 1.6 µs
