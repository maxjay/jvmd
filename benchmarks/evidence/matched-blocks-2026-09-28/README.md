# Ten matched product blocks: collection reports

All ten declared blocks completed in [run 36365632505](https://github.com/maxjay/jvmd/actions/runs/36365632505)
at `4f318bb13d3df99e8a8b93bf8bbb805a5c209a3a`. Each block ran eight cases
against both servers, with fresh processes and isolated state: **160 case runs**.

| Case outcome | Count |
|---|---:|
| Pass | 104 |
| Incorrect | 19 |
| Protocol error | 37 |
| Harness error / not run | 0 / 0 |

These are the original small collection-report ZIPs. Every ZIP's SHA-256 was
checked against GitHub's artifact digest, its CRC checked, and its original
`block-report.json` decoded. `manifest.json` retains the artifact identities,
per-block denominators and aggregate counts. All ten reports have no collection
integrity issues. Product failures remain failures and exclude their endpoints
from admissible paired performance effects.

The full raw shards are separate `raw-matched-product-N` artifacts in that run.
They have not been downloaded and audited locally: each exceeds the local
download tool's 32 MiB limit. The completed final `merge` job independently read
all ten original raw shards on a separate CI runner. Its exact artifact is
`final-matched-product.zip`; its GitHub digest and ZIP CRC were verified locally.
There are **no independent-audit integrity issues**, and the same 160 case outcomes
remain. None of the **42 endpoint/state combinations** has ten admissible pairs;
all effect estimates and uncertainty intervals are unavailable. No failed paired
block was dropped. The workflow correctly fails the mandatory correctness gate.
This closes the matched raw-reduction check without establishing a speedup.

To reproduce the counts, verify each ZIP against `blocks[].zipSha256Verified`,
read `block-report.json`, and sum `runOutcomes[0].summary.outcomes` across all ten.
Do not average ratios from these single-block reports or discard failed blocks.
