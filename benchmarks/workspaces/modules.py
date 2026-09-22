#!/usr/bin/env python3
"""Saved base-module API changes with JDTLS autobuild enabled and checked consumers."""
import argparse,json,os,re,statistics,subprocess,time
from pathlib import Path
from run import CAPABILITIES,SETTINGS,position,start

def workflow_fixture(root, java_home, sources=4):
    """Two actual Git repositories; equal Maven coordinates and source substitution."""
    import hashlib
    host, library = root/'host', root/'library'
    root.mkdir(parents=True, exist_ok=False)
    header = '<project><modelVersion>4.0.0</modelVersion><groupId>workflow</groupId><version>1</version>'
    modules = {'library': [], 'dependent': ['library'], 'app': ['dependent', 'library'], 'independent': []}
    files = {}
    for name, dependencies in modules.items():
        project = library if name == 'library' else host/name
        source = project/'src/main/java/bench'; source.mkdir(parents=True)
        deps = ''.join('<dependency><groupId>workflow</groupId><artifactId>'+d+'</artifactId><version>1</version></dependency>' for d in dependencies)
        if name == 'library':
            deps += '<dependency><groupId>external</groupId><artifactId>offset</artifactId><version>1</version></dependency>'
        (project/'pom.xml').write_text(header+'<artifactId>'+name+'</artifactId><properties><maven.compiler.release>17</maven.compiler.release></properties><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.16.0</version><configuration><debug>true</debug><debuglevel>lines,vars,source</debuglevel></configuration></plugin></plugins></build><dependencies>'+deps+'</dependencies></project>')
        for i in range(sources):
            (source/f'Unused{name.title()}{i}.java').write_text(f'package bench; public class Unused{name.title()}{i} {{ public int id(){{return {i};}} }}\n')
        wrapper = project/'.mvn/wrapper'; wrapper.mkdir(parents=True)
        (wrapper/'maven-wrapper.properties').write_text('distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip\n')
    (host/'pom.xml').write_text(header+'<artifactId>host</artifactId><packaging>pom</packaging><modules><module>dependent</module><module>app</module><module>independent</module></modules></project>')
    provider = library/'src/main/java/bench/Library.java'
    provider.write_text('package bench;\npublic class Library {\n  public static int value() {\n    int base = external.Offset.base();\n    int value = base + 1;\n    return value;\n  }\n  public static String revision() { return "A"; }\n}\n')
    consumer = host/'dependent/src/main/java/bench/Dependent.java'
    consumer.write_text('package bench;\npublic class Dependent {\n  public static int read() { return Library.value(); }\n}\n')
    main = host/'app/src/main/java/bench/Main.java'
    main.write_text('package bench;\npublic class Main {\n  public static void main(String[] args) throws Exception {\n    while (true) {\n      int value = Library.value();\n      System.out.println("READY revision=" + Library.revision() + " value=" + value);\n      Thread.sleep(200);\n    }\n  }\n}\n')
    independent = host/'independent/src/main/java/bench/Independent.java'
    independent.write_text('package bench; public class Independent { public static int value(){return 7;} }\n')
    repository = root/'repository'; artifact=repository/'external/offset/1'; artifact.mkdir(parents=True)
    external = root/'external-src/external/Offset.java'; external.parent.mkdir(parents=True)
    external.write_text('package external; public class Offset { public static int base(){return 40;} }\n')
    classes=root/'external-classes'; classes.mkdir()
    subprocess.run([str(java_home/'bin/javac'),'--release','17','-g','-d',str(classes),str(external)],check=True)
    subprocess.run([str(java_home/'bin/jar'),'--create','--date=2025-01-01T00:00:00Z','--file',str(artifact/'offset-1.jar'),'-C',str(classes),'.'],check=True)
    subprocess.run([str(java_home/'bin/jar'),'--create','--date=2025-01-01T00:00:00Z','--file',str(artifact/'offset-1-sources.jar'),'-C',str(external.parent.parent),'.'],check=True)
    (artifact/'offset-1.pom').write_text('<project><modelVersion>4.0.0</modelVersion><groupId>external</groupId><artifactId>offset</artifactId><version>1</version></project>')
    manifest=host/'.jvmd/workspace.json';manifest.parent.mkdir();manifest.write_text(json.dumps({'roots':['.','../library'],'ignore_versions':False}))
    for project in (host,library):
        subprocess.run(['git','init','-q',str(project)],check=True)
        subprocess.run(['git','-C',str(project),'add','.'],check=True)
        subprocess.run(['git','-C',str(project),'-c','user.name=Fixture','-c','user.email=fixture@example.invalid','commit','-qm','Deterministic workflow fixture'],check=True,env=dict(os.environ,GIT_AUTHOR_DATE='2025-01-01T00:00:00Z',GIT_COMMITTER_DATE='2025-01-01T00:00:00Z'))
    source_a=provider.read_text()
    source_api=source_a.replace('public static int value()', 'public static String value()').replace('return value;', 'return "changed";')
    source_b=source_a.replace('base + 1','base + 2').replace('"A"','"B"')
    source_c=source_b.replace('base + 2','base + 3').replace('"B"','"C"')
    def call_range(file,include_arguments=False):
        text=file.read_text();start=text.index('Library.value')+len('Library.');end=start+len('value')+(2 if include_arguments else 0)
        return {'uri':file.as_uri(),'range':{'start':position(text,start),'end':position(text,end)}}
    files={'provider':str(provider),'consumer':str(consumer),'main':str(main),'independent':str(independent)}
    result={'root':str(root),'roots':[str(host),str(library)],'repository':str(repository),'files':files,
            'versions':{'A':source_a,'API':source_api,'B':source_b,'C':source_c},
            'expected':{'definition':{'path':str(provider),'range':{'start':{'line':2,'character':20},'end':{'line':2,'character':25}}},
                        'references':[call_range(consumer),call_range(main)],
                        'reference_call_ranges':[call_range(consumer,True),call_range(main,True)],
                        'breakpoint_line':6,'locals':{'base':'40','value':'42'},'B':'READY revision=B value=42','C':'READY revision=C value=43'},
            'source_hashes':{str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in root.rglob('*.java')}}
    (root/'fixture.json').write_text(json.dumps(result,indent=2)+'\n')
    return result

def project_fixture(root, source, pin):
    """Copy the existing pinned PetClinic corpus with no prebuilt project outputs."""
    import hashlib
    import shutil
    actual=subprocess.check_output(['git','-C',str(source),'rev-parse','HEAD'],text=True).strip()
    if actual!=pin:raise ValueError(f'PetClinic revision {actual} does not match corpus pin {pin}')
    if subprocess.check_output(['git','-C',str(source),'status','--porcelain','--untracked-files=no'],text=True).strip():
        raise ValueError('Pinned PetClinic checkout has tracked modifications')
    root.mkdir(parents=True)
    project=root/'project'
    shutil.copytree(source,project,ignore=shutil.ignore_patterns('.git','target'))
    owner=project/'src/main/java/org/springframework/samples/petclinic/owner/Owner.java'
    text=owner.read_text();declaration=text.index('getPets()');call=text.index('getPets().contains')
    repository=root/'repository';repository.mkdir()
    result={'kind':'petclinic','pin':pin,'root':str(root),'roots':[str(project)],'repository':str(repository),
        'files':{'provider':str(owner),'consumer':str(owner)},'versions':{'A':text},
        'probe_offset':call+len('getPe'),
        'expected':{'method':'getPets','initial_type':'List','definition':{'path':str(owner),
            'range':{'start':position(text,declaration),'end':position(text,declaration+len('getPets'))}}},
        'source_hashes':{str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in project.rglob('*.java')}}
    (root/'fixture.json').write_text(json.dumps(result,indent=2)+'\n')
    return result


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
