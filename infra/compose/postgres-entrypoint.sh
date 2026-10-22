#!/bin/sh
# Docker starts this wrapper as root, before the official entrypoint changes to postgres.
set -eu
export CORE_DB_PASSWORD="$(cat /run/secrets/core_db_password)"
export REPAIR_DB_PASSWORD="$(cat /run/secrets/repair_db_password)"
export MIGRATION_DB_PASSWORD="$(cat /run/secrets/migration_db_password)"
exec /usr/local/bin/docker-entrypoint.sh "$@"
