import json
from pathlib import Path
import tempfile
import unittest
from validate_native import audit, digest


class NativeAuditTest(unittest.TestCase):
    def fixture(self, root):
        def write(name, value):
            file = root / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(json.dumps(value)+'\n')
        row = {'schemaVersion':1,'id':'LIFE-01','outcome':'pass','profile':'pipe',
               'startNs':'1','endNs':'10','operations':[],'assertions':[]}
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


if __name__ == '__main__':
    unittest.main()
