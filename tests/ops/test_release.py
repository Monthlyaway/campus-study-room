"""Operational release gates: reject altered artifacts before deployment side effects."""
import importlib.util
from contextlib import ExitStack
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('studyroom_ops', ROOT / 'scripts/studyroom.py')
ops = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ops)


class ReleaseGateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / 'manifest.json'
        self.manifest = {
            'releaseId': 'r-test', 'commit': '1' * 40, 'rollbackCompatibility': 'expand-only-v1',
            'composeSha256': ops.digest(ops.BASE),
            'dependencyBaselineSha256': ops.digest(ROOT / 'docs/dependency-baseline.json'),
            'migrations': {str(p.relative_to(ROOT)): ops.digest(p) for p in sorted((ROOT / 'db/migration').rglob('*.sql'))},
            'images': {name: {'ref': 'studyroom-' + name + ':r-test', 'id': 'sha256:' + 'a' * 64} for name in ('core', 'repair', 'web')},
        }

    def tearDown(self):
        self.temp.cleanup()

    def verify(self):
        self.path.write_text(json.dumps(self.manifest))
        return ops.verify_manifest(self.path)

    @patch.object(ops, 'image_id', return_value='sha256:' + 'a' * 64)
    def test_verified_exact_combination_accepted(self, inspect):
        self.assertEqual(set(self.verify()['images']), {'core', 'repair', 'web'})
        self.assertEqual(inspect.call_count, 3)

    @patch.object(ops, 'image_id', return_value='sha256:' + 'b' * 64)
    def test_retagged_image_rejected(self, inspect):
        with self.assertRaisesRegex(RuntimeError, 'Image identity mismatch'):
            self.verify()

    def test_edited_compose_rejected_before_image_access(self):
        self.manifest['composeSha256'] = 'changed'
        with patch.object(ops, 'image_id') as inspect:
            with self.assertRaisesRegex(RuntimeError, 'Compose configuration differs'):
                self.verify()
            inspect.assert_not_called()

    def test_changed_or_missing_migration_rejected(self):
        self.manifest['migrations'].pop(next(iter(self.manifest['migrations'])))
        with self.assertRaisesRegex(RuntimeError, 'Migration contents differ'):
            self.verify()

    def test_partial_application_release_rejected(self):
        del self.manifest['images']['repair']
        with self.assertRaisesRegex(RuntimeError, 'exactly core, repair and web'):
            self.verify()

    def test_unsafe_manifest_fields_rejected_before_docker_or_maintenance(self):
        original = json.loads(json.dumps(self.manifest))
        cases = [('releaseId', '../outside'), ('releaseId', 'x\nOTHER=value'), ('releaseId', 'x' * 97),
                 ('commit', '1' * 39), ('commit', 'z' * 40), ('rollbackCompatibility', ''), ('rollbackCompatibility', '../../family')]
        for key, value in cases:
            with self.subTest(key=key, value=value):
                self.manifest = {**original, key: value}
                with patch.object(ops, 'image_id') as inspect, patch.object(ops, 'config') as config:
                    with self.assertRaisesRegex(RuntimeError, 'Invalid'):
                        self.verify()
                    inspect.assert_not_called()
                    config.assert_not_called()
        for key, value in [('id', 'sha256:short'), ('id', 'sha256:' + 'z' * 64), ('ref', 'valid\nCORE_IMAGE=other'), ('ref', ' image'), ('ref', '$(command)')]:
            with self.subTest(image_key=key):
                self.manifest = json.loads(json.dumps(original))
                self.manifest['images']['core'][key] = value
                with patch.object(ops, 'image_id') as inspect:
                    with self.assertRaisesRegex(RuntimeError, 'Invalid image'):
                        self.verify()
                    inspect.assert_not_called()

    def test_production_cannot_import_demo(self):
        with patch.object(ops, 'compose') as compose:
            with self.assertRaisesRegex(RuntimeError, 'Demo seed is forbidden'):
                ops.migrate('prod', action='demo')
            compose.assert_not_called()

    def test_failed_upgrade_schema_is_checked_instead_of_last_successful_release(self):
        state = Path(self.temp.name)
        old = {**self.manifest, 'releaseId': 'release-a', 'commit': 'a' * 40, 'rollbackCompatibility': 'expand-only-v1'}
        failed = {**old, 'releaseId': 'release-b', 'commit': 'b' * 40,
                  'migrations': {**old['migrations'], 'db/migration/core/V20990101000000__later.sql': 'new'}}
        (state / 'current-release.json').write_text(json.dumps(old))
        (state / 'pending-release.json').write_text(json.dumps(failed))
        with patch.object(ops, 'verify_manifest', return_value=old), patch.object(ops, 'config', return_value=(state, {})), patch.object(ops, 'compose') as compose:
            with self.assertRaisesRegex(RuntimeError, 'Schema-changing rollback requires'):
                ops.deploy('test', self.path, rollback=True)
            compose.assert_not_called()
        self.assertFalse((state / 'control/maintenance').exists())

    def test_unrelated_compatibility_proof_cannot_authorize_rollback(self):
        state = Path(self.temp.name)
        old = {**self.manifest, 'releaseId': 'release-a', 'commit': 'a' * 40, 'rollbackCompatibility': 'expand-only-v1'}
        failed = {**old, 'releaseId': 'release-b', 'commit': 'b' * 40, 'migrations': {'different': 'hash'}}
        (state / 'current-release.json').write_text(json.dumps(old))
        (state / 'pending-release.json').write_text(json.dumps(failed))
        (state / 'compatibility').mkdir()
        (state / 'compatibility/release-b--release-a.json').write_text(json.dumps({'fromCommit': 'c' * 40, 'toCommit': old['commit'], 'result': 'passed'}))
        with patch.object(ops, 'verify_manifest', return_value=old), patch.object(ops, 'config', return_value=(state, {})), patch.object(ops, 'compose') as compose:
            with self.assertRaisesRegex(RuntimeError, 'does not match both commits'):
                ops.deploy('test', self.path, rollback=True)
            compose.assert_not_called()

    def releases(self):
        old = {**self.manifest, 'releaseId': 'release-a', 'commit': 'a' * 40, 'rollbackCompatibility': 'expand-only-v1'}
        newer = {**old, 'releaseId': 'release-b', 'commit': 'b' * 40,
                 'migrations': {**old['migrations'], 'db/migration/core/V20990101000000__nullable.sql': 'b' * 64}}
        database = {'postgresVersion': '180006', 'migrations': [{'schema': 'core', 'version': '20990101000000', 'checksum': 42, 'success': True}]}
        state = Path(self.temp.name)
        (state / 'control').mkdir(exist_ok=True)
        return state, old, newer, database

    def proof(self, state, source, target, database):
        proof = {'formatVersion': 2, 'fromCommit': source['commit'], 'toCommit': target['commit'], 'result': 'passed',
                 'fromManifestSha256': ops.object_digest(source), 'toManifestSha256': ops.object_digest(target),
                 'fromImages': {k: v['id'] for k, v in source['images'].items()},
                 'toImages': {k: v['id'] for k, v in target['images'].items()},
                 'database': database, 'databaseSha256': ops.object_digest(database),
                 'checks': {key: True for key in ('threeImageIds', 'threeVersions', 'oldSessionCore', 'oldSessionRepair', 'businessData', 'oldVersionWrite', 'expandedColumnPreserved')}}
        directory = state / 'compatibility'
        directory.mkdir(exist_ok=True)
        ops.write_json(directory / (source['releaseId'] + '--' + target['releaseId'] + '.json'), proof)
        return proof

    def deployment_mocks(self, stack, state, target, database):
        def compose(environment, *args, **kwargs):
            if args[0] == 'ps':
                return SimpleNamespace(stdout='postgres\nmailpit\ncore\nrepair\nweb\n')
            if '/api/v1/version' in str(args):
                return SimpleNamespace(stdout=json.dumps({'data': {'commit': target['commit'], 'releaseId': target['releaseId']}}))
            if '/usr/share/nginx/html/version.json' in args:
                return SimpleNamespace(stdout=json.dumps({'commit': target['commit'], 'releaseId': target['releaseId']}))
            return SimpleNamespace(stdout='')
        mocks = {}
        for name, options in {
            'verify_manifest': {'return_value': target}, 'config': {'return_value': (state, {'PUBLIC_BASE_URL': 'http://example.invalid'})},
            'compose': {'side_effect': compose}, 'database_inventory': {'return_value': database},
            'wait_drained': {}, 'wait_status': {}, 'backup': {}, 'migrate': {}, 'smoke': {}, 'verify_running_release': {},
        }.items():
            mocks[name] = stack.enter_context(patch.object(ops, name, **options))
        return mocks

    def test_normal_deploy_cannot_bypass_rollback_by_using_old_source(self):
        state, old, newer, database = self.releases()
        ops.record_schema(state, newer, database)
        with patch.object(ops, 'compose') as compose:
            with self.assertRaisesRegex(RuntimeError, 'Deploy cannot remove'):
                ops.release_preflight(state, newer, old, False)
            compose.assert_not_called()

    def test_successful_application_rollback_keeps_newer_schema_high_watermark(self):
        state, old, newer, database = self.releases()
        ops.write_json(state / 'current-release.json', newer)
        ops.record_schema(state, newer, database)
        self.proof(state, newer, old, database)
        with ExitStack() as stack:
            mocks = self.deployment_mocks(stack, state, old, database)
            ops.deploy('test', self.path, rollback=True)
            mocks['migrate'].assert_not_called()
        self.assertEqual(json.loads((state / 'current-release.json').read_text()), old)
        self.assertEqual(json.loads((state / 'schema-release.json').read_text())['manifest'], newer)
        self.assertFalse((state / 'pending-release.json').exists())
        with self.assertRaisesRegex(RuntimeError, 'Deploy cannot remove'):
            ops.release_preflight(state, old, old, False)

    def test_matching_commits_do_not_accept_proof_for_different_manifest(self):
        state, old, newer, database = self.releases()
        self.proof(state, newer, old, database)
        changed = {**old, 'images': {**old['images'], 'web': {'ref': 'retagged', 'id': 'sha256:' + 'b' * 64}}}
        with self.assertRaisesRegex(RuntimeError, 'exact manifests'):
            ops.compatibility_proof(state, newer, changed, database)

    def test_compatibility_proof_must_match_actual_database_not_only_manifest(self):
        state, old, newer, database = self.releases()
        self.proof(state, newer, old, database)
        with self.assertRaisesRegex(RuntimeError, 'actual PostgreSQL/Flyway'):
            ops.compatibility_proof(state, newer, old, {**database, 'migrations': []})

    def test_failed_retry_before_shutdown_preserves_existing_maintenance(self):
        state, old, newer, database = self.releases()
        ops.write_json(state / 'current-release.json', old)
        ops.write_json(state / 'pending-release.json', newer)
        for name in ('maintenance', 'jobs-paused'):
            (state / 'control' / name).touch()
        with ExitStack() as stack:
            mocks = self.deployment_mocks(stack, state, newer, database)
            mocks['wait_drained'].side_effect = RuntimeError('Unhealthy partially started service')
            with self.assertRaisesRegex(RuntimeError, 'partially started'):
                ops.deploy('test', self.path)
        self.assertTrue((state / 'control/maintenance').exists())
        self.assertTrue((state / 'control/jobs-paused').exists())
        self.assertEqual(json.loads((state / 'pending-release.json').read_text()), newer)

    def test_public_reopen_failure_does_not_mark_release_successful(self):
        state, old, newer, database = self.releases()
        ops.write_json(state / 'current-release.json', newer)
        ops.record_schema(state, newer, database)
        self.proof(state, newer, old, database)
        with ExitStack() as stack:
            mocks = self.deployment_mocks(stack, state, old, database)
            mocks['wait_status'].side_effect = lambda url, status: (_ for _ in ()).throw(RuntimeError('Public reopen failed')) if status == 200 else None
            with self.assertRaisesRegex(RuntimeError, 'Public reopen failed'):
                ops.deploy('test', self.path, rollback=True)
        self.assertEqual(json.loads((state / 'current-release.json').read_text()), newer)
        failed = json.loads((state / 'pending-release.json').read_text())
        self.assertTrue(failed['failed'])
        self.assertEqual(failed['schemaManifest'], newer)
        self.assertTrue((state / 'control/maintenance').exists())
        self.assertTrue((state / 'control/jobs-paused').exists())

    def test_partial_migration_failure_keeps_actual_database_and_attempted_schema(self):
        state, old, newer, database = self.releases()
        before = {**database, 'migrations': []}
        ops.write_json(state / 'current-release.json', old)
        ops.record_schema(state, old, before)
        with ExitStack() as stack:
            mocks = self.deployment_mocks(stack, state, newer, before)
            mocks['database_inventory'].side_effect = [before, database]
            mocks['migrate'].side_effect = RuntimeError('Later schema migration failed')
            with self.assertRaisesRegex(RuntimeError, 'Later schema'):
                ops.deploy('test', self.path)
        failed = json.loads((state / 'pending-release.json').read_text())
        self.assertEqual(failed['phase'], 'migration_started')
        self.assertEqual(failed['databaseAfterFailure'], database)
        self.assertEqual(failed['schemaManifest'], newer)
        with self.assertRaisesRegex(RuntimeError, 'Deploy cannot remove'):
            ops.release_preflight(state, old, old, False)

    def test_startup_failure_automatically_recovers_previous_only_with_exact_proof(self):
        state, old, newer, database = self.releases()
        before = {**database, 'migrations': []}
        ops.write_json(state / 'current-release.json', old)
        ops.record_schema(state, old, before)
        self.proof(state, newer, old, database)
        with ExitStack() as stack:
            mocks = self.deployment_mocks(stack, state, newer, database)
            mocks['verify_manifest'].side_effect = [newer, old]
            mocks['database_inventory'].side_effect = [before, database, database, database]
            mocks['verify_running_release'].side_effect = [RuntimeError('New version mismatch'), None]
            with self.assertRaisesRegex(RuntimeError, 'New version mismatch'):
                ops.deploy('test', self.path)
            self.assertEqual(mocks['verify_running_release'].call_args_list[-1].args, ('test', old))
            self.assertEqual(mocks['migrate'].call_count, 2)
        self.assertEqual(json.loads((state / 'current-release.json').read_text()), old)
        self.assertEqual(json.loads((state / 'schema-release.json').read_text())['manifest'], newer)
        self.assertFalse((state / 'pending-release.json').exists())
        self.assertFalse((state / 'control/maintenance').exists())
        self.assertFalse((state / 'control/jobs-paused').exists())
        report = json.loads(next((state / 'release-failures').glob('*.json')).read_text())
        self.assertEqual(report['recovery']['result'], 'passed')

    def test_startup_failure_without_schema_proof_keeps_maintenance_and_pending(self):
        state, old, newer, database = self.releases()
        before = {**database, 'migrations': []}
        ops.write_json(state / 'current-release.json', old)
        ops.record_schema(state, old, before)
        with ExitStack() as stack:
            mocks = self.deployment_mocks(stack, state, newer, database)
            mocks['verify_manifest'].side_effect = [newer, old]
            mocks['database_inventory'].side_effect = [before, database, database, database]
            mocks['verify_running_release'].side_effect = RuntimeError('New startup failed')
            with self.assertRaisesRegex(RuntimeError, 'New startup failed'):
                ops.deploy('test', self.path)
            self.assertEqual(mocks['verify_running_release'].call_count, 1)
        self.assertTrue((state / 'control/maintenance').exists())
        self.assertTrue((state / 'control/jobs-paused').exists())
        self.assertEqual(json.loads((state / 'pending-release.json').read_text())['recovery']['result'], 'blocked_or_failed')

    def test_automatic_recovery_failure_never_reopens_public_service(self):
        state, old, newer, database = self.releases()
        before = {**database, 'migrations': []}
        ops.write_json(state / 'current-release.json', old)
        ops.record_schema(state, old, before)
        self.proof(state, newer, old, database)
        with ExitStack() as stack:
            mocks = self.deployment_mocks(stack, state, newer, database)
            mocks['verify_manifest'].side_effect = [newer, old]
            mocks['database_inventory'].side_effect = [before, database, database, database]
            mocks['verify_running_release'].side_effect = [RuntimeError('New startup failed'), RuntimeError('Old readiness failed')]
            with self.assertRaisesRegex(RuntimeError, 'New startup failed'):
                ops.deploy('test', self.path)
            self.assertFalse(any(call.args[-1] == 200 for call in mocks['wait_status'].call_args_list))
        self.assertTrue((state / 'control/maintenance').exists())
        self.assertTrue((state / 'control/jobs-paused').exists())
        self.assertTrue((state / 'pending-release.json').exists())


if __name__ == '__main__':
    unittest.main()
