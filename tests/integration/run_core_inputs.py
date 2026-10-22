#!/usr/bin/env python3
"""Typed-command replay, wire input boundaries and negative authorization on PostgreSQL."""
from run_core_persistence import persistence_environment, ops, ROOT

def main():
    env = persistence_environment('studyroom_core_inputs')
    env['STUDYROOM_INPUT_DB_TESTS'] = 'true'
    ops.run([ROOT / 'mvnw', '-B', '-ntp', '-pl', 'services/core-service', '-am',
             '-Dtest=CoreInputBoundaryTest', '-Dsurefire.failIfNoSpecifiedTests=false', 'test'], env=env)

if __name__ == '__main__':
    main()
