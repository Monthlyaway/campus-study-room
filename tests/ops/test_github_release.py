"""Boundary tests for trusted Actions identity and immutable release promotion."""
import importlib.util
import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


release = load('tested_github_release', 'github-release.py')
ci = load('tested_ci_release', 'ci-release.py')


class GitHubReleaseTest(unittest.TestCase):
    def setUp(self):
        self.commit = 'a' * 40
        self.env = {'CI': 'true', 'GITHUB_ACTIONS': 'true',
                    'GITHUB_REPOSITORY': release.REPOSITORY, 'GITHUB_SHA': self.commit,
                    'GITHUB_REF': 'refs/tags/v0.2.0', 'GITHUB_RUN_ID': '123',
                    'GITHUB_RUN_ATTEMPT': '1', 'GITHUB_WORKSPACE': str(ROOT),
                    'GITHUB_EVENT_NAME': 'push'}
        self.manifest = {'commit': self.commit, 'releaseId': 'v0.2.0'}
        self.evidence = {'result': 'passed', 'repository': release.REPOSITORY,
                         **self.manifest, 'manifestSha256': 'b' * 64,
                         'runId': '123', 'runAttempt': '1', 'checks': list(release.CHECKS)}

    def test_complete_same_run_evidence_promotes_without_rewriting_version(self):
        release.validate_evidence(self.manifest, self.evidence, 'b' * 64, self.env)
        self.assertEqual(('123', '1', self.commit, 'v0.2.0'), ci.actions_identity(self.env))

    def test_old_attempt_different_manifest_missing_browser_and_skipped_results_rejected(self):
        for change in ({'runAttempt': '2'}, {'manifestSha256': 'c' * 64},
                       {'checks': list(release.CHECKS - {'real-browser-workflows'})},
                       {'result': 'skipped'}, {'repository': 'someone/fork'},
                       {'commit': 'd' * 40}, {'releaseId': 'ci-123'}):
            with self.subTest(change=change), self.assertRaises(RuntimeError):
                release.validate_evidence(self.manifest, {**self.evidence, **change}, 'b' * 64, self.env)

    def test_deployment_binds_original_test_manifest_and_all_three_image_ids(self):
        tested = {**self.manifest, 'images': {name: {'id': 'sha256:' + 'e' * 64}
                                            for name in ('core', 'repair', 'web')}}
        metadata = {'runId': '123', 'runAttempt': '1',
                    'workflow': 'https://github.com/' + release.REPOSITORY + '/actions/runs/123'}
        published = {**tested, 'testedManifestSha256': 'b' * 64, 'githubRun': metadata['workflow']}
        release.validate_promotion(published, tested, self.evidence, 'b' * 64, metadata)
        for key, value in [('images', {**tested['images'], 'web': {'id': 'sha256:' + 'f' * 64}}),
                           ('migrations', {'new-unverified-migration': 'a' * 64}),
                           ('testedManifestSha256', 'a' * 64), ('githubRun', 'https://example.invalid/')]:
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                release.validate_promotion({**published, key: value}, tested, self.evidence, 'b' * 64, metadata)
        with self.assertRaises(RuntimeError):
            release.validate_promotion(published, tested, {**self.evidence, 'result': 'failed'}, 'b' * 64, metadata)

    def test_branch_job_and_mismatched_tag_cannot_publish(self):
        for change in ({'GITHUB_REF': 'refs/heads/main'}, {'GITHUB_REF': 'refs/tags/v0.3.0'},
                       {'GITHUB_SHA': 'd' * 40}, {'GITHUB_ACTIONS': 'false'},
                       {'GITHUB_REPOSITORY': 'someone/fork'}):
            with self.subTest(change=change), self.assertRaises(RuntimeError):
                release.validate_evidence(self.manifest, self.evidence, 'b' * 64, {**self.env, **change})

    def test_fork_or_unrelated_checkout_cannot_use_runner(self):
        for change in ({'GITHUB_REPOSITORY': 'someone/fork'}, {'GITHUB_WORKSPACE': '/'},
                       {'GITHUB_EVENT_NAME': 'pull_request_target'}, {'GITHUB_RUN_ID': '../12'}):
            with self.subTest(change=change), self.assertRaises(RuntimeError):
                ci.actions_identity({**self.env, **change})
        with tempfile.TemporaryDirectory() as tmp:
            event = Path(tmp) / 'event.json'
            event.write_text(json.dumps({'pull_request': {'head': {
                'repo': {'full_name': 'someone/fork'}, 'sha': self.commit}}}))
            with self.assertRaises(RuntimeError):
                ci.actions_identity({**self.env, 'GITHUB_EVENT_NAME': 'pull_request',
                                     'GITHUB_EVENT_PATH': str(event)})

    def test_pr_requires_same_repository_and_uses_actual_head(self):
        with tempfile.TemporaryDirectory() as tmp:
            event = Path(tmp) / 'event.json'
            event.write_text(json.dumps({'pull_request': {'head': {
                'repo': {'full_name': release.REPOSITORY}, 'sha': 'c' * 40}}}))
            identity = ci.actions_identity({**self.env, 'GITHUB_EVENT_NAME': 'pull_request',
                                           'GITHUB_REF': 'refs/pull/1/merge', 'GITHUB_EVENT_PATH': str(event)})
            self.assertEqual('c' * 40, identity[2])
            self.assertEqual('ci-123-1-' + 'c' * 12, identity[3])

    def package(self, name='core'):
        # Actual GHCR REST response shape: repository is not always returned.
        return {'name': 'campus-study-room-' + name, 'visibility': 'private',
                'package_type': 'container', 'owner': {'login': 'Monthlyaway', 'id': 102352706}}

    def image_manifest(self):
        return {**self.manifest, 'images': {
            name: {'id': 'sha256:' + str(index) * 64, 'ref': 'local-' + name + ':mutable'}
            for index, name in enumerate(('core', 'repair', 'web'), 1)}}

    def image_labels(self):
        return {'org.opencontainers.image.source': 'https://github.com/Monthlyaway/campus-study-room',
                'org.opencontainers.image.revision': self.commit,
                'org.opencontainers.image.version': 'v0.2.0'}

    def test_private_package_with_omitted_repository_metadata_allowed(self):
        for name in ('core', 'repair', 'web'):
            package = self.package(name)
            with self.subTest(name=name), patch.object(release, 'api', return_value=package):
                self.assertEqual(package, release.package_private(name))
        with patch.object(release, 'api', return_value=None) as request:
            self.assertIsNone(release.package_private('core', missing=True))
            request.assert_called_once_with(
                'users/Monthlyaway/packages/container/campus-study-room-core', missing=True)

    def test_package_requires_private_expected_owner_name_and_container_type(self):
        for field, bad_value in (('visibility', 'public'), ('visibility', 'internal'),
                                 ('name', 'campus-study-room-web'), ('package_type', 'npm'),
                                 ('owner', {'login': 'another-owner'}), ('owner', None)):
            for missing in (False, True):
                package = self.package()
                if missing:
                    package.pop(field)
                else:
                    package[field] = bad_value
                with self.subTest(field=field, value=bad_value, missing=missing), \
                        patch.object(release, 'api', return_value=package), self.assertRaises(RuntimeError):
                    release.package_private('core')

    def test_optional_repository_metadata_must_match_when_present(self):
        for repository in (None, {}, {'full_name': 'someone/another-repo'}, 'invalid'):
            package = {**self.package(), 'repository': repository}
            with self.subTest(repository=repository), patch.object(release, 'api', return_value=package):
                with self.assertRaises(RuntimeError):
                    release.package_private('core')
        package = {**self.package(), 'repository': {'full_name': release.REPOSITORY}}
        with patch.object(release, 'api', return_value=package):
            self.assertEqual(package, release.package_private('core'))

    def test_public_repository_identity_is_required(self):
        # Current GitHub REST metadata after the owner made this repository public.
        repository = {'private': False, 'visibility': 'public', 'full_name': release.REPOSITORY}
        with patch.object(release, 'api', return_value=repository) as request:
            release.public_repository()
            request.assert_called_once_with('repos/' + release.REPOSITORY)
        for change in ({'full_name': 'someone/campus-study-room'},
                       {'full_name': 'Monthlyaway/another-repo'},
                       {'private': True, 'visibility': 'private'},
                       {'private': True}, {'private': 0}, {'visibility': 'private'},
                       {'visibility': 'internal'}):
            with self.subTest(change=change), patch.object(release, 'api', return_value={**repository, **change}):
                with self.assertRaisesRegex(RuntimeError, 'expected public repository'):
                    release.public_repository()
        for field in repository:
            incomplete = {key: value for key, value in repository.items() if key != field}
            with self.subTest(missing=field), patch.object(release, 'api', return_value=incomplete):
                with self.assertRaisesRegex(RuntimeError, 'expected public repository'):
                    release.public_repository()
        for malformed in (None, [], 'public'):
            with self.subTest(malformed=malformed), patch.object(release, 'api', return_value=malformed):
                with self.assertRaisesRegex(RuntimeError, 'expected public repository'):
                    release.public_repository()

    def test_all_tested_image_ids_require_source_commit_and_version_labels(self):
        manifest = self.image_manifest()
        with patch.object(release.subprocess, 'check_output', return_value=json.dumps(self.image_labels())) as inspect:
            release.verify_image_labels(manifest)
        self.assertEqual([info['id'] for info in manifest['images'].values()],
                         [call.args[0][-1] for call in inspect.call_args_list])
        for call in inspect.call_args_list:
            self.assertEqual(['docker', 'image', 'inspect', '--format', '{{json .Config.Labels}}'], call.args[0][:-1])

    def test_any_tested_image_with_missing_or_wrong_source_commit_or_version_is_rejected(self):
        manifest = self.image_manifest()
        for name in manifest['images']:
            for field in self.image_labels():
                for missing in (True, False):
                    labels = self.image_labels()
                    if missing:
                        labels.pop(field)
                    else:
                        labels[field] = 'unverified-value'
                    def inspected(args, **kwargs):
                        return json.dumps(labels if args[-1] == manifest['images'][name]['id'] else self.image_labels())
                    with self.subTest(image=name, field=field, missing=missing), \
                            patch.object(release.subprocess, 'check_output', side_effect=inspected), \
                            self.assertRaises(RuntimeError):
                        release.verify_image_labels(manifest)
        with patch.object(release.subprocess, 'check_output', return_value='null'), self.assertRaises(RuntimeError):
            release.verify_image_labels(manifest)

    def publish_inputs(self, root):
        manifest = self.image_manifest()
        manifest_path, evidence_path = root / 'manifest.json', root / 'result.json'
        manifest_path.write_text(json.dumps(manifest))
        evidence_path.write_text(json.dumps({**self.evidence, 'manifestSha256': release.sha(manifest_path)}))
        return manifest, SimpleNamespace(manifest=manifest_path, evidence=evidence_path, output=root / 'published')

    def test_wrong_last_image_label_stops_publish_before_any_push(self):
        with tempfile.TemporaryDirectory() as tmp:
            manifest, args = self.publish_inputs(Path(tmp))
            def inspected(command, **kwargs):
                self.assertEqual(['docker', 'image', 'inspect'], command[:3])
                labels = self.image_labels()
                if command[-1] == manifest['images']['web']['id']:
                    labels.pop('org.opencontainers.image.source')
                return json.dumps(labels)
            with patch.object(release, 'ops_module', return_value=SimpleNamespace(verify_manifest=lambda path: manifest)), \
                    patch.dict(os.environ, self.env, clear=True), \
                    patch.object(release.subprocess, 'check_output', side_effect=inspected), \
                    patch.object(release, 'run') as mutate, patch.object(release, 'api') as request:
                with self.assertRaisesRegex(RuntimeError, 'labels do not match: web'):
                    release.publish(args)
                mutate.assert_not_called()
                request.assert_not_called()
            self.assertFalse(args.output.exists())

    def test_existing_version_tag_is_not_overwritten_when_repository_metadata_omitted(self):
        with tempfile.TemporaryDirectory() as tmp:
            manifest, args = self.publish_inputs(Path(tmp))
            def checked(command, **kwargs):
                if command[:3] == ['docker', 'image', 'inspect']:
                    return json.dumps(self.image_labels())
                if command == ['git', 'rev-parse', 'HEAD']:
                    return self.commit
                if command == ['git', 'status', '--porcelain']:
                    return ''
                if command[:4] == ['gh', 'api', '--paginate', '--slurp']:
                    return json.dumps([[{'metadata': {'container': {'tags': ['v0.2.0']}}}]])
                raise AssertionError('Unexpected command: ' + str(command))
            with patch.object(release, 'ops_module', return_value=SimpleNamespace(verify_manifest=lambda path: manifest)), \
                    patch.dict(os.environ, self.env, clear=True), \
                    patch.object(release.subprocess, 'check_output', side_effect=checked), \
                    patch.object(release, 'run') as mutate, patch.object(release, 'public_repository'), \
                    patch.object(release, 'api', side_effect=[None, self.package()]):
                with self.assertRaisesRegex(RuntimeError, 'Immutable package tag already exists: core:v0.2.0'):
                    release.publish(args)
                self.assertEqual([['git', 'fetch', 'origin', 'main'],
                                  ['git', 'merge-base', '--is-ancestor', self.commit, 'origin/main']],
                                 [call.args[0] for call in mutate.call_args_list])
            self.assertFalse(args.output.exists())

    def archive(self, path, name, kind=tarfile.REGTYPE):
        with tarfile.open(path, 'w:gz') as archive:
            member = tarfile.TarInfo(name)
            member.type, member.mode = kind, 0o755
            if kind == tarfile.REGTYPE:
                member.size = 10
                archive.addfile(member, io.BytesIO(b'#!/bin/sh\n'))
            else:
                member.linkname = '/etc/passwd'
                archive.addfile(member)

    def test_source_archive_rejects_traversal_absolute_and_links(self):
        for index, (name, kind) in enumerate((('../outside', tarfile.REGTYPE),
                                            ('/outside', tarfile.REGTYPE),
                                            ('link', tarfile.SYMTYPE), ('link', tarfile.LNKTYPE))):
            with self.subTest(name=name, kind=kind), tempfile.TemporaryDirectory() as tmp:
                archive = Path(tmp) / 'source.tar.gz'
                self.archive(archive, name, kind)
                with self.assertRaises(RuntimeError):
                    release.extract_source(archive, Path(tmp) / 'source')
                self.assertFalse((Path(tmp) / 'outside').exists())

    def test_source_archive_preserves_executable_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive, source = Path(tmp) / 'source.tar.gz', Path(tmp) / 'source'
            self.archive(archive, 'scripts/example')
            release.extract_source(archive, source)
            self.assertEqual(b'#!/bin/sh\n', (source / 'scripts/example').read_bytes())
            self.assertEqual(0o755, (source / 'scripts/example').stat().st_mode & 0o777)
            with self.assertRaises(FileExistsError):
                release.extract_source(archive, source)

    def test_cached_source_rejects_content_mode_type_and_missing_file_drift(self):
        for change in ('content', 'mode', 'type', 'missing', 'symlink'):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as tmp:
                archive, source = Path(tmp) / 'source.tar.gz', Path(tmp) / 'source'
                self.archive(archive, 'scripts/example')
                release.extract_source(archive, source)
                release.verify_source(archive, source)
                target = source / 'scripts/example'
                if change == 'content':
                    target.write_bytes(b'#!/bin/xx\n')
                elif change == 'mode':
                    target.chmod(0o644)
                else:
                    target.unlink()
                    if change == 'type':
                        target.mkdir()
                    elif change == 'symlink':
                        target.symlink_to(archive)
                with self.assertRaises(RuntimeError):
                    release.verify_source(archive, source)

    def test_cached_source_rejects_parent_and_root_symlinks(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive, source = Path(tmp) / 'source.tar.gz', Path(tmp) / 'source'
            self.archive(archive, 'scripts/example')
            release.extract_source(archive, source)
            root_link = Path(tmp) / 'link'
            root_link.symlink_to(source, target_is_directory=True)
            with self.assertRaises(RuntimeError):
                release.verify_source(archive, root_link)
            (source / 'scripts').rename(source / 'real-scripts')
            (source / 'scripts').symlink_to(source / 'real-scripts', target_is_directory=True)
            with self.assertRaises(RuntimeError):
                release.verify_source(archive, source)

    def test_cache_extras_cannot_replace_verified_deployment_module(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            scripts = root / 'scripts'
            scripts.mkdir()
            module = scripts / 'studyroom.py'
            module.write_text('MARKER = "verified-source"\n')
            (scripts / '__pycache__').mkdir()
            (scripts / '__pycache__/unrelated.pyc').write_bytes(b'not executable source')
            # Even if an import loader would choose cached bytecode, it is not used.
            with patch('importlib.machinery.SourceFileLoader.exec_module', side_effect=AssertionError('used bytecode loader')):
                loaded = release.ops_module(root)
            self.assertEqual('verified-source', loaded.MARKER)

    def test_registry_credentials_use_stdin_and_ephemeral_config(self):
        with patch.dict(os.environ, {'GH_TOKEN': 'private-test-token'}, clear=True), patch.object(release, 'run') as command:
            with release.registry_login() as env:
                config = Path(env['DOCKER_CONFIG'])
                self.assertTrue(config.exists())
                self.assertEqual(0o700, config.stat().st_mode & 0o777)
                self.assertNotIn('private-test-token', str(command.call_args.args))
                self.assertEqual('private-test-token', command.call_args.kwargs['input'])
            self.assertFalse(config.exists())


if __name__ == '__main__':
    unittest.main()
