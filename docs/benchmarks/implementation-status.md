# Benchmark implementation: work in progress

This branch executes the worker specification. It is a draft, not a declaration
that the full catalogue, lifecycle experiment, or performance comparison passed.
The starting revision is `d7dbc57bfbe15ee97eecc9b94d185922fd1cff07`.

## Reviewable changes so far

| Previous behaviour | Implemented change | Evidence |
|---|---|---|
| Workbook in Git was a corrupt 7,525-byte object | Restored the intact 30,151-byte workbook and exported all three sheets | `catalogue.py` verifies ZIP CRC, SHA-256, every exported cell, 28 families and 129 APIs |
| Incorrect samples could leave a successful job | Runner and comparator write artifacts and then fail on incorrect first, warmup or steady results | Subprocess tests deliberately inject a wrong warmup, stale provider result and wrong navigation range |
| The two harnesses treated versionless diagnostics differently | One contract and shared adversarial vectors; absent versions never establish exact-version admission | TypeScript and Python contract tests |
| Unreadable RSS/counters became zero; nested counters could be double-counted | Unavailable values remain null; compiler snapshots are read once and cannot support causal zero-work claims without owner epochs | Missing/reset/aggregate tests; explicit evidence status |
| Stderr receipt times were presented as attributed elapsed work | Raw log observations retained; causal stage times and residuals remain unavailable | Overlap and delayed-observation contract tests |
| Second attachment retained an ID while discarding live documents | Initialize the session's `Documents` once | Regression fails on the parent; 32 targeted production tests pass with the isolated fix |
| CI could commit generated baselines | Workflow permissions are read-only and its Git push step is removed | Workflow diff |

The existing LSP runner now also accepts explicit suite arguments. Cases record
raw exchanges, exact fixture identities, every measured attempt and every failed
oracle. The existing prepared-workspace harness remains the second harness;
its admission and resource semantics are being brought under the same contract.

## Current limits and gates

The local environment refuses Unix socket creation and cannot read child-process
`/proc` metrics. Pipe runs are explicitly diagnostic. They do not establish normal
daemon attachment, scheduler behaviour, process-tree resource costs or AOT use.
JDTLS can run here, and its failures are retained rather than treated as expected
answers. Development pilots are not frozen, independent comparison blocks.

| Gate | Current disposition | Remaining evidence |
|---|---|---|
| A01 | pass | Connector-decoded repository bytes match the intact workbook SHA-256 and ZIP CRC; see `benchmarks/evidence/workbook-roundtrip.json` |
| A02 | fail | Complete executable coverage for supported targets; no unimplemented target may be classified unsupported |
| A03 | pass for shared admission contract; broader cases pending | Shared vectors and cross-file stale-result subprocess test |
| A04 | pass for tested gates | Runner/comparator reject injected wrong output; all three CI jobs passed on c5c10fd2. All three CI jobs also passed at 58bbd42b; those jobs did not yet execute the new native matrix. |
| A05 | blocked | CMP-01 variants are implemented; full supported-server execution and oracle review remain |
| A06 | partial | Live protocol journals, immediate/settled probes, source-drift detection and artifact-only checks are implemented; complete case histories still require full execution. |
| A07 | pass for bounded matrix; broader variants pending | Both clean/traced packaged Unix pilots at 1c29254 pass retained attach, normal detach, disposal and restart witnesses |
| A08 | blocked | Existing semantic proof tests pass; full benchmark mutation/ownership matrix remains |
| A09 | blocked | Request and transition intervals exist; final causal reconstruction remains |
| A10 | blocked | Invalid zero-work claims removed; complete scoped native evidence still to collect |
| A11 | partial | CI records sampled daemon/peer/helper costs; complete lifetime accounting and observer experiments remain |
| A12 | blocked | Fixture hashes and pipe source checks exist; full experiment inventories remain |
| A13 | partial | Profiles separated; CI records actual AOT rejection and continued normal JVM execution, not accepted AOT |
| A14 | blocked | No public comparative performance claim; independent blocks and uncertainty analysis remain |
| A15 | partial | Artifact-only reducer reproduces tested summaries and rejects tampering/interruption/concealed failures; full native bundle reduction and final gate remain. |
| A16 | pass for the reproduced diagnostic path | Session/context/processor spans explain the reproduced long open; no claim of identical causation for every historical run. See `long-open-diagnosis.md`. |
| A17 | pass for workflow/publication method | CI push removed; branch changes are published through the GitHub connector |
| A18 | pass for isolated retention fix | Before/after reproducer logs and 32 passing targeted production tests; later changes require fresh validation |

The full specification remains the acceptance contract. This file must be updated
with final evidence and dispositions; an intermediate draft is not task completion.

## Checkpoint: 2026-09-27

94 executable case definitions now map to all 106 target API entries.
This is implementation coverage, not a statement that 106 APIs passed. Protobuf
source generation (API-043) now has its real Gradle/protoc fixture; see the
protobuf follow-up below for its failed immediate-readiness result.
The reducer checks observed protocol methods as well as declared mappings, so a
case declaration alone cannot establish executed coverage. Required variants and
supported-server validation remain open.

Added transactional workspace edits, deterministic binary/source JAR fixtures,
project/build/configuration cases, refactoring and generation cases, and explicit
file/workspace lifecycle changes. Generated-code checks now include independent
compilation and selected runtime behaviour. Several new cases still need their
first supported-server run.

36 TypeScript tests pass. Python: 31 tests, 30 pass and one explicit process-resource
integration skip. Maven offline build and image assembly completed. AOT runtime
acceptance remains a separate, unproved gate. The artifact reducer is tested
against interrupted runs, changed raw results, failed assertions, missing samples,
and a failed immediate response followed by a successful settled retry.

The first new native matrix pipe pilot passed LIFE-01–07 and LIFE-09–10 semantic
checks. LIFE-08 failed when changed bytes at the same dependency coordinate did
not immediately expose the new member. The pilot does not establish settling time
or the cause, and pipe results do not prove Unix transport/scheduler/product AOT.
The bounded pressure leg observed accessibility-cache eviction and then checked
recovery. Process-family metrics remain unavailable in this local environment.

The JDTLS generation pilot applied and compiled all six generators, but each case
ended in a shutdown protocol failure. Other development pilots exposed oracle
mistakes (implicit Object superclass, invocation ranges, enum wire values and
command JSON encoding); corrected definitions need reruns. Stale responses and
incorrect caller identities remain recorded separately from those harness errors.
Two longer pilots were interrupted by the execution service; partial journals are
preserved and cannot pass artifact reduction.

Public comparative performance claims remain disabled. Native attribution,
observer overhead, complete variant coverage, long Apache Maven document-open
causality and independent final blocks are still required. This is a published
work checkpoint, not completion of the worker specification.

## Lifecycle follow-up

The next checkpoint adds packaged shim processes for Unix runs, live peer
journals, actual peer shutdown, strict daemon exit checks, and resource sampling
of both the daemon and separately launched shim trees without double counting.
CI now runs separate clean and traced product lifecycle pilots and uploads their
raw artifacts with read-only repository permissions. These remain correctness
and diagnostic subsets, not final independent performance blocks.

37 TypeScript tests pass. Python: 33 tests, 32 pass and one explicit process
visibility skip. New tests cover failed daemon shutdown, overlapping process-tree
roots, missing peer visibility, and JFR background events without invocation IDs.

The second pipe lifecycle pilot used 128 edits and retained all five artifact
mutation histories. Same-coordinate replacement remained stale throughout its
bounded retries; removal also failed. The attachment leg in that pilot depended
on the preceding stale binary result, so it cannot establish an independent
attachment defect. The current definition tests attachment changes against the
original unchanged binary first. All failed immediate responses remain recorded.

An isolated copy of the prepared Apache Maven fixture reproduced a 17.065-second
first native document-open response and a 108.4-ms second response. The definition
oracle and final input inventories passed. Tracing recorded 39 hashed files,
166,841,112 hashed bytes and 92 inventories in the first request, but lacked a
session-worker execution span. Its full cause remains unresolved. A brief JDTLS
diagnostic overlapped this development pilot, so these numbers are not isolated
performance estimates. A slash in the case ID also broke its per-case report
filename after measurement; the raw journals, JFR and final summary survived,
and that serialization bug is corrected.

A separate JDTLS shutdown thread dump shows Equinox waiting for framework stop,
which is joining the Java indexing thread while that thread waits in its indexer
loop. This is a captured shutdown wait, not a claim of a fully explained defect.
Selected witnesses are in `benchmarks/evidence/lifecycle-followup-2026-09-27/`;
that directory explicitly does not claim to be the full raw experiment bundle.

## Causal attribution and optional metrics

The next Apache replay recorded 23 external compiler launches whose invocation
intervals cover 15.527 seconds of a 16.358-second native RPC. These launches occur
inside annotation-processor preparation during document maintenance. The client
request took 16.367 seconds; the second open took 112.920 ms. Exact definition and
unchanged copied-input checks passed. Raw stage events and request records are
committed in `benchmarks/evidence/actor-attribution-2026-09-27/`. See
`long-open-diagnosis.md` for the interval semantics and limits.

The clean packaged Unix pilot at 2bbe9542 passed LIFE-01–07, LIFE-09–10 and the
Apache case. LIFE-08 remained incorrect. The traced product profile failed
because the jlink image omits `jdk.management` but tracing referenced its extended
ThreadMXBean. A separate reduced-runtime reproducer fails with
`NoClassDefFoundError` before the fix and returns the unchanged request result
afterward. The fix records unavailable allocation counters as -1 and caches that
capability failure. It does not add a zero-work claim or alter analyzer decisions.
Ten targeted production tests passed, followed by a successful final regression
run for the exact optional-counter patch. The actor attribution changes are kept
separate from the optional-module correctness fix in Git history.

Full CI native artifacts were uploaded (1.17 GB). The connector's download limit
is 512 MiB, so the workflow now additionally packages bounded review ZIPs containing
raw requests, native JFR recordings, stage reductions, logs and original checksum
inventories. The full bundle is retained. A local 86-file review ZIP was checked
for ZIP CRC and every selected file's SHA-256. Review copies explicitly identify
themselves as subsets and cannot stand in for a complete-bundle acceptance gate.

## Protobuf follow-up

BLD-02 now uses verified Gradle/protoc/runtime inputs, separate main/test schemas,
actual compiler Exec tasks, per-fixture settings on all LSP configuration paths,
and isolated Gradle state. It measures generation and generated-type readiness
separately, records created-file notifications, checks unchanged-repeat bytes,
and independently compiles and executes the generated code after measurement.

The local JDTLS pilot generated all six expected files. Compilation, serialization
round trips, consumer execution, unchanged repeat and shutdown checks passed.
Its first definition lookup was empty; the next and settled lookups were correct.
The failed first reply remains in the report and makes the case incorrect. See
`protobuf-case.md` for the setup contract and earlier harness-development errors.
No catalogue pass or performance claim follows from closing this implementation
gap. CI now collects the same case's complete raw bundle.

All 39 TypeScript harness tests pass after this follow-up, including rejection of
a successful no-op generation command and consistent per-fixture configuration.
Selected raw pilot witnesses are in `benchmarks/evidence/protobuf-2026-09-27/`.

## Native identity and resource reporting follow-up

The schema-2 causal reducer preserves untagged packaged-shim requests by their
JVM/native-request identity instead of pooling them under an empty invocation ID.
It rejects duplicate client identities, parent cycles and cross-request parents;
missing parents remain explicit. No client link or cross-clock join is inferred.

Available resource samples no longer imply complete lifetime accounting. Server
and peer roots are classified from declared ownership, with other descendants
classified as helpers. An external javac process is not labelled a bridge merely
because its executable name differs from java. Missing samples and unsampled
short-lived helpers still block complete resource claims. JFR DataLoss events are
retained when present; their absence is not a completeness proof.

Failed peer initialization now closes the newly started peer and retains its
failure journal. Review packages include the attribution file referenced by the
profile manifest. The local recovery validation has 39 passing TypeScript tests
and 34 passing Python tests with one explicit process-visibility skip.

## Saved Unix evidence audit

At 1c29254, both clean and traced packaged-daemon/shim runs passed LIFE-01–07,
LIFE-09–10 and the Apache case. Source attachment replacement, same-coordinate
binary replacement and removal remained stale through the bounded LIFE-08 probes.
The first two irrelevant-artifact transitions passed. Both profiles reported AOT
rejection; accepted AOT execution remains unproved.

The downloaded 13.8-MB review artifact passed its published SHA-256, ZIP CRC, and
all inner review-manifest hashes. Independent structural audit of both subsets
retains the failed lifecycle case. The audit checks case/summary agreement,
recording hashes, request identities, paired send/result records and clean daemon
exit. Tests reject altered bytes, resealed concealed failures, unfinished request
journals, unsealed extra files and paths escaping the bundle. Python now has 38
passing tests and one process-visibility skip. Full CI bundles get the same audit.

This is structural integrity evidence, not independent replay of every semantic
oracle or the final performance gate. The old local 21,813-file full-bundle audit
ran before the workspace reset; its raw bundle was not recovered, so this
checkpoint does not present that prior result as fresh validation.

The protobuf CI pilot at 4e2af56 independently reproduced successful real
generation, unchanged output bytes, compilation, serialization/consumer checks
and clean shutdown. Its immediate type lookup was empty and its retry/settled
lookups passed. The incorrect first reply is retained. No comparison claim is
enabled by either pilot.

The recovered Unix JFR was exported again with the pinned, hash-verified JDK.
Request 4 records 33.646 seconds in 23 compiler invocations. Request 5 spends
34.391 seconds queued and 104.505 ms executing. Selected raw events and an exactly
reproducible reduction are committed in `benchmarks/evidence/unix-open-2026-09-27/`.
The native identities stay separate; no missing client identity is invented.
See `long-open-diagnosis.md` for the queue/execution table and evidence limits.

## Required variants are a separate acceptance gate

Mapping all 106 target APIs did not establish the workbook's required variants.
`required-variants.json` now decomposes all 28 original families into 107 explicit
requirements while preserving the original variant and correctness instructions.
The initial source review marks 32 implementations as complete, 55 as partial and
20 as absent. These are implementation dispositions, not measured passes. The
ledger names remaining work; a partial case cannot inherit a pass or unsupported
status from another endpoint in its family.

Every new bundle snapshots and seals that ledger. Artifact-only reduction emits
`variants.json`, checks each required case and its operation states/assertions in
every server/block, and requires variant coverage in addition to API coverage for
`catalogueComplete`. Failed immediate probes remain failures after settled
success. Older bundles without the ledger keep their selected-case results but
cannot establish full variant coverage. This structural gate does not replace
independent semantic replay or the remaining performance/resource gates.

All 45 TypeScript tests pass, including missing operations/assertions, missing
server/block evidence, partial implementation despite passing API cases,
unsupported without evidence, and a successful block concealing a failed block.
