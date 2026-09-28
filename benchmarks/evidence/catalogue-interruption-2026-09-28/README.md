# Catalogue interrupted after 235 finalized cases

The execution service lost the active local process while collecting the 350-case
pipe replay at `61926e258cb09b475c65a4b837934248c69837f7`. The original directory
contains 235 finalized reports, one unfinished report and 114 cases not yet begun.
Its final checksum inventory and summary were never written. The missing seal is
not fabricated. The separate reducer invocation in `replayed-summary.json`
correctly rejects the interrupted bundle and retains every failed or missing case.

`interrupted-review.tar.xz` is an explicitly unsealed protocol review snapshot,
including original manifests, reports, requests, replies, transitions and process
journals. Its `review-manifest.json` hashes the selected bytes at acquisition and
lists omissions. Those hashes preserve this snapshot; they do not claim that the
original collection finalized successfully. Full raw files remain in the original
capture. The archive contains 2,155 members and its digest is in `manifest.json`.

The failed persisted seed also exposed a journal-placement bug: its operations
were copied into a reopened phase which had never started. The corrected runner
keeps the seed's failure in its own phase and marks reopen as not started. The
original audit's transition-journal errors remain in this snapshot.

Two other recorded failures were harness mistakes: JDK symlink identity and
classpath setter encoding. Their corrected independent replays are in
`../project-paths-2026-09-28/`. Do not count those original failures as product
defects or overwrite them with later passes.

`continuation-invocation.json` records the exact remaining 58 case selectors and
fresh-directory command. It includes the interrupted selector again and therefore
one already-completed JVMD unsupported case as well. It does not rerun or replace
the other finalized cases. Both captures remain separate diagnostic histories;
neither their latencies nor their denominators may be pooled as independent runs.

The continuation finalized all **116** planned reports: 39 pass, 10 incorrect,
12 protocol errors, 54 unsupported and one not applicable. Its original full
inventory verifies and is preserved in `continuation-review.tar.xz`; its separate
artifact reduction is `continuation-replayed-summary.json`. Two failed semantic
transitions never reached a settled probe (classpath replacement and import
membership), so their missing settled witnesses remain audit failures. There are
no harness-error outcomes in this continuation.

Across the interrupted capture and continuation, all 350 distinct planned
server/case pairs now have a finalized report. This is execution coverage, not
350 valid passes or a repair of the original unsealed bundle. Complete product
validation is a separate, predeclared ten-shard collection at source `6cd97fa`,
[run 36369332041](https://github.com/maxjay/jvmd/actions/runs/36369332041).
