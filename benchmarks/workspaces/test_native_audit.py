import json
from pathlib import Path
import tempfile
import unittest
from validate_native import audit, digest
from cgroup_resources import prepare, finish
from native_replay import DISK_SHA, Replies, replay_case


class NativeAuditTest(unittest.TestCase):
    def fixture(self, root):
        def write(name, value):
            file = root / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(json.dumps(value)+'\n')
        row = {'schemaVersion':1,'id':'LIFE-01','outcome':'pass','profile':'pipe',
               'startNs':'1','endNs':'10','operations':[],'assertions':[],
               'witness':{'result':{'contents':'String marker'},'state':{'session':'s1'},'diskSha256':DISK_SHA},
               'initialStore':'absent before launch','machine':{'aot_cache':'disabled'},'aot':'disabled'}
        write('manifest.json', {'schemaVersion':1,'sourceInputs':{'file':'abc'},'cases':['LIFE-01'],'profile':'pipe','trace':False})
        write('summary.json', {'cases':[{k:v for k,v in row.items() if k!='operations'}], 'semanticComplete':True,'complete':False})
        write('LIFE-01.json', row)
        (root/'lifecycle-operations.jsonl').write_text('')
        write('fresh/launch.json', {'epoch':'fresh:1','profile':'pipe'})
        write('fresh/process.json', {'epoch':'fresh:1','pid':123})
        write('fresh/exit.json', {'code':0,'forced':False})
        write('fresh/resources.json', {'availability':'unavailable'})
        for name in ('resource-samples.jsonl','native-events.jsonl','native-calls.jsonl'):
            (root/'fresh'/name).write_text('')
        write('fresh/peer-1/events.jsonl', {'direction':'receive','timeNs':'3','message':{'result':row['witness']['result']}})
        calls=[]
        for i,value in enumerate([row['witness']['state'],row['machine']]):
            call=dict(id=i,method='session.status',params={},startNs='2',endNs='4',epoch='fresh:1',trace={'invocation':str(i)},outcome='pass',result={'result':value})
            calls.append(call)
        (root/'fresh/native-calls.jsonl').write_text(''.join(json.dumps(c)+'\n' for c in calls))
        (root/'fresh/native-events.jsonl').write_text(''.join(json.dumps({k:v for k,v in c.items() if k not in ('result','endNs','outcome')})+'\n' for c in calls))
        self.seal(root)
        return write

    def seal(self, root):
        (root/'checksums.sha256').write_text(''.join(digest(p)+'  '+p.relative_to(root).as_posix()+'\n' for p in sorted(root.rglob('*')) if p.is_file() and p.name!='checksums.sha256'))

    def test_valid_integrity_does_not_authorize_full_or_performance_claim(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);self.fixture(root);result=audit(root)
            self.assertTrue(result['integrityValid'],result['issues'])
            self.assertTrue(result['semanticComplete'])
            self.assertFalse(result['complete'])
            self.assertFalse(result['publicPerformanceClaims'])
            (root/'unsealed.json').write_text('{}')
            self.assertIn('unsealed extra artifact: unsealed.json',audit(root)['issues'])

    def test_resealed_summary_cannot_hide_wrong_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);write=self.fixture(root)
            row=json.loads((root/'LIFE-01.json').read_text());row['outcome']='incorrect';write('LIFE-01.json',row)
            self.seal(root);result=audit(root)
            self.assertFalse(result['integrityValid'])
            self.assertFalse(result['semanticComplete'])
            self.assertIn('summary conceals failed/missing lifecycle case',result['issues'])

    def test_resealed_pass_flags_cannot_hide_a_wrong_semantic_reply(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);write=self.fixture(root)
            row=json.loads((root/'LIFE-01.json').read_text())
            row['witness']['result']={'contents':'int marker'}
            write('LIFE-01.json',row)
            write('summary.json',{'cases':[{k:v for k,v in row.items() if k!='operations'}],'semanticComplete':True,'complete':False})
            write('fresh/peer-1/events.jsonl',{'direction':'receive','timeNs':'3','message':{'result':row['witness']['result']}})
            self.seal(root)
            result=audit(root)
            self.assertFalse(result['semanticComplete'])
            self.assertIn('LIFE-01: semantic replay: hover type wrong',result['semanticReplayIssues'])

    def test_correct_witness_requires_an_actual_reply_inside_the_case_interval(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);write=self.fixture(root)
            for message in ({'contents':'String marker'},{'contents':'int marker'}):
                write('fresh/peer-1/events.jsonl',{'direction':'receive','timeNs':'11','message':{'result':message}})
                self.seal(root)
                result=audit(root)
                self.assertFalse(result['semanticComplete'])
                self.assertTrue(any('absent from wire replies' in s for s in result['semanticReplayIssues']))

    def test_idle_allows_background_diagnostics_but_not_explicit_observer_work(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);write=self.fixture(root)
            row=dict(id='LIFE-10',outcome='pass',startNs='1',endNs='2000010',idleStartNs='1',idleEndNs='1000001',operations=[],after={'sessions':[]})
            write('fresh/native-calls.jsonl',dict(id=1,method='lsp.diagnostics',startNs='10',endNs='2000000',outcome='pass',result={'result':row['after']}))
            plan=dict(idleMs=1,activeQueries=0)
            self.assertEqual(replay_case(row,plan,Replies(root)),[])
            raw=json.loads((root/'fresh/native-calls.jsonl').read_text());raw['method']='daemon.status';write('fresh/native-calls.jsonl',raw)
            self.assertIn('LIFE-10: semantic replay: idle interval contains an explicit native call',replay_case(row,plan,Replies(root)))

    def test_resealed_interrupted_request_cannot_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);write=self.fixture(root)
            write('fresh/native-events.jsonl',{'id':1,'method':'document.open','params':{},'startNs':'1','epoch':'fresh:1'})
            self.seal(root);result=audit(root)
            self.assertIn('unfinished native calls: fresh',result['issues'])
            (root/'fresh/exit.json').write_text('{"code":7}')
            result=audit(root)
            self.assertIn('artifact hash mismatch: fresh/exit.json',result['issues'])
            self.assertIn('unclean daemon shutdown: fresh',result['issues'])

    def test_checksum_paths_cannot_escape_bundle(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);self.fixture(root)
            with (root/'checksums.sha256').open('a') as out:
                out.write('0'*64+'  ../outside.json\n')
            self.assertIn('unsafe artifact path: ../outside.json',audit(root)['issues'])

    def test_lifetime_claim_requires_raw_membership_and_launched_roots(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);write=self.fixture(root)
            start=prepare(root/'fresh')
            lifetime=finish(root/'fresh',[123])
            write('fresh/launch.json',{'epoch':'fresh:1','profile':'pipe','lifetimeResources':start})
            summary=json.loads((root/'summary.json').read_text())
            summary['lifetimeResources']=[dict(daemonEpoch='fresh:1',**lifetime)]
            summary['lifetimeCountersComplete']=False;write('summary.json',summary)
            self.seal(root)
            self.assertTrue(audit(root)['integrityValid'])
            summary['lifetimeResources'][0]['cpu_seconds']=0;write('summary.json',summary);self.seal(root)
            self.assertIn('lifetime summary rows differ from raw epoch files',audit(root)['issues'])
            summary['lifetimeResources'][0]['cpu_seconds']=None
            summary['lifetimeCountersComplete']=True;write('summary.json',summary);self.seal(root)
            self.assertIn('lifetime counter summary disagrees with raw evidence',audit(root)['issues'])
            write('fresh/peer-1/launch.json',{'pid':456});self.seal(root)
            self.assertIn('lifetime roots differ from launched processes: fresh',audit(root)['issues'])


if __name__ == '__main__':
    unittest.main()
