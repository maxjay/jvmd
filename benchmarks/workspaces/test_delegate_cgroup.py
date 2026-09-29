"""Delegation regressions; fake kernel files never stand in for a real cgroup run."""
import contextlib
import errno
import io
import json
import os
import pwd
import shutil
import subprocess
import sys
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import delegate_cgroup as delegate


class DelegationCleanupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'cgroup'
        self.root.mkdir()
        self.parent = self.group(self.root, 'jvmd-validation-' + 'a' * 32)
        self.record = Path(self.temp.name) / 'delegation.json'
        self.record.write_text(json.dumps(dict(availability='ready', parent=str(self.parent),
                                              epoch=self.parent.stat().st_ino)))
        self.removed = []
        self.real_rmdir = Path.rmdir
        self.addCleanup(patch.stopall)
        patch.object(delegate, 'CGROUP_ROOT', self.root).start()
        patch.object(delegate, 'cgroup_mount', return_value='test mount').start()

    def group(self, parent, name, populated=0, procs=''):
        group = parent / name
        group.mkdir()
        for name, value in {'cgroup.events': f'populated {populated}\nfrozen 0\n',
                            'cgroup.procs': procs, 'cgroup.stat': 'nr_descendants 0\n',
                            'cgroup.subtree_control': '', 'cpu.stat': 'usage_usec 123\n',
                            'io.stat': '8:0 rbytes=0 wbytes=4096 rios=0 wios=1\n',
                            'memory.current': '4096\n', 'memory.peak': '8192\n',
                            'memory.events': 'oom 0\n'}.items():
            (group / name).write_text(value)
        return group

    def kernel_rmdir(self, group):
        # Emulate only rmdir's cgroup-specific virtual-file behaviour. Production
        # never unlinks files, recurses, kills, migrates, or changes limits here.
        self.removed.append(group.name)
        saved = json.loads(self.record.with_suffix('.cleanup.json').read_text())
        self.assertTrue(saved['children'])
        if group != self.parent:
            self.assertIn(group.name, [row['name'] for row in saved['removalSnapshots']])
        if any(p.is_dir() for p in group.iterdir()) or (group / 'cgroup.procs').read_text().strip():
            raise OSError(errno.EBUSY, 'populated or non-leaf cgroup')
        for entry in group.iterdir():
            entry.unlink()
        self.real_rmdir(group)

    def clean(self):
        with patch.object(Path, 'rmdir', autospec=True, side_effect=self.kernel_rmdir), \
                contextlib.redirect_stdout(io.StringIO()):
            delegate.cleanup(self.record)
        return json.loads(self.record.with_suffix('.cleanup.json').read_text())

    def test_empty_benchmark_and_observer_removed_after_counters_preserved(self):
        observer = self.group(self.parent, 'observer-' + 'b' * 32)
        benchmark = self.group(self.parent, 'jvmd-benchmark-' + 'c' * 32)
        earlier = Path(self.temp.name) / 'lifetime-resources.json'
        earlier.write_text('{"availability":"unavailable","reason":"descendant alive","cpu_seconds":null}')
        original = earlier.read_bytes()
        result = self.clean()
        self.assertTrue(result['parentRemoved'])
        self.assertEqual(result['removedObservers'], [observer.name])
        self.assertEqual(result['removedBenchmarks'], [benchmark.name])
        self.assertEqual(result['removalSnapshots'][0]['cpu.stat'], 'usage_usec 123\n')
        self.assertEqual(result['removalSnapshots'][0]['memory.peak'], '8192\n')
        self.assertEqual(earlier.read_bytes(), original)
        self.assertFalse(self.parent.exists())

    def test_live_child_is_never_removed(self):
        child = self.group(self.parent, 'jvmd-benchmark-' + 'b' * 32, populated=1, procs='42\n')
        with self.assertRaisesRegex(ValueError, 'live processes'):
            self.clean()
        self.assertTrue(child.exists())
        self.assertEqual(self.removed, [])
        evidence = json.loads(self.record.with_suffix('.cleanup.json').read_text())
        self.assertFalse(evidence['parentRemoved'])
        self.assertEqual(evidence['remainingChildren'][0]['cgroup.procs'], '42\n')

    def test_populated_descendant_without_direct_pid_is_not_empty(self):
        self.group(self.parent, 'observer-' + 'b' * 32, populated=1)
        with self.assertRaisesRegex(ValueError, 'live processes'):
            self.clean()
        self.assertEqual(self.removed, [])

    def test_unknown_or_non_uuid_child_is_not_removed(self):
        for name in ('other-work', 'observer-not-owned', 'jvmd-benchmark-not-owned'):
            with self.subTest(name=name):
                child = self.group(self.parent, name)
                with self.assertRaisesRegex(ValueError, 'unrecognised'):
                    self.clean()
                self.assertTrue(child.exists())
                self.assertEqual(self.removed, [])
                for p in child.iterdir():
                    p.unlink()
                child.rmdir()

    def test_missing_emptiness_evidence_fails_closed(self):
        child = self.group(self.parent, 'jvmd-benchmark-' + 'b' * 32)
        (child / 'cgroup.events').unlink()
        with self.assertRaisesRegex(ValueError, 'emptiness unavailable'):
            self.clean()
        self.assertEqual(self.removed, [])
        evidence = json.loads(self.record.with_suffix('.cleanup.json').read_text())
        self.assertIn('unavailable', evidence['children'][0]['cgroup.events'])

    def test_nonleaf_is_not_recursively_deleted(self):
        child = self.group(self.parent, 'jvmd-benchmark-' + 'b' * 32)
        nested = self.group(child, 'observer-' + 'c' * 32)
        with self.assertRaisesRegex(ValueError, 'not a leaf'):
            self.clean()
        self.assertTrue(nested.exists())
        self.assertEqual(self.removed, [])

    def test_kernel_race_failure_preserves_snapshot_and_original_error(self):
        self.group(self.parent, 'jvmd-benchmark-' + 'b' * 32)
        def busy(group):
            self.assertTrue(self.record.with_suffix('.cleanup.json').exists())
            raise OSError(errno.EBUSY, 'repopulated meanwhile')
        with patch.object(Path, 'rmdir', autospec=True, side_effect=busy), \
                contextlib.redirect_stdout(io.StringIO()), self.assertRaises(OSError):
            delegate.cleanup(self.record)
        evidence = json.loads(self.record.with_suffix('.cleanup.json').read_text())
        self.assertEqual(len(evidence['removalSnapshots']), 1)
        self.assertFalse(evidence['parentRemoved'])
        self.assertIn('repopulated meanwhile', evidence['error'])

    def test_parent_epoch_change_is_rejected_before_removal(self):
        record = json.loads(self.record.read_text())
        record['epoch'] += 1
        self.record.write_text(json.dumps(record))
        with self.assertRaisesRegex(ValueError, 'epoch changed'):
            self.clean()
        self.assertEqual(self.removed, [])

    def test_parent_outside_delegation_is_rejected(self):
        record = json.loads(self.record.read_text())
        record['parent'] = str(self.root)
        self.record.write_text(json.dumps(record))
        with self.assertRaisesRegex(ValueError, 'unexpected delegated parent'):
            self.clean()
        self.assertEqual(self.removed, [])

    def test_symlink_child_is_not_followed(self):
        outside = self.group(self.root, 'outside')
        (self.parent / ('observer-' + 'b' * 32)).symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'unrecognised'):
            self.clean()
        self.assertTrue(outside.exists())
        self.assertEqual(self.removed, [])


class DelegationRuntimeTests(unittest.TestCase):
    def test_path_requires_explicit_absolute_search_entries(self):
        for value in (None, '', '.', 'bin:/usr/bin', '/usr/bin:', ':/usr/bin'):
            with self.subTest(path=value), self.assertRaisesRegex(ValueError, 'runner PATH'):
                delegate.runner_path(value)
        self.assertEqual(delegate.runner_path('/chosen/node/bin:/usr/bin'), '/chosen/node/bin:/usr/bin')

    def test_missing_path_blocks_launch_even_without_cgroup_capability(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / 'delegation.json'
            output.write_text(json.dumps(dict(availability='unavailable', uid=1001, gid=1001)))
            with patch.object(os, 'setgroups') as drop, self.assertRaisesRegex(ValueError, 'runner PATH'):
                delegate.run(output, ['node'])
            drop.assert_not_called()

    def test_path_lookup_and_launch_happen_after_privilege_drop(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / 'delegation.json'
            output.write_text(json.dumps(dict(availability='unavailable', uid=1001, gid=1001,
                                              runnerPath='/selected/bin:/usr/bin')))
            order = []
            account = type('Account', (), dict(pw_dir='/home/runner', pw_name='runner'))()
            def lookup(command, path):
                self.assertEqual(order, ['groups', 'gid', 'uid'])
                self.assertEqual(path, '/selected/bin:/usr/bin')
                order.append('lookup')
                return '/selected/bin/node'
            with patch.dict(os.environ, {'PATH': '/wrong/bin'}), \
                    patch.object(os, 'setgroups', side_effect=lambda _: order.append('groups')), \
                    patch.object(os, 'setgid', side_effect=lambda _: order.append('gid')), \
                    patch.object(os, 'setuid', side_effect=lambda _: order.append('uid')), \
                    patch.object(pwd, 'getpwuid', return_value=account), \
                    patch.object(shutil, 'which', side_effect=lookup), patch.object(os, 'execve') as execute:
                delegate.run(output, ['node', '--version'])
                args = execute.call_args.args
                self.assertEqual(args[0], '/selected/bin/node')
                self.assertEqual(args[1], ['node', '--version'])
                self.assertEqual(args[2]['PATH'], '/selected/bin:/usr/bin')
                self.assertEqual(args[2]['HOME'], '/home/runner')
                self.assertEqual(args[2]['USER'], 'runner')
            launch = json.loads(output.with_suffix('.launches.jsonl').read_text())
            self.assertEqual(launch['executable'], '/selected/bin/node')
            self.assertEqual(launch['command'], ['node', '--version'])

    def test_actual_wrapper_retains_selected_runtime_for_child_commands(self):
        if os.geteuid() != 0:
            self.skipTest('real setuid wrapper requires root; workflow runtime smoke covers delegation')
        try:
            account = pwd.getpwnam('nobody')
        except KeyError:
            self.skipTest('no non-root test account')
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            root.chmod(0o777)
            chosen, fallback = root / 'chosen', root / 'fallback'
            chosen.mkdir(); fallback.mkdir()
            for directory, marker in ((chosen, 'selected'), (fallback, 'wrong')):
                executable = directory / 'runtime-probe'
                executable.write_text('#!/bin/sh\nprintf "' + marker + '\\n"\n')
                executable.chmod(0o755)
            # Copy the exact script and dependency so restrictive checkout parent
            # permissions cannot make the privilege-drop witness ambiguous.
            for source in ('delegate_cgroup.py', 'cgroup_resources.py'):
                shutil.copyfile(Path(__file__).parent / source, root / source)
            output = root / 'delegation.json'
            path = str(chosen) + ':/usr/bin:/bin'
            output.write_text(json.dumps(dict(availability='unavailable', uid=account.pw_uid,
                                              gid=account.pw_gid, runnerPath=path)))
            child = 'import os,subprocess; print(os.getuid()); subprocess.run(["runtime-probe"],check=True)'
            env = dict(os.environ, PATH=str(fallback) + ':/usr/bin:/bin')
            result = subprocess.run([sys.executable, str(root / 'delegate_cgroup.py'), 'run',
                                     '--output', str(output), '--', sys.executable, '-c', child],
                                    env=env, capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('selected', result.stdout)
            self.assertNotIn('wrong', result.stdout)
            self.assertIn(str(account.pw_uid), result.stdout)
            launch = json.loads(output.with_suffix('.launches.jsonl').read_text())
            self.assertEqual(launch['uid'], account.pw_uid)
            self.assertEqual(launch['runnerPath'], path)


if __name__ == '__main__':
    unittest.main()
