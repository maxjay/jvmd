# Product launcher: actual AOT disposition

The ordinary product `DOC-01/repeat` case passed in
[run 36366608337](https://github.com/maxjay/jvmd/actions/runs/36366608337).
`capture.zip` is the complete original artifact, not a review subset. Its SHA-256
matches GitHub's recorded digest, ZIP CRC passes, and local artifact-only reduction
validates every original bundle hash, protocol journal and lifetime counter scope.
`manifest.json` records both the PR head and the actual tested merge revision.

**The launcher requested AOT, but the archive was absent and the JVM rejected the
load.** The preserved raw log says `Specified AOT cache not found`, followed by
`Loading static archive failed.` The launcher fell back and produced correct LSP
results. This is neither accepted AOT nor evidence that the archive was used.
The log was copied after measurement and shutdown, with no warming status query.

The measured cgroup lifetime contains 2.869935 CPU seconds and a kernel
memory-charge peak of 180,047,872 bytes; the empty group was removed. These are
one complete case lifetime including launch and shutdown, not request CPU/RSS or
a comparative performance estimate. All process roots and descendants are covered
by this kernel accounting scope; per-role sampled RSS is a different observation.

To replay, verify `capture.zip` against `manifest.json`, extract into a new folder,
then run `node benchmarks/lsp-scenarios/reduce.ts /path/to/capture /path/to/output`
with an existing empty output directory. The saved `replayed-summary.json` is the
result of that separate invocation. Catalogue completeness remains false because
this capture intentionally contains one case. No public speed claim is enabled.
