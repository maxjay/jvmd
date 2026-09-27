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
