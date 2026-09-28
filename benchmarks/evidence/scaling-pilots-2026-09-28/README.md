# Preserved scaling development pilots

These are separate diagnostic captures, not independent performance blocks. Each
archive preserves its original full checksum inventory and an explicit review
subset manifest. Large caches are omitted; the package does not claim complete
raw-bundle equivalence. Verify archive SHA-256 against `manifest.json` first.

| Capture | Source | Disposition |
|---|---|---|
| `original-layout.tar.xz` | `d585cad` | Eclipse-only metadata did not declare JVMD's Maven model. Null JVMD answers are fixture failures, not scaling findings. |
| `maven.tar.xz` | `4c5c1f8` | Fourteen reports: six pass, six incorrect, two protocol errors. All six selected JVMD hover controls pass. JVMD's 64-member response was explicitly truncated; the original oracle incorrectly classified that as a complete-enumeration failure. JDTLS retains stale early responses and shutdown failures. |
| `incomplete.tar.xz` | `33b35ba` | Both servers explicitly truncate the 64-member enumeration. Revised oracle records two unavailable-evidence outcomes. No successful timing estimate is permitted. |

The third capture also has two unsealed Equinox temporary files. Packaging used
`--allow-unsealed-files` to preserve that failure explicitly; it did not repair or
reseal the source capture. Every originally sealed payload still verifies. The
archive manifest lists the extra paths and hashes. The missing planned warmup and
steady requests after first-result rejection remain recorded as integrity gaps.
The case is neither successful nor a completed enumeration trace.

Commands are in the original manifests and launch records. The corrected Maven
fixture uses matching Eclipse projects, a real Maven reactor, deterministic local
archives and an isolated seeded repository. Sizes were not reduced after observing
the completion limit. No native zero-work, scaling curve or cross-server speedup
claim follows from these development pilots.
