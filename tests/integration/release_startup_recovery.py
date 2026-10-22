#!/usr/bin/env python3
"""Inject one startup network failure in an explicitly isolated compatibility project."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import subprocess
import sys
import time

from release_compatibility import sandbox, versions_and_images, expanded_value, Client, ops


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('candidate', type=Path)
    parser.add_argument('private_fixture', type=Path)
    parser.add_argument('evidence', type=Path)
    args = parser.parse_args()
    state, values = sandbox('test')
    candidate = ops.verify_manifest(args.candidate)
    previous = json.loads((state / 'current-release.json').read_text())
    if candidate['releaseId'] == previous['releaseId']:
        raise RuntimeError('Require different candidate and previous application releases')
    if args.evidence.exists():
        raise RuntimeError('Do not overwrite failure evidence')
    before = ops.database_inventory('test')
    ops.compatibility_proof(state, candidate, previous, before)
    fixture = json.loads(args.private_fixture.read_text())
    if args.private_fixture.stat().st_mode & 0o077:
        raise RuntimeError('Private fixture must be 0600')
    failures = set((state / 'release-failures').glob('*.json'))
    started = time.monotonic()
    process = subprocess.Popen([sys.executable, str(ops.ROOT / 'scripts/studyroom.py'), 'deploy', 'test', str(args.candidate.resolve())])
    injected = False
    network = values['COMPOSE_PROJECT_NAME'] + '_private'
    while process.poll() is None and time.monotonic() - started < 120:
        containers = ops.run(['docker', 'ps', '--filter', 'label=com.docker.compose.project=' + values['COMPOSE_PROJECT_NAME'],
                              '--filter', 'label=com.docker.compose.service=core', '--format', '{{.ID}}'], capture=True).stdout.split()
        if len(containers) == 1:
            data = json.loads(ops.run(['docker', 'inspect', containers[0]], capture=True).stdout)[0]
            pending = state / 'pending-release.json'
            if (data['Image'] == candidate['images']['core']['id'] and network in data['NetworkSettings']['Networks']
                    and pending.exists() and json.loads(pending.read_text()).get('phase') == 'starting'):
                ops.run(['docker', 'network', 'disconnect', network, containers[0]])
                injected = True
                break
        time.sleep(0.5)
    if not injected:
        process.wait(timeout=300)
        raise RuntimeError('Candidate startup was not observed; no fault was injected')
    code = process.wait(timeout=360)
    if code == 0:
        raise RuntimeError('Failed candidate deployment must remain a failed command after recovery')
    actual = json.loads((state / 'current-release.json').read_text())
    if actual != previous:
        raise RuntimeError('Previous release was not restored')
    versions_and_images('test', previous)
    if ops.database_inventory('test') != before:
        raise RuntimeError('Startup-only recovery changed database inventory')
    expanded_value('test', fixture)
    for name in ('maintenance', 'jobs-paused'):
        if (state / 'control' / name).exists():
            raise RuntimeError('Recovered applications remained unavailable')
    ops.wait_status(values['PUBLIC_BASE_URL'], 200)
    client = Client(values['PUBLIC_BASE_URL'])
    client.login('student', ops.secret(state, 'demo_password'))
    for probe in fixture['reads']:
        if probe.get('actor') != 'applicant':
            result = client.call('GET', probe['path'])
            if result['id'] != probe['expected']['id'] or result['status'] != 'CANCELLED':
                raise RuntimeError('Recovered business records changed')
    new_failures = set((state / 'release-failures').glob('*.json')) - failures
    if len(new_failures) != 1:
        raise RuntimeError('Missing unique failure/recovery record')
    record = json.loads(new_failures.pop().read_text())
    if record.get('recovery', {}).get('result') != 'passed' or (state / 'pending-release.json').exists():
        raise RuntimeError('Recovery metadata is incomplete')
    result = {'result': 'passed', 'checkedAt': dt.datetime.now(dt.timezone.utc).isoformat(),
              'fault': 'Disconnect candidate core from its isolated private network during startup',
              'candidate': candidate['releaseId'], 'restored': previous['releaseId'], 'deployExitCode': code,
              'elapsedSeconds': round(time.monotonic() - started, 2), 'recovery': record['recovery'],
              'checks': {'threeImageIdsAndVersions': True, 'databaseAndExpandedColumnPreserved': True,
                         'publicEntryReopened': True, 'businessRecordsRetained': True, 'failedAttemptArchived': True}}
    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    args.evidence.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
