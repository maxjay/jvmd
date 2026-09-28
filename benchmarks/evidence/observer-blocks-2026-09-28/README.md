# Observer experiments: all declared blocks retained

[Run 36365023630](https://github.com/maxjay/jvmd/actions/runs/36365023630),
source `934ca279970ba2e46e5cbf12ff0e6bbedb1cb7b2`, completed both predeclared
ten-block experiments. Each block pairs instrumentation off/on in alternating
order over LIFE-01, LIFE-09 (128 edits), and LIFE-10. All **40 process runs**
passed their semantic and raw-artifact audits, with complete measured cgroup
lifetime totals. The whole workflow remains failed because its separate native
LIFE-08 and protobuf cases failed and final parent-cgroup removal returned EBUSY.
The cleanup cause is unresolved; it does not become a successful cleanup claim.

Values below are on/off geometric mean ratios with 95% paired bootstrap
intervals over ten independent blocks, seed 271828, 10,000 resamples. They are
endpoint descriptions without simultaneous family-wide coverage. The acceptance
tolerance was declared as 0.95–1.05 for the entire interval before collection.

| Endpoint | Tracing on/off (95% interval) | Extra status polling on/off (95% interval) |
|---|---|---|
| LIFE-01 elapsed | 1.167 (1.154–1.180) | 1.005 (0.998–1.012) |
| Launch to first correct result | 1.168 (1.154–1.180) | 1.005 (0.998–1.012) |
| LIFE-09 editing elapsed | 1.146 (1.120–1.174) | 1.128 (1.113–1.143) |
| LIFE-10 idle elapsed | 1.004 (1.002–1.006) | 1.001 (0.999–1.004) |
| Whole-lifetime CPU | 1.460 (1.425–1.492) | 1.069 (1.057–1.080) |
| Kernel memory-charge peak | 1.260 (1.170–1.350) | 0.999 (0.926–1.080) |

**Neither instrumentation configuration meets the tolerance across its measured
endpoints.** Use tracing for causal diagnosis and the clean profile for speed
comparisons. Extra status polling is observably active during the editing trace.
The status experiment leaves essential state-witness calls in both configurations;
it measures the additional per-edit polling. Neither experiment measures the
overhead of the common process sampler/cgroup collector. Kernel memory charge is
not RSS, and these observer ratios do not compare JVMD with JDTLS.

`reports.tar.xz` contains the exact JSON reports extracted from completed job
logs, plus both reports from the earlier rejected capture (missing operation
journal, run 36363483278). No failed capture is repaired or replaced. Provenance,
the extraction procedure, archive hashes and the GitHub full-artifact identity are
in `manifest.json`. The full raw ZIP exceeds the local 32 MiB download limit;
its GitHub digest is recorded but has not been independently verified locally.
This is a report review subset, not a substitute for full raw artifact replay.

Run `python3 benchmarks/evidence/observer-blocks-2026-09-28/replay.py` from the
repository root to verify the saved subset and independently reproduce all twelve
statistical estimates from their preserved block observations. This verifies the
statistical reduction only; replaying the original raw server transcripts requires
the full `observer-experiments` artifact and `experiment.py reduce`.

## Subsequent validation collection

Run 36366608337 at PR head `896b688` repeated the same two committed plans during
later product validation. Its exact small `observer-reports-896b.zip` is retained
separately; the ZIP digest and CRC were verified and all twelve estimates were
independently recomputed. All 40 runs validate. Tracing launch-to-first was 1.078
(1.052–1.096), tracing CPU 1.380 (1.344–1.416), and extra-polling editing elapsed
1.139 (1.106–1.176). The tolerance conclusion is unchanged. These are two distinct
collections; no blocks were selected between them, replaced, pooled or averaged.
The manifest records both artifact identities. The first collection's results
above remain intact and the second collection's full raw ZIP remains in its run.
