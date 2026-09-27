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
| A07 | blocked | Retained-state regression reproduced/fixed; normal detach/disposal/restart/machine matrix remains |
| A08 | blocked | Existing semantic proof tests pass; full benchmark mutation/ownership matrix remains |
| A09 | blocked | Request and transition intervals exist; final causal reconstruction remains |
| A10 | blocked | Invalid zero-work claims removed; complete scoped native evidence still to collect |
| A11 | blocked | Local process metrics inaccessible; pressure/reclamation/observer experiments remain |
| A12 | blocked | Fixture hashes and pipe source checks exist; full experiment inventories remain |
| A13 | blocked | Product/direct/pipe profiles separated; normal product AOT acceptance unavailable locally |
| A14 | blocked | No public comparative performance claim; independent blocks and uncertainty analysis remain |
| A15 | partial | Artifact-only reducer reproduces tested summaries and rejects tampering/interruption/concealed failures; full native bundle reduction and final gate remain. |
| A16 | blocked | Historical long-open evidence preserved; native causal investigation remains |
| A17 | pass for workflow/publication method | CI push removed; branch changes are published through the GitHub connector |
| A18 | pass for isolated retention fix | Before/after reproducer logs and 32 passing targeted production tests; later changes require fresh validation |

The full specification remains the acceptance contract. This file must be updated
with final evidence and dispositions; an intermediate draft is not task completion.

## Checkpoint: 2026-09-27

93 executable case definitions now map to 105 of the 106 target API entries.
This is implementation coverage, not a statement that 105 APIs passed. Protobuf
source generation (API-043) still needs its real prepared Gradle/protoc fixture.
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
