"""Recheck native lifecycle semantics from recorded wire replies and state witnesses.

No server is launched. A pass flag is never an oracle. Positive witnesses are
linked to responses inside the recorded client interval; missing replies fail.
This does not infer complete native work or machine-index reuse from status IDs.
"""
import hashlib
import json
import re
from urllib.parse import unquote

DISK = 'public class Value { public String marker = "disk"; }\n'
DISK_SHA = hashlib.sha256(DISK.encode()).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'))


def markup(value):
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return '\n'.join(map(markup, value))
    return value.get('value', '') if isinstance(value, dict) else ''


def hover(value, type_):
    content = markup(value.get('contents'))
    assert re.search(r'\bmarker\b', content), 'hover symbol missing'
    assert re.search(r'\b' + type_ + r'\b', content), 'hover type wrong'


def completion(value, required, forbidden=()):
    items = value if isinstance(value, list) else value['items']
    assert isinstance(items, list), 'completion list missing'
    assert all(isinstance(i.get('label'), str) and i['label'] for i in items), 'invalid completion label'
    names = [re.split(r'[(:\s]', i['label'])[0] for i in items]
    assert all(n in names for n in required), 'required completion missing'
    assert not any(n in names for n in forbidden), 'forbidden completion present'


class Replies:
    def __init__(self, root):
        self.rows = {}
        self.calls = []
        self.events = []
        for file in root.glob('*/native-calls.jsonl'):
            for line in file.read_text().splitlines():
                call = json.loads(line)
                self.calls.append(call)
                if call.get('outcome') == 'pass' and 'result' in call:
                    self.add(call['result'], call['endNs'])
                    if isinstance(call['result'], dict) and 'result' in call['result']:
                        self.add(call['result']['result'], call['endNs'])
        for file in root.glob('*/peer-*/events.jsonl'):
            for line in file.read_text().splitlines():
                event = json.loads(line)
                self.events.append(event)
                message = event.get('message', {})
                if event.get('direction') == 'receive' and 'result' in message:
                    self.add(message['result'], event['timeNs'])

    def add(self, value, time):
        self.rows.setdefault(canonical(value), []).append(int(time))

    def contains(self, value, interval):
        assert any(int(interval['startNs']) <= t <= int(interval['endNs'])
                   for t in self.rows.get(canonical(value), [])), 'witness absent from wire replies in its interval'


def replay_case(row, plan, replies):
    """Return issues; failed cases stay failed while their claimed good probes are checked."""
    issues = []
    def check(label, action):
        try:
            action()
        except (AssertionError, KeyError, TypeError, ValueError, IndexError) as error:
            issues.append(row['id'] + ': ' + label + ': ' + str(error))
    def reply(value, interval=None):
        replies.contains(value, interval or row)
    def h(value, type_, interval=None):
        hover(value, type_); reply(value, interval)
    def c(value, required, forbidden=(), interval=None):
        completion(value, required, forbidden); reply(value, interval)
    def witness(value, type_='String'):
        h(value['result'], type_); reply(value['state'])
        assert value['diskSha256'] == DISK_SHA, 'saved disk input differs from fixed fixture'
    def run():
        id_ = row['id']
        if id_ in ('LIFE-01', 'LIFE-02', 'LIFE-03', 'LIFE-05', 'LIFE-06'):
            witness(row['witness'])
        if id_ == 'LIFE-01':
            reply(row['machine'])
            assert row['initialStore'] == 'absent before launch'
            assert row['aot'] == row['machine'].get('aot_cache', {'status':'unavailable'})
        elif id_ == 'LIFE-02':
            assert row['persistedInputs'], 'restart has no retained state inventory'
            reply(row['machine'])
        elif id_ == 'LIFE-03':
            assert row['daemonEpoch'], 'resident epoch missing'
        elif id_ == 'LIFE-04':
            for key in ('before', 'afterA', 'afterB'):
                witness(row[key], 'int')
            states = [row[k]['state'] for k in ('before', 'afterA', 'afterB')]
            assert len({s['session'] for s in states}) == 1, 'attachment replaced session'
            assert all(s['documents'] == states[0]['documents'] for s in states), 'attachment replaced live documents'
            assert states[0]['documents']['open_documents'] > 0
            assert row['documentVersion'] == 2
            assert any(call['method'] == 'lsp.diagnostics' and call.get('result', {}).get('result', {}).get('value', {}).get('version') == 2
                       and int(row['startNs']) <= int(call['endNs']) <= int(row['endNs']) for call in replies.calls), 'live document version has no diagnostic witness'
        elif id_ == 'LIFE-05':
            reply(row['afterDetach'])
            assert row['afterDetach']['documents']['open_documents'] == 0
            assert row['afterDetach']['session'] == row['retainedSession'] == row['witness']['state']['session']
            if plan['profile'] == 'pipe':
                assert any(c['method'] == 'document.close' for c in row['detachCalls'])
                assert not any(c['method'] == 'session.close' for c in row['detachCalls'])
        elif id_ == 'LIFE-06':
            reply(row['afterDisposal'])
            assert row['previousSession'] != row['newSession'] == row['witness']['state']['session']
            assert all(s['session'] != row['previousSession'] for s in row['afterDisposal']['sessions'])
        elif id_ == 'LIFE-07':
            witness(row['unaffected']); witness(row['firstWorkspaceAfter'])
            assert len(row['operations']) == 2
            assert len({o['session'] for o in row['operations']}) == 2
            assert len({o['root'] for o in row['operations']}) == 2
            for op in row['operations']:
                c(op['result'], ['original'], ['next']); reply(op['state'])
                assert op['session'] == op['state']['session']
        elif id_ == 'LIFE-08':
            wanted = ['identical-new-path', 'new-artifact', 'source-attachment', 'same-coordinate-bytes', 'removal']
            assert [t['mutation'] for t in row['transitions']] == wanted
            for transition in row['transitions']:
                changed = [p for p in set(transition['before']) | set(transition['after']) if transition['before'].get(p) != transition['after'].get(p)]
                assert len(changed) == 1 and set(changed) == set(transition['changed'])
                assert int(transition['triggerNs']) <= int(transition['firstCorrectNs']) <= int(transition['settledNs']) <= int(row['endNs'])
                assert transition['attempts'] >= 2
        elif id_ == 'LIFE-09':
            for key in ('before','pressureState','after'):
                reply(row[key])
            witness(row['recovered'])
            eviction = row['eviction']; cache = row['pressureState']['analyzer']['resident_accessibility_cache']
            assert eviction['status'] == 'measured' and eviction['observed'] == cache and cache['evictions'] > 0
            assert eviction['owner']['session'] == row['pressureState']['session']
            pressures = [o['receiver'] for o in row['operations'] if o.get('kind') == 'accessibility_cache_pressure']
            assert pressures == list(range(24)) + [0], 'pressure/recovery workload incomplete'
            assert [o['edit'] for o in row['operations'] if 'version' in o] == list(range(plan['edits'])), 'edit workload incomplete'
            assert [o['version'] for o in row['operations'] if 'version' in o] == list(range(2, plan['edits']+2))
        elif id_ == 'LIFE-10':
            assert int(row['idleEndNs']) - int(row['idleStartNs']) >= plan['idleMs'] * 1000000
            assert not any(e.get('direction') == 'send' and int(row['idleStartNs']) < int(e['timeNs']) < int(row['idleEndNs']) for e in replies.events), 'idle interval contains a peer request'
            assert not any(c['method'] != 'lsp.diagnostics' and int(row['idleStartNs']) < int(c['startNs']) < int(row['idleEndNs']) for c in replies.calls), 'idle interval contains an explicit native call'
            # The shim may finish scheduled diagnostics while the client is idle.
            # That work stays measured; idle is not a claim of server quiescence.
            assert [o['iteration'] for o in row['operations']] == list(range(plan['activeQueries']))
            reply(row['after'])
        elif id_ == 'APACHE/document-open':
            reply(row['state']); reply(row['result'])
            values = row['result'] if isinstance(row['result'], list) else [row['result']]
            assert len(values) == 1, 'definition cardinality differs'
            value = values[0]; uri = value.get('uri', value.get('targetUri'))
            provider = [e['message']['params']['textDocument'] for e in replies.events if e.get('direction') == 'send' and e.get('message', {}).get('method') == 'textDocument/didOpen'
                        and e['message']['params']['textDocument']['uri'] == uri]
            assert provider and unquote(uri).endswith('/org/apache/maven/project/MavenProject.java')
            text = provider[-1]['text']; start = text.index('MavenProject', text.index('class MavenProject')); prefix = text[:start]
            line = prefix.count('\n'); character = len(prefix.rsplit('\n', 1)[-1].encode('utf-16-le'))//2
            assert value.get('targetSelectionRange', value.get('range')) == {'start':{'line':line,'character':character},'end':{'line':line,'character':character+12}}, 'definition selects the wrong range'
        else:
            assert id_ in ('LIFE-01', 'LIFE-02', 'LIFE-03'), 'unknown native case has no replay oracle'
    if row['outcome'] == 'pass':
        check('semantic replay', run)
    for i, op in enumerate(row.get('operations', [])):
        if op.get('outcome') != 'pass':
            continue
        def operation():
            kind = op.get('kind')
            if kind == 'completion_visibility':
                expected = 'next' if op['mutation'] == 'same-coordinate-bytes' else 'original'
                c(op['result'], [] if op['mutation'] == 'removal' else [expected], ['original','next'] if op['mutation'] == 'removal' else ['original' if expected == 'next' else 'next'], op)
            elif kind == 'source_attachment_resolve':
                c(op['origin'], ['original'], interval=op)
                assert op['item'] in op['origin']['items'], 'resolve object does not originate in completion'
                assert 'MEMBER_DOC_ATTACHED_V2' in markup(op['result'].get('documentation'))
                reply(op['result'], op)
            elif kind == 'unaffected_workspace':
                h(op['result'], 'String', op)
            elif kind == 'accessibility_cache_pressure':
                c(op['result'], ['unique'+str(op['receiver'])], interval=op)
            elif 'edit' in op and 'version' in op:
                h(op['result'], 'String' if op['edit'] % 2 else 'int', op)
            elif 'iteration' in op:
                h(op['result'], 'String', op); reply(op['maintenance'], op)
            elif kind == 'observer_status_poll':
                reply(op['result'], op)
        check('operation '+str(i), operation)
    return issues
