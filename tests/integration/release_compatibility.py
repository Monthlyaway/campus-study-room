#!/usr/bin/env python3
"""Exercise old applications against an expanded database in an explicitly isolated project."""
import argparse
import datetime as dt
import http.cookiejar
import importlib.util
import json
import os
from pathlib import Path
import re
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("compatibility_ops", ROOT / "scripts/studyroom.py")
ops = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ops)


class Client:
    def __init__(self, base, cookie=None):
        self.base, self.cookie = base.rstrip("/"), cookie
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))

    def call(self, method, path, body=None):
        if not path.startswith("/api/v1/") or "\n" in path:
            raise RuntimeError("Only same-origin business probes are accepted")
        headers = {"Content-Type": "application/json"}
        if self.cookie:
            headers["Cookie"] = self.cookie
        if method != "GET":
            token = self.call("GET", "/api/v1/auth/csrf")
            headers[token["headerName"]] = token["csrfToken"]
            headers["Idempotency-Key"] = str(uuid.uuid4())
        request = urllib.request.Request(self.base + path, method=method, headers=headers,
                                         data=json.dumps(body).encode() if body is not None else None)
        with self.opener.open(request, timeout=30) as response:
            return json.load(response)["data"]

    def login(self, username, password):
        return self.call("POST", "/api/v1/auth/login", {"username": username, "password": password})

    def session_cookie(self):
        return "; ".join(f"{cookie.name}={cookie.value}" for cookie in self.jar if cookie.name == "STUDYROOMSESSION")


def sandbox(environment):
    if environment not in {"dev", "test"}:
        raise RuntimeError("Compatibility probes only run in isolated dev/test environments")
    state, values = ops.config(environment)
    if values.get("COMPATIBILITY_SANDBOX") != "true" or not values.get("COMPOSE_PROJECT_NAME", "").startswith("studyroom-compat-"):
        raise RuntimeError("Require COMPATIBILITY_SANDBOX=true and a unique studyroom-compat-* Compose project")
    return state, values


def versions_and_images(environment, manifest):
    for name, image in manifest["images"].items():
        container = ops.compose(environment, "ps", "-q", name, capture=True).stdout.strip()
        if not container or "\n" in container:
            raise RuntimeError(f"Expected exactly one running {name} container")
        actual = ops.run(["docker", "inspect", "--format", "{{.Image}}", container], capture=True).stdout.strip()
        if actual != image["id"]:
            raise RuntimeError(f"Running container image differs from manifest: {name}")
        if name == "web":
            data = json.loads(ops.compose(environment, "exec", "-T", name, "cat", "/usr/share/nginx/html/version.json", capture=True).stdout)
        else:
            data = json.loads(ops.compose(environment, "exec", "-T", name, "curl", "-fsS", "http://localhost:8080/api/v1/version", capture=True).stdout)["data"]
        if data.get("commit") != manifest["commit"] or data.get("releaseId") != manifest["releaseId"]:
            raise RuntimeError(f"Running release version differs from manifest: {name}")


def assert_subset(actual, expected, label):
    if not isinstance(expected, dict) or not expected or any(actual.get(key) != value for key, value in expected.items()):
        raise RuntimeError(f"Compatibility assertion failed: {label}")


EXPANDED_COLUMNS = {("core", "reservation", "operator_note"),
                    ("repair", "repair_order", "assigned_to_name")}


def expanded_column(column):
    parts = tuple(column.split("."))
    if parts not in EXPANDED_COLUMNS:
        raise RuntimeError("Expanded column must be an explicitly supported nullable migration")
    return parts


def capture(environment, manifest_path, fixture_path, column):
    state, config = sandbox(environment)
    manifest = ops.verify_manifest(manifest_path, allow_older_schema=True)
    path = Path(fixture_path).resolve()
    if path.exists():
        raise RuntimeError("Fixture exists; use a new file for a new rehearsal")
    parts = expanded_column(column)
    with ops.environment_lock(environment):
        versions_and_images(environment, manifest)
        if any((state / "control" / name).exists() for name in ("maintenance", "jobs-paused")):
            raise RuntimeError("Capture requires an available baseline; existing maintenance is preserved")
        password = ops.secret(state, "demo_password")
        student, manager = Client(config["PUBLIC_BASE_URL"]), Client(config["PUBLIC_BASE_URL"])
        me = student.login("student", password)
        manager.login("room-admin", password)
        building = student.call("GET", "/api/v1/buildings")["items"][0]
        room = manager.call("POST", "/api/v1/rooms", {"buildingId": building["id"], "code": "COMPAT-" + uuid.uuid4().hex[:10], "name": "兼容性演练隔离自习室"})
        manager.call("PUT", f"/api/v1/rooms/{room['id']}/opening-rules", {"expectedVersion": room["version"], "rules": [{"weekday": day, "startMinute": 0, "endMinute": 1440} for day in range(1, 8)]})
        seat = manager.call("POST", f"/api/v1/rooms/{room['id']}/seats", {"code": "01", "hasPower": True})
        start = (dt.datetime.now(dt.timezone(dt.timedelta(hours=8))) + dt.timedelta(days=2)).replace(hour=10, minute=0, second=0, microsecond=0)
        booking = student.call("POST", "/api/v1/reservations", {"seatId": seat["id"], "startAt": start.isoformat(), "endAt": (start + dt.timedelta(hours=1)).isoformat()})
        repair = student.call("POST", "/api/v1/repairs", {"roomId": room["id"], "title": "兼容性演练报修", "description": "此记录只存在于隔离演练环境，用于验证升级和回退的数据保留。"})
        applicant = Client(config["PUBLIC_BASE_URL"])
        applicant_me = applicant.login("applicant", password)
        application_start = start + dt.timedelta(hours=3)
        application = applicant.call("POST", "/api/v1/room-applications", {
            "roomId": room["id"], "startAt": application_start.isoformat(),
            "endAt": (application_start + dt.timedelta(hours=1)).isoformat(),
            "purposeTitle": "兼容性演练整室申请", "purposeDescription": "验证升级与旧版回退后的审批数据、占用和取消。"})
        for username in ("dept-approver", "space-approver"):
            approver = Client(config["PUBLIC_BASE_URL"])
            approver.login(username, password)
            application = approver.call("POST", f"/api/v1/room-applications/{application['id']}/decisions",
                                        {"expectedVersion": application["version"], "decision": "APPROVE"})
        if application["status"] != "APPROVED" or len(application["actions"]) != 2:
            raise RuntimeError("Baseline application was not approved by both independent approvers")
        fixture = {"formatVersion": 1, "capturedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                   "baselineManifestSha256": ops.object_digest(manifest), "cookie": student.session_cookie(), "userId": me["userId"],
                   "applicantCookie": applicant.session_cookie(), "applicantId": applicant_me["userId"],
                   "reads": [{"path": f"/api/v1/reservations/{booking['id']}", "expected": {key: booking[key] for key in ("id", "status", "version", "seatId")}},
                             {"path": f"/api/v1/repairs/{repair['id']}", "expected": {key: repair[key] for key in ("id", "status", "version", "reporterId")}},
                             {"actor": "applicant", "path": f"/api/v1/room-applications/{application['id']}",
                              "expected": {key: application[key] for key in ("id", "status", "version", "actions")}}],
                   "writes": [{"path": f"/api/v1/reservations/{booking['id']}/cancel", "body": {"expectedVersion": booking["version"], "reason": "兼容演练验证旧版写入"}, "expected": {"id": booking["id"], "status": "CANCELLED"}},
                              {"path": f"/api/v1/repairs/{repair['id']}/cancel", "body": {"expectedVersion": repair["version"], "reason": "兼容演练验证旧版写入"}, "expected": {"id": repair["id"], "status": "CANCELLED"}},
                              {"actor": "applicant", "path": f"/api/v1/room-applications/{application['id']}/cancel",
                               "body": {"expectedVersion": application["version"]}, "expected": {"id": application["id"], "status": "CANCELLED"}}],
                   "expandedColumn": {"schema": parts[0], "table": parts[1], "column": parts[2], "id": (booking if parts[0] == "core" else repair)["id"], "value": "retained-" + uuid.uuid4().hex}}
        path.parent.mkdir(parents=True, exist_ok=True)
        descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, "w") as stream:
            json.dump(fixture, stream, indent=2)
            stream.write("\n")
    print(f"Private V1 session/data fixture captured: {path}; finish the rehearsal within the session lifetime.")


def expanded_value(environment, fixture, initialize=False):
    column = fixture["expandedColumn"]
    schema, table, name = expanded_column(".".join(str(column.get(key, "")) for key in ("schema", "table", "column")))
    identifier = str(uuid.UUID(column["id"]))
    if not re.fullmatch(r"retained-[0-9a-f]{32}", column["value"]):
        raise RuntimeError("Unexpected expanded-column sentinel")
    value = column["value"]
    def query(sql):
        return ops.compose(environment, "exec", "-T", "postgres", "psql", "-X", "-v", "ON_ERROR_STOP=1", "-U", "studyroom_bootstrap", "-d", "studyroom", "-At", "-c", sql, capture=True).stdout.strip()
    nullable = query(f"SELECT is_nullable FROM information_schema.columns WHERE table_schema='{schema}' AND table_name='{table}' AND column_name='{name}'")
    if nullable != "YES":
        raise RuntimeError("Compatibility probe requires the newly added nullable column")
    if initialize:
        query(f"UPDATE {schema}.{table} SET {name}='{value}' WHERE id='{identifier}'")
    if query(f"SELECT {name} FROM {schema}.{table} WHERE id='{identifier}'") != value:
        raise RuntimeError("Expanded-column sentinel was lost or changed")


def switch_images(environment, manifest, config):
    overrides = {name.upper() + "_IMAGE": image["ref"] for name, image in manifest["images"].items()}
    overrides.update(RELEASE_ID=manifest["releaseId"], RELEASE_COMMIT=manifest["commit"])
    for key in ("POSTGRES_IMAGE", "MAILPIT_IMAGE"):
        if key in config:
            overrides[key] = config[key]
    ops.compose(environment, "stop", "-t", "60", "core", "repair", "web")
    ops.compose(environment, "up", "-d", "--wait", "--wait-timeout", "180", "core", "repair", "web", overrides=overrides)


def verify(environment, source_path, target_path, fixture_path, output):
    state, config = sandbox(environment)
    source = ops.verify_manifest(source_path)
    target = ops.verify_manifest(target_path, allow_older_schema=True)
    fixture_path, output = Path(fixture_path).resolve(), Path(output).resolve()
    if fixture_path.stat().st_mode & 0o077:
        raise RuntimeError("Session fixture permissions must be 0600")
    fixture = json.loads(fixture_path.read_text())
    if fixture.get("baselineManifestSha256") != ops.object_digest(target):
        raise RuntimeError("The session fixture was not captured against the target V1 manifest")
    for key in ("cookie", "applicantCookie"):
        if not fixture.get(key) or any(c in fixture[key] for c in "\r\n"):
            raise RuntimeError("Invalid private session fixture")
    if output.exists():
        raise RuntimeError("Do not overwrite a previous compatibility proof")
    client = Client(config["PUBLIC_BASE_URL"], fixture["cookie"])
    applicant = Client(config["PUBLIC_BASE_URL"], fixture["applicantCookie"])
    def actor(probe):
        return applicant if probe.get("actor") == "applicant" else client
    flags = [state / "control/maintenance", state / "control/jobs-paused"]
    with ops.environment_lock(environment):
        if any(path.exists() for path in flags):
            raise RuntimeError("An existing maintenance window must not be modified by the rehearsal")
        versions_and_images(environment, source)
        before = ops.database_inventory(environment)
        if not any(row.get("success") is True for row in before["migrations"]):
            raise RuntimeError("No applied migration inventory found")
        assert_subset(client.call("GET", "/api/v1/auth/me"), {"userId": fixture["userId"]}, "V1 session after V2 upgrade")
        assert_subset(applicant.call("GET", "/api/v1/auth/me"), {"userId": fixture["applicantId"]}, "V1 applicant session after V2 upgrade")
        for probe in fixture["reads"]:
            assert_subset(actor(probe).call("GET", probe["path"]), probe["expected"], "pre-rehearsal business data")
        expanded_value(environment, fixture, initialize=True)
        try:
            for path in flags:
                path.touch()
            ops.wait_status(config["PUBLIC_BASE_URL"], 503)
            ops.wait_drained(environment)
            switch_images(environment, target, config)
            versions_and_images(environment, target)
            flags[0].unlink()
            ops.wait_status(config["PUBLIC_BASE_URL"], 200)
            assert_subset(client.call("GET", "/api/v1/auth/me"), {"userId": fixture["userId"]}, "old session on core")
            assert_subset(applicant.call("GET", "/api/v1/auth/me"), {"userId": fixture["applicantId"]}, "old applicant session on core")
            client.call("GET", "/api/v1/repairs?view=mine")
            for probe in fixture["reads"]:
                assert_subset(actor(probe).call("GET", probe["path"]), probe["expected"], "business data on old images")
            for write in fixture["writes"]:
                if not re.fullmatch(r"/api/v1/(reservations|repairs|room-applications)/[0-9a-f-]{36}/cancel", write["path"]):
                    raise RuntimeError("Only isolated reservation/repair cancellation probes are accepted")
                assert_subset(actor(write).call("POST", write["path"], write["body"]), write["expected"], "old application write")
                assert_subset(actor(write).call("GET", write["path"].rsplit("/", 1)[0]), write["expected"], "persisted old application write")
            expanded_value(environment, fixture)
            if ops.database_inventory(environment) != before:
                raise RuntimeError("Application-only switch unexpectedly modified PostgreSQL/Flyway state")
            # Return the isolated project to B before producing permission to perform the real rollback.
            flags[0].touch()
            ops.wait_status(config["PUBLIC_BASE_URL"], 503)
            ops.wait_drained(environment)
            switch_images(environment, source, config)
            versions_and_images(environment, source)
            flags[0].unlink()
            ops.wait_status(config["PUBLIC_BASE_URL"], 200)
            flags[1].unlink()
            proof = {"formatVersion": 2, "result": "passed", "createdAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                     "fromCommit": source["commit"], "toCommit": target["commit"],
                     "fromManifestSha256": ops.object_digest(source), "toManifestSha256": ops.object_digest(target),
                     "fromImages": {k: v["id"] for k, v in source["images"].items()},
                     "toImages": {k: v["id"] for k, v in target["images"].items()},
                     "database": before, "databaseSha256": ops.object_digest(before),
                     "sandboxProject": config["COMPOSE_PROJECT_NAME"],
                     "checks": {name: True for name in ("threeImageIds", "threeVersions", "oldSessionCore", "oldSessionRepair", "businessData", "oldVersionWrite", "expandedColumnPreserved")}}
            output.parent.mkdir(parents=True, exist_ok=True)
            ops.write_json(output, proof)
        except Exception:
            for path in flags:
                path.touch()
            raise
    print(f"Verified compatibility proof: {output}; no cookies or secrets are included.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    first = sub.add_parser("capture", help="Create real V1 business records and a private pre-upgrade session fixture")
    first.add_argument("environment", choices=["dev", "test"])
    first.add_argument("manifest", type=Path)
    first.add_argument("fixture", type=Path)
    first.add_argument("--expanded-column", required=True)
    check = sub.add_parser("verify", help="Run old applications against expanded schema, restore B, then write a bound proof")
    check.add_argument("environment", choices=["dev", "test"])
    check.add_argument("from_manifest", type=Path)
    check.add_argument("to_manifest", type=Path)
    check.add_argument("fixture", type=Path)
    check.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.command == "capture":
        capture(args.environment, args.manifest, args.fixture, args.expanded_column)
    else:
        verify(args.environment, args.from_manifest, args.to_manifest, args.fixture, args.output)


if __name__ == "__main__":
    main()
