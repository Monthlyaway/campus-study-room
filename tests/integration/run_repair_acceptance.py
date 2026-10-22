#!/usr/bin/env python3
"""Run M3 public API acceptance against the native local pair and actual Mailpit."""
from run_core_persistence import ops, ROOT


def run_local():
    env = ops.native_env('local', 'core')
    _, values = ops.config('local')
    env['STUDYROOM_TEST_PASSWORD'] = env['DEMO_PASSWORD']
    env['STUDYROOM_MAILPIT_URL'] = 'http://127.0.0.1:' + values['MAIL_UI_PORT']
    ops.run(['python3', ROOT / 'tests/integration/repair_acceptance_http.py'], env=env)


def main():
    from run_core_persistence import ops
    with ops.environment_lock('local'):
        run_local()


if __name__ == '__main__':
    main()
