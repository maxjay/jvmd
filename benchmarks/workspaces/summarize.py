#!/usr/bin/env python3
"""Preserve per-worker values and aggregate medians without dropping slower runs."""
import argparse, json, statistics
from pathlib import Path

def med(values): return statistics.median(values)

def summarize(root):
    groups = {}; totals = {'workers': 0, 'workspaces': 0, 'editor_requests': 0}
    for path in sorted(root.glob('*/*/report.json')):
        report = json.loads(path.read_text())
        if not report.get('complete'): raise AssertionError(path)
        fixture, tail = path.parent.parent.name.split('-', 1); mode, repetition = tail.rsplit('-', 1)
        ws = report['workspaces']; resident = ws[1:-1]
        if not resident: resident = ws[:1]
        values = {'fresh': ws[0]['ready_ms'], 'new_workspace': med(w['ready_ms'] for w in resident), 'restart': ws[-1]['ready_ms'],
                  'dependency_search': med(w['search_ms'] for w in resident)}
        values['measured_work'] = sum(w['ready_ms']+w['search_ms']+sum(sum(v) for v in w['operations_ms'].values())+sum(sum(v) for v in w['typing_ms'].values())+sum(w['diagnostics_ms'].values()) for w in ws)
        if ws[0]['preindex_ms'] is not None: values['preindex'] = ws[0]['preindex_ms']
        for key in ws[0]['operations_ms']:
            values['first_'+key] = med(w['operations_ms'][key][0] for w in resident)
            values['warm_'+key] = med(med(w['operations_ms'][key][1:]) for w in resident)
        for key in ws[0]['typing_ms']: values['typing_'+key] = med(med(w['typing_ms'][key]) for w in resident)
        for key in ws[0]['diagnostics_ms']: values['diagnostics_'+key] = med(w['diagnostics_ms'][key] for w in resident)
        groups.setdefault(fixture, {}).setdefault(mode, []).append({'path': str(path.relative_to(root)), 'repetition': int(repetition), 'values': values})
        totals['workers'] += 1; totals['workspaces'] += len(ws)
        totals['editor_requests'] += sum(sum(len(v) for v in w['operations_ms'].values())+sum(len(v) for v in w['typing_ms'].values()) for w in ws)
    output = {}
    for fixture, modes in groups.items():
        output[fixture] = {}
        for mode, workers in modes.items():
            keys = workers[0]['values']
            output[fixture][mode] = {'workers': workers, 'median': {k: med(w['values'][k] for w in workers) for k in keys},
                                     'min': {k: min(w['values'][k] for w in workers) for k in keys}, 'max': {k: max(w['values'][k] for w in workers) for k in keys}}
    return {'units': 'milliseconds', 'aggregation': 'Median of worker medians; new-workspace metrics use the two new resident roots; warm metrics use five post-first samples per root.', 'totals': totals, 'fixtures': output}

if __name__ == '__main__':
    p = argparse.ArgumentParser(); p.add_argument('root', type=Path); p.add_argument('output', type=Path); a = p.parse_args()
    result = summarize(a.root); a.output.write_text(json.dumps(result, indent=2)+'\n'); print(json.dumps(result['totals']))
