"""Summarize nested JFR spans without adding inclusive child time twice."""
import json, sys, statistics, math
from collections import defaultdict
from pathlib import Path

def stats(xs):
    xs=sorted(xs)
    return {'p50':statistics.median(xs),'p95':xs[math.ceil(.95*len(xs))-1]} if xs else None

source=Path(sys.argv[1]); events=json.loads(source.read_text())['recording']['events']
spans=[e['values'] for e in events if e['type']=='dev.jvmd.Stage']
completions=[e for e in spans if e['stage']=='completion.materialize' and e['method']=='lsp.request']
requests=list(dict.fromkeys(e['request'] for e in completions))
assert len(requests)==24, f'Expected first + two warmups + twenty steady + one edit request; got {len(requests)}'
steady=set(requests[3:23]) # first request, two discarded warmups, twenty steady samples
stages=defaultdict(lambda:defaultdict(list)); counts=defaultdict(list)
for request in steady:
    grouped=defaultdict(list)
    for e in spans:
        if e['request']==request: grouped[e['stage']].append(e)
    for name, rows in grouped.items():
        stages[name]['calls'].append(len(rows))
        stages[name]['wall_ms'].append(sum(e['durationNanos'] for e in rows)/1e6)
        for key, label, divisor in [('threadCpuNanos','cpu_ms',1e6),('threadAllocatedBytes','allocated_bytes',1)]:
            if all(e[key]>=0 for e in rows): stages[name][label].append(sum(e[key] for e in rows)/divisor)
        counters=defaultdict(int)
        for row in rows:
            for key,value in json.loads(row.get('counters') or '{}').items():counters[key]+=value
        for key,value in counters.items():stages[name]['counter:'+key].append(value)
result={'completion_requests':requests,'steady_requests':sorted(steady),
        'stages':{stage:{k:stats(v) for k,v in values.items()} for stage,values in stages.items()}}
source.with_suffix('.summary.json').write_text(json.dumps(result,indent=2)+'\n')
for name,row in sorted(result['stages'].items(),key=lambda it:it[1]['wall_ms']['p50'],reverse=True)[:45]:
    print(name, json.dumps(row))
