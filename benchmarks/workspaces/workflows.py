#!/usr/bin/env python3
"""Development workflows in the existing workspace harness; never substitute engines for IDEs."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time

from compile import EXPORTS, sha
from modules import workflow_fixture, project_fixture
from resources import ProcessMonitor, export_workflow_jfr, workflow_resources
from run import Client, CAPABILITIES, position, first_system_value

HERE = Path(__file__).resolve().parent


def write(path, value):
    temporary=path.with_name(path.name+'.tmp')
    temporary.write_text(json.dumps(value, indent=2)+'\n');temporary.replace(path)


def configuration(a, root, fixture, build, workflow, state=None):
    config = root/'config.json'
    write(config, {'jdk_home':str(a.java_home), 'm2_repo':fixture['repository'],
                   'index_on_start':True, 'heap_ceiling_mb':1024})
    java = [str(a.java_home/'bin/java'), *EXPORTS, '--enable-native-access=ALL-UNNAMED',
            '-Xmx1024m', f'-Djvmd.config={config}', f'-Djvmd.state={state or root / "state"}',
            f'-Djvmd.resolvers={a.resolvers}', '-Djvmd.index.scan.initial_delay_seconds=0']
    if a.mode=='retention':java+=['-Djvmd.benchmark.retention=true','-XX:NativeMemoryTracking=summary']
    if a.mode == 'attribution' or a.instrumentation:
        settings='profile'
        if a.mode!='attribution':
            settings=root/'stages.jfc'
            settings.write_text('<configuration version="2.0" label="JVMD stages" provider="JVMD"><event name="dev.jvmd.Stage"><setting name="enabled">true</setting><setting name="stackTrace">false</setting></event></configuration>')
        java += ['-Djvmd.trace=true',f'-Djvmd.benchmark.workflow={workflow}', f'-XX:StartFlightRecording=filename={root / "server.jfr"},settings={settings},dumponexit=true',
                 '-XX:FlightRecorderOptions=stackdepth=128', '-Xlog:jfr*=off']
    java += ['-cp', build['classpath'], 'dev.jvmd.benchmark.StdioApplication']
    bridge = root/'bridge.json'
    write(bridge, {'repo':str(a.repo), 'root':fixture['roots'][0], 'command':java,
                   'trace':{'workflow':workflow,'revision':'fixture:A→API→B→C'} if a.mode == 'attribution' or a.instrumentation else None})
    return [a.node, str(HERE/'bridge.ts'), str(bridge)]


def record(report, name, action, correct, timeout=30):
    """Retain every attempt, including errors; waiting is inside the action interval."""
    started=time.monotonic_ns()
    row={'name':name,'start_ns':started,'outcome':'timed_out','attempts':[], 'time_to_correct_ms':None}
    report['actions'].append(row)
    while True:
        before=time.monotonic_ns();attempt={}
        value=None
        try:
            value=action();attempt['result']=value;valid=correct(value)
            attempt['outcome']='correct' if valid else ('stale' if 'completion' in name or 'diagnostics' in name else 'wrong')
        except Exception as error:
            valid=False;attempt.update(outcome='error',error=repr(error))
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
    version=1;revision='A'
    def check(name,action,oracle):
        id_=report['workflow']+':'+str(len(report['actions']))+':'+name
        if a.mode=='attribution' or a.instrumentation:client.call('benchmark/traceContext',{'invocation':id_,'revision':revision})
        try:return record(report,name,action,oracle)
        finally:report['actions'][-1].update(id=id_,input_revision=revision)
    def complete():
        source=consumer.read_text();offset=source.index('.value')+len('.val')
        return client.call('textDocument/completion',{'textDocument':{'uri':consumer.as_uri()},'position':position(source,offset)})[0]
    def valid_completion(result, required):
        items=result.get('items',[]) if isinstance(result,dict) else result
        rows=[item for item in items or [] if item.get('label','').startswith('value')]
        return bool(rows) and any(required in json.dumps(item) and re.search(r'\bvalue\s*\(\s*\)',json.dumps(item)) for item in rows) and not any(('int' if required=='String' else 'String') in json.dumps(item) for item in rows)
    def definition():
        source=consumer.read_text();offset=source.index('.value')+2
        value=client.call('textDocument/definition',{'textDocument':{'uri':consumer.as_uri()},'position':position(source,offset)})[0]
        return [value] if isinstance(value,dict) else value
    try:
        check('open',lambda: client.call('initialize',{'processId':os.getpid(),'rootUri':Path(fixture['roots'][0]).as_uri(),'workspaceFolders':[{'uri':Path(p).as_uri(),'name':Path(p).name} for p in fixture['roots']],'capabilities':CAPABILITIES})[0],lambda r:bool(r.get('capabilities')))
        client.notify('initialized')
        for name in ('provider','consumer'):
            file=Path(fixture['files'][name]);client.notify('textDocument/didOpen',{'textDocument':{'uri':file.as_uri(),'languageId':'java','version':version,'text':file.read_text()}})
        check('warm_completion',complete,lambda r:valid_completion(r,'int'))
        check('warm_definition',definition,lambda r:bool(r) and r[0]['uri']==provider.as_uri())
        for _ in range(a.samples):check('unchanged_completion',complete,lambda r:valid_completion(r,'int'))
        revision='API';began=time.monotonic_ns();provider.write_text(fixture['versions']['API']);version+=1
        client.notify('textDocument/didChange',{'textDocument':{'uri':provider.as_uri(),'version':version},'contentChanges':[{'text':provider.read_text()}]})
        client.notify('textDocument/didSave',{'textDocument':{'uri':provider.as_uri()}})
        check('api_completion',complete,lambda r:valid_completion(r,'String'))
        client.notify('textDocument/didSave',{'textDocument':{'uri':consumer.as_uri()}})
        def diagnostics():
            rows=[m['params'] for m in client.notifications if m.get('method')=='textDocument/publishDiagnostics' and m.get('params',{}).get('uri')==consumer.as_uri()]
            return rows[-1] if rows else {'diagnostics':[]}
        check('api_diagnostics',diagnostics,lambda r:any(d.get('severity')==1 for d in r['diagnostics']))
        check('api_definition',definition,lambda r:bool(r) and r[0]['uri']==provider.as_uri())
        report['api_edit_to_correct_ms']=(time.monotonic_ns()-began)/1e6
        if a.mode=='retention':
            session=client.call('jvmd/request',{'method':'daemon.status'})[0]['result']['sessions'][0]['session']
            def rpc(method,params=None):
                result=client.call('jvmd/request',{'method':method,'params':{'session':session,**(params or {})}})[0]
                if result.get('warnings'):
                    report.setdefault('warnings',[]).extend(result['warnings'])
                    if any(w.startswith(('analyzer_fault','diagnostics_superseded')) for w in result['warnings']):raise AssertionError(result)
                return result['result']
            report['retention']={'boundary':'JVMD engine and actual WorkspaceBindings leases','snapshots':[],
                'unmeasured':['VS Code product retention','retained object graph','heap after closing the final workspace']}
            def snapshot(phase,operation='snapshot',inspect=False):
                value=rpc('benchmark.retention',{'operation':operation,'inspect_heap':inspect})
                if value.get('heap_used_bytes',0)<=0:raise AssertionError(value)
                report['retention']['snapshots'].append({'phase':phase,**value})
            snapshot('before_edits',inspect=True)
            for edit in range(a.edits):
                revision='B' if edit%2==0 else 'API'
                provider.write_text(fixture['versions'][revision]);version+=1
                client.notify('textDocument/didChange',{'textDocument':{'uri':provider.as_uri(),'version':version},'contentChanges':[{'text':provider.read_text()}]})
                client.notify('textDocument/didSave',{'textDocument':{'uri':provider.as_uri()}})
                check('body_completion' if revision=='B' else 'api_completion',complete,lambda r:valid_completion(r,'int' if revision=='B' else 'String'))
                snapshot('held_edit_'+str(edit),'hold')
            snapshot('views_released','release',inspect=True)
            for edit in range(a.edits):
                revision='B' if edit%2==0 else 'API'
                provider.write_text(fixture['versions'][revision]);version+=1
                client.notify('textDocument/didChange',{'textDocument':{'uri':provider.as_uri(),'version':version},'contentChanges':[{'text':provider.read_text()}]})
                client.notify('textDocument/didSave',{'textDocument':{'uri':provider.as_uri()}})
                check('body_completion' if revision=='B' else 'api_completion',complete,lambda r:valid_completion(r,'int' if revision=='B' else 'String'))
                snapshot('released_edit_'+str(edit),inspect=edit==a.edits-1)
            opened=[];revision='A'
            for extra in report.get('additional_fixtures',[]):
                extra=json.loads((root/extra).read_text())
                id_=rpc('session.open',{'root':extra['roots'][0]})['session'];opened.append((id_,extra))
                file=Path(extra['files']['consumer']);source=file.read_text()
                params={'session':id_,'method':'textDocument/completion','params':{'textDocument':{'uri':file.as_uri()},'position':position(source,source.index('.value')+4)}}
                check('workspace_completion',lambda:rpc('lsp.request',params)['value'],lambda r:valid_completion(r,'int'))
                snapshot('workspace_open_'+id_)
            for id_,extra in opened:
                rpc('session.close',{'session':id_});snapshot('workspace_closed_'+id_)
                for cycle in range(2):
                    reopened=rpc('session.open',{'root':extra['roots'][0]})['session']
                    file=Path(extra['files']['consumer']);source=file.read_text()
                    params={'session':reopened,'method':'textDocument/completion','params':{'textDocument':{'uri':file.as_uri()},'position':position(source,source.index('.value')+4)}}
                    check('reopen_completion',lambda:rpc('lsp.request',params)['value'],lambda r:valid_completion(r,'int'))
                    rpc('session.close',{'session':reopened});snapshot('reopen_closed_'+reopened)
        report['outcome']='correct'
    finally:
        client.close()


def product(a,root,fixture,build,report,backend,resume=None):
    if a.mode=='retention':raise RuntimeError('Product retention adapter is not implemented; use --engines engine-jvmd for retained-view diagnostics')
    if a.vscode is None:raise RuntimeError('--vscode is required for an actual VS Code run')
    extension=root/'extension';extension.mkdir()
    shutil.copyfile(HERE/'vscode-package.json',extension/'package.json')
    shutil.copyfile(HERE/'vscode.cjs',extension/'vscode.cjs')
    # Reuse production framing/RpcClient without adding a TypeScript build dependency.
    subprocess.run([a.node,'--input-type=module','-e',
                    'import{stripTypeScriptTypes}from"node:module";import{readFileSync,writeFileSync}from"node:fs";writeFileSync(process.argv[2],stripTypeScriptTypes(readFileSync(process.argv[1],"utf8")));',
                    str(a.repo/'shim/src/transport.ts'),str(extension/'transport.mjs')],check=True,capture_output=True)
    bridge=configuration(a,root,fixture,build,report['workflow'],resume/'state' if resume else None) if backend=='jvmd' else None
    settings=root/'settings.xml'
    settings.write_text('<settings><localRepository>'+fixture['repository']+'</localRepository><offline>true</offline></settings>')
    workspace=(resume or root)/'fixture.code-workspace'
    user_data=(resume or root)/'user-data'
    if not resume:
        (user_data/'User').mkdir(parents=True)
        write(user_data/'User/settings.json',{'update.mode':'none','extensions.autoUpdate':False,
            'extensions.autoCheckUpdates':False,'telemetry.telemetryLevel':'off'})
    if not resume:write(workspace,{'folders':[{'path':p} for p in fixture['roots']], 'settings':{
        'security.workspace.trust.enabled':False,'java.jdt.ls.java.home':str(a.java_home),
        'java.configuration.maven.userSettings':str(settings),'java.configuration.updateBuildConfiguration':'automatic',
        'java.import.maven.enabled':True,'java.import.gradle.enabled':False,'java.autobuild.enabled':True,
        'java.server.launchMode':'Standard','java.debug.settings.hotCodeReplace':'manual',
        'java.debug.settings.forceBuildBeforeLaunch':True}})
    config={'fixture':fixture,'report':report,'root':str(root),'backend':backend,'bridge':bridge,'samples':a.samples,'workflow':report['scenario'],'initial_revision':report.get('initial_revision','A')}
    config['expected_extensions']=a.extension_versions
    write(root/'driver.json',config)
    executable=a.vscode
    if executable.parent.name=='bin' and (executable.parent.parent/'code').is_file():executable=executable.parent.parent/'code'
    command=[str(executable),'--no-sandbox','--disable-gpu','--disable-workspace-trust','--skip-welcome','--skip-release-notes',
             '--user-data-dir',str(user_data),'--extensions-dir',str(a.extensions),
             '--extensionDevelopmentPath='+str(extension),'--extensionTestsPath='+str(extension/'vscode.cjs'),str(workspace)]
    if backend=='jvmd':command+=['--disable-extension','redhat.java','--disable-extension','vscjava.vscode-java-debug','--disable-extension','vscjava.vscode-java-test']
    write(root/'command.json',command)
    environment=dict(os.environ,JVMD_WORKFLOW_CONFIG=str(root/'driver.json'))
    with (root/'editor.log').open('w') as log:
        began=time.monotonic()
        report['external_open_start_ms']=began*1000
        process=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT,env=environment,start_new_session=True)
        monitor=ProcessMonitor(process.pid, output=root/"resource-samples.jsonl")
        deadline=began+a.timeout
        ready=None
        try:
            while process.poll() is None:
                if time.monotonic()>deadline:raise TimeoutError('VS Code process deadline exceeded')
                if (root/'clock-request.json').exists() and not (root/'clock-response.json').exists():
                    write(root/'clock-response.json',{'monotonic_ms':time.monotonic_ns()/1e6})
                if ready is None and (root/'driver-result.json').exists():
                    try:
                        progress=json.loads((root/'driver-result.json').read_text())
                        if 'open_to_project_ready_ms' in progress:ready=(time.monotonic()-began)*1000
                    except json.JSONDecodeError:pass
                time.sleep(.01)
            status=process.returncode
            report['external_open_to_ready_ms']=ready
            report['editor_process_ms']=(time.monotonic()-began)*1000
            result=root/'driver-result.json'
            if result.exists():report.update(json.loads(result.read_text()))
            if status:raise RuntimeError(f'VS Code exited {status}; see editor.log')
            if report.get('outcome')!='correct':raise AssertionError('VS Code did not produce a checked result')
        finally:
            if (root/'driver-result.json').exists():
                report.update(json.loads((root/'driver-result.json').read_text()))
            if process.poll() is None:process.kill();process.wait()
            try:os.killpg(process.pid,signal.SIGTERM)
            except ProcessLookupError:pass
            report['debuggee_pids']=sorted({attempt['result']['pid'] for action in report['actions'] if action['name'] in ('run_output','hotswap_output') for attempt in action['attempts'] if isinstance(attempt.get('result'),dict) and attempt['result'].get('pid')})
            write(root/'resources.json',monitor.close(report['debuggee_pids']))
            workflow_resources(report,root/'resource-samples.jsonl')


def finish(a,root,report):
    if (root/'server.jfr').exists():
        try:report['profiles']=[export_workflow_jfr(a.java_home/'bin/jfr',root/'server.jfr',root,a.repo,'profile' if a.mode=='attribution' else 'stages.jfc')]
        except Exception as error:
            report['profile_error']=repr(error)
            if a.mode=='attribution':report['outcome']='failed'
    elif a.mode=='attribution' and 'jvmd' in report['engine']:
        report['profile_error']='Required JVMD recording is missing';report['outcome']='failed'
    write(root/'report.json',report)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('repo','build','java-home','resolvers','root'):parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--project',type=Path,help='Unmodified PetClinic checkout from jvmd-tests/corpus/fetch.sh')
    parser.add_argument('--dependency-cache',type=Path,help='Prepared Maven artifact cache copied equally before each timed process; downloads are excluded')
    parser.add_argument('--vscode',type=Path);parser.add_argument('--extensions',type=Path)
    parser.add_argument('--node',default=shutil.which('node'))
    parser.add_argument('--engines',nargs='+',choices=['engine-jvmd','vscode-jvmd','vscode-java'],default=['vscode-jvmd','vscode-java'])
    parser.add_argument('--mode',choices=['comparison','attribution','retention'],default='comparison')
    parser.add_argument('--runs',type=int,default=5);parser.add_argument('--samples',type=int,default=20)
    parser.add_argument('--sources',type=int,default=4);parser.add_argument('--timeout',type=int,default=240)
    parser.add_argument('--reopen',action='store_true',help='Reopen actual products using the same project paths and persisted editor/server state')
    parser.add_argument('--overhead',action='store_true',help='Serial alternating disabled/enabled stage-JFR pairs for each engine and repetition')
    parser.add_argument('--instrumentation',action='store_true',help='Stage-only JFR in comparison mode for explicit overhead pairs; excluded from ordinary comparisons')
    parser.add_argument('--edits',type=int,default=20)
    parser.add_argument('--workspaces',type=int,default=3)
    parser.add_argument('--workflow',choices=['language','runtime','coverage','background','project'],default='language');parser.add_argument('--smoke',action='store_true')
    a=parser.parse_args()
    for key,value in vars(a).items():
        if isinstance(value,Path):setattr(a,key,value.resolve())
    if a.reopen and 'engine-jvmd' in a.engines:parser.error('--reopen currently uses the product adapter; engine persisted reopen is in run.py')
    if a.workflow=='project' and a.project is None:parser.error('--workflow project requires --project')
    if a.overhead and (a.mode!='comparison' or 'vscode-java' in a.engines):parser.error('overhead requires comparison mode and JVMD engines only')
    if not 1<=a.workspaces<=8:parser.error('--workspaces must be 1..8')
    if not 1<=a.edits<=32:parser.error('--edits must be 1..32 (bounded retained-view diagnostic)')
    if a.smoke:a.runs=1;a.samples=2;a.edits=3
    a.root.mkdir(parents=True,exist_ok=False)
    build=json.loads(a.build.read_text())
    provenance={'build':build,'command':sys.argv,'platform':sys.platform,'machine':dict(zip(('sysname','nodename','release','version','machine'),os.uname())),
                'java':subprocess.check_output([str(a.java_home/'bin/java'),'-version'],stderr=subprocess.STDOUT,text=True),
                'maven':subprocess.check_output([shutil.which('mvn'),'-version'],text=True) if shutil.which('mvn') else 'external Maven unavailable; resolver bundle hashes recorded in build',
                'node':subprocess.check_output([a.node,'--version'],text=True),'harness':{str(p.relative_to(a.repo)):sha(p) for p in HERE.iterdir() if p.is_file()},
                'cache':{'project':'fresh tool/project state','dependencies':str(a.dependency_cache) if a.dependency_cache else 'generated external dependency only','downloads':'none during measurement; Maven offline','os':'not flushed'},
                'hardware':{'logical_cpus':os.cpu_count(),'cpu_models':sorted({line.partition(':')[2].strip() for line in Path('/proc/cpuinfo').read_text().splitlines() if line.startswith('model name')}),'filesystem':subprocess.check_output(['stat','-f','-c','%T',str(a.root)],text=True).strip(),'cpu_quota':first_system_value(('/sys/fs/cgroup/cpu.max',)),'memory_limit':first_system_value(('/sys/fs/cgroup/memory.max',))},
                'profiles':'attribution timings excluded from comparisons','extensions':{}}
    if a.extensions:
        for p in a.extensions.glob('*/package.json'):
            data=json.loads(p.read_text());provenance['extensions'][data.get('publisher','')+'.'+data['name']]={'version':data['version'],'manifest_sha256':sha(p),'server_jars':{str(jar.relative_to(p.parent)):sha(jar) for jar in p.parent.glob('server/**/*.jar')}}
    if a.dependency_cache:
        provenance['cache']['artifact_sha256']={str(p.relative_to(a.dependency_cache)):sha(p)
            for p in sorted(a.dependency_cache.rglob('*')) if p.is_file() and p.suffix in ('.jar','.pom')}
        provenance['cache']['inventory_boundary']='Dependency JAR/POM hashes recorded before timing; OS cache is not flushed'
    write(a.root/'provenance.json',provenance)
    a.extension_versions={id_:extension['version'] for id_,extension in provenance['extensions'].items()}
    failures=[]
    for repetition in range(a.runs):
        order=a.engines[repetition%len(a.engines):]+a.engines[:repetition%len(a.engines)]
        states=([False,True] if repetition%2==0 else [True,False]) if a.overhead else [a.instrumentation]
        for name,instrumentation in [(name,state) for state in states for name in order]:
            a.instrumentation=instrumentation
            suffix=('-stages' if instrumentation else '-disabled') if a.overhead else ''
            root=a.root/f'{name}-{repetition}{suffix}';root.mkdir()
            report={'schema':1,'workflow':f'{a.workflow}-{repetition}-{name}{suffix}','engine':name,'repetition':repetition,'mode':a.mode,'instrumentation':a.instrumentation,'overhead_pair':a.overhead,
                    'boundary':'backend-result' if name=='engine-jvmd' else 'VS Code provider readiness','actions':[],
                    'outcome':'unavailable','cache_state':'fresh project/tool state, dependencies available','scenario':a.workflow,'fixture':'fixture/fixture.json','profiles':[],
                    'unmeasured':['visible UI completion','IntelliJ','product retained heap','dominator retained sizes','processor/resource-generation workflows','structural hot swap','IDE test execution']}
            try:
                if a.workflow=='project':
                    pin=re.search(r'pin=([a-f0-9]{40})',(a.repo/'jvmd-tests/corpus/fetch.sh').read_text()).group(1)
                    fixture=project_fixture(root/'fixture',a.project,pin)
                else:fixture=workflow_fixture(root/'fixture',a.java_home,a.sources)
                if a.dependency_cache:
                    shutil.copytree(a.dependency_cache,fixture['repository'],dirs_exist_ok=True)
                    # The fixture's local sources must not accidentally resolve from the supplied cache.
                    shutil.rmtree(Path(fixture['repository'])/'workflow',ignore_errors=True)
                if a.mode=='retention':
                    report['additional_fixtures']=[]
                    for number in range(1,a.workspaces):
                        extra=workflow_fixture(root/f'fixture{number}',a.java_home,a.sources)
                        report['additional_fixtures'].append(f'fixture{number}/fixture.json')
                if name=='engine-jvmd':
                    if a.workflow!='language':raise ValueError('engine adapter currently supports language workflow only')
                    engine(a,root,fixture,build,report)
                else:product(a,root,fixture,build,report,name.removeprefix('vscode-'))
            except Exception as error:
                report['outcome']='failed';report.setdefault('error',repr(error));report['process_error']=repr(error);failures.append(root.name)
            finally:
                finish(a,root,report)
            if report['outcome']!='correct' and root.name not in failures:failures.append(root.name)
            print(name,repetition,report['outcome'],report.get('error',''),flush=True)
            if a.reopen and report['outcome']=='correct':
                reopened=a.root/(root.name+'-reopen');reopened.mkdir()
                again={key:value for key,value in report.items() if key in ('schema','engine','repetition','mode','instrumentation','boundary','unmeasured')}
                again.update(workflow=report['workflow']+'-reopen',scenario='reopen',cache_state='persisted reopen',
                    initial_revision=report['final_revision'],fixture='../'+root.name+'/fixture/fixture.json',actions=[],profiles=[],outcome='unavailable')
                try:product(a,reopened,fixture,build,again,name.removeprefix('vscode-'),resume=root)
                except Exception as error:
                    again['outcome']='failed';again.setdefault('error',repr(error));failures.append(reopened.name)
                finally:finish(a,reopened,again)
                if again['outcome']!='correct' and reopened.name not in failures:failures.append(reopened.name)
                print(name,repetition,'reopen',again['outcome'],again.get('error',''),flush=True)
    write(a.root/'complete.json',{'complete':not failures,'failures':failures})
    if failures:raise SystemExit(1)

if __name__=='__main__':main()
