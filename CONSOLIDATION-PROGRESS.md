# File semantic contribution consolidation

Source of truth: the current implementation task requested in chat. Older architecture/checklist documents are historical context only and do not define this branch's scope.

Base: `03591aeef674b67f131065048a50543d78aca855` (current `main` at branch creation)
Branch: `refactor/file-semantic-contribution`

## Objective

Consolidate per-file semantic facts behind one authoritative typed `FileSemanticContribution` and delete duplicate semantic ownership rather than layering another representation on top.

The first target is the duplicated persistence/invalidation representation around:
- source/content identity;
- API identity;
- dependencies;
- exported names;
- unresolved names.

Navigation and diagnostics may consume the contribution, but derived indexes/results remain derived rather than becoming additional semantic authorities.

## Required invariants

- Existing public behavior and conservative invalidation correctness stay unchanged.
- An unchanged semantic query performs zero new javac analysis.
- A body-only edit reanalyses only the changed file when dependency semantics permit it.
- An API edit reanalyses the actual reverse-dependency closure.
- No whole-module semantic materialisation is introduced into a one-file update.
- New abstractions must replace old ownership. A phase that leaves both authoritative representations in place is incomplete.
- Production LOC and number of semantic representations are reported before/after.
- Benchmark before production edits, make one consolidation, benchmark after using the same suite.

## Baseline

Pending the branch's first pull-request Checkpoints run. The branch is currently production-identical to `main`; only this tracking document is added.

## Deletion ledger

### Planned additions
- one typed authoritative file semantic contribution (location chosen after code audit);
- narrowly scoped codec/adapters only where unavoidable.

### Planned removals
- duplicate Rocks semantic `FileInput` / `Stored` representations where the canonical contribution can be consumed directly;
- duplicate source/API/dependency/export/unresolved identity ownership;
- redundant conversions between equivalent per-file semantic shapes;
- module-wide reconstruction performed solely to update one file, if it can be replaced without weakening correctness.

## Acceptance

The branch is accepted only if:
1. focused and repository correctness gates pass;
2. before/after benchmark evidence is recorded;
3. semantic ownership is reduced rather than duplicated;
4. production code complexity/LOC is reduced or any unavoidable increase is explicitly justified by deletion of a larger responsibility;
5. the PR documents exact commits and measurements.
