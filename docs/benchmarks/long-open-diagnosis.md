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

## Packaged Unix replay: distinguish queueing from execution

The traced Unix CI replay at `1c29254` reproduces compiler preparation and also
exposes the queue boundary that the serial pipe pilot could not show. Two native
`document.open` requests overlap. Native request 5 waits behind request 4 on the
session worker; its RPC duration is mostly waiting, not a second compiler pass.

| Native JVM boundary | Request 4 | Request 5 |
|---|---:|---:|
| RPC interval union | 34,399.556 ms | 34,497.016 ms |
| Session queue | 0.694 ms | 34,391.256 ms |
| Session execution | 34,395.383 ms | 104.505 ms |
| Processor preparation, interval union | 33,827.910 ms | 83.366 ms |
| External compiler invocation, interval union | 33,646.044 ms | No spans recorded |
| Recorded compiler launches | 23 | No counters recorded |

The compiler spans record 996 input-source instances. Preparation visits 24
modules, 24 source roots and 201 classpath entries per request. These inclusive
stage observations overlap; adding the two RPC durations would double-count
much of the user-visible wait. A missing compiler span remains unavailable
evidence about zero work, not proof of zero work.

The raw selected stage events, their source JFR hash and a schema-2 reduction are
in `benchmarks/evidence/unix-open-2026-09-27/`. Recompute with
`reduce_native(events, [], manifest['epoch'])` from `causal.py`. Native identity
is `(epoch, process, request)`. These shim-originated requests have no explicit
client invocation identity, so the reduction makes no client/native join.
All selected parent chains resolve. That check does not prove a loss-free trace.

The separately recorded client milestones are 61,670.402 ms for attachment and
36,139.471 ms from two opens to the exact definition result in the traced run;
the clean run records 58,321.354 ms and 24,773.674 ms respectively. There is only
one ordered clean/traced pair, so their difference is not a profiling-overhead
bound or a speed comparison. Both profiles report AOT archive rejection and
continue in normal execution. This replay establishes neither accepted AOT
execution nor complete helper-process resource costs.

## Separate JDTLS shutdown blocking path

An instrumented two-project build reproduction at `75be95e` answers shutdown
in 2.859 ms, then exits with code 1 about 60.064 seconds after the exit
notification. No harness forced kill occurs. This is separate from the JVMD
document-open investigation and from the uninstrumented build matrix.

| Observation | First snapshot, about 2 seconds | Second snapshot, about 7 seconds |
| --- | --- | --- |
| Main thread | Waiting for Equinox framework stop | Still waiting |
| Framework-stop thread | JobManager.shutdown → Thread.join | Same blocking path |
| Original Java indexing thread | Traversing JRT classes through AddJrtToIndex | Waiting in indexerLoop |
| Second Java indexing thread | Waiting in indexerLoop | Waiting on the same IndexManager |

The selected and full thread dumps, process journal and runnable SIGQUIT probe
are in `benchmarks/evidence/shutdown-index-2026-09-27/`. SIGQUIT is a documented
[HotSpot thread-dump mechanism](https://docs.oracle.com/en/java/javase/25/troubleshoot/troubleshooting-guide.pdf).
The probe deliberately collects raw stdout because these dumps can interfere
with protocol framing after exit. Its timings are diagnostic observations, not
a profiling-overhead bound or performance comparison. It ran alongside the
independent build matrix.

The pinned [JDTLanguageServer exit implementation](https://github.com/eclipse-jdtls/eclipse.jdt.ls/blob/08eafe6/org.eclipse.jdt.ls.core/src/org/eclipse/jdt/ls/core/internal/handlers/JDTLanguageServer.java)
schedules a forced exit after one minute; the [base server](https://github.com/eclipse-jdtls/eclipse.jdt.ls/blob/08eafe6/org.eclipse.jdt.ls.core/src/org/eclipse/jdt/ls/core/internal/BaseJDTLanguageServer.java)
defines that exit code as 1. The observed timing/status are consistent with this
fallback. This is an inference from the recorded process events and pinned
source, not direct interception of System.exit.

The exact JDT Core binary's bytecode shows shutdown clearing its processing-thread
field and joining the saved thread. Reset can create a new worker when that field
is null; the loop tests whether the field is nonnull rather than whether it still
names the current worker. Together with the two observed workers, that suggests
a restart-during-shutdown race to investigate. The triggering reset call and its
interleaving have not been captured, so a complete root-cause proof is still open.
Do not assign all sixty seconds to useful indexing or to a request's latency.

All originally sealed probe payload hashes verify. Two additional runtime files
are preserved with failed-inventory status; the review subset is not a claim
that the full original capture is sealed.
