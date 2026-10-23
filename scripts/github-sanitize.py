#!/usr/bin/env python3
"""Revalidate the whole upload set, including after interrupted test commands."""
import json
import os
from pathlib import Path
from private_artifacts import protect_reports

def sanitize(root):
    paths = [root/"output/check", root/"output/container-release", root/"output/browser"]
    nodes = paths + [p for directory in paths if directory.exists() for p in directory.rglob("*")]
    if any(p.is_symlink() for p in nodes):
        raise RuntimeError("Artifact symlinks are not allowed")
    files = [p for p in nodes if p.is_file()]
    if not files:
        return
    secrets = []
    for directory in (root/".local").rglob("secrets"):
        if directory.is_dir():
            secrets.extend(p.read_text().strip() for p in directory.iterdir() if p.is_file() and not p.is_symlink())
    for path in (root/".local/check").glob("*/credentials.json"):
        secrets.extend(json.loads(path.read_text()).values())
    secrets.extend(os.environ.get(key, "") for key in ("GH_TOKEN", "GITHUB_TOKEN"))
    protect_reports(paths, secrets, root/".local/private-upload-quarantine")

if __name__ == "__main__":
    sanitize(Path(__file__).resolve().parent.parent)
    print("Complete artifact set passed independent private-data validation.")
