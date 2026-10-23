#!/usr/bin/env python3
"""REQ-ENG-05/07: create the explicitly authorized course accounts through real APIs."""
import argparse
import contextlib
import fcntl
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid

# The account prefix is lower-case because the application normalizes usernames.
ACCOUNTS = [
    ("student", "课程演示·计算机学生", "CS", ["STUDENT"]),
    ("student2", "课程演示·人文学生", "ART", ["STUDENT"]),
    ("applicant", "课程演示·整室申请人", "CS", ["STUDENT", "ROOM_APPLICANT"]),
    ("dept-approver", "课程演示·院系审批员", "SYSTEM", ["DEPT_APPROVER"]),
    ("space-approver", "课程演示·场地审批员", "SYSTEM", ["SPACE_APPROVER"]),
    ("dispatcher", "课程演示·维修调度员", "SYSTEM", ["PROPERTY_DISPATCHER"]),
    ("repairer", "课程演示·维修人员", "SYSTEM", ["REPAIRER"]),
    ("property-admin", "课程演示·物业验收员", "SYSTEM", ["PROPERTY_ADMIN"]),
    ("room-admin", "课程演示·教室管理员", "SYSTEM", ["ROOM_ADMIN"]),
    ("operator", "课程演示·运营统计员", "SYSTEM", ["OPERATOR"]),
    ("admin", "课程演示·账号与权限管理员", "SYSTEM", ["SYSTEM_ADMIN"]),
]
ROOMS = [("SHARED", "课程演示·共享自习室", None),
         ("CS", "课程演示·计算机自习室", "CS"),
         ("ART", "课程演示·人文自习室", "ART")]
OPENING = [{"weekday": day, "startMinute": 480, "endMinute": 1320} for day in range(1, 8)]


def origin(value):
    parsed = urllib.parse.urlsplit(value)
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
            or parsed.query or parsed.fragment or parsed.path not in ("", "/")):
        raise RuntimeError("An explicit HTTPS origin is required")
    parsed.port  # Reject invalid ports before reading credentials.
    return value.rstrip("/")


def private_file(path):
    if path.is_symlink() or not path.is_file() or path.stat().st_mode & 0o077:
        raise RuntimeError("Credential and journal files must be owner-only regular files")
    return path


def save_private(path, value):
    if path.is_symlink():
        raise RuntimeError("Refusing a private output symlink")
    fd, name = tempfile.mkstemp(prefix=".course-", dir=path.parent)
    try:
        with os.fdopen(fd, "w") as stream:
            json.dump(value, stream, indent=2, ensure_ascii=False)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, path)
    finally:
        Path(name).unlink(missing_ok=True)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        raise RuntimeError("API redirect rejected; credentials stay on the authorized origin")


class Client:
    def __init__(self, base, username, password):
        self.base = origin(base)
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar), NoRedirect())
        self.csrf = self.call("GET", "/api/v1/auth/csrf")["csrfToken"]
        self.me = self.call("POST", "/api/v1/auth/login", {"username": username, "password": password})
        self.csrf = self.call("GET", "/api/v1/auth/csrf")["csrfToken"]

    def call(self, method, path, body=None, key=None):
        if not path.startswith("/api/v1/") or "://" in path:
            raise RuntimeError("Unexpected API path")
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if method != "GET":
            headers["X-CSRF-TOKEN"] = self.csrf
            headers["Idempotency-Key"] = key or str(uuid.uuid4())
        request = urllib.request.Request(self.base + path, method=method, headers=headers,
                                         data=None if body is None else json.dumps(body).encode())
        try:
            with self.opener.open(request, timeout=30) as response:
                raw = response.read()
                return json.loads(raw)["data"] if raw else None
        except urllib.error.HTTPError as error:
            # Never include server response bodies: they could echo submitted credentials.
            status = error.code
            error.close()
            raise RuntimeError(f"Course setup {method} {path} failed with HTTP {status}") from None
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, KeyError):
            raise RuntimeError("Course setup API response unavailable; rerun with the same journal") from None

    def items(self, path):
        found = []
        for page in range(1, 101):
            separator = "&" if "?" in path else "?"
            result = self.call("GET", path + separator + urllib.parse.urlencode({"page": page, "size": 100}))
            found.extend(result["items"])
            if len(found) >= result["total"]:
                return found
        raise RuntimeError("Course setup listing exceeded its safety limit")


def normalized(bindings):
    return sorted((str(item["roleId"]), sorted(
        (scope["scopeType"], scope.get("departmentId") or "", scope.get("roomId") or "")
        for scope in item["scopes"])) for item in bindings)


class CourseSetup:
    def __init__(self, base, state, admin, factory=Client):
        self.base, self.state, self.admin, self.factory = base, state, admin, factory
        self.directory = state / "course-demo"
        self.journal_path = self.directory / "journal.json"
        if self.journal_path.exists():
            self.journal = json.loads(private_file(self.journal_path).read_text())
            if self.journal.get("baseUrl") != base or self.journal.get("formatVersion") != 1:
                raise RuntimeError("Existing course journal belongs to another target")
        else:
            self.journal = {"formatVersion": 1, "baseUrl": base, "resources": {}, "operations": {}, "accounts": {}}
            self.save()

    def save(self):
        save_private(self.journal_path, self.journal)

    def find(self, client, endpoint, field, value):
        found = [item for item in client.items(endpoint) if item.get(field) == value]
        if len(found) > 1:
            raise RuntimeError("Ambiguous course resource identity")
        return found[0] if found else None

    def ensure(self, client, endpoint, key, payload, *, field="code"):
        existing = self.find(client, endpoint, field, payload[field])
        record = self.journal["resources"].get(key)
        if record is None:
            if existing is not None:
                raise RuntimeError("Refusing to adopt a pre-existing course name without its setup journal")
            record = {"id": None, "payload": payload, "idempotencyKey": str(uuid.uuid4())}
            self.journal["resources"][key] = record
            self.save()  # Password/key survive a lost create response.
        elif record["payload"] != payload:
            raise RuntimeError("Course setup definition differs from its saved operation")
        if record["id"] is None:
            # The journal may have been saved before POST ever reached the server.
            # A matching name therefore proves nothing: only the original create
            # or its idempotent replay can establish which object this run owns.
            created = client.call("POST", endpoint, record["payload"], record["idempotencyKey"])
            existing = self.find(client, endpoint, field, payload[field])
            if existing is None or existing["id"] != created["id"]:
                raise RuntimeError("Course name differs from the original creation response")
            record["id"] = created["id"]
            self.save()
        elif existing is None:
            raise RuntimeError("An existing course resource disappeared; no automatic replacement")
        if existing["id"] != record["id"]:
            raise RuntimeError("Course name now identifies an unexpected resource")
        for field, expected in payload.items():
            if field == "password":
                continue
            if existing.get(field) != expected:
                raise RuntimeError("Existing course resource was changed; manual review required")
        if existing.get("enabled") is not True:
            raise RuntimeError("Existing course resource was disabled; it will not be re-enabled")
        record["id"] = existing["id"]
        self.save()
        return existing

    def mutate(self, client, key, method, path, payload):
        record = self.journal["operations"].get(key)
        if record and record.get("done"):
            raise RuntimeError("Completed course configuration drifted; refusing to overwrite later changes")
        if record is None:
            record = {"method": method, "path": path, "payload": payload,
                      "idempotencyKey": str(uuid.uuid4()), "done": False}
            self.journal["operations"][key] = record
            self.save()
        if record["method"] != method or record["path"] != path:
            raise RuntimeError("Course operation identity changed")
        result = client.call(method, path, record["payload"], record["idempotencyKey"])
        # An idempotent response describes the original write, not necessarily
        # current state. Re-read before accepting a replay after an interruption.
        if path.endswith("/role-bindings"):
            user = self.find(client, "/api/v1/users", "id", path.split("/")[-2])
            matches = user is not None and normalized(user["roleBindings"]) == normalized(record["payload"]["bindings"])
        elif path.endswith("/opening-rules"):
            room = client.call("GET", path.removesuffix("/opening-rules"))
            matches = room["openingRules"] == record["payload"]["rules"]
        else:
            raise RuntimeError("Unexpected course mutation verification path")
        if not matches:
            raise RuntimeError("Current course configuration differs from the original operation; manual review required")
        record["done"] = True
        self.save()
        return result

    def mark_done(self, key):
        self.journal["operations"].setdefault(key, {})["done"] = True
        self.save()

    def binding(self, suffix, desired, stage="final"):
        username = "course-" + suffix
        user = self.find(self.admin, "/api/v1/users", "username", username)
        key = f"bindings/{suffix}/{stage}"
        if normalized(user["roleBindings"]) == normalized(desired):
            self.mark_done(key)
            return
        self.mutate(self.admin, key, "PUT", "/api/v1/users/" + user["id"] + "/role-bindings",
                    {"expectedVersion": user["version"], "bindings": desired})

    def execute(self):
        version = self.admin.call("GET", "/api/v1/version")
        system = next((d for d in self.admin.items("/api/v1/departments") if d["code"] == "SYSTEM"), None)
        if system is None:
            raise RuntimeError("Reference seed SYSTEM department is missing")
        departments = {"SYSTEM": system["id"]}
        for code, name in (("CS", "课程演示·计算机学院"), ("ART", "课程演示·人文学院")):
            departments[code] = self.ensure(self.admin, "/api/v1/departments", "department/" + code,
                                            {"code": "COURSE-" + code, "name": name})["id"]
        campus = self.ensure(self.admin, "/api/v1/campuses", "campus",
                             {"code": "COURSE-MAIN", "name": "课程演示·主校区"})
        building = self.ensure(self.admin, "/api/v1/buildings", "building",
                               {"code": "COURSE-LIBRARY", "name": "课程演示·图书楼", "campusId": campus["id"]})
        role_ids = {r["code"]: r["id"] for r in self.admin.items("/api/v1/roles")}
        if not {r for _, _, _, codes in ACCOUNTS for r in codes} <= role_ids.keys():
            raise RuntimeError("Required reference roles are missing")
        for suffix, display, department, codes in ACCOUNTS:
            record = self.journal["accounts"].get(suffix)
            if record is None:
                record = {"username": "course-" + suffix, "password": secrets.token_urlsafe(24),
                          "displayName": display, "roles": codes}
                self.journal["accounts"][suffix] = record
                self.save()
            self.ensure(self.admin, "/api/v1/users", "user/" + suffix, {
                "username": record["username"], "password": record["password"], "displayName": display,
                "email": record["username"] + "@studyroom.test", "schoolNo": "COURSE-" + suffix.upper(),
                "departmentId": departments[department],
            }, field="username")
        # SYSTEM_ADMIN intentionally has no room:manage permission. A separate course
        # operator creates only the course resources, then loses its temporary broad scope.
        final = self.journal["operations"].get("bindings/room-admin/final")
        if final is not None:
            # Once narrowing has started, never restore the construction scope.
            # A committed final PUT can lose its response before done is saved.
            room_ids = [self.journal["resources"].get("room/" + code, {}).get("id")
                        for code, _, _ in ROOMS]
            if not all(room_ids) or len(set(room_ids)) != len(ROOMS):
                raise RuntimeError("Final course scope requires all original room identities")
            desired = [{"roleId": role_ids["ROOM_ADMIN"], "scopes": [
                {"scopeType": "ROOM", "roomId": room_id} for room_id in room_ids]}]
            if ("payload" in final
                    and normalized(final["payload"].get("bindings", [])) != normalized(desired)):
                raise RuntimeError("Saved final course scope differs from the original room identities")
            self.binding("room-admin", desired)
        else:
            self.binding("room-admin", [{"roleId": role_ids["ROOM_ADMIN"], "scopes": [
                {"scopeType": "DEPARTMENT", "departmentId": departments[code]} for code in ("CS", "ART")
            ] + [{"scopeType": "SHARED"}]}], "construction")
        room_account = self.journal["accounts"]["room-admin"]
        room_client = self.factory(self.base, room_account["username"], room_account["password"])
        rooms = {}
        for code, name, department in ROOMS:
            payload = {"code": "COURSE-" + code, "name": name, "buildingId": building["id"],
                       "departmentId": departments[department] if department else None}
            room = self.ensure(room_client, "/api/v1/rooms", "room/" + code, payload)
            rooms[code] = room["id"]
            for number in range(1, 11):
                self.ensure(room_client, f'/api/v1/rooms/{room["id"]}/seats', f"seat/{code}/{number}",
                            {"code": f"{number:02d}", "hasPower": number % 2 == 0})
            actual = room_client.call("GET", "/api/v1/rooms/" + room["id"])
            key = "opening/" + code
            if actual["openingRules"] == OPENING:
                self.mark_done(key)
            else:
                self.mutate(room_client, key, "PUT", "/api/v1/rooms/" + room["id"] + "/opening-rules",
                            {"expectedVersion": actual["version"], "rules": OPENING})
        room_scopes = [{"scopeType": "ROOM", "roomId": room} for room in rooms.values()]
        for suffix, _, _, codes in ACCOUNTS:
            if suffix in ("student", "student2", "applicant", "repairer"):
                scopes = [{"scopeType": "SELF"}]
            elif suffix == "dept-approver":
                scopes = [{"scopeType": "DEPARTMENT", "departmentId": departments[d]} for d in ("CS", "ART")]
            elif suffix == "admin":
                scopes = [{"scopeType": "ALL"}]
            else:
                scopes = room_scopes
            self.binding(suffix, [{"roleId": role_ids[code], "scopes": scopes} for code in codes])
        # Verify each real login and granted roles without creating business orders.
        for suffix, _, _, codes in ACCOUNTS:
            account = self.journal["accounts"][suffix]
            client = self.factory(self.base, account["username"], account["password"])
            if set(client.me.get("roles", [])) != set(codes):
                raise RuntimeError("Course account login did not return the expected roles")
        self.journal.update(completed=True, applicationVersion=version, rooms=rooms)
        self.save()
        output = self.directory / "course-accounts.json"
        save_private(output, {"formatVersion": 1, "baseUrl": self.base, "applicationVersion": version,
                              "accounts": list(self.journal["accounts"].values()),
                              "rooms": [{"code": "COURSE-" + code, "name": name, "id": rooms[code], "seats": 10}
                                        for code, name, _ in ROOMS],
                              "opening": "Asia/Shanghai; every day 08:00–22:00",
                              "mail": "Mailpit only; no real external email delivery claimed"})
        return output


@contextlib.contextmanager
def target_lock(state, base):
    if state.is_symlink() or not state.is_dir() or state.stat().st_mode & 0o077:
        raise RuntimeError("Use the existing owner-only production state directory")
    config = dict(line.split("=", 1) for line in private_file(state / "compose.env").read_text().splitlines()
                  if line and not line.startswith("#"))
    if config.get("ENV_NAME") != "prod" or origin(config.get("PUBLIC_BASE_URL", "")) != base:
        raise RuntimeError("Explicit target URL must match the selected production environment")
    directory = state / "course-demo"
    if directory.is_symlink():
        raise RuntimeError("Refusing a course state symlink")
    directory.mkdir(mode=0o700, exist_ok=True)
    if directory.stat().st_mode & 0o077:
        raise RuntimeError("Course state directory must be owner-only")
    with contextlib.ExitStack() as stack:
        # Share the production deployment/backup lock throughout API initialization.
        for lock in (state / "deployment.lock", directory / "setup.lock"):
            if lock.is_symlink():
                raise RuntimeError("Refusing a course setup lock symlink")
            fd = os.open(lock, os.O_CREAT | os.O_RDWR, 0o600)
            stream = stack.enter_context(os.fdopen(fd, "a"))
            fcntl.flock(stream, fcntl.LOCK_EX)
        yield config


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--state-dir", type=Path, required=True)
    parser.add_argument("--apply", action="store_true", help="Authorize the real COURSE-prefixed API writes")
    args = parser.parse_args(argv)
    base = origin(args.base_url)
    if not args.apply:
        print(f"Dry run: {base}; 11 course accounts, 2 departments, 1 campus, 1 building, 3 rooms, 30 seats.")
        print("No network requests or file writes. Add --apply to initialize the explicitly selected production target.")
        return
    state = args.state_dir.absolute()
    with target_lock(state, base) as config:
        password = private_file(state / "secrets/admin_password").read_text().strip()
        admin = Client(base, config.get("ADMIN_USERNAME", ""), password)
        if "SYSTEM_ADMIN" not in admin.me.get("roles", []):
            raise RuntimeError("The supplied maintenance account is not a system administrator")
        output = CourseSetup(base, state, admin).execute()
        print("Course setup verified; credentials were written only to the private JSON file: " + str(output))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # RuntimeError messages above contain only controlled text/status, never API bodies.
        message = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        print("Course setup stopped: " + message, file=sys.stderr)
        sys.exit(1)
