# Independent experiment collection

These committed plans coordinate the existing LSP suite and native lifecycle
harness. They freeze sizes, repetitions, order, timeouts, reset policy, statistics
and claim criteria before collection. Collection requires an unchanged committed
plan and a clean checkout. Each child uses a fresh fixture, server process, state
directory and isolated dependency repository. Server/configuration order reverses
on alternate restored blocks. Never resume by replacing failed blocks.

```sh
python3 benchmarks/workspaces/experiment.py collect \
  --plan benchmarks/experiments/matched-product.json \
  --java-home /path/to/pinned-jdk --jdtls-home /path/to/pinned-jdtls \
  --image /path/to/jvmd/image --output /new/path/matched-product \
  --report /new/path/matched-product-report.json

python3 benchmarks/workspaces/experiment.py reduce \
  --output /new/path/matched-product --report /new/path/recomputed.json
```

`scaling-product.json` runs five independent controls at three sizes, each with
zero-change, unrelated-body and relevant-API cases. This is a substantial offline
experiment: ten blocks contain 900 separate server lifetimes. Do not shorten it
after inspecting results. Smaller semantic pilots must be named separately and
cannot support the final comparison. Source projects use the same source bytes
through Maven and explicit Eclipse metadata; the aggregator is one extra fixed
build module. Local dependency archives are deterministic and independently
compiled before timing. The original Eclipse-only pilot is invalid for JVMD's
Maven model and remains a distinct preserved capture.

`observer-trace.json` and `observer-status.json` each contain ten paired blocks of
LIFE-01, LIFE-09 and LIFE-10. Both sides keep essential state checks. The trace
experiment toggles native/JFR instrumentation; the polling experiment separately
adds a status request after every edit. Case elapsed time includes those extra
requests. Query request intervals remain separate. The predeclared overhead
tolerance is ±5%; the entire 95% interval must fit to declare overhead bounded.
Otherwise use clean timings for speed claims and traced runs for attribution.

The analysis takes one median per endpoint/state/run and bootstraps paired block
log ratios with seed 271828 and 10,000 resamples. Requests within a run are never
independent replicates. One missing, failed, nonpositive or unavailable paired
value disables that endpoint's effect estimate. Timeouts remain failures, not
latencies equal to the deadline. Reports retain every block and all denominators.
Intervals describe individual endpoints; no family-wide significance is claimed.

Optional lifetime resource measurement requires `JVMD_BENCH_CGROUP_ROOT` pointing
to a delegated cgroup-v2 parent **that already contains the launching harness in
an observer leaf**. On the disposable CI runner, `delegate_cgroup.py` creates that
parent, starts a new wrapper in its observer leaf, and drops back to the runner
UID before launching the harness. It never changes global limits or the root
cgroup's permissions. Existing external processes are not migrated. Memory
charges, sampled RSS/PSS and Java heap/allocation remain distinct quantities.

Raw bundles are immutable and sealed after all planned attempts. Reduction writes
outside them, verifies inventories and reproduces child reports from their raw
records. It refuses source/toolchain drift and mismatched paired fixture identities.
The result never automatically authorizes public performance claims: the A01–A18
acceptance ledger remains mandatory. Running this alongside development is a
diagnostic pilot, even if every numerical check happens to pass.

The matched and scaling plans also support `collect --block N`. This selects the
original numbered block and its original server order, preserving the full
committed plan. It does not reduce the declared sample count or authorize a
single-block comparison. `experiment.py merge --output /unused --shards /a /b ...
--report /outside/report.json` independently audits all ten immutable captures,
requires matching source, plan, JDK and distribution identities, and reconstructs
the original paired analysis. Missing or duplicate blocks disable inference.
Relocated downloaded artifacts retain their original invocation identity.

`Benchmark experiment blocks` builds one distribution, then restores those exact
bytes on separate Ubuntu job VMs for the ten declared blocks of each plan. Every
block attempts both servers sequentially. Job failures do not cancel later blocks
or suppress raw uploads. This workflow has read-only repository permissions. It
runs on changes to its workflow or either collection plan; routine source pushes
do not restart a full 900-lifetime scaling collection. Small reports and original
raw captures are separate artifacts, and a final job reduces all original shards.
Hardware and runner image details accompany each block; OS cache remains
uncontrolled. These changes to the declared runner layout start a new collection.
