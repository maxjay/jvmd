# Index Storage Measurement Branch

This branch is intentionally based on `main` and contains measurement-only changes. It is not the implementation branch.

## Purpose

Produce an uncontaminated SQLite control baseline and compare candidate bulk-storage designs before the production redesign selects a backend.

## Measurements

1. Existing SQLite repository indexing:
   - three fresh runs on the same generated repository;
   - unchanged restart;
   - one-artifact replacement;
   - discovery/hash/parse/storage/link/query timings;
   - writer queue vs transaction execution;
   - DB/WAL/SHM bytes.

2. Storage prototypes on identical sorted facts:
   - RocksDB external SST ingestion;
   - minimal immutable sorted segment;
   - publication time;
   - process-attributed write bytes where Linux exposes them;
   - final storage bytes;
   - exact and prefix/reverse lookup p50/p95;
   - RSS delta.

## Guardrails

- No production backend switch happens on this branch.
- CI-scale synthetic measurements select what deserves implementation work; they do not satisfy the final 861-JAR acceptance gate.
- The production implementation continues independently on `indexing/immutable-artifacts`.


## First CI result — 2026-09-18

Environment: Ubuntu 24.04 runner, Linux 6.17 Azure kernel, Temurin 25.0.4.1+1, 4 processors.

SQLite control:
- three fresh 12-artifact runs completed;
- fresh-run 2/3 wall time: 119.9 ms / 105.3 ms after JVM warmup;
- fresh storage: 245,760 bytes final DB after close;
- unchanged restart: 23.3 ms wall, 0 indexed, 12 reused, 0 hashes;
- one-artifact update: 36.5 ms wall, 1 indexed, 10 reused, 2 hashes.

Candidate comparison on 100,000 symbols + 200,000 unique relationships = 800,000 sorted facts:
- immutable segment publish: 367.6 / 320.3 / 170.7 ms; storage 30,444,682 bytes; writes ~30,445,568 bytes/run;
- RocksDB external SST publish: 461.9 / 327.3 / 318.8 ms; storage ~5,220,000 bytes; steady-state writes ~10,436,608 bytes;
- immutable median query p50/p95: ~0.117 / 0.376 ms;
- RocksDB median query p50/p95: ~0.430 / 0.746 ms;
- RocksDB steady-state RSS delta was materially lower in this CI sample.

Decision: **select RocksDB external-SST ingestion for the production implementation.**

Rationale: publication throughput is effectively comparable at this scale, while RocksDB uses about 83% less final storage and about 66% fewer steady-state process-attributed write bytes. Query latency remains sub-millisecond in the prototype. The immutable segment remains a fallback/reference implementation.

This is a candidate-selection measurement, not the final performance acceptance gate. The real 861-JAR repository run and production query-equivalence checks remain required.
