#!/usr/bin/env python3
"""Preserve per-worker values and aggregate medians without dropping slower runs."""
import argparse, json, statistics
import math
from pathlib import Path

def med(values): return statistics.median(values)


def investigate(run):
    """Explain actual invocations, never a sum of unrelated stage percentiles."""
    stages=(run.get('trace') or {}).get('traceEvents',[])
    def union(intervals):
        total=0;end=None
        for start,stop in sorted(intervals):
            total+=max(0,stop-max(start,end if end is not None else start))
            end=max(stop,end if end is not None else stop)
        return total
    for action in run['actions']:
        selected=[s for s in stages if s['args'].get('invocation')==action.get('id') and action.get('id')]
        requests=[s for s in selected if s['name']=='rpc.execute']
        if not requests:continue
        foreground=[(s['ts'],s['ts']+s['dur']) for s in requests]
        action['trace_evidence']={'spans':[s['args']['span'] for s in selected],
            'foreground_rpc_union_ms':union(foreground)/1000,
            'queue_union_ms':union((s['ts'],s['ts']+s['dur']) for s in selected if s['args']['queued'])/1000,
            'causal_tail_after_last_rpc_ms':max(0,max(s['ts']+s['dur'] for s in selected)-max(end for _,end in foreground))/1000,
            'boundary':'Server handler intervals; not frontend pixels or full transport. Background may overlap foreground. Durations are not summed across nested spans.'}
    backlog=[]
    failed=[a for a in run['actions'] if a['outcome']!='correct']
    if failed:
        action=failed[0]
        text=json.dumps(action)
        if 'NoClassDefFoundError' in text and 'jvmd' in run['engine']:
            backlog.append({'priority':1,'workflow':run['workflow'],'action':action['name'],'stage':'runtime.launch',
                'evidence':{'attempts':len(action['attempts']),'output':'NoClassDefFoundError in recorded debuggee output','artifact':run['directory']+'/report.json'},
                'code':'jvmd-dist/src/main/java/dev/jvmd/dist/Application.java: run / compileRuntimeModule',
                'hypothesis':'Compilation overlays local sources, but the launch classpath may still use the pre-compilation graph without those source-only module outputs.',
                'candidate':'Test adding the exact local runtime dependency outputs to launch classpath construction in a separate correctness change.',
                'benefit':'Restore a correct cross-repository launch; no latency saving can be estimated from a failed action.',
                'constraints':'Respect runtime scopes, transitive dependencies and selected local versions; do not substitute an installed library or launch stale outputs.',
                'verify':'--workflow runtime, required B/C markers, breakpoint/locals/step and unchanged PID',
                'confidence':'High for observed failure; root cause remains a hypothesis.'})
    interventions={
        'inputs.validate':('Repeated input observation may dominate the action.','Examine request-scoped duplicate metadata/hash observations before changing validation.','Preserve document versions, filesystem change detection and current-input agreement.'),
        'inputs.discover':('Input discovery may scan more files than the edit needs.','Test scoped inventory reuse using the recorded files checked/hashed.','Detect additions/deletions and dependency changes; no stale snapshots.'),
        'compiler.prepare':('Compiler preparation and validation may repeat after this edit.','Inspect the recorded cache decision and sample stacks; test avoiding one evidenced redundant preparation.','Keep compiler ownership and input identity checks.'),
        'compiler.parse':('Parsing consumes a material part of this particular request.','Test reducing the evidenced explicit/implicit source set or reusing an already valid compilation.','Required diagnostics, dependencies and edited signatures must agree.'),
        'compiler.enter_attribute':('Combined javac enter/attribute consumes this request interval.','Use source counts and sampled stacks to test a narrower valid compilation for this edit.','Do not skip required attribution or alter processor behavior.'),
        'query.lookup':('Lookup work dominates this selected request.','Use postings/decoded-record counts to test one more selective lookup path.','Preserve complete references, source precedence and pagination.'),
        'completion.materialize':('Completion construction dominates this selected request.','Inspect candidate/documentation counts and samples before testing deferred optional materialization.','Keep required candidates, signatures and replacement ranges.'),
        'session.queue':('The request spends measurable time waiting for its session actor.','Test scheduling of the observed competing background work.','Preserve actor isolation and cancellation semantics.')}
    eligible=[a for a in run['actions'] if a['outcome']=='correct' and a['name']=='api_completion']
    if eligible:
        action=max(eligible,key=lambda a:a['time_to_correct_ms'])
        candidates=[s for s in stages if s['args'].get('invocation')==action.get('id') and action.get('id') and s['name'] in interventions]
        if candidates:
            stage=max(candidates,key=lambda s:s['dur']);hypothesis,candidate,constraints=interventions[stage['name']]
            profiles=[p for p in (run.get('attribution') or {}).get('groups',[]) if p['span']==stage['args']['span']]
            bound=min(stage['dur']/1000,action['time_to_correct_ms'])*.9
            backlog.append({'priority':2,'workflow':run['workflow'],'action':action['id'],'stage':stage['name'],
                'evidence':{'span':stage['args']['span'],'inclusive_wall_ms':stage['dur']/1000,'action_ms':action['time_to_correct_ms'],
                            'work':stage['args']['work'],'samples':sum(p['samples'] for p in profiles),'artifact':run['directory']+'/trace.json'},
                'code':{name:method for p in profiles for name,method in p['methods'].items()},
                'hypothesis':hypothesis,'candidate':candidate,'constraints':constraints,
                'benefit':f'For this invocation only, a 10x stage speedup saves at most {bound:.3f} ms if the whole measured interval is serial on the critical path. Inclusive/background overlap can make the benefit smaller.',
                'verify':'Repeat the same API edit with independent correctness, unprofiled comparison, identical cache state and stage/work attribution.',
                'confidence':'One profiled invocation; validate critical-path contribution and repeat before optimizing.'})
    return backlog


def summarize_workflows(root):
    from verify import verify_workflows
    verification=verify_workflows(root)
    checked={r['worker']:r for r in verification['workflow_workers']}
    invocations=[];groups={};backlog=[]
    for path in sorted(root.glob('*/report.json')):
        report=json.loads(path.read_text())
        if 'workflow' not in report:continue
        report['directory']=path.parent.name
        report['verification']=checked[path.parent.name]
        for file,key in [('resources.json','resources'),('trace.json','trace'),('attribution.json','attribution')]:
            report[key]=json.loads((path.parent/file).read_text()) if (path.parent/file).exists() else None
        backlog.extend(investigate(report))
        invocations.append(report)
        mode=report['mode']+' / '+report.get('cache_state','fresh project/tool state')+(' / overhead' if report.get('overhead_pair') else '')+(' / stages enabled' if report.get('instrumentation') else '')
        for name in sorted({a['name'] for a in report['actions']}):
            actions=[a for a in report['actions'] if a['name']==name]
            successes=[a['time_to_correct_ms'] for a in actions if a['outcome']=='correct'] if checked[path.parent.name]['verified'] else []
            row={'repetition':report['repetition'],'invocation':report['workflow'],'attempts':sum(len(a['attempts']) for a in actions),
                 'count':len(actions),'correct':len(successes),'failures':len(actions)-len(successes),
                 'p50_ms':med(successes) if successes else None,
                 'p95_ms':sorted(successes)[math.ceil(.95*len(successes))-1] if len(successes)>=20 else None}
            groups.setdefault((report['engine'],mode,name),[]).append(row)
    rows=[]
    for (engine,mode,name),processes in groups.items():
        values=[p['p50_ms'] for p in processes if p['p50_ms'] is not None]
        rows.append({'engine':engine,'mode':mode,'action':name,'processes':processes,
                     'correct':sum(p['correct'] for p in processes),'failures':sum(p['failures'] for p in processes),
                     'p50_ms':med(values) if values else None,'min_process_p50_ms':min(values) if values else None,'max_process_p50_ms':max(values) if values else None,
                     'p95_ms':med(p['p95_ms'] for p in processes) if all(p['p95_ms'] is not None for p in processes) else None})
    return {'schema':1,'kind':'workflows','rows':rows,'invocations':invocations,'verification':verification,
            'improvement_backlog':sorted(backlog,key=lambda b:b['priority']),
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
