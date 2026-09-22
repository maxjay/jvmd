#!/usr/bin/env python3
"""Preserve per-worker values and aggregate medians without dropping slower runs."""
import argparse, json, statistics
import math
from pathlib import Path

def med(values): return statistics.median(values)


def summarize_workflows(root):
    from verify import verify_workflows
    verification=verify_workflows(root)
    checked={r['worker']:r for r in verification['workflow_workers']}
    invocations=[];groups={}
    for path in sorted(root.glob('*/report.json')):
        report=json.loads(path.read_text())
        if 'workflow' not in report:continue
        report['directory']=path.parent.name
        report['verification']=checked[path.parent.name]
        for file,key in [('resources.json','resources'),('trace.json','trace'),('attribution.json','attribution')]:
            report[key]=json.loads((path.parent/file).read_text()) if (path.parent/file).exists() else None
        invocations.append(report)
        for name in sorted({a['name'] for a in report['actions']}):
            actions=[a for a in report['actions'] if a['name']==name]
            successes=[a['time_to_correct_ms'] for a in actions if a['outcome']=='correct'] if checked[path.parent.name]['verified'] else []
            row={'repetition':report['repetition'],'invocation':report['workflow'],'attempts':sum(len(a['attempts']) for a in actions),
                 'count':len(actions),'correct':len(successes),'failures':len(actions)-len(successes),
                 'p50_ms':med(successes) if successes else None,
                 'p95_ms':sorted(successes)[math.ceil(.95*len(successes))-1] if len(successes)>=20 else None}
            groups.setdefault((report['engine'],report['mode'],name),[]).append(row)
    rows=[]
    for (engine,mode,name),processes in groups.items():
        values=[p['p50_ms'] for p in processes if p['p50_ms'] is not None]
        rows.append({'engine':engine,'mode':mode,'action':name,'processes':processes,
                     'correct':sum(p['correct'] for p in processes),'failures':sum(p['failures'] for p in processes),
                     'p50_ms':med(values) if values else None,'min_process_p50_ms':min(values) if values else None,'max_process_p50_ms':max(values) if values else None,
                     'p95_ms':med(p['p95_ms'] for p in processes) if all(p['p95_ms'] is not None for p in processes) else None})
    return {'schema':1,'kind':'workflows','rows':rows,'invocations':invocations,'verification':verification,
            'provenance':json.loads((root/'provenance.json').read_text()),
            'aggregation':'Median of per-process medians; p95 only with at least 20 correct samples in every process. Failed workers remain visible and contribute no fast successes. Modes never mixed.'}

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
        process_resources = [json.loads(p.read_text()) for p in sorted(path.parent.glob('process*/resources.json'))]
        if process_resources:
            values['peak_rss_mib'] = max(r['peak_rss_bytes'] for r in process_resources)/(1024*1024)
            values['cpu_seconds'] = sum(r['cpu_seconds_observed'] for r in process_resources)
            values['disk_read_mib'] = sum(r['read_bytes_observed'] for r in process_resources)/(1024*1024)
            values['disk_write_mib'] = sum(r['write_bytes_observed'] for r in process_resources)/(1024*1024)
            profiles = [r['jfr'] for r in process_resources if 'jfr' in r]
            if profiles: values['sampled_allocated_mib'] = sum(r['sampled_allocated_bytes'] for r in profiles)/(1024*1024)
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
    return {'units': {'latency': 'milliseconds', 'memory_and_io': 'MiB', 'cpu': 'seconds',
                      'allocation': 'MiB estimated from JFR ObjectAllocationSample weights'},
            'aggregation': 'Median of worker medians; new-workspace metrics use resident roots; warm metrics exclude the first request.', 'totals': totals, 'fixtures': output}

if __name__ == '__main__':
    p = argparse.ArgumentParser(); p.add_argument('root', type=Path); p.add_argument('output', type=Path); p.add_argument('--workflows',action='store_true'); a = p.parse_args()
    result = summarize_workflows(a.root) if a.workflows else summarize(a.root)
    a.output.write_text(json.dumps(result, indent=2)+'\n'); print(json.dumps(result.get('totals',result.get('verification'))))
