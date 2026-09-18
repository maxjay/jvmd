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
