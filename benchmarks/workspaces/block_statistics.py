"""Paired independent-block inference; never treat nested requests as replicates."""
import math
import random
import statistics


def quantile(values, p):
    values = sorted(values)
    h = (len(values) - 1) * p
    i = int(h)
    return values[i] + (h - i) * (values[min(i + 1, len(values) - 1)] - values[i])


def paired_effect(rows, planned_blocks, seed=271828, resamples=10000, tolerance=None):
    """Rows contain one A/B aggregate per restored block, not raw requests.

    A missing, failed or invalid block disables inference for the endpoint. We
    retain its denominator instead of conditioning a headline on successful runs.
    """
    issues = []
    identities = [row['block'] for row in rows]
    if len(set(identities)) != len(identities):
        issues.append('duplicate independent block')
    if set(identities) != set(range(1, planned_blocks + 1)):
        issues.append('missing or unexpected independent block')
    failures = []
    for row in rows:
        if row.get('outcome') != 'pass' or any(not isinstance(row.get(k), (int, float)) or isinstance(row[k], bool)
                or not math.isfinite(row[k]) or row[k] <= 0 for k in ('a', 'b')):
            failures.append(row['block'])
    if failures:
        issues.append('failed or unavailable paired block')
    if planned_blocks < 10:
        issues.append('fewer than ten independent blocks')
    result = dict(schemaVersion=1, plannedBlocks=planned_blocks, observedBlocks=len(rows),
                  failedBlocks=failures, method='geometric mean B/A; paired percentile bootstrap of independent block log ratios',
                  seed=seed, resamples=resamples, confidence=0.95, quantile='Hyndman-Fan type 7',
                  familyInference='none; endpoint intervals are descriptive, without simultaneous coverage',
                  ratio=None, interval=None, eligible=False, issues=issues, overheadWithinTolerance=False)
    if issues:
        return result
    logs = [math.log(row['b'] / row['a']) for row in sorted(rows, key=lambda r:r['block'])]
    rng = random.Random(seed)
    boot = [math.exp(statistics.mean(rng.choices(logs, k=len(logs)))) for _ in range(resamples)]
    interval = [quantile(boot, .025), quantile(boot, .975)]
    result.update(ratio=math.exp(statistics.mean(logs)), interval=interval, eligible=True,
                  overheadWithinTolerance=tolerance is not None and interval[0] >= 1-tolerance and interval[1] <= 1+tolerance)
    return result
