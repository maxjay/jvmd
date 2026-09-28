# Independent invalidation controls and harness corrections

Seven cases distinguish provider body edits, overload addition, namespace
addition/removal/unrelated addition, ordered duplicate classpaths, and identical
classpath metadata. Each relevant change uses an unchanged caller and an exact
before/after semantic witness. Irrelevant controls preserve the answer; they do
not prove zero internal work.

The original `96ee053` pilot is preserved in `original.tar.xz`. Its three namespace
cases exposed an independent compiler-oracle mistake: different packages' public
`Shadow` types were copied under alias filenames. The source layout/fresh compiler
snapshot fix is `003c1f8`. Its classpath fixture also omitted local Maven artifact
seeding; the missing inputs are a harness failure, not server initialization evidence.

`interrupted.tar.xz` preserves the first corrected replay, interrupted after four
finalized cases. It has no original final seal; snapshot hashes do not repair it.
`continuation.tar.xz` retains all ten subsequent namespace/classpath case runs.
The artifact-only replay found two unsealed Equinox temporary files; they remain
explicitly outside the original inventory in the review manifest and audit.

In that continuation, both servers returned an incorrect first result after
classpath reordering, then returned the required member in the next and settled
probes. JVMD's namespace controls passed. JDTLS namespace addition was initially
stale then converged; remove/unrelated controls were semantically correct but had
unclean shutdowns. None of those later correct replies erases an early failure.

These are original diagnostic review subsets, with omissions and byte hashes in
their embedded manifests. Additional sealed body/overload and isolated-directory
namespace/classpath validation is recorded separately; no failed original capture
is replaced, and this directory makes no timing or complete-catalogue claim.
