#!/bin/sh
set -eu
if [ -r /run/secrets/db_password ]; then export DB_PASSWORD="$(cat /run/secrets/db_password)"; fi
if [ -r /run/secrets/internal_password ]; then export INTERNAL_PASSWORD="$(cat /run/secrets/internal_password)"; fi
if [ -r /run/secrets/checkin_key ]; then export CHECKIN_CODE_ENCRYPTION_KEY="$(cat /run/secrets/checkin_key)"; fi
if [ -r /run/secrets/smtp_password ]; then export SMTP_PASSWORD="$(cat /run/secrets/smtp_password)"; fi
if [ -r /run/secrets/admin_password ]; then export ADMIN_PASSWORD="$(cat /run/secrets/admin_password)"; fi
# Read owner-only mounted secrets, then permanently drop privilege before Java starts.
if [ "$(id -u)" = 0 ]; then
  exec setpriv --reuid=10001 --regid=10001 --init-groups "$0" "$@"
fi
: "${DB_PASSWORD:?Database secret is required}"
if [ "${1:-run}" = run ]; then
  exec java ${JAVA_OPTS:--XX:MaxRAMPercentage=70} -jar /app/application.jar
else
  exec java ${JAVA_OPTS:--XX:MaxRAMPercentage=70} -jar /app/migration.jar "$@"
fi
