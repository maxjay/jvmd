#!/usr/bin/env python3
"""Fast standard-library tests for benchmark reporting; no language server required."""
import json, subprocess, sys, tempfile, time, unittest
import threading
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from compare import compare
from resources import ProcessMonitor, attribute_samples
from run import Client, first_system_value
from verify import workflow_oracle
from summarize import investigate


class HarnessTest(unittest.TestCase):
    def test_backlog_prioritizes_observed_refresh_wait_over_small_completion_cost(self):
        stage=lambda name,id_,parent,duration:{'name':name,'ts':0,'dur':duration*1000,
            'args':{'span':id_,'parent':parent,'invocation':'edit','queued':False,'work':{}}}
        report={'engine':'vscode-jvmd','workflow':'unit-test','directory':'worker','api_edit_to_correct_ms':1000,
            'actions':[{'id':'edit','name':'api_completion','outcome':'correct','time_to_correct_ms':1000}],
            'trace':{'traceEvents':[stage('rpc.execute',1,0,1000),stage('project.resolve',2,1,900),stage('completion.materialize',3,1,20)]},
            'attribution':{'groups':[{'span':2,'invocation':'edit','event':'jdk.ThreadPark','samples':1,'observed_wait_ms':880,
                'methods':{'dev.jvmd.index.rocks.RocksArtifactAdmission.acquireArtifact':{'source':'admission.java','line':1}}}]}}
        backlog=investigate(report)
        self.assertEqual('project.resolve',backlog[0]['stage'])
        self.assertEqual(880,backlog[0]['evidence']['wait_events_ms'])
        self.assertIn('memory admission',backlog[0]['hypothesis'])

    def test_completion_rejects_stale_and_incomplete_semantics(self):
        self.assertFalse(workflow_oracle('api_completion', {'items': []}, {}))
        self.assertFalse(workflow_oracle('api_completion', {'items': [{'label': 'value()', 'detail': 'int'}]}, {}))
        self.assertFalse(workflow_oracle('api_completion', {'items': [
            {'label': 'value()', 'detail': 'String'}, {'label': 'value()', 'detail': 'int'}]}, {}))
        self.assertTrue(workflow_oracle('api_completion', {'items': [{'label': 'value()', 'detail': 'String'}]}, {}))

    def test_definition_checks_source_selection_and_token_range(self):
        fixture = {'files': {'provider': '/library/Library.java'}, 'versions': {'A': 'int value() {}'}}
        result = [{'uri': 'file:///library/Library.java', 'range': {
            'start': {'line': 0, 'character': 4}, 'end': {'line': 0, 'character': 9}}}]
        self.assertTrue(workflow_oracle('warm_definition', result, fixture))
        result[0]['range']['end']['character'] = 10
        self.assertFalse(workflow_oracle('warm_definition', result, fixture))
        result[0]['uri'] = 'file:///installed/Library.java'
        self.assertFalse(workflow_oracle('warm_definition', result, fixture))

    def test_hotswap_requires_changed_output_in_same_process(self):
        fixture = {'expected': {'C': 'READY revision=C value=43'}}
        self.assertFalse(workflow_oracle('hotswap_output', {'pid': 10, 'original_pid': 9,
            'output': fixture['expected']['C']}, fixture))
        self.assertFalse(workflow_oracle('hotswap_output', {'pid': 10, 'original_pid': 10,
            'output': 'READY revision=B value=42'}, fixture))
        self.assertTrue(workflow_oracle('hotswap_output', {'pid': 10, 'original_pid': 10,
            'output': fixture['expected']['C']}, fixture))

    def test_dependency_navigation_rejects_wrong_artifact_and_wrong_source(self):
        source='public static int base(){return 40;}'
        fixture={'expected':{'external_source':source,'external_source_uri':'jar:file:///repo/offset-1-sources.jar!/external/Offset.java'}}
        row={'uri':fixture['expected']['external_source_uri'],'range':{'start':{'line':0,'character':18},'end':{'line':0,'character':22}},'source':None}
        self.assertTrue(workflow_oracle('dependency_definition',[row],fixture))
        row['uri']=row['uri'].replace('offset-1-','offset-2-')
        self.assertFalse(workflow_oracle('dependency_definition',[row],fixture))
        row.update(uri='jdt://contents/offset-1.jar/external/Offset.class?project',source=source)
        self.assertTrue(workflow_oracle('dependency_definition',[row],fixture))
        row['source']=source.replace('40','99')
        self.assertFalse(workflow_oracle('dependency_definition',[row],fixture))

    def test_reference_call_ranges_are_valid_but_rename_must_replace_only_identifier(self):
        token={'uri':'file:///host/Main.java','range':{'start':{'line':2,'character':4},'end':{'line':2,'character':9}}}
        call={'uri':token['uri'],'range':{'start':token['range']['start'],'end':{'line':2,'character':11}}}
        declaration={'uri':'file:///library/Library.java','range':token['range']}
        fixture={'files':{'provider':'/library/Library.java'},'expected':{
            'references':[token],'reference_call_ranges':[call],'definition':{'range':token['range']}}}
        for location in (token,call):self.assertTrue(workflow_oracle('references',[location],fixture))
        self.assertFalse(workflow_oracle('references',[],fixture))
        self.assertFalse(workflow_oracle('references',[token,call],fixture))
        edits=lambda location:[dict(location,newText='renamedValue'),dict(declaration,newText='renamedValue')]
        self.assertTrue(workflow_oracle('rename_preview',edits(token),fixture))
        self.assertFalse(workflow_oracle('rename_preview',edits(call),fixture))

    def test_samples_choose_innermost_matching_thread_and_leave_others_unassigned(self):
        spans = [{'queued': False, 'eventThread': {'javaThreadId': 7},
                  'startTime': '2026-01-01T00:00:00Z', 'duration': 'PT1S',
                  'durationNanos': 1_000_000_000, 'stage': 'parent', 'span': 1},
                 {'queued': False, 'eventThread': {'javaThreadId': 7},
                  'startTime': '2026-01-01T00:00:00.1Z', 'duration': 'PT0.3S',
                  'durationNanos': 300_000_000, 'stage': 'child', 'span': 2}]
        events = [{'type': 'jdk.ExecutionSample', 'values': {
            'startTime': '2026-01-01T00:00:00.2Z', 'sampledThread': {'javaThreadId': thread}}}
            for thread in (7, 8)]
        result = attribute_samples(events, spans)
        self.assertEqual(2, result['total_events']['jdk.ExecutionSample'])
        self.assertEqual(1, result['assigned_events']['jdk.ExecutionSample'])
        self.assertEqual({2, None}, {row['span'] for row in result['groups']})

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
