#!/usr/bin/env python3
"""Actual public API regression for the authorized assignment-name snapshot."""
import unittest
import repair_acceptance_http as acceptance
from repair_acceptance_http import checked


class AssignmentNameTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        acceptance.RepairAcceptanceTests.setUpClass()
        cls.fixture = acceptance.RepairAcceptanceTests
        cls.fixture_case = acceptance.RepairAcceptanceTests()

    @classmethod
    def tearDownClass(cls):
        cls.fixture.tearDownClass()

    def test_snapshot_is_readable_scoped_and_refreshes_only_on_assignment(self):
        f, case = self.fixture, self.fixture_case
        order = case.create()
        self.assertIsNone(order['assignedToName'])
        first = case.act('dispatch', order, 'assign', assignedTo=f.ids['worker_a'])
        self.assertEqual('维修验收 worker_a', first['assignedToName'])
        self.assertEqual(first['assignedToName'], case.detail(order)['assignedToName'])
        reporter = f.users['reporter']
        row = next(r for r in reporter.get('/api/v1/repairs?view=mine&roomId=' + f.room)['items'] if r['id'] == order['id'])
        self.assertEqual(first['assignedToName'], row['assignedToName'])
        actor = f.system.get('/api/v1/users?q=' + f.users['worker_a'].username)['items'][0]
        checked(f.system, 'PATCH', '/api/v1/users/' + actor['id'], {'expectedVersion': actor['version'], 'displayName': '修改后的维修姓名'})
        self.assertEqual('维修验收 worker_a', reporter.get('/api/v1/repairs/' + order['id'])['assignedToName'], 'Historical snapshot must not become an unscoped live user lookup')
        refreshed = case.act('dispatch', order, 'assign', assignedTo=f.ids['worker_a'])
        self.assertEqual('修改后的维修姓名', refreshed['assignedToName'])
        second = case.act('dispatch', order, 'assign', assignedTo=f.ids['worker_b'])
        self.assertEqual('维修验收 worker_b', second['assignedToName'])
        self.assertEqual(first['firstAssignedAt'], second['firstAssignedAt'])
        self.assertEqual(first['workDueAt'], second['workDueAt'])
        case.denied('dispatch', order, 'assign', 422, assignedTo=f.ids['reporter'])
        self.assertEqual(second['assignedToName'], case.detail(order)['assignedToName'])
        self.assertEqual(404, f.users['outsider'].call('GET', '/api/v1/repairs/' + order['id'])[0])
        self.assertEqual([], f.users['outsider'].get('/api/v1/repairs?roomId=' + f.room)['items'])
        cancelled = case.act('dispatch', order, 'cancel', reason='姓名快照验收完成')
        self.assertEqual(second['assignedToName'], cancelled['assignedToName'])
        self.assertEqual('CANCELLED', cancelled['status'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
