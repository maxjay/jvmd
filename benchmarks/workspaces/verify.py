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
    lines = text.splitlines(keepends=True)
    def offset(position): return sum(len(s) for s in lines[:position['line']])+position['character']
    start, end = offset(range_['start']), offset(range_['end']); assert 0 <= start < end <= len(text)
    return text[start:end]

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
    p = argparse.ArgumentParser(); p.add_argument('root', type=Path); p.add_argument('output', type=Path); a = p.parse_args()
    result = verify(a.root); a.output.write_text(json.dumps(result, indent=2)+'\n'); print(json.dumps(result))
