"""Bootstrap trust boundary and create-once runtime state regression tests."""
import base64
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]


def module(name, path):
    loader = importlib.machinery.SourceFileLoader(name, str(path))
    spec = importlib.util.spec_from_loader(name, loader)
    value = importlib.util.module_from_spec(spec)
    loader.exec_module(value)
    return value


bootstrap = module('bootstrap_under_test', ROOT / 'scripts/bootstrap')
state_helper = module('bootstrap_state_under_test', ROOT / 'infra/ansible/files/setup-state.py')


class BootstrapInventoryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        identity = self.folder / 'id_ed25519'
        identity.write_text('test key placeholder')
        identity.chmod(0o600)
        wire = len(b'ssh-ed25519').to_bytes(4, 'big') + b'ssh-ed25519' + (32).to_bytes(4, 'big') + b'0' * 32
        self.data = json.loads((ROOT / 'infra/ansible/inventory.example.json').read_text())
        self.data.update(ssh_private_key_file=str(identity), ssh_host_key='ssh-ed25519 ' + base64.b64encode(wire).decode())

    def test_inventory_validated_without_contact_or_install(self):
        file = self.folder / 'inventory.json'
        file.write_text(json.dumps(self.data))
        with mock.patch.object(bootstrap, 'controller') as controller:
            bootstrap.main(['test', str(file), '--validate'])
        controller.assert_not_called()

    def test_prod_and_unknown_keys_rejected_before_connect(self):
        for environment, values in [('prod', self.data), ('test', {**self.data, 'ansible_ssh_common_args': '-o StrictHostKeyChecking=no'})]:
            with self.subTest(environment=environment, values=values), self.assertRaises(ValueError):
                bootstrap.validate_inventory(environment, values)

    def test_invalid_paths_identity_scope_and_ports(self):
        cases = [('environment', 'dev'), ('sudo', False), ('state_directory', '/srv/x/../other'),
                 ('state_directory', '/etc'), ('release_directory', self.data['state_directory']),
                 ('backup_directory', '/srv/outside/backups'), ('deployment_user', 'root'),
                 ('deployment_user', 7), ('project_name', 'other'), ('project_name', 'studyroom-dev'), ('bind_address', '0.0.0.0'),
                 ('web_port', True), ('web_port', 80), ('mail_ui_port', self.data['web_port']),
                 ('public_base_url', 'https://example.test'), ('host', '-oProxyCommand=x'), ('host', 123),
                 ('ssh_port', 0), ('ssh_user', 'root;id'), ('ssh_private_key_file', '/not/existing'),
                 ('ssh_host_key', ''), ('ssh_host_key', 'ssh-ed25519 ZmFrZQ==')]
        for name, value in cases:
            with self.subTest(name=name, value=value), self.assertRaises(ValueError):
                bootstrap.validate_inventory('test', {**self.data, name: value})

    def test_private_key_permissions_required(self):
        Path(self.data['ssh_private_key_file']).chmod(0o644)
        with self.assertRaisesRegex(ValueError, 'permissions'):
            bootstrap.validate_inventory('test', self.data)

    def test_strict_host_key_pinning_survives_environment_overrides(self):
        data = bootstrap.validate_inventory('test', self.data)
        with mock.patch.dict(os.environ, {'ANSIBLE_HOST_KEY_CHECKING': 'False', 'ANSIBLE_SSH_ARGS': '-o StrictHostKeyChecking=no'}):
            command, environment = bootstrap.invocation('test', data, self.folder)
        self.assertEqual(environment['ANSIBLE_HOST_KEY_CHECKING'], 'True')
        self.assertIn('StrictHostKeyChecking=yes', environment['ANSIBLE_SSH_ARGS'])
        self.assertIn('GlobalKnownHostsFile=/dev/null', environment['ANSIBLE_SSH_ARGS'])
        self.assertNotIn('StrictHostKeyChecking=no', environment['ANSIBLE_SSH_ARGS'])
        self.assertEqual((self.folder / 'known_hosts').read_text(), 'studyroom-bootstrap-target ' + self.data['ssh_host_key'] + '\n')
        self.assertEqual((self.folder / 'known_hosts').stat().st_mode & 0o777, 0o600)
        inventory = json.loads((self.folder / 'inventory.json').read_text())
        self.assertEqual(inventory['all']['children']['studyroom']['hosts']['runtime']['ansible_become'], True)
        self.assertIn(str(ROOT / 'infra/ansible/bootstrap.yml'), command)

    def test_container_validation_refuses_remote_connection(self):
        file = self.folder / 'inventory.json'
        file.write_text(json.dumps(self.data))
        with mock.patch.object(bootstrap, 'controller') as controller, self.assertRaises(SystemExit):
            bootstrap.main(['test', str(file), '--container-validation', '--validate'])
        controller.assert_not_called()

    def test_local_inventory_still_requires_explicit_sudo_and_loopback(self):
        data = {**self.data, 'connection': 'local', 'host': 'localhost'}
        self.assertEqual(bootstrap.validate_inventory('test', data)['connection'], 'local')
        with self.assertRaises(ValueError):
            bootstrap.validate_inventory('test', {**data, 'host': 'other-server'})


class BootstrapStateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.state = Path(self.temp.name) / 'state'
        self.state.mkdir(mode=0o700)
        self.settings = json.loads((ROOT / 'infra/ansible/inventory.example.json').read_text())
        self.settings['state_directory'] = str(self.state)
        self.initializer = str(ROOT / 'scripts/init-env')

    def configure(self):
        return state_helper.configure(self.settings, self.initializer)

    def snapshot(self):
        return {name: ((self.state / 'secrets' / name).read_bytes(), (self.state / 'secrets' / name).stat().st_mtime_ns) for name in state_helper.NAMES}

    def test_create_and_second_run_preserve_all_secret_bytes_and_timestamps(self):
        self.assertTrue(self.configure()['changed'])
        before = self.snapshot()
        config = (self.state / 'compose.env').read_bytes()
        self.assertFalse(self.configure()['changed'])
        self.assertEqual(self.snapshot(), before)
        self.assertEqual((self.state / 'compose.env').read_bytes(), config)
        self.assertTrue(json.loads((self.state / 'bootstrap-state.json').read_text())['secretsValidated'])

    def test_configuration_drift_fails_without_replacing_existing_secrets(self):
        self.configure()
        before = self.snapshot()
        self.settings['project_name'] = 'studyroom-another'
        with self.assertRaisesRegex(RuntimeError, 'not overwritten'):
            self.configure()
        self.assertEqual(self.snapshot(), before)

    def test_bad_secret_permissions_and_key_length_are_rejected(self):
        self.configure()
        key = self.state / 'secrets/checkin_key'
        key.chmod(0o644)
        with self.assertRaisesRegex(RuntimeError, 'private'):
            self.configure()
        key.chmod(0o600)
        key.write_text(base64.b64encode(b'short').decode())
        with self.assertRaisesRegex(RuntimeError, '32 bytes'):
            self.configure()

    def test_symlink_and_duplicate_config_are_rejected(self):
        self.configure()
        secret = self.state / 'secrets/demo_password'
        secret.unlink()
        secret.symlink_to(self.state / 'secrets/internal_password')
        with self.assertRaisesRegex(RuntimeError, 'symlinks'):
            self.configure()
        secret.unlink()
        secret.write_text('valid-but-not-real-password')
        secret.chmod(0o600)
        with (self.state / 'compose.env').open('a') as stream:
            stream.write('ENV_NAME=test\n')
        with self.assertRaisesRegex(RuntimeError, 'Duplicate'):
            self.configure()

    def test_missing_existing_secret_is_not_silently_regenerated(self):
        self.configure()
        secret = self.state / 'secrets/core_db_password'
        secret.unlink()
        with self.assertRaisesRegex(RuntimeError, 'restore them from backup'):
            self.configure()
        self.assertFalse(secret.exists())

    def test_secrets_without_configuration_are_not_repurposed(self):
        self.configure()
        before = self.snapshot()
        (self.state / 'compose.env').unlink()
        with self.assertRaisesRegex(RuntimeError, 'restore the matching configuration'):
            self.configure()
        self.assertEqual(self.snapshot(), before)

    def test_existing_world_readable_config_is_rejected(self):
        self.configure()
        (self.state / 'compose.env').chmod(0o644)
        with self.assertRaisesRegex(RuntimeError, 'private'):
            self.configure()


if __name__ == '__main__':
    unittest.main()
