#!/usr/bin/env python3
"""Independently verify complete response coverage and real source ranges after timing."""
import argparse, json, re
from pathlib import Path
from urllib.parse import unquote, urlparse

def source_path(uri, worker):
    parsed = urlparse(uri); assert parsed.scheme == 'file', uri
    original = Path(unquote(parsed.path))
    # Archived traces retain original URIs. Resolve generated workspace paths below
    # the extracted worker directory so verification works on another machine.
    offset = next(i for i, part in enumerate(original.parts) if re.fullmatch(r'workspace\d+', part))
    return worker.joinpath(*original.parts[offset:])

def selected(text, range_):
    if isinstance(range_,list):range_={'start':range_[0],'end':range_[1]}
    lines = text.splitlines(keepends=True)
    def offset(position): return sum(len(s) for s in lines[:position['line']])+position['character']
    start, end = offset(range_['start']), offset(range_['end']); assert 0 <= start < end <= len(text)
    return text[start:end]


def workflow_oracle(name, result, fixture, revision=None):
    """Independent expectations from generated sources, never another engine's output."""
    if name=='api_save':return result.get('dirty') is False and result.get('text')==fixture['versions']['API']
    if name == 'open':return bool(result.get('capabilities'))
    if name in ('workspace_completion','reopen_completion'):revision='A' # Newly opened fixture starts at its recorded A source.
    if 'completion' in name:
        rows=result.get('items',[]) if isinstance(result,dict) else result
        rows=[row for row in rows or [] if row.get('label','').startswith(fixture.get('expected',{}).get('method','value'))]
        expected='String' if name=='api_completion' or revision=='API' else fixture.get('expected',{}).get('initial_type','int')
        stale='int' if expected=='String' else 'String'
        return any(re.search(r'\b'+expected+r'\b',json.dumps(r)) for r in rows) and not any(re.search(r'\b'+stale+r'\b',json.dumps(r)) for r in rows)
    if 'definition' in name:
        rows=[result] if isinstance(result,dict) else result
        if len(rows or [])!=1:return False
        row=rows[0]; actual=unquote(urlparse(row['uri']).path)
        if actual!=fixture['files']['provider']:return False
        source=fixture['versions']['API' if name=='api_definition' or revision=='API' else 'A']
        return selected(source,row['range'])==fixture.get('expected',{}).get('method','value')
    if 'diagnostics' in name:
        errors=[d for d in result.get('diagnostics',[]) if d.get('severity')==1]
        if name=='revert_diagnostics':return not errors
        # The only erroneous statement is the typed caller on zero-based line 2.
        return bool(errors) and all((d['range'][0] if isinstance(d['range'],list) else d['range']['start'])['line']==2 for d in errors) and any(
            any(term in d.get('message','').lower() for term in ('string','int','convert','type')) for d in errors)
    if name in ('references','rename_preview'):
        expected=list(fixture['expected']['references'])
        if name=='rename_preview':
            expected.append({'uri':Path(fixture['files']['provider']).as_uri(),'range':fixture['expected']['definition']['range']})
            if any(row.get('newText')!='renamedValue' for row in result):return False
            actual=[{'uri':r['uri'],'range':r['range']} for r in result]
        else:actual=[r for r in result if not (unquote(urlparse(r['uri']).path)==fixture['files']['provider'] and r['range']==fixture['expected']['definition']['range'])]
        key=lambda r:json.dumps(r,sort_keys=True)
        return sorted(map(key,actual))==sorted(map(key,expected))
    if name in ('run_output','hotswap_output'):
        marker=fixture['expected']['B' if name=='run_output' else 'C']
        return marker in result.get('output','') and result.get('pid',0)>0 and (name!='hotswap_output' or result['pid']==result.get('original_pid'))
    if name in ('debug_stop','debug_step'):
        frames=result.get('frames',[])
        if not frames:return False
        frame=frames[0]
        if name=='debug_stop':return frame.get('source_file')==fixture['files']['provider'] and frame.get('line')==fixture['expected']['breakpoint_line']
        return frame.get('source_file')==fixture['files']['main'] and 'main' in frame.get('method','')
    if name=='debug_locals':
        return all(any(v.get('name')==name and v.get('value')==value for v in result.get('locals',[])) for name,value in fixture['expected']['locals'].items())
    raise AssertionError('Missing independent oracle: '+name)


def verify_workflows(root):
    workers=[]
    for path in sorted(root.glob('*/report.json')):
        report=json.loads(path.read_text())
        if 'workflow' not in report:continue
        fixture=json.loads((path.parent/report['fixture']).read_text())
        errors=[];attempts=0
        for action in report['actions']:
            valid=[]
            for attempt in action['attempts']:
                attempts+=1
                try:correct='result' in attempt and workflow_oracle(action['name'],attempt['result'],fixture,action.get('input_revision'))
                except (KeyError,ValueError,AssertionError,IndexError,TypeError):correct=False
                if 'document_version' in action and isinstance(attempt.get('result'),dict) and 'version' in attempt['result']:
                    correct=correct and attempt['result']['version']==action['document_version']
                valid.append(correct)
                if (attempt['outcome']=='correct')!=correct:errors.append(action['name']+': misclassified attempt')
            if action['outcome']=='correct' and (not valid or not valid[-1] or any(valid[:-1])):
                errors.append(action['name']+': invalid first-correct boundary')
            if action.get('retry_count')!=max(0,len(valid)-1):errors.append(action['name']+': retry count')
            if action['outcome']!='correct' and action.get('time_to_correct_ms') is not None:errors.append(action['name']+': failure has success timing')
        required={'warm_completion','warm_definition'}
        if report.get('scenario','language') in ('language','coverage'):required.update({'api_completion','api_diagnostics','api_definition'})
        if report.get('scenario')=='coverage':required.update({'prefix_completion','growth_completion','backspace_completion','broadening_completion','references','rename_preview','revert_diagnostics'})
        if report.get('scenario')=='runtime':required.update({'run_output','debug_stop','debug_locals','debug_step','hotswap_output'})
        if report['outcome']=='correct' and not required.issubset({a['name'] for a in report['actions']}):errors.append('missing required actions')
        if report.get('mode')=='retention' and report['outcome']=='correct':
            snapshots=report.get('retention',{}).get('snapshots',[])
            held=[s['held_views'] for s in snapshots if s['phase'].startswith('held_edit_')]
            released=[s for s in snapshots if s['phase'].startswith('released_edit_')]
            if not held or held!=list(range(1,len(held)+1)) or len(released)!=len(held) or any(s['held_views']!=0 or s['heap_used_bytes']<=0 for s in released):errors.append('invalid retained-view lifecycle')
        workers.append({'worker':path.parent.name,'outcome':report['outcome'],'verified':not errors and report['outcome']=='correct',
                        'attempts':attempts,'errors':errors})
    if not workers:raise AssertionError('No workflow reports')
    return {'schema':1,'workflow_workers':workers,'complete':all(w['verified'] for w in workers)}

def verify(root):
    counts = {'workers': 0, 'editor_responses': 0, 'responses': 0, 'ranges': 0, 'dependency_identity_sets': 0}; identity_sets = {}
    for report_path in sorted(root.glob('*/*/report.json')):
        report = json.loads(report_path.read_text()); assert report['complete'], report_path
        before = counts['responses']
        editor_before = counts['editor_responses']
        fixture = report_path.parent.parent.name.split('-', 1)[0]
        for workspace in report['workspaces']:
            identities = workspace['dependency_identities']; old = identity_sets.setdefault(fixture, identities)
            assert identities == old, (report_path, identities, old); counts['dependency_identity_sets'] += 1
        for path in sorted(report_path.parent.glob('process*/messages.jsonl')):
            pending = {}; partials = {}
            for line in path.read_text().splitlines():
                record = json.loads(line); message = record['message']
                if record['direction'] == 'send' and 'method' in message and 'id' in message: pending[message['id']] = message
                elif record['direction'] == 'receive' and message.get('method') == '$/progress':
                    params = message.get('params', {}); partials.setdefault(params.get('token'), []).extend(params.get('value', []))
                elif record['direction'] == 'receive' and 'method' not in message and 'id' in message and message['id'] in pending:
                    request = pending.pop(message['id']); method = request['method']
                    if method in ('textDocument/hover','textDocument/completion','textDocument/signatureHelp','textDocument/definition','textDocument/references','textDocument/rename','textDocument/documentSymbol'):
                        assert 'error' not in message, message; counts['editor_responses'] += 1
                    if method not in ('textDocument/references', 'textDocument/rename', 'textDocument/definition'): continue
                    assert 'error' not in message, message; value = message['result']; counts['responses'] += 1
                    token = request.get('params', {}).get('partialResultToken')
                    if token in partials and isinstance(value, list): value = partials.pop(token)+value
                    probe = source_path(request['params']['textDocument']['uri'],report_path.parent); expected = set(probe.parent.glob('*.java'))
                    if method == 'textDocument/references': locations = value
                    elif method == 'textDocument/definition':
                        locations = [value] if isinstance(value, dict) else value
                        assert len(locations) == 1 and source_path(locations[0]['uri'],report_path.parent).name.endswith('_0.java'), value
                    else:
                        locations = [{'uri': change['textDocument']['uri'], **edit} for change in value.get('documentChanges', []) for edit in change.get('edits', [])]
                        locations += [{'uri': uri, **edit} for uri, edits in value.get('changes', {}).items() for edit in edits]
                        assert all(location['newText'] == 'doubled' for location in locations), value
                    actual = [source_path(location['uri'],report_path.parent) for location in locations]
                    if method != 'textDocument/definition': assert len(actual) == len(set(actual)) and set(actual) == expected, (path, actual, expected)
                    for location, source in zip(locations, actual):
                        token = selected(source.read_text(), location['range'])
                        assert token == 'twice' or method == 'textDocument/references' and re.fullmatch(r'twice\([^)]*\)', token), (path, token)
                        counts['ranges'] += 1
        expected_responses = sum(sum(len(w['operations_ms'][k]) for k in ('definition','references','rename_preview')) for w in report['workspaces'])
        assert counts['responses']-before == expected_responses, ('incomplete protocol trace',report_path,counts['responses']-before,expected_responses)
        expected_editor = sum(sum(len(v) for v in w['operations_ms'].values())+sum(len(v) for v in w['typing_ms'].values()) for w in report['workspaces'])
        assert counts['editor_responses']-editor_before == expected_editor, ('incomplete editor trace',report_path,counts['editor_responses']-editor_before,expected_editor)
        counts['workers'] += 1
    return counts

if __name__ == '__main__':
    p = argparse.ArgumentParser(); p.add_argument('root', type=Path); p.add_argument('output', type=Path); p.add_argument('--workflows',action='store_true'); a = p.parse_args()
    result = verify_workflows(a.root) if a.workflows else verify(a.root)
    a.output.write_text(json.dumps(result, indent=2)+'\n'); print(json.dumps(result))
    if a.workflows and not result['complete']:raise SystemExit(1)
