#!/usr/bin/env python3
"""T50-03 / M1-05 / M2-03: real core restart, retained performance database.

Default command only prints the plan. Run AFTER the final six-minute performance
measurement, with an explicitly approved CPU window. This adds three labeled
synthetic historical orders; it never resets a dataset, deletes rows/volumes,
changes a clock, invokes task code directly, or creates replacement containers.
"""
import argparse
import contextlib
import datetime as dt
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('core_recovery_environment', ROOT / 'tests/performance/environment.py')
perf = importlib.util.module_from_spec(spec)
spec.loader.exec_module(perf)
FIRST_PROCESSING_LIMIT = 60
RECOVERY_TIMEOUT = 180
STABILITY_SECONDS = 65  # More than two actual 30-second scheduler intervals.
SERVICES = {'postgres', 'core', 'repair', 'web', 'mailpit'}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def json_sql(statement):
    # PostgreSQL json_agg(row_to_json(...)) can insert newlines between rows.
    # These probes each issue one SELECT, so parse its entire JSON value.
    return json.loads(perf.sql(statement).strip())


def literal(value):
    return "'" + str(value).replace("'", "''") + "'"


def identifiers(fixture):
    return ','.join(literal(str(uuid.UUID(row['id']))) for row in fixture['orders'])


def fixture_plan(run_id, now):
    run_id = str(uuid.UUID(run_id))
    require(now.tzinfo is not None, 'Fixture timestamps must be timezone aware')
    start = (now.astimezone(dt.timezone(dt.timedelta(hours=8))) - dt.timedelta(days=2)).replace(hour=10, minute=0, second=0, microsecond=0)
    def identity(name):
        return str(uuid.uuid5(uuid.UUID(run_id), name))
    orders = []
    for kind, number, initial, terminal, version, action in (
        ('M1', 0, 'RESERVED', 'NO_SHOW', 0, 'RESERVATION_NO_SHOW'),
        ('M1', 50, 'CHECKED_IN', 'COMPLETED', 1, 'RESERVATION_COMPLETED'),
        ('M2', 100, 'PENDING', 'EXPIRED', 0, 'APPLICATION_EXPIRED'),
    ):
        name = kind + '-' + initial
        orders.append({'id': identity(name), 'sourceType': kind, 'initialStatus': initial,
                       'expectedStatus': terminal, 'initialVersion': version, 'auditAction': action,
                       'userId': perf.ident('user', number), 'roomId': perf.ident('room', number // 50),
                       'seatId': perf.ident('seat', number) if kind == 'M1' else None,
                       'claimId': identity(name + '-claim') if kind == 'M1' else None,
                       'claimRequestId': identity(name + '-request'),
                       'startAt': start.isoformat(), 'endAt': (start + dt.timedelta(hours=1)).isoformat(),
                       'createdAt': (start - dt.timedelta(days=1)).isoformat(),
                       'tasks': [{'id': identity(name + '-' + task), 'type': task,
                                  'dueAt': (start + dt.timedelta(minutes=offset)).isoformat()}
                                 for task, offset in ([('M1_PRE_START', -15), ('M1_LATE', 10), ('M1_NO_SHOW', 15), ('M1_COMPLETE', 60)]
                                                      if kind == 'M1' else [('M2_EXPIRE', 0), ('M2_COMPLETE', 60)])],
                       'stepIds': [identity(name + '-step-' + str(n)) for n in (1, 2)]})
    return {'runId': run_id, 'fixtureKind': 'synthetic-overdue-orders-in-isolated-performance-database',
            'createdAt': now.isoformat(), 'orders': orders, 'taskCount': 10,
            'notice': 'Historical fixtures are inserted while core is stopped, not submitted through the public API. All subsequent transitions, claims, audits and notifications must be produced by the real scheduler.'}


def fixture_sql(fixture):
    """Only INSERT new UUIDs; no destructive cleanup or prewritten success evidence."""
    statements = ['BEGIN;', "SET LOCAL lock_timeout='5s';", "SET LOCAL statement_timeout='30s';"]
    label = 'SYNTHETIC CORE RESTART ' + str(uuid.UUID(fixture['runId']))
    for row in fixture['orders']:
        q = {k: literal(v) for k, v in row.items() if not isinstance(v, (list, dict))}
        if row['sourceType'] == 'M1':
            checked = q['startAt'] if row['initialStatus'] == 'CHECKED_IN' else 'NULL'
            updated = checked if row['initialStatus'] == 'CHECKED_IN' else q['createdAt']
            statements.append(f"""INSERT INTO core.reservation(id,user_id,room_id,seat_id,start_at,end_at,status,checked_in_at,config_version,max_duration_hours,version,created_at,updated_at,operator_note)
                VALUES({q['id']},{q['userId']},{q['roomId']},{q['seatId']},{q['startAt']},{q['endAt']},{q['initialStatus']},{checked},0,4,{row['initialVersion']},{q['createdAt']},{updated},{literal(label)});""")
            statements.append(f"""INSERT INTO core.resource_claim(id,room_id,seat_id,start_at,end_at,source_type,source_id,source_version,created_by,created_request_id)
                VALUES({q['claimId']},{q['roomId']},{q['seatId']},{q['startAt']},{q['endAt']},'M1',{q['id']},0,{q['userId']},{q['claimRequestId']});""")
        else:
            statements.append(f"""INSERT INTO core.room_application(id,applicant_id,applicant_department_id,room_id,start_at,end_at,purpose_title,purpose_description,status,current_step,expires_at,config_version,max_duration_hours,created_at,updated_at)
                VALUES({q['id']},{q['userId']},{literal(perf.ident('department'))},{q['roomId']},{q['startAt']},{q['endAt']},{literal(label)},{literal(fixture['notice'])},'PENDING',1,{q['startAt']},0,4,{q['createdAt']},{q['createdAt']});""")
            for number, permission, status in ((1, 'room_application:review_dept', 'PENDING'), (2, 'room_application:review_space', 'WAITING')):
                statements.append(f"INSERT INTO core.approval_step(id,application_id,step_no,required_permission,status) VALUES({literal(row['stepIds'][number-1])},{q['id']},{number},{literal(permission)},{literal(status)});")
        for task in row['tasks']:
            statements.append(f"""INSERT INTO core.scheduled_task(id,task_type,source_type,source_id,dedup_key,due_at,status,next_attempt_at)
                VALUES({literal(task['id'])},{literal(task['type'])},{q['sourceType']},{q['id']},{literal(label + ':' + task['id'])},{literal(task['dueAt'])},'PENDING',{literal(task['dueAt'])});""")
    statements.append('COMMIT;')
    return '\n'.join(statements) + '\n'


def snapshot(fixture):
    ids = identifiers(fixture)
    # Delivery status may advance independently; compare notification identity and
    # business content, not SMTP worker lease/status, during the duplicate check.
    return json_sql(f"""SELECT json_build_object(
      'observedAt',clock_timestamp(),
      'orders',(SELECT json_agg(r ORDER BY id) FROM (
        SELECT id,status,version,ended_at,updated_at FROM core.reservation WHERE id IN({ids})
        UNION ALL SELECT id,status,version,NULL::timestamptz,updated_at FROM core.room_application WHERE id IN({ids})) r),
      'tasks',(SELECT json_agg(r ORDER BY id) FROM (SELECT id,task_type,source_id,status,attempts,lease_owner,lease_until,last_error_code FROM core.scheduled_task WHERE source_id IN({ids})) r),
      'claims',(SELECT coalesce(json_agg(r ORDER BY id),'[]') FROM (SELECT id,source_id,released_at,release_request_id FROM core.resource_claim WHERE source_id IN({ids})) r),
      'violations',(SELECT coalesce(json_agg(r ORDER BY id),'[]') FROM (SELECT id,reservation_id,user_id,reason FROM core.violation WHERE reservation_id IN({ids})) r),
      'notifications',(SELECT coalesce(json_agg(r ORDER BY id),'[]') FROM (SELECT id,source_id,recipient_id,event_type,dedup_key FROM core.notification WHERE source_id IN({ids})) r),
      'audits',(SELECT coalesce(json_agg(r ORDER BY id),'[]') FROM (SELECT id,object_id,action,actor_type,request_id,created_at FROM core.audit_event WHERE object_id IN({ids})) r));""")


def assert_recovered(fixture, actual):
    orders = {row['id']: row for row in actual['orders'] or []}
    require(set(orders) == {row['id'] for row in fixture['orders']}, 'Missing or unexpected recovery order')
    for row in fixture['orders']:
        found = orders[row['id']]
        require(found['status'] == row['expectedStatus'] and found['version'] == row['initialVersion'] + 1, 'Wrong terminal state or repeated business transition: ' + row['id'])
        transitions = [a for a in actual['audits'] if a['object_id'] == row['id'] and a['action'] == row['auditAction']]
        require(len(transitions) == 1 and transitions[0]['actor_type'] == 'JOB', 'Expected exactly one real scheduler transition audit')
        releases = [a for a in actual['audits'] if a['object_id'] == row['id'] and a['action'] == 'RESOURCE_RELEASED']
        require(len(releases) == (1 if row['claimId'] else 0), 'Claim release audit count differs')
        if row['expectedStatus'] == 'COMPLETED':
            require(dt.datetime.fromisoformat(found['ended_at']) == dt.datetime.fromisoformat(row['endAt']), 'Completion endedAt must equal the booked end')
    expected_tasks = {task['id'] for row in fixture['orders'] for task in row['tasks']}
    require({t['id'] for t in actual['tasks'] or []} == expected_tasks, 'Missing or duplicate scheduled task')
    require(all(t['status'] == 'DONE' and t['attempts'] == 0 and not t['lease_owner'] and not t['lease_until'] and not t['last_error_code'] for t in actual['tasks']), 'Not all original tasks completed cleanly')
    require({c['id'] for c in actual['claims']} == {row['claimId'] for row in fixture['orders'] if row['claimId']}, 'PENDING application must not acquire a claim')
    require(all(c['released_at'] and c['release_request_id'] for c in actual['claims']), 'An expired claim remains active')
    no_show = next(row for row in fixture['orders'] if row['expectedStatus'] == 'NO_SHOW')
    violations = actual['violations']
    require(len(violations) == 1 and violations[0]['reservation_id'] == no_show['id'] and violations[0]['user_id'] == no_show['userId'] and violations[0]['reason'] == 'NO_SHOW', 'NO_SHOW must create one violation and checked-in reservation none')
    notices = actual['notifications']
    require(len(notices) == 1 and notices[0]['source_id'] == no_show['id'] and notices[0]['recipient_id'] == no_show['userId'] and notices[0]['event_type'] == 'NO_SHOW', 'Only one NO_SHOW notification is allowed; expired PRE_START/LATE must not be sent')
    require(len(actual['audits']) == 5, 'Expected three transitions and two claim releases, without duplicates')


def invariant_snapshot(fixture):
    """Fingerprint existing business rows, excluding only this run's new orders."""
    ids = identifiers(fixture)
    probes = []
    for table, column in (('reservation', 'id'), ('room_application', 'id'), ('resource_claim', 'source_id')):
        probes.append(f"'{table}',(SELECT json_build_object('count',count(*),'rowDigestMd5',md5(coalesce(string_agg(md5(row_to_json(r)::text),'' ORDER BY id),''))) FROM core.{table} r WHERE {column} NOT IN({ids}))")
    return json_sql('SELECT json_build_object(' + ','.join(probes) + ');')


def assert_fixed_layout():
    expected = ROOT / '.local/performance'
    require(perf.PROJECT == 'studyroom-performance' and perf.STATE == expected and expected.resolve() == expected, 'Only the fixed isolated performance project/state is accepted')
    require(expected.is_dir() and not expected.is_symlink(), 'Performance state must already exist, without symlinks')
    require(not any((expected / 'control' / name).exists() for name in ('jobs-paused', 'maintenance')), 'Existing pause/maintenance must be preserved; refusing recovery')


@contextlib.contextmanager
def deployment_lock():
    path = perf.STATE / 'deployment.lock'
    require(not path.is_symlink(), 'Refusing a symlink deployment lock')
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'a') as stream:
        try:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError('Another deployment/backup owns the performance environment') from None
        try:
            yield
        finally:
            fcntl.flock(stream, fcntl.LOCK_UN)


def inspect(container_ids):
    require(bool(container_ids), 'No existing performance containers')
    return json.loads(subprocess.check_output(['docker', 'inspect', *container_ids], text=True))


def validate_containers(containers, manifest, metadata):
    rows = {}
    for container in containers:
        labels = container['Config']['Labels']
        name = labels.get('com.docker.compose.service')
        require(labels.get('com.docker.compose.project') == 'studyroom-performance', 'Refusing a different Docker project')
        require(name not in rows and name in SERVICES and container['State']['Running'], 'Require exactly five existing running services')
        host = container['HostConfig']
        require(host['CpusetCpus'] == '0-3' and host['Memory'] > 0 and host['MemorySwap'] == host['Memory'], 'CPU/memory limits drifted')
        if name in manifest['images']:
            require(container['Image'] == manifest['images'][name]['id'] == metadata['images'][name], 'Running image differs from final release: ' + name)
        rows[name] = {'id': container['Id'], 'image': container['Image'], 'cpuSet': host['CpusetCpus'], 'memory': host['Memory'], 'memorySwap': host['MemorySwap']}
    require(set(rows) == SERVICES and sum(row['memory'] for row in rows.values()) == 8 * 1024 ** 3, 'Expected five services within a combined 8GiB')
    return rows


def completed_performance(manifest):
    name = (perf.OUTPUT / 'latest-run.txt').read_text().strip()
    require(re.fullmatch(r'\d{8}T\d{6}Z', name), 'Unsafe performance result path')
    result = json.loads((perf.OUTPUT / name / 'result.json').read_text())
    environment = json.loads((perf.OUTPUT / name / 'environment.json').read_text())
    require(result.get('passed') is True and result.get('warmupSeconds') == 60 and result.get('measurementSeconds') == 300 and result.get('concurrency') == 20, 'Run the full passing performance measurement before recovery')
    require(environment.get('releaseId') == manifest['releaseId'] and environment.get('releaseCommit') == manifest['commit'] and environment.get('images') == {k: v['id'] for k, v in manifest['images'].items()}, 'Performance result was not measured on this final release')
    require(all(result['dataBefore'].get(key) == value for key, value in {'accounts': 1000, 'rooms': 20, 'seats': 1000, 'historicalReservations': 100000}.items()), 'Performance result has the wrong data scale')
    return {'runId': name, 'resultSha256': hashlib.sha256((perf.OUTPUT / name / 'result.json').read_bytes()).hexdigest()}


def readiness():
    response = perf.compose('exec', '-T', 'core', 'curl', '--max-time', '2', '-fsS', 'http://localhost:8080/actuator/health/readiness', capture=True, check=False)
    return response.returncode == 0 and json.loads(response.stdout).get('status') == 'UP'


def preflight(manifest_path):
    assert_fixed_layout()
    manifest = perf.ops.verify_manifest(manifest_path)
    metadata = json.loads((perf.OUTPUT / 'environment.json').read_text())
    require(metadata.get('project') == perf.PROJECT and metadata.get('releaseId') == manifest['releaseId'] and metadata.get('releaseCommit') == manifest['commit'], 'Performance environment must already run the final release')
    containers = inspect(perf.compose('ps', '--status', 'running', '-q', capture=True).stdout.split())
    services = validate_containers(containers, manifest, metadata)
    measured = completed_performance(manifest)
    require(readiness(), 'Core must be healthy before the normal stop')
    require(int(perf.sql("SELECT count(*) FROM core.scheduled_task WHERE status<>'DONE' AND next_attempt_at<=clock_timestamp()+interval '5 minutes';")) == 0, 'Existing imminent tasks would change non-fixture records; refusing to interfere')
    scale = perf.counts()
    require(scale['accounts'] == 1000 and scale['rooms'] == 20 and scale['seats'] == 1000 and scale['historicalReservations'] >= 100000, 'Original performance dataset is missing')
    return {'releaseId': manifest['releaseId'], 'releaseCommit': manifest['commit'],
            'manifestSha256': hashlib.sha256(Path(manifest_path).read_bytes()).hexdigest(),
            'services': services, 'performance': measured, 'dataBefore': scale,
            'hostLimit': 'Docker services share CPUs 0-3 and total 8GiB; host and Docker VM are shared, not dedicated hardware.'}


def save(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n')


def run(manifest_path):
    checked = preflight(manifest_path)
    run_id = str(uuid.uuid4())
    directory = perf.OUTPUT / ('core-recovery-' + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + run_id[:8])
    directory.mkdir(mode=0o700)
    report = {'passed': False, 'runId': run_id, 'preflight': checked, 'thresholdSeconds': FIRST_PROCESSING_LIMIT,
              'stabilitySeconds': STABILITY_SECONDS, 'fixturesRetained': True,
              'testSourceCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
              'testScriptSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    core_id = checked['services']['core']['id']
    stop_attempted = False
    try:
        stop_attempted = True
        perf.compose('stop', '-t', '60', 'core', capture=True)
        stopped = inspect([core_id])[0]
        # JVM PID 1 returns 143 for a normal Docker SIGTERM; 137 is a forced kill.
        require(not stopped['State']['Running'] and stopped['State']['ExitCode'] in (0, 143) and not stopped['State']['OOMKilled'], 'Core did not stop normally; refusing fixture insertion')
        report['normalStop'] = {key: stopped['State'][key] for key in ('ExitCode', 'OOMKilled', 'FinishedAt')}
        fixture = fixture_plan(run_id, dt.datetime.now(dt.timezone.utc))
        save(directory / 'fixture.json', fixture)
        statement = fixture_sql(fixture)
        (directory / 'fixture.sql').write_text(statement)
        before = invariant_snapshot(fixture)
        save(directory / 'original-business-before.json', before)
        perf.sql(statement)
        initial = snapshot(fixture)
        save(directory / 'before-restart.json', initial)
        require(not initial['audits'] and not initial['notifications'] and not initial['violations'], 'Synthetic fixture must not contain success evidence')
        report['restartRequestedAt'] = dt.datetime.now(dt.timezone.utc).isoformat()
        started = time.monotonic()
        perf.compose('start', 'core', capture=True)  # Reuse the SAME stopped container.
        require(perf.compose('ps', '-q', 'core', capture=True).stdout.strip() == core_id, 'Core container was unexpectedly replaced')
        first_seen = healthy_seen = all_seen = None
        last_update = started
        while time.monotonic() - started < RECOVERY_TIMEOUT:
            current = snapshot(fixture)
            elapsed = time.monotonic() - started
            if current['audits'] and first_seen is None:
                first_seen = elapsed
            if healthy_seen is None and readiness():
                healthy_seen = time.monotonic() - started
            try:
                assert_recovered(fixture, current)
                if healthy_seen is not None:
                    all_seen = time.monotonic() - started
                    break
            except RuntimeError:
                pass  # Preserve and evaluate full evidence below; never lower thresholds.
            if time.monotonic() - last_update >= 30:
                print(json.dumps({'phase': 'recovering', 'elapsedSeconds': round(elapsed, 3), 'completedTasks': sum(t['status'] == 'DONE' for t in current['tasks'])}), flush=True)
                last_update = time.monotonic()
            time.sleep(1)
        save(directory / 'after-recovery.json', current)
        report.update(firstProcessingObservedSeconds=first_seen, healthyObservedSeconds=healthy_seen, allProcessedObservedSeconds=all_seen)
        assert_recovered(fixture, current)
        require(first_seen is not None and first_seen <= FIRST_PROCESSING_LIMIT and all_seen is not None, 'Core recovery did not begin within 60 seconds of restart request')
        # This stricter start-request bound also covers the PRD healthy-after-restart bound.
        stability_start = time.monotonic()
        print(json.dumps({'phase': 'checking-duplicates', 'firstProcessingSeconds': first_seen, 'allProcessedSeconds': all_seen}), flush=True)
        while time.monotonic() - stability_start < STABILITY_SECONDS:
            time.sleep(max(0, min(10, STABILITY_SECONDS - (time.monotonic() - stability_start))))
        stable = snapshot(fixture)
        save(directory / 'after-two-scans.json', stable)
        assert_recovered(fixture, stable)
        require({k: v for k, v in current.items() if k != 'observedAt'} == {k: v for k, v in stable.items() if k != 'observedAt'}, 'A subsequent scheduler scan duplicated or changed a result')
        after = invariant_snapshot(fixture)
        save(directory / 'original-business-after.json', after)
        require(before == after, 'Existing business rows changed during isolated recovery')
        require(readiness(), 'Core is not healthy after the recovery check')
        report.update(passed=True, dataAfter=perf.counts(), originalBusinessUnchanged=True,
                      taskCount=fixture['taskCount'], terminalTransitionCount=3, releasedClaims=2,
                      noShowViolations=1, noShowNotifications=1, expiredReminderNotifications=0,
                      actualStabilitySeconds=time.monotonic() - stability_start)
    except BaseException as error:
        report['failureType'] = type(error).__name__
        # Domain errors contain only our fixed assertion text and fixture UUIDs.
        if isinstance(error, RuntimeError):
            report['failure'] = str(error)
        raise
    finally:
        if stop_attempted:
            try:
                current_state = inspect([core_id])[0]
                if not current_state['State']['Running']:
                    perf.compose('start', 'core', capture=True)
                report['coreRunningAtExit'] = inspect([core_id])[0]['State']['Running']
            except Exception as error:
                report['cleanupFailureType'] = type(error).__name__
                report['passed'] = False
        report['finishedAt'] = dt.datetime.now(dt.timezone.utc).isoformat()
        save(directory / 'result.json', report)
        print(json.dumps({'output': str(directory), 'passed': report['passed']}), flush=True)
    require(report['passed'], 'Recovery failed; retained evidence identifies the failure')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', nargs='?', choices=('plan', 'preflight', 'run'), default='plan')
    parser.add_argument('--release-manifest', type=Path)
    args = parser.parse_args()
    if args.command == 'plan':
        print(json.dumps({'project': perf.PROJECT, 'state': str(perf.STATE), 'execution': 'not started',
                          'prerequisites': ['final release manifest', 'passing full performance measurement on that release', 'explicitly approved CPU window'],
                          'orders': ['M1 RESERVED -> NO_SHOW', 'M1 CHECKED_IN -> COMPLETED', 'M2 PENDING -> EXPIRED'],
                          'tasks': 10, 'firstProcessingLimitSeconds': FIRST_PROCESSING_LIMIT,
                          'duplicateObservationSeconds': STABILITY_SECONDS,
                          'retainedFixtureEffect': 'COMPLETED count increases by one; perform the fixed 100000-history load measurement first.'}, indent=2))
        return
    if args.release_manifest is None:
        parser.error('--release-manifest is required for preflight/run')
    assert_fixed_layout()
    with perf.exclusive_run(), deployment_lock():
        if args.command == 'preflight':
            print(json.dumps(preflight(args.release_manifest), indent=2))
        else:
            run(args.release_manifest)


if __name__ == '__main__':
    main()
