#!/usr/bin/env python3
"""Checked production-LSP workloads across distinct roots, edits and persisted restart."""
import argparse, hashlib, json, os, queue, shutil, statistics, subprocess, threading, time
from pathlib import Path
from xml.sax.saxutils import escape
from compile import EXPORTS, sha
from resources import ProcessMonitor, summarize_jfr

CAPABILITIES = {'workspace': {'workspaceFolders': True, 'configuration': True, 'workspaceEdit': {'documentChanges': True}},
                'textDocument': {'publishDiagnostics': {'versionSupport': True}, 'completion': {'completionItem': {'snippetSupport': True}},
                                 'signatureHelp': {}, 'documentSymbol': {'hierarchicalDocumentSymbolSupport': True}}}
SETTINGS = {'java': {'autobuild': {'enabled': False}, 'import': {'maven': {'enabled': False}, 'gradle': {'enabled': False}},
                     'signatureHelp': {'enabled': True}, 'references': {'includeDecompiledSources': False}}}

class Client:
    def __init__(self, command, root, jfr_tool=None, recording=None):
        self.command, self.root = command, root
        self.log = (root/'stderr.log').open('w')
        self.raw = (root/'messages.jsonl').open('w')
        self.records = []
        self.lock = threading.Lock(); self.condition = threading.Condition(); self.next = 0
        self.responses = {}; self.notifications = []; self.failure = None
        self.started = time.perf_counter()
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.log)
        self.monitor = ProcessMonitor(self.process.pid)
        self.jfr_tool, self.recording = jfr_tool, recording
        (root/'command.json').write_text(json.dumps(command, indent=2)+'\n')
        self.reader = threading.Thread(target=self.read, daemon=True); self.reader.start()

    def record(self, direction, message):
        with self.lock:
            line = json.dumps({'elapsed_ms': (time.perf_counter()-self.started)*1000, 'direction': direction, 'message': message})+'\n'
            self.records.append(line); self.raw.write(line); self.raw.flush()

    def send(self, message):
        data = json.dumps(message).encode()
        self.record('send', message)
        self.process.stdin.write(f'Content-Length: {len(data)}\r\n\r\n'.encode()+data); self.process.stdin.flush()

    def read(self):
        try:
            while True:
                headers = {}
                while True:
                    line = self.process.stdout.readline()
                    if not line: raise EOFError('server closed stdout')
                    if line == b'\r\n': break
                    key, value = line.decode().split(':', 1); headers[key.lower()] = value.strip()
                message = json.loads(self.process.stdout.read(int(headers['content-length'])))
                self.record('receive', message)
                if 'method' in message and 'id' in message:
                    result = None
                    if message['method'] == 'workspace/configuration':
                        result = []
                        for item in message['params']['items']:
                            value = SETTINGS
                            for part in item.get('section', '').split('.'):
                                if part: value = value.get(part, {}) if isinstance(value, dict) else None
                            result.append(value)
                    elif message['method'] == 'workspace/workspaceFolders': result = []
                    self.send({'jsonrpc': '2.0', 'id': message['id'], 'result': result})
                else:
                    with self.condition:
                        if 'id' in message: self.responses[message['id']] = message
                        else: self.notifications.append(message)
                        self.condition.notify_all()
        except BaseException as e:
            with self.condition: self.failure = repr(e); self.condition.notify_all()

    def notify(self, method, params=None): self.send({'jsonrpc': '2.0', 'method': method, 'params': params or {}})

    def call(self, method, params=None, timeout=180):
        self.next += 1; ident = self.next; started = time.perf_counter()
        params = dict(params or {})
        partial_token = None
        if method in ('textDocument/definition', 'textDocument/references', 'textDocument/documentSymbol'):
            partial_token = f'benchmark-{ident}'
            params['partialResultToken'] = partial_token
        notification_start = len(self.notifications)
        self.send({'jsonrpc': '2.0', 'id': ident, 'method': method, 'params': params})
        with self.condition:
            if not self.condition.wait_for(lambda: ident in self.responses or self.failure, timeout): raise TimeoutError(method)
            if ident not in self.responses: raise RuntimeError(self.failure)
            response = self.responses.pop(ident)
        elapsed = (time.perf_counter()-started)*1000
        if 'error' in response: raise AssertionError((method, response['error']))
        result = response['result']
        if partial_token is not None:
            chunks = [message['params']['value'] for message in self.notifications[notification_start:]
                      if message.get('method') == '$/progress' and message.get('params', {}).get('token') == partial_token]
            if chunks:
                result = [item for chunk in chunks for item in chunk] + result
        return result, elapsed

    def diagnostics(self, uri, version, error, since, timeout=90):
        def matching():
            for message in reversed(self.notifications[since:]):
                p = message.get('params', {})
                if message.get('method') == 'textDocument/publishDiagnostics' and p.get('uri') == uri and p.get('version', version) == version:
                    diagnostics = p.get('diagnostics', [])
                    if error and any('missingValue' in str(d) for d in diagnostics): return p
                    if not error and not diagnostics: return p
            return None
        with self.condition:
            if not self.condition.wait_for(lambda: matching() is not None or self.failure, timeout): raise TimeoutError(('diagnostics', uri, version, error))
            result = matching()
            if result is None: raise RuntimeError(self.failure)
            return result

    def close(self):
        try:
            if self.process.poll() is None:
                self.call('shutdown', timeout=30); self.notify('exit'); self.process.wait(timeout=30)
            if self.process.returncode != 0: raise RuntimeError(('server exit', self.process.returncode))
        finally:
            if self.process.poll() is None: self.process.kill(); self.process.wait()
            self.reader.join(timeout=5)
            self.log.close(); self.raw.close()
            # Rewrite a closed, complete snapshot after draining stdout. This also makes
            # restored environments independent of partially synchronized open log files.
            (self.root/'messages.jsonl').write_text(''.join(self.records))
            resources = self.monitor.close()
            if self.recording and self.recording.exists():
                resources['jfr'] = summarize_jfr(self.jfr_tool, self.recording)
            (self.root/'resources.json').write_text(json.dumps(resources, indent=2)+'\n')

def position(text, offset):
    # Fixtures are ASCII: Python character offsets equal LSP UTF-16 offsets.
    return {'line': text.count('\n', 0, offset), 'character': offset-text.rfind('\n', 0, offset)-1}

def project(root, number, sources, jars, binary_type, binary_expression):
    root.mkdir(parents=True); package = f'bench.ws{number}'; prefix = f'SymbolRun{number}_'; helper = prefix+'0'
    directory = root/'src/main/java'/package.replace('.', '/'); directory.mkdir(parents=True)
    for i in range(sources):
        name = prefix+str(i)
        if i == 0: body = 'public static int twice(int value){return value*2;}'
        elif i == 1: body = f'int use(){{int local=1;return {helper}.twice(local);}}\nObject binary({binary_type} dependency){{return dependency.{binary_expression};}}'
        else: body = f'int use(){{return {helper}.twice({i});}}'
        (directory/(name+'.java')).write_text(f'package {package};\npublic class {name} {{\n{body}\n}}\n')
    dependencies = ''.join(f'<dependency><groupId>fixture</groupId><artifactId>{jar.parent.parent.name}</artifactId><version>1</version></dependency>' for jar in jars)
    (root/'pom.xml').write_text(f'<project><modelVersion>4.0.0</modelVersion><groupId>bench</groupId><artifactId>workspace{number}</artifactId><version>1</version><properties><maven.compiler.release>25</maven.compiler.release></properties><dependencies>{dependencies}</dependencies></project>')
    wrapper = root/'.mvn/wrapper'; wrapper.mkdir(parents=True)
    (wrapper/'maven-wrapper.properties').write_text('distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip\n')
    (root/'.project').write_text(f'<projectDescription><name>workspace{number}</name><buildSpec><buildCommand><name>org.eclipse.jdt.core.javabuilder</name><arguments/></buildCommand></buildSpec><natures><nature>org.eclipse.jdt.core.javanature</nature></natures></projectDescription>')
    entries = '<classpathentry kind="src" path="src/main/java"/>' + ''.join(f'<classpathentry kind="lib" path="{escape(str(jar))}"/>' for jar in jars)
    (root/'.classpath').write_text('<classpath>'+entries+'<classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/><classpathentry kind="output" path="bin"/></classpath>')
    settings = root/'.settings'; settings.mkdir()
    (settings/'org.eclipse.jdt.core.prefs').write_text('eclipse.preferences.version=1\norg.eclipse.jdt.core.compiler.compliance=25\norg.eclipse.jdt.core.compiler.source=25\norg.eclipse.jdt.core.compiler.codegen.targetPlatform=25\n')
    return directory/(prefix+'1.java'), prefix

def validate(operation, value, count, binary_member):
    if operation in ('hover', 'signature_help'): assert value and 'twice' in json.dumps(value), (operation, value)
    elif operation == 'definition': assert value, value
    elif operation == 'binary_hover': assert binary_member in json.dumps(value), value
    elif 'completion' in operation:
        items = value.get('items', []) if isinstance(value, dict) else value
        assert any((binary_member if operation == 'binary_completion' else 'twice') in item['label'] for item in items), value
    elif operation == 'references': assert len(value) == count and len({r['uri'] for r in value}) == count, (count, value)
    elif operation == 'rename_preview':
        edits = [e for change in value.get('documentChanges', []) for e in change.get('edits', [])] + [e for changes in value.get('changes', {}).values() for e in changes]
        assert len(edits) == count and all(e['newText'] == 'doubled' for e in edits), value
    elif operation == 'document_symbols': assert value, value

def editor(client, file, prefix, a):
    text = file.read_text(); uri = file.as_uri(); document = {'uri': uri}
    call_offset = text.index('twice(')
    specs = [('hover', 'hover', call_offset+1, {}), ('binary_hover', 'hover', text.index(a.binary_member)+2, {}),
             ('binary_completion', 'completion', text.index(a.binary_member)+3, {}),
             ('signature_help', 'signatureHelp', text.index('twice(local')+len('twice(lo'), {}),
             ('definition', 'definition', call_offset+1, {}), ('completion', 'completion', call_offset+3, {}),
             ('references', 'references', call_offset+1, {'context': {'includeDeclaration': True}}),
             ('rename_preview', 'rename', call_offset+1, {'newName': 'doubled'}), ('document_symbols', 'documentSymbol', 0, {})]
    measurements = {}
    for name, method, offset, extra in specs:
        times = []
        for _ in range(a.samples+1):
            value, ms = client.call('textDocument/'+method, {'textDocument': document, 'position': position(text, offset), **extra})
            validate(name, value, a.sources, a.binary_member); times.append(ms)
        measurements[name] = times
    # Distinct changes exercise invalidation and compiler reuse, not an identical-response cache.
    typing = {'completion': [], 'binary_completion': [], 'signature_help': []}; version = 1
    for i in range(a.edits):
        version += 1; edited = text.replace('local=1', f'local={i+10}')
        client.notify('textDocument/didChange', {'textDocument': {'uri': uri, 'version': version}, 'contentChanges': [{'text': edited}]})
        for name, method, offset, extra in specs:
            if name not in typing: continue
            offset = edited.index(a.binary_member)+3 if name == 'binary_completion' else edited.index('twice(')+(len('twice(lo') if name == 'signature_help' else 3)
            value, ms = client.call('textDocument/'+method, {'textDocument': document, 'position': position(edited, offset), **extra})
            validate(name, value, a.sources, a.binary_member); typing[name].append(ms)
    diag_times = {}
    for error in (True, False):
        version += 1; edited = text.replace('twice(local)', 'twice(missingValue)') if error else text
        since = len(client.notifications); started = time.perf_counter()
        client.notify('textDocument/didChange', {'textDocument': {'uri': uri, 'version': version}, 'contentChanges': [{'text': edited}]})
        client.diagnostics(uri, version, error, since)
        diag_times['error' if error else 'restore'] = (time.perf_counter()-started)*1000
    assert file.read_text() == text
    return {'operations_ms': measurements, 'typing_ms': typing, 'diagnostics_ms': diag_times}

def start(a, server, run_root, workspace, state, build):
    run_root.mkdir(parents=True)
    java = str(a.java_home/'bin/java')
    recording = run_root/'server.jfr' if a.profile else None
    profile_args = ([f'-XX:StartFlightRecording=filename={recording},settings=profile,dumponexit=true',
                     '-XX:FlightRecorderOptions=stackdepth=128', '-Xlog:jfr=warning'] if a.profile else [])
    if server == 'jvmd':
        config = run_root/'config.json'; config.write_text(json.dumps({'jdk_home': str(a.java_home), 'm2_repo': str(a.repository), 'heap_ceiling_mb': 1024, 'index_on_start': True}))
        command = [java, '-Xmx1024m', *EXPORTS, '--enable-native-access=ALL-UNNAMED', f'-Djvmd.config={config}', f'-Djvmd.state={state}',
                   f'-Djvmd.resolvers={a.resolvers}', '-Djvmd.index.scan.initial_delay_seconds=0', '-cp', build['classpath'], 'dev.jvmd.benchmark.StdioApplication']
        command[1:1] = profile_args
        adapter = run_root/'bridge.json'; adapter.write_text(json.dumps({'repo': str(a.repo), 'root': str(workspace), 'command': command}))
        client = Client(['node', str(Path(__file__).resolve().parent/'bridge.ts'), str(adapter)], run_root,
                        a.java_home/'bin/jfr', recording)
        deadline = time.monotonic()+180
        while True:
            value, _ = client.call('jvmd/request', {'method': 'daemon.status'}); status = value['result']['index']
            if status.get('phase') == 'ready' and status.get('timings', {}).get('scans', 0) >= 1: break
            if status.get('phase') == 'failed' or time.monotonic() >= deadline: raise AssertionError(status)
            time.sleep(.02)
        return client, (time.perf_counter()-client.started)*1000
    launcher = next((a.jdtls/'plugins').glob('org.eclipse.equinox.launcher_*.jar'))
    command = [java, *profile_args, '-Xmx1024m', '-Declipse.application=org.eclipse.jdt.ls.core.id1', '-Dosgi.bundles.defaultStartLevel=4',
               '-Declipse.product=org.eclipse.jdt.ls.core.product', '-Dlog.level=WARNING', '--add-modules=ALL-SYSTEM',
               '--add-opens', 'java.base/java.util=ALL-UNNAMED', '--add-opens', 'java.base/java.lang=ALL-UNNAMED',
               '-jar', str(launcher), '-configuration', str(a.jdtls/'config_linux'), '-data', str(state)]
    return Client(command, run_root, a.java_home/'bin/jfr', recording), None

def run(a, server, iteration, build):
    run_root = a.root/f'{server}-{iteration}'; run_root.mkdir(parents=True, exist_ok=False)
    jars = sorted(a.repository.rglob('*.jar'))
    projects = [project(run_root/f'workspace{i}', i, a.sources, jars, a.dependency_type, a.binary_expression) for i in range(a.workspaces+1)]
    results = []; client = None; prior_state = run_root/'state0'
    try:
        for i, (file, prefix) in enumerate(projects):
            workspace = run_root/f'workspace{i}'; restart = i == a.workspaces
            spawn = i == 0 or restart or server == 'jdtls-isolated'
            if spawn and client: client.close(); client = None
            before = time.perf_counter(); seed = None
            if spawn:
                state = prior_state if restart else run_root/f'state{i}'
                client, seed = start(a, server, run_root/f'process{i}', workspace, state, build)
            elif server == 'jvmd': client.call('jvmd/openWorkspace', {'root': str(workspace)})
            if spawn or server == 'jvmd':
                client.call('initialize', {'processId': os.getpid(), 'rootUri': workspace.as_uri(), 'workspaceFolders': [{'uri': workspace.as_uri(), 'name': workspace.name}],
                                           'capabilities': CAPABILITIES, 'initializationOptions': {'settings': SETTINGS, 'extendedClientCapabilities': {'classFileContentsSupport': True}}})
                client.notify('initialized'); client.notify('workspace/didChangeConfiguration', {'settings': SETTINGS})
            else: client.notify('workspace/didChangeWorkspaceFolders', {'event': {'added': [{'uri': workspace.as_uri(), 'name': workspace.name}], 'removed': []}})
            if server != 'jvmd':
                deadline = time.monotonic()+120
                while True:
                    value, _ = client.call('workspace/symbol', {'query': prefix})
                    if len([s for s in value if s['name'].startswith(prefix)]) == a.sources: break
                    if time.monotonic() > deadline: raise AssertionError(('source readiness', prefix, value))
                    time.sleep(.05)
            since = len(client.notifications)
            client.notify('textDocument/didOpen', {'textDocument': {'uri': file.as_uri(), 'languageId': 'java', 'version': 1, 'text': file.read_text()}})
            client.diagnostics(file.as_uri(), 1, False, since)
            ready = (time.perf_counter()-before)*1000
            entry = {'workspace': i, 'restart': restart, 'ready_ms': ready, 'preindex_ms': seed, **editor(client, file, prefix, a)}
            if server == 'jvmd':
                status, _ = client.call('jvmd/request', {'method': 'daemon.status'}); entry['daemon_status'] = status['result']
                session = next(s['session'] for s in status['result']['sessions'] if s['root'] == str(workspace))
                status, _ = client.call('jvmd/request', {'method': 'session.status', 'params': {'session': session}}); entry['session_status'] = status['result']
                started = time.perf_counter(); rows = []; cursor = '0'
                while cursor is not None:
                    response, _ = client.call('jvmd/request', {'method': 'symbol.find', 'params': {'session': session, 'name_path': a.query, 'substring': True, 'scope': 'deps', 'kinds': ['class'], 'limit': 20, 'cursor': cursor}})
                    assert not response.get('warnings'), response
                    rows.extend(response['result']['matches']); cursor = response.get('cursor') if response.get('truncated') else None
                entry['search_ms'] = (time.perf_counter()-started)*1000
                identities = sorted(r['fqn'] for r in rows if r['fqn'] == a.dependency_type or a.query == 'Type0')
            else:
                began = time.perf_counter(); attempts = []
                while True:
                    rows, elapsed = client.call('workspace/symbol', {'query': a.jdtls_query})
                    identities = sorted((r.get('containerName', '')+'.'+r['name']).strip('.') for r in rows if r['name'] == a.query)
                    if a.query != 'Type0': identities = [name for name in identities if name == a.dependency_type]
                    attempts.append({'request_ms': elapsed, 'matching_identities': identities})
                    if len(identities) == a.expected_results and len(set(identities)) == a.expected_results: break
                    if time.perf_counter()-began > 30: raise AssertionError(('dependency search remained incomplete', attempts))
                    time.sleep(.05)
                entry['search_attempts'] = attempts
                entry['search_ms'] = elapsed if len(attempts) == 1 else (time.perf_counter()-began)*1000
            assert len(identities) == a.expected_results and len(set(identities)) == a.expected_results, identities
            entry['dependency_identities'] = identities; results.append(entry)
            (run_root/'report.json').write_text(json.dumps({'server': server, 'iteration': iteration, 'build_revision': build['revision'], 'workspaces': results, 'complete': False}, indent=2)+'\n')
            print(json.dumps({'server': server, 'iteration': iteration, 'workspace': i, 'ready_ms': ready}), flush=True)
        client.close(); client = None
        report = {'server': server, 'iteration': iteration, 'build_revision': build['revision'], 'workspaces': results, 'complete': True}
        (run_root/'report.json').write_text(json.dumps(report, indent=2)+'\n'); return report
    finally:
        if client: client.close()

def main():
    p = argparse.ArgumentParser()
    for name in ('repo', 'build', 'java-home', 'jdtls', 'resolvers', 'repository', 'root'): p.add_argument('--'+name, type=Path, required=True)
    p.add_argument('--servers', nargs='+', choices=['jvmd', 'jdtls-shared', 'jdtls-isolated'], default=['jvmd', 'jdtls-shared', 'jdtls-isolated'])
    for name, default in [('sources', 24), ('workspaces', 3), ('samples', 5), ('runs', 3), ('edits', 8), ('expected-results', 1)]: p.add_argument('--'+name, type=int, default=default)
    p.add_argument('--dependency-type', default='com.fasterxml.jackson.databind.ObjectMapper'); p.add_argument('--binary-expression', default='getFactory()')
    p.add_argument('--binary-member', default='getFactory'); p.add_argument('--query', default='ObjectMapper'); p.add_argument('--jdtls-query', default='com.fasterxml.jackson.databind.ObjectMapper'); p.add_argument('--profile', action='store_true')
    a = p.parse_args()
    for key, value in vars(a).items():
        if isinstance(value, Path): setattr(a, key, value.resolve())
    a.root.mkdir(parents=True, exist_ok=False); build = json.loads(a.build.read_text())
    metadata = {k: str(v) if isinstance(v, Path) else v for k,v in vars(a).items()}
    metadata['dependencies'] = {str(f): sha(f) for f in sorted(a.repository.rglob('*.jar'))}; metadata['build'] = build
    metadata['harness'] = {f.name: sha(f) for f in sorted(Path(__file__).parent.iterdir()) if f.is_file()}
    metadata['node'] = subprocess.check_output(['node', '--version'], text=True).strip(); metadata['jdk'] = subprocess.run([str(a.java_home/'bin/java'), '-version'], capture_output=True, text=True).stderr
    for key, file in [('cpu_quota', '/sys/fs/cgroup/cpu.max'), ('memory_limit', '/sys/fs/cgroup/memory.max')]: metadata[key] = Path(file).read_text().strip()
    (a.root/'metadata.json').write_text(json.dumps(metadata, indent=2)+'\n')
    for i in range(a.runs):
        order = a.servers[i % len(a.servers):]+a.servers[:i % len(a.servers)]
        for server in order: run(a, server, i, build)
    (a.root/'complete.json').write_text(json.dumps({'runs': a.runs*len(a.servers), 'workspaces': a.runs*len(a.servers)*(a.workspaces+1)})+'\n')

if __name__ == '__main__': main()
