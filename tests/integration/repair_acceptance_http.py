#!/usr/bin/env python3
"""M3 negative acceptance using public HTTP, real core/repair and real Mailpit SMTP.

Run with STUDYROOM_TEST_PASSWORD and optional CORE/REPAIR/BASE URLs as repair_http.
All fixtures are synthetic, uniquely named and created through supported APIs.
No SQL, service mocks or capture mail adapter is used here. Mailpit evidence is
read-only and matched to a new recipient and exact work-order ID.
"""
import json
import os
import time
import unittest
import urllib.parse
import urllib.request
import uuid
from repair_http import Client

MAILPIT = os.environ.get('STUDYROOM_MAILPIT_URL', 'http://localhost:18025').rstrip('/')


def checked(client, method, path, body, expected=200):
    status, result = client.call(method, path, body)
    if status != expected:
        raise AssertionError(f'{client.username} {method} {path}: {status}: {result}')
    return result['data']


class RepairAcceptanceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.password = os.environ.get('STUDYROOM_TEST_PASSWORD')
        if not cls.password:
            raise RuntimeError('STUDYROOM_TEST_PASSWORD is required; no silently skipped acceptance')
        cls.prefix = 'm3-' + uuid.uuid4().hex[:9]
        cls.system = Client('admin', cls.password)
        cls.room_admin = Client('room-admin', cls.password)
        cls.roles = {r['code']: r['id'] for r in cls.system.get('/api/v1/roles')['items']}
        cls.dept = checked(cls.system, 'POST', '/api/v1/departments', {'code': cls.prefix, 'name': '维修负面验收院系'}, 201)
        cls.other_dept = checked(cls.system, 'POST', '/api/v1/departments', {'code': cls.prefix + '-b', 'name': '维修范围外院系'}, 201)
        building = cls.room_admin.get('/api/v1/buildings?size=100')['items'][0]
        cls.rooms = []
        for i, dept in enumerate((cls.dept, cls.other_dept)):
            room = checked(cls.room_admin, 'POST', '/api/v1/rooms', {'code': cls.prefix + '-' + str(i), 'name': 'M3 独立验收教室 ' + str(i), 'buildingId': building['id'], 'departmentId': dept['id']}, 201)
            checked(cls.room_admin, 'PUT', '/api/v1/rooms/' + room['id'] + '/opening-rules', {'expectedVersion': room['version'], 'rules': [{'weekday': d, 'startMinute': 0, 'endMinute': 1440} for d in range(1, 8)]})
            checked(cls.room_admin, 'POST', '/api/v1/rooms/' + room['id'] + '/seats', {'code': 'S1', 'hasPower': True}, 201)
            cls.rooms.append(room)
        cls.room = cls.rooms[0]['id']
        cls.users = {}
        specifications = {
            'reporter': [('STUDENT', None)],
            'outsider': [('STUDENT', None)],
            'worker_a': [('REPAIRER', None)],
            'worker_b': [('REPAIRER', None)],
            'disabled': [('REPAIRER', None)],
            'dispatch': [('PROPERTY_DISPATCHER', cls.room)],
            'dispatch_out': [('PROPERTY_DISPATCHER', cls.rooms[1]['id'])],
            'property': [('PROPERTY_ADMIN', cls.room)],
            'property_out': [('PROPERTY_ADMIN', cls.rooms[1]['id'])],
            'self_worker': [('STUDENT', None), ('REPAIRER', None), ('PROPERTY_ADMIN', cls.room)],
        }
        for key, roles in specifications.items():
            username = cls.prefix + '-' + key.replace('_', '-')
            # schoolNo has an intentionally separate compact <=24-character namespace.
            user = checked(cls.system, 'POST', '/api/v1/users', {'username': username, 'displayName': '维修验收 ' + key, 'password': cls.password, 'schoolNo': uuid.uuid4().hex[:24], 'email': username + '@example.invalid', 'departmentId': cls.other_dept['id'] if key == 'outsider' else cls.dept['id']}, 201)
            user = checked(cls.system, 'PUT', '/api/v1/users/' + user['id'] + '/role-bindings', {'expectedVersion': user['version'], 'bindings': [{'roleId': cls.roles[code], 'scopes': [{'scopeType': 'ROOM', 'roomId': room}] if room else [{'scopeType': 'SELF'}]} for code, room in roles]})
            if key == 'disabled':
                checked(cls.system, 'PATCH', '/api/v1/users/' + user['id'], {'expectedVersion': user['version'], 'enabled': False})
                cls.disabled_id = user['id']
            else:
                cls.users[key] = Client(username, cls.password)
        cls.ids = {key: client.me['userId'] for key, client in cls.users.items()}

    @classmethod
    def tearDownClass(cls):
        """Finish our own fixture work through legitimate transitions after a failed assertion."""
        if not hasattr(cls, 'users') or 'property' not in cls.users:
            return
        property_user = cls.users['property']
        rows = property_user.get('/api/v1/repairs?view=manage&roomId=' + cls.room + '&size=100')['items']
        by_id = {client.me['userId']: client for client in cls.users.values()}
        for order in rows:
            deadline = time.monotonic() + 100
            while time.monotonic() < deadline:
                current = property_user.get('/api/v1/repairs/' + order['id'])
                state = current['status']
                if state in ('CLOSED', 'CANCELLED'):
                    break
                if state in ('BLOCK_PENDING', 'RELEASE_PENDING'):
                    time.sleep(.5)
                    continue
                if state in ('SUBMITTED', 'ASSIGNED'):
                    actor, action, values, expected = cls.users['dispatch'], 'cancel', {'reason': '独立验收夹具完成'}, 200
                elif state == 'IN_REPAIR':
                    actor, action, values, expected = by_id[current['assignedTo']], 'complete', {'workNote': '独立验收夹具已完成复查'}, 200
                elif state == 'AWAITING_ACCEPTANCE':
                    actor, action, values, expected = property_user, 'acceptance', {'decision': 'ACCEPT', 'comment': '独立验收夹具完成复核'}, 202
                else:
                    raise AssertionError('Unexpected fixture cleanup state: ' + state)
                checked(actor, 'POST', '/api/v1/repairs/' + order['id'] + '/' + action, {'expectedVersion': current['version'], **values}, expected)
            else:
                raise AssertionError('Fixture did not finish its business cleanup: ' + order['id'])

    def detail(self, order):
        return self.users['property'].get('/api/v1/repairs/' + order['id'])

    def create(self, reporter='reporter', **changes):
        return checked(self.users[reporter], 'POST', '/api/v1/repairs', {'roomId': self.room, 'title': '独立维修负面验收', 'description': '故障说明：真实 HTTP 验收。', **changes}, 201)

    def act(self, actor, order, action, expected=200, **values):
        current = self.detail(order)
        return checked(self.users[actor], 'POST', '/api/v1/repairs/' + order['id'] + '/' + action, {'expectedVersion': current['version'], **values}, expected)

    def denied(self, actor, order, action, expected, **values):
        before = self.detail(order)
        status, body = self.users[actor].call('POST', '/api/v1/repairs/' + order['id'] + '/' + action, {'expectedVersion': before['version'], **values})
        self.assertEqual(expected, status, body)
        after = self.detail(order)
        for key in ('status', 'version', 'history', 'acceptances', 'rating', 'assignedTo', 'workDueAt'):
            self.assertEqual(before[key], after[key], f'Failed {action} changed {key}')
        return body

    def until(self, order, desired):
        end = time.monotonic() + 100
        while time.monotonic() < end:
            current = self.detail(order)
            if current['status'] == desired:
                return current
            time.sleep(.5)
        self.fail(f'Work order {order["id"]} did not reach {desired}; {current}')

    def claims(self, order):
        rows = self.room_admin.get('/api/v1/rooms/' + self.room + '/resource-claims?sourceType=M3&size=100')['items']
        return [row for row in rows if row['sourceId'] == order['id']]

    def notices(self, actor, order, event):
        rows = self.users[actor].get('/api/v1/notifications?sourceType=M3&roomId=' + self.room + '&size=100')['items']
        return [n for n in rows if n['sourceId'] == order['id'] and n['eventType'] == event]

    def wait_notice(self, actor, order, event):
        end = time.monotonic() + 100
        while time.monotonic() < end:
            rows = self.notices(actor, order, event)
            if rows:
                self.assertEqual(1, len(rows), 'A single event/recipient must have one record')
                return rows[0]
            time.sleep(.5)
        self.fail(f'No {event} notice for {actor}')

    def test_01_create_validation_department_and_visibility(self):
        reporter = self.users['reporter']
        for changes in ({'title': ''}, {'title': '字' * 101}, {'description': ' '}, {'description': '字' * 2001}):
            status, body = reporter.call('POST', '/api/v1/repairs', {'roomId': self.room, 'title': '故障', 'description': '故障说明', **changes})
            self.assertEqual(422, status, body)
        status, body = self.users['outsider'].call('POST', '/api/v1/repairs', {'roomId': self.room, 'title': '范围外', 'description': '其他院系教室'})
        self.assertEqual(403, status, body)
        order = self.create(title='🔧' * 100, description='字' * 2000)
        for actor in ('outsider', 'dispatch_out', 'property_out'):
            self.assertEqual(404, self.users[actor].call('GET', '/api/v1/repairs/' + order['id'])[0])
            view = 'mine' if actor == 'outsider' else 'manage'
            rows = self.users[actor].get('/api/v1/repairs?view=' + view + '&roomId=' + self.room)['items']
            self.assertNotIn(order['id'], [r['id'] for r in rows])
        self.act('reporter', order, 'cancel')
        self.assertEqual([], self.claims(order))

    def test_02_assignment_scope_invalid_worker_and_cancel_terminal(self):
        order = self.create()
        self.denied('dispatch_out', order, 'assign', 404, assignedTo=self.ids['worker_a'])
        for invalid in (self.ids['reporter'], self.disabled_id):
            self.denied('dispatch', order, 'assign', 422, assignedTo=invalid)
        self.act('dispatch', order, 'assign', assignedTo=self.ids['worker_a'])
        self.denied('reporter', order, 'cancel', 409, reason='报修人不能取消已派单工单')
        self.denied('dispatch', order, 'cancel', 422, reason=' ')
        cancelled = self.act('dispatch', order, 'cancel', reason='验收清理：尚未开始维修')
        self.assertEqual('CANCELLED', cancelled['status'])
        self.denied('dispatch', order, 'assign', 403, assignedTo=self.ids['worker_b'])
        self.denied('worker_a', order, 'start', 403)
        self.assertEqual([], self.claims(order))
        notice = self.wait_notice('reporter', order, 'CANCELLED')
        status, body = self.users['outsider'].call('POST', '/api/v1/notifications/' + notice['id'] + '/read', {'expectedVersion': notice['version']})
        self.assertEqual(404, status, body)
        marked = checked(self.users['reporter'], 'POST', '/api/v1/notifications/' + notice['id'] + '/read', {'expectedVersion': notice['version']})
        self.assertIsNotNone(marked['readAt'])
        self.assertEqual(marked['readAt'], self.notices('reporter', order, 'CANCELLED')[0]['readAt'])

    def test_03_reassignment_keeps_deadline_old_worker_loses_access(self):
        order = self.create()
        first = self.act('dispatch', order, 'assign', assignedTo=self.ids['worker_a'])
        second = self.act('dispatch', order, 'assign', assignedTo=self.ids['worker_b'])
        self.assertEqual(first['firstAssignedAt'], second['firstAssignedAt'])
        self.assertEqual(first['workDueAt'], second['workDueAt'])
        self.assertEqual(len(first['history']) + 1, len(second['history']))
        self.denied('worker_a', order, 'start', 404)
        for actor in ('worker_a', 'worker_b'):
            self.wait_notice(actor, order, 'ASSIGNED')
        for actor in ('reporter', 'property', 'dispatch', 'outsider'):
            self.assertEqual([], self.notices(actor, order, 'ASSIGNED'))
        self.act('worker_b', order, 'start', 202)
        self.until(order, 'IN_REPAIR')
        self.denied('dispatch', order, 'assign', 403, assignedTo=self.ids['worker_a'])
        self.act('worker_b', order, 'complete', workNote='新维修人完成维修')
        self.act('property', order, 'acceptance', 202, decision='ACCEPT', comment='独立验收通过')
        self.until(order, 'CLOSED')

    def test_04_completion_acceptance_and_cancellation_keep_resource_block(self):
        order = self.create()
        self.act('dispatch', order, 'assign', assignedTo=self.ids['worker_a'])
        self.act('worker_a', order, 'start', 202)
        self.until(order, 'IN_REPAIR')
        self.denied('worker_b', order, 'complete', 404, workNote='他人提交')
        self.denied('property', order, 'complete', 403, workNote='管理员不是维修人')
        self.denied('reporter', order, 'rating', 403, score=5)
        for note in (' ', '字' * 2001):
            self.denied('worker_a', order, 'complete', 422, workNote=note)
        self.denied('dispatch', order, 'cancel', 403, reason='封禁期间不能取消')
        self.assertEqual([None], [c['releasedAt'] for c in self.claims(order)])
        self.act('worker_a', order, 'complete', workNote='字' * 2000)
        self.denied('dispatch', order, 'cancel', 403, reason='待验收不能取消')
        self.denied('reporter', order, 'rating', 403, score=5)
        for reason in (' ', '字' * 501):
            self.denied('reporter', order, 'acceptance', 422, decision='REJECT', comment=reason)
        self.denied('reporter', order, 'acceptance', 422, decision='INVALID', comment='非法决定')
        self.wait_notice('reporter', order, 'AWAITING_ACCEPTANCE')
        for actor in ('worker_a', 'worker_b', 'property', 'dispatch', 'outsider'):
            self.assertEqual([], self.notices(actor, order, 'AWAITING_ACCEPTANCE'))
        rejected = self.act('reporter', order, 'acceptance', decision='REJECT', comment='仍有故障，需要重修')
        self.assertEqual('IN_REPAIR', rejected['status'])
        self.assertEqual([None], [c['releasedAt'] for c in self.claims(order)])
        self.act('worker_a', order, 'complete', workNote='重新修复完成')
        self.act('reporter', order, 'acceptance', 202, decision='ACCEPT')
        self.until(order, 'CLOSED')
        self.assertEqual(1, len(self.claims(order)))
        self.assertIsNotNone(self.claims(order)[0]['releasedAt'])

    def test_05_worker_reporter_admin_cannot_self_accept_and_rating_is_once(self):
        order = self.create('self_worker')
        self.act('dispatch', order, 'assign', assignedTo=self.ids['self_worker'])
        self.act('self_worker', order, 'start', 202)
        self.until(order, 'IN_REPAIR')
        self.act('self_worker', order, 'complete', workNote='本人报修并完成维修，必须他人验收')
        self.denied('self_worker', order, 'acceptance', 403, decision='ACCEPT')
        self.denied('self_worker', order, 'acceptance', 403, decision='REJECT', comment='仍不能自验')
        self.denied('property_out', order, 'acceptance', 404, decision='ACCEPT')
        self.assertEqual([None], [c['releasedAt'] for c in self.claims(order)])
        self.act('property', order, 'acceptance', 202, decision='ACCEPT', comment='范围内另一位物业管理员验收')
        closed = self.until(order, 'CLOSED')
        for score in (0, 6, 1.5, 4.0, True, '5'):
            self.denied('self_worker', order, 'rating', 422, score=score)
        self.denied('self_worker', order, 'rating', 422, score=5, comment='字' * 501)
        self.denied('property', order, 'rating', 403, score=5)
        key = str(uuid.uuid4())
        payload = {'expectedVersion': closed['version'], 'score': 5, 'comment': '独立验收后评价'}
        path = '/api/v1/repairs/' + order['id'] + '/rating'
        status, rated = self.users['self_worker'].call('POST', path, payload, key)
        self.assertEqual(201, status, rated)
        status, replay = self.users['self_worker'].call('POST', path, payload, key)
        self.assertEqual(201, status, replay)
        self.assertEqual(rated['data']['rating'], replay['data']['rating'])
        self.denied('self_worker', order, 'rating', 409, score=4)
        self.assertEqual('CLOSED', self.detail(order)['status'])
        notice = self.wait_notice('self_worker', order, 'CLOSED')
        # Real SMTP reaches Mailpit; read-only API confirms body and destination.
        recipient = self.users['self_worker'].username + '@example.invalid'
        query = urllib.parse.urlencode({'query': 'to:' + recipient, 'limit': 100})
        end = time.monotonic() + 100
        received = None
        while time.monotonic() < end:
            with urllib.request.urlopen(MAILPIT + '/api/v1/search?' + query, timeout=10) as response:
                messages = json.load(response)['messages']
            for message in messages:
                if message['Subject'].endswith('CLOSED'):
                    with urllib.request.urlopen(MAILPIT + '/api/v1/message/' + message['ID'], timeout=10) as response:
                        content = json.load(response)
                    if order['id'] in content.get('Text', ''):
                        received = content
                        break
            if received:
                break
            time.sleep(1)
        self.assertIsNotNone(received, 'CLOSED notification did not reach real Mailpit SMTP')
        self.assertIn(recipient, [r['Address'] for r in received['To']])
        # Mailpit can expose an accepted message before the sender commits SENT.
        # Share the SMTP deadline; never give the delivery acknowledgment extra time.
        while True:
            delivered_rows = self.notices('self_worker', order, 'CLOSED')
            self.assertEqual(1, len(delivered_rows), 'CLOSED event/recipient must remain unique')
            delivered = delivered_rows[0]
            self.assertEqual(notice['id'], delivered['id'])
            if delivered['status'] == 'SENT' or time.monotonic() >= end:
                break
            time.sleep(min(.5, max(0, end - time.monotonic())))
        self.assertEqual('SENT', delivered['status'])
        self.assertGreaterEqual(delivered['attempts'], 1)


if __name__ == '__main__':
    unittest.main(verbosity=2)
