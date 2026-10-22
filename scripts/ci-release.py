#!/usr/bin/env python3
"""Build an immutable release and deploy/test it in this CI job's private Docker daemon."""
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent.parent


def main():
    if os.environ.get('CI') != 'true' or Path(os.environ.get('CI_PROJECT_DIR', '/')).resolve() != ROOT:
        raise RuntimeError('Only run inside the intended disposable GitLab job checkout')
    pipeline = os.environ.get('CI_PIPELINE_ID', '')
    commit = os.environ.get('CI_COMMIT_SHA', '')
    if not re.fullmatch(r'[0-9]+', pipeline) or not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise RuntimeError('Missing immutable GitLab pipeline identity')
    actual = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    if actual != commit:
        raise RuntimeError('Checkout differs from pipeline commit')
    if os.environ.get('DOCKER_TLS_VERIFY') != '1':
        raise RuntimeError('CI Docker connection must verify TLS')
    output = ROOT / 'output/container-release'
    output.mkdir(parents=True, exist_ok=True)
    for attempt in range(90):
        if subprocess.run(['docker', 'info'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
            break
        time.sleep(1)
    else:
        raise RuntimeError('Private CI Docker daemon did not become ready; runner must support isolated DinD')
    version = json.loads(subprocess.check_output(['docker', 'version', '--format', '{{json .}}'], text=True))
    compose = subprocess.check_output(['docker', 'compose', 'version', '--short'], text=True).strip().lstrip('v')
    if version['Server']['Version'] != '28.5.2' or compose != '2.40.3':
        raise RuntimeError('CI Docker/Compose differs from pinned runtime')
    spec = importlib.util.spec_from_file_location('ci_release_ops', ROOT / 'scripts/studyroom.py')
    ops = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(ops)
    release = 'ci-' + pipeline + '-' + commit[:12]
    # Build uses its own local configuration; test state is selected only afterwards.
    os.environ.pop('STUDYROOM_STATE_DIR', None)
    ops.build_release(release)
    manifest_path = ROOT / '.local/releases' / release / 'manifest.json'
    manifest = json.loads(manifest_path.read_text())
    (output / 'manifest.json').write_bytes(manifest_path.read_bytes())
    baseline = json.loads((ROOT / 'docs/dependency-baseline.json').read_text())
    for name in ('postgres', 'mailpit'):
        ops.run(['docker', 'pull', baseline['images'][name]])
    state = ROOT / '.local/ci-release'
    os.environ['STUDYROOM_STATE_DIR'] = str(state)
    ops.run([sys.executable, ROOT / 'scripts/init-env', 'test', '--directory', state])
    config = state / 'compose.env'
    replacements = {'COMPOSE_PROJECT_NAME': 'studyroom-ci-' + pipeline,
                    'BIND_ADDRESS': '0.0.0.0', 'PUBLIC_BASE_URL': 'http://docker:18082'}
    config.write_text('\n'.join(key + '=' + replacements.get(key, value) if sep else line
                                for line in config.read_text().splitlines()
                                for key, sep, value in [line.partition('=')]) + '\n')
    try:
        ops.deploy('test', manifest_path)
        # Browser runs in the private daemon's network namespace, against the actual three images.
        # /builds is shared by GitLab job/services; never mount a host Docker socket.
        ops.run(['docker', 'run', '--rm', '--network', 'host', '--shm-size=1g',
                 '--mount', f'type=bind,src={ROOT},dst=/workspace', '-w', '/workspace/tests/browser',
                 '-e', 'CI=true', '-e', 'STUDYROOM_E2E_ALLOW_MUTATIONS=1',
                 '-e', 'STUDYROOM_E2E_BASE_URL=http://127.0.0.1:18082',
                 '-e', 'STUDYROOM_E2E_EXPECT_COMMIT=' + commit,
                 '-e', 'STUDYROOM_TEST_PASSWORD_FILE=/workspace/.local/ci-release/secrets/demo_password',
                 baseline['images']['node'], 'sh', '-ec',
                 '../../scripts/ci-apt git ca-certificates\nnpm ci\nnpx playwright install --with-deps chromium\nnpm test'])
        from ci_reports import require_junit_report
        require_junit_report(ROOT / 'output/browser/junit.xml')
        ops.verify_running_release('test', manifest)
        ops.write_json(output / 'result.json', {'result': 'passed', 'pipeline': pipeline, 'commit': commit,
                       'releaseId': release, 'docker': version['Server']['Version'], 'compose': compose,
                       'checks': ['three-images-built', 'fresh-test-deployment', 'private-endpoint-smoke', 'real-browser-workflows', 'running-version-identity']})
    finally:
        # Keep named volumes and private state for the job lifetime; do not publish secrets or DB dumps.
        with (output / 'services.log').open('w') as log:
            try:
                subprocess.run(['docker', 'compose', '--env-file', str(state / 'compose.env'), '-f', str(ops.BASE), 'logs', '--no-color'], cwd=ROOT, env={**os.environ, **ops.config('test')[1]}, stdout=log, stderr=subprocess.STDOUT, check=True)
            except Exception:
                pass
        with ops.environment_lock('test'):
            ops.compose('test', 'stop', '-t', '60', 'core', 'repair', 'web', check=False)
        from private_artifacts import protect_reports
        protect_reports([output, ROOT / 'output/browser'],
                        [p.read_text().strip() for p in (state / 'secrets').iterdir() if p.is_file()],
                        ROOT / '.local/private-ci-artifacts')


if __name__ == '__main__':
    main()
