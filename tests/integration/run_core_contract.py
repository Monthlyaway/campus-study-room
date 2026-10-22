#!/usr/bin/env python3
"""Isolated PostgreSQL and real HTTP contract regressions."""
from runner_locks import local_runner_lock
from run_core_persistence import persistence_environment, ops, ROOT

def run_local():
    env = persistence_environment('studyroom_core_contract')
    env['STUDYROOM_CONTRACT_DB_TESTS'] = 'true'
    ops.run([ROOT / 'mvnw', '-B', '-ntp', '-pl', 'services/core-service', '-am',
             '-Dtest=CoreContractTest', '-Dsurefire.failIfNoSpecifiedTests=false', 'test'], env=env)

def main():
    with local_runner_lock(ops, workspace=True):
        run_local()


if __name__ == '__main__':
    main()
