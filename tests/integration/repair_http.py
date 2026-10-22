#!/usr/bin/env python3
"""REQ-M3/X behavior tests through real core + repair HTTP and PostgreSQL.

Set STUDYROOM_CORE_URL/STUDYROOM_REPAIR_URL (or STUDYROOM_BASE_URL) and STUDYROOM_TEST_PASSWORD.
Requires reference+demo seed in an isolated local/test environment. No database
writes, service mocks, or test-only business endpoints are used by this suite.
"""
import http.cookiejar
import json
import os
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

CORE = os.environ.get("STUDYROOM_CORE_URL", os.environ.get("STUDYROOM_BASE_URL", "http://localhost:8081")).rstrip("/")
REPAIR = os.environ.get("STUDYROOM_REPAIR_URL", os.environ.get("STUDYROOM_BASE_URL", "http://localhost:8082")).rstrip("/")


class Client:
    def __init__(self, username, password):
        self.username = username
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
        self.csrf = self.call("GET", "/api/v1/auth/csrf")[1]["data"]["csrfToken"]
        status, data = self.call("POST", "/api/v1/auth/login", {"username": username, "password": password})
        if status != 200:
            raise AssertionError(f"Login {username} returned {status}: {data}")
        self.me = data["data"]
        self.csrf = self.call("GET", "/api/v1/auth/csrf")[1]["data"]["csrfToken"]

    def call(self, method, path, body=None, key=None, csrf=True):
        base = REPAIR if path.startswith("/api/v1/repairs") else CORE
        headers = {"Content-Type": "application/json"}
        if method not in ("GET", "HEAD"):
            headers["Idempotency-Key"] = key or str(uuid.uuid4())
            if csrf and hasattr(self, "csrf"):
                headers["X-CSRF-TOKEN"] = self.csrf
        req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
        try:
            with self.opener.open(req, timeout=20) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else {}
        except urllib.error.HTTPError as error:
            raw = error.read()
            error.close()
            return error.code, json.loads(raw) if raw else {}

    def get(self, path):
        status, body = self.call("GET", path)
        if status != 200:
            raise AssertionError(f"{self.username}: GET {path}: {status}: {body}")
        return body["data"]


class RepairHttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        password = os.environ.get("STUDYROOM_TEST_PASSWORD")
        if not password:
            raise RuntimeError("STUDYROOM_TEST_PASSWORD must name the isolated demo password; tests are not silently skipped")
        cls.student = Client("student", password)
        system_admin = Client("admin", password)
        test_username = "repairtest-" + uuid.uuid4().hex[:12]
        status, user = system_admin.call("POST", "/api/v1/users", {"username": test_username, "password": password, "email": test_username + "@example.invalid", "displayName": "维修验收测试账号", "departmentId": cls.student.me["departmentId"], "schoolNo": test_username})
        if status != 201:
            raise AssertionError(f"Create test student: {status}: {user}")
        student_role = next(role["id"] for role in system_admin.get("/api/v1/roles")["items"] if role["code"] == "STUDENT")
        status, assigned = system_admin.call("PUT", "/api/v1/users/" + user["data"]["id"] + "/role-bindings", {"expectedVersion": user["data"]["version"], "bindings": [{"roleId": student_role, "scopes": []}]})
        if status != 200:
            raise AssertionError(f"Assign test student role: {status}: {assigned}")
        cls.student = Client(test_username, password)
        cls.other = Client("student2", password)
        cls.dispatcher = Client("dispatcher", password)
        cls.worker = Client("repairer", password)
        cls.admin = Client("property-admin", password)
        room_admin = Client("room-admin", password)
        building = room_admin.get("/api/v1/buildings?page=1&size=100")["items"][0]
        code = "REPAIR-" + uuid.uuid4().hex[:12].upper()
        status, created = room_admin.call("POST", "/api/v1/rooms", {"buildingId": building["id"], "code": code, "name": "HTTP 维修验收教室", "departmentId": None})
        if status != 201:
            raise AssertionError(f"Create isolated repair test room: {status}: {created}")
        cls.room = created["data"]
        cls.room_id = cls.room["id"]
        status, opened = room_admin.call("PUT", "/api/v1/rooms/" + cls.room_id + "/opening-rules", {"expectedVersion": cls.room["version"], "rules": [{"weekday": day, "startMinute": 480, "endMinute": 1320} for day in range(1, 8)]})
        if status != 200:
            raise AssertionError(f"Open test room: {status}: {opened}")
        for number in range(1, 4):
            status, seat = room_admin.call("POST", "/api/v1/rooms/" + cls.room_id + "/seats", {"code": f"S{number}", "hasPower": True})
            if status != 201:
                raise AssertionError(f"Create test seat: {status}: {seat}")
        candidates = cls.dispatcher.get("/api/v1/repairs/assignable-workers?roomId=" + cls.room_id)["items"]
        cls.worker_id = next(r["userId"] for r in candidates if r["userId"] == cls.worker.me["userId"])

    def create(self, title="集成验收插座故障", key=None):
        payload = {"roomId": self.room_id, "title": title, "description": "真实 HTTP 集成测试：插座需要检查。"}
        status, body = self.student.call("POST", "/api/v1/repairs", payload, key)
        self.assertEqual(201, status, body)
        self.assertEqual("SUBMITTED", body["data"]["status"])
        return body["data"], payload

    def action(self, client, order, name, expected=200, **extra):
        current = client.get("/api/v1/repairs/" + order["id"])
        status, body = client.call("POST", "/api/v1/repairs/" + order["id"] + "/" + name, {"expectedVersion": current["version"], **extra})
        self.assertEqual(expected, status, body)
        return body["data"]

    def until(self, order, status):
        end = time.monotonic() + 100
        while time.monotonic() < end:
            current = self.student.get("/api/v1/repairs/" + order["id"])
            if current["status"] == status:
                return current
            time.sleep(1)
        self.fail(f"Order did not converge to {status}; last detail: {current}")

    def test_01_validation_privacy_and_idempotent_cancel(self):
        status, body = self.student.call("POST", "/api/v1/repairs", {"roomId": self.room_id, "title": "   ", "description": "故障"})
        self.assertEqual(422, status, body)
        key = str(uuid.uuid4())
        order, payload = self.create(key=key)
        status, replay = self.student.call("POST", "/api/v1/repairs", payload, key)
        self.assertEqual(201, status)
        self.assertEqual(order["id"], replay["data"]["id"])
        status, mismatch = self.student.call("POST", "/api/v1/repairs", {**payload, "title": "另一个内容"}, key)
        self.assertEqual(409, status, mismatch)
        status, hidden = self.other.call("GET", "/api/v1/repairs/" + order["id"])
        self.assertEqual(404, status, hidden)
        status, no_csrf = self.student.call("POST", "/api/v1/repairs/" + order["id"] + "/cancel", {"expectedVersion": order["version"]}, csrf=False)
        self.assertEqual(403, status, no_csrf)
        current = self.action(self.student, order, "cancel", reason="测试完成，撤销未派发工单")
        self.assertEqual("CANCELLED", current["status"])
        self.assertEqual([], current["allowedActions"])
        # A completed request replays its original result even if the object later changed state.
        status, replay = self.student.call("POST", "/api/v1/repairs", payload, key)
        self.assertEqual("SUBMITTED", replay["data"]["status"])

    def test_02_real_resource_lifecycle_and_multiple_sources(self):
        future = (datetime.now(timezone(timedelta(hours=8))) + timedelta(days=1)).replace(hour=10, minute=0, second=0, microsecond=0)
        query = urllib.parse.urlencode({"roomId": self.room_id, "startAt": future.isoformat(), "endAt": (future + timedelta(hours=1)).isoformat()})
        seats = self.student.get("/api/v1/availability/seats?" + query)["items"]
        self.assertTrue(seats, "Fresh shared room must have an available test seat")
        seat_id = seats[0].get("seatId", seats[0].get("id"))
        status, booked = self.student.call("POST", "/api/v1/reservations", {"seatId": seat_id, "startAt": future.isoformat(), "endAt": (future + timedelta(hours=1)).isoformat()})
        self.assertEqual(201, status, booked)
        first, _ = self.create("并行维修故障 A")
        second, _ = self.create("并行维修故障 B")
        for order in (first, second):
            assigned = self.action(self.dispatcher, order, "assign", assignedTo=self.worker_id)
            status, denied = self.student.call("POST", "/api/v1/repairs/" + order["id"] + "/cancel", {"expectedVersion": assigned["version"], "reason": "已派单不能本人撤销"})
            self.assertEqual(409, status, denied)
            pending = self.action(self.worker, order, "start", expected=202)
            self.assertEqual("BLOCK_PENDING", pending["status"])
        first = self.until(first, "IN_REPAIR")
        second = self.until(second, "IN_REPAIR")
        self.assertEqual([], self.student.get("/api/v1/availability/seats?" + query)["items"])
        reservation = self.student.get("/api/v1/reservations/" + booked["data"]["id"])
        self.assertEqual("CANCELLED", reservation["status"])
        self.assertEqual("MAINTENANCE", reservation["cancelReason"])
        completed = self.action(self.worker, first, "complete", workNote="完成插座检测，更换损坏组件")
        self.assertEqual("AWAITING_ACCEPTANCE", completed["status"])
        self.assertEqual([], self.student.get("/api/v1/availability/seats?" + query)["items"])
        rejected = self.action(self.student, first, "acceptance", decision="REJECT", comment="仍有松动，请重新检查")
        self.assertEqual("IN_REPAIR", rejected["status"])
        self.action(self.worker, first, "complete", workNote="重新紧固并完成通电检查")
        pending = self.action(self.student, first, "acceptance", expected=202, decision="ACCEPT", comment="复查正常")
        self.assertEqual("RELEASE_PENDING", pending["status"])
        first = self.until(first, "CLOSED")
        self.assertEqual([], self.student.get("/api/v1/availability/seats?" + query)["items"], "Closing A must not release B")
        self.action(self.worker, second, "complete", workNote="故障 B 已完成维修")
        self.action(self.admin, second, "acceptance", expected=202, decision="ACCEPT", comment="验收正常")
        second = self.until(second, "CLOSED")
        self.assertTrue(self.student.get("/api/v1/availability/seats?" + query)["items"])
        self.assertEqual("CANCELLED", self.student.get("/api/v1/reservations/" + booked["data"]["id"])["status"], "Maintenance cancellation cannot resurrect")
        rated = self.action(self.student, first, "rating", expected=201, score=5, comment="维修及时")
        self.assertEqual(5, rated["rating"]["score"])
        status, duplicate = self.student.call("POST", "/api/v1/repairs/" + first["id"] + "/rating", {"expectedVersion": rated["version"], "score": 4})
        self.assertEqual(409, status, duplicate)
        self.assertEqual(2, len(first["acceptances"]))
        self.assertGreaterEqual(len(first["history"]), 9)


if __name__ == "__main__":
    unittest.main(verbosity=2)
