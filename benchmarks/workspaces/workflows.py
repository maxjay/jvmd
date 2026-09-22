#!/usr/bin/env python3
"""Development workflows in the existing workspace harness; never substitute engines for IDEs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time

from compile import EXPORTS, sha
from modules import workflow_fixture
from resources import ProcessMonitor, export_workflow_jfr
from run import Client, CAPABILITIES, position

HERE = Path(__file__).resolve().parent


def write(path, value):
    path.write_text(json.dumps(value, indent=2)+'\n')


def configuration(a, root, fixture, build, workflow):
    config = root/'config.json'
    write(config, {'jdk_home':str(a.java_home), 'm2_repo':fixture['repository'],
                   'index_on_start':True, 'heap_ceiling_mb':1024})
    java = [str(a.java_home/'bin/java'), *EXPORTS, '--enable-native-access=ALL-UNNAMED',
            '-Xmx1024m', f'-Djvmd.config={config}', f'-Djvmd.state={root / "state"}',
            f'-Djvmd.resolvers={a.resolvers}', '-Djvmd.index.scan.initial_delay_seconds=0']
    if a.mode == 'attribution':
        java += ['-Djvmd.trace=true', f'-XX:StartFlightRecording=filename={root / "server.jfr"},settings=profile,dumponexit=true',
                 '-XX:FlightRecorderOptions=stackdepth=128', '-Xlog:jfr*=off']
    java += ['-cp', build['classpath'], 'dev.jvmd.benchmark.StdioApplication']
    bridge = root/'bridge.json'
    write(bridge, {'repo':str(a.repo), 'root':fixture['roots'][0], 'command':java,
                   'trace':{'workflow':workflow,'revision':'fixture:A→API→B→C'} if a.mode == 'attribution' else None})
    return [a.node, str(HERE/'bridge.ts'), str(bridge)]


def record(report, name, action, correct, timeout=30):
    """Retain every attempt, including errors; waiting is inside the action interval."""
    started=time.monotonic_ns()
    row={'name':name,'start_ns':started,'outcome':'timed_out','attempts':[], 'time_to_correct_ms':None}
    report['actions'].append(row)
    while True:
        before=time.monotonic_ns()
        try:
            value=action(); valid=correct(value)
            attempt={'result':value,'outcome':'correct' if valid else 'wrong_or_stale'}
        except Exception as error:
            valid=False;attempt={'outcome':'error','error':repr(error)}
        ended=time.monotonic_ns()
        attempt.update(start_ns=before,end_ns=ended,latency_ms=(ended-before)/1e6)
        row['attempts'].append(attempt)
        row['first_response_ms']=row['attempts'][0]['latency_ms']
        row['elapsed_ms']=(ended-started)/1e6
        row['retry_count']=len(row['attempts'])-1
        if valid:
            row['outcome']='correct';row['time_to_correct_ms']=row['elapsed_ms'];return value
        if (ended-started)/1e9>=timeout:
            raise AssertionError(f'{name}: no correct result in {timeout}s')
        time.sleep(.05)


def engine(a, root, fixture, build, report):
    command=configuration(a,root,fixture,build,report['workflow'])
    client=Client(command,root)
    provider=Path(fixture['files']['provider']);consumer=Path(fixture['files']['consumer'])
    version=1
    def complete():
        source=consumer.read_text();offset=source.index('.value')+len('.val')
        return client.call('textDocument/completion',{'textDocument':{'uri':consumer.as_uri()},'position':position(source,offset)})[0]
    def valid_completion(result, required):
        items=result.get('items',[]) if isinstance(result,dict) else result
        rows=[item for item in items or [] if item.get('label','').startswith('value')]
        return bool(rows) and any(required in json.dumps(item) for item in rows) and not any(('int' if required=='String' else 'String') in json.dumps(item) for item in rows)
    def definition():
        source=consumer.read_text();offset=source.index('.value')+2
        value=client.call('textDocument/definition',{'textDocument':{'uri':consumer.as_uri()},'position':position(source,offset)})[0]
        return [value] if isinstance(value,dict) else value
    try:
        record(report,'open',lambda: client.call('initialize',{'processId':os.getpid(),'rootUri':Path(fixture['roots'][0]).as_uri(),'workspaceFolders':[{'uri':Path(p).as_uri(),'name':Path(p).name} for p in fixture['roots']],'capabilities':CAPABILITIES})[0],lambda r:bool(r.get('capabilities')))
        client.notify('initialized')
        for name in ('provider','consumer'):
            file=Path(fixture['files'][name]);client.notify('textDocument/didOpen',{'textDocument':{'uri':file.as_uri(),'languageId':'java','version':version,'text':file.read_text()}})
        record(report,'warm_completion',complete,lambda r:valid_completion(r,'int'))
        record(report,'warm_definition',definition,lambda r:bool(r) and r[0]['uri']==provider.as_uri())
        began=time.monotonic_ns();provider.write_text(fixture['versions']['API']);version+=1
        client.notify('textDocument/didChange',{'textDocument':{'uri':provider.as_uri(),'version':version},'contentChanges':[{'text':provider.read_text()}]})
        client.notify('textDocument/didSave',{'textDocument':{'uri':provider.as_uri()}})
        record(report,'api_completion',complete,lambda r:valid_completion(r,'String'))
        client.notify('textDocument/didSave',{'textDocument':{'uri':consumer.as_uri()}})
        def diagnostics():
            rows=[m['params'] for m in client.notifications if m.get('method')=='textDocument/publishDiagnostics' and m.get('params',{}).get('uri')==consumer.as_uri()]
            return rows[-1] if rows else {'diagnostics':[]}
        record(report,'api_diagnostics',diagnostics,lambda r:any(d.get('severity')==1 for d in r['diagnostics']))
        record(report,'api_definition',definition,lambda r:bool(r) and r[0]['uri']==provider.as_uri())
        report['api_edit_to_correct_ms']=(time.monotonic_ns()-began)/1e6
        report['outcome']='correct'
    finally:
        client.close()


def product(a,root,fixture,build,report,backend):
    if a.vscode is None:raise RuntimeError('--vscode is required for an actual VS Code run')
    extension=root/'extension';extension.mkdir()
    shutil.copyfile(HERE/'vscode-package.json',extension/'package.json')
    shutil.copyfile(HERE/'vscode.cjs',extension/'vscode.cjs')
    # Reuse production framing/RpcClient without adding a TypeScript build dependency.
    subprocess.run([a.node,'--input-type=module','-e',
                    'import{stripTypeScriptTypes}from"node:module";import{readFileSync,writeFileSync}from"node:fs";writeFileSync(process.argv[2],stripTypeScriptTypes(readFileSync(process.argv[1],"utf8")));',
                    str(a.repo/'shim/src/transport.ts'),str(extension/'transport.mjs')],check=True,capture_output=True)
    bridge=configuration(a,root,fixture,build,report['workflow']) if backend=='jvmd' else None
    settings=root/'settings.xml'
    settings.write_text('<settings><localRepository>'+fixture['repository']+'</localRepository><offline>true</offline></settings>')
    workspace=root/'fixture.code-workspace'
    write(workspace,{'folders':[{'path':p} for p in fixture['roots']], 'settings':{
        'security.workspace.trust.enabled':False,'java.jdt.ls.java.home':str(a.java_home),
        'java.configuration.maven.userSettings':str(settings),'java.configuration.updateBuildConfiguration':'automatic',
        'java.import.maven.enabled':True,'java.import.gradle.enabled':False,'java.autobuild.enabled':True,
        'java.server.launchMode':'Standard','java.debug.settings.hotCodeReplace':'manual',
        'java.debug.settings.forceBuildBeforeLaunch':True,
        'update.mode':'none','extensions.autoUpdate':False,'telemetry.telemetryLevel':'off'}})
    config={'fixture':fixture,'report':report,'root':str(root),'backend':backend,'bridge':bridge,'samples':a.samples,'runtime':a.runtime}
    write(root/'driver.json',config)
    executable=a.vscode
    if executable.parent.name=='bin' and (executable.parent.parent/'code').is_file():executable=executable.parent.parent/'code'
    command=[str(executable),'--no-sandbox','--disable-gpu','--disable-workspace-trust','--skip-welcome','--skip-release-notes',
             '--user-data-dir',str(root/'user-data'),'--extensions-dir',str(a.extensions),
             '--extensionDevelopmentPath='+str(extension),'--extensionTestsPath='+str(extension/'vscode.cjs'),str(workspace)]
    if backend=='jvmd':command+=['--disable-extension','redhat.java','--disable-extension','vscjava.vscode-java-debug','--disable-extension','vscjava.vscode-java-test']
    write(root/'command.json',command)
    environment=dict(os.environ,JVMD_WORKFLOW_CONFIG=str(root/'driver.json'))
    with (root/'editor.log').open('w') as log:
        process=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT,env=environment,start_new_session=True)
        monitor=ProcessMonitor(process.pid, output=root/"resource-samples.jsonl")
        try:
            status=process.wait(timeout=a.timeout)
            result=root/'driver-result.json'
            if result.exists():report.update(json.loads(result.read_text()))
            if status:raise RuntimeError(f'VS Code exited {status}; see editor.log')
            if report.get('outcome')!='correct':raise AssertionError('VS Code did not produce a checked result')
        finally:
            if process.poll() is None:process.kill();process.wait()
            try:os.killpg(process.pid,signal.SIGTERM)
            except ProcessLookupError:pass
            write(root/'resources.json',monitor.close())


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('repo','build','java-home','resolvers','root'):parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--dependency-cache',type=Path,help='Prepared Maven artifact cache copied equally before each timed process; downloads are excluded')
    parser.add_argument('--vscode',type=Path);parser.add_argument('--extensions',type=Path)
    parser.add_argument('--node',default=shutil.which('node'))
    parser.add_argument('--engines',nargs='+',choices=['engine-jvmd','vscode-jvmd','vscode-java'],default=['vscode-jvmd','vscode-java'])
    parser.add_argument('--mode',choices=['comparison','attribution','retention'],default='comparison')
    parser.add_argument('--runs',type=int,default=5);parser.add_argument('--samples',type=int,default=20)
    parser.add_argument('--sources',type=int,default=4);parser.add_argument('--timeout',type=int,default=240)
    parser.add_argument('--runtime',action='store_true');parser.add_argument('--smoke',action='store_true')
    a=parser.parse_args()
    for key,value in vars(a).items():
        if isinstance(value,Path):setattr(a,key,value.resolve())
    if a.mode=='retention':parser.error('retention workflow is not yet implemented; no retention measurements are claimed')
    if a.smoke:a.runs=1;a.samples=2
    a.root.mkdir(parents=True,exist_ok=False)
    build=json.loads(a.build.read_text())
    provenance={'build':build,'command':sys.argv,'platform':sys.platform,'machine':dict(zip(('sysname','nodename','release','version','machine'),os.uname())),
                'java':subprocess.check_output([str(a.java_home/'bin/java'),'-version'],stderr=subprocess.STDOUT,text=True),
                'node':subprocess.check_output([a.node,'--version'],text=True),'harness':{str(p.relative_to(a.repo)):sha(p) for p in HERE.iterdir() if p.is_file()},
                'cache':{'project':'fresh tool/project state','dependencies':str(a.dependency_cache) if a.dependency_cache else 'generated external dependency only','downloads':'none during measurement; Maven offline','os':'not flushed'},
                'profiles':'attribution timings excluded from comparisons','extensions':{}}
    if a.extensions:
        for p in a.extensions.glob('*/package.json'):
            data=json.loads(p.read_text());provenance['extensions'][data.get('publisher','')+'.'+data['name']]={'version':data['version'],'manifest_sha256':sha(p)}
    write(a.root/'provenance.json',provenance)
    failures=[]
    for repetition in range(a.runs):
        order=a.engines[repetition%len(a.engines):]+a.engines[:repetition%len(a.engines)]
        for name in order:
            root=a.root/f'{name}-{repetition}';root.mkdir()
            report={'schema':1,'workflow':f'library-development-{repetition}-{name}','engine':name,'repetition':repetition,'mode':a.mode,
                    'boundary':'backend-result' if name=='engine-jvmd' else 'VS Code provider readiness','actions':[],
                    'outcome':'unavailable','runtime':a.runtime,'fixture':'fixture/fixture.json','profiles':[],'unmeasured':['visible UI completion','IntelliJ','retained heap']}
            try:
                fixture=workflow_fixture(root/'fixture',a.java_home,a.sources)
                if a.dependency_cache:
                    shutil.copytree(a.dependency_cache,fixture['repository'],dirs_exist_ok=True)
                    # The fixture's local sources must not accidentally resolve from the supplied cache.
                    shutil.rmtree(Path(fixture['repository'])/'workflow',ignore_errors=True)
                if name=='engine-jvmd':engine(a,root,fixture,build,report)
                else:product(a,root,fixture,build,report,name.removeprefix('vscode-'))
            except Exception as error:
                report['outcome']='failed';report['error']=repr(error);failures.append(root.name)
            finally:
                if (root/'server.jfr').exists():
                    try:report['profiles']=[export_workflow_jfr(a.java_home/'bin/jfr',root/'server.jfr',root)]
                    except Exception as error:report['profile_error']=repr(error)
                write(root/'report.json',report)
            print(name,repetition,report['outcome'],report.get('error',''),flush=True)
    write(a.root/'complete.json',{'complete':not failures,'failures':failures})
    if failures:raise SystemExit(1)

if __name__=='__main__':main()
