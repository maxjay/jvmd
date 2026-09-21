#!/usr/bin/env python3
"""Create a compact, machine-readable JVMD versus JDTLS comparison."""
import argparse, json
from pathlib import Path

def unit(name):
    if name == 'cpu_seconds': return 's'
    if name.endswith('_mib'): return 'MiB'
    return 'ms'


def compare(summary, candidate, baseline, selected=None):
    fixtures = {}
    for fixture, modes in summary['fixtures'].items():
        if candidate not in modes or baseline not in modes:
            continue
        left, right = modes[candidate]['median'], modes[baseline]['median']
        metrics = {}
        for name in sorted(left.keys() & right.keys()):
            if selected and name not in selected: continue
            candidate_value, baseline_value = left[name], right[name]
            metrics[name] = {'jvmd': candidate_value, 'jdtls': baseline_value,
                             'jvmd_over_jdtls': candidate_value/baseline_value if baseline_value else None,
                             'lower_is_better': True, 'unit': unit(name)}
        fixtures[fixture] = metrics
    if not fixtures:
        raise ValueError(f'no fixtures contain both {candidate!r} and {baseline!r}')
    return {'candidate': candidate, 'baseline': baseline, 'interpretation':
            'jvmd_over_jdtls below 1 means JVMD used less time/resources; allocation is sampled, not retained heap.',
            'fixtures': fixtures}


def markdown(result):
    previous_pr = result.get('previous_pr')
    improvement_header = f'Improvement vs PR #{previous_pr}' if previous_pr else 'Improvement vs previous PR'
    lines = ['# JVMD / JDTLS LSP benchmark', '',
             f'| Fixture | Metric | Unit | JVMD | JDTLS | JVMD / JDTLS | {improvement_header} |',
             '|---|---|---|---:|---:|---:|---:|']
    for fixture, metrics in result['fixtures'].items():
        for name, values in metrics.items():
            ratio = values['jvmd_over_jdtls']
            ratio_text = 'n/a' if ratio is None else f'{ratio:.3f}'
            improvement = values.get('improvement_over_previous_pr')
            improvement_text = 'n/a' if improvement is None else f'{improvement:+.1f}%'
            lines.append(f"| {fixture} | {name} | {values['unit']} | {values['jvmd']:.3f} | {values['jdtls']:.3f} | {ratio_text} | {improvement_text} |")
    lines += ['', result['interpretation']]
    if result.get('semantic_state'):
        lines += ['', semantic_markdown(result['semantic_state'])]
    return '\n'.join(lines)+'\n'


def semantic_markdown(result):
    lines = ['## JVMD semantic state: base versus candidate', '', result['interpretation'], '',
             f"Base: `{result['base_sha']}` · candidate: `{result['head_sha']}`", '',
             '| Metric | Unit | Base JVMD | Candidate JVMD | After / before | Repetitions |',
             '|---|---|---:|---:|---:|---:|']
    for row in result['rows']:
        ratio = row['after_over_before']
        ratio_text = 'n/a' if ratio is None else f'{ratio:.3f}'
        lines.append(f"| {row['metric']} | {row['unit']} | {row['before']:.3f} | {row['after']:.3f} | {ratio_text} | {row['repetitions']} |")
    return '\n'.join(lines)+'\n'


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('summary', type=Path); parser.add_argument('output', type=Path)
    parser.add_argument('--candidate', default='after'); parser.add_argument('--baseline', default='jdtls-shared')
    parser.add_argument('--metrics', nargs='+')
    parser.add_argument('--markdown', type=Path)
    parser.add_argument('--semantic-state', type=Path, help='Paired JVMD component evidence, kept separate from LSP ratios')
    args = parser.parse_args(); result = compare(json.loads(args.summary.read_text()), args.candidate, args.baseline, args.metrics)
    if args.semantic_state: result['semantic_state'] = json.loads(args.semantic_state.read_text())
    args.output.write_text(json.dumps(result, indent=2)+'\n')
    if args.markdown: args.markdown.write_text(markdown(result))
    print(json.dumps({'fixtures': len(result['fixtures'])}))
