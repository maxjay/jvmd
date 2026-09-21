import unittest

from compare import markdown
from trend import annotate, tables


class TrendTest(unittest.TestCase):
    def test_adds_improvement_from_matching_previous_row(self):
        comment = '''| Fixture | Metric | Unit | JVMD | JDTLS | JVMD / JDTLS |
|---|---|---|---:|---:|---:|
| review | hover | ms | 10.000 | 20.000 | 0.500 |
'''
        result = {'fixtures': {'review': {'hover': {
            'unit': 'ms', 'jvmd': 8, 'jdtls': 20, 'jvmd_over_jdtls': .4
        }}}, 'interpretation': 'Lower is better.'}
        annotate(result, tables(comment)[0], 41)
        self.assertEqual(result['fixtures']['review']['hover']['improvement_over_previous_pr'], 20)
        rendered = markdown(result)
        self.assertIn('Improvement vs PR #41', rendered)
        self.assertIn('alt="+20.0% (improvement)"', rendered)
        self.assertIn('badge/-%2B20.0%25-brightgreen', rendered)

    def test_colour_codes_improvement_with_five_percent_noise_band(self):
        result = {'fixtures': {'review': {
            'clear_improvement': {'unit': 'ms', 'jvmd': 8, 'jdtls': 20,
                                  'jvmd_over_jdtls': .4, 'improvement_over_previous_pr': 5.1},
            'positive_drift': {'unit': 'ms', 'jvmd': 8, 'jdtls': 20,
                               'jvmd_over_jdtls': .4, 'improvement_over_previous_pr': 5},
            'negative_drift': {'unit': 'ms', 'jvmd': 8, 'jdtls': 20,
                               'jvmd_over_jdtls': .4, 'improvement_over_previous_pr': -5},
            'clear_regression': {'unit': 'ms', 'jvmd': 8, 'jdtls': 20,
                                 'jvmd_over_jdtls': .4, 'improvement_over_previous_pr': -5.1},
        }}, 'interpretation': 'Lower is better.'}

        rendered = markdown(result)

        self.assertIn('badge/-%2B5.1%25-brightgreen', rendered)
        self.assertIn('badge/-%2B5.0%25-yellow', rendered)
        self.assertIn('badge/--5.0%25-yellow', rendered)
        self.assertIn('badge/--5.1%25-red', rendered)
        self.assertIn('Yellow: within ±5%', rendered)

    def test_missing_or_zero_previous_value_is_not_available(self):
        result = {'fixtures': {'review': {
            'hover': {'unit': 'ms', 'jvmd': 8},
            'rename': {'unit': 'ms', 'jvmd': 4},
        }}}
        annotate(result, {('review', 'hover', 'ms'): 0}, 40)
        self.assertIsNone(result['fixtures']['review']['hover']['improvement_over_previous_pr'])
        self.assertIsNone(result['fixtures']['review']['rename']['improvement_over_previous_pr'])


if __name__ == '__main__': unittest.main()
