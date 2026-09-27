# What the Apache Maven document-open pilot measured

The reproduced first open spent most of its time launching external compilers
for annotation-processor preparation. It did that work before returning from
`document.open`. It was not the latency of a completion request.

This is a causal finding for the recorded development pilot, not a performance
comparison or proof that every historical 18-second open had the same cause.

| Boundary | First open | Second open |
|---|---:|---:|
| Client native request | 16,366.691 ms | 112.920 ms |
| JVM RPC span | 16,357.510 ms | 111.821 ms |
| Session worker | 16,356.396 ms | 111.234 ms |
| Context preparation | 16,355.168 ms | 108.412 ms |
| Annotation-processor preparation, interval union | 15,786.316 ms | 96.873 ms |
| External compiler invocation, interval union | 15,527.022 ms | No invocation spans recorded |
| Recorded external compiler launches | 23 | No launch counters recorded |

These are nested or overlapping observations. Do not add the rows together, and
do not subtract the JVM durations from the client clock to invent a residual.
The first request's 23 invocation spans cover about 95% of its JVM RPC interval.
They record 996 input-source instances across those compiler invocations; this
is not a count of unique workspace files.

The request also recorded 9,959,556 hashed bytes while constructing its context
identity and 156,881,556 during analyzer configuration. These are positive work
observations from instrumented paths. Other hashing paths are not necessarily
covered, and a missing counter does not establish zero work.

The caller open followed the provider open in the same session. Both exercised
24 module preparation calls. The source path can reuse prepared processor outputs;
the second request's recording contains no compiler-invocation spans. A complete
scoped negative proof would additionally require recording-loss and counter-scope
evidence. The exact subsequent definition query passed its independent URI and
range oracle. The copied workspace and dependency inventories stayed unchanged.

## How this was established

The initial trace located the time inside the native request but sampled the
waiting caller rather than the session worker. Opt-in spans were added around
session execution, context identity/lookup/creation/configuration, processor
preparation, and actual compiler invocation. The production algorithms and
invalidation decisions were preserved. The replay used a fresh machine store,
isolated copies of the prepared Maven fixture and dependency repository, the
pinned JDK, a 1024-MiB ceiling, and the diagnostic pipe transport.

The raw stage events, raw open calls, source hashes and reduction are in
`benchmarks/evidence/actor-attribution-2026-09-27/`. Recompute stage unions from
those events using `benchmarks/workspaces/causal.py`; its `reduce_native` function
uses one JVM clock and keeps inclusive worker totals distinct from unions.
The original JFR hash is recorded in the provenance file.

The pilot does not establish Unix scheduler behaviour, product AOT acceptance,
process-tree CPU/RSS costs, or bounded profiling overhead. The separate Unix CI
pilot at 2bbe9542 passed the clean lifecycle checks except artifact freshness,
and passed the Apache definition check. Its traced profile exposed a different
instrumentation defect: the packaged runtime omits the optional `jdk.management`
module, while allocation sampling assumed its extended ThreadMXBean was present.
A reduced-runtime reproducer fails before that fix and returns the same request
result afterward, with allocation counters unavailable instead of zero.

## Consequences for the benchmark

Keep attachment, document maintenance, first query and repeated query as separate
measurements. Retain the user-visible open-to-correct-result interval as well.
Record processor preparation and child compiler costs in the document-maintenance
stage. Investigating whether that preparation can be deferred or reused more
widely is a separate production change requiring correctness evidence; this
benchmark work does not silently remove it to improve a timing.
