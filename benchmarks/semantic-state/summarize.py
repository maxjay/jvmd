#!/usr/bin/env python3
"""Summarize paired JVMD-only experiments without inventing JDTLS equivalents."""
import argparse
import json
import statistics
from pathlib import Path


def summarize(root, base_sha, head_sha):
    median = statistics.median
    read = lambda name: json.loads((root / name).read_text())
    rows = []

    def add(metric, unit, before, after, samples=3):
        rows.append(dict(metric=metric, unit=unit, before=before, after=after,
                         after_over_before=after / before if before else None, repetitions=samples))

    for suite, label in [('lifetime', 'Zero-cache workspace'), ('retained', '16 MiB workspace')]:
        runs = {rev: [read(f'{suite}-{rev}-{i}.json') for i in range(1, 4)] for rev in ['baseline', 'after']}
        for metric, unit, extract in [
            ('cold lookup', 'ms', lambda r: r['milliseconds'][0]),
            ('warm lookup', 'ms', lambda r: median(r['milliseconds'][1:])),
            ('file loads / 31 requests', 'count', lambda r: r['loads']),
        ]:
            add(f'{label}: {metric}', unit, *(median(extract(r) for r in runs[rev]) for rev in ['baseline', 'after']))
    runs = {rev: [read(f'source-{rev}-{i}.json') for i in range(1, 4)] for rev in ['baseline', 'after']}
    for key, label, unit in [('cold_identity_ms', 'Source cold identity', 'ms'),
                             ('cold_identity_allocated_bytes', 'Source cold Java allocation', 'B')]:
        add(label, unit, *(median(r[key] for r in runs[rev]) for rev in ['baseline', 'after']))
    for op in ['identity', 'handle', 'prefix', 'exact']:
        add(f'Source warm {op}', 'ms', *(median(median(r[op]['milliseconds']) for r in runs[rev]) for rev in ['baseline', 'after']))
    add('Source publication', 'ms', read('seed-baseline.json')['seed_ms'], read('seed-after.json')['seed_ms'], 1)
    disk = [int(line.split()[0]) for line in (root / 'disk-kib.txt').read_text().splitlines()]
    if len(disk) != 2:
        raise ValueError('expected baseline and candidate disk measurements')
    add('Index disk allocation', 'KiB', *disk, 1)
    return dict(base_sha=base_sha, head_sha=head_sha, rows=rows, interpretation=(
        'JVMD-only component experiments; these are not LSP timings or a JDTLS comparison. '
        '128 files / 31 workspace requests; 256 files / 3,072 source symbols. '
        'Medians of three process medians; publication and disk are single samples. '
        'Workspace facts are supplied without javac. Allocation is Java thread allocation, not retained heap or native memory. '
        'After / before below 1 is lower; cold-start and storage regressions are included.'))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('root', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--base-sha', required=True)
    parser.add_argument('--head-sha', required=True)
    args = parser.parse_args()
    args.output.write_text(json.dumps(summarize(args.root, args.base_sha, args.head_sha), indent=2) + '\n')
