#!/usr/bin/env python3
"""D-06 real Mailpit SMTP + unavailable-peer recovery on isolated PostgreSQL."""
from runner_locks import local_runner_lock
from run_core_persistence import persistence_environment, ops, ROOT


def run_local():
    env = persistence_environment('studyroom_core_smtp')
    env['STUDYROOM_SMTP_DB_TESTS'] = 'true'
    env.setdefault('STUDYROOM_SMTP_HOST', env['SMTP_HOST'])
    env.setdefault('STUDYROOM_SMTP_PORT', env['SMTP_PORT'])
    _, values = ops.config('local')
    env.setdefault('STUDYROOM_MAILPIT_URL', 'http://127.0.0.1:' + values['MAIL_UI_PORT'])
    ops.run([ROOT / 'mvnw', '-B', '-ntp', '-pl', 'services/core-service', '-am',
             '-Dtest=CoreSmtpAcceptanceTest', '-Dsurefire.failIfNoSpecifiedTests=false', 'test'], env=env)


def main():
    from run_core_persistence import ops
    with local_runner_lock(ops, workspace=True):
        run_local()


if __name__ == '__main__':
    main()
