import copy
import json
import os
import subprocess
import sys
from pathlib import Path
import tempfile
import unittest
from cgroup_resources import decode_raw, prepare, reduce_lifetime, finish, audit_directory


def snap(cpu=0, io=0, peak=0, populated=0, inode=41, begin=10):
    raw = {'cpu.stat': f'usage_usec {cpu}\nuser_usec {cpu}\nsystem_usec 0\n',
           'memory.current': '0\n', 'memory.peak': f'{peak}\n',
           'memory.events': 'oom 0\noom_kill 0\n', 'cgroup.events': f'populated {populated}\n',
           'cgroup.procs': '12\n' if populated else '',
           'io.stat': f'8:0 rbytes={io} wbytes=0 rios=1 wios=0\n' if io else ''}
    return dict(raw=raw, inode=inode, start_ns=begin, end_ns=begin + 1,
                pre_read_events_raw='populated 0\n', pre_read_pids_raw='', **decode_raw(raw))


class LifetimeResourceTests(unittest.TestCase):
    def fixture(self):
        start = dict(availability='ready', owner='/sys/fs/cgroup/owned', epoch=41, before=snap())
        after = snap(cpu=2_000_000, io=4096, peak=8192, begin=30)
        members = [dict(pid=12, role='server', owner=start['owner'], epoch=41, entered_ns=20)]
        return start, after, members

    def test_lifetime_totals_retain_work_after_short_lived_children_exit(self):
        start, after, members = self.fixture()
        result = reduce_lifetime(start, after, members, [12])
        self.assertTrue(result['scope_complete'])
        self.assertEqual(result['cpu_seconds'], 2)
        self.assertEqual(result['memory_peak_bytes'], 8192)
        self.assertEqual(result['io_bytes']['8:0']['rbytes'], 4096)
        self.assertEqual(after['pids'], [])

    def test_unavailable_is_null_and_never_zero_work(self):
        result = reduce_lifetime({'availability': 'unavailable', 'reason': 'denied'}, None, [], [])
        self.assertFalse(result['scope_complete'])
        self.assertIsNone(result['cpu_seconds'])
        self.assertIsNone(result['memory_peak_bytes'])

    def test_missing_root_live_descendants_changed_epoch_or_forged_counter_fail(self):
        start, after, members = self.fixture()
        for mutate in [lambda s, a, m: m.clear(), lambda s, a, m: a.update(inode=42),
                       lambda s, a, m: a['cpu'].update(usage_usec=0),
                       lambda s, a, m: m[0].update(entered_ns=35),
                       lambda s, a, m: m[0].update(owner='/foreign'),
                       lambda s, a, m: a.update(pre_read_events_raw='populated 1\n'),
                       lambda s, a, m: a.update(**snap(cpu=2, populated=1, begin=30))]:
            s, a, m = copy.deepcopy((start, after, members))
            mutate(s, a, m)
            with self.assertRaises(ValueError):
                reduce_lifetime(s, a, m, [12])

    def test_memory_reset_and_missing_io_controller_are_not_accepted(self):
        start, after, members = self.fixture()
        start['before'] = snap(peak=16384)
        with self.assertRaisesRegex(ValueError, 'peak memory'):
            reduce_lifetime(start, after, members, [12])
        bad = after['raw'].copy()
        bad['io.stat'] = '8:0 wbytes=17\n'
        with self.assertRaisesRegex(ValueError, 'missing I/O counter'):
            decode_raw(bad)

    def test_no_delegation_and_non_cgroup_paths_preserve_explicit_unavailability(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / 'capture'
            self.assertEqual(prepare(out)['availability'], 'unavailable')
            self.assertEqual(finish(out, [])['availability'], 'unavailable')
            other = Path(tmp) / 'not-cgroup'
            other.mkdir()
            result = prepare(Path(tmp) / 'second', other)
            self.assertEqual(result['availability'], 'unavailable')
            self.assertEqual(list(other.iterdir()), [])
            self.assertIsNone(result['owner'])

    def test_artifact_audit_rejects_invented_roots_and_unavailable_zero(self):
        with tempfile.TemporaryDirectory() as tmp:
            prepare(tmp)
            finish(tmp, [12])
            self.assertEqual(audit_directory(tmp, [12])['availability'], 'unavailable')
            with self.assertRaisesRegex(ValueError, 'launch journals'):
                audit_directory(tmp, [13])
            file = Path(tmp) / 'lifetime-resources.json'
            row = json.loads(file.read_text())
            row['cpu_seconds'] = 0
            file.write_text(json.dumps(row))
            with self.assertRaisesRegex(ValueError, 'numerical value'):
                audit_directory(tmp, [12])

    @unittest.skipUnless(os.environ.get('JVMD_BENCH_CGROUP_ROOT'), 'delegated cgroup-v2 parent not supplied')
    def test_real_exec_scope_retains_cpu_of_exited_descendants(self):
        with tempfile.TemporaryDirectory() as tmp:
            start = prepare(tmp, os.environ['JVMD_BENCH_CGROUP_ROOT'])
            self.assertEqual(start['availability'], 'ready', start)
            child_code = 'import time; end=time.process_time()+0.15\nwhile time.process_time()<end: pass'
            code = 'import subprocess,sys; subprocess.run([sys.executable,"-c",' + repr(child_code) + '],check=True)'
            child = subprocess.Popen([sys.executable, str(Path(__file__).with_name('cgroup_resources.py')),
                                      'exec', '--output', tmp, '--role', 'server', '--', sys.executable, '-c', code])
            self.assertEqual(child.wait(timeout=10), 0)
            result = finish(tmp, [child.pid])
            self.assertEqual(result['availability'], 'measured', result)
            self.assertGreater(result['cpu_seconds'], 0.1)
            self.assertGreater(result['memory_peak_bytes'], 0)
            self.assertTrue(result['group_removed'])
            self.assertTrue(audit_directory(tmp, [child.pid])['scope_complete'])


if __name__ == '__main__':
    unittest.main()
