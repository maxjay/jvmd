import copy
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch
import experiment


class ShardTests(unittest.TestCase):
    def fixture(self, root, block, ratio=2, outcome='pass'):
        plan=dict(schemaVersion=1,mode='comparison',purpose='comparison-evidence',profile='product',blocks=10,
                  warmup=2,samples=20,timeoutMs=1000,runDeadlineSeconds=10,servers=['jvmd','jdtls'],caseIds=['DOC-01/repeat'],
                  analysis=dict(seed=271828,resamples=100))
        root.mkdir()
        (root/'plan.json').write_text(json.dumps(plan))
        manifest=dict(plan=plan,planSha256=experiment.sha(root/'plan.json'),selectedBlock=block,
                      captureRoot=str(root),schedule=experiment.schedule(plan),revision='same source',sourceInputs={'file':'source hash'},
                      finalSourceInputs={'file':'source hash'},sourceDrift=False,javaInputs={'java':'hash'},finalJavaInputs={'java':'hash'},
                      distributionInputs={'jvmdImage':{'binary':'hash'}},finalDistributionInputs={'jvmdImage':{'binary':'hash'}},
                      tools=dict(node='node',javaHome='/same/java',image='/same/image',jdtlsHome='/same/jdtls',pipeBuild=None))
        (root/'manifest.json').write_text(json.dumps(manifest))
        row=experiment.schedule(plan)[block-1]
        (root/'runs.json').write_text(json.dumps([{**row,'command':experiment.command_for(plan,row,manifest['tools'],root/row['directory']),'status':7,'error':None,'forcedTermination':False}]))
        self.seal(root)
        pairs=[dict(block=b,a=1 if b==block else None,b=ratio if b==block else None,outcome=outcome if b==block else 'unavailable_evidence') for b in range(1,11)]
        report=dict(complete=outcome=='pass',issues=[],runOutcomes=[{**row,'outcome':outcome}],effects=[dict(endpoint=['DOC-01/repeat','hover','steady','request'],blocks=pairs)])
        return report

    def seal(self, root):
        (root/'checksums.sha256').write_text(''.join(experiment.sha(p)+'  '+p.relative_to(root).as_posix()+'\n' for p in sorted(root.rglob('*')) if p.is_file() and p.name!='checksums.sha256'))

    def test_relocated_raw_capture_keeps_original_invocation_identity(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)/'captured';self.fixture(root,2)
            target=Path(tmp)/'downloaded';shutil.copytree(root,target)
            result=experiment.reduce_experiment(target)
            self.assertEqual(result['issues'],[])
            self.assertEqual(result['scope'],'single declared block')
            self.assertFalse(result['complete'])
            self.assertEqual(len(result['runOutcomes']),1)
            runs=experiment.read(target/'runs.json');runs[0]['command'][runs[0]['command'].index('--servers')+1]='jvmd,jdtls'
            (target/'runs.json').write_text(json.dumps(runs));self.seal(target)
            self.assertIn('recorded invocation differs from plan: 02-matched',experiment.reduce_experiment(target)['issues'])

    def test_complete_pairing_not_the_number_of_requests_controls_shard_inference(self):
        with tempfile.TemporaryDirectory() as tmp:
            roots=[Path(tmp)/str(i) for i in range(1,11)]
            reports={p:self.fixture(p,i) for i,p in enumerate(roots,1)}
            with patch.object(experiment,'reduce_experiment',side_effect=lambda p:copy.deepcopy(reports[p])):
                result=experiment.reduce_shards(roots)
                self.assertTrue(result['complete']);self.assertEqual(result['effects'][0]['ratio'],2)
                self.assertFalse(result['publicComparativePerformance'])
                for altered in (roots[:-1],roots+[roots[0]]):
                    result=experiment.reduce_shards(altered)
                    self.assertFalse(result['complete']);self.assertIsNone(result['effects'][0]['ratio'])
                reports[roots[2]]['effects'][0]['blocks'][2]['outcome']='timeout'
                result=experiment.reduce_shards(roots)
                self.assertIsNone(result['effects'][0]['ratio'])
                self.assertEqual(result['effects'][0]['failedBlocks'],[3])
                manifest=experiment.read(roots[3]/'manifest.json');manifest['javaInputs']['java']='different JDK'
                (roots[3]/'manifest.json').write_text(json.dumps(manifest));self.seal(roots[3])
                result=experiment.reduce_shards(roots)
                self.assertIn('4: mismatched javaInputs',result['issues'])
                self.assertIsNone(result['effects'][0]['ratio'])


if __name__ == '__main__':
    unittest.main()
