#!/usr/bin/env python3
"""Add changes from a previous merge-review comment to a comparison."""
import argparse, json
from pathlib import Path

from compare import markdown


def tables(comment):
    """Return benchmark tables keyed by (fixture, metric, unit), in document order."""
    found, current = [], None
    for line in comment.splitlines():
        if line.startswith('| Fixture | Metric | Unit | JVMD |'):
            current = {}
            found.append(current)
            continue
        if current is None or not line.startswith('|') or line.startswith('|---'):
            continue
        cells = [cell.strip() for cell in line.strip().strip('|').split('|')]
        if len(cells) < 6:
            continue
        try:
            current[(cells[0], cells[1], cells[2])] = float(cells[3])
        except ValueError:
            continue
    return found


def annotate(result, previous, previous_pr):
    result['previous_pr'] = previous_pr
    for fixture, metrics in result['fixtures'].items():
        for metric, values in metrics.items():
            old = previous.get((fixture, metric, values.get('unit', 'ms')))
            values['previous_pr_jvmd'] = old
            values['improvement_over_previous_pr'] = (
                (old - values['jvmd']) / old * 100 if old not in (None, 0) else None)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('comparison', type=Path)
    parser.add_argument('previous_comment', type=Path)
    parser.add_argument('--table', type=int, required=True)
    parser.add_argument('--previous-pr', type=int, required=True)
    parser.add_argument('--markdown', type=Path, required=True)
    args = parser.parse_args()
    result = json.loads(args.comparison.read_text())
    previous_tables = tables(args.previous_comment.read_text())
    if args.table >= len(previous_tables):
        raise ValueError(f'previous comment has {len(previous_tables)} benchmark table(s), cannot select {args.table}')
    annotate(result, previous_tables[args.table], args.previous_pr)
    args.comparison.write_text(json.dumps(result, indent=2)+'\n')
    args.markdown.write_text(markdown(result))
    print(json.dumps({'previous_pr': args.previous_pr, 'matched_table': args.table}))


if __name__ == '__main__': main()
