"""Course initialization uses scoped APIs, persistent credentials and no default writes."""
import contextlib
import copy
import fcntl
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("course_setup", ROOT / "scripts/course-setup.py")
course = importlib.util.module_from_spec(spec)
spec.loader.exec_module(course)


class FakeApi:
    def __init__(self):
        self.rows = {"/api/v1/departments": [{"id": "system", "code": "SYSTEM", "name": "系统管理", "enabled": True}],
                     "/api/v1/users": [], "/api/v1/campuses": [], "/api/v1/buildings": [], "/api/v1/rooms": []}
        self.roles = {code: "role-" + code for _, _, _, codes in course.ACCOUNTS for code in codes}
        self.rows["/api/v1/roles"] = [{"id": value, "code": key} for key, value in self.roles.items()]
        self.writes = []
        self.requests = []
        self.results = {}
        self.me = {"roles": ["SYSTEM_ADMIN"]}
        self.lose_next_create = False
        self.lose_next_update = False

    def items(self, path):
        return copy.deepcopy(self.rows.get(path, []))

    def call(self, method, path, body=None, key=None):
        if method == "GET":
            if path == "/api/v1/version":
                return {"releaseId": "v0.2.0", "commit": "a" * 40}
            for row in self.rows["/api/v1/rooms"]:
                if path == "/api/v1/rooms/" + row["id"]:
                    return copy.deepcopy(row)
            raise AssertionError("Unexpected test GET")
        self.requests.append((method, path, key))
        if key in self.results:
            return copy.deepcopy(self.results[key])
        if method == "POST":
            field = "username" if path == "/api/v1/users" else "code"
            if any(row.get(field) == body[field] for row in self.rows.get(path, [])):
                raise RuntimeError("Test duplicate natural key")
        self.writes.append((method, path, copy.deepcopy(body)))
        if method == "POST":
            value = {**body, "id": "created-" + str(len(self.writes)), "enabled": True, "version": 0}
            if path == "/api/v1/users":
                value["roleBindings"] = []
            if path == "/api/v1/rooms":
                value["openingRules"] = []
            self.rows.setdefault(path, []).append(value)
            result = copy.deepcopy(value)
        elif path.endswith("/role-bindings"):
            uid = path.split("/")[-2]
            value = next(x for x in self.rows["/api/v1/users"] if x["id"] == uid)
            self_assert_version = value["version"]
            if self_assert_version != body["expectedVersion"]:
                raise RuntimeError("Test version conflict")
            value["roleBindings"] = copy.deepcopy(body["bindings"])
            value["version"] += 1
            result = copy.deepcopy(value)
        elif path.endswith("/opening-rules"):
            rid = path.split("/")[-2]
            value = next(x for x in self.rows["/api/v1/rooms"] if x["id"] == rid)
            value["openingRules"] = copy.deepcopy(body["rules"])
            value["version"] += 1
            result = copy.deepcopy(value)
        else:
            raise AssertionError("Unexpected test write")
        self.results[key] = result
        if method == "POST" and self.lose_next_create:
            self.lose_next_create = False
            raise RuntimeError("Test connection lost after committed create")
        if method == "PUT" and self.lose_next_update:
            self.lose_next_update = False
            raise RuntimeError("Test connection lost after committed update")
        return copy.deepcopy(result)

    def login(self, base, username, password):
        user = next(x for x in self.rows["/api/v1/users"] if x["username"] == username)
        if password != user["password"]:
            raise RuntimeError("Test password changed; no automatic reset")
        client = type("AuthenticatedFake", (), {})()
        client.items = self.items
        client.call = self.call
        client.me = {"roles": [next(code for code, rid in self.roles.items() if rid == b["roleId"])
                              for b in user["roleBindings"]]}
        return client


class CourseSetupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.state = Path(self.temp.name)
        (self.state / "course-demo").mkdir(mode=0o700)
        self.api = FakeApi()

    def setup(self):
        return course.CourseSetup("https://49.51.193.169", self.state, self.api, self.api.login)

    def test_dry_run_never_contacts_target_or_creates_output(self):
        missing = self.state / "missing-production"
        output = io.StringIO()
        with patch.object(course, "Client") as client, contextlib.redirect_stdout(output):
            course.main(["--base-url", "https://49.51.193.169", "--state-dir", str(missing)])
            client.assert_not_called()
        self.assertFalse(missing.exists())
        self.assertIn("No network requests or file writes", output.getvalue())

    def test_all_roles_resources_scopes_and_private_credentials_then_zero_write_rerun(self):
        path = self.setup().execute()
        data = json.loads(path.read_text())
        self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        self.assertEqual((path.parent / "journal.json").stat().st_mode & 0o777, 0o600)
        self.assertEqual(len(data["accounts"]), 11)
        self.assertEqual(len({x["password"] for x in data["accounts"]}), 11)
        self.assertTrue(all(len(x["password"]) >= 32 for x in data["accounts"]))
        self.assertEqual(len(self.api.rows["/api/v1/rooms"]), 3)
        self.assertEqual(sum(len(rows) for endpoint, rows in self.api.rows.items() if endpoint.endswith("/seats")), 30)
        users = {x["username"]: x for x in self.api.rows["/api/v1/users"]}
        room_scopes = users["course-room-admin"]["roleBindings"][0]["scopes"]
        self.assertEqual({x["scopeType"] for x in room_scopes}, {"ROOM"})
        self.assertEqual(len(room_scopes), 3)
        self.assertEqual({x["scopeType"] for x in users["course-dept-approver"]["roleBindings"][0]["scopes"]},
                         {"DEPARTMENT"})
        self.assertTrue(all(u["schoolNo"].startswith("COURSE-") for u in users.values()))
        self.assertTrue(all("DEMO-" not in u["schoolNo"] for u in users.values()))
        before = len(self.api.writes)
        passwords = {a["username"]: a["password"] for a in data["accounts"]}
        repeated = json.loads(self.setup().execute().read_text())
        self.assertEqual(before, len(self.api.writes))
        self.assertEqual(passwords, {a["username"]: a["password"] for a in repeated["accounts"]})

    def interrupt_final_room_binding(self, *, before_commit=False):
        original = self.api.call
        def interrupted(method, path, body=None, key=None):
            if method == "PUT" and path.endswith("/role-bindings"):
                user = next(u for u in self.api.rows["/api/v1/users"] if u["id"] == path.split("/")[-2])
                if (user["username"] == "course-room-admin"
                        and body["bindings"][0]["scopes"][0]["scopeType"] == "ROOM"):
                    if before_commit:
                        raise RuntimeError("Test final scope request never sent")
                    self.api.lose_next_update = True
            return original(method, path, body, key)
        with patch.object(self.api, "call", side_effect=interrupted):
            with self.assertRaisesRegex(RuntimeError, "never sent" if before_commit else "lost"):
                self.setup().execute()
        setup = self.setup()
        self.assertTrue(setup.journal["operations"]["bindings/room-admin/construction"]["done"])
        self.assertFalse(setup.journal["operations"]["bindings/room-admin/final"]["done"])
        return setup

    def test_lost_final_scope_response_resumes_without_restoring_construction(self):
        self.interrupt_final_room_binding()
        before = len(self.api.writes)
        path = self.setup().execute()
        self.assertTrue(path.exists())
        repeated = self.api.writes[before:]
        user = next(u for u in self.api.rows["/api/v1/users"] if u["username"] == "course-room-admin")
        self.assertFalse(any(p == "/api/v1/users/" + user["id"] + "/role-bindings" for _, p, _ in repeated))
        expected = {r["id"] for r in self.api.rows["/api/v1/rooms"]}
        self.assertEqual({s["roomId"] for s in user["roleBindings"][0]["scopes"]}, expected)
        self.assertTrue(self.setup().journal["operations"]["bindings/room-admin/final"]["done"])
        before = len(self.api.writes)
        self.setup().execute()
        self.assertEqual(len(self.api.writes), before)

    def test_saved_unsent_final_scope_resumes_using_original_key_and_rooms(self):
        setup = self.interrupt_final_room_binding(before_commit=True)
        final = setup.journal["operations"]["bindings/room-admin/final"]
        before = len(self.api.writes)
        self.setup().execute()
        self.assertIn(("PUT", final["path"], final["idempotencyKey"]), self.api.requests)
        changes = [body for _, path, body in self.api.writes[before:] if path == final["path"]]
        self.assertEqual(changes, [final["payload"]])
        self.assertTrue(self.setup().journal["operations"]["bindings/room-admin/final"]["done"])

    def test_final_scope_recovery_rejects_missing_or_changed_original_room_ids(self):
        setup = self.interrupt_final_room_binding()
        before = len(self.api.writes)
        for replacement, message in ((None, "requires all original"), ("unowned-room", "differs from the original")):
            with self.subTest(replacement=replacement):
                setup.journal["resources"]["room/CS"]["id"] = replacement
                setup.save()
                with self.assertRaisesRegex(RuntimeError, message):
                    self.setup().execute()
                self.assertEqual(len(self.api.writes), before)
                self.assertFalse(self.setup().journal["operations"]["bindings/room-admin/final"]["done"])

    def test_lost_final_scope_response_then_manual_drift_remains_fail_closed(self):
        self.interrupt_final_room_binding()
        user = next(u for u in self.api.rows["/api/v1/users"] if u["username"] == "course-room-admin")
        user["roleBindings"][0]["scopes"] = [{"scopeType": "ALL"}]
        user["version"] += 1
        before = len(self.api.writes)
        with self.assertRaisesRegex(RuntimeError, "Current course configuration differs"):
            self.setup().execute()
        self.assertEqual(len(self.api.writes), before)
        self.assertEqual(user["roleBindings"][0]["scopes"], [{"scopeType": "ALL"}])
        self.assertFalse(self.setup().journal["operations"]["bindings/room-admin/final"]["done"])

    def test_committed_create_with_lost_response_is_adopted_only_from_journal(self):
        self.api.lose_next_create = True
        with self.assertRaisesRegex(RuntimeError, "lost"):
            self.setup().execute()
        self.assertEqual(len(self.api.rows["/api/v1/departments"]), 2)
        pending = self.setup().journal["resources"]["department/CS"]["idempotencyKey"]
        self.setup().execute()
        self.assertEqual(len(self.api.rows["/api/v1/departments"]), 3)
        self.assertEqual(len(self.api.rows["/api/v1/users"]), 11)
        self.assertEqual(sum(key == pending for _, _, key in self.api.requests), 2)

    def test_unowned_name_collision_is_not_adopted(self):
        self.api.rows["/api/v1/departments"].append({"id": "unowned", "code": "COURSE-CS",
                                                  "name": "课程演示·计算机学院", "enabled": True})
        with self.assertRaisesRegex(RuntimeError, "pre-existing"):
            self.setup().execute()
        self.assertEqual([], self.api.writes)

    def test_saved_but_unsent_create_does_not_adopt_external_same_name(self):
        setup = self.setup()
        payload = {"username": "course-admin", "displayName": "课程演示·账号与权限管理员",
                   "password": "test-only-long-password", "email": "course-admin@studyroom.test",
                   "schoolNo": "COURSE-ADMIN", "departmentId": "system"}
        original = self.api.call
        def not_sent(method, *args, **kwargs):
            if method == "POST":
                raise RuntimeError("Test request never sent")
            return original(method, *args, **kwargs)
        with patch.object(self.api, "call", side_effect=not_sent):
            with self.assertRaisesRegex(RuntimeError, "never sent"):
                setup.ensure(self.api, "/api/v1/users", "user/admin", payload, field="username")
        saved = self.setup().journal["resources"]["user/admin"]
        self.assertIsNone(saved["id"])
        self.api.rows["/api/v1/users"].append({**payload, "password": "another-external-password",
                                            "id": "external-user", "enabled": True,
                                            "version": 0, "roleBindings": []})
        with self.assertRaisesRegex(RuntimeError, "duplicate natural key"):
            self.setup().ensure(self.api, "/api/v1/users", "user/admin", payload, field="username")
        self.assertEqual(self.api.requests[-1], ("POST", "/api/v1/users", saved["idempotencyKey"]))
        self.assertIsNone(self.setup().journal["resources"]["user/admin"]["id"])
        self.assertEqual(self.api.rows["/api/v1/users"][0]["roleBindings"], [])
        self.assertFalse(any(method == "PUT" for method, _, _ in self.api.requests))

    def test_lost_binding_response_then_scope_drift_is_not_accepted_as_done(self):
        self.api.rows["/api/v1/users"].append({"id": "user-1", "username": "course-room-admin",
                                             "version": 0, "roleBindings": []})
        setup = self.setup()
        desired = [{"roleId": self.api.roles["ROOM_ADMIN"],
                    "scopes": [{"scopeType": "ROOM", "roomId": "course-room"}]}]
        payload = {"expectedVersion": 0, "bindings": desired}
        self.api.lose_next_update = True
        with self.assertRaisesRegex(RuntimeError, "lost"):
            setup.mutate(self.api, "binding-test", "PUT", "/api/v1/users/user-1/role-bindings", payload)
        self.api.rows["/api/v1/users"][0]["roleBindings"][0]["scopes"] = [{"scopeType": "ALL"}]
        self.api.rows["/api/v1/users"][0]["version"] += 1
        with self.assertRaisesRegex(RuntimeError, "Current course configuration differs"):
            self.setup().mutate(self.api, "binding-test", "PUT", "/api/v1/users/user-1/role-bindings", payload)
        self.assertFalse(self.setup().journal["operations"]["binding-test"]["done"])
        self.assertEqual(len(self.api.writes), 1)
        self.assertEqual(self.api.rows["/api/v1/users"][0]["roleBindings"][0]["scopes"], [{"scopeType": "ALL"}])

    def test_lost_opening_response_then_manual_drift_is_not_accepted_as_done(self):
        self.api.rows["/api/v1/rooms"].append({"id": "room-1", "version": 0, "openingRules": []})
        setup = self.setup()
        payload = {"expectedVersion": 0, "rules": course.OPENING}
        self.api.lose_next_update = True
        with self.assertRaisesRegex(RuntimeError, "lost"):
            setup.mutate(self.api, "opening-test", "PUT", "/api/v1/rooms/room-1/opening-rules", payload)
        changed = [{"weekday": 1, "startMinute": 600, "endMinute": 660}]
        self.api.rows["/api/v1/rooms"][0]["openingRules"] = changed
        with self.assertRaisesRegex(RuntimeError, "Current course configuration differs"):
            self.setup().mutate(self.api, "opening-test", "PUT", "/api/v1/rooms/room-1/opening-rules", payload)
        self.assertFalse(self.setup().journal["operations"]["opening-test"]["done"])
        self.assertEqual(len(self.api.writes), 1)
        self.assertEqual(self.api.rows["/api/v1/rooms"][0]["openingRules"], changed)

    def test_disabled_or_edited_accounts_are_not_reenabled_or_reset(self):
        self.setup().execute()
        before = len(self.api.writes)
        self.api.rows["/api/v1/users"][0]["enabled"] = False
        with self.assertRaisesRegex(RuntimeError, "disabled"):
            self.setup().execute()
        self.assertEqual(before, len(self.api.writes))

    def test_api_initialization_holds_the_production_deployment_lock(self):
        config = self.state / "compose.env"
        config.write_text("ENV_NAME=prod\nPUBLIC_BASE_URL=https://49.51.193.169\n")
        config.chmod(0o600)
        self.state.chmod(0o700)
        with course.target_lock(self.state, "https://49.51.193.169"):
            with (self.state / "deployment.lock").open("a") as competing:
                with self.assertRaises(BlockingIOError):
                    fcntl.flock(competing, fcntl.LOCK_EX | fcntl.LOCK_NB)
        with (self.state / "deployment.lock").open("a") as competing:
            fcntl.flock(competing, fcntl.LOCK_EX | fcntl.LOCK_NB)

    def test_insecure_url_and_wrong_state_fail_before_network(self):
        for value in ("http://49.51.193.169", "https://name:secret@example.com", "https://example.com/path"):
            with self.assertRaises(RuntimeError):
                course.origin(value)
        config = self.state / "compose.env"
        config.write_text("ENV_NAME=prod\nPUBLIC_BASE_URL=https://example.com\n")
        config.chmod(0o600)
        self.state.chmod(0o700)
        with self.assertRaisesRegex(RuntimeError, "match"):
            with course.target_lock(self.state, "https://49.51.193.169"):
                self.fail("Unexpected lock grant")
        alias = self.state / "alias"
        alias.symlink_to(config)
        with self.assertRaises(RuntimeError):
            course.private_file(alias)


if __name__ == "__main__":
    unittest.main()
