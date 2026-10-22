"""Reject release metadata drift before performance traffic, without Docker or HTTP."""
from contextlib import ExitStack
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('performance_identity_environment', ROOT / 'tests/performance/environment.py')
environment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(environment)
spec = importlib.util.spec_from_file_location('performance_identity_run', ROOT / 'tests/performance/run.py')
performance_run = importlib.util.module_from_spec(spec)
with patch.dict(sys.modules, {'environment': environment}):
    spec.loader.exec_module(performance_run)


class PerformanceIdentityTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.state = self.directory / 'state'
        self.output = self.directory / 'output'
        self.state.mkdir()
        self.output.mkdir()
        (self.state / 'compose.env').write_text('ENV_NAME=test\n')
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(patch.object(environment, 'STATE', self.state))
        self.stack.enter_context(patch.object(environment, 'OUTPUT', self.output))
        self.stack.enter_context(patch.object(environment, 'BASE_URL', 'http://performance.invalid'))
        # Any unmocked process or network access is a test failure, never a runtime operation.
        self.stack.enter_context(patch.object(environment.subprocess, 'run', side_effect=AssertionError('Unexpected subprocess')))
        self.stack.enter_context(patch.object(environment.subprocess, 'check_output', side_effect=AssertionError('Unexpected Docker access')))
        self.stack.enter_context(patch.object(environment.urllib.request, 'urlopen', side_effect=AssertionError('Unexpected HTTP access')))
        self.images = {name: 'sha256:' + character * 64 for name, character in [('core', 'a'), ('repair', 'b'), ('web', 'c')]}
        self.manifest = {
            'releaseId': 'r-fixture', 'commit': '1' * 40,
            'images': {name: {'ref': 'studyroom-' + name + ':r-fixture', 'id': image} for name, image in self.images.items()},
        }

    def initialize(self, release=True):
        by_ref = {entry['ref']: entry['id'] for entry in self.manifest['images'].values()}
        by_ref.update({'studyroom-' + name + ':development': image for name, image in self.images.items()})

        def output(command, **kwargs):
            if command[:2] == ['docker', 'info']:
                return json.dumps({'Architecture': 'aarch64', 'NCPU': 4, 'MemTotal': 8 * 1024**3})
            if command == ['git', 'rev-parse', 'HEAD']:
                return '2' * 40 + '\n'
            raise AssertionError('Unexpected subprocess command')

        with patch.object(environment.ops, 'verify_manifest', return_value=self.manifest), \
             patch.object(environment.ops, 'image_id', side_effect=by_ref.__getitem__), \
             patch.object(environment.subprocess, 'check_output', side_effect=output):
            return environment.initialize('manifest.json' if release else None)

    def test_persistent_override_uses_manifest_despite_ambient_labels(self):
        with patch.dict(environment.os.environ, {'RELEASE_ID': 'unrelated', 'RELEASE_COMMIT': '3' * 40, 'GIT_COMMIT': '4' * 40}):
            self.initialize()
        # Read it again after initialization: later Compose repair restarts use this file.
        services = json.loads((self.output / 'compose.override.json').read_text())['services']
        expected = {'RELEASE_ID': 'r-fixture', 'RELEASE_COMMIT': '1' * 40, 'GIT_COMMIT': '1' * 40}
        for service in ('core', 'repair'):
            self.assertEqual(services[service]['environment'], expected)
            self.assertEqual(services[service]['image'], self.images[service])
            self.assertEqual(services[service]['pull_policy'], 'never')

    def capture(self, versions, release=True, broken_service=None):
        metadata = {'images': self.images, 'releaseId': 'r-fixture' if release else None,
                    'releaseCommit': '1' * 40 if release else None}
        (self.output / 'environment.json').write_text(json.dumps(metadata))
        containers = []
        for name, memory in [('postgres', 3), ('core', 2), ('repair', 2), ('web', .5), ('mailpit', .5)]:
            containers.append({'Config': {'Labels': {'com.docker.compose.service': name}},
                               'Image': self.images.get(name, 'sha256:' + 'd' * 64),
                               'HostConfig': {'CpusetCpus': '0-3', 'Memory': int(memory * 1024**3),
                                              'MemorySwap': int(memory * 1024**3)}})
        seen = []
        paths = {'/version.json': 'web', '/api/v1/version': 'core', '/api/v1/repairs/version': 'repair'}

        def response(url, timeout):
            self.assertEqual(timeout, 10)
            service = paths[url.removeprefix('http://performance.invalid')]
            seen.append(service)
            if service == broken_service:
                raise OSError('fixture transport failure')
            value = versions[service]
            return io.BytesIO(json.dumps(value if service == 'web' else {'data': value}).encode())

        with patch.object(environment, 'compose', return_value=SimpleNamespace(stdout='five-container-ids')), \
             patch.object(environment.subprocess, 'check_output', return_value=json.dumps(containers)), \
             patch.object(environment.urllib.request, 'urlopen', side_effect=response):
            result = environment.capture_actual_limits()
        return result, seen

    def release_versions(self):
        return {service: {'releaseId': 'r-fixture', 'commit': '1' * 40} for service in self.images}

    def test_matching_image_ids_do_not_hide_any_endpoint_mismatch(self):
        for service in self.images:
            for field, changed in [('releaseId', 'development'), ('commit', 'working-tree')]:
                with self.subTest(service=service, field=field):
                    versions = self.release_versions()
                    versions[service][field] = changed
                    with self.assertRaisesRegex(RuntimeError, 'Performance release version mismatch: ' + service):
                        self.capture(versions)
                    recorded = json.loads((self.output / 'environment.json').read_text())
                    self.assertEqual(recorded['releaseVersionCheck'], 'failed')
                    self.assertEqual(recorded['observedVersions'], versions)

    def test_matching_release_records_all_three_observed_versions(self):
        versions = self.release_versions()
        limits, seen = self.capture(versions)
        self.assertEqual(len(limits), 5)
        self.assertEqual(set(seen), {'core', 'repair', 'web'})
        recorded = json.loads((self.output / 'environment.json').read_text())
        self.assertEqual(recorded['releaseVersionCheck'], 'passed')
        self.assertEqual(recorded['observedVersions'], versions)

    def test_development_records_baked_web_identity_without_requiring_release_match(self):
        with patch.dict(environment.os.environ, {'RELEASE_ID': 'unrelated', 'RELEASE_COMMIT': '3' * 40}):
            self.initialize(release=False)
        services = json.loads((self.output / 'compose.override.json').read_text())['services']
        self.assertEqual(services['repair']['environment']['GIT_COMMIT'], 'working-tree')
        versions = self.release_versions()
        versions['core'] = versions['repair'] = {'releaseId': 'development', 'commit': 'working-tree'}
        self.capture(versions, release=False)
        recorded = json.loads((self.output / 'environment.json').read_text())
        self.assertEqual(recorded['observedVersions']['web']['releaseId'], 'r-fixture')
        self.assertEqual(recorded['releaseVersionCheck'], 'not-applicable')

    def test_unavailable_version_endpoint_fails_closed(self):
        with self.assertRaisesRegex(RuntimeError, 'Performance version probe failed: repair'):
            self.capture(self.release_versions(), broken_service='repair')

    def test_history_minimum_accepts_accumulated_rows_without_changing_counts(self):
        for total in (99999, 100000, 100002):
            with self.subTest(historicalReservations=total):
                counts = {'accounts': 1000, 'rooms': 20, 'seats': 1000, 'historicalReservations': total}
                original = counts.copy()
                if total < 100000:
                    with self.assertRaisesRegex(RuntimeError, 'at least 100000'):
                        performance_run.validate_data_scale(counts)
                else:
                    performance_run.validate_data_scale(counts)
                self.assertEqual(counts, original)
        for key, invalid in [('accounts', 999), ('accounts', 1001), ('rooms', 19), ('rooms', 21), ('seats', 999), ('seats', 1001)]:
            with self.subTest(key=key, invalid=invalid):
                counts = {'accounts': 1000, 'rooms': 20, 'seats': 1000, 'historicalReservations': 100002}
                counts[key] = invalid
                with self.assertRaises(RuntimeError):
                    performance_run.validate_data_scale(counts)


if __name__ == '__main__':
    unittest.main()
