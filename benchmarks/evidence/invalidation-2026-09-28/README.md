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

The completed validation at `75317ab` is preserved separately in
`body-overload.tar.xz` and `verified-controls.tar.xz`. All fourteen case runs have
intact original bundle inventories; the control capture also passed a separate
raw-artifact reduction after copying from its temporary collection directory.

| Declared case | JVMD | JDTLS |
|---|---|---|
| Provider body-only edit | pass | semantic pass; shutdown failure |
| Add more-specific overload | pass | semantic pass; shutdown failure |
| Add same-package shadow | pass | initially stale; then settled |
| Remove same-package shadow | pass | initially stale; then settled |
| Add unrelated namespace | pass | semantic pass; shutdown failure |
| Reverse ordered duplicate classpath | initially stale; then settled | initially stale; then settled |
| Identical classpath metadata | pass | semantic pass; shutdown failure |

The resulting mandatory outcomes are **6 pass, 4 incorrect, 4 protocol errors**.
The earlier control capture's namespace-removal response was immediate-correct;
both observations remain visible. Do not select the more favourable run or erase
the incorrect first reply because a later retry converged. These outcomes apply
to the benchmark's declared current-result requirement, not a claim that every
asynchronous delay violates the LSP specification.

Every archive here is a diagnostic review subset, with omissions and byte hashes
in its embedded manifest. No failed original capture is replaced, and this
directory makes no timing or complete-catalogue claim.
