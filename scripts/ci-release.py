#!/usr/bin/env python3
"""Build and test a release in an isolated project on the trusted GitHub runner."""
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
REPOSITORY = 'Monthlyaway/campus-study-room'


def actions_identity(env):
    if (env.get('CI') != 'true' or env.get('GITHUB_ACTIONS') != 'true'
            or env.get('GITHUB_REPOSITORY') != REPOSITORY
            or Path(env.get('GITHUB_WORKSPACE', '/')).resolve() != ROOT):
        raise RuntimeError('Only run in this repository GitHub Actions checkout')
    run, attempt, commit = (env.get(key, '') for key in
                            ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'GITHUB_SHA'))
    if not re.fullmatch(r'[1-9][0-9]*', run) or not re.fullmatch(r'[1-9][0-9]*', attempt):
        raise RuntimeError('Missing immutable GitHub run identity')
    event = env.get('GITHUB_EVENT_NAME')
    if event == 'pull_request':
        payload = json.loads(Path(env['GITHUB_EVENT_PATH']).read_text())['pull_request']
        if payload['head']['repo']['full_name'] != REPOSITORY:
            raise RuntimeError('Fork code must not execute on this trusted runner')
        commit = payload['head']['sha']
    elif event not in ('push', 'workflow_dispatch'):
        raise RuntimeError('Unsupported GitHub Actions event')
    if not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise RuntimeError('Missing immutable GitHub commit identity')
    ref = env.get('GITHUB_REF', '')
    if ref.startswith('refs/tags/'):
        release = ref.removeprefix('refs/tags/')
        if not re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?', release):
            raise RuntimeError('Release tag must be a semantic version')
    else:
        release = f'ci-{run}-{attempt}-{commit[:12]}'
    return run, attempt, commit, release


def free_ports():
    sockets = [socket.socket() for _ in range(2)]
    try:
        for connection in sockets:
            connection.bind(('127.0.0.1', 0))
        return [connection.getsockname()[1] for connection in sockets]
    finally:
        for connection in sockets:
            connection.close()


def main():
    run, attempt, commit, release = actions_identity(os.environ)
    actual = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    if actual != commit:
        raise RuntimeError('Checkout differs from the event commit')
    # The workflow and host lock serialize jobs. Each run owns only this project;
    # the shared Docker socket is for trusted repository code, never fork code.
    os.environ.update(COMPOSE_PARALLEL_LIMIT='1', COMPOSE_BAKE='false')
    version = json.loads(subprocess.check_output(['docker', 'version', '--format', '{{json .}}'], text=True))
    compose_version = subprocess.check_output(['docker', 'compose', 'version', '--short'], text=True).strip().lstrip('v')
    baseline = json.loads((ROOT / 'docs/dependency-baseline.json').read_text())
    if version['Server']['Version'] != baseline['docker'] or compose_version != baseline['compose']:
        raise RuntimeError('Runner Docker/Compose differs from the dependency baseline')
    spec = importlib.util.spec_from_file_location('ci_release_ops', ROOT / 'scripts/studyroom.py')
    ops = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(ops)
    output = ROOT / 'output/container-release'
    for path in (output, ROOT / 'output/browser'):
        if path.is_symlink():
            raise RuntimeError('CI report path must not be a symlink')
        if path.exists():
            shutil.rmtree(path)
        path.mkdir(parents=True)
    os.environ.pop('STUDYROOM_STATE_DIR', None)
    ops.build_release(release)
    manifest_path = ROOT / '.local/releases' / release / 'manifest.json'
    manifest = json.loads(manifest_path.read_text())
    (output / 'manifest.json').write_bytes(manifest_path.read_bytes())
    for name in ('postgres', 'mailpit'):
        ops.run(['docker', 'pull', baseline['images'][name]])
    state = ROOT / '.local' / f'ci-release-{run}-{attempt}'
    if state.exists():
        raise RuntimeError('CI state already exists; use a fresh GitHub run attempt')
    os.environ['STUDYROOM_STATE_DIR'] = str(state)
    ops.run([sys.executable, ROOT / 'scripts/init-env', 'test', '--directory', state])
    web_port, mail_port = free_ports()
    config = state / 'compose.env'
    replacements = {'COMPOSE_PROJECT_NAME': f'studyroom-ci-{run}-{attempt}',
                    'WEB_PORT': str(web_port), 'MAIL_UI_PORT': str(mail_port),
                    'BIND_ADDRESS': '127.0.0.1', 'PUBLIC_BASE_URL': f'http://127.0.0.1:{web_port}'}
    values = dict(line.split('=', 1) for line in config.read_text().splitlines() if line and not line.startswith('#'))
    # Exercise the same small-host resource limits as production, not an
    # unconstrained acceptance environment that only works on larger machines.
    replacements.update(CORE_MEMORY_LIMIT='512m', REPAIR_MEMORY_LIMIT='448m',
                        POSTGRES_MEMORY_LIMIT='256m', MAILPIT_MEMORY_LIMIT='96m', WEB_MEMORY_LIMIT='64m',
                        MIGRATION_MEMORY_LIMIT='384m', POSTGRES_SHARED_BUFFERS='64MB', POSTGRES_MAX_CONNECTIONS='40',
                        CORE_DB_POOL_SIZE='6', REPAIR_DB_POOL_SIZE='6',
                        APP_JAVA_OPTS='-Xms64m -Xmx192m -XX:MaxMetaspaceSize=128m -XX:MaxDirectMemorySize=32m '
                                      '-XX:ReservedCodeCacheSize=48m -Xss512k -XX:+ExitOnOutOfMemoryError',
                        MIGRATION_JAVA_OPTS='-Xms32m -Xmx128m -XX:MaxMetaspaceSize=128m -XX:+ExitOnOutOfMemoryError')
    values.update(replacements)
    config.write_text('\n'.join(key + '=' + value for key, value in values.items()) + '\n')
    try:
        ops.deploy('test', manifest_path)
        # Browser installation uses official sources and runs on the remote host.
        # Secrets stay in this run's private test state, outside uploaded artifacts.
        browser_env = {**os.environ, 'STUDYROOM_E2E_ALLOW_MUTATIONS': '1',
                       'STUDYROOM_E2E_BASE_URL': f'http://127.0.0.1:{web_port}',
                       'STUDYROOM_E2E_EXPECT_COMMIT': commit,
                       'STUDYROOM_TEST_PASSWORD_FILE': str(state / 'secrets/demo_password')}
        browser_dir = ROOT / 'tests/browser'
        ops.run(['npm', 'ci'], cwd=browser_dir, env=browser_env)
        ops.run(['npx', 'playwright', 'install', 'chromium'], cwd=browser_dir, env=browser_env)
        ops.run(['npm', 'test'], cwd=browser_dir, env=browser_env)
        from ci_reports import require_junit_report
        require_junit_report(ROOT / 'output/browser/junit.xml')
        ops.verify_running_release('test', manifest)
        ops.write_json(output / 'result.json', {'result': 'passed', 'runId': run, 'runAttempt': attempt,
                       'repository': REPOSITORY, 'commit': commit, 'releaseId': release,
                       'manifestSha256': ops.digest(manifest_path),
                       'docker': version['Server']['Version'], 'compose': compose_version,
                       'checks': ['three-images-built', 'fresh-test-deployment', 'private-endpoint-smoke',
                                  'real-browser-workflows', 'running-version-identity']})
    finally:
        try:
            with (output / 'services.log').open('w') as log:
                try:
                    subprocess.run(['docker', 'compose', '--env-file', str(state / 'compose.env'), '-f', str(ops.BASE),
                                    'logs', '--no-color'], cwd=ROOT, env={**os.environ, **ops.config('test')[1]},
                                   stdout=log, stderr=subprocess.STDOUT, check=True)
                except subprocess.CalledProcessError:
                    pass
            with ops.environment_lock('test'):
                # Only this run's containers/network are removed. Named database/mail
                # volumes and private state remain for diagnosis; no global prune.
                ops.compose('test', 'down', '--timeout', '60')
        finally:
            # Cleanup failures must not skip privacy checks before always-upload.
            from private_artifacts import protect_reports
            protect_reports([output, ROOT / 'output/browser'],
                            [p.read_text().strip() for p in (state / 'secrets').iterdir() if p.is_file()],
                            ROOT / '.local/private-ci-artifacts' / f'{run}-{attempt}')


if __name__ == '__main__':
    main()
