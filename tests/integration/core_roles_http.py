#!/usr/bin/env python3
"""TC-D-02 dynamic role on real core + repair HTTP; no mocked authorization."""
import datetime as dt
import os
import unittest
import uuid
from repair_http import Client

class DynamicRoleHttpTests(unittest.TestCase):
    def test_statistics_only_role_cannot_dispatch_and_revocation_invalidates_session(self):
        password = os.environ.get('STUDYROOM_TEST_PASSWORD')
        if not password:
            raise RuntimeError('STUDYROOM_TEST_PASSWORD is required for the isolated HTTP environment')
        admin, manager, reporter, worker = [Client(name, password) for name in ('admin', 'room-admin', 'student', 'repairer')]
        def good(client, method, path, body, expected=200):
            status, response = client.call(method, path, body)
            self.assertEqual(expected, status, (path, response))
            return response.get('data')
        suffix = uuid.uuid4().hex[:12]
        room = good(manager, 'POST', '/api/v1/rooms', {
            'buildingId': reporter.get('/api/v1/buildings')['items'][0]['id'],
            'code': 'ROLE-' + suffix, 'name': '动态统计授权验收教室'}, 201)
        role = good(admin, 'POST', '/api/v1/roles', {'code': 'STAT_' + suffix, 'name': '仅运营统计'}, 201)
        role = good(admin, 'PUT', '/api/v1/roles/' + role['id'] + '/permissions', {
            'expectedVersion': role['version'], 'permissionCodes': ['statistics:read']})
        name = 'analyst-' + suffix
        user = good(admin, 'POST', '/api/v1/users', {
            'username': name, 'displayName': '动态统计验收', 'password': password,
            'email': name + '@example.invalid', 'departmentId': reporter.me['departmentId']}, 201)
        before_grant = Client(name, password)
        binding = good(admin, 'PUT', '/api/v1/users/' + user['id'] + '/role-bindings', {
            'expectedVersion': user['version'], 'bindings': [{'roleId': role['id'],
                'scopes': [{'scopeType': 'ROOM', 'roomId': room['id']}]}]})
        self.assertEqual(401, before_grant.call('GET', '/api/v1/auth/me')[0])
        analyst = Client(name, password)
        self.assertEqual(['statistics:read'], analyst.me['permissions'])
        date = dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).date().isoformat()
        statistics_path = f'/api/v1/statistics/usage?fromDate={date}&toDate={date}&roomId={room["id"]}'
        self.assertEqual(0, analyst.get(statistics_path)['reservationCount'])
        status, _ = admin.call('DELETE', '/api/v1/roles/' + role['id'], {'expectedVersion': role['version']})
        self.assertEqual(409, status)
        order = good(reporter, 'POST', '/api/v1/repairs', {
            'roomId': room['id'], 'title': '动态权限测试灯光故障', 'description': '本测试验证统计权限不能派单'}, 201)
        try:
            denied, response = analyst.call('POST', '/api/v1/repairs/' + order['id'] + '/assign', {
                'expectedVersion': order['version'], 'assignedTo': worker.me['userId']})
            self.assertIn(denied, (403, 404), response)
            persisted = reporter.get('/api/v1/repairs/' + order['id'])
            self.assertEqual(order['version'], persisted['version'])
            self.assertEqual('SUBMITTED', persisted['status'])
            self.assertIsNone(persisted.get('assignedTo'))
            good(admin, 'PUT', '/api/v1/users/' + user['id'] + '/role-bindings', {
                'expectedVersion': binding['version'], 'bindings': []})
            self.assertEqual(401, analyst.call('GET', statistics_path)[0])
            self.assertEqual(401, analyst.call('GET', '/api/v1/repairs')[0])
            current = Client(name, password)
            self.assertEqual(403, current.call('GET', statistics_path)[0])
        finally:
            latest = reporter.get('/api/v1/repairs/' + order['id'])
            if latest['status'] == 'SUBMITTED':
                good(reporter, 'POST', '/api/v1/repairs/' + order['id'] + '/cancel', {'expectedVersion': latest['version']})

if __name__ == '__main__':
    unittest.main(verbosity=2)
