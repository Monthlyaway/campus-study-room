import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('compatibility_probe', ROOT / 'tests/integration/release_compatibility.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class CompatibilityProbeSafetyTests(unittest.TestCase):
    def test_only_known_nullable_columns_can_receive_sentinel_writes(self):
        for name in ('core.reservation.operator_note', 'repair.repair_order.assigned_to_name'):
            self.assertEqual(tuple(name.split('.')), probe.expanded_column(name))
        for name in ('core.app_user.password_hash', 'core.reservation.status',
                     'repair.repair_order.status', 'repair.repair_order.assigned_to_name; DROP TABLE repair.repair_order'):
            with self.assertRaisesRegex(RuntimeError, 'explicitly supported'):
                probe.expanded_column(name)

    def test_repair_sentinel_is_checked_in_repair_table_and_must_be_nullable(self):
        value = 'retained-' + 'a' * 32
        fixture = {'expandedColumn': {'schema': 'repair', 'table': 'repair_order',
                   'column': 'assigned_to_name', 'id': '00000000-0000-0000-0000-000000000001', 'value': value}}
        replies = [SimpleNamespace(stdout='YES'), SimpleNamespace(stdout='UPDATE 1'), SimpleNamespace(stdout=value)]
        with patch.object(probe.ops, 'compose', side_effect=replies) as compose:
            probe.expanded_value('test', fixture, initialize=True)
            queries = [call.args[-1] for call in compose.call_args_list]
            self.assertIn("table_schema='repair' AND table_name='repair_order'", queries[0])
            self.assertIn('UPDATE repair.repair_order SET assigned_to_name=', queries[1])
            self.assertIn('SELECT assigned_to_name FROM repair.repair_order', queries[2])
        with patch.object(probe.ops, 'compose', return_value=SimpleNamespace(stdout='NO')) as compose:
            with self.assertRaisesRegex(RuntimeError, 'nullable'):
                probe.expanded_value('test', fixture, initialize=True)
            self.assertEqual(1, compose.call_count)


if __name__ == '__main__':
    unittest.main()
