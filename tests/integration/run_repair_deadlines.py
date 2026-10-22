#!/usr/bin/env python3
"""M3-07: isolated repair PG/clock, real core APIs and recipient scope assertions."""
from runner_locks import local_runner_lock
import json
import os
import unittest


def prepare_fixture(password, output):
    """Shared by native runner and CI; uses public APIs and no local-state helper."""
    os.environ['STUDYROOM_TEST_PASSWORD'] = password
    from repair_acceptance_http import RepairAcceptanceTests
    # Creates unique actors and resources only via public administration APIs.
    RepairAcceptanceTests.setUpClass()
    fixture = RepairAcceptanceTests
    users = {}
    for name, client in fixture.users.items():
        account = fixture.system.get('/api/v1/users?q=' + client.username)['items'][0]
        users[name] = {'id': account['id'], 'authVersion': account['authVersion'], 'username': client.username}
    output.mkdir(parents=True, exist_ok=True)
    source = output / 'fixture.json'
    source.write_text(json.dumps({'roomId': fixture.room, 'users': users}))
    return fixture, source


def assert_recipients(fixture, output):
    """Verify all nine new enabled actors through their real core sessions."""
    results = json.loads((output / 'deadline-results.json').read_text())
    expected = {'ASSIGN_OVERDUE': {'dispatch', 'property', 'self_worker'},
                'WORK_OVERDUE': {'worker_a', 'property', 'self_worker'},
                'ACCEPT_OVERDUE': {'reporter', 'property', 'self_worker'}}
    evidence = {}
    assertions = unittest.TestCase()
    for event, order_id in results.items():
        counts = {}
        for actor, client in fixture.users.items():
            rows = client.get('/api/v1/notifications?sourceType=M3&roomId=' + fixture.room + '&size=100')['items']
            rows = [n for n in rows if n['sourceId'] == order_id and n['eventType'] == event]
            counts[actor] = len(rows)
            assertions.assertEqual(1 if actor in expected[event] else 0, len(rows), f'{event}: incorrect recipient {actor}')
        evidence[event] = {'orderId': order_id, 'recipientCounts': counts, 'statePreservedAtDeadline': {'ASSIGN_OVERDUE': 'SUBMITTED', 'WORK_OVERDUE': 'ASSIGNED', 'ACCEPT_OVERDUE': 'AWAITING_ACCEPTANCE'}[event]}
    claims = fixture.room_admin.get('/api/v1/rooms/' + fixture.room + '/resource-claims?sourceType=M3&size=100')['items']
    claims = [c for c in claims if c['sourceId'] in results.values()]
    assertions.assertEqual(1, len(claims))
    assertions.assertIsNotNone(claims[0]['releasedAt'], 'Cleanup must finish legitimate independent acceptance')
    (output / 'recipient-results.json').write_text(json.dumps({'passed': True, 'events': evidence, 'cleanup': 'business cancellation/independent acceptance; resource released'}, ensure_ascii=False, indent=2) + '\n')
    print('M3-07 real core HTTP recipient matrix: PASS (3 events × 9 enabled actors; exact per-recipient counts).')


def run_local():
    from run_repair_persistence import psql, ops, ROOT
    env = ops.native_env('local', 'repair')
    output = ROOT / 'output/repair-acceptance/deadlines'
    fixture, source = prepare_fixture(env['DEMO_PASSWORD'], output)
    database = 'studyroom_repair_deadlines'
    if not psql("SELECT 1 FROM pg_database WHERE datname='studyroom_repair_deadlines'"):
        psql('CREATE DATABASE studyroom_repair_deadlines OWNER studyroom_migration')
    psql('''CREATE SCHEMA IF NOT EXISTS core AUTHORIZATION studyroom_migration;
      CREATE SCHEMA IF NOT EXISTS repair AUTHORIZATION studyroom_migration;
      CREATE SCHEMA IF NOT EXISTS session AUTHORIZATION studyroom_migration;
      GRANT USAGE ON SCHEMA repair,session TO studyroom_repair;
      ALTER DEFAULT PRIVILEGES FOR ROLE studyroom_migration IN SCHEMA repair GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO studyroom_repair;
      ALTER DEFAULT PRIVILEGES FOR ROLE studyroom_migration IN SCHEMA session GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO studyroom_repair;''', database)
    migration = ops.native_env('local', 'migration')
    migration['DB_URL'] = migration['DB_URL'].rsplit('/', 1)[0] + '/' + database
    ops.run(['java', '-jar', ops.jar('migration'), 'migrate'], env=migration)
    psql('REVOKE UPDATE,DELETE ON repair.audit_event FROM studyroom_repair', database)
    env['DB_URL'] = env['DB_URL'].rsplit('/', 1)[0] + '/' + database
    env['STUDYROOM_REPAIR_DEADLINE_TESTS'] = 'true'
    env['STUDYROOM_REPAIR_DEADLINE_FIXTURE'] = str(source)
    ops.run([ROOT / 'mvnw', '-B', '-ntp', '-pl', 'services/repair-service', '-am',
             '-Dtest=RepairDeadlineHttpTest', '-Dsurefire.failIfNoSpecifiedTests=false', 'test'], env=env)
    assert_recipients(fixture, output)


def main():
    from run_core_persistence import ops
    with local_runner_lock(ops, workspace=True):
        run_local()


if __name__ == '__main__':
    main()
