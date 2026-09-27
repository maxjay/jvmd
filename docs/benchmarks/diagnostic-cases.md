# Diagnostic publication cases

These cases measure completion of an asynchronous diagnostic transition. They
do not assign request latency to a notification. The send of didOpen or the
provider didChange starts the interval; receipt of the first correctly versioned,
semantically current publication ends it. Validation time is outside that interval.

| Case | Trigger | Required evidence | Ambiguous evidence |
| --- | --- | --- | --- |
| DIA-01/valid | Open independently valid caller | Exact caller version/incarnation; well-formed diagnostics with no errors | Versionless empty publication cannot prove current content |
| DIA-01/provider-edit | Change only provider return type from int to String | Previously valid caller, unchanged caller bytes/version, exact new String-to-int error at its invocation | An empty or warning-only caller publication cannot identify the provider generation |

Before launch, javac validates the original pair and rejects the changed pair
with exactly one error in the caller. The harness records the full JDK inventory,
commands, source hashes, statuses and compiler output. Compilation uses release
17, disables annotation processing and removes inherited Java option/classpath
overrides. These compiler checks prepare an oracle; their time is not charged to
the server transition. OS cache state is uncontrolled.

The publication policy is frozen before server launch. Its deadline is the case
timeout measured from the trigger, with at most 1,000 examined publications.
Unrelated files and old versions are retained but cannot satisfy admission.
Missing-version evidence remains unavailable unless a later exact-version
publication establishes the required witness. A current-version wrong error is
incorrect immediately and is never erased by a later correct publication.

The provider case does not send an extra validate request, save the provider,
change the caller, or poll an editor endpoint to make diagnostics appear. It does
not demand a second correct publication: an asynchronous producer may publish
once. It records an explicit timeout, protocol failure or unavailable-evidence
outcome when admission cannot be established.

`diagnostic-observations.jsonl` preserves starts, every examined publication and
termination. The reducer reconstructs source buffers from raw client sends and
re-evaluates publication decisions from raw server receipts. It rejects omitted,
reordered or substituted observations and synthetic request timing. Receipt
before the deadline remains valid if the observer itself resumes after it.

Run the published cases from a clean frozen checkout, using the suite's explicit
server, launch profile, tool paths and output-directory arguments. Select
`--only DIA-01/valid,DIA-01/provider-edit`. A pipe pilot is diagnostic evidence;
it does not establish product daemon lifecycle or comparative performance.

Loose-file syntax-only/full scope variants remain separate outstanding cases.
