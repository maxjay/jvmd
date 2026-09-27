# Benchmark implementation: work in progress

This branch executes the worker specification. It is a draft, not a declaration
that the full catalogue, lifecycle experiment, or performance comparison passed.
The starting revision is `d7dbc57bfbe15ee97eecc9b94d185922fd1cff07`.

Latest implementation inventory: 158 case definitions, 171 passing TypeScript
harness tests, and 107 required variants (81 implemented, 20 partial, 6 absent).
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
