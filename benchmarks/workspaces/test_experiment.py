import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import experiment


class ExperimentTests(unittest.TestCase):
    def plan(self):
        return dict(schemaVersion=1,mode='comparison',purpose='diagnostic',profile='product',blocks=2,
                    warmup=2,samples=20,timeoutMs=1000,runDeadlineSeconds=5,servers=['jvmd','jdtls'],caseIds=['DOC-01/repeat'])

    def test_order_is_balanced_and_plan_cannot_mislabel_small_run(self):
        plan=self.plan();rows=experiment.schedule(plan)
        self.assertEqual([r['servers'] for r in rows],[['jvmd','jdtls'],['jdtls','jvmd']])
        plan['mode']='observer-trace'
        self.assertEqual([r['config'] for r in experiment.schedule(plan)],['off','on','on','off'])
        plan['purpose']='comparison-evidence'
        with self.assertRaisesRegex(ValueError,'ten independent'):experiment.schedule(plan)

    def test_actual_child_failures_are_sealed_without_abandoning_later_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            base=Path(tmp);repo=base/'repo';repo.mkdir();plan=repo/'plan.json';plan.write_text(json.dumps(self.plan())+'\n')
            fake=repo/'fake-node';fake.write_text('#!/usr/bin/env python3\nimport sys\nprint("intentional child failure")\nsys.exit(7)\n');fake.chmod(0o755)
            subprocess.run(['git','init','-q',str(repo)],check=True)
            subprocess.run(['git','add','.'],cwd=repo,check=True)
            subprocess.run(['git','-c','user.name=Fixture','-c','user.email=fixture@example.invalid','commit','-qm','immutable test plan'],cwd=repo,check=True)
            java=base/'java';(java/'bin').mkdir(parents=True);(java/'lib').mkdir()
            for name in ('release','bin/java','lib/modules'):(java/name).write_text('tool identity')
            image=base/'image';image.mkdir();(image/'server').write_text('JVMD fixture')
            jdtls=base/'jdtls';jdtls.mkdir();(jdtls/'server').write_text('JDTLS fixture')
            args=argparse.Namespace(plan=plan,output=base/'capture',node=str(fake),java_home=java,image=image,jdtls_home=jdtls,pipe_build=None)
            with patch.object(experiment,'REPO',repo):
                output=experiment.collect(args)
                self.assertEqual(experiment.verify_inventory(output),[])
                self.assertEqual((output/'plan.json').read_bytes(),plan.read_bytes())
                runs=experiment.read(output/'runs.json')
                self.assertEqual([r['status'] for r in runs],[7,7])
                self.assertEqual([r['block'] for r in runs],[1,2])
                self.assertEqual(experiment.reduce_experiment(output)['complete'],False)
                (output/'01-matched.log').write_text('rewritten')
                self.assertIn('hash mismatch: 01-matched.log',experiment.verify_inventory(output))
                plan.write_text(json.dumps({**self.plan(),'samples':1}))
                args.output=base/'changed-plan'
                with self.assertRaisesRegex(ValueError,'clean checkout'):experiment.collect(args)
                self.assertFalse(args.output.exists())
