#!/usr/bin/env python3
"""Real HTTP + isolated PostgreSQL acceptance with test-only clock injection."""
from runner_locks import local_runner_lock
from run_core_persistence import persistence_environment, ops, ROOT

def run_local():
    env = persistence_environment('studyroom_core_matrix')
    env['STUDYROOM_MATRIX_DB_TESTS'] = 'true'
    ops.run([ROOT / 'mvnw', '-B', '-ntp', '-pl', 'services/core-service', '-am',
             '-Dtest=CoreAcceptanceMatrixTest', '-Dsurefire.failIfNoSpecifiedTests=false', 'test'], env=env)

def main():
    with local_runner_lock(ops, workspace=True):
        run_local()


if __name__ == '__main__':
    main()
