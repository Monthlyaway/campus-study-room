#!/bin/sh
set -eu
: "${CORE_DB_PASSWORD:?Database role password is required}"
: "${REPAIR_DB_PASSWORD:?Database role password is required}"
: "${MIGRATION_DB_PASSWORD:?Database role password is required}"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
\getenv core_password CORE_DB_PASSWORD
\getenv repair_password REPAIR_DB_PASSWORD
\getenv migration_password MIGRATION_DB_PASSWORD
CREATE ROLE studyroom_core LOGIN PASSWORD :'core_password';
CREATE ROLE studyroom_repair LOGIN PASSWORD :'repair_password';
CREATE ROLE studyroom_migration LOGIN PASSWORD :'migration_password';
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT CONNECT ON DATABASE studyroom TO studyroom_core, studyroom_repair, studyroom_migration;
CREATE SCHEMA core AUTHORIZATION studyroom_migration;
CREATE SCHEMA repair AUTHORIZATION studyroom_migration;
CREATE SCHEMA session AUTHORIZATION studyroom_migration;
GRANT USAGE ON SCHEMA core TO studyroom_core;
GRANT USAGE ON SCHEMA repair TO studyroom_repair;
GRANT USAGE ON SCHEMA session TO studyroom_core, studyroom_repair;
ALTER DEFAULT PRIVILEGES FOR ROLE studyroom_migration IN SCHEMA core GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO studyroom_core;
ALTER DEFAULT PRIVILEGES FOR ROLE studyroom_migration IN SCHEMA repair GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO studyroom_repair;
ALTER DEFAULT PRIVILEGES FOR ROLE studyroom_migration IN SCHEMA session GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO studyroom_core,studyroom_repair;
ALTER DEFAULT PRIVILEGES FOR ROLE studyroom_migration IN SCHEMA core GRANT USAGE, SELECT ON SEQUENCES TO studyroom_core;
ALTER DEFAULT PRIVILEGES FOR ROLE studyroom_migration IN SCHEMA repair GRANT USAGE, SELECT ON SEQUENCES TO studyroom_repair;
ALTER DEFAULT PRIVILEGES FOR ROLE studyroom_migration IN SCHEMA session GRANT USAGE, SELECT ON SEQUENCES TO studyroom_core,studyroom_repair;
ALTER ROLE studyroom_core SET search_path TO core,public;
ALTER ROLE studyroom_repair SET search_path TO repair,public;
SQL
