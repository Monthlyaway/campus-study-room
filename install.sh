#!/bin/sh
# Portable installation entry: Docker Engine + Compose and Python 3 are sufficient.
set -eu
cd "$(dirname "$0")"
command -v docker >/dev/null || { echo 'Install Docker Engine and Compose v2 first.' >&2; exit 1; }
command -v python3 >/dev/null || { echo 'Python 3.10+ is required.' >&2; exit 1; }
docker info >/dev/null
environment=${1:-local}
[ "$#" -eq 0 ] || shift
schedule_backups=false
if [ "$#" -gt 0 ]; then
  [ "$#" -eq 1 ] && [ "$1" = --schedule-backups ] || { echo 'Usage: ./install.sh [local|dev|test] [--schedule-backups]' >&2; exit 1; }
  schedule_backups=true
fi
python3 scripts/studyroom.py install "$environment"
if [ "$schedule_backups" = true ]; then
  python3 scripts/backup_schedule.py install "$environment"
fi
