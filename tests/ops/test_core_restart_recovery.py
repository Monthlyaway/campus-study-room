"""Safety/negative-assertion tests only; these are not real restart evidence."""
import contextlib
import copy
import datetime as dt
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import uuid

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('core_restart_probe', ROOT / 'tests/integration/core_restart_recovery.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class CoreRestartRecoverySafetyTests(unittest.TestCase):
    def setUp(self):
        self.fixture = probe.fixture_plan('a152b53b-5335-4351-86b1-bca9b03c03d4', dt.datetime(2031, 1, 2, tzinfo=dt.timezone.utc))

    def successful_observation(self):
        result = {key: [] for key in ('orders', 'tasks', 'claims', 'violations', 'notifications', 'audits')}
        for row in self.fixture['orders']:
            result['orders'].append({'id': row['id'], 'status': row['expectedStatus'], 'version': row['initialVersion'] + 1,
                                     'ended_at': row['endAt'] if row['expectedStatus'] == 'COMPLETED' else None})
            result['tasks'].extend({'id': task['id'], 'status': 'DONE', 'attempts': 0, 'lease_owner': None,
                                    'lease_until': None, 'last_error_code': None} for task in row['tasks'])
            result['audits'].append({'object_id': row['id'], 'action': row['auditAction'], 'actor_type': 'JOB'})
            if row['claimId']:
                result['claims'].append({'id': row['claimId'], 'released_at': row['endAt'], 'release_request_id': str(uuid.uuid4())})
                result['audits'].append({'object_id': row['id'], 'action': 'RESOURCE_RELEASED', 'actor_type': 'USER'})
            if row['expectedStatus'] == 'NO_SHOW':
                result['violations'].append({'reservation_id': row['id'], 'user_id': row['userId'], 'reason': 'NO_SHOW'})
                result['notifications'].append({'source_id': row['id'], 'recipient_id': row['userId'], 'event_type': 'NO_SHOW'})
        return result

    def test_three_overdue_cases_have_ten_real_tasks_without_precomputed_results(self):
        rows = self.fixture['orders']
        self.assertEqual(['RESERVED', 'CHECKED_IN', 'PENDING'], [row['initialStatus'] for row in rows])
        tasks = [task for row in rows for task in row['tasks']]
        self.assertEqual(10, len({task['id'] for task in tasks}))
        self.assertTrue(all(dt.datetime.fromisoformat(task['dueAt']) < dt.datetime.fromisoformat(self.fixture['createdAt']) for task in tasks))
        statement = probe.fixture_sql(self.fixture)
        self.assertEqual(2, statement.count('INSERT INTO core.resource_claim('))
        self.assertEqual(10, statement.count('INSERT INTO core.scheduled_task('))
        self.assertIn('room_application:review_dept', statement)
        self.assertIn('SYNTHETIC CORE RESTART', statement)
        for prohibited in ('DELETE ', 'TRUNCATE ', 'UPDATE ', 'INSERT INTO core.audit_event', 'INSERT INTO core.notification', 'INSERT INTO core.violation'):
            self.assertNotIn(prohibited, statement)
        with self.assertRaises(ValueError):
            probe.fixture_plan("'; DROP TABLE core.reservation;", dt.datetime.now(dt.timezone.utc))
        with self.assertRaisesRegex(RuntimeError, 'timezone'):
            probe.fixture_plan(str(uuid.uuid4()), dt.datetime.now())

    def test_result_assertions_reject_duplicate_or_invalid_business_effects(self):
        original = self.successful_observation()
        probe.assert_recovered(self.fixture, original)
        cases = ('extra_reminder', 'duplicate_violation', 'duplicate_audit', 'repeated_version',
                 'wrong_ended_at', 'unreleased_claim', 'pending_application_claim',
                 'missing_task', 'task_still_pending', 'task_retry_error', 'wrong_recipient')
        for case in cases:
            with self.subTest(case=case):
                result = copy.deepcopy(original)
                if case == 'extra_reminder': result['notifications'].append({'event_type': 'M1_LATE'})
                elif case == 'duplicate_violation': result['violations'].append(copy.deepcopy(result['violations'][0]))
                elif case == 'duplicate_audit': result['audits'].append(copy.deepcopy(result['audits'][0]))
                elif case == 'repeated_version': result['orders'][0]['version'] += 1
                elif case == 'wrong_ended_at': result['orders'][1]['ended_at'] = self.fixture['orders'][1]['startAt']
                elif case == 'unreleased_claim': result['claims'][0]['released_at'] = None
                elif case == 'pending_application_claim': result['claims'].append({'id': str(uuid.uuid4())})
                elif case == 'missing_task': result['tasks'].pop()
                elif case == 'task_still_pending': result['tasks'][0]['status'] = 'PENDING'
                elif case == 'task_retry_error': result['tasks'][0]['attempts'] = 1
                elif case == 'wrong_recipient': result['notifications'][0]['recipient_id'] = str(uuid.uuid4())
                with self.assertRaises(RuntimeError):
                    probe.assert_recovered(self.fixture, result)

    def test_container_safety_rejects_foreign_project_image_and_budget(self):
        image_ids = {name: 'sha256:' + name for name in ('core', 'repair', 'web')}
        manifest = {'images': {name: {'id': value} for name, value in image_ids.items()}}
        metadata = {'images': image_ids}
        memory = {'postgres': 3 * 1024**3, 'core': 2 * 1024**3, 'repair': 2 * 1024**3, 'web': 512 * 1024**2, 'mailpit': 512 * 1024**2}
        containers = [{'Id': name, 'Image': image_ids.get(name, 'sha256:dependency'), 'State': {'Running': True},
                       'Config': {'Labels': {'com.docker.compose.service': name, 'com.docker.compose.project': 'studyroom-performance'}},
                       'HostConfig': {'CpusetCpus': '0-3', 'Memory': size, 'MemorySwap': size}} for name, size in memory.items()]
        self.assertEqual(probe.SERVICES, set(probe.validate_containers(containers, manifest, metadata)))
        for case in ('project', 'image', 'stopped', 'missing', 'cpu', 'memory', 'swap'):
            with self.subTest(case=case):
                rows = copy.deepcopy(containers)
                if case == 'project': rows[0]['Config']['Labels']['com.docker.compose.project'] = 'studyroom-test'
                elif case == 'image': rows[1]['Image'] = 'sha256:wrong'
                elif case == 'stopped': rows[0]['State']['Running'] = False
                elif case == 'missing': rows.pop()
                elif case == 'cpu': rows[0]['HostConfig']['CpusetCpus'] = '0-7'
                elif case == 'memory': rows[0]['HostConfig'].update(Memory=4*1024**3, MemorySwap=4*1024**3)
                elif case == 'swap': rows[0]['HostConfig']['MemorySwap'] = -1
                with self.assertRaises(RuntimeError):
                    probe.validate_containers(rows, manifest, metadata)

    def test_original_data_fingerprint_is_read_only_and_excludes_only_fixture_orders(self):
        with patch.object(probe.perf, 'sql', return_value='{}') as query:
            probe.invariant_snapshot(self.fixture)
        statement = query.call_args.args[0]
        self.assertTrue(statement.startswith('SELECT '))
        self.assertIn('row_to_json(r)', statement)
        for row in self.fixture['orders']:
            self.assertEqual(3, statement.count(row['id']))
        self.assertNotIn('UPDATE ', statement)

    def test_sql_json_preserves_postgresql_multiline_aggregate_output(self):
        # json_agg(composite rows) emits newlines between records under psql -At.
        output = '{"orders" : [{"id":"first","status":"RESERVED"},\n {"id":"second","status":"CHECKED_IN"}], "tasks" : []}\n'
        with patch.object(probe.perf, 'sql', return_value=output):
            actual = probe.json_sql('SELECT json_build_object(...);')
        self.assertEqual(['first', 'second'], [row['id'] for row in actual['orders']])
        self.assertEqual([], actual['tasks'])

    def test_deployment_lock_is_nonblocking_and_preserves_existing_lock(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(probe.perf, 'STATE', Path(directory)):
            with probe.deployment_lock():
                with self.assertRaisesRegex(RuntimeError, 'Another deployment'):
                    with probe.deployment_lock():
                        self.fail('A second lock owner must be refused')
            path = Path(directory) / 'deployment.lock'
            self.assertTrue(path.exists())
            self.assertEqual(0o600, path.stat().st_mode & 0o777)

    def test_wrong_state_and_external_symlink_are_refused(self):
        with patch.object(probe.perf, 'STATE', ROOT / '.local/local'):
            with self.assertRaisesRegex(RuntimeError, 'fixed isolated'):
                probe.assert_fixed_layout()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / '.local').mkdir()
            (root / 'other').mkdir()
            (root / '.local/performance').symlink_to(root / 'other')
            with patch.object(probe, 'ROOT', root), patch.object(probe.perf, 'STATE', root / '.local/performance'):
                with self.assertRaisesRegex(RuntimeError, 'fixed isolated'):
                    probe.assert_fixed_layout()

    def test_prior_performance_must_match_final_release_and_full_duration(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(probe.perf, 'OUTPUT', Path(directory)):
            path = Path(directory)
            run_id = '20310102T120000Z'
            (path / 'latest-run.txt').write_text(run_id)
            run = path / run_id
            run.mkdir()
            manifest = {'releaseId': 'final', 'commit': 'commit', 'images': {name: {'id': 'sha256:' + name} for name in ('core', 'repair', 'web')}}
            environment = {'releaseId': 'final', 'releaseCommit': 'commit', 'images': {name: value['id'] for name, value in manifest['images'].items()}}
            result = {'passed': True, 'warmupSeconds': 60, 'measurementSeconds': 300, 'concurrency': 20,
                      'dataBefore': {'accounts': 1000, 'rooms': 20, 'seats': 1000, 'historicalReservations': 100000}}
            (run / 'environment.json').write_text(json.dumps(environment))
            (run / 'result.json').write_text(json.dumps(result))
            self.assertEqual(run_id, probe.completed_performance(manifest)['runId'])
            for field, invalid in (('measurementSeconds', 30), ('warmupSeconds', 0), ('concurrency', 1), ('passed', False)):
                (run / 'result.json').write_text(json.dumps({**result, field: invalid}))
                with self.assertRaisesRegex(RuntimeError, 'full passing'):
                    probe.completed_performance(manifest)
            (run / 'result.json').write_text(json.dumps(result))
            (run / 'environment.json').write_text(json.dumps({**environment, 'releaseId': 'development'}))
            with self.assertRaisesRegex(RuntimeError, 'final release'):
                probe.completed_performance(manifest)
            (path / 'latest-run.txt').write_text('../local')
            with self.assertRaisesRegex(RuntimeError, 'Unsafe'):
                probe.completed_performance(manifest)

    def test_default_plan_cannot_start_docker_or_write_database(self):
        with patch('sys.argv', ['core_restart_recovery.py']), patch.object(probe.perf, 'compose') as compose, patch.object(probe.perf, 'sql') as sql, patch.object(probe, 'run') as run, contextlib.redirect_stdout(io.StringIO()) as output:
            probe.main()
        self.assertEqual('not started', json.loads(output.getvalue())['execution'])
        compose.assert_not_called()
        sql.assert_not_called()
        run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
