#!/usr/bin/env python3
"""Fail closed before executing the repository-specific trusted runner workload."""
import json, os, re, subprocess
from pathlib import Path

def validate(env, event):
    if env.get('GITHUB_ACTIONS') != 'true' or env.get('GITHUB_REPOSITORY') != 'Monthlyaway/campus-study-room':
        raise RuntimeError('Use this repository GitHub Actions runner')
    if env.get('GITHUB_ACTOR') != 'Monthlyaway' or env.get('GITHUB_TRIGGERING_ACTOR') != 'Monthlyaway' or env.get('RUNNER_NAME') != 'codex-lb-campus-study-room':
        raise RuntimeError('Only the owner on the dedicated runner may run trusted jobs')
    kind, ref = env.get('GITHUB_EVENT_NAME'), env.get('GITHUB_REF', '')
    if kind == 'pull_request':
        pr = event.get('pull_request', {})
        if pr.get('head', {}).get('repo', {}).get('full_name') != env['GITHUB_REPOSITORY'] or pr.get('base', {}).get('ref') != 'main' or not pr.get('head', {}).get('ref', '').startswith('ycy/'):
            raise RuntimeError('Fork PRs and non-main targets cannot use the production cohost')
        commit = pr.get('head', {}).get('sha', '')
    elif kind in ('push', 'workflow_dispatch'):
        if ref != 'refs/heads/main' and not ref.startswith('refs/heads/ycy/') and not re.fullmatch(r'refs/tags/v[0-9]+\.[0-9]+\.[0-9]+', ref):
            raise RuntimeError('Unexpected trusted ref')
        commit = env.get('GITHUB_SHA', '')
    else:
        raise RuntimeError('Unexpected GitHub event')
    if not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise RuntimeError('Missing exact commit')
    return commit

if __name__ == '__main__':
    root = Path(__file__).resolve().parent.parent
    commit = validate(os.environ, json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text()))
    if Path(os.environ['GITHUB_WORKSPACE']).resolve() != root:
        raise RuntimeError('Unexpected checkout path')
    actual = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    if actual != commit:
        raise RuntimeError('Checkout does not match requested commit')
    subprocess.run(['docker', 'info'], check=True, stdout=subprocess.DEVNULL)
    print('Trusted runner identity and exact checkout verified: ' + commit)
