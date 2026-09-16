# JVMD Diagnostics Redesign Progress

Implementation branch: `diagnostics/incremental-state`
Base: `main` at `5cbf5bb13051d76f52765018bb3881025f313d18`

## Acceptance invariant

Repeated unchanged `diag.get` must prove:

- `javac_queries = 0`
- `files_reanalysed = 0`
- no unnecessary synchronous source-index writes
- diagnostics remain correct
- pagination over an unchanged cached snapshot does not trigger semantic analysis

## Original benchmark / baseline

The architecture document records the pre-redesign observed benchmark as:

- fresh workspace diagnostics: approximately **26.8 s**
- repeated workspace diagnostics: approximately **8.45 s**

These are the historical/design baseline. A branch-local measured baseline will be captured after Phase 1 instrumentation is in place, before optimization phases are marked complete.

## Current phase

**Phase 1 — Instrument the current diagnostic path**

Goals:

- account for `diag.get` total time and major sub-phases;
- expose javac query counts/duration;
- expose files analysed/reused and cache hits/misses;
- expose workspace/Maven refresh and source enumeration work;
- expose analyzer/context preparation and source-index publication work;
- add focused tests for the counters;
- capture the current benchmark before behavioral optimization.

## Completed phases

None yet.

## Checkpoints

### Checkpoint 0 — Branch and implementation log

Commit: _this commit; fill with SHA in the next progress update_

Change:

- created the dedicated implementation branch;
- created this progress log as the persistent source of truth;
- recorded the historical benchmark and the architectural acceptance invariant.

Files/classes changed:

- `DIAGNOSTICS-PROGRESS.md`

Tests added:

- none

Test results:

- not applicable

Performance measurements:

- historical/design baseline only: fresh ~26.8 s, repeated ~8.45 s

Javac query counts:

- not yet instrumented

Files analysed vs reused:

- not yet instrumented

Cache hit/miss information:

- not yet instrumented

Known issues / regressions:

- no code changed yet;
- branch-local benchmark still needs to be measured after instrumentation.

Next checkpoint:

- instrument `diag.get`, `Analyzer`/`CompilerPool`, request-level workspace preparation, and index publication timing/counters without changing diagnostic semantics.

## Repository observations

The current implementation matches the design document's main diagnosis:

- whole-workspace `diag.get` enumerates all source files and calls `analyzer(...).diagnostics(...)` for each file;
- `Application.analyzer(...)` refreshes Maven/resolution state and reconstructs module/compiler context for each file-level call;
- a single session-owned `Analyzer` is repeatedly reconfigured for different module contexts;
- the analyzer path can synchronously publish source state into the index;
- `WorkspaceBindings` is a separate workspace-wide snapshot and remains useful for workspace semantic/navigation operations.

The implementation will therefore adapt the document to the actual repository rather than introducing a parallel duplicate semantic engine.