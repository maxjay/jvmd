## W8 MACHINE re-decision

Corpus: `/tmp/claude-0/w8/repository`, 483 jars with symbols, 1004202 symbols. Each column is a fresh JVM (`-Xmx2g`).

| Metric | RocksDB | Native + accelerators | Native / RocksDB |
|---|---:|---:|---:|
| build_ms | 109712.3 ms | 88444.1 ms | 0.81 |
| heap_allocation_bytes | 37513.7 MB | 40631.8 MB | 1.08 |
| peak_heap_bytes | 1049.1 MB | 936.1 MB | 0.89 |
| peak_rss_bytes | 1251.7 MB | 1150.6 MB | 0.92 |
| peak_native_malloc_bytes | 197.3 MB | 188.2 MB | 0.95 |
| disk_bytes | 483.7 MB | 248.8 MB | 0.51 |
| restart_first_query_ms | 195.9 ms | 17.8 ms | 0.09 |
| exact_p50_us | 48.8 µs | 568.1 µs | 11.65 |
| exact_p95_us | 276.9 µs | 939.0 µs | 3.39 |
| prefix_p50_us | 77.8 µs | 285.8 µs | 3.67 |
| prefix_p95_us | 438.5 µs | 598.5 µs | 1.36 |
| substring_p50_us | 711.3 µs | 2935.7 µs | 4.13 |
| substring_p95_us | 3987.7 µs | 41789.3 µs | 10.48 |

Pre-registered rule (adopt native only if all hold):

- FAIL peak RSS during seed <= 0.7 x RocksDB
- FAIL total heap allocation during seed <= 0.7 x RocksDB
- FAIL exact lookup p95 <= 1.5 x RocksDB
- PASS disk bytes <= 1.25 x RocksDB
- PASS restart to first query <= 100 ms

Decision: **keep RocksDB**
