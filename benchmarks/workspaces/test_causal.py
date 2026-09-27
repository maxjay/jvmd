import unittest
from causal import union_ns, reduce_native

class CausalTest(unittest.TestCase):
    def test_overlapping_and_nested_work_is_not_summed_into_elapsed(self):
        self.assertEqual(15, union_ns([(0, 10), (3, 5), (8, 15)]))
        self.assertEqual(0, union_ns([]))
        with self.assertRaises(ValueError): union_ns([(10, 3)])

    def test_missing_compiler_spans_do_not_prove_zero_and_clocks_cannot_mix(self):
        call = {'trace': {'invocation': 'a'}, 'startNs': '50000', 'endNs': '50010'}
        result = reduce_native([], [call], 'epoch')
        self.assertIsNone(result['invocations'][0]['nativeRequestIntervalUnionNs'])
        self.assertEqual('unavailable', result['invocations'][0]['compilerZeroWork']['status'])
        self.assertIsNone(result['invocations'][0]['clientMinusNativeResidualNs'])
        def span(pid, sid): return {'type':'dev.jvmd.Stage', 'values':{'process':pid,'span':sid,'invocation':'a','durationNanos':1,'startNanos':4,'stage':'rpc.execute','counters':'{}'}}
        with self.assertRaisesRegex(ValueError, 'clock domains'): reduce_native([span(1,1),span(2,2)], [call], 'epoch')
        with self.assertRaisesRegex(ValueError, 'duplicate'): reduce_native([span(1,1),span(1,1)], [call], 'epoch')

    def test_background_spans_without_invocations_remain_unmatched(self):
        events = [{'type': 'dev.jvmd.Stage', 'values': {
            'process': 1, 'span': 1, 'invocation': None, 'durationNanos': 4,
            'startNanos': 2, 'stage': 'background.index', 'counters': '{}'}}]
        result = reduce_native(events, [{'trace': {'invocation': 'request'}}], 'epoch')
        self.assertEqual([], result['unmatchedNativeInvocations'])
        self.assertEqual(['["unattributed-span",1,1]'], result['unmatchedNativeGroups'])
        self.assertEqual(['request'], result['unmatchedClientInvocations'])
        self.assertIsNone(next(r for r in result['invocations'] if r['identityKind'] == 'unattributed-span')['clientRequest'])

    def test_untagged_requests_remain_separate_and_have_no_fabricated_client_join(self):
        def event(sid, request, parent=0):
            return {'type':'dev.jvmd.Stage','values':{'process':7,'span':sid,'request':request,'parent':parent,
                    'invocation':None,'durationNanos':10,'startNanos':sid,'stage':'rpc.execute' if not parent else 'worker','counters':'{}'}}
        result = reduce_native([event(1,10),event(2,10,1),event(3,11),event(4,0)], [], 'epoch')
        self.assertEqual(3, len(result['invocations']))
        requests = [r for r in result['invocations'] if r['identityKind'] == 'native-request']
        self.assertEqual([[10],[11]], [r['nativeRequestIds'] for r in requests])
        self.assertTrue(all(r['clientRequest'] is None for r in requests))
        self.assertEqual(10, int(requests[0]['nativeRequestIntervalUnionNs']))
        with self.assertRaisesRegex(ValueError, 'different request'):
            reduce_native([event(1,10),event(2,11,1)], [], 'epoch')
        with self.assertRaisesRegex(ValueError, 'cycle'):
            reduce_native([event(1,10,2),event(2,10,1)], [], 'epoch')
        missing = reduce_native([event(2,10,1)], [], 'epoch')
        self.assertEqual([{'span':2,'missingParent':1}], missing['invocations'][0]['parentIssues'])

    def test_duplicate_client_identity_cannot_silently_overwrite_an_attempt(self):
        call = {'trace':{'invocation':'same'}}
        with self.assertRaisesRegex(ValueError, 'duplicate client'):
            reduce_native([], [call,call], 'epoch')
