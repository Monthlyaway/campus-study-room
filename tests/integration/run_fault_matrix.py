#!/usr/bin/env python3
"""D07/X03/M3-03: isolated real-process failure/recovery, audit and secret-log acceptance.

No build, main-environment restart, business SQL writes, or mocked business rules.
`all` runs the API sequence. For independent browser evidence: setup, prepare;
click start on web18183; recover-block; click complete+accept; recover-release;
verify; stop. A file gate only drops resource-command requests before upstream.
"""
import argparse
import base64
from datetime import datetime, timedelta, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.request
import urllib.parse
import uuid

from run_core_persistence import ROOT, ops, psql

STATE = ROOT / '.local/fault-matrix'
OUTPUT = ROOT / 'output/fault-matrix'
DATABASE = 'studyroom_fault_matrix'
BLOCK = STATE / 'control/resource-blocked'
FIXTURE = STATE / 'fixture.json'
CORE = 'http://127.0.0.1:18181'
REPAIR = 'http://127.0.0.1:18182'
os.environ['STUDYROOM_CORE_URL'] = CORE
os.environ['STUDYROOM_REPAIR_URL'] = REPAIR
from repair_http import Client, RepairHttpTests


def write_json(path, value, private=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
    if private:
        path.chmod(0o600)


def evidence(name, value):
    write_json(OUTPUT / (name + '.json'), value)


def secret_needles(values):
    path = STATE / 'log-scan-needles.json'
    previous = json.loads(path.read_text()) if path.exists() else []
    write_json(path, sorted(set(previous + [v for v in values if v])), private=True)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def db(sql):
    return psql(sql, DATABASE)


def rows(sql):
    raw = db('SELECT coalesce(json_agg(t),\'[]\'::json) FROM (' + sql + ') t')
    return json.loads(raw)


def environment(service):
    env = ops.native_env('local', service)
    # Python HTTP-suite variables collide with Spring relaxed property binding:
    # STUDYROOM_CORE_URL overrides YAML studyroom.core-url / CORE_INTERNAL_URL.
    for key in list(env):
        if key.startswith(('SPRING_', 'STUDYROOM_')) or key in {
            'REPAIR_DB_URL', 'REPAIR_DB_USER', 'REPAIR_DB_PASSWORD',
            'INTERNAL_PASSWORD_FILE', 'JDK_JAVA_OPTIONS',
        }:
            env.pop(key)
    env['DB_URL'] = env['DB_URL'].rsplit('/', 1)[0] + '/' + DATABASE
    env.update(CONTROL_DIR=str(STATE / 'control'), MAIL_MODE='capture', MAIL_DIRECTORY=str(STATE / 'mail'),
               CORE_INTERNAL_URL='http://127.0.0.1:18180', SERVER_PORT='18181' if service == 'core' else '18182',
               JAVA_TOOL_OPTIONS='-Xms64m -Xmx384m', JOBS_PAUSED='false', RELEASE_ID='isolated-fault-matrix')
    # Keep production 30-second worker scan and real retry backoff defaults.
    return env


def command(pid):
    return subprocess.run(['ps', '-p', str(pid), '-o', 'command='], text=True, capture_output=True).stdout.strip()


def launch(name, args, env=None):
    pidfile = STATE / (name + '.pid')
    if pidfile.exists() and command(int(pidfile.read_text())):
        raise RuntimeError('Refusing duplicate process: ' + name)
    with (STATE / (name + '.log')).open('a') as stream:
        process = subprocess.Popen([str(a) for a in args], cwd=ROOT, env=env, stdout=stream, stderr=stream, start_new_session=True)
    pidfile.write_text(str(process.pid))


def stop(name):
    path = STATE / (name + '.pid')
    if not path.exists():
        return
    pid = int(path.read_text())
    running = command(pid)
    if not running:
        return
    marker = str(STATE / 'run' / (name + '.jar')) if name in ('core', 'repair') else str(STATE)
    if marker not in running:
        raise RuntimeError('Refusing to stop unrelated PID: ' + name)
    os.kill(pid, signal.SIGTERM)
    end = time.monotonic() + 60
    while time.monotonic() < end:
        status = subprocess.run(['ps', '-p', str(pid), '-o', 'stat='], text=True, capture_output=True).stdout.strip()
        if not status or status.startswith('Z'):
            return
        time.sleep(.2)
    raise RuntimeError('Owned process did not stop gracefully: ' + name)


def start_service(service):
    launch(service, ['java', '-jar', STATE / 'run' / (service + '.jar')], environment(service))
    ops.wait_http((CORE if service == 'core' else REPAIR) + '/actuator/health/readiness')


def setup():
    for port in (18180, 18181, 18182, 18183):
        with socket.socket() as sock:
            if sock.connect_ex(('127.0.0.1', port)) == 0:
                raise RuntimeError('Port already owned: ' + str(port))
    for directory in ('run', 'control', 'web'):
        (STATE / directory).mkdir(parents=True, exist_ok=True)
    if FIXTURE.exists():
        raise RuntimeError('Existing isolated fixture preserved; use explicit phases, not duplicate setup')
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    artifacts = {}
    for service in ('core', 'repair'):
        source = ROOT / '.local/local/run' / (service + '.jar')
        target = STATE / 'run' / source.name
        shutil.copy2(source, target)
        artifacts[service] = {'copiedFrom': str(source.relative_to(ROOT)), 'sha256': digest(target), 'sourceMtime': source.stat().st_mtime}
    migration = STATE / 'run/migration.jar'
    shutil.copy2(ops.jar('migration'), migration)
    artifacts['migration'] = {'sha256': digest(migration)}
    shutil.copytree(ROOT / 'apps/web/dist', STATE / 'web', dirs_exist_ok=True)
    artifacts['web'] = {str(p.relative_to(STATE / 'web')): digest(p) for p in sorted((STATE / 'web').rglob('*')) if p.is_file()}
    evidence('runtime', {'recordedAt': time.time(), 'checkoutCommitAtCopy': commit, 'warning': 'Stable runtime copies are independently hashed; checkout HEAD is not proof of their source commit or final typed DTO version.', 'database': DATABASE, 'ports': [18180, 18181, 18182, 18183], 'artifacts': artifacts})
    if not psql("SELECT 1 FROM pg_database WHERE datname='" + DATABASE + "'"):
        psql('CREATE DATABASE ' + DATABASE + ' OWNER studyroom_migration')
    db('''CREATE SCHEMA IF NOT EXISTS core AUTHORIZATION studyroom_migration;
CREATE SCHEMA IF NOT EXISTS repair AUTHORIZATION studyroom_migration;
CREATE SCHEMA IF NOT EXISTS session AUTHORIZATION studyroom_migration;
GRANT USAGE ON SCHEMA core,session TO studyroom_core;
GRANT USAGE ON SCHEMA repair,session TO studyroom_repair;
ALTER DEFAULT PRIVILEGES FOR ROLE studyroom_migration IN SCHEMA core GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO studyroom_core;
ALTER DEFAULT PRIVILEGES FOR ROLE studyroom_migration IN SCHEMA repair GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO studyroom_repair;
ALTER DEFAULT PRIVILEGES FOR ROLE studyroom_migration IN SCHEMA session GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO studyroom_core,studyroom_repair;''')
    env = environment('migration')
    with (STATE / 'migration.log').open('a') as stream:
        for action in ('migrate', 'reference', 'demo'):
            subprocess.run(['java', '-jar', str(migration), action], cwd=ROOT, env=env, stdout=stream, stderr=stream, check=True)
    db('REVOKE UPDATE,DELETE ON core.audit_event FROM studyroom_core; REVOKE UPDATE,DELETE ON repair.audit_event FROM studyroom_repair')
    start_service('core')
    launch('proxy', [sys.executable, ROOT / 'tests/integration/fault_proxy.py', '--upstream', CORE, '--port', '18180', '--events', STATE / 'proxy-events.jsonl', '--resource-block-file', BLOCK, '--no-response-loss'])
    start_service('repair')
    launch('web', [sys.executable, ROOT / 'tests/integration/fault_matrix_web.py', '--directory', STATE / 'web', '--port', '18183'])
    ops.wait_http('http://127.0.0.1:18183/')
    secret_needles([ops.secret(ROOT / '.local/local', key) for key in ('demo_password', 'core_db_password', 'repair_db_password', 'migration_db_password', 'internal_password', 'checkin_key')])
    print('Isolated runtime ready; stable artifact digests recorded. No main process changed.')


def remember_clients(clients):
    secret_needles([cookie.value for client in clients for cookie in client.cookies])


def clients():
    fixture = json.loads(FIXTURE.read_text())
    password = ops.secret(ROOT / '.local/local', 'demo_password')
    result = {key: Client(username, password) for key, username in fixture['users'].items()}
    remember_clients(result.values())
    return fixture, result


def prepare():
    if FIXTURE.exists():
        raise RuntimeError('Fixture already exists; refusing duplicate creation')
    os.environ['STUDYROOM_TEST_PASSWORD'] = ops.secret(ROOT / '.local/local', 'demo_password')
    RepairHttpTests.setUpClass()
    case = RepairHttpTests()
    order, _ = case.create('网络中断恢复与双侧审计验收')
    assigned = case.action(case.dispatcher, order, 'assign', assignedTo=case.worker_id)
    code = Client('room-admin', os.environ['STUDYROOM_TEST_PASSWORD']).get('/api/v1/rooms/' + case.room_id + '/display-code')['code']
    secret_needles([code])
    actor_map = {'reporter': case.student, 'worker': case.worker, 'dispatch': case.dispatcher, 'property': case.admin}
    remember_clients(actor_map.values())
    fixture = {'orderId': assigned['id'], 'roomId': case.room_id, 'workerId': case.worker_id,
               'users': {key: actor.username for key, actor in actor_map.items()}, 'passwordFile': '.local/local/secrets/demo_password'}
    write_json(FIXTURE, fixture)
    BLOCK.touch()
    print(json.dumps(fixture, ensure_ascii=False))


def detail(actor, order_id):
    return actor.get('/api/v1/repairs/' + order_id)


def wait_for(actor, order_id, desired, failed_attempt=False, timeout=120):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        current = detail(actor, order_id)
        commands = rows("SELECT command_id,command_type,status,attempts,last_error_code,request_id FROM repair.outbox_command WHERE order_id='" + order_id + "' AND command_type<>'NOTIFY'")
        relevant = [c for c in commands if c['command_type'] == ('RELEASE' if desired == 'RELEASE_PENDING' else 'BLOCK')]
        if current['status'] == desired and (not failed_attempt or any(c['attempts'] > 0 for c in relevant)):
            return current, commands
        time.sleep(.5)
    raise AssertionError('Order did not reach ' + desired)


def action(actor, order_id, name, expected, **values):
    current = detail(actor, order_id)
    status, body = actor.call('POST', '/api/v1/repairs/' + order_id + '/' + name, {'expectedVersion': current['version'], **values})
    assert status == expected, (status, body)
    return body['data']


def recover(kind):
    fixture, users = clients()
    order_id = fixture['orderId']
    state = 'BLOCK_PENDING' if kind == 'block' else 'RELEASE_PENDING'
    pending, commands = wait_for(users['property'], order_id, state, failed_attempt=True)
    evidence(kind + '-pending', {'status': pending['status'], 'version': pending['version'], 'sync': pending.get('sync'), 'commands': commands})
    expected_claims = 0 if kind == 'block' else 1
    claims = rows("SELECT id,released_at FROM core.resource_claim WHERE source_type='M3' AND source_id='" + order_id + "'")
    assert len(claims) == expected_claims and all(c['released_at'] is None for c in claims), claims
    tomorrow = (datetime.now(timezone(timedelta(hours=8))) + timedelta(days=1)).replace(hour=10, minute=0, second=0, microsecond=0)
    availability_path = '/api/v1/availability/seats?' + urllib.parse.urlencode({'roomId': fixture['roomId'], 'startAt': tomorrow.isoformat(), 'endAt': (tomorrow + timedelta(hours=1)).isoformat()})
    seats_before = users['reporter'].get(availability_path)['items']
    assert bool(seats_before) == (kind == 'block')
    BLOCK.unlink()
    target = 'IN_REPAIR' if kind == 'block' else 'CLOSED'
    started = time.monotonic()
    current, commands = wait_for(users['property'], order_id, target, timeout=360)
    seats_after = users['reporter'].get(availability_path)['items']
    assert bool(seats_after) == (kind == 'release')
    evidence(kind + '-recovered', {'status': current['status'], 'secondsFromNetworkRestore': time.monotonic() - started, 'commands': commands, 'publicAvailableSeatsBefore': len(seats_before), 'publicAvailableSeatsAfter': len(seats_after)})
    if kind == 'block':
        BLOCK.touch()  # Hold RELEASE for independent UI evidence.
    print('PASS: ' + state + ' persisted; restored network automatically converged to ' + target)


def internal(body):
    password = ops.secret(ROOT / '.local/local', 'internal_password')
    credential = base64.b64encode(('repair-service:' + password).encode()).decode()
    secret_needles([credential])
    request = urllib.request.Request(CORE + '/internal/v1/resource-commands', data=json.dumps(body).encode(), headers={'Authorization': 'Basic ' + credential, 'Content-Type': 'application/json', 'X-Request-ID': str(uuid.uuid4())})
    with urllib.request.urlopen(request, timeout=15) as response:
        assert response.status == 200
        return json.load(response)['data']


def verify():
    fixture, users = clients()
    order_id = fixture['orderId']
    assert detail(users['property'], order_id)['status'] == 'CLOSED'
    admin = Client('admin', ops.secret(ROOT / '.local/local', 'demo_password'))
    remember_clients([admin])
    repairs = admin.get('/api/v1/repairs/audit-events?objectId=' + order_id + '&size=100')['items']
    core = admin.get('/api/v1/audit-events?objectId=' + order_id + '&size=100')['items']
    outbox = rows("SELECT command_id,command_type,payload_json,payload_hash,request_id,attempts,status FROM repair.outbox_command WHERE order_id='" + order_id + "' AND command_type<>'NOTIFY' ORDER BY source_version")
    assert len(outbox) == 2 and all(c['status'] == 'DONE' and c['attempts'] >= 1 for c in outbox)
    correlation = []
    for command_row, repair_action, core_action, user_action in zip(outbox, ('RESOURCE_BLOCK_CONFIRMED', 'RESOURCE_RELEASE_CONFIRMED'), ('RESOURCE_CLAIMED', 'RESOURCE_RELEASED'), ('REPAIR_START', 'REPAIR_ACCEPTANCE')):
        matching_repair = [a for a in repairs if a['action'] == repair_action]
        matching_core = [a for a in core if a['action'] == core_action]
        initiating_user = [a for a in repairs if a['action'] == user_action]
        assert len(matching_repair) == len(matching_core) == 1, (repair_action, repairs, core)
        request_id = command_row['request_id']
        assert len(initiating_user) == 1
        assert initiating_user[0]['requestId'] == matching_repair[0]['requestId'] == matching_core[0]['requestId'] == request_id
        correlation.append({'operation': command_row['command_type'], 'commandId': command_row['command_id'], 'requestId': request_id, 'coreAuditId': matching_core[0]['id'], 'repairAuditId': matching_repair[0]['id'], 'initiatingUserAuditId': initiating_user[0]['id']})
    evidence('audit-correlation', correlation)
    events = [json.loads(line) for line in (STATE / 'proxy-events.jsonl').read_text().splitlines()]
    for c in outbox:
        deliveries = [e for e in events if e['commandId'] == c['command_id']]
        assert any(e.get('requestBlocked') for e in deliveries) and any(e.get('upstreamStatus') == 200 for e in deliveries)
        assert len({e['payloadHash'] for e in deliveries}) == 1
        assert {e['requestId'] for e in deliveries} == {c['request_id']}
    evidence('proxy-events', events)
    original_block = outbox[0]['payload_json']
    duplicate = internal(original_block)
    stale_body = {**original_block, 'commandId': str(uuid.uuid4())}
    stale = internal(stale_body)
    assert duplicate['sourceState'] == 'ACTIVE', duplicate  # Original response replay, not current state.
    assert stale['result'] == 'STALE_IGNORED' and stale['sourceState'] == 'RELEASED' and stale['sourceVersion'] == 2
    source = rows("SELECT * FROM core.resource_source_state WHERE source_type='M3' AND source_id='" + order_id + "'")
    claims = rows("SELECT id,released_at FROM core.resource_claim WHERE source_type='M3' AND source_id='" + order_id + "'")
    assert len(source) == len(claims) == 1 and source[0]['state'] == 'RELEASED' and source[0]['max_version'] == 2 and claims[0]['released_at']
    ids = [outbox[0]['command_id'], outbox[1]['command_id'], stale_body['commandId']]
    processed = rows("SELECT command_id,payload_hash,http_status,result_json,processed_at FROM core.processed_command WHERE command_id IN (" + ','.join("'" + i + "'" for i in ids) + ')')
    assert len(processed) == 3
    evidence('late-block', {'originalReplay': duplicate, 'staleReplay': stale, 'source': source, 'claims': claims, 'processedCommands': processed})
    # Full core outage is a separate authorization boundary, not resource-path failure.
    status, body = users['reporter'].call('POST', '/api/v1/repairs', {'roomId': fixture['roomId'], 'title': '完整授权服务中断验收', 'description': '授权失败必须保持ASSIGNED且不创建封禁命令'})
    assert status == 201, body
    other = body['data']['id']
    assigned = action(users['dispatch'], other, 'assign', 200, assignedTo=fixture['workerId'])
    unchanged_query = "SELECT status,version,resource_version,(SELECT count(*) FROM repair.repair_history WHERE order_id=r.id) history_count,(SELECT count(*) FROM repair.audit_event WHERE object_id=r.id) audit_count FROM repair.repair_order r WHERE id='" + other + "'"
    before = rows(unchanged_query)
    stop('core')
    try:
        status, error = users['worker'].call('POST', '/api/v1/repairs/' + other + '/start', {'expectedVersion': assigned['version']})
        assert status == 503 and error['error']['code'] == 'AUTHORIZATION_UNAVAILABLE', (status, error)
        after = rows(unchanged_query)
        commands = rows("SELECT command_id FROM repair.outbox_command WHERE order_id='" + other + "' AND command_type='BLOCK'")
        assert before == after and not commands
        evidence('authorization-outage', {'httpStatus': status, 'errorCode': error['error']['code'], 'before': before, 'after': after, 'blockCommands': len(commands)})
    finally:
        start_service('core')
    action(users['dispatch'], other, 'cancel', 200, reason='独立授权故障验收完成')
    # Actual known secrets, captured API session cookies, and complete authorized daily code.
    needles = json.loads((STATE / 'log-scan-needles.json').read_text())
    logs = [STATE / name for name in ('core.log', 'repair.log', 'proxy.log', 'web.log', 'proxy-events.jsonl')]
    matches = [(path.name, index) for path in logs for index, value in enumerate(needles) if value.encode() in path.read_bytes()]
    assert not matches, 'Sensitive values appeared in logs (values deliberately withheld)'
    correlated_logs = []
    for service in ('core', 'repair'):
        records = [json.loads(line) for line in (STATE / (service + '.log')).read_text().splitlines() if line.startswith('{')]
        for correlation_row in correlation:
            relevant = [record for record in records if record.get('requestId') == correlation_row['requestId']]
            assert relevant, 'Expected cross-service requestId absent from structured log'
            correlated_logs.append({'service': service, 'operation': correlation_row['operation'], 'requestId': correlation_row['requestId'], 'matchingLogEvents': len(relevant)})
    evidence('log-scan', {'logFiles': [p.name for p in logs], 'bytesScanned': sum(p.stat().st_size for p in logs), 'knownSecretAndSessionValues': len(needles), 'exactValueMatches': 0, 'includesActualFullDailyCode': True, 'correlatedLogs': correlated_logs})
    evidence('result', {'status': 'passed', 'completedAt': time.time(), 'checks': ['resource BLOCK path unavailable persists pending', 'resource RELEASE path unavailable keeps active claim', 'automatic network restoration convergence', 'same command requestId in both audit APIs', 'stable command payload across retries', 'late BLOCK replay never resurrects released claim', 'permanent processed-command records', 'full core outage fails closed503 without transition', 'real credentials cookies daily code absent from logs']})
    print('PASS: fault matrix, both-service audits, stale BLOCK persistence and secret log scan')


def all_steps():
    setup()
    prepare()
    fixture, users = clients()
    action(users['worker'], fixture['orderId'], 'start', 202)
    recover('block')
    action(users['worker'], fixture['orderId'], 'complete', 200, workNote='实际网络恢复验收完成维修')
    action(users['property'], fixture['orderId'], 'acceptance', 202, decision='ACCEPT', comment='独立验收通过')
    recover('release')
    verify()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['setup', 'prepare', 'recover-block', 'recover-release', 'verify', 'all', 'stop'])
    action_name = parser.parse_args().action
    STATE.mkdir(parents=True, exist_ok=True)
    STATE.chmod(0o700)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    with (STATE / 'environment.lock').open('a') as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
        if action_name == 'all':
            all_steps()
        elif action_name == 'setup':
            setup()
        elif action_name == 'prepare':
            prepare()
        elif action_name.startswith('recover-'):
            recover(action_name.removeprefix('recover-'))
        elif action_name == 'verify':
            verify()
        else:
            for name in ('web', 'repair', 'proxy', 'core'):
                stop(name)
            print('Owned isolated processes stopped; database and evidence preserved.')


if __name__ == '__main__':
    main()
