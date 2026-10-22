#!/usr/bin/env python3
"""Isolated PostgreSQL and real HTTP contract regressions."""
from run_core_persistence import persistence_environment, ops, ROOT

def main():
    env = persistence_environment('studyroom_core_contract')
    env['STUDYROOM_CONTRACT_DB_TESTS'] = 'true'
    ops.run([ROOT / 'mvnw', '-B', '-ntp', '-pl', 'services/core-service', '-am',
             '-Dtest=CoreContractTest', '-Dsurefire.failIfNoSpecifiedTests=false', 'test'], env=env)

if __name__ == '__main__':
    main()
