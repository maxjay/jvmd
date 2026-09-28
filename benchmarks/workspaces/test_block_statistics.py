import copy
import unittest
from block_statistics import paired_effect


class BlockStatisticsTests(unittest.TestCase):
    def rows(self):
        return [dict(block=i, a=float(i), b=float(i)*2, outcome='pass') for i in range(1, 11)]

    def test_known_pairing_retains_run_structure(self):
        result=paired_effect(self.rows(),10,resamples=500)
        self.assertAlmostEqual(result['ratio'],2)
        self.assertEqual(result['interval'],[2,2])
        self.assertFalse(result['overheadWithinTolerance'])

    def test_one_failure_missing_pair_duplicate_or_nan_disables_inference(self):
        for mutation in (lambda r:r.pop(),lambda r:r.append(copy.deepcopy(r[0])),
                         lambda r:r[0].update(outcome='incorrect'),lambda r:r[0].update(b=float('nan')),
                         lambda r:r[0].update(a=0)):
            rows=self.rows();mutation(rows);result=paired_effect(rows,10,resamples=500)
            self.assertFalse(result['eligible']);self.assertIsNone(result['ratio'])

    def test_repeated_requests_cannot_manufacture_independent_blocks(self):
        rows=[dict(block=1,a=1,b=1,outcome='pass') for _ in range(20)]
        self.assertFalse(paired_effect(rows,10,resamples=500)['eligible'])
        self.assertFalse(paired_effect(rows[:1],1,resamples=500)['eligible'])

    def test_predeclared_overhead_tolerance_requires_entire_interval(self):
        rows=self.rows()
        for row in rows:row['b']=row['a']*1.02
        self.assertTrue(paired_effect(rows,10,resamples=500,tolerance=.05)['overheadWithinTolerance'])
        rows[-1]['b']=rows[-1]['a']*2
        result=paired_effect(rows,10,resamples=500,tolerance=.05)
        self.assertFalse(result['overheadWithinTolerance'])
        self.assertEqual(result,paired_effect(rows,10,resamples=500,tolerance=.05))
