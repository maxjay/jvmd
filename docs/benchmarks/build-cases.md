# Independent build scope experiments

BLD-01 now has eight independent two-project cases. The workspace and selected
project routes each cover the four states below. Both projects and server state
reset between cases; automatic builds are explicitly disabled.

| State | Setup before target | Target | Required result |
| --- | --- | --- | --- |
| Full | Fresh valid projects | Full build | Success and executable current class output |
| Unchanged | Explicit successful full baseline | Incremental build with identical sources | Success, correct behaviour and unchanged output bytes |
| Changed | Explicit successful full baseline, then one source edit | Immediate incremental build | Success and runtime value 13 replacing 7 |
| Error | Unique invalid primary source prepared before launch | Full build | Error status and one exact missing-symbol diagnostic |

Request send to response receipt is the build interval. Class-byte observation starts after response receipt and has its own recorded
read interval. Validation executes an immutable copied class, not the live output
folder. Its child JVM runs after that snapshot and does not extend the request
interval. RPC duration, trigger-to-artifact-observation time and validation work
are separate; observing correct bytes later does not prove they were present
when the reply arrived. Output hashes
show content equality; they never establish zero compiler work.

Before launch, a separately identified javac compiles the baseline and changed
primary source and the distinct peer source. Their executable results must be
7, 13 and 73. It also rejects primary and peer sources containing different
unique missing symbols, with exactly one error each. Commands, full JDK input
inventory, source hashes, outputs and statuses are preserved in
`build-compiler.json`. No helper compiler populates either server output folder.

The target operation checks source membership and non-target bytes. Selected
project builds preserve the peer's output bytes. Workspace builds validate both
projects' executable outputs, including the valid peer in an error case.

Scope controls run only after the target. A known error is excluded by a build
selecting the other valid project, then included by a workspace build. Each
error must name its unique symbol at the exact source URI and span. In the error
variant, the initially invalid primary project is excluded by selecting the
valid peer. Other variants introduce the peer error only after target validation.
The positive error witness identifies the diagnosed source content; a versionless
empty notification is never used to establish diagnostic freshness.

The pinned route semantics are defined by
[BuildWorkspaceHandler at 08eafe6](https://github.com/eclipse-jdtls/eclipse.jdt.ls/blob/08eafe6/org.eclipse.jdt.ls.core/src/org/eclipse/jdt/ls/core/internal/handlers/BuildWorkspaceHandler.java).
Standard and JDTLS-specific workspace-folder initialization both declare the
two roots. No readiness request or metadata poll intervenes between the
changed-source trigger and its target build. Unsupported extension routes retain
capability evidence; a supported route that fails its oracle stays failed.

Select `BLD-01/two-project-workspace-full` (and the corresponding unchanged,
changed, error or projects selectors) from a frozen published checkout. These
are correctness/diagnostic cases. They do not establish comparative performance,
complete resource costs or accepted product AOT.

Changed-source recovery is now observed with the suite's predeclared transition
policy: immediate build, bounded retries after failed output, then one additional
correct settled build. Every failed result remains in the case. These extra build
requests can themselves advance compilation; the recovery interval is explicitly
probe-driven and is not a passive background-readiness measurement. The initial
`75be95e` capture stops after its wrong immediate result and remains unchanged.

Each snapshot is included in the review archive and linked to its operation. The
reducer verifies the saved byte hash, the post-response observation interval and
the separately computed artifact transition time. Earlier build captures retain
their live-output validation limitation; they are not retroactively assigned
snapshot evidence.
