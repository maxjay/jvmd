#!/usr/bin/env python3
"""Checked per-character completion, including cache misses and backspacing."""
import argparse,json,os,statistics,time
from pathlib import Path
from run import CAPABILITIES,SETTINGS,position,project,start

def worker(a,mode,repetition):
    root=a.root/(a.fixture+'-'+mode+'-'+str(repetition));root.mkdir()
    binary='getFactory' if a.fixture=='real' else 'marker0'
    type_='com.fasterxml.jackson.databind.ObjectMapper' if a.fixture=='real' else 'fixture.a0.Type0'
    expression=binary+'()' if a.fixture=='real' else binary
    file,prefix=project(root/'workspace0',0,a.sources,sorted(a.repository.rglob('*.jar')),type_,expression)
    original=file.read_text();build=json.loads((a.before if mode=='before' else a.after).read_text())
    server='jdtls-shared' if mode=='jdtls' else 'jvmd';client=None;session=None;version=1
    result={'mode':mode,'fixture':a.fixture,'repetition':repetition,'build':build if mode!='jdtls' else None,'sources':a.sources,'chains':[],'complete':False}
    try:
        client,_=start(a,server,root/'process0',root/'workspace0',root/'state0',build)
        client.call('initialize',{'processId':os.getpid(),'rootUri':(root/'workspace0').as_uri(),'workspaceFolders':[{'uri':(root/'workspace0').as_uri(),'name':'prefix'}],'capabilities':CAPABILITIES,'initializationOptions':{'settings':SETTINGS,'extendedClientCapabilities':{'classFileContentsSupport':True}}})
        client.notify('initialized');client.notify('workspace/didChangeConfiguration',{'settings':SETTINGS})
        if mode=='jdtls':
            deadline=time.monotonic()+120
            while True:
                rows,_=client.call('workspace/symbol',{'query':prefix})
                if len([r for r in rows if r['name'].startswith(prefix)])==a.sources:break
                if time.monotonic()>deadline:raise AssertionError(('readiness',rows))
                time.sleep(.05)
        since=len(client.notifications);client.notify('textDocument/didOpen',{'textDocument':{'uri':file.as_uri(),'languageId':'java','version':version,'text':original}})
        client.diagnostics(file.as_uri(),version,False,since)
        def status():
            nonlocal session
            if mode=='jdtls':return None
            if session is None:
                r,_=client.call('jvmd/request',{'method':'daemon.status'});session=r['result']['sessions'][0]['session']
            r,_=client.call('jvmd/request',{'method':'session.status','params':{'session':session}});return r['result']
        result['before']=status()
        def completion_profile():
            if mode=='jdtls':return None
            analyzer=status().get('analyzer',{})
            return {k:analyzer.get(k) for k in [
                'completion_requests','completion_computations','completion_cache_hits','completion_last_cache_hit',
                'completion_candidates_seen','completion_rows_materialized','completion_doc_lookups',
                'completion_last_timing_ms','completion_timing_ms'
            ]}
        for cycle in range(a.cycles):
            for kind,member in [('source','twice'),('binary',binary)]:
                # Each chain changes surrounding source to force its first miss.
                template=original+'// prefix cycle '+str(cycle)+' '+kind+'\n';start_offset=template.index(member)
                samples=[]
                # Start at two characters, then backspace beyond that cached prefix.
                # An empty ObjectMapper selector can omit the target from JDTLS's
                # incomplete/ranked page, so it is not a like-for-like target check.
                for typed in [member[:i] for i in range(2,len(member)+1)]+[member[:1]]:
                    text=template[:start_offset]+typed+template[start_offset+len(member):];version+=1
                    began=time.perf_counter();client.notify('textDocument/didChange',{'textDocument':{'uri':file.as_uri(),'version':version},'contentChanges':[{'text':text}]})
                    value,request_ms=client.call('textDocument/completion',{'textDocument':{'uri':file.as_uri()},'position':position(text,start_offset+len(typed))})
                    elapsed=(time.perf_counter()-began)*1000;items=value.get('items',[]) if isinstance(value,dict) else value
                    matches=[i for i in items if i['label'].split('(')[0].split(':')[0].strip()==member]
                    assert matches,(kind,typed,value)
                    if mode!='jdtls':
                        expected={'start':position(text,start_offset),'end':position(text,start_offset+len(typed))}
                        assert all(i['textEdit']['range']==expected for i in matches),(expected,matches)
                        assert all(i['textEdit']['newText'].startswith(typed) for i in items),(typed,items)
                    sample={'prefix':typed,'change_to_response_ms':elapsed,'request_ms':request_ms,'items':len(items),'matches':matches}
                    if mode!='jdtls':sample['completion_profile']=completion_profile()
                    samples.append(sample)
                result['chains'].append({'cycle':cycle,'kind':kind,'samples':samples})
        result['after']=status();result['complete']=True
        (root/'report.json').write_text(json.dumps(result,indent=2)+'\n')
        print(a.fixture,mode,repetition,{kind:round(statistics.median(s['change_to_response_ms'] for c in result['chains'] if c['kind']==kind for s in c['samples'][1:-1]),3) for kind in ['source','binary']},flush=True)
    finally:
        if client:client.close()

def main():
    p=argparse.ArgumentParser()
    for name in ['repo','before','after','java-home','jdtls','resolvers','fixtures','root']:p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--runs',type=int,default=3);p.add_argument('--cycles',type=int,default=8);p.add_argument('--sources',type=int,default=24)
    p.add_argument('--modes',nargs='+',default=['before','after','jdtls']);p.add_argument('--fixture-names',nargs='+',default=['real','multi']);a=p.parse_args()
    for k,v in vars(a).items():
        if isinstance(v,Path):setattr(a,k,v.resolve())
    a.root.mkdir(parents=True,exist_ok=False);a.profile=False
    (a.root/'command.json').write_text(json.dumps({k:str(v) if isinstance(v,Path) else v for k,v in vars(a).items()},indent=2)+'\n')
    for repetition in range(a.runs):
        for fixture in a.fixture_names:
            a.fixture=fixture;a.repository=a.fixtures/fixture
            for mode in a.modes[repetition%len(a.modes):]+a.modes[:repetition%len(a.modes)]:worker(a,mode,repetition)
    (a.root/'complete.json').write_text(json.dumps({'workers':a.runs*len(a.fixture_names)*len(a.modes)})+'\n')
if __name__=='__main__':main()
