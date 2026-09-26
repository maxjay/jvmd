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
| A01 | blocked | Local restoration passes; verify connector repository round trip |
| A02 | fail | Complete executable coverage for supported targets; no unimplemented target may be classified unsupported |
| A03 | pass for shared admission contract; broader cases pending | Shared vectors and cross-file stale-result subprocess test |
| A04 | pass locally | Actual runner and comparator reject injected wrong output after saving artifacts; integration workflow still to run |
| A05 | blocked | CMP-01 variants are implemented; full supported-server execution and oracle review remain |
| A06 | blocked | Raw histories and series plans exist; final artifact validator remains |
| A07 | blocked | Retained-state regression reproduced/fixed; normal detach/disposal/restart/machine matrix remains |
| A08 | blocked | Existing semantic proof tests pass; full benchmark mutation/ownership matrix remains |
| A09 | blocked | Request and transition intervals exist; final causal reconstruction remains |
| A10 | blocked | Invalid zero-work claims removed; complete scoped native evidence still to collect |
| A11 | blocked | Local process metrics inaccessible; pressure/reclamation/observer experiments remain |
| A12 | blocked | Fixture hashes and pipe source checks exist; full experiment inventories remain |
| A13 | blocked | Product/direct/pipe profiles separated; normal product AOT acceptance unavailable locally |
| A14 | blocked | No public comparative performance claim; independent blocks and uncertainty analysis remain |
| A15 | blocked | Raw artifacts written; independent reducer and final validation remain |
| A16 | blocked | Historical long-open evidence preserved; native causal investigation remains |
| A17 | pass for workflow/publication method | CI push removed; branch changes are published through the GitHub connector |
| A18 | pass for isolated retention fix | Before/after reproducer logs and 32 passing targeted production tests; later changes require fresh validation |

The full specification remains the acceptance contract. This file must be updated
with final evidence and dispositions; an intermediate draft is not task completion.
