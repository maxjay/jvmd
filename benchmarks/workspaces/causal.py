#!/usr/bin/env python3
"""Reconstruct native intervals without mixing client and JVM clocks or inventing zero work."""
import argparse
import collections
import json
from pathlib import Path
from resources import export_jfr


def union_ns(intervals):
    total, begin, end = 0, None, None
    for a, b in sorted(intervals):
        if b < a:
            raise ValueError('negative native interval')
        if begin is None:
            begin, end = a, b
        elif a > end:
            total += end - begin
            begin, end = a, b
        else:
            end = max(end, b)
    return total if begin is None else total + end - begin


def reduce_native(events, calls, epoch):
    spans = [e['values'] for e in events if e['type'] == 'dev.jvmd.Stage']
    ids = set()
    by_invocation = collections.defaultdict(list)
    for span in spans:
        key = (span['process'], span['span'])
        if key in ids:
            raise ValueError('duplicate native span identity')
        ids.add(key)
        if span['durationNanos'] < 0:
            raise ValueError('negative span duration')
        by_invocation[span.get('invocation') or ''].append(span)
    call_by_invocation = {c['trace']['invocation']: c for c in calls}
    rows = []
    for invocation in sorted(set(by_invocation) | set(call_by_invocation)):
        selected = by_invocation.get(invocation, [])
        processes = {s['process'] for s in selected}
        if len(processes) > 1:
            raise ValueError('invocation crosses JVM clock domains')
        stages = collections.defaultdict(list)
        for span in selected:
            stages[span['stage']].append(span)
        result = {}
        for name, values in stages.items():
            counts = collections.Counter()
            for value in values:
                counts.update(json.loads(value['counters']))
            result[name] = {
                'spans': len(values),
                'intervalUnionNs': str(union_ns([(v['startNanos'], v['startNanos']+v['durationNanos']) for v in values])),
                'inclusiveWorkerNs': str(sum(v['durationNanos'] for v in values)),
                'observedCounters': dict(counts),
                'counterScopeComplete': False,
                'spanIds': [v['span'] for v in values],
            }
        call = call_by_invocation.get(invocation)
        roots = [s for s in selected if s['stage'] == 'rpc.execute']
        rows.append({
            'invocation': invocation,
            'epoch': epoch,
            'nativeClockDomain': f'jvm:{epoch}:{next(iter(processes))}' if processes else None,
            'clientRequest': call,
            'stages': result,
            'nativeRequestIntervalUnionNs': str(union_ns([(s['startNanos'], s['startNanos']+s['durationNanos']) for s in roots])) if roots else None,
            'causalDescendantUnionNs': str(union_ns([(s['startNanos'], s['startNanos']+s['durationNanos']) for s in selected])) if selected else None,
            'clientMinusNativeResidualNs': None,
            'residualStatus': 'unavailable: clock domains and interval boundaries differ',
            'compilerZeroWork': {'status': 'unavailable', 'value': None, 'reason': 'absence of an observed stage is not a complete-scope negative proof'},
        })
    return {'schemaVersion': 1, 'epoch': epoch, 'invocations': rows,
            'semantics': 'Native unions use one JVM clock. Worker sums remain inclusive and may overlap. Detached descendants may end after the root response. No native total is subtracted from client latency.',
            'unmatchedNativeInvocations': [r['invocation'] for r in rows if r['clientRequest'] is None],
            'unmatchedClientInvocations': [r['invocation'] for r in rows if not r['stages']]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--output', type=Path)
    a = parser.parse_args()
    root = a.directory
    output = a.output or root
    output.mkdir(parents=True, exist_ok=True)
    launch = json.loads((root/'launch.json').read_text())
    export_jfr(a.java_home/'bin/jfr', root/'server.jfr', output, a.repo)
    events = json.loads((output/'profile-events.json').read_text())['recording']['events']
    calls = [json.loads(s) for s in (root/'native-calls.jsonl').read_text().splitlines()]
    result = reduce_native(events, calls, launch['epoch'])
    (output/'causal.json').write_text(json.dumps(result, indent=2)+'\n')
    return result


if __name__ == '__main__':
    main()
