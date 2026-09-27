# Organize-imports scope cases

`FMT-02/imports-folder` and `FMT-02/imports-project` reset independently. They
exercise `java.edit.organizeImports` at a folder URI and project URI respectively.
The pinned JDTLS implementation supports both targets; a single-file request does
not establish either scope.

| Candidate | Folder selection | Project selection |
|---|---|---|
| `bench.selected` | Change imports | Change imports |
| `bench.selected.child` | Change imports | Change imports |
| `bench.selectedExtra` | Preserve exactly | Change imports |
| `elsewhere.bench.selected` | Preserve exactly | Change imports |
| `bench.unselected` | Preserve exactly | Change imports |
| Another imported project | Preserve exactly | Preserve exactly |

Each candidate has an unused `Set` import, a used `List` import and a distinct
executable method. An exact symbol search proves all six candidates are present
before the target command. This control prepares the search/index state; these
cases do not claim cold command performance. Both standard workspace folders and
JDTLS's pinned initialization option identify the two projects.

Primary candidates are open buffers. The compiler first validates the unchanged
fixture. The command must supply one atomic workspace edit. Its transaction must
preserve source membership, remove only the selected unused imports, retain the
used imports and preserve every class-body byte, including literal whitespace.
The applied buffers must match that transaction. Sibling and foreign source
bytes remain unchanged. All disk sources stay fixed; buffer synchronization is
recorded through normal `didChange` notifications.

Independent compilation and execution verify every primary candidate's distinct
value after application. A separate repeat must preserve the exact buffer state,
including excluded document versions. No-op edits cannot satisfy the first-use
oracle, and a later correct reply cannot erase an earlier scope failure.

The command can request `workspace/applyEdit` before replying. Its recorded RPC
interval includes the normal client callback round trip. Post-response scope,
compiler and runtime validation are outside that interval; it is not presented
as isolated server computation. The raw callback, edit, replies, mutations and
client states are retained for review.

Source review: JDTLS `08eafe6`,
`org.eclipse.jdt.ls.core/internal/commands/OrganizeImportsCommand.java` (repository
path includes `src/org/eclipse/jdt/ls/core`), Git blob
`405b15eac093194c5155d9e9275eafa628608a03`. The package collection predicate is a
reason to include both prefix and substring lookalikes, not evidence of a runtime
failure by itself. Eight adversarial tests cover scope expansion, omitted targets,
foreign edits, resource deletion, literal changes and non-idempotent repeats.
