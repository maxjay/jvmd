#!/usr/bin/env python3
"""Saved base-module API changes with JDTLS autobuild enabled and checked consumers."""
import argparse,json,os,re,statistics,subprocess,time
from pathlib import Path
from run import CAPABILITIES,SETTINGS,position,start

def fixture(root,count):
    modules={'base':[], 'core':['base'], 'app':['core'], 'independent':[]}
    root.mkdir(parents=True);wrapper=root/'.mvn/wrapper';wrapper.mkdir(parents=True)
    (wrapper/'maven-wrapper.properties').write_text('distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip\n')
    header='<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><version>1</version>'
    (root/'pom.xml').write_text(header+'<artifactId>reactor</artifactId><packaging>pom</packaging><modules>'+''.join('<module>'+m+'</module>' for m in modules)+'</modules></project>')
    probes={}
    for module,deps in modules.items():
        project=root/module;directory=project/'src/main/java/bench'/module;directory.mkdir(parents=True)
        dependencies=''.join('<dependency><groupId>fixture</groupId><artifactId>'+d+'</artifactId><version>1</version></dependency>' for d in deps)
        (project/'pom.xml').write_text(header+'<artifactId>'+module+'</artifactId><properties><maven.compiler.release>25</maven.compiler.release></properties><dependencies>'+dependencies+'</dependencies></project>')
        (project/'.project').write_text('<projectDescription><name>'+module+'</name><buildSpec><buildCommand><name>org.eclipse.jdt.core.javabuilder</name><arguments/></buildCommand></buildSpec><natures><nature>org.eclipse.jdt.core.javanature</nature></natures></projectDescription>')
        (project/'.classpath').write_text('<classpath><classpathentry kind="src" path="src/main/java"/>'+''.join('<classpathentry kind="src" path="/'+d+'" exported="true"/>' for d in deps)+'<classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/><classpathentry kind="output" path="target/classes"/></classpath>')
        settings=project/'.settings';settings.mkdir();(settings/'org.eclipse.jdt.core.prefs').write_text('eclipse.preferences.version=1\norg.eclipse.jdt.core.compiler.compliance=25\norg.eclipse.jdt.core.compiler.source=25\norg.eclipse.jdt.core.compiler.codegen.targetPlatform=25\n')
        for i in range(count):
            name='ModBench'+module.title()+str(i)
            expression='1' if not deps else 'bench.'+deps[0]+'.ModBench'+deps[0].title()+'0.value()'
            # Independent probe has a member access too, without a base/core dependency.
            if module=='independent' and i==0:expression='ModBenchIndependent1.value()'
            file=directory/(name+'.java');file.write_text('package bench.'+module+'; public class '+name+' { public static int value(){return '+expression+';} }\n')
            if i==0:probes[module]=file
    return probes

def checked_completion(client,file,expected):
    text=file.read_text();offset=text.index('.value()')+len('.val');began=time.perf_counter();attempts=[]
    while True:
        result,elapsed=client.call('textDocument/completion',{'textDocument':{'uri':file.as_uri()},'position':position(text,offset)})
        items=result.get('items',[]) if isinstance(result,dict) else result
        values=[x for x in items if x['label'].startswith('value')]
        correct=any(re.search(r'\b'+expected+r'\b',json.dumps(x)) for x in values)
        attempts.append({'request_ms':elapsed,'values':values,'correct':correct})
        if correct:return {'time_to_correct_ms':(time.perf_counter()-began)*1000,'first_request_ms':attempts[0]['request_ms'],'attempts':attempts}
        if time.perf_counter()-began>15:raise AssertionError(('stale/missing cross-module completion',file,expected,attempts))
        time.sleep(.02)

def diagnostics(client,file,error,since):
    def match():
        for message in reversed(client.notifications[since:]):
            p=message.get('params',{})
            if message.get('method')=='textDocument/publishDiagnostics' and p.get('uri')==file.as_uri():
                errors=[d for d in p.get('diagnostics',[]) if d.get('severity')==1]
                if bool(errors)==error:return p
        return None
    with client.condition:
        assert client.condition.wait_for(lambda:match() is not None or client.failure,30),('dependent diagnostics',file,error)
        value=match();assert value is not None,client.failure;return value

def worker(a,mode,repetition,build):
    root=a.root/(mode+'-'+str(repetition));root.mkdir();probes=fixture(root/'workspace0',a.sources_per_module)
    a.repository=root/'repository';a.repository.mkdir();client=None;session=None
    server='jdtls-shared' if mode=='jdtls' else 'jvmd';began=time.perf_counter()
    try:
        client,_=start(a,server,root/'process0',root/'workspace0',root/'state0',build)
        client.call('initialize',{'processId':os.getpid(),'rootUri':(root/'workspace0').as_uri(),'workspaceFolders':[{'uri':(root/'workspace0').as_uri(),'name':'reactor'}],'capabilities':CAPABILITIES,'initializationOptions':{'settings':SETTINGS,'extendedClientCapabilities':{'classFileContentsSupport':True}}})
        client.notify('initialized');client.notify('workspace/didChangeConfiguration',{'settings':SETTINGS})
        if server!='jvmd':
            deadline=time.monotonic()+120
            while True:
                rows,_=client.call('workspace/symbol',{'query':'ModBench'})
                if len([r for r in rows if r['name'].startswith('ModBench')])==4*a.sources_per_module:break
                if time.monotonic()>deadline:raise AssertionError(('source readiness',rows))
                time.sleep(.05)
        for file in probes.values():client.notify('textDocument/didOpen',{'textDocument':{'uri':file.as_uri(),'languageId':'java','version':1,'text':file.read_text()}})
        for file in probes.values():diagnostics(client,file,False,0)
        ready=(time.perf_counter()-began)*1000
        result={'mode':mode,'repetition':repetition,'build':build,'ready_ms':ready,'settings':SETTINGS,'sources_per_module':a.sources_per_module,'warm':{},'changes':[],'complete':False}
        for name in ['core','app','independent']:result['warm'][name]=[checked_completion(client,probes[name],'int') for _ in range(4)]
        def status():
            nonlocal session
            if server!='jvmd':return None
            if session is None:
                response,_=client.call('jvmd/request',{'method':'daemon.status'});session=response['result']['sessions'][0]['session']
            response,_=client.call('jvmd/request',{'method':'session.status','params':{'session':session}});return response['result']
        result['before']=status();base=probes['base'];version=1
        changes=[('body','int','2'),('signature','String','"changed"'),('body','String','"again"'),('signature','int','3')]*2
        for index,(kind,type_,value) in enumerate(changes):
            before=status();version+=1;text='package bench.base; public class ModBenchBase0 { public static '+type_+' value(){return '+value+';} } // '+str(index)+'\n'
            since=len(client.notifications);started=time.perf_counter();base.write_text(text)
            client.notify('textDocument/didChange',{'textDocument':{'uri':base.as_uri(),'version':version},'contentChanges':[{'text':text}]})
            client.notify('textDocument/didSave',{'textDocument':{'uri':base.as_uri()}})
            core=checked_completion(client,probes['core'],type_)
            change={'kind':kind,'return_type':type_,'saved_change_to_correct_completion_ms':(time.perf_counter()-started)*1000,'core':core,'app':checked_completion(client,probes['app'],'int'),'independent':checked_completion(client,probes['independent'],'int'),'before':before}
            # JVMD exposes lazy dependent diagnostics: touch the unchanged consumer with
            # didSave to request its normal diagnostics publication. JDTLS autobuild stays on.
            client.notify('textDocument/didSave',{'textDocument':{'uri':probes['core'].as_uri()}})
            # A body-only edit need not republish an unchanged diagnostic set. A
            # signature flip must produce a fresh changed/cleared consumer result.
            change['diagnostics']=diagnostics(client,probes['core'],type_=='String',since if kind=='signature' else 0)
            change['after']=status();result['changes'].append(change)
            (root/'report.json').write_text(json.dumps(result,indent=2)+'\n')
        result['complete']=True;(root/'report.json').write_text(json.dumps(result,indent=2)+'\n')
        print(mode,repetition,'ready',round(ready),'signature completion',round(statistics.median(c['saved_change_to_correct_completion_ms'] for c in result['changes'] if c['kind']=='signature'),2),flush=True)
    finally:
        if client:client.close()

def main():
    p=argparse.ArgumentParser()
    for name in ['repo','before','after','java-home','jdtls','resolvers','root']:p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--runs',type=int,default=2);p.add_argument('--sources-per-module',type=int,default=32);p.add_argument('--modes',nargs='+',default=['before','after','jdtls']);a=p.parse_args()
    for k,v in vars(a).items():
        if isinstance(v,Path):setattr(a,k,v.resolve())
    a.root.mkdir(parents=True,exist_ok=False);a.profile=False;SETTINGS['java']['autobuild']['enabled']=True
    (a.root/'command.json').write_text(json.dumps({k:str(v) if isinstance(v,Path) else v for k,v in vars(a).items()},indent=2)+'\n')
    for i in range(a.runs):
        modes=a.modes[i%len(a.modes):]+a.modes[:i%len(a.modes)]
        for mode in modes:worker(a,mode,i,json.loads((a.before if mode=='before' else a.after).read_text()))
    (a.root/'complete.json').write_text(json.dumps({'workers':a.runs*len(a.modes)})+'\n')
if __name__=='__main__':main()
