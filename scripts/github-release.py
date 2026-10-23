#!/usr/bin/env python3
"""Publish tested images to private GHCR and deploy the same immutable release."""
import argparse
import contextlib
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parent.parent
REPOSITORY = 'Monthlyaway/campus-study-room'
REGISTRY = 'ghcr.io/monthlyaway/campus-study-room-'
OWNER = REPOSITORY.split('/', 1)[0]
SOURCE_URL = 'https://github.com/' + REPOSITORY
OCI_SOURCE = 'org.opencontainers.image.source'
OCI_REVISION = 'org.opencontainers.image.revision'
OCI_VERSION = 'org.opencontainers.image.version'
RELEASES = Path('/home/ubuntu/Deployments/campus-study-room/releases')
PRODUCTION_STATE = Path('/home/ubuntu/.local/state/campus-study-room/prod')
CHECKS = {'three-images-built', 'fresh-test-deployment', 'private-endpoint-smoke',
          'real-browser-workflows', 'running-version-identity'}


def run(args, **kwargs):
    return subprocess.run(list(map(str, args)), check=True, text=True, **kwargs)


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2) + '\n')


def ops_module(root=ROOT):
    spec = importlib.util.spec_from_file_location('github_release_ops', root / 'scripts/studyroom.py')
    module = importlib.util.module_from_spec(spec)
    # Execute the verified source, never a potentially stale or replaced .pyc.
    source = root / 'scripts/studyroom.py'
    exec(compile(source.read_bytes(), str(source), 'exec'), module.__dict__)
    return module


def api(path, *, missing=False):
    result = subprocess.run(['gh', 'api', path], capture_output=True, text=True)
    if result.returncode:
        if missing and '(HTTP 404)' in result.stderr:
            return None
        raise RuntimeError('GitHub API request failed: ' + path + '\n' + result.stderr)
    return json.loads(result.stdout)


def public_repository():
    repo = api('repos/' + REPOSITORY)
    if (not isinstance(repo, dict) or repo.get('full_name') != REPOSITORY
            or repo.get('private') is not False or repo.get('visibility') != 'public'):
        raise RuntimeError('Release target must remain the expected public repository')


def package_private(name, *, missing=False):
    package = api('users/Monthlyaway/packages/container/campus-study-room-' + name, missing=missing)
    if package is None:
        return None
    if (not isinstance(package, dict) or package.get('visibility') != 'private'
            or package.get('name') != 'campus-study-room-' + name
            or package.get('package_type') != 'container'
            or not isinstance(package.get('owner'), dict)
            or package['owner'].get('login') != OWNER):
        raise RuntimeError('Package must have the expected private container identity: ' + name)
    # GHCR can omit repository from its REST response. Bind source identity to
    # the tested immutable images separately; reject a conflicting link if sent.
    if 'repository' in package and (not isinstance(package['repository'], dict)
            or package['repository'].get('full_name') != REPOSITORY):
        raise RuntimeError('Package repository association does not match: ' + name)
    return package


def verify_image_labels(manifest):
    expected = {OCI_SOURCE: SOURCE_URL, OCI_REVISION: manifest['commit'],
                OCI_VERSION: manifest['releaseId']}
    for name, info in manifest['images'].items():
        # Inspect the tested ID, never a mutable local or registry tag.
        labels = json.loads(subprocess.check_output(['docker', 'image', 'inspect', '--format',
                            '{{json .Config.Labels}}', info['id']], text=True))
        if not isinstance(labels, dict) or any(labels.get(key) != value for key, value in expected.items()):
            raise RuntimeError('Tested image source, revision or version labels do not match: ' + name)


@contextlib.contextmanager
def registry_login():
    token = os.environ.get('GH_TOKEN') or os.environ.get('GITHUB_TOKEN')
    if not token:
        raise RuntimeError('Supply the workflow token as GH_TOKEN (never as a command argument)')
    # Credentials are temporary, isolated from the remote user's Docker config.
    with tempfile.TemporaryDirectory(prefix='studyroom-ghcr-') as directory:
        env = {**os.environ, 'DOCKER_CONFIG': directory}
        run(['docker', 'login', 'ghcr.io', '--username', 'Monthlyaway', '--password-stdin'],
            input=token, env=env, stdout=subprocess.DEVNULL)
        yield env


def validate_test_evidence(manifest, evidence, manifest_sha, run_id, run_attempt):
    if (evidence.get('result') != 'passed' or evidence.get('repository') != REPOSITORY
            or evidence.get('commit') != manifest['commit']
            or evidence.get('releaseId') != manifest['releaseId']
            or evidence.get('manifestSha256') != manifest_sha
            or set(evidence.get('checks', [])) != CHECKS
            or evidence.get('runId') != run_id or evidence.get('runAttempt') != run_attempt):
        raise RuntimeError('Release requires complete matching container/browser evidence from this run')


def validate_evidence(manifest, evidence, manifest_sha, env):
    validate_test_evidence(manifest, evidence, manifest_sha, env.get('GITHUB_RUN_ID'), env.get('GITHUB_RUN_ATTEMPT'))
    if (env.get('CI') != 'true' or env.get('GITHUB_ACTIONS') != 'true'
            or env.get('GITHUB_REPOSITORY') != REPOSITORY
            or env.get('GITHUB_SHA') != manifest['commit']
            or env.get('GITHUB_REF') != 'refs/tags/' + manifest['releaseId']
            or not re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?', manifest['releaseId'])):
        raise RuntimeError('Publishing requires a matching version-tag GitHub Actions run')


def validate_promotion(published, tested, evidence, tested_sha, metadata):
    validate_test_evidence(tested, evidence, tested_sha, metadata.get('runId'), metadata.get('runAttempt'))
    if published.get('testedManifestSha256') != tested_sha:
        raise RuntimeError('Published manifest does not bind the tested manifest')
    for key in ('releaseId', 'commit', 'composeSha256', 'dependencyBaselineSha256',
                'migrations', 'referenceSeed', 'rollbackCompatibility'):
        if published.get(key) != tested.get(key):
            raise RuntimeError('Publication changed tested release identity: ' + key)
    if any(published['images'][name]['id'] != tested['images'][name]['id'] for name in ('core', 'repair', 'web')):
        raise RuntimeError('Published images differ from the tested images')
    run_url = f'https://github.com/{REPOSITORY}/actions/runs/{metadata.get("runId")}'
    if published.get('githubRun') != run_url or metadata.get('workflow') != run_url:
        raise RuntimeError('Release workflow identity mismatch')


def publish(args):
    ops = ops_module()
    manifest = ops.verify_manifest(args.manifest)
    evidence = json.loads(args.evidence.read_text())
    validate_evidence(manifest, evidence, sha(args.manifest), os.environ)
    verify_image_labels(manifest)
    actual = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    if actual != manifest['commit'] or subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip():
        raise RuntimeError('Publishing requires the clean tested checkout')
    run(['git', 'fetch', 'origin', 'main'], cwd=ROOT)
    run(['git', 'merge-base', '--is-ancestor', manifest['commit'], 'origin/main'], cwd=ROOT)
    tag = manifest['releaseId']
    public_repository()
    if api('repos/' + REPOSITORY + '/releases/tags/' + tag, missing=True) is not None:
        raise RuntimeError('Release already exists; never replace a published release automatically')
    for name in manifest['images']:
        package = package_private(name, missing=True)
        if package:
            versions = json.loads(subprocess.check_output(['gh', 'api', '--paginate', '--slurp',
                f'users/Monthlyaway/packages/container/campus-study-room-{name}/versions?per_page=100'], text=True))
            if any(tag in version.get('metadata', {}).get('container', {}).get('tags', [])
                   for page in versions for version in page):
                raise RuntimeError('Immutable package tag already exists: ' + name + ':' + tag)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    published = {**manifest, 'images': {}, 'transport': 'ghcr-digest',
                 'githubRun': f'https://github.com/{REPOSITORY}/actions/runs/{os.environ["GITHUB_RUN_ID"]}',
                 'testedManifestSha256': sha(args.manifest)}
    with registry_login() as docker_env:
        for name, info in manifest['images'].items():
            ref = REGISTRY + name + ':' + tag
            run(['docker', 'tag', info['id'], ref], env=docker_env)
            run(['docker', 'push', ref], env=docker_env)
            digests = json.loads(subprocess.check_output(['docker', 'image', 'inspect', '--format',
                                 '{{json .RepoDigests}}', ref], env=docker_env, text=True))
            candidates = [value for value in digests if re.fullmatch(re.escape(REGISTRY + name) + r'@sha256:[0-9a-f]{64}', value)]
            if len(candidates) != 1:
                raise RuntimeError('Registry did not provide one immutable digest: ' + name)
            run(['docker', 'pull', candidates[0]], env=docker_env)
            if ops.image_id(candidates[0]) != info['id']:
                raise RuntimeError('Published image differs from the tested image: ' + name)
            package_private(name)
            published['images'][name] = {'ref': candidates[0], 'id': info['id']}
        baseline = json.loads((ROOT / 'docs/dependency-baseline.json').read_text())
        published['runtimeImages'] = {name: {'ref': baseline['images'][name], 'id': ops.image_id(baseline['images'][name])}
                                      for name in ('postgres', 'mailpit')}
    write_json(output / 'manifest.json', published)
    ops.verify_manifest(output / 'manifest.json')
    shutil.copy2(args.evidence, output / 'container-test.json')
    shutil.copy2(args.manifest, output / 'tested-manifest.json')
    source = output / 'source.tar.gz'
    run(['git', 'archive', '--format=tar.gz', '--output', source, manifest['commit']], cwd=ROOT)
    metadata = {'formatVersion': 1, 'repository': REPOSITORY, 'releaseId': tag,
                'commit': manifest['commit'], 'workflow': published['githubRun'],
                'runId': os.environ['GITHUB_RUN_ID'], 'runAttempt': os.environ['GITHUB_RUN_ATTEMPT'],
                'manifestSha256': sha(output / 'manifest.json'), 'sourceSha256': sha(source),
                'verification': 'same tested image IDs pulled again through GHCR digest references'}
    write_json(output / 'build.json', metadata)
    assets = sorted(output.iterdir())
    (output / 'SHA256SUMS').write_text(''.join(f'{sha(path)}  {path.name}\n' for path in assets))
    notes = output / 'release-notes.md'
    notes.write_text(f'Production delivery of `{manifest["commit"]}`.\n\n'
                     f'The three private GHCR images were built and tested on codex-lb, then published without rebuilding. '
                     f'Use `manifest.json` for immutable image digests and `SHA256SUMS` for release assets.\n\n'
                     f'[CI and browser verification]({published["githubRun"]}).\n')
    run(['gh', 'release', 'create', tag, '--repo', REPOSITORY, '--verify-tag', '--draft',
         '--title', 'Campus Study Room ' + tag, '--notes-file', notes,
         *assets, output / 'SHA256SUMS'])
    run(['gh', 'release', 'edit', tag, '--repo', REPOSITORY, '--draft=false'])
    print('Published public release: https://github.com/' + REPOSITORY + '/releases/tag/' + tag)


def extract_source(archive, destination):
    destination.mkdir(parents=True, exist_ok=False)
    with tarfile.open(archive, 'r:gz') as source:
        for member in source.getmembers():
            name = Path(member.name)
            if name.is_absolute() or '..' in name.parts or not (member.isdir() or member.isfile()):
                raise RuntimeError('Unsafe release source archive member')
            target = destination / name
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
                target.chmod(member.mode & 0o777)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with source.extractfile(member) as stream, target.open('xb') as output:
                    shutil.copyfileobj(stream, output)
                target.chmod(member.mode & 0o777)


def verify_source(archive, destination):
    """Revalidate every archived path before reusing a mounted source directory.

    Generated caches outside the archive may remain. The deployment module is
    compiled from source bytes explicitly, so those caches cannot replace it.
    """
    if destination.is_symlink() or not destination.is_dir():
        raise RuntimeError('Release source must be a real directory')
    with tarfile.open(archive, 'r:gz') as source:
        for member in source.getmembers():
            name = Path(member.name)
            if name.is_absolute() or '..' in name.parts or not (member.isdir() or member.isfile()):
                raise RuntimeError('Unsafe release source archive member')
            target = destination
            for component in name.parts:
                target = target / component
                if target.is_symlink():
                    raise RuntimeError('Release source contains a symlink: ' + member.name)
            try:
                actual = target.lstat()
            except FileNotFoundError as error:
                raise RuntimeError('Release source is missing an archived path: ' + member.name) from error
            expected_type = stat.S_ISDIR if member.isdir() else stat.S_ISREG
            if not expected_type(actual.st_mode) or stat.S_IMODE(actual.st_mode) != member.mode & 0o777:
                raise RuntimeError('Release source type or mode changed: ' + member.name)
            if member.isfile():
                digest = hashlib.sha256()
                with source.extractfile(member) as stream:
                    for block in iter(lambda: stream.read(1024 * 1024), b''):
                        digest.update(block)
                if actual.st_size != member.size or sha(target) != digest.hexdigest():
                    raise RuntimeError('Release source contents changed: ' + member.name)


def deploy(args):
    public_repository()
    bundle = args.bundle.resolve()
    manifest = json.loads((bundle / 'manifest.json').read_text())
    ops_module().validate_manifest_fields(manifest)
    tag = manifest['releaseId']
    if not re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?', tag):
        raise RuntimeError('Production deployment requires a versioned release')
    sums = {}
    for line in (bundle / 'SHA256SUMS').read_text().splitlines():
        match = re.fullmatch(r'([0-9a-f]{64})  ([A-Za-z0-9._-]+)', line)
        if not match or match[2] in sums:
            raise RuntimeError('Invalid release checksums')
        sums[match[2]] = match[1]
    if not {'manifest.json', 'source.tar.gz', 'build.json', 'container-test.json', 'tested-manifest.json'} <= sums.keys():
        raise RuntimeError('Release bundle is incomplete')
    for name, expected in sums.items():
        if sha(bundle / name) != expected:
            raise RuntimeError('Release asset checksum mismatch: ' + name)
    metadata = json.loads((bundle / 'build.json').read_text())
    if (metadata.get('repository') != REPOSITORY or metadata.get('commit') != manifest['commit']
            or metadata.get('releaseId') != tag or metadata.get('manifestSha256') != sums['manifest.json']
            or metadata.get('sourceSha256') != sums['source.tar.gz']):
        raise RuntimeError('Release metadata identity mismatch')
    tested = json.loads((bundle / 'tested-manifest.json').read_text())
    ops_module().validate_manifest_fields(tested)
    validate_promotion(manifest, tested, json.loads((bundle / 'container-test.json').read_text()),
                       sums['tested-manifest.json'], metadata)
    for name, info in manifest['images'].items():
        if not re.fullmatch(re.escape(REGISTRY + name) + r'@sha256:[0-9a-f]{64}', info['ref']):
            raise RuntimeError('Production requires fixed GHCR digest references')
        package_private(name)
    release = api('repos/' + REPOSITORY + '/releases/tags/' + tag)
    if release.get('draft') or release.get('prerelease'):
        raise RuntimeError('Production requires a published stable release')
    if api('repos/' + REPOSITORY + '/commits/' + tag).get('sha') != manifest['commit']:
        raise RuntimeError('Release tag does not identify the manifest commit')
    # Bind the local bundle to GitHub's uploaded asset digests; adjacent local
    # checksum files alone do not establish which release produced the bundle.
    assets = {asset['name']: asset.get('digest') for asset in release.get('assets', [])}
    for name, expected in {**sums, 'SHA256SUMS': sha(bundle / 'SHA256SUMS')}.items():
        if assets.get(name) != 'sha256:' + expected:
            raise RuntimeError('Bundle differs from the published GitHub release asset: ' + name)
    destination = RELEASES / tag
    source = destination / 'source'
    destination.mkdir(parents=True, exist_ok=True, mode=0o700)
    if source.exists():
        # Retrying a failed deploy must not overwrite files mounted by live services.
        marker = destination / 'source.sha256'
        if not marker.exists() or marker.read_text().strip() != sums['source.tar.gz']:
            raise RuntimeError('Existing deployment source does not match this release')
    else:
        extract_source(bundle / 'source.tar.gz', source)
        (destination / 'source.sha256').write_text(sums['source.tar.gz'] + '\n')
    verify_source(bundle / 'source.tar.gz', source)
    deployed_manifest = destination / 'manifest.json'
    if deployed_manifest.exists() and sha(deployed_manifest) != sums['manifest.json']:
        raise RuntimeError('Refusing to overwrite an existing release manifest')
    shutil.copy2(bundle / 'manifest.json', deployed_manifest)
    ops = ops_module(source)
    with registry_login() as docker_env:
        for info in {**manifest['runtimeImages'], **manifest['images']}.values():
            run(['docker', 'pull', info['ref']], env=docker_env)
            if ops.image_id(info['ref']) != info['id']:
                raise RuntimeError('Downloaded release image identity mismatch')
        verify_image_labels(manifest)
    if not (PRODUCTION_STATE / 'compose.env').exists():
        raise RuntimeError('Initialize and configure the production state before deployment')
    os.environ['STUDYROOM_STATE_DIR'] = str(PRODUCTION_STATE)
    ops.deploy('prod', deployed_manifest,
               runtime_images={name.upper() + '_IMAGE': info['ref'] for name, info in manifest['runtimeImages'].items()})
    ops.verify_running_release('prod', manifest)
    print('Production deployed from stable source: ' + str(source))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    publishing = commands.add_parser('publish')
    publishing.add_argument('--manifest', type=Path, default=ROOT / 'output/container-release/manifest.json')
    publishing.add_argument('--evidence', type=Path, default=ROOT / 'output/container-release/result.json')
    publishing.add_argument('--output', type=Path, default=ROOT / 'output/github-release')
    deploying = commands.add_parser('deploy')
    deploying.add_argument('bundle', type=Path)
    args = parser.parse_args()
    (publish if args.command == 'publish' else deploy)(args)


if __name__ == '__main__':
    main()
