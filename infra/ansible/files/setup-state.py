#!/usr/bin/env python3
"""Create once, validate thereafter. Never rotate or print runtime secrets."""
import argparse
import base64
import fcntl
import json
import os
from pathlib import Path
import subprocess
import sys

NAMES = ("postgres_password", "core_db_password", "repair_db_password", "migration_db_password", "internal_password", "checkin_key", "demo_password")


def read_config(path):
    result = {}
    for line in path.read_text().splitlines():
        if line and not line.startswith("#"):
            key, value = line.split("=", 1)
            if key in result:
                raise RuntimeError("Duplicate configuration key")
            result[key] = value
    return result


def configure(settings, initializer):
    state = Path(settings["state_directory"])
    expected = {"ENV_NAME": settings["environment"], "STUDYROOM_STATE_DIR": str(state), "COMPOSE_PROJECT_NAME": settings["project_name"],
                "WEB_PORT": str(settings["web_port"]), "MAIL_UI_PORT": str(settings["mail_ui_port"]), "BIND_ADDRESS": settings["bind_address"],
                "PUBLIC_BASE_URL": settings["public_base_url"], "MAIL_MODE": "smtp", "SMTP_HOST": "mailpit", "SMTP_PORT": "1025", "COOKIE_SECURE": "false"}
    for path in [state, state / "secrets", state / "compose.env", state / "bootstrap-state.json", state / ".bootstrap.lock", *[state / "secrets" / name for name in NAMES]]:
        if path.is_symlink():
            raise RuntimeError("Runtime paths must not be symlinks")
    existing = (state / "compose.env").exists()
    if existing:
        current = read_config(state / "compose.env")
        if any(current.get(key) != value for key, value in expected.items()):
            raise RuntimeError("Existing configuration differs from inventory; it was not overwritten")
    missing_secrets = any(not (state / "secrets" / name).is_file() for name in NAMES)
    if existing and missing_secrets:
        raise RuntimeError("Existing runtime is missing secrets; restore them from backup rather than generating replacement credentials")
    if not existing and any((state / "secrets" / name).exists() for name in NAMES):
        raise RuntimeError("Existing secrets have no configuration; restore the matching configuration before bootstrap")
    missing = not existing
    if missing:
        subprocess.run([sys.executable, initializer, settings["environment"], "--directory", str(state)], check=True, capture_output=True, text=True)
    if not existing:
        current = read_config(state / "compose.env")
        current.update(expected)
        temporary = state / "compose.env.bootstrap-tmp"
        descriptor = os.open(temporary, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, "w") as stream:
            stream.write("\n".join(f"{key}={value}" for key, value in current.items()) + "\n")
        os.replace(temporary, state / "compose.env")
    for path in [state, state / "secrets", state / "compose.env"]:
        if path.stat().st_mode & 0o077 or path.stat().st_uid != os.geteuid():
            raise RuntimeError("Runtime directories and configuration must belong to the deployment user and be private")
    for name in NAMES:
        path = state / "secrets" / name
        if not path.is_file() or path.stat().st_mode & 0o077 or path.stat().st_uid != os.geteuid():
            raise RuntimeError("Secret files must belong to the deployment user and be private")
        value = path.read_text().strip()
        if name == "checkin_key":
            if len(base64.b64decode(value, validate=True)) != 32:
                raise RuntimeError("Check-in encryption key must decode to 32 bytes")
        elif not 12 <= len(value) <= 64 or any(character.isspace() for character in value):
            raise RuntimeError("Existing secret does not meet runtime length/format requirements")
    report = {"environment": settings["environment"], "secretCount": len(NAMES), "secretsValidated": True, "configuration": expected}
    marker = state / "bootstrap-state.json"
    content = json.dumps(report, indent=2) + "\n"
    changed = missing or not marker.exists() or marker.read_text() != content
    if not marker.exists() or marker.read_text() != content:
        marker.write_text(content)
        marker.chmod(0o600)
    return {"changed": changed, "environment": settings["environment"], "secretsValidated": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--settings", required=True)
    parser.add_argument("--initializer", required=True)
    args = parser.parse_args()
    settings = json.loads(args.settings)
    if settings["environment"] not in {"local", "dev", "test"}:
        raise RuntimeError("prod is not enabled")
    state = Path(settings["state_directory"])
    if not state.is_dir():
        raise RuntimeError("Create the private state directory before initialization")
    if (state / ".bootstrap.lock").is_symlink():
        raise RuntimeError("Runtime paths must not be symlinks")
    with open(state / ".bootstrap.lock", "a") as lock:
        os.chmod(lock.name, 0o600)
        fcntl.flock(lock, fcntl.LOCK_EX)
        print(json.dumps(configure(settings, args.initializer)))


if __name__ == "__main__":
    main()
