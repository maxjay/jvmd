#!/usr/bin/env python3
"""Introduce one new dependency between resident workspaces and check scoped reuse."""
import argparse, json, os, shutil, subprocess, time
from pathlib import Path
from run import CAPABILITIES, SETTINGS, project, start

def core_search(client, session):
    rows=[]; cursor='0'; began=time.perf_counter()
    while cursor is not None:
        result,_=client.call('jvmd/request',{'method':'symbol.find','params':{'session':session,'scope':'deps','name_path':'Type0','substring':True,'kinds':['class'],'limit':20,'cursor':cursor}})
        assert not result['warnings'], result
        rows.extend(result['result']['matches']);cursor=result.get('cursor') if result.get('truncated') else None
    return sorted(row['fqn'] for row in rows), (time.perf_counter()-began)*1000

def main():
    p=argparse.ArgumentParser()
    for name in ('repo','build','java-home','jdtls','resolvers','repository','root'):p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--runs',type=int,default=3);a=p.parse_args()
    for key,value in vars(a).items():
        if isinstance(value,Path):setattr(a,key,value.resolve())
    a.profile=False;a.root.mkdir(parents=True,exist_ok=False);build=json.loads(a.build.read_text())
    original=a.repository;source=a.root/'added-source/fixture/added/Type0.java';source.parent.mkdir(parents=True)
    source.write_text('package fixture.added; public class Type0 { public int marker0; }')
    classes=a.root/'added-classes';classes.mkdir()
    subprocess.run([str(a.java_home/'bin/javac'),'-d',str(classes),str(source)],check=True)
    added=a.root/'added.jar';subprocess.run([str(a.java_home/'bin/jar'),'--create','--file',str(added),'-C',str(classes),'.'],check=True)
    all_results=[]
    for repetition in range(a.runs):
        modes=['jvmd','jdtls-shared'] if repetition%2==0 else ['jdtls-shared','jvmd']
        for server in modes:
            root=a.root/f'{server}-{repetition}';root.mkdir();a.repository=root/'repository';shutil.copytree(original,a.repository)
            client=None;entries=[];original_session=None
            try:
                for i in range(3):
                    if i==2:
                        folder=a.repository/'fixture/added/1';folder.mkdir(parents=True);shutil.copy2(added,folder/'added-1.jar')
                        (folder/'added-1.pom').write_text('<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>added</artifactId><version>1</version></project>')
                    workspace=root/f'workspace{i}';file,prefix=project(workspace,i,24,sorted(a.repository.rglob('*.jar')),'fixture.a0.Type0','marker0')
                    began=time.perf_counter()
                    if client is None:client,_=start(a,server,root/'process0',workspace,root/'state',build)
                    elif server=='jvmd':client.call('jvmd/openWorkspace',{'root':str(workspace)})
                    if i==0 or server=='jvmd':
                        client.call('initialize',{'processId':os.getpid(),'rootUri':workspace.as_uri(),'workspaceFolders':[{'uri':workspace.as_uri(),'name':workspace.name}],
                                                  'capabilities':CAPABILITIES,'initializationOptions':{'settings':SETTINGS}})
                        client.notify('initialized');client.notify('workspace/didChangeConfiguration',{'settings':SETTINGS})
                    else:client.notify('workspace/didChangeWorkspaceFolders',{'event':{'added':[{'uri':workspace.as_uri(),'name':workspace.name}],'removed':[]}})
                    if server!='jvmd':
                        deadline=time.monotonic()+120
                        while True:
                            rows,_=client.call('workspace/symbol',{'query':prefix})
                            if len([r for r in rows if r['name'].startswith(prefix)])==24:break
                            if time.monotonic()>deadline:raise AssertionError(rows)
                            time.sleep(.05)
                    since=len(client.notifications);client.notify('textDocument/didOpen',{'textDocument':{'uri':file.as_uri(),'languageId':'java','version':1,'text':file.read_text()}})
                    client.diagnostics(file.as_uri(),1,False,since);ready=(time.perf_counter()-began)*1000
                    entry={'workspace':i,'ready_ms':ready}
                    if server=='jvmd':
                        response,_=client.call('jvmd/request',{'method':'daemon.status'});entry['status']=response['result']
                        session=next(s['session'] for s in response['result']['sessions'] if s['root']==str(workspace))
                        if i==0:original_session=session
                        identities,search=core_search(client,session)
                    else:
                        began=time.perf_counter();attempts=[]
                        while True:
                            rows,search=client.call('workspace/symbol',{'query':'Type0'})
                            identities=sorted(row['containerName']+'.'+row['name'] for row in rows if row['name']=='Type0')
                            attempts.append({'request_ms':search,'identities':identities})
                            if len(identities)==(129 if i==2 else 128):break
                            if time.perf_counter()-began>30:raise AssertionError(('dependency search remained incomplete',attempts))
                            time.sleep(.05)
                        entry['search_attempts']=attempts
                        if len(attempts)>1:search=(time.perf_counter()-began)*1000
                    expected=sorted([f'fixture.a{n}.Type0' for n in range(128)]+(['fixture.added.Type0'] if i==2 else []))
                    assert identities==expected,(server,i,identities)
                    entry.update(search_ms=search,identities=identities);entries.append(entry)
                if server=='jvmd':
                    old,_=core_search(client,original_session);assert old==entries[0]['identities']
                    first,last=entries[1]['status'],entries[2]['status']
                    assert last['index']['hashes']==first['index']['hashes']+1,(first['index'],last['index'])
                    assert last['classpath_files']['hashes']==first['classpath_files']['hashes']+1,(first['classpath_files'],last['classpath_files'])
                    assert last['index']['timings']['scans']==first['index']['timings']['scans'],(first['index'],last['index'])
                result={'server':server,'repetition':repetition,'workspaces':entries,'complete':True};all_results.append(result)
                (root/'report.json').write_text(json.dumps(result,indent=2)+'\n');print(server,repetition,entries[-1]['ready_ms'],flush=True)
            finally:
                if client:client.close()
    (a.root/'report.json').write_text(json.dumps({'runs':all_results,'complete':True},indent=2)+'\n')

if __name__=='__main__':main()
