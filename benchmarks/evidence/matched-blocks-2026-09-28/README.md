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
download tool's 32 MiB limit. The workflow's final `merge` job independently reads
all original raw shards. These small reports alone do not close that final audit
gate or establish public comparative performance claims.

To reproduce the counts, verify each ZIP against `blocks[].zipSha256Verified`,
read `block-report.json`, and sum `runOutcomes[0].summary.outcomes` across all ten.
Do not average ratios from these single-block reports or discard failed blocks.
