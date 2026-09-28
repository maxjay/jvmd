import unittest
from catalogue_partition import partitions


class CataloguePartitionTests(unittest.TestCase):
    def test_every_declared_case_is_attempted_once_in_original_order(self):
        ids = [str(i) for i in range(175)]
        rows = partitions(dict(caseIds=ids, shards=10), ids)
        self.assertEqual([case for row in rows for case in row], ids)
        self.assertEqual(sorted(map(len, rows)), [17]*5 + [18]*5)
        with self.assertRaisesRegex(ValueError, 'executable registry'):
            partitions(dict(caseIds=ids[:-1], shards=10), ids)
        with self.assertRaisesRegex(ValueError, 'executable registry'):
            partitions(dict(caseIds=['x', 'x'], shards=2), ['x', 'x'])
