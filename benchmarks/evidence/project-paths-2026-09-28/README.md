# Corrected project-path oracles

Both JDTLS cases, PRJ-02/jdk and ENV-01/classpath-roundtrip, pass with empty
integrity-issue lists at `75317ab8a069610c12347fbb0d94f07674a31442`.
The separate artifact replay also passes; see `verified-replayed-summary.json`.

The original catalogue compared a selected JDK symlink literally with the JVM's
real installation path. The corrected oracle resolves existing aliases and still
rejects a distinct installation. The classpath case used absolute getter paths
as setter inputs. The pinned JDTLS 1.61.0 binary's
`ProjectCommand.updateClasspaths` sends source/output entries to
`IProject.getFolder`; the corrected request supplies project-relative paths and
retains the original absolute getter response as its exact final oracle.

The first corrected replay passed both semantic cases, but its final audit found
two Equinox temporary files absent from the earlier per-case checkpoint. That
capture remains in `checkpoint-drift.tar.xz`, including the failed audit. Its
original final full-bundle seal is valid; its earlier case checkpoint is not.
This is a preserved integrity failure, not a product correctness failure.

The two-case replay was repeated in a separate temporary directory, then copied
and independently reduced after collection. Both cases and their checkpoint/full
inventories validate. `verified.tar.xz` preserves the protocol review subset;
`review-manifest.json` names every omitted runtime/fixture file. This successful
run does not explain the earlier file-appearance cause or repair its checkpoint.
No comparative performance claim is made from either local replay.
