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
        indexing=[s for s in stages if s['name']=='index.scan']
        action['trace_evidence']['index_scan_overlap_ms']=union((max(start,s['ts']),min(end,s['ts']+s['dur']))
            for start,end in foreground for s in indexing if max(start,s['ts'])<min(end,s['ts']+s['dur']))/1000
        allocations=[p['sampled_allocated_bytes'] for p in (run.get('attribution') or {}).get('groups',[])
                     if p['invocation']==action.get('id') and p['event']=='jdk.ObjectAllocationSample']
        action['sampled_allocation_mib']=sum(allocations)/1048576 if allocations else None
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
                'scope':'Runs with source-only local runtime dependencies; no benefit is claimed for installed-artifact-only launches.',
                'benefit':'Restore a correct cross-repository launch; no latency saving can be estimated from a failed action.',
                'constraints':'Respect runtime scopes, transitive dependencies and selected local versions; do not substitute an installed library or launch stale outputs.',
                'verify':'--workflow runtime, required B/C markers, breakpoint/locals/step and unchanged PID',
                'confidence':'High for observed failure; root cause remains a hypothesis.'})
        if action['name']=='dependency_definition' and 'jvmd' in run['engine']:
            backlog.append({'priority':1,'workflow':run['workflow'],'action':action['name'],'stage':'dependency source selection',
                'evidence':{'attempts':len(action['attempts']),'empty_results':sum(a.get('result')==[] for a in action['attempts']),
                    'artifact':run['directory']+'/report.json'},
                'code':'jvmd-dist/src/main/java/dev/jvmd/dist/Application.java: describeDocumented; jvmd-lsp/src/main/java/dev/jvmd/lsp/LspFacade.java: location',
                'hypothesis':'Merging live binary-symbol data may overwrite indexed source-JAR metadata with null source fields; exact name ranges also require checking.',
                'candidate':'Test preserving the selected artifact source metadata when the live symbol has no source location, in a separate correctness change.',
                'scope':'Navigation to matched external source JARs; local-source navigation already has its own oracle.',
                'benefit':'Restore correct navigation; no speedup can be estimated from missing results.',
                'constraints':'Preserve artifact/version selection and exact declaration ranges; do not substitute another source checkout or decompiled content.',
                'verify':'--workflow coverage: dependency_definition must select the fixture source JAR and the base identifier.',
                'confidence':'Observed empty provider results; metadata-merge root cause is a code-inspection hypothesis.'})
    interventions={
        'project.resolve':('Project refresh includes resolution and workspace index binding.','Inspect the measured refresh wait/CPU stacks before testing a narrower refresh path.','Preserve selected dependency versions, workspace source precedence, generation publication and memory admission limits.'),
        'inputs.validate':('Repeated input observation may dominate the action.','Examine request-scoped duplicate metadata/hash observations before changing validation.','Preserve document versions, filesystem change detection and current-input agreement.'),
        'inputs.discover':('Input discovery may scan more files than the edit needs.','Test scoped inventory reuse using the recorded files checked/hashed.','Detect additions/deletions and dependency changes; no stale snapshots.'),
        'compiler.prepare':('Compiler preparation and validation may repeat after this edit.','Inspect the recorded cache decision and sample stacks; test avoiding one evidenced redundant preparation.','Keep compiler ownership and input identity checks.'),
        'compiler.parse':('Parsing consumes a material part of this particular request.','Test reducing the evidenced explicit/implicit source set or reusing an already valid compilation.','Required diagnostics, dependencies and edited signatures must agree.'),
        'compiler.enter_attribute':('Combined javac enter/attribute consumes this request interval.','Use source counts and sampled stacks to test a narrower valid compilation for this edit.','Do not skip required attribution or alter processor behavior.'),
        'query.lookup':('Lookup work dominates this selected request.','Use postings/decoded-record counts to test one more selective lookup path.','Preserve complete references, source precedence and pagination.'),
        'completion.materialize':('Completion construction has a measured, bounded cost in this request.','Inspect candidate/documentation counts and samples before testing deferred optional materialization.','Keep required candidates, signatures and replacement ranges.'),
        'session.queue':('The request spends measurable time waiting for its session actor.','Test scheduling of the observed competing background work.','Preserve actor isolation and cancellation semantics.')}
    eligible=[a for a in run['actions'] if a['outcome']=='correct' and a['name']=='api_completion']
    if eligible:
        action=max(eligible,key=lambda a:a['time_to_correct_ms'])
        candidates=[s for s in stages if s['args'].get('invocation')==action.get('id') and action.get('id') and s['name'] in interventions]
        if candidates:
            stage=max(candidates,key=lambda s:s['dur']);hypothesis,candidate,constraints=interventions[stage['name']]
            included={stage['args']['span']}
            while True:
                descendants={s['args']['span'] for s in stages if s['args']['parent'] in included}
                if descendants.issubset(included):break
                included.update(descendants)
            profiles=[p for p in (run.get('attribution') or {}).get('groups',[]) if p['span'] in included]
            admission=[p for p in profiles if p.get('observed_wait_ms',0)>0 and 'dev.jvmd.index.rocks.RocksArtifactAdmission.acquireArtifact' in p['methods']]
            if admission:
                hypothesis='Foreground project refresh waits for artifact-memory admission while background indexing consumes permits.'
                candidate='Test avoiding artifact admission for a verified metadata-only reuse, or prioritizing foreground admission, in a separate bounded change.'
                constraints='Keep freshness checks, inventory publication and the existing memory limit; never skip admission for parsing/materialization that needs the budget.'
            bound=min(stage['dur']/1000,action['time_to_correct_ms'])*.9
            backlog.append({'priority':2,'workflow':run['workflow'],'action':action['id'],'stage':stage['name'],
                'evidence':{'span':stage['args']['span'],'inclusive_wall_ms':stage['dur']/1000,'action_ms':action['time_to_correct_ms'],
                            'work':stage['args']['work'],'inclusive_samples':sum(p['samples'] for p in profiles),
                            'wait_events_ms':sum(p.get('observed_wait_ms',0) for p in profiles),
                            'overlapping_index_work':[{'span':s['args']['span'],'stage':s['name'],'work':s['args']['work']} for s in stages if s['name']=='index.scan' and s['ts']<stage['ts']+stage['dur'] and stage['ts']<s['ts']+s['dur']],
                            'child_work':[{s['name']:s['args']['work']} for s in stages if s['args']['span'] in included and s['args']['span']!=stage['args']['span'] and s['args']['work']],
                            'artifact':run['directory']+'/trace.json'},
                'code':{name:method for p in profiles for name,method in p['methods'].items()},
                'hypothesis':hypothesis,'candidate':candidate,'constraints':constraints,
                'scope':'Foreground actions overlapping background artifact scans; no idle-warm benefit is assumed.' if admission else 'This action and cache state on comparable fixtures; repeat before extrapolating.',
                'benefit':f'For this invocation only, a 10x stage speedup saves at most {bound:.3f} ms if the whole measured interval is serial on the critical path. The complete API edit took {run.get("api_edit_to_correct_ms",action["time_to_correct_ms"]):.3f} ms. Inclusive/background overlap can make the benefit smaller.',
                'verify':'Repeat the same API edit with independent correctness, unprofiled comparison, identical cache state and stage/work attribution.',
                'confidence':'Recorded admission wait stack and overlapping indexing; repeat before changing scheduling.' if admission else 'One profiled invocation; validate critical-path contribution and repeat before optimizing.'})
    return backlog


def summarize_workflows(root):
    from verify import verify_workflows
    verification=verify_workflows(root)
    checked={r['worker']:r for r in verification['workflow_workers']}
    provenance=json.loads((root/'provenance.json').read_text())
    invocations=[];groups={};backlog=[];related=[]
    for directory in sorted(root.parent.iterdir()):
        if directory==root or not (directory/'provenance.json').exists():continue
        other=json.loads((directory/'provenance.json').read_text())
        if other.get('build')!=provenance['build'] or other.get('harness')!=provenance.get('harness'):continue
        for path in sorted(directory.glob('*/report.json')):
            item=json.loads(path.read_text())
            fixture=path.parent/item.get('fixture','fixture/fixture.json')
            if 'workflow' in item and item['mode']!='comparison' and fixture.exists():
                related.append({'engine':item['engine'],'scenario':item.get('scenario'),'mode':item['mode'],
                    'source_hashes':json.loads(fixture.read_text())['source_hashes'],
                    'workflow':item['workflow'],'outcome':item['outcome'],'url':'../'+directory.name+'/dashboard.html#'+item['workflow']})
    for path in sorted(root.glob('*/report.json')):
        report=json.loads(path.read_text())
        if 'workflow' not in report:continue
        report['directory']=path.parent.name
        report['verification']=checked[path.parent.name]
        fixture=json.loads((path.parent/report['fixture']).read_text())
        report['related_diagnostics']=[{k:v for k,v in item.items() if k!='source_hashes'} for item in related
            if item['engine']==report['engine'] and item['scenario']==report.get('scenario') and item['source_hashes']==fixture['source_hashes']]
        for file,key in [('resources.json','resources'),('trace.json','trace'),('attribution.json','attribution')]:
            report[key]=json.loads((path.parent/file).read_text()) if (path.parent/file).exists() else None
        backlog.extend(investigate(report))
        invocations.append(report)
        mode=report['mode']+' / '+report.get('cache_state','fresh project/tool state')+(' / overhead' if report.get('overhead_pair') else '')+(' / stages enabled' if report.get('instrumentation') else '')
        observations=list(report['actions'])
        for field,name in [('external_open_to_ready_ms','open_to_project_ready'),('api_edit_to_correct_ms','api_edit_to_correct')]:
            resources=report.get('workflow_resources',{}).get(name)
            if name=='open_to_project_ready' and report.get('scenario')=='background':name='open_with_typing_to_ready'
            if report.get(field) is not None:observations.append({'name':name,'outcome':report['outcome'],'time_to_correct_ms':report[field],'attempts':[],'resources':resources})
        for name in sorted({a['name'] for a in observations}):
            actions=[a for a in observations if a['name']==name]
            successes=[a['time_to_correct_ms'] for a in actions if a['outcome']=='correct'] if checked[path.parent.name]['verified'] else []
            row={'repetition':report['repetition'],'invocation':report['workflow'],'attempts':sum(len(a['attempts']) for a in actions),
                 'count':len(actions),'correct':len(successes),'failures':len(actions)-len(successes),
                 'p50_ms':med(successes) if successes else None,
                 'p95_ms':sorted(successes)[math.ceil(.95*len(successes))-1] if len(successes)>=20 else None}
            metrics={'tooling_cpu_s':[a['resources']['cpu_seconds_observed']['tooling'] for a in actions if a.get('resources')],
                     'tooling_rss_mib':[a['resources']['peak_rss_bytes']['tooling']/1048576 for a in actions if a.get('resources')],
                     'sampled_allocation_mib':[a['sampled_allocation_mib'] for a in actions if a.get('sampled_allocation_mib') is not None]}
            row.update({key:med(values) if values and successes else None for key,values in metrics.items()})
            groups.setdefault((report['engine'],mode,name),[]).append(row)
    rows=[]
    for (engine,mode,name),processes in groups.items():
        values=[p['p50_ms'] for p in processes if p['p50_ms'] is not None]
        rows.append({'engine':engine,'mode':mode,'action':name,'processes':processes,
                     'correct':sum(p['correct'] for p in processes),'failures':sum(p['failures'] for p in processes),
                     'p50_ms':med(values) if values else None,'min_process_p50_ms':min(values) if values else None,'max_process_p50_ms':max(values) if values else None,
                     'p95_ms':med(p['p95_ms'] for p in processes) if all(p['p95_ms'] is not None for p in processes) else None})
        for key in ('tooling_cpu_s','tooling_rss_mib','sampled_allocation_mib'):
            observed=[p[key] for p in processes if p[key] is not None]
            rows[-1][key]=med(observed) if observed else None
    return {'schema':1,'kind':'workflows','rows':rows,'invocations':invocations,'verification':verification,
            'improvement_backlog':sorted(backlog,key=lambda b:(b['priority'],-b.get('evidence',{}).get('inclusive_wall_ms',0))),
            'provenance':provenance,
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
