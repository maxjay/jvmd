# Failed full replay: unavailable JDK path

The 350-case capture at `61926e2` finalized 11 passes, 2 incorrect results,
4 protocol errors and 333 harness errors. Most launches failed after the borrowed
JDK path became unavailable. This is not evidence that those server features fail.

The archive preserves raw exchanges, per-case failures, original checksums and a
review-subset manifest. The full original inventory verified before packaging;
selected bytes survived an archive round trip. Omitted caches and workspaces are
listed explicitly. The archive is not a full acceptance bundle.

The replacement replay uses the same frozen source revision, the pinned JDK
archive extracted into the active workspace, and a new production compilation.
It is a new capture; none of these failures were overwritten or relabelled.
