# All ten declared scaling blocks

[Run 36365632505](https://github.com/maxjay/jvmd/actions/runs/36365632505) collected
all ten predeclared blocks at `4f318bb`. Five axes, three sizes and three mutation
controls give 45 cases per server per block: **900 case runs**. Each block used its
own runner VM, with the two servers running sequentially in balanced order from
one shared immutable distribution. No failed block was replaced.

| Mandatory case outcome | Count |
|---|---:|
| Pass | 512 |
| Incorrect | 115 |
| Protocol error | 213 |
| Unavailable evidence: incomplete enumeration | 60 |
| Harness error / not run | 0 / 0 |

Every original small report ZIP is preserved here. SHA-256 was checked against
GitHub's artifact digest and ZIP CRC verified before decoding. `manifest.json`
contains exact identities, per-block outcomes and aggregate counts.

Each block contains six 64-member completion cases whose replies are explicitly
incomplete and omit required members. Those attempts remain unavailable evidence.
Their first failed oracle prevents the planned later warmup/steady requests from
running; the reducer records all **132 missing-sample issues per block** rather
than fabricating measurements or presenting partial lists as successful work.
These are not empty-list speed wins. The other incorrect results and shutdown
failures also remain in the denominator.

The full raw artifacts are separate `raw-scaling-product-N` artifacts, roughly
580 MB each, exceeding the local download tool's 32 MiB limit. These small reports
are a review subset and do not by themselves constitute a local full-raw audit.
The independent final CI reducer reads all original raw shards. Its final result
is recorded separately when complete. No scaling effect or public comparative
performance claim is inferred from these collection reports.
