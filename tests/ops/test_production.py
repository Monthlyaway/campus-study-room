"""Production startup keeps private services, real secrets, and immutable release gates."""
from contextlib import ExitStack
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('production_ops', ROOT / 'scripts/studyroom.py')
ops = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ops)


class ProductionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.state = Path(self.temp.name) / 'production'
        subprocess.run([sys.executable, str(ROOT / 'scripts/init-env'), 'prod',
                        '--directory', str(self.state)], check=True, stdout=subprocess.DEVNULL)
        (self.state / 'control').mkdir()
        self.values = dict(line.split('=', 1) for line in (self.state / 'compose.env').read_text().splitlines() if line)
        self.values.update(PUBLIC_BASE_URL='https://49.51.193.169', ADMIN_USERNAME='owner',
                           ADMIN_EMAIL='owner@campus.example')
        self.manifest = {
            'releaseId': 'production-test', 'commit': 'a' * 40,
            'rollbackCompatibility': 'expand-only-v1',
            'migrations': {'db/migration/core/V20261022000000__base.sql': 'b' * 64},
            'images': {key: {'ref': 'studyroom-' + key + ':test', 'id': 'sha256:' + 'c' * 64}
                       for key in ('core', 'repair', 'web')},
        }
        self.database = {'postgresVersion': '180006', 'migrations': []}

    def test_init_has_no_demo_and_preserves_private_bootstrap_secret(self):
        path = self.state / 'secrets/admin_password'
        before = path.read_bytes()
        self.assertFalse((self.state / 'secrets/demo_password').exists())
        self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.state.stat().st_mode & 0o777, 0o700)
        self.assertGreaterEqual(len(before), 32)
        self.assertEqual(self.values['COOKIE_SECURE'], 'true')
        self.assertEqual(self.values['CORE_MEMORY_LIMIT'], '512m')
        self.assertEqual(self.values['REPAIR_MEMORY_LIMIT'], '448m')
        subprocess.run([sys.executable, str(ROOT / 'scripts/init-env'), 'prod',
                        '--directory', str(self.state)], check=True, stdout=subprocess.DEVNULL)
        self.assertEqual(path.read_bytes(), before)

    def test_insecure_production_configuration_rejected_before_docker(self):
        for update in ({'PUBLIC_BASE_URL': ''}, {'PUBLIC_BASE_URL': 'http://49.51.193.169'},
                       {'PUBLIC_BASE_URL': 'https://user:password@example.com'},
                       {'PUBLIC_BASE_URL': 'https://example.com/path'},
                       {'PUBLIC_BASE_URL': 'https://example.com?secret=x'},
                       {'PUBLIC_BASE_URL': 'https://example.com:invalid'},
                       {'BIND_ADDRESS': '0.0.0.0'}, {'COOKIE_SECURE': 'false'},
                       {'MAIL_MODE': 'capture'}):
            with self.subTest(update=update), patch.object(ops, 'config', return_value=(self.state, {**self.values, **update})), patch.object(ops, 'verify_manifest') as verify:
                with self.assertRaises(RuntimeError):
                    ops.deploy('prod', self.state / 'manifest.json')
                verify.assert_not_called()
        (self.state / 'secrets/demo_password').write_text('test-only')
        with self.assertRaisesRegex(RuntimeError, 'demo password'):
            ops.production_config(self.state, self.values)

    def test_bootstrap_requires_identity_and_private_unique_secret(self):
        path = self.state / 'secrets/admin_password'
        with self.assertRaisesRegex(RuntimeError, 'ADMIN_USERNAME and ADMIN_EMAIL'):
            ops.production_bootstrap(self.state, {**self.values, 'ADMIN_EMAIL': ''})
        path.chmod(0o644)
        with self.assertRaisesRegex(RuntimeError, 'owner-only'):
            ops.production_bootstrap(self.state, self.values)
        path.chmod(0o600)
        for value in ('', 'DemoPassword123!', 'ChangeMe123456!', 'admin12345678', 'a' * 65):
            path.write_text(value)
            with self.subTest(length=len(value)), self.assertRaisesRegex(RuntimeError, 'password must be unique'):
                ops.production_bootstrap(self.state, self.values)

    def test_bootstrap_mounts_password_without_exposing_it_in_arguments_or_environment(self):
        password = (self.state / 'secrets/admin_password').read_text()
        with patch.object(ops, 'config', return_value=(self.state, self.values)), patch.object(ops, 'compose') as compose:
            ops.migrate('prod', action='bootstrap')
        call = compose.call_args
        self.assertIn('--volume', call.args)
        self.assertIn(str(self.state / 'secrets/admin_password') + ':/run/secrets/admin_password:ro', call.args)
        self.assertNotIn(password, repr(call))
        self.assertNotIn('ADMIN_PASSWORD', call.kwargs['overrides'])
        self.assertEqual(call.kwargs['overrides']['ADMIN_USERNAME'], 'owner')

    def mock_deploy(self, stack, *, demos=0):
        def compose(environment, *args, **kwargs):
            if args[0] == 'ps':
                return SimpleNamespace(stdout='postgres\nmailpit\n')
            if 'SELECT count(*) FROM core.app_user' in str(args):
                return SimpleNamespace(stdout=str(demos) + '\n')
            return SimpleNamespace(stdout='')
        mocks = {}
        for name, options in {
            'config': {'return_value': (self.state, self.values)},
            'verify_manifest': {'return_value': self.manifest},
            'compose': {'side_effect': compose},
            'database_inventory': {'return_value': self.database},
            'wait_drained': {}, 'wait_status': {}, 'backup': {}, 'migrate': {},
            'verify_running_release': {}, 'smoke': {},
        }.items():
            mocks[name] = stack.enter_context(patch.object(ops, name, **options))
        return mocks

    def test_first_deployment_seeds_only_reference_and_unique_administrator(self):
        with ExitStack() as stack:
            mocks = self.mock_deploy(stack)
            ops.deploy('prod', self.state / 'manifest.json')
            actions = [call.kwargs.get('action', 'migrate') for call in mocks['migrate'].call_args_list]
            self.assertEqual(actions, ['migrate', 'reference', 'bootstrap'])
            mocks['verify_running_release'].assert_called_once_with('prod', self.manifest)
        self.assertEqual(json.loads((self.state / 'current-release.json').read_text()), self.manifest)
        self.assertFalse((self.state / 'pending-release.json').exists())

    def test_upgrade_does_not_require_bootstrap_credentials_or_reset_password(self):
        ops.write_json(self.state / 'current-release.json', self.manifest)
        ops.record_schema(self.state, self.manifest, self.database)
        (self.state / 'secrets/admin_password').unlink()
        self.values.pop('ADMIN_USERNAME')
        self.values.pop('ADMIN_EMAIL')
        with ExitStack() as stack:
            mocks = self.mock_deploy(stack)
            ops.deploy('prod', self.state / 'manifest.json')
            self.assertEqual([c.kwargs.get('action', 'migrate') for c in mocks['migrate'].call_args_list],
                             ['migrate', 'reference'])

    def test_demo_database_is_rejected_without_starting_application(self):
        with ExitStack() as stack:
            mocks = self.mock_deploy(stack, demos=11)
            with self.assertRaisesRegex(RuntimeError, 'demo accounts'):
                ops.deploy('prod', self.state / 'manifest.json')
            mocks['verify_running_release'].assert_not_called()
            self.assertEqual([c.kwargs.get('action', 'migrate') for c in mocks['migrate'].call_args_list],
                             ['migrate', 'reference'])
        self.assertTrue((self.state / 'control/maintenance').exists())
        self.assertTrue((self.state / 'control/jobs-paused').exists())
        self.assertFalse((self.state / 'current-release.json').exists())

    def test_native_migrations_cannot_bypass_release_container(self):
        with patch.object(ops, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'immutable release container'):
                ops.migrate('prod', native=True)
            run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
