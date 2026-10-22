#!/usr/bin/env python3
"""Run D authorization and administration against an isolated PostgreSQL database."""
import importlib.util
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('studyroom_ops',ROOT/'scripts/studyroom.py')
ops=importlib.util.module_from_spec(spec)
spec.loader.exec_module(ops)

def psql(sql,database='studyroom'):
    return ops.compose('local','exec','-T','postgres','psql','-v','ON_ERROR_STOP=1','-U','studyroom_bootstrap','-d',database,'-tAc',sql,capture=True).stdout.strip()

def main():
    database='studyroom_admin_integration'
    if not psql("SELECT 1 FROM pg_database WHERE datname='studyroom_admin_integration'"):
        psql('CREATE DATABASE studyroom_admin_integration OWNER studyroom_migration')
    psql('''CREATE SCHEMA IF NOT EXISTS core AUTHORIZATION studyroom_migration;
    CREATE SCHEMA IF NOT EXISTS repair AUTHORIZATION studyroom_migration;
    CREATE SCHEMA IF NOT EXISTS session AUTHORIZATION studyroom_migration;
    GRANT USAGE ON SCHEMA core,session TO studyroom_core;
    ALTER DEFAULT PRIVILEGES FOR ROLE studyroom_migration IN SCHEMA core GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO studyroom_core;
    ALTER DEFAULT PRIVILEGES FOR ROLE studyroom_migration IN SCHEMA session GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO studyroom_core;''',database)
    env=ops.native_env('local','migration');env['DB_URL']=env['DB_URL'].rsplit('/',1)[0]+'/'+database
    for action in ('migrate','reference','demo'):
        ops.run(['java','-jar',ops.jar('migration'),action],env=env)
    psql('REVOKE UPDATE,DELETE ON core.audit_event FROM studyroom_core',database)
    env=ops.native_env('local','core');env['DB_URL']=env['DB_URL'].rsplit('/',1)[0]+'/'+database;env['STUDYROOM_ADMIN_DB_TESTS']='true';env['JOBS_PAUSED']='true'
    control=ROOT/'.local/admin-test-control';control.mkdir(exist_ok=True);(control/'jobs-paused').touch();env['CONTROL_DIR']=str(control)
    ops.run([ROOT/'mvnw','-B','-ntp','-pl','services/core-service','-am','-Dtest=AdminPersistenceTest','-Dsurefire.failIfNoSpecifiedTests=false','test'],env=env)
if __name__=='__main__':main()
