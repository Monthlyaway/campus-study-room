#!/bin/sh
# Portable installation entry: Docker Engine + Compose and Python 3 are sufficient.
set -eu
cd "$(dirname "$0")"
command -v docker >/dev/null || { echo 'Install Docker Engine and Compose v2 first.' >&2; exit 1; }
command -v python3 >/dev/null || { echo 'Python 3.10+ is required.' >&2; exit 1; }
docker info >/dev/null
exec python3 scripts/studyroom.py install "${1:-local}"
