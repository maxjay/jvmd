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
    ids = {}
    groups = collections.defaultdict(list)
    def group(span):
        if span.get('invocation'):
            return ('invocation', span['invocation'])
        if span.get('request', 0) > 0:
            return ('native-request', span['process'], span['request'])
        return ('unattributed-span', span['process'], span['span'])
    for span in spans:
        key = (span['process'], span['span'])
        if key in ids:
            raise ValueError('duplicate native span identity')
        ids[key] = span
        if span['durationNanos'] < 0:
            raise ValueError('negative span duration')
        groups[group(span)].append(span)
    call_by_group = {}
    for call in calls:
        invocation = call.get('trace', {}).get('invocation')
        if not invocation:
            raise ValueError('client call has no invocation identity')
        key = ('invocation', invocation)
        if key in call_by_group:
            raise ValueError('duplicate client invocation identity')
        call_by_group[key] = call
    rows = []
    for key in sorted(set(groups) | set(call_by_group)):
        selected = groups.get(key, [])
        invocation = key[1] if key[0] == 'invocation' else None
        processes = {s['process'] for s in selected}
        if len(processes) > 1:
            raise ValueError('invocation crosses JVM clock domains')
        parent_issues = []
        for span in selected:
            parent = span.get('parent', 0)
            seen = {span['span']}
            while parent:
                if parent in seen:
                    raise ValueError('cycle in native span parents')
                seen.add(parent)
                ancestor = ids.get((span['process'], parent))
                if ancestor is None:
                    parent_issues.append({'span': span['span'], 'missingParent': parent})
                    break
                if group(ancestor) != key:
                    raise ValueError('parent belongs to a different request identity')
                parent = ancestor.get('parent', 0)
        stages = collections.defaultdict(list)
        for span in selected:
            stages[span['stage']].append(span)
        result = {}
        for name, values in stages.items():
            counts = collections.Counter()
            for value in values:
                counters = json.loads(value['counters'])
                if any(not isinstance(v, int) or isinstance(v, bool) or v < 0 for v in counters.values()):
                    raise ValueError('invalid observed work counter')
                counts.update(counters)
            result[name] = {
                'spans': len(values),
                'intervalUnionNs': str(union_ns([(v['startNanos'], v['startNanos']+v['durationNanos']) for v in values])),
                'inclusiveWorkerNs': str(sum(v['durationNanos'] for v in values)),
                'observedCounters': dict(counts),
                'counterScopeComplete': False,
                'spanIds': [v['span'] for v in values],
            }
        call = call_by_group.get(key)
        roots = [s for s in selected if s['stage'] == 'rpc.execute']
        rows.append({
            'groupId': json.dumps(key, separators=(',', ':')),
            'identityKind': key[0],
            'invocation': invocation,
            'epoch': epoch,
            'nativeRequestIds': sorted({s.get('request', 0) for s in selected if s.get('request', 0) > 0}),
            'nativeClockDomain': f'jvm:{epoch}:{next(iter(processes))}' if processes else None,
            'clientRequest': call,
            'clientLinkStatus': 'matched explicit invocation' if call and selected else 'unavailable',
            'parentIssues': parent_issues,
            'recordingCompleteness': 'unavailable: parent validation does not prove loss-free recording',
            'stages': result,
            'nativeRequestIntervalUnionNs': str(union_ns([(s['startNanos'], s['startNanos']+s['durationNanos']) for s in roots])) if roots else None,
            'observedSpanUnionNs': str(union_ns([(s['startNanos'], s['startNanos']+s['durationNanos']) for s in selected])) if selected else None,
            'clientMinusNativeResidualNs': None,
            'residualStatus': 'unavailable: clock domains and interval boundaries differ',
            'compilerZeroWork': {'status': 'unavailable', 'value': None, 'reason': 'absence of an observed stage is not a complete-scope negative proof'},
        })
    return {'schemaVersion': 2, 'epoch': epoch, 'invocations': rows,
            'semantics': 'Native unions use one JVM clock. Worker sums remain inclusive and may overlap. Detached descendants may end after the root response. No native total is subtracted from client latency.',
            'identitySemantics': 'Explicit invocation joins client/native records. Untagged requests use process + native request identity; no cross-process or client join is inferred. Unattributed spans remain separate.',
            'unmatchedNativeGroups': [r['groupId'] for r in rows if r['clientRequest'] is None],
            'unmatchedNativeInvocations': [r['invocation'] for r in rows if r['clientRequest'] is None and r['invocation'] is not None],
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
