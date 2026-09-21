#!/usr/bin/env python3
"""Fast standard-library tests for benchmark reporting; no language server required."""
import json, subprocess, sys, tempfile, time, unittest
import threading
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from compare import compare
from resources import ProcessMonitor
from run import Client, first_system_value


class HarnessTest(unittest.TestCase):
    def test_optional_system_value_supports_cgroup_v1_and_missing_files(self):
        with tempfile.TemporaryDirectory() as directory:
            missing = Path(directory)/'v2'; fallback = Path(directory)/'v1'
            fallback.write_text('200000\n')
            self.assertEqual('200000', first_system_value((missing, fallback)))
            self.assertIsNone(first_system_value((missing,)))

    def test_client_reassembles_lsp_partial_results(self):
        client = Client.__new__(Client)
        client.next = 0; client.responses = {}; client.notifications = []; client.failure = None
        client.condition = threading.Condition()
        def send(message):
            token = message['params']['partialResultToken']
            client.notifications.extend([
                {'method': '$/progress', 'params': {'token': token, 'value': [1, 2]}},
                {'method': '$/progress', 'params': {'token': token, 'value': [3]}},
            ])
            client.responses[message['id']] = {'id': message['id'], 'result': []}
        client.send = send
        result, _ = client.call('textDocument/references', {'textDocument': {'uri': 'file:///Test.java'}})
        self.assertEqual([1, 2, 3], result)

    def test_compare_reports_directional_ratio(self):
        summary = {'fixtures': {'small': {'after': {'median': {'hover': 2.0, 'peak_rss_mib': 40.0}},
                                                   'jdtls-shared': {'median': {'hover': 4.0, 'peak_rss_mib': 100.0}}}}}
        result = compare(summary, 'after', 'jdtls-shared')
        self.assertEqual(.5, result['fixtures']['small']['hover']['jvmd_over_jdtls'])
        self.assertEqual(.4, result['fixtures']['small']['peak_rss_mib']['jvmd_over_jdtls'])
        self.assertEqual('ms', result['fixtures']['small']['hover']['unit'])
        self.assertEqual('MiB', result['fixtures']['small']['peak_rss_mib']['unit'])

    @unittest.skipUnless(Path('/proc/self/stat').exists(), 'Linux /proc required')
    def test_monitor_includes_descendant_memory(self):
        child = subprocess.Popen([sys.executable, '-c', 'import time; x=bytearray(8*1024*1024); time.sleep(.25)'])
        monitor = ProcessMonitor(child.pid, .005)
        child.wait(); values = monitor.close()
        self.assertGreater(values['samples'], 1)
        self.assertGreater(values['peak_rss_bytes'], 8*1024*1024)
        self.assertGreaterEqual(values['peak_processes'], 1)


if __name__ == '__main__': unittest.main()
