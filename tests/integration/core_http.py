#!/usr/bin/env python3
"""Real HTTP/PostgreSQL acceptance: no mocked identity, resource locks or persistence.
Run against an isolated demo-seeded environment. CORE_URL and DEMO_PASSWORD required.
"""
import concurrent.futures
import datetime as dt
import http.cookiejar
import json
import os
from pathlib import Path
import threading
import unittest
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get('CORE_URL', 'http://localhost:8081') + '/api/v1'
PASSWORD = os.environ.get('DEMO_PASSWORD', os.environ.get('STUDYROOM_TEST_PASSWORD', ''))
if not PASSWORD and Path('.local/local/secrets/demo_password').exists():
    PASSWORD = Path('.local/local/secrets/demo_password').read_text().strip()


class Client:
    def __init__(self, username):
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.token = ''
        self.token = self.call('GET', '/auth/csrf')[1]['data']['csrfToken']
        code, body = self.call('POST', '/auth/login', {'username': username, 'password': PASSWORD})
        assert code == 200, (username, code, body)
        self.me = body['data']
        self.token = self.call('GET', '/auth/csrf')[1]['data']['csrfToken']

    def call(self, method, path, data=None, key=None):
        headers = {'Content-Type': 'application/json', 'X-CSRF-TOKEN': self.token,
                   'Idempotency-Key': key or str(uuid.uuid4())}
        req = urllib.request.Request(BASE + path, method=method, headers=headers,
                                     data=json.dumps(data).encode() if data is not None else None)
        try:
            response = self.opener.open(req, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        raw = response.read()
        return response.status, json.loads(raw) if raw else {}

    def good(self, method, path, data=None):
        code, body = self.call(method, path, data)
        assert 200 <= code < 300, (path, code, body)
        return body.get('data')


class CoreAcceptance(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.admin = Client('admin')
        cls.manager = Client('room-admin')
        cls.student = Client('student')
        cls.student2 = Client('student2')
        cls.applicant = Client('applicant')
        cls.dept = Client('dept-approver')
        cls.space = Client('space-approver')
        cls.suffix = uuid.uuid4().hex[:8]
        building = cls.student.good('GET', '/buildings')['items'][0]
        cls.rooms = []
        cls.seats = []
        for n in range(2):
            r = cls.manager.good('POST', '/rooms', {'buildingId': building['id'], 'code': f'TEST-{cls.suffix}-{n}', 'name': 'HTTP 验收专用自习室'})
            cls.manager.good('PUT', f"/rooms/{r['id']}/opening-rules", {'expectedVersion': r['version'], 'rules': [{'weekday': d, 'startMinute': 0, 'endMinute': 1440} for d in range(1, 8)]})
            s = cls.manager.good('POST', f"/rooms/{r['id']}/seats", {'code': '01', 'hasPower': True})
            cls.rooms.append(r)
            cls.seats.append(s)
        cls.start = (dt.datetime.now(dt.timezone(dt.timedelta(hours=8))) + dt.timedelta(days=2)).replace(hour=10, minute=0, second=0, microsecond=0)
        cls.booking_ids = []

    def window(self, seat=0, hour=0):
        start = self.start + dt.timedelta(hours=hour)
        return {'seatId': self.seats[seat]['id'], 'startAt': start.isoformat(), 'endAt': (start + dt.timedelta(hours=1)).isoformat()}

    def test_01_scope_csrf_and_idempotency(self):
        restricted = [r for r in self.student.good('GET', '/rooms')['items'] if r.get('departmentId') == self.student.me['departmentId']][0]
        status, _ = self.student2.call('GET', f"/rooms/{restricted['id']}")
        self.assertEqual(status, 404)
        self.assertEqual(self.student.call('GET', '/users')[0], 403)
        key = str(uuid.uuid4())
        data = self.window(hour=0)
        code, first = self.student.call('POST', '/reservations', data, key)
        self.assertEqual(code, 201, first)
        code, replay = self.student.call('POST', '/reservations', data, key)
        self.assertEqual(code, 201, replay)
        self.assertEqual(first['data'], replay['data'])
        self.assertEqual(self.student.call('POST', '/reservations', self.window(hour=1), key)[0], 409)
        self.assertEqual(self.student2.call('GET', '/reservations/' + first['data']['id'])[0], 404)
        cancelled = self.student.good('POST', '/reservations/' + first['data']['id'] + '/cancel', {'expectedVersion': first['data']['version']})
        self.assertEqual(cancelled['status'], 'CANCELLED')
        self.assertEqual(self.student.call('POST', '/reservations/' + first['data']['id'] + '/check-in', {'expectedVersion': cancelled['version'], 'roomCode': 'INVALID'})[0], 409)

    def test_02_twenty_users_one_seat(self):
        roles = self.admin.good('GET', '/roles')['items']
        student_role = next(r['id'] for r in roles if r['code'] == 'STUDENT')
        names = []
        for i in range(20):
            name = f'concurrent-{self.suffix}-{i}'
            u = self.admin.good('POST', '/users', {'username': name, 'password': PASSWORD, 'email': name + '@example.invalid', 'displayName': '并发验收账号', 'departmentId': self.student.me['departmentId'], 'schoolNo': name})
            self.admin.good('PUT', '/users/' + u['id'] + '/role-bindings', {'expectedVersion': u['version'], 'bindings': [{'roleId': student_role, 'scopes': []}]})
            names.append(name)
        with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:
            clients = list(pool.map(Client, names))
        barrier = threading.Barrier(20)
        def reserve(client):
            barrier.wait(timeout=30)
            return client, client.call('POST', '/reservations', self.window(hour=2))
        with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:
            results = list(pool.map(reserve, clients))
        self.assertEqual(sum(code == 201 for _, (code, _) in results), 1, [(c, b) for _, (c, b) in results])
        self.assertEqual(sum(code == 409 for _, (code, _) in results), 19)
        claims = self.manager.good('GET', f"/rooms/{self.rooms[0]['id']}/resource-claims")['items']
        live = [c for c in claims if c['releasedAt'] is None and c['sourceType'] == 'M1']
        self.assertEqual(len(live), 1)
        winner, (_, data) = next(x for x in results if x[1][0] == 201)
        winner.good('POST', '/reservations/' + data['data']['id'] + '/cancel', {'expectedVersion': data['data']['version']})

    def test_03_same_user_two_rooms(self):
        barrier = threading.Barrier(2)
        clients = [Client('student'), Client('student')]
        def reserve(i):
            barrier.wait(timeout=10)
            return clients[i].call('POST', '/reservations', self.window(seat=i, hour=3))
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(reserve, range(2)))
        self.assertEqual(sorted(x[0] for x in results), [201, 409], results)
        success = next(b['data'] for c, b in results if c == 201)
        self.student.good('POST', '/reservations/' + success['id'] + '/cancel', {'expectedVersion': success['version']})

    def test_04_application_final_conflict_is_atomic(self):
        data = self.window(hour=4)
        body = {'roomId': self.rooms[0]['id'], 'startAt': data['startAt'], 'endAt': data['endAt'], 'purposeTitle': '学习交流', 'purposeDescription': 'HTTP 验收专用申请'}
        self.assertEqual(self.student.call('POST', '/room-applications', body)[0], 403)
        application = self.applicant.good('POST', '/room-applications', body)
        self.assertEqual(self.space.call('POST', '/room-applications/' + application['id'] + '/decisions', {'expectedVersion': application['version'], 'decision': 'APPROVE'})[0], 403)
        application = self.dept.good('POST', '/room-applications/' + application['id'] + '/decisions', {'expectedVersion': application['version'], 'decision': 'APPROVE'})
        reservation = self.student.good('POST', '/reservations', data)
        code, result = self.space.call('POST', '/room-applications/' + application['id'] + '/decisions', {'expectedVersion': application['version'], 'decision': 'APPROVE'})
        self.assertEqual(code, 409, result)
        unchanged = self.applicant.good('GET', '/room-applications/' + application['id'])
        self.assertEqual(unchanged['status'], 'PENDING')
        self.assertEqual(len(unchanged['actions']), 1)
        self.student.good('POST', '/reservations/' + reservation['id'] + '/cancel', {'expectedVersion': reservation['version']})
        approved = self.space.good('POST', '/room-applications/' + application['id'] + '/decisions', {'expectedVersion': application['version'], 'decision': 'APPROVE'})
        self.assertEqual(approved['status'], 'APPROVED')
        self.assertEqual(self.student.call('POST', '/reservations', data)[0], 409)
        self.applicant.good('POST', '/room-applications/' + application['id'] + '/cancel', {'expectedVersion': approved['version']})

    def test_05_disable_invalidates_existing_session(self):
        name = 'disabled-' + self.suffix
        u = self.admin.good('POST', '/users', {'username': name, 'password': PASSWORD, 'email': name + '@example.invalid', 'displayName': '停用验收账号', 'departmentId': self.student.me['departmentId']})
        target = Client(name)
        self.admin.good('PATCH', '/users/' + u['id'], {'expectedVersion': u['version'], 'enabled': False})
        self.assertEqual(target.call('GET', '/auth/me')[0], 401)


if __name__ == '__main__':
    unittest.main(verbosity=2)
