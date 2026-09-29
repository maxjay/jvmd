"""Guard the real CI entry points against sudo's PATH and evidence-loss regressions."""
from pathlib import Path
import unittest


class DelegationWorkflowTests(unittest.TestCase):
    def test_every_delegating_workflow_captures_the_original_path_and_checks_children(self):
        root = Path(__file__).resolve().parents[2] / '.github' / 'workflows'
        observed = []
        for path in sorted(root.glob('*.yml')):
            text = path.read_text()
            if 'delegate_cgroup.py setup' not in text:
                continue
            observed.append(path.name)
            with self.subTest(workflow=path.name):
                self.assertIn('--runner-path "$PATH"', text)
                self.assertIn('verify_delegate_runtime.cjs "$(command -v node)" v24.21.0', text)
                cleanup = text.index('delegate_cgroup.py cleanup')
                upload = text.index('name: Preserve delegation runtime and cleanup evidence')
                self.assertLess(cleanup, upload, 'cleanup evidence must be uploaded after cleanup')
                section = text[upload:]
                self.assertIn('if: always()', section)
                self.assertIn('.cleanup.json', section)
                self.assertIn('.launches.jsonl', section)
                self.assertIn('delegation-runtime.json', section)
                self.assertNotIn('contents: write', text)
                self.assertNotIn('git push', text)
        self.assertEqual(observed, ['benchmark-experiments.yml', 'catalogue-validation.yml', 'lsp-scenarios.yml'])


if __name__ == '__main__':
    unittest.main()
