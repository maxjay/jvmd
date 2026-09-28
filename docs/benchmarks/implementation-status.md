# Benchmark implementation: work in progress

This branch executes the worker specification. It is a draft, not a declaration
that the full catalogue, lifecycle experiment, or performance comparison passed.
The starting revision is `d7dbc57bfbe15ee97eecc9b94d185922fd1cff07`.

Latest implementation inventory: 175 workbook case definitions plus 45 scaling and seven invalidation cases, 224 passing TypeScript
harness tests, and 107 required variants (107 implemented, 0 partial, 0 absent).
Implementation is not execution evidence. The checkpoint notes below retain
historical counts; the current variant ledger is authoritative for source scope.

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

The local environment refuses Unix socket creation and has no delegated writable
cgroup hierarchy. Local pipe captures remain diagnostic. Product CI now verifies
raw lifetime CPU, block I/O and kernel memory-charge accounting, including exited
descendants; sampled RSS remains a distinct lower-bound observation. The original
failed captures and corrected captures are preserved separately.

| Gate | Current disposition | Evidence or remaining gate |
|---|---|---|
| A01 | pass | Workbook round-trip SHA-256/ZIP CRC and every exported cell; `benchmarks/evidence/workbook-roundtrip.json` |
| A02 | blocked | 107/107 required variants implemented across 175 workbook cases; full 350-report replay still collecting |
| A03 | pass | Shared admission vectors, versionless and cross-file stale-result regression tests |
| A04 | pass | Actual runner/comparator subprocess tests reject wrong warmup, stale output and concealed failures after saving artifacts |
| A05 | fail | CMP-01 methods and independent timers execute; early stale product answers and shutdown failures remain mandatory failures in matched blocks |
| A06 | blocked | First/warmup/repeat/mutation journals are retained; full catalogue histories still require final collection and review |
| A07 | pass | Clean/traced product LIFE-01–07 distinguish fresh, restarted, retained, detached and disposed states using semantic witnesses |
| A08 | blocked | LIFE-07/08 shared ownership runs plus seven new independent overload/namespace/classpath controls; full supported-server replay in progress |
| A09 | pass | Client action/request intervals and native queue/execution spans retained separately; overlap reducer rejects invalid sums and cross-clock residuals |
| A10 | pass | Complete-scope claim gate rejects unknown/reset/duplicate counter scopes; complete reuse/zero-work claims remain unavailable, never zero |
| A11 | blocked | Six product daemon epochs have verified lifetime totals; pressure/recovery is witnessed; corrected observer experiments and final qualification remain |
| A12 | blocked | Pinned inputs, drift rejection and immutable distribution sharing implemented; final experiment inventories still collecting |
| A13 | blocked | Product/direct/pipe and JDTLS workspace states separated; native AOT rejection recorded; ordinary per-case product AOT log capture being completed |
| A14 | blocked | Ten declared matched/scaling blocks are collecting in CI run `36365632505`; uncertainty and success denominators await all original blocks |
| A15 | blocked | Native semantic wire replay and portable independent-block reduction pass tests; final full-bundle reductions remain |
| A16 | pass | Reproduced Apache open linked to compiler/context/processor activity and separate queue delay; `long-open-diagnosis.md` states the causal limits |
| A17 | pass | CI permissions are read-only; benchmark source/evidence published through the GitHub connector |
| A18 | pass | Isolated retention/allocation fixes retain original semantic contracts; 32 targeted production tests and subsequent production CI pass |

A pass in this table applies to the named measurement requirement. It does not
turn a failed product answer into a passing benchmark. A10 specifically records
that unsupported reuse and zero-work claims are prohibited, not that complete
native work counters have been obtained. A05's implementation exists, but its
mandatory product correctness condition has failed in collected evidence.

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

## Independent file and use mutations

There are now 107 case definitions. Completed cancellation has a subsequent
correctness probe; DOC-01 has an unchanged read series; external create, modify
and delete start from independent fixtures. External search and caller-visibility
routes are separate cases so a missing workspace-symbol capability cannot hide
supported definition/hover checks. Added/removed uses have independent reference
cases under both declaration flags and independent highlight cases. The fixture
now has an actual unrelated `number()` homonym. Symbol rename also compiles the
edited project. The ledger records 40 implemented, 48 partial and 19 absent
requirements; implementation still does not imply a passing experiment.

The first JDTLS pilot exposed two harness/oracle defects: rejecting an empty
`changes` map alongside `documentChanges`, and choosing a fixture name that
collided with a JDK type in workspace search. The edit planner now follows LSP
3.17's documentChanges precedence, including when a fallback map is nonempty;
the external provider has a distinct fixture name. All 46 harness tests pass.
The original failed bundles are retained separately from reruns.

The corrected four-case JDTLS pilot passed symbol rename (including compilation
and homonym protection) and external creation. Modification and deletion returned
stale immediate search results, followed by correct retries and settled probes;
both cases remain incorrect. A broader pilot had correct hover/highlight replies
but nonzero shutdown exits, which remain protocol failures. The JVMD pipe pilot
passed completed cancellation, repeated hover, both reference flags and their
add/remove variants, and rename. Highlights were explicitly unsupported. Its
external cases exposed the missing workspace-symbol capability check; the new
separate search route addresses that harness gap without declaring file-change
semantics unsupported. The route-specific results follow.

The final DOC-02 pilot at `deb0ae5` used six independently reset routes per
server. JVMD passed all three caller-visibility cases, including deletion's exact
caller diagnostic, and declared all three workspace-search routes unsupported
from initialize capabilities. JDTLS passed search-create and search-delete;
search-modify retained a stale immediate range before settling. Its three
caller-visibility cases had correct semantic replies but nonzero shutdown exits.
No failure was changed to a pass. All four pilot bundles had unchanged source
hashes during collection. Their raw protocol journals, original inventories and
review hashes are committed in `benchmarks/evidence/independent-variants-2026-09-27/`.
The archive contains review subsets, not complete performance evidence.

Cancelled workflows now stop starting further native/protobuf experiments while
still uploading their captured evidence. Ordinary correctness failures continue
to allow the other diagnostic profiles to run.

## Signature and span-shift follow-up

There are now 109 case definitions. Signature help has an unchanged series plus
an independently reset provider-parameter mutation. Hover has a distinct return
type/documentation mutation at the unchanged caller. Inlay hints and selection
ranges now probe inserted lines using ranges calculated from the current source.
Type-hierarchy mutation probes use a freshly prepared item on every attempt;
the supertype probe targets Child so changing its parent must change the answer.
The previous Base-supertype probe could not distinguish that mutation.

The signature oracle validates the active overload's parameter types in order,
not any signature or the return type. It follows LSP 3.17 defaults and the
signature-local active-parameter precedence; the client advertises that support.
All 50 harness tests pass. The variant ledger now has 44 implemented, 45 partial
and 18 absent requirements. Real-server validation of this new batch remains
pending, and no performance claim is enabled.

## Actual call-graph mutations

REL-01 now has independently reset incoming/outgoing add/remove cases. Preparation
and expansion have separate unchanged series. Mutation cases enumerate exact
current call-site ranges and reacquire the hierarchy item for every immediate,
retry and settled expansion. Removing the sole outgoing call must return no edge;
unrelated methods cannot substitute for the expected caller/callee. Resulting
sources compile independently after measurement.

Two subprocess tests serve stale pre-edit graphs through the actual runner. An
added incoming call and a removed outgoing call both fail while retaining their
raw replies. All 52 TypeScript tests pass. The source ledger has 48 implemented,
43 partial and 16 absent requirements; real-server call-graph validation is still
pending and performance claims remain disabled.

The signature/span pilot at `6a2949e` had no integrity issues or source drift.
JVMD passed hover/signature repeats and both declaration-edit cases; the other
four selected endpoints were explicitly unsupported. JDTLS retained stale replies
for both provider edits and both parent-change probes before correct settled
replies. Its unchanged hover/signature and shifted hint/selection replies passed
their semantic checks but had nonzero shutdown exits. The raw review archive is
in `benchmarks/evidence/signature-call-2026-09-27/`.

The first call-graph pilot exposed an oracle assumption: JDTLS may repeat caller
rows and use call-site selections inside the enclosing declaration. LSP's item
selection is required to be contained by its enclosing range; it need not select
the declaration name. The corrected oracle validates exact caller/callee name,
kind, URI and enclosing declaration, bounded selections, and the exact distinct
call-site set from the fixture. It records raw row/range counts separately. It
does not drop unrelated callers, missing uses or stale ranges. Tests cover both
grouped and repeated representations and reject those incorrect alternatives.
All 54 harness tests pass. The original pilot is retained as development evidence,
not a server-correctness claim; the corrected real-server replay is pending.

## Corrected call-graph replay

The replay at `71c49fe` uses the corrected semantic-set oracle for all six
JDTLS call-graph cases. Incoming first/repeat, addition and removal pass fully.
Outgoing first/repeat, addition and removal return the exact expected call sites,
but all three exit nonzero during shutdown and remain `protocol_error`. All 40
recorded semantic operations pass; no integrity issues or source drift occurred.
No failed operation or shutdown was dropped. This one-block diagnostic does not
establish comparative performance.

`benchmarks/evidence/call-graph-2026-09-27/` preserves the manifest, per-case
dispositions and a hash-verified raw protocol review subset. The archive includes
requests, responses, source snapshots, operations, assertions, launch data and
stderr; generated workspaces and caches are excluded and explicitly identified
in its review manifest. The earlier development pilot remains separately marked
as unsuitable for server-correctness claims because its grouping oracle was wrong.

## Independent reference-lens variants

VIEW-03 now covers first and unchanged repeated acquisition/resolution, plus
independently reset added-use and removed-use cases. Each changed resolve
receives a newly acquired lens, with the existing client/document-state ownership
check. Acquisition and resolution remain separately timed requests.

The reference-only fixture disables implementation lenses before initialization.
The oracle requires the exact declaration, reference command, anchor, displayed
count and independently enumerated location set. It rejects duplicates that
preserve the count, wrong commands, wrong declarations, unrelated files and stale
post-edit use sets. The command shape follows JDTLS CodeLensHandler's
`java.show.references` contract; opaque lens data is forwarded without inspection.
Mutated fixtures undergo independent compilation. Client capabilities explicitly
advertise code-lens support and the refresh request handler already implemented.

58 harness tests pass, including subprocess failures for stale added/removed
references and a duplicated reference with the correct count. Source inventory is
115 cases and 107 variants: 52 implemented, 42 partial and 13 absent. These are
implementation dispositions; supported-server replay is still required.

## Narrow formatting and stronger mutation-test prerequisites

The range-formatting case now selects only the middle method in a dedicated
fixture. Deliberately unformatted methods before and after it must remain byte
identical. Both initial and idempotence requests reject edits outside the selected
method. The first formatting operation validates its edit scope and unchanged
fixture code before it can be recorded as successful. Raw-string formatting now
also compiles the returned source independently. Other formatter routes retain
their own fresh fixtures.

The first reference-lens pilot exposed a harness bug: declaration lookup found
the letter `b` in `public`, rejecting the correct returned method lens. The lookup
now starts at the method name. Stale-lens and stale-call-graph subprocess tests
require a passing baseline and an incorrect immediate post-mutation response,
so a failure during setup cannot satisfy a stale-result test. The initial lens
pilot is development evidence, not evidence of JDTLS semantic failure; its
corrected replay remains required.

59 harness tests pass. The inventory is 115 cases and 107 variants: 54 implemented,
40 partial and 13 absent. Supported-server formatting replay and the broader
worker-specification gates remain open.

## Lens/format replay and complete inventory checks

At `25eba1c`, JDTLS passes all three reference-lens cases and raw-string formatting.
Narrow formatting passes its initial scope/preservation check, independent
compilation and idempotence, but exits with code 1 and remains `protocol_error`.
JVMD explicitly reports all five routes unsupported. The ten-case pilot has
four passes, one protocol failure and five unsupported dispositions, with no
source drift or integrity issues. The initial lens development pilot is retained
and explicitly ineligible as evidence of server correctness.

`benchmarks/evidence/lens-format-2026-09-27/` preserves both raw review subsets,
including JDTLS runtime logs. In the failed formatting case, JDTLS logs receipt
of shutdown and exit, then application/plugin shutdown, followed by a Gradle
background-job exception. The pinned JDTLS source at `08eafe6` schedules a
forced exit after one minute; the observed delay is consistent with that path.
These observations do not prove why the process stayed alive. No shutdown error
has been converted into a pass or used as a request-correctness failure.

The LSP runner now re-enumerates repository files for its final source snapshot,
detecting additions and removals as well as changed bytes. NUL-delimited Git
paths preserve unusual filenames. Artifact-only reduction rejects every file
missing from the checksum inventory, including an omitted experiment manifest.
61 harness tests pass; the new tests exercise source additions/deletions and
unsealed extra files/manifests. Other source and acceptance counts are unchanged.

## Process shutdown observations

Each new LSP client run journals launch request, actual spawn, shutdown request
and reply, exit notification, input closure, observed process exit and output
stream closure with client monotonic timestamps. Exit status and signal are
retained. Shutdown timeout and forced kill are distinct observations; requesting
a kill is not treated as proof that a process has exited. The client waits for
observed termination and drained streams before the runner seals its journals.

Artifact reduction cross-checks the process journal against the final case report
and rejects a pass without an observed clean exit and closed streams. Legacy
bundles without process journals remain readable; they do not gain these missing
observations retroactively. This tracks the launched peer process, not complete
daemon/helper lifetime resource costs.

64 harness tests pass. Controlled subprocesses cover a clean exit, nonzero exit
and a peer that ignores shutdown/exit and requires a recorded forced kill.
The JDTLS shutdown cause remains unresolved; the new timestamps improve future
diagnosis without changing prior failed dispositions.

## Cleanup repeat checks and measured shutdown disposition

Import organization now runs an unchanged second request independently through
the request, command and pre-save routes. Every route must preserve the complete
source state on repeat. The first result must retain the used import and all
non-import code, remove the unused import and leave every unrelated source byte
unchanged. Manual cleanup has the same exclusion/idempotence checks and may only
insert the configured override annotation. Independent compilation remains
required. Source scope is 57 implemented, 37 partial and 13 absent variants;
64 harness tests pass. A supported-server cleanup replay is still required.

The first process-stage replay at `53e582a` returned correct hover results for
both servers. JVMD's diagnostic pipe process exited with code 0. JDTLS's direct
process returned its shutdown response 4.131 ms after shutdown began, received
the client's exit notification at 4.237 ms, and exited with code 1 at
60,030.919 ms. The harness recorded neither a shutdown timeout nor a forced kill.
This separates a prompt protocol acknowledgement from delayed process
termination; it does not establish a comparative performance estimate or prove
the cause of the delay. The pinned JDTLS one-minute exit timer remains a
source-supported explanation consistent with the timing.

`benchmarks/evidence/shutdown-stages-2026-09-27/` preserves the hash-verified
protocol/process journals, runtime logs and stage table. Source drift is false
and artifact integrity checks pass. The JDTLS case remains `protocol_error`.

## Code paste and cleanup replay

FMT-03 now includes code copied from a separate source file whose `List` field
requires `java.util.List` in the target. The import-on-paste setting is fixed
before initialization. The command result must preserve the pasted text and
supply only the expected import. The client validates and applies the additional
edit at its supplied version, then rebases the insertion onto its unchanged
marker. It never applies a stale versioned edit. Unrelated files must remain
byte identical; independent compilation and execution verify the resulting field.
Subprocess tests reject missing imports and the wrong `java.awt.List` import.

Workspace-edit planning now accepts valid empty transactions. An empty preferred
`documentChanges` list preserves the source even if a fallback `changes` object
is nonempty, as required by the negotiated LSP representation. Missing/non-object
edits still fail. This is necessary for valid unchanged-repeat results and does
not excuse a missing requested transformation.

At `8491c0f`, all four JDTLS cleanup routes pass their first effect, independent
compilation, unrelated-source checks and unchanged repeat. The organize-imports
command case exits cleanly. The request, pre-save and manual cleanup cases exit
with code 1, without a harness kill, and remain `protocol_error`. JVMD explicitly
declares all four routes unsupported. The raw review subset and process journals
are preserved in `benchmarks/evidence/cleanup-2026-09-27/`; no source drift or
integrity issues occurred. The code-paste case still needs supported-server replay.

The inventory is 116 cases and 107 variants: 58 implemented, 37 partial and 12
absent. All 67 harness tests pass. Remaining acceptance gates are unchanged.

## Predeclared probing policy and paste replay

The manifest now declares the existing transition probe schedule before launch:
at most 100 attempts, 20 ms between failed attempts, each request's timeout, and
a deadline checked after failures. In-flight acquisition/query requests retain
their individual timeout, so this is explicitly not a hard total-duration cap.
A second correct response is required for the settled observation. Deadline
tracking now uses the same monotonic client clock as protocol events.

Each transition records its policy, deadline, attempt count, end observation and
termination reason (`settled`, `deadline` or `attempt_limit`). The reducer checks
those records against the predeclared plan. Failed early replies remain failures;
reaching the attempt limit cannot be described as a full timeout-duration probe.
Adversarial subprocess tests verify failed probes retain their declared limits
and stopping reason.

At `fb447f6`, JDTLS code paste passes exact import selection, unrelated-file
preservation, independent compilation and runtime validation. File-path inference
also passes. String paste and semicolon insertion pass semantic/compilation
checks but retain nonzero shutdown exits; neither was killed by the harness.
JVMD declares all four routes unsupported. The raw review subset is preserved
in `benchmarks/evidence/paste-2026-09-27/`, with no source drift or integrity issues.

The reviewed FMT-03 ledger now names witnesses for all four variants. The string
case additionally checks exact final source, unrelated files and runtime string
value; those new assertions require their own replay and are not claimed by the
earlier archive. Inventory: 116 cases, 61 implemented variants, 34 partial and
12 absent. All 67 harness tests pass.

## Final replay for this checkpoint

The `b8dc461` replay verifies the newly declared probe policy with independent
reference addition on both JVMD and JDTLS. Both immediate and settled replies
pass, and each transition records one attempt terminating as `settled`. JDTLS
also passes the stronger string-paste checks: exact escaped source, unchanged
unrelated files, independent compilation and runtime string value. Its nonzero
shutdown exit remains `protocol_error`; JVMD declares string paste unsupported.

The four-case pilot has two passes, one protocol failure and one unsupported
disposition. Every semantic operation and assertion passes, with no source drift
or integrity issues. `benchmarks/evidence/paste-probe-2026-09-27/` preserves the
raw protocol/process review subset, declared plan, transition records and hashes.

GitHub Tests and Benchmarks passed at `fb447f6`. Later-head workflows have their
own dispositions and must not inherit that result. The implementation remains
a draft: 34 partial and 12 absent variants, full semantic replay, accepted AOT,
complete lifetime resources, observer overhead and independent comparison blocks
remain outstanding. No comparative-performance claim has been enabled.

## Invalid rename positions and explicit rejection evidence

REF-01 now has independently reset prepare-rename and rename cases at in-bounds
whitespace. Each first proves a valid symbol works. The rename baseline checks
the complete proposed workspace: exactly one declaration and three caller uses
change, with every other byte preserved. It previews the transaction without
applying it. Both cases then require rejection of the whitespace position and
verify all source hashes, disk hashes, versions, file membership and mutation
records remain unchanged; unsolicited server apply-edit requests fail the case.

LSP 3.17 permits a null response or a semantic error for an invalid rename target.
Rename may also return an empty/no-change workspace edit. The client now
advertises prepare-rename support and gates that case on `prepareProvider`.
Semantic errors retain their raw error and exchange records, with an explicit
`rename_rejection` disposition. Only InvalidRequest, InvalidParams or RequestFailed
with an informative rename/target message are accepted. Internal errors, missing
methods, cancellation and timeouts remain failures. The reducer independently
checks the method, state, case identity, error and earlier successful baseline;
an arbitrary error cannot be relabelled as a successful negative test.

Fourteen added tests run both cases through the actual subprocess runner with
valid and deliberately broken peers, and try forged rejection dispositions.
All 81 harness tests pass. Inventory: 118 cases, 62 implemented variants, 34
partial and 11 absent. This is implementation/test evidence; the two new cases
still require real-server replay. No comparative-performance claim is enabled.

Protocol reference: [LSP 3.17 rename and prepareRename](https://github.com/microsoft/language-server-protocol/blob/gh-pages/_specifications/lsp/3.17/language/rename.md).
The pinned JDTLS `08eafe6` PrepareRenameHandler returns InvalidRequest for a target
that cannot be renamed; this is why treating every JSON-RPC error as a transport
failure would mismeasure this negative scenario.

The `e34513c` real-server replay passes all eight semantic operations and all
fixture-preservation assertions. JVMD returns null for both invalid requests;
JDTLS returns InvalidRequest for prepare-rename and an empty rename edit. Three
cases pass completely. The JDTLS prepare case remains `protocol_error` because
the process exits with code 1 during shutdown, without a harness kill.
There is no source drift or integrity issue. The raw protocol/process review
subset is in `benchmarks/evidence/rename-invalid-2026-09-27/`.

`benchmarks/lsp-scenarios/package_evidence.py` now makes these review subsets
reproducibly: it verifies every full-bundle checksum and inventory membership,
records omitted paths, hashes every selected member and checks the resulting
archive byte-for-byte. A review subset is explicitly not a complete experiment
bundle. This replay does not change the outstanding comparison gates.

## Exact type hierarchies and legacy traversal limits

Modern hierarchy preparation now has first, warmup and steady observations, in
addition to expansion observations. Every item must have the exact fixture URI,
kind, declaration range and name selection. The parent-change cases still obtain
a fresh raw item for every retry and retain incorrect immediate responses.

The legacy open/resolve route now has nine independently reset cases: children,
parents and both directions, each at depths zero, one and two. A four-level
interface fixture gives an exact graph, a meaningful grandchild and an unrelated
type without depending on JDK Object subtype enumeration. Each open and resolve
request is measured separately, including unchanged repeats. Resolution starts
with a newly obtained, unexpanded raw item; it cannot reuse an already expanded
answer as evidence that resolve worked. Exact recursive oracles check both
requested edges, duplicate/extraneous types and the traversal boundary.

The context checks the original serialized legacy item belongs to this client
and unchanged document state before transmission. The saved reducer checks its
origin request, response bytes, time order and state. Altered or stale items fail.
Nine added tests cover wrong kinds/spans, wrong direction/depth, missing
grandchildren, duplicate types, forged provenance and stale modern expansions.
All 90 harness tests pass. These strengthened cases require real-server replay.

The source ledger now has 64 implemented variants, 32 partial and 11 absent;
there are 126 cases. Counts describe implementation, not accepted comparison
evidence. Legacy argument order and traversal semantics were checked against
JDTLS `08eafe6` JDTDelegateCommandHandler and TypeHierarchyCommand. No comparison
claim is enabled.

## Legacy hierarchy wire correction and independent parent changes

The `a6496e2` real-server replay exposed a harness encoding mistake: the legacy
command consumes direction and depth as JSON strings. The harness supplied
numbers, which the pinned JDTLS command's JSONUtility conversion cannot convert
to Integer. All nine legacy request failures in this pilot are development
evidence about the harness, not evidence of server correctness or speed.
`benchmarks/evidence/hierarchy-wire-2026-09-27/` retains the original requests,
errors, logs and all case dispositions. The modern subtype case passes; the
supertype case retains stale prepare ranges immediately after the parent edit,
followed by current replies, plus a nonzero shutdown exit. JVMD declares all
eleven routes unsupported. No source drift occurred.

The corrected encoder follows the actual [VS Code Java client](https://github.com/redhat-developer/vscode-java/blob/777cc2c73d45e07d33ed37eb5eeb50b7e1800f21/src/typeHierarchy/typeHierarchyTree.ts).
The fake peer now rejects numeric command arguments, so the regression cannot
pass by relying on a more permissive substitute server. Semantic tree oracles
are unchanged. Three additional independent cases replace Child's parent and
verify children, parents and bidirectional legacy expansion. Every retry obtains
a fresh raw depth-zero item, checks the complete changed graph to depth two,
and the resulting source must compile independently. Stale-parent tests establish
a correct baseline and prove every retry uses a new acquisition request.

Saved reduction now also checks operation command identity against its exchange,
first/repeat series against the declared endpoint, and raw response payloads
against exchange payloads. Reassigning a command or rewriting just the processed
result cannot silently alter the saved evidence. All 94 harness tests pass;
129 cases are defined. The corrected legacy cases and new parent-change cases
require another real-server replay. The variant count remains 64 implemented,
32 partial and 11 absent, with all comparison gates still outstanding.

## Corrected hierarchy replay and generator membership/status checks

At `f758c9b`, all nine legacy direction/depth cases pass on JDTLS, including
unchanged repeats and original-item provenance. The modern subtype case passes.
Modern supertype and all three legacy parent-change cases retain stale early
preparation/expansion replies, followed by correct settled replies; the legacy
edited fixtures compile independently. The modern supertype case also has a
nonzero shutdown exit. JVMD declares all fourteen cases unsupported. The final
counts are ten passes, four incorrect cases and fourteen unsupported cases.
Source hashes remain stable. Three reducer issues identify missing immediate
expansions when their preceding fresh-item acquisition failed; the failed
acquisition operations themselves are present. Raw protocol/process evidence is
preserved in `benchmarks/evidence/hierarchy-corrected-2026-09-27/`.
Archive validation additionally discovered two zero-byte runtime files outside
the original checksum inventory. All originally sealed files still match their
hashes. The extras are archived explicitly with a failed-inventory disposition;
the original checksums are preserved. The cause is unproven. The pilot is not
fully sealed and cannot support an accepted comparison result.

The six generator cases now check the full proposed transaction before applying
it: only Generate.java may change, file membership and every other source are
preserved, and existing field declarations stay intact. Status is requested
immediately after the recorded document-change notification, before independent
compilation can hide an early stale response. Current status and exact live
declarations must agree; unchanged status is checked again afterward.

The outline oracle checks the exact selected methods/constructor and preserved
fields, their kinds, declaration selections and containing ranges. It supports
hierarchical and flat symbols. Independent compiled reflection checks the exact
method signatures, constructor signature and field types, alongside the existing
behavioural probes. The previous "more than four symbols" check is removed.

Constructor status is handled according to its actual schema: it lists eligible
superclass constructors and fields, not an existing-constructor flag. Those
choices must remain accurate after generation; the generated constructor's
existence is proved by the exact outline and compiled reflection. Other status
routes must recognize generated members while preserving unselected choices.

Ten new tests reject stale status, disappeared choices, extra/missing or stale
symbols and unrelated edits; subprocess tests establish that status probing
precedes compilation. Two further tests prove packaging rejects unsealed extras
by default, preserves them only as explicitly failed-inventory evidence, and
never treats changed sealed payloads as valid. All 106 harness tests pass. The source ledger has 70
implemented variants, 26 partial and 11 absent. These strengthened generator
cases still require real-server replay, and no comparison claim is enabled.

## Preparation failures are recorded before target requests

Each transition attempt now records separate preparation and target operation
IDs for its first and settled probes, a monotonic interval, stopping stage and
outcome. Start/finish records are written to `transitions.jsonl` as the attempt
runs. If fresh-item acquisition fails, the target is explicitly blocked; no
target request, response or zero-duration sample is invented. Retry delays remain
outside the failed attempt's interval. The predeclared policy names this rule.

The reducer replays operation ownership, time order, target state and exact
start/finish journal contents. A missing immediate target is accounted for only
when an identified preparation operation failed. The failed operation remains
incorrect even if a later retry and settled response succeed. Legacy artifacts
without attempt records retain their earlier validation behaviour.

A subprocess regression injects one stale preparation, then correct replies.
It proves the case remains incorrect, no immediate expansion was sent, the retry
and settled expansion succeed, and the records have no missing-evidence issue.
Forged blocked-target claims, missing settled targets and rewritten journals
fail validation. All 107 harness tests pass; the implementation inventory remains
129 cases and 70 implemented, 26 partial and 11 absent variants.

## Generator replay

At `6a14f2c`, all six JDTLS generator cases pass exact member/field preservation,
post-generation status, live declarations, independent compilation, reflected
member signatures and runtime behaviour. Accessors pass completely. Overrides,
equals/hashCode, constructors and delegates retain nonzero shutdown exits, with
no harness kill. ToString returns seventeen stale `exists:false` replies before
the eighteenth attempt and its settled probe recognize the generated member;
its overall case remains incorrect. JVMD declares all six routes unsupported.

`benchmarks/evidence/generation-2026-09-27/` preserves the twelve-case pilot.
All originally sealed files match their hashes and source inputs are stable.
Archive validation again found two additional zero-byte runtime files outside
the original inventory; their metadata change times postdate the checksum seal.
They are included with explicit failed-inventory status and unchanged original
checksums. The cause is unproven, and this capture is not claimed to be fully
sealed. It predates the new attempt journals and does not provide retroactive
execution evidence for those records. Comparison claims remain disabled.

## Real replay of preparation/target boundaries

At `354a97c`, JVMD and JDTLS both pass independent reference addition with an
immediate target and settled confirmation in one attempt. JDTLS modern supertype
and legacy parent-change scenarios explicitly record `blocked_by_preparation`:
their first fresh-item responses are stale, so no immediate expansion is sent.
They settle on attempts seventeen and three respectively, and remain incorrect.
The modern case additionally retains its nonzero shutdown exit. JVMD declares
both hierarchy routes unsupported.

All attempt journals, operation identities, timing order and raw exchanges
validate without integrity issues or source drift. The complete original file
inventory verifies at packaging. `benchmarks/evidence/transition-attempts-2026-09-27/`
preserves the six-case review subset: two passes, two incorrect cases and two
unsupported cases. This directly exercises the new blocked-preparation records;
it does not erase the earlier failed captures or enable a performance claim.

## A real project JDK switch

PRJ-02/jdk-switch now requires two real full JDK installations: JDK 17 through
`--alternate-java-home`, and JDK 21 or newer through `--java-home`. Each release,
canonical path and regular-file inventory is recorded. An independent javac
witness first compiles the identical List.getFirst source under both platforms:
17 must reject precisely that method, the newer JDK must compile it and return
the expected first list item at runtime. Both use source/target 17; release
emulation is explicitly disabled. Missing tools are preparation failures.

The live scenario establishes the old project VM, unchanged compiler settings
and a nonempty baseline that contains get but excludes getFirst. After the one
old-to-new update, its very next request probes completion. No metadata query
or independent compilation hides immediate readiness. All retries remain in
the transition journal. The new VM and unchanged language settings must then
be reported accurately; every source byte and open document version must stay
unchanged. A successful-looking update object with success:false is rejected,
including in the older same-home case.

Nine tests exercise false acknowledgements, misleading VM inventories, unrelated
compiler failures, empty baselines, stale API replies and inaccurate reported
environments. The two real compiler witnesses also pass locally with Temurin
17.0.20.1 and 25.0.4.1. This implementation still needs its first real LSP replay;
it does not promote development timings into comparison evidence. See
`jdk-switch-case.md` for the exact execution contract.

## Real JDK-switch replay

At `7b72684`, JDTLS passes both the tightened same-home command case and the
real JDK 17 to 25 switch. Before switching, completion includes get and excludes
getFirst. The immediate and settled replies after the switch both include
getFirst; the reported VM changes and the compiler language settings remain at
17. Every source byte and document version is preserved. Both cases shut down
successfully. JVMD records the command as unsupported from initialize evidence.

The independent JDK compilers establish the expected API difference and the
new-platform runtime returns the first element. `benchmarks/evidence/jdk-switch-2026-09-27/`
preserves raw replies, transition attempts and toolchain/compiler identities.
All originally sealed files match and source inputs are stable. Packaging again
found additional runtime files outside the original inventory. Those files and
their metadata are preserved with explicit failed-inventory status; the original
seal is unchanged and the cause is unproven. The original reducer recorded two
passes and two unsupported cases, but this capture is not fully sealed and
provides no comparative performance estimate.

## Independent symbol routes and inherited declarations

Fifteen independently reset cases now exercise standard outline, extended
outline, workspace search, filtered search and workspace-symbol resolution.
Each route has unchanged first/repeat, added-declaration and renamed-declaration
cases. Oracles check complete expected name/kind sets, exact declaration ranges,
source URIs and absence of the old renamed symbol. The extended outline must
include an inherited member whose range belongs to a separate parent source;
its containing child and declaring-source URI are checked independently.

Saved changes send the new buffer and save before the immediate semantic probe.
The trigger is the buffer-change notification; no compilation or readiness
query precedes the first probe. A resolve transition acquires and validates a
new original search item on every attempt. The client refuses forged or stale
items, and artifact replay checks the original raw item, state and request ID.
Changed cases preserve all unrelated source state and independently compile
only after the semantic transition.

Twelve new tests reject extra/stale declarations, homonym paths, wrong kinds,
use-site ranges, absent or mislocated inherited members, and forged item
provenance. Subprocess tests run all five first/repeat routes; stale mutation
responses remain incorrect after successful retries. All 128 harness tests pass.
The four NAV-03 variants remain partial: project/source/limit filters still need
negative controls that can actually expose an ignored filter. These fifteen
new definitions also still need real-server replay.

## Package-outline range correction

The first real symbol replay exposed an overly narrow package oracle. JDTLS
selects the complete `package bench;` declaration; the oracle had demanded only
the identifier. Both exact ranges are valid package selections. The corrected
oracle accepts those two fixture-owned spans and still checks the complete
package range, package name/kind, containing level, and strict type/member spans.
Regression vectors cover both valid forms and reject an unrelated selection.
The twelve symbol tests pass. The original frozen replay continues with its
original oracle so its failed raw results remain attributable to that revision;
those package failures are harness mistakes, not evidence of a server defect.

## Symbol filters with present excluded candidates

Three additional independent cases prepare two imported projects and a real
class-only JAR. The main project contains two matching source declarations; the
second has a distinct matching declaration; the JAR contributes a fourth type.
Raw compiler/JAR commands, tool hashes, source bytes and archive/class hashes are
recorded. Both workspace folders are supplied consistently at initialize and in
server callbacks. The expected binary archive, package and class-file URI path come from these
inputs; they are not learned from a server response.

After the measured first/repeat or changed semantic probe, explicit controls
check all candidates, each project's scope, source-only scope, the combined
project/source filter and a one-result limit. Each control is repeated unchanged.
The bounded reply may choose any one correct source declaration; it cannot be
empty, oversized, duplicated or unrelated. Such an arbitrary subset is never
used as a mutation-freshness witness. Only the preceding complete declaration
set establishes new/renamed visibility. Both excluded candidates must appear in
positive controls, and secondary sources and binary bytes must remain unchanged.

Six new tests exercise ignored exclusions, misleading binary identities, limits,
consistent workspace folders and control ordering for unchanged/new/renamed
cases. All 134 harness tests pass. The real binary preparation also succeeds
locally. The four NAV-03 variants are now implemented in source (75 total), while
real-server replay remains required; execution failures are separate from that
implementation disposition. There are 23 partial and 9 absent variants.

## Workspace-symbol command encoding correction

The first symbol replay also exposed a command encoding error: the original LSP
SymbolInformation has numeric kind, but java.project.resolveWorkspaceSymbol uses
plain Gson and expects the enum name inside its JSON string argument. The
vscode-java provider explicitly performs this conversion. The benchmark now
maps that one field (11 to Interface, 5 to Class, etc.) while preserving the
original item and every other returned field. Both the newer independent route
and the older search-filter case use the same encoder.

Issued-item provenance is checked against that declared serialization, not a
fabricated symbol. Every resolve operation records the named-enum encoding;
artifact reduction reconstructs the argument from the original raw search
reply. Older records retain their original numeric serialization when validating
provenance, and their protocol failures remain failures. The fake command now
rejects numeric enums too, so the previous mistake fails the subprocess gate.
All 135 harness tests pass. Real replay of the corrected wire argument remains
required; the original three resolve protocol failures are harness mistakes.

## Initial symbol replay and preserved harness mistakes

The thirty-case frozen replay at `770c576` records nine passes, six incorrect
cases, three protocol errors and twelve unsupported cases. JVMD passes all three
standard outline variants. JDTLS passes all three workspace-search variants and
all three single-project filtered-search variants, including additions and
renames. These successes have no failed immediate semantic reply.

The six JDTLS outline cases fail the original package-selection oracle before
reaching their mutation; all six also retain nonzero shutdown exits. Their raw
baseline replies satisfy the corrected oracle when replayed without a server.
The three resolve cases fail the original numeric-enum command argument. Those
nine primary failures are identified harness mistakes, not attributed server
semantic defects. Missing remaining samples after the early aborts remain in
the original reducer output.

`benchmarks/evidence/symbols-initial-2026-09-27/` preserves every raw exchange and
failure, the original revision and checksum inventory, plus explicit review
classifications. The complete original inventory verifies; source inputs are
stable. This capture predates both wire/package corrections and the two-project
filter controls. Corrected full transitions still require real-server replay.


## Resume checkpoint after execution-environment disconnect

The last locally validated implementation is `742eb25`: 148 case definitions,
135 passing harness tests, and 107 required variants (75 implemented, 23 partial,
9 absent). Its initial symbol capture and the JDK-switch capture are published.
The corrected symbol replay was running from that frozen revision when the
execution service disconnected. Its completion and final inventory are
unverified; no successful corrected-run claim is made.

The last inspected finalized reports show JVMD passing standard outline
first/repeat and added-declaration cases. JDTLS's corrected first/repeat outline
passes semantic replies but retains a shutdown failure. Its added-declaration
case had no failed semantic operation when last inspected, but was not finalized.

Resume by recovering `symbols-corrected-replay` and its raw console output if
the workspace is available. Preserve any incomplete capture. The selected plan
contains standard outline, extended outline, symbol resolve and filter-control
cases, each in first/repeat, new and renamed variants, plus the older
NAV-03/search-filter case. Inspect final reports and package the original sealed
evidence before changing the frozen replay checkout. If the capture is lost or
incomplete, execute that selection again from a clean published revision.

Next development work is the missing DIA-01 valid-source and provider-edit
variants. Their proposed observer must require exact current document versions,
retain rejected publications, and distinguish an empty unchanged-caller
publication (which does not identify the provider generation) from a direct
request's incorrect response. A unique new String-to-int mismatch at the
responsible caller invocation should be backed by independent before/after
compiler witnesses. No diagnostic implementation or test result is claimed:
the attempted local patch failed when the environment went offline. Inspect for
partial local files before resuming that edit.

The remaining specification gates and public-comparison prohibition still
apply. The draft is unfinished.

## Recovery and diagnostic publication oracle

The replacement execution environment did not retain the interrupted corrected
symbol capture. The branch was restored from `8319fef`; all 135 existing tests
passed. A fresh corrected replay now runs in a separate frozen checkout at that
revision, using the pinned JDTLS archive and a locally rebuilt pipe adapter.
The interrupted capture remains unverified.

The diagnostic oracle now distinguishes a current caller version from a current
provider generation. After a provider-only edit, empty and warning-only caller
publications remain pending. Admission requires the unique String-to-int error
at the declared invocation, an exact caller version, a proven incarnation and
a publication within the declared time window. Wrong current-version errors
are incorrect; unrelated, stale and late publications cannot satisfy admission.
Versionless evidence remains unavailable or ignored under the shared contract.
Eight adversarial tests cover these rules, including malformed warnings and
UTF-16 boundaries; all 143 harness tests pass.

This checkpoint adds the oracle, not executable diagnostic cases. Variant
counts remain 75 implemented, 23 partial and 9 absent until observer journals,
independent compiler witnesses and complete cases are wired and tested.

## Current-version valid and provider-edit diagnostic cases

`DIA-01/valid` and `DIA-01/provider-edit` now execute the diagnostic oracle.
Both use an independent compiler witness prepared before server launch: the
original provider and caller compile, while changing only the provider return
type produces exactly the declared String-to-int caller error. The actual
local JDK 25 compiler reproduced those two outcomes with release 17. Full JDK
inputs, source hashes, compiler commands and results are recorded.

The provider case first proves a valid current-version caller publication, then
changes only the provider's open buffer. Caller bytes, version, incarnation and
all disk sources stay fixed. Empty/warning-only caller publications remain
pending; the exact new mismatch proves the provider generation. No readiness
request is sent between the edit and admission. A wrong current-version error
is terminal and remains incorrect even if a later publication is correct.

Every considered publication has a raw-event identity and replayable disposition.
The artifact reducer reconstructs buffers from didOpen/didChange, replays all
admission decisions, checks the trigger/deadline and rejects omitted evidence.
Timeout, missing version evidence, publication exhaustion and server exit produce
explicit operations. Notification transitions are labelled separately from
request measurements; no request latency or second publication is fabricated.
The review packager preserves observer journals and compiler witness files.

All 153 harness tests pass; catalogue validation remains 106 target APIs in
28 families. There are now 150 cases and 77 implemented, 23 partial, 7 absent
variants. These are source implementation counts. Real diagnostic pilots and
the remaining acceptance gates are still required. See `diagnostic-cases.md`.

## Recovered symbol replay and multi-root initialization correction

The fresh corrected symbol capture at `8319fef` completes all 26 selected
server/case pairs: 7 pass, 3 incorrect, 6 protocol errors and 10 unsupported.
All 62 semantic operations outside the three filter failures pass. This includes
standard/extended outlines, new/renamed declarations and named-enum original-item
resolution. Six JDTLS outline cases retain their nonzero shutdown exits.
The original reducer has no integrity issues, source inputs remain stable, and
the complete original checksum inventory verifies during review packaging.

The three filter failures expose another harness setup mistake: the secondary
project is absent from global search. Pinned JDTLS `BaseInitHandler` reads the
URI-array `initializationOptions.workspaceFolders`, falling back to rootUri;
sending only the standard initialize field imports one project. Initialization
now sends both representations for JDTLS, and the test covers both server paths.
The failed capture remains in `benchmarks/evidence/symbols-corrected-2026-09-27/`;
its results are not retrospectively healed. The multi-root correction requires
a new filter-control replay. All 154 harness tests pass.

## First compiler-backed diagnostic pilot

The frozen four-case pilot at `5639add` records one pass, one timeout and two
unavailable-evidence outcomes. JVMD passes valid-source exact-version admission.
Its provider-only edit does not produce the required fresh caller mismatch within
the declared 30-second window. JDTLS's versionless caller publications cannot
prove exact current-version validity; both cases stop at baseline admission, so
no JDTLS provider-edit outcome is inferred. All four shutdowns complete cleanly.

`benchmarks/evidence/diagnostics-2026-09-27/` preserves compiler witnesses, raw
publications and replayable observer decisions. Original reduction reports no
integrity issues, and all originally sealed payload hashes still match. Packaging
discovers six additional runtime files and preserves them explicitly as unsealed
evidence. The capture is not described as fully sealed. The public-comparison
prohibition and all remaining specification gates are unchanged.

## Corrected two-project filter capture

The focused replay at `3993d75` passes all three JDTLS filter-control cases:
first/repeat, added declaration and renamed declaration. All 46 operations pass,
including the complete primary declaration probes followed by twelve controls
per case. Global, primary-project, secondary-project, source-only and bounded
result queries now prove their inclusions and exclusions against both a present
foreign source and a real binary. Changed declarations satisfy immediate and
settled probes, and all three shutdowns are clean. No initial failed capture is
rewritten or silently reclassified.

`benchmarks/evidence/symbol-filters-2026-09-27/` preserves the raw replay. Original
reduction has no integrity issues and source hashes stay fixed. Every originally
sealed artifact still verifies, but packaging discovers additional runtime files
and preserves them with failed-inventory status. This is semantic diagnostic
evidence, not a fully sealed or public comparative performance result.

## Independent two-project build variants

Eight new BLD-01 cases cover workspace and selected-project routes for full,
unchanged incremental, changed-source and compile-error builds. Each case resets
both projects. Automatic builds are disabled. Unchanged/changed cases establish
an explicit full-build baseline; the changed case then edits one disk source
and immediately requests an incremental build. Validation executes the server's
class file, requiring value 13 instead of 7. Mere class-file existence is no
longer sufficient. Workspace builds also verify the peer's distinct value 73.

Independent prelaunch javac/runtime witnesses verify all three valid behaviours
and the unique primary/peer compile errors. Project builds preserve unselected
output bytes; all target runs check complete source membership and unrelated
source hashes. Unchanged bytes are not interpreted as zero compilation work.

After the target operation, positive scope controls place a known source error
outside a selected successful project. Building that project must succeed;
building the whole workspace must report the error at its exact URI/range. The
compile-error variant uses its initially broken primary project as the excluded
error and builds the valid peer. Scope controls cannot warm the preceding target.
Raw notifications support diagnostic assertions without invented notification
latency, and no editor buffer is opened to change build behaviour.

All 167 harness tests pass, including wrong-scope, stale-output and wrong-status
injections. Actual compiler preparation verifies the three runtime values and
two unique failures. Source inventory is now 158 cases and 81 implemented, 20
partial, 6 absent variants. These implementation counts await real build replay.
The complete experiment and public-comparison acceptance gates remain open.

## Build pilot and retained recovery attempts

The sixteen-case frozen matrix at `75be95e` records two incorrect cases, six
protocol errors and eight unsupported routes. Both JDTLS immediate changed-source
builds return success while the actual class still executes 7 instead of 13.
Full, unchanged and error cases pass semantic, diagnostic and scope checks, then
retain shutdown failures. JVMD declares all eight extension routes unsupported.
Raw evidence is in `benchmarks/evidence/builds-initial-2026-09-27/`. Original
reduction has no integrity issues; additional runtime files discovered during
packaging remain explicitly unsealed.

Changed build cases now use the existing predeclared immediate/retry/settled
policy. The wrong immediate build remains incorrect after successful recovery;
two new tests enforce that for both routes. Repeated explicit builds can advance
the server's work, so this is probe-driven recovery, not passive readiness. All
169 harness tests pass. Real recovery replay remains a separate follow-up.

## Shutdown blocking-path probe

A separate instrumented reproduction at `75be95e` passes build semantics, answers
shutdown in 2.859 ms and exits with code 1 about 60.064 seconds after exit is
notified. The harness does not force-kill it. Two SIGQUIT snapshots at roughly
two and seven seconds show Equinox's framework-stop thread waiting in
`JobManager.shutdown` at `Thread.join`. The first has active JRT indexing; the
second has two Java indexing threads waiting on the same IndexManager while
framework shutdown still joins. This is not evidence that indexing occupies
the entire minute.

Pinned JDTLS schedules System.exit(1) one minute after exit, consistent with the
observed terminal event. The observed blocking path is established for this
probe. A worker restarting during shutdown is a candidate mechanism from the
binary's reset/loop/shutdown code, not a proven triggering interleaving.
`benchmarks/evidence/shutdown-index-2026-09-27/` retains raw stdout, full stacks,
protocol/process records, exact bytecode and the runnable probe. Instrumentation
can contaminate stdout framing after exit; it is separated from normal benchmark
evidence. Two additional runtime files remain explicitly unsealed.

## Changed-build recovery and compiled-artifact observation boundary

The focused replay at `3db59d1` retains the wrong immediate class behaviour in
both routes, then records a correct second attempt, a correct settled check and
successful scope controls. Both cases remain incorrect and retain shutdown
failures. `benchmarks/evidence/build-recovery-2026-09-27/` preserves every attempt;
the complete original inventory verifies and the reducer has no integrity issues.

Review exposed a separate measurement limitation: previous build validation
executed a live class file after the response. A changing output could therefore
move the observation boundary or disagree with a later file hash. Those historic
captures do not prove the class contents at response receipt. They are preserved
with that limitation, not retrospectively upgraded.

New validation copies each class once, records its read interval and hash, then
executes that preserved copy and verifies it remains unchanged. Operations link
the class snapshot and expose a distinct artifact-observation interval alongside
the RPC interval. The reducer checks the boundary, witness link and actual saved
bytes, and refuses a successful new build operation without the declared snapshot.
Review archives include the copied class files. All 171 tests pass, including
changing live output during validation and tampered observation timing/hashes.
Source variant counts remain 81 implemented, 20 partial and 6 absent; real snapshot
replay and the full experiment gates are still separate obligations.

## Immutable build-output replay

The replay at `1d01e5c` records immutable class snapshots for both changed-build
routes. Each retains the wrong immediate value 7, then the correct retry and
settled value 13; both retain nonzero shutdown exits. The complete original
checksum inventory verifies, source drift is false and the reducer reports no
integrity issues. An artifact-only audit of the extracted review also passes;
changing one copied class makes the reducer reject its observed-byte witness.

`benchmarks/evidence/build-snapshots-2026-09-27/` preserves the completed capture
and the earlier interrupted capture separately. The interrupted capture has no
original seal and an unfinalized second report; no terminal event was recreated.
These are single-block diagnostic observations. RPC completion and post-response
class observation retain separate boundaries, and retry-driven recovery does not
establish passive readiness or public comparative performance.

## Folder and project import-cleanup scope

Two independent cases now require positive presence of every included and excluded
candidate, exact import-only edits, byte-preserved class bodies and outside-scope
files, independent compilation/runtime validation and an exact repeat. The folder
case includes nested packages plus prefix/substring lookalikes; the project case
includes another imported project. `import-scope-cases.md` defines the boundaries,
including client edit callbacks within the command round trip. All 179 harness
tests pass; this source checkpoint still requires its real-server replay.

The real scope replay at `9328669` passes JDTLS project cleanup and records an
empty, incorrect folder edit despite positive candidate presence. Both JVMD
routes are unsupported; all four processes shut down cleanly. Full checksum
inventory and reducer integrity verify. The preserved dispatch-bytecode evidence
supports a file-before-folder lookup explanation, explicitly an inference rather
than an instrumented branch trace. See `import-scope-cases.md` and the raw capture
in `benchmarks/evidence/import-scope-2026-09-27/`.

## Dependency-source and attachment variants

Ten independently reset cases now cover class-file/document content without
attachment, with matching attachment, and after attachment replacement; an
unattached decompiler case; and all three metadata states. Content and metadata
requests have separate timers. Updated cases retain immediate, retry, settled and
repeat observations. Exact archive paths, authored source comments and complete
attached source distinguish generations; binary/archive bytes and caller state
remain fixed. Unattached text uses semantic declarations rather than a decompiler
text golden. Fixture compilation records tool identities, commands and JAR hashes.

All 184 harness tests pass. A real prelaunch fixture build verifies six archives.
The first real replay exposed a harness error: binary definitions were required
to end in `.java`, while the actual opaque JDTLS URI correctly ends in `.class`.
The corrected check names the exact JAR/class and preserves its server handle.
The four failed setup attempts are retained in
`benchmarks/evidence/dependency-baseline-2026-09-27/`; additional unsealed runtime
files retain failed-inventory status. Corrected supported-server replay is pending.

## Optional completion command and clean code-action context

Selection now inspects the original semantic completion item. If its optional
command is absent, the case records `not_applicable`, resolves the same item for
its fixture documentation, and sends no fabricated selection request. Artifact
validation checks the original command-free reply, selected item, unchanged
document state and actual resolve arguments. The variant ledger accepts this
disposition only for the explicitly optional selection witness. An offered command
that fails remains a failure, including protocol and shutdown failures.

The clean-context code-action case independently compiles its source, confirms a
valid server hover, then accepts either protocol-defined empty form (`null` or
`[]`). It preserves all source states and refuses edit callbacks. Full harness
validation passes 190 tests, including actual runner cases for offered, unoffered
and failing selection commands.

The expanded dependency replay at `a62ce1a` exposed a second harness error: generic
location normalization decoded JDTLS's opaque class-file handle. Content and
metadata requests consequently used an altered URI. The fix preserves the
original URI exactly, with a regression using its real escaped form. The original
20-case capture remains in `benchmarks/evidence/dependency-handle-2026-09-27/`;
its complete inventory verifies. It is not evidence of a server content defect.


## Corrected dependency replay and complete refactor effect checks

The capture at `6a13a09` passes all ten JDTLS dependency cases after preserving
its original escaped binary handles. JVMD reports ten unsupported routes. JDTLS
completion selection passes; JVMD correctly records an unoffered optional
command with the original-item resolve witness. The JDTLS no-action requests
pass, but shutdown exits 1 without a kill, so that case remains a protocol error.
All 24 reports finalized, the complete original inventory verifies, and reducer
integrity issues are empty. Raw review evidence is preserved in
`benchmarks/evidence/dependency-corrected-2026-09-27/`. This is one-block semantic
evidence, not a public performance comparison.

Code-action discovery now has an independent refactor case. It selects local
extraction by complete edit effect, preserves original items for resolution,
and rejects unrelated edits without relying on action titles. Extension
refactors now check exact source membership and untouched document states.
Signature refactoring changes both the name and parameter order, checks every
caller and executes independent probes. Extraction binds the selected expression
to its returned local. Move checks both callers and the selected package; interface
extraction checks precisely the selected member and preserved implementation.
All four routes compile and execute independently authored behaviour checks.

Five adversarial tests reject altered literals, wrong parameters/callers,
unrelated edits or incarnation changes, extra/missing sources, wrong extracted
expressions and wrong return locals. All 195 harness tests pass. Source inventory:
167 cases and 107 variants, 92 implemented, 11 partial, 4 absent. Real refactor
replay remains required.


## Navigation movement, package-folder rename, and real refactor results

All seven NAV-01 routes now retain separate first/repeat request timings and
immediate/settled checks after source movement. Declaration, definition, type,
implementation, super-link, qualified-name and stack-frame routes each use
independently computed expected identities/ranges. Stack lookup changes the saved
source and frame line; it does not pretend an unsaved buffer is a stack artifact.

Folder rename now exercises the actual willRenameFiles request for a package
folder, applies edits and the requested resource operation, notifies didRenameFiles,
and proves exact new package paths/callers and subsequent definition targets.
An adjacent package with the same prefix remains unchanged. Every source is
compiled and a separate runtime probe checks the moved and untouched behaviour.
The client adapter expands only the requested folder operation into registered
Java files, and rejects escapes, collisions and changed destinations.

Real replay at `d7ad357` passed all five JDTLS refactor semantic/compile/runtime
checks. Three cases exited cleanly; extract-selection and extract-interface
exited 1 during shutdown without a forced kill, so both remain protocol errors.
JVMD reported all five routes unsupported. All ten reports finalized with no
reducer integrity issues and verified original inventories. The evidence is in
`benchmarks/evidence/refactor-effects-2026-09-27/`. All 197 harness tests pass;
168 cases cover 96 implemented, 8 partial and 3 absent required variants.


## Project membership and source/dependency scope

Initial project inventory now proves both main/test classification and exact
symbols from distinct roots. Add/remove workspace cases start independently and
verify both inventory and the unique second-project symbol, with byte controls.
Source-root add/remove likewise have independent initial states, one mutation,
exact source-path inventories, positive/negative symbol witnesses, and unchanged
source/document-state controls.

Main/test classpath cases now have separate source roots, output directories and
actual main/test dependency JARs. Seven independent tool invocations compile both
libraries, build reproducible archives, prove main dependency acceptance, reject
the test-only dependency on the main classpath, and accept the combined test
classpath. JDK identity, commands, source and binary hashes are retained. Exact
scope oracles reject leaked test entries, missing outputs and duplicates. Local
fixture preparation verified all seven commands; all 199 harness tests pass.

Navigation replay at `bc94bad` passed JDTLS folder rename completely. It exposed
two harness mistakes, now corrected: declaration navigation requires an inherited
declaring method, and stack mapping returns a URI string (not a Location).
Pinned server sources and original failures are preserved in
`benchmarks/evidence/navigation-folder-2026-09-27/`. This capture also retains
an incorrect immediate implementation target, empty post-movement super-links,
and separate nonzero shutdowns. It does not stand in for the corrected replay.
Source scope is now 170 cases, 102 implemented variants, 2 partial and 3 absent.


## Compiler and loose-file diagnostic modes

Four independent cases complete unchanged/changed compiler settings and
syntax-only/full loose-file diagnostics. Compiler witnesses prove the same record
source compiles at release 17 and is rejected at release 11. Loose-file witnesses
separately prove valid, semantic-error and syntax-error sources before launch.
The actual loose file is outside the sole declared source root. Syntax-only mode
must suppress the semantic error and positively detect a subsequent syntax error;
full mode must locate the exact missing-name error. Compiler changes preserve
all source bytes and versions, so a positive new language rejection is required
in addition to reported settings.

The observer and artifact-only audit retain exact version/incarnation admission,
mode-command acknowledgement, original buffers, compiler evidence and every
publication decision. Versionless evidence remains unavailable. These new cases
continue after that specific unavailable disposition to collect later declared
stages; the initial failure remains in the case outcome. Wrong current-version
answers still fail immediately. Nine new adversarial/integration tests cover mode
confusion, wrong ranges, forged compiler releases and inputs, missing mode
acknowledgements, full scenario execution and raw-journal replay. All 208 tests
pass; real mode replay remains pending. Only persisted reopen remains absent
from the 107-variant source ledger (174 case definitions).

Real replay at `f9b068e` passes main/test project classification, both real
dependency scopes, independent source-root add/remove and corrected stack mapping.
Inherited-declaration requests pass but shutdown exits 1. Workspace add and remove
each retain one stale immediate inventory before correct settled inventory and
symbol visibility. All nine JVMD routes are unsupported. All 18 reports finalized,
full inventories verify and reducer integrity issues are empty; see
`benchmarks/evidence/project-scopes-2026-09-27/`.


## Persisted reopen completes the required source variants

SES-01 now has a separate persisted-state reopen case. It verifies saved String
field semantics, applies and verifies an unsaved int overlay, closes the first
client's buffers and process, hashes nonempty retained server state twice, then
launches a new process against that exact state directory. The new client starts
without live buffers and must resolve the original saved String declaration.
The state snapshot interval is outside request timings; this proves reuse of
persisted files, not an inferred cache hit or a Unix lifecycle claim in pipe mode.

Seed and reopened sessions have independent immutable protocol/process journals,
request identities and reports. An unclean seed shutdown prevents relaunch and
remains a failure. Artifact-only validation checks both phases, state identity,
clock boundaries and original saved/unsaved/reopened source payloads. The review
packager retains both histories. Five tests run the actual runner against correct,
leaking, empty-state and failed-seed peers, and reject snapshot/answer tampering.
All 213 harness tests pass. The source ledger now accounts for all 107 required
variants in 175 cases; full execution and the acceptance gates remain separate.

Mode replay at `074041e` finalized all eight cases with a verified complete
inventory and no reducer integrity issues. All four JDTLS cases lack exact-version
admission because publications are versionless; later declared stages are still
collected. All four JVMD command routes are unsupported. Legal versionless
notifications are not labelled protocol errors. Raw histories are preserved in
`benchmarks/evidence/diagnostic-modes-2026-09-27/`.


## Persisted-state execution and retained first-transition failures

The first real reopen pilot at `fb4cdfe` passes fully on JVMD's diagnostic pipe
profile, including both clean process exits, exact retained-state snapshots and
discarded unsaved overlay. JDTLS passes the saved-source baseline but returns empty
hover content immediately after the edit and exits 1 during shutdown. It never
reaches restart; no JDTLS persistence outcome is inferred. Both finalized reports
and verified inventories are in `benchmarks/evidence/persisted-reopen-2026-09-28/`.

The seed edit now uses the existing declared immediate/settled transition policy.
A later settled result permits collection of the independent restart stage, but
an earlier failed seed operation still determines the final case failure. A new
actual-runner test proves that successful reopen cannot hide a failed immediate
seed result. All 214 TypeScript tests pass. The full process resource sampler
still returns unavailable locally despite readable child status: partial `/proc`
visibility does not establish complete CPU/RSS/I/O scope.

## Lifetime resource accounting and full replay recovery

An optional delegated cgroup-v2 collector now wraps every native daemon and
packaged bridge before exec. Descendants inherit its dedicated group. Final CPU,
block-device I/O and kernel memory-charge counters are read only after the whole
group is empty; raw files, group epoch, launch roots and entry records are retained.
The artifact audit recomputes totals and rejects missing roots, changed epochs,
active descendants, counter resets and summaries that disagree with raw evidence.
Kernel memory charges are explicitly not RSS/PSS or retained Java heap. These
counters do not establish per-role allocation or instrumentation overhead.
Without delegated controllers the result is unavailable with null totals.

Local validation: 214 TypeScript tests pass; Python runs 47 tests with 45 passes
and two explicit capability skips (process I/O visibility and delegated cgroup).
The new integration test measures an exited CPU-consuming descendant when an
actual delegated group is supplied; ordinary directories cannot imitate cgroups.
Native summaries retain the broad resource gate as incomplete even if the narrower
lifetime counter scope is measured.

The full 350-report replay at `61926e2` finalized with 333 harness errors, 11 passes,
2 incorrect results and 4 protocol errors. Its borrowed JDK path became unavailable
during collection. These results cannot establish supported-server coverage. The
original sealed capture remains intact. A new replay uses the SHA-256-verified JDK
archive extracted into this workspace and a fresh production compilation from the
same frozen revision. No result from the failed capture is overwritten.

CI at `61926e2`: Tests and Benchmarks passed. LSP scenario contract tests, image
build, legacy phase checks and artifact audit passed; native clean/traced lifecycle
and protobuf semantic steps failed and uploaded their evidence. Those failures
remain open; a passing structural audit does not turn them into semantic passes.

The same lifetime collector now covers ordinary LSP cases, including separate
seed/reopened process lifetimes. JVMD product runs register both daemon and shim;
JDTLS registers its server root. The report retains original/wrapped commands and
actual daemon termination events. Artifact-only reduction checks raw roots and
counters for both persisted phases, including failures. A subprocess regression
proves forged resource totals cannot pass reduction. All 214 TypeScript tests and
45 available Python tests pass; the same two environment capability tests skip.

Read-only CI now provisions a dedicated empty cgroup parent on its disposable
Ubuntu runner and validates accounting of a CPU-consuming descendant after exit.
It changes only its new parent's controllers/ownership, never root controllers,
limits or unrelated processes. Capability acquisition failure is saved and fails
a final gate after other diagnostics; it cannot become zero measured work.

Inspection of native CI run `36360839683` identifies the LIFE-08 failures more
precisely: both product profiles retain changed source-attachment documentation,
same-coordinate binary replacement and dependency-removal freshness failures.
The new-path and unrelated-artifact controls settle correctly. Retained attach,
normal detach, disposal/reopen, restart, pressure/recovery and Apache open pass in
both profiles. These finite cases do not prove complete work accounting or reuse.

## Controlled scaling fixtures

The suite now accepts `--scaling-axes sources,modules,artifacts,members,reach`.
These are 45 diagnostic cases alongside the unchanged 175 workbook cases, not
additional workbook coverage. Each axis has three predeclared sizes and three
independent mutation controls: identical payload, unrelated body, relevant API.
Source volume preserves the caller graph. Module count redistributes the same
padding sources. Reach varies how many of 64 fixed callers depend on the changed
provider. Member count changes enumeration size explicitly. Local artifact count
uses distinct deterministic archives with pinned timestamps. Generated source
bytes, edges, structural counts, JDK identity and archive hashes are retained.

Preparation compiles isolated source copies and dependency classes before timing.
A local generated fixture with four projects, eight artifacts, sixteen queried
members and sixteen affected callers compiled successfully. Invariance tests
check that changing one control preserves the other semantic inputs. Validation:
217 TypeScript tests pass, with 45 Python passes and two capability skips. These
checks do not establish a scaling curve or any asymptotic claim.

CI run `36362120963` exposed a delegation-boundary bug: wrapper processes started
outside the new subtree, so cgroup migration could not cross the common ancestor.
The corrected validation launcher places only its new observer process inside
the delegated parent, then drops privileges before executing the harness. Server
wrappers can then move between that observer leaf and their measured leaves.
The host root's permissions/controllers remain unchanged. Runtime validation of
this correction is pending; the failed run is not counted as resource evidence.

## Frozen independent-block experiments

`benchmarks/experiments/` now contains committed matched-product, scaling-product,
trace-overhead and status-polling plans. The coordinator invokes the existing
harnesses, alternates pair order, restores each case, retains failed blocks,
seals every attempt, and reduces immutable captures independently. It refuses a
dirty checkout or edited plan and checks source/toolchain/distribution identities
before and after collection. A real child-process regression proves a failed
first block does not erase or skip the next planned block.

The analysis uses one endpoint/state median per run and paired bootstrap intervals
across ten restored blocks. Missing, duplicated, failed or unavailable pairs cannot
produce an effect. Within-run requests cannot inflate the independent sample
count. Observer plans predeclare a 5% tolerance and separately measure trace/JFR
instrumentation and additional status polling over LIFE-01/09/10. Essential state
checks remain on both sides. CI collects these plans read-only; it does not edit
source, push code or manufacture baselines. Full acceptance remains separate.

Validation: 217 TypeScript tests pass; Python runs 53 tests, with 51 passes and the
two explicit local capability skips. CI at `d585cad` passes the real delegated
cgroup descendant-accounting test and all harness contract tests, confirming the
corrected containment boundary. Native semantic failures still remain visible.

The first scaling pilot exposed a fixture setup error: Eclipse metadata alone
cannot declare JVMD's Maven source roots or dependencies. Its null JVMD answers
are not scaling evidence. The corrected generator emits a real Maven reactor,
matching Eclipse source projects, identical dependency archives and a seeded local
repository. The fixed aggregator is counted separately from variable source
projects. A four-project/eight-artifact fixture compiles independently. The original
pilot remains preserved; corrected supported-server replay is still required.

## Verified native lifetime counters

At `d585cad`, both clean and traced product CI captures have measured lifetime
counters for fresh, restarted and Apache epochs. All six groups contain the
launched daemon/bridge roots, are empty at final read, and are removed cleanly.
Local replay of the raw resource records and both native review audits succeeds.
`benchmarks/evidence/native-lifetime-2026-09-28/` preserves a resource-only subset
and exact source-artifact identity; its omissions are explicit. These totals
include unsuccessful freshness attempts and are not an overhead comparison.

Native summaries now retain daemon identity separately from the kernel group
epoch, and artifact audits compare every summary row with its raw epoch file.
Experiment collection also retains the committed plan's exact bytes; reduction
checks actual invocation order/arguments as well as the declared schedule.
CI preserves active independent-block experiments across subsequent pushes.

The failed full replay is now packaged separately under
`benchmarks/evidence/full-replay-toolchain-failure-2026-09-28/`. Its complete
original inventory verified; its 333 harness failures are not server findings.
The restored full replay and corrected Maven scaling replay continue from frozen
checkouts. JVMD's corrected relevant-edit and unrelated-edit scaling controls pass;
JDTLS retains stale early hover results and an unclean shutdown in the first
corrected relevant-edit case. These development captures do not authorize timing
comparisons.

## Complete versus incomplete enumeration

The corrected Maven scaling pilot finished at `4c5c1f8`: all six selected JVMD
hover controls pass. The 64-member case returned 50 items with `isIncomplete:true`.
The original pilot retains its incorrect classification; the revised oracle
records this legal truncation as unavailable full-enumeration evidence. Missing
members in a declared complete list, duplicate members and forbidden members
remain incorrect. The fixed 64-member size is unchanged. Neither incomplete nor
incorrect output enters successful latency estimates. Raw replay independently
reconstructs the missing set and rejects forged unavailability.

The pilot's seven JDTLS reports retain stale early results and/or shutdown failures.
These reports are diagnostic observations, not independent scaling estimates.
The exact result inventory and a focused replay of the revised truncation oracle
remain separate from the final experiment.

A sampled process record with missing I/O fields now becomes unavailable rather
than substituting zero. Validation: all 219 TypeScript tests pass; Python runs
53 tests, with 51 passes and two explicit local capability skips.

## Native artifact semantic replay

The native validator now rechecks lifecycle witnesses against captured wire
replies in their recorded client intervals. It recomputes hover/completion, live
attachment retention, detach/disposal, dependency visibility, pressure/eviction,
recovery, idle and Apache definition assertions. Pass flags and matching summary
files alone cannot satisfy those checks. Resealed wrong-type replies and detached
witnesses are rejected. Both saved native captures at `d585cad` pass this stronger
audit while retaining LIFE-08's product freshness failures. No complete native
work/reuse claim follows from these positive semantic witnesses.

Observer-only captures now create the empty lifecycle journal explicitly; their
selected legacy operations are stored in per-case files even when none use the
transition journal. This closes a missing-artifact error in the collector path.
Validation: 219 TypeScript tests and 53 available Python tests pass; two Python
capability checks remain explicitly skipped locally.

## Independent blocks and portable reduction

The first ten-block trace and status experiments both finished all 20 planned
configuration runs at `4c5c1f8`. All 40 lifetime scopes measured successfully, but
every child artifact audit rejected the missing empty lifecycle journal. Those
experiments remain failed captures, not overhead evidence. The exact reports are
in CI run `36363483278`, artifact `10946589156`; its SHA-256 is
`b381fb0682dc7c7d1d0e852f9d409776cf86a754589cde6c08fd6ee8c8cb9987`.
The corrected observer-only local pipe capture finishes LIFE-01/09/10 and passes
artifact semantic replay; product resource/overhead verification remains separate.
Idle means no new client target requests or explicit polling; scheduled background
diagnostics remain measured and are permitted. It does not mean server quiescence.

Matched/scaling plans now declare ten separately restored job VMs, with one shared
built distribution. The collector preserves the original numbered order when
running a single declared block; the merger independently reduces all ten sealed
captures. Missing/duplicate blocks, drift and failed pairs disable estimates.
Downloaded artifacts validate against the original recorded output paths. The
read-only workflow retains every attempted block and separate review reports.
It never writes source or pushes commits. No comparative claim is enabled.

Validation for distributed collection: 58 Python tests run, 56 pass and the same
two local capability tests skip. Tests cover relocated captures, original server
order, missing/duplicate block shards, a failed pair, changed JDK identity and
actual child-process failures. The stable-checkout TypeScript rerun passes all
219 tests. An earlier concurrent documentation edit was correctly rejected as
source drift in two subprocess tests; that failed invocation is retained locally.

## Independent invalidation controls and preserved pilots

Seven additional diagnostic cases cover provider body edits, more-specific
overload addition, namespace shadow insertion/removal, unrelated-name insertion,
ordered duplicate-class dependencies and identical classpath metadata. Each starts
from an independent fixture and preserves unchanged caller bytes. See
`invalidation-cases.md` for questions, exact witnesses and evidence limits.
Source-level tests reject a stale same-name overload and the wrong shadowed type.
These controls close a source-coverage omission; supported-server replay follows.

`benchmarks/evidence/scaling-pilots-2026-09-28/` retains the invalid original layout,
corrected Maven pilot and explicit-truncation follow-up separately. The latter
preserves two unsealed Equinox temporary files as an inventory failure. None of
these captures is a performance experiment. The complete local observer
coordinator capture and reproducible report are under
`benchmarks/evidence/observer-coordinator-2026-09-28/`; both configurations pass
semantic replay with unavailable local lifetime counters.

## Ordinary product AOT evidence

The LSP launcher now copies the shipped launcher's AOT log after measured work
and daemon termination. Its disposition is replayed from those bytes. A rejection
takes precedence over an earlier archive-open message, and cache-file presence
never establishes acceptance. This adds no pre-query status call. Native product
captures already retain runtime AOT state; older ordinary captures remain labeled
with the evidence actually present in each bundle.

AOT validation: all 224 TypeScript tests pass. A short product LSP CI capture
records the shipped launcher log and validates it alongside ordinary request
results; it does not run a new independent performance collection.
