# Corrected dependency handle replay

Source: `6a13a0992a960e768a72b18d83ec92538090d044`. Both servers, one independent block, one warmup and two steady samples, pipe profile. All 24 reports finalized; original complete file inventory and every archived member hash verified. The reducer reports no integrity issues.

| Cases | JVMD | JDTLS |
| --- | --- | --- |
| Ten dependency content/attachment routes | 10 unsupported | 10 pass |
| Optional completion selection | not_applicable, original item and resolve witness | pass |
| No applicable quick fix | unsupported | request checks pass; shutdown exits 1, case protocol_error |

The two earlier dependency captures remain preserved as harness mistakes. This replay uses the original URI, including its escaped opaque handle. Attached content is compared with independently authored source; unattached content checks class/member identity. Source attachment updates have immediate and settled probes, with binary and caller controls.

The archive is a review subset of the immutable original bundle. Its original checksums are retained; omitted server caches are not resealed away. `manifest.json` records original inventory verification and archive hash. The case-level shutdown failure is retained. No public comparative claim follows from this one-block capture.
