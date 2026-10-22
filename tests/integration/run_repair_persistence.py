#!/usr/bin/env python3
"""Run repair JPA/deadline/concurrency tests in an isolated local PostgreSQL database."""
import importlib.util
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("studyroom_ops", ROOT / "scripts/studyroom.py")
ops = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ops)


def psql(sql, database="studyroom"):
    return ops.compose("local", "exec", "-T", "postgres", "psql", "-v", "ON_ERROR_STOP=1", "-U", "studyroom_bootstrap", "-d", database, "-tAc", sql, capture=True).stdout.strip()


def main():
    database = "studyroom_repair_integration"
    if not psql("SELECT 1 FROM pg_database WHERE datname='studyroom_repair_integration'"):
        psql("CREATE DATABASE studyroom_repair_integration OWNER studyroom_migration")
    psql("""
      CREATE SCHEMA IF NOT EXISTS core AUTHORIZATION studyroom_migration;
      CREATE SCHEMA IF NOT EXISTS repair AUTHORIZATION studyroom_migration;
      CREATE SCHEMA IF NOT EXISTS session AUTHORIZATION studyroom_migration;
      GRANT USAGE ON SCHEMA repair,session TO studyroom_repair;
      ALTER DEFAULT PRIVILEGES FOR ROLE studyroom_migration IN SCHEMA repair GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO studyroom_repair;
      ALTER DEFAULT PRIVILEGES FOR ROLE studyroom_migration IN SCHEMA session GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO studyroom_repair;
    """, database)
    env = ops.native_env("local", "migration")
    env["DB_URL"] = env["DB_URL"].rsplit("/", 1)[0] + "/" + database
    ops.run(["java", "-jar", ops.jar("migration"), "migrate"], env=env)
    psql("REVOKE UPDATE,DELETE ON repair.audit_event FROM studyroom_repair", database)
    env = ops.native_env("local", "repair")
    env["DB_URL"] = env["DB_URL"].rsplit("/", 1)[0] + "/" + database
    env["STUDYROOM_REPAIR_DB_TESTS"] = "true"
    ops.run([ROOT / "mvnw", "-B", "-ntp", "-pl", "services/repair-service", "-am", "-Dtest=RepairPersistenceTest", "-Dsurefire.failIfNoSpecifiedTests=false", "test"], env=env)


if __name__ == "__main__":
    main()
