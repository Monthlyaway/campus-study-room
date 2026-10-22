#!/usr/bin/env python3
"""Run core resource/deadline tests on an isolated, migrated PostgreSQL database."""
from runner_locks import local_runner_lock
import importlib.util
import re
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('studyroom_ops', ROOT / 'scripts/studyroom.py')
ops = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ops)

def psql(sql, database='studyroom'):
    return ops.compose('local', 'exec', '-T', 'postgres', 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'studyroom_bootstrap', '-d', database, '-tAc', sql, capture=True).stdout.strip()

def persistence_environment(database='studyroom_core_integration'):
    if not re.fullmatch(r'studyroom_core_[a-z_]+', database):
        raise ValueError('Only isolated core test database names are accepted')
    if not psql(f"SELECT 1 FROM pg_database WHERE datname='{database}'"):
        psql(f'CREATE DATABASE {database} OWNER studyroom_migration')
    psql('''CREATE SCHEMA IF NOT EXISTS core AUTHORIZATION studyroom_migration;
    CREATE SCHEMA IF NOT EXISTS repair AUTHORIZATION studyroom_migration;
    CREATE SCHEMA IF NOT EXISTS session AUTHORIZATION studyroom_migration;
    GRANT USAGE ON SCHEMA core,session TO studyroom_core;
    ALTER DEFAULT PRIVILEGES FOR ROLE studyroom_migration IN SCHEMA core GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO studyroom_core;
    ALTER DEFAULT PRIVILEGES FOR ROLE studyroom_migration IN SCHEMA session GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO studyroom_core;''', database)
    env = ops.native_env('local', 'migration')
    env['DB_URL'] = env['DB_URL'].rsplit('/', 1)[0] + '/' + database
    for action in ('migrate', 'reference', 'demo'):
        ops.run(['java', '-jar', ops.jar('migration'), action], env=env)
    psql('REVOKE UPDATE,DELETE ON core.audit_event FROM studyroom_core', database)
    env = ops.native_env('local', 'core')
    env['DB_URL'] = env['DB_URL'].rsplit('/', 1)[0] + '/' + database
    env['STUDYROOM_CORE_DB_TESTS'] = 'true'
    control = ROOT / '.local/core-test-control'
    control.mkdir(exist_ok=True)
    (control / 'jobs-paused').touch()
    env['CONTROL_DIR'] = str(control)
    return env

def run_local():
    env = persistence_environment()
    ops.run([ROOT / 'mvnw', '-B', '-ntp', '-pl', 'services/core-service', '-am', '-Dtest=CorePersistenceTest', '-Dsurefire.failIfNoSpecifiedTests=false', 'test'], env=env)

def main():
    with local_runner_lock(ops, workspace=True):
        run_local()


if __name__ == '__main__':
    main()
