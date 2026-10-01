# JVMD memory profiling

The tooling behind [docs/jvmd-memory-allocation-profile.md](../../docs/jvmd-memory-allocation-profile.md).
It observes JVMD and changes nothing: the daemon runs with the benchmark's command line (same JDK,
heap, allocation agent and config), plus only the flags of one profiling mode.

| File | |
|---|---|
| `profile.ts` | Drives one daemon through the lifecycle on the pinned apache/maven fixture, with checkpoints M0–M26 and the benchmark's oracles. One profiling mode per run: `control`, `exact`, `alloc`, `live`, `retention`, `nmt`, `native`, `rss`. Also `--seed-only` (machine index only), `--seed-restart`, `--reuse-state DIR` (reopen a persisted state), `--skip-references`, `--native-budget-mb`, `--heap`, `--jfr-events`. |
| `probes.ts` | Daemon launch with the allocation probe connected from process start, `/proc` and smaps readers, the 50 ms sampler. |
| `analyze.py` | Reduces one run: lifecycle checkpoints, allocation or native profiles per phase (mechanism, operation, owner, thread, class, stack), histograms, NMT, smaps split, GC log, JFR stages. |
| `mat.sh`, `mat_drill.sh` | Eclipse MAT batch analysis of a heap dump: dominators, owner retained sizes, duplicate strings, leak suspects, and one level below the large owners. |
| `synthesize.py` | Builds the report's tables into `results/summary.json` and `results/allocation-top50.json`. |
| `tables.py` | Renders the report's main tables (decimal MB/GB) from `results/summary.json`. |
| `Humongous.java` | Streams `jdk.ObjectAllocationOutsideTLAB` events above a size threshold out of a JFR file, one JSON line each. |
| `subset_repository.py` | Hard-linked subsets of a Maven repository for the scale and per-JAR experiments. |

Two observational hooks support it:

- **The benchmark agent** (`../harness/agent/AllocationAgent.java`) answers `s`/`p` with a JSON
  memory snapshot. `p` also resets pool peaks.
- **The daemon** exposes the first scan's progress in `daemon.status` when started with
  `-Djvmd.profile.bootstrap_status=true`. This is off by default.

The full matrix runs in CI from **Actions → JVMD memory profile**, which uploads the raw evidence as
artifacts. Commands for each profile are in the report's *Reproduction* section.

## Corrective-pass control (PR #55)

`profile.ts --mode control` is also the corrective pass's frozen references/restart control. It is run from
`jvmd-benchmarks.yml` (dispatch with `memory_control=equivalent|shipping` and `revision=<sha>`). `--heap 0`
launches without `-Xmx` (shipping defaults). The run prints `checkpoint:`, `operation:`, `M1` and `M2` lines
to the job log. See `docs/persistence-architecture-status.md`, *Corrective pass*.
