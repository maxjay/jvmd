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
        self.assertIn('| +20.0% |', rendered)

    def test_missing_or_zero_previous_value_is_not_available(self):
        result = {'fixtures': {'review': {
            'hover': {'unit': 'ms', 'jvmd': 8},
            'rename': {'unit': 'ms', 'jvmd': 4},
        }}}
        annotate(result, {('review', 'hover', 'ms'): 0}, 40)
        self.assertIsNone(result['fixtures']['review']['hover']['improvement_over_previous_pr'])
        self.assertIsNone(result['fixtures']['review']['rename']['improvement_over_previous_pr'])


if __name__ == '__main__': unittest.main()
