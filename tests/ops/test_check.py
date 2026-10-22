"""Isolation and fail-closed guarantees for the default real integration entry point."""
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import integration_harness as harness

spec = importlib.util.spec_from_file_location("check_ops", ROOT / "scripts/studyroom.py")
ops = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ops)


class CheckEntrypointTests(unittest.TestCase):
    def invoke(self, unit_only=False, failure=None):
        calls = []
        with patch.object(harness, "workspace_lock", return_value=contextlib.nullcontext()), \
             patch.object(ops, "run", side_effect=lambda args, **kw: calls.append((args, kw))), \
             patch.object(ops, "compose"), patch.object(ops, "web_command", side_effect=lambda *args: ["web", *args]), \
             patch.object(ops, "java_env", return_value={"PATH": "test", "STUDYROOM_CORE_DB_TESTS": "true"}), \
             patch.object(harness, "run_local", side_effect=failure) as integration, \
             contextlib.redirect_stdout(io.StringIO()) as output:
            ops.check(unit_only)
        return calls, integration, output.getvalue()

    def test_default_runs_real_integration_after_build_and_static_checks(self):
        calls, integration, _ = self.invoke()
        self.assertEqual("verify", calls[0][0][-1])
        self.assertNotIn("STUDYROOM_CORE_DB_TESTS", calls[0][1]["env"])
        integration.assert_called_once_with({"PATH": "test"})
        self.assertTrue(any("typecheck" in args for args, _ in calls))
        self.assertTrue(any("tests/ops" in args for args, _ in calls))

    def test_unit_only_is_explicit_and_never_claims_real_acceptance(self):
        _, integration, output = self.invoke(True)
        integration.assert_not_called()
        self.assertIn("NOT run", output)
        self.assertIn("not full acceptance", output)

    def test_default_dependency_failure_cannot_fall_back_to_unit_only(self):
        with self.assertRaisesRegex(RuntimeError, "Docker unavailable"):
            self.invoke(failure=RuntimeError("Docker unavailable"))

    def test_real_pg_plan_contains_contract_inputs_smtp_and_both_services(self):
        names = {name for _, name, _ in harness.PERSISTENCE_SUITES}
        self.assertEqual(9, len(names))
        self.assertTrue({"CoreContractTest", "CoreInputBoundaryTest", "CoreSmtpAcceptanceTest",
                         "RepairPersistenceTest", "RepairDeadlineHttpTest"} <= names)

    def test_ci_adapter_refuses_an_ordinary_local_invocation(self):
        with self.assertRaisesRegex(RuntimeError, "disposable CI"):
            harness.run_ci({})


class IsolatedDependencyTests(unittest.TestCase):
    def test_environment_lock_blocks_another_process_until_release(self):
        with tempfile.TemporaryDirectory() as folder:
            lock, marker = Path(folder) / "deployment.lock", Path(folder) / "entered"
            code = "import sys; from pathlib import Path; sys.path.insert(0,sys.argv[1]); from integration_harness import file_lock; print('waiting',flush=True);\nwith file_lock(Path(sys.argv[2])): Path(sys.argv[3]).touch()"
            process = None
            try:
                with harness.file_lock(lock):
                    process = subprocess.Popen([sys.executable, "-c", code, str(ROOT / "scripts"), str(lock), str(marker)],
                                               stdout=subprocess.PIPE, text=True)
                    self.assertEqual("waiting", process.stdout.readline().strip())
                    self.assertFalse(marker.exists())
                    self.assertIsNone(process.poll())
                self.assertEqual(0, process.wait(timeout=5))
                self.assertTrue(marker.exists())
            finally:
                if process is not None:
                    harness.stop_owned(process)
                    process.stdout.close()

    def test_failing_suite_cleans_owned_containers_but_retains_volume_and_private_credentials(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder)
            deps = harness.Dependencies({}, base / "output", base / "state", local=True)
            other = harness.Dependencies({}, base / "other-output", base / "other-state", local=True)
            self.assertNotEqual(deps.project, other.project)
            with patch.object(deps, "compose") as compose, patch.object(deps, "sql"), \
                 patch.object(deps, "published_port", side_effect=["35001", "35002", "35003"]), \
                 patch.object(harness, "wait_http"):
                with self.assertRaisesRegex(ValueError, "suite failed"):
                    with deps.started():
                        self.assertTrue((base / "state/postgres_password").is_file())
                        self.assertEqual("127.0.0.1", deps.env["PGHOST"])
                        raise ValueError("suite failed")
            self.assertEqual("down", compose.call_args_list[-1].args[0])
            self.assertNotIn("--volumes", compose.call_args_list[-1].args)
            self.assertTrue((base / "state/postgres_password").exists())
            self.assertEqual(0o600, (base / "state/credentials.json").stat().st_mode & 0o777)
            self.assertIn(deps.project + "_postgres-data", (base / "state/environment.json").read_text())

    def test_dependency_start_failure_still_attempts_owned_cleanup(self):
        with tempfile.TemporaryDirectory() as folder:
            deps = harness.Dependencies({}, Path(folder) / "out", Path(folder) / "state", local=True)
            with patch.object(deps, "compose", side_effect=[RuntimeError("Docker unavailable"), None]) as compose:
                with self.assertRaisesRegex(RuntimeError, "Docker unavailable"):
                    with deps.started():
                        self.fail("A failed dependency must never enter the suite")
            self.assertEqual(["up", "down"], [call.args[0] for call in compose.call_args_list])

    def test_environment_cannot_inherit_production_database_or_spring_overrides(self):
        with tempfile.TemporaryDirectory() as folder:
            deps = harness.Dependencies({"REPAIR_DB_URL": "production", "REPAIR_DB_USER": "production",
                                         "SPRING_DATASOURCE_URL": "production", "STUDYROOM_CORE_URL": "production",
                                         "INTERNAL_PASSWORD_FILE": "/production", "JAVA_TOOL_OPTIONS": "production"},
                                        Path(folder) / "out", Path(folder) / "state", local=True)
            deps.env.update(PGHOST="127.0.0.1", PGPORT="35001", SMTP_HOST="127.0.0.1", SMTP_PORT="35002",
                            STUDYROOM_MAILPIT_URL="http://127.0.0.1:35003")
            env = harness.common_environment(deps, 35004, 35005)
            self.assertNotIn("production", env.values())
            self.assertEqual("http://127.0.0.1:35004", env["CORE_URL"])
            self.assertEqual(env["CORE_URL"], env["CORE_INTERNAL_URL"])
            self.assertEqual("http://127.0.0.1:35005", env["STUDYROOM_REPAIR_URL"])
            self.assertEqual("smtp", env["MAIL_MODE"])

    def test_ports_are_distinct_reserved_and_never_use_existing_runtime_ports(self):
        sockets = harness.reserve_ports(3)
        try:
            ports = {sock.getsockname()[1] for sock in sockets}
            self.assertEqual(3, len(ports))
            self.assertFalse(ports & harness.EXCLUDED_PORTS)
        finally:
            for sock in sockets:
                sock.close()

    def test_fault_java_process_cannot_bypass_proxy_via_python_url_alias(self):
        common = {"REPAIR_DB_PASSWORD": "isolated-password", "CORE_INTERNAL_URL": "http://127.0.0.1:35001",
                  "STUDYROOM_CORE_URL": "http://127.0.0.1:35001", "STUDYROOM_REPAIR_URL": "http://127.0.0.1:35002"}
        env = harness.application_environment(common, "repair", 35002, "http://127.0.0.1:35003")
        self.assertEqual("http://127.0.0.1:35003", env["CORE_INTERNAL_URL"])
        self.assertNotIn("STUDYROOM_CORE_URL", env)
        self.assertNotIn("STUDYROOM_REPAIR_URL", env)
        self.assertEqual("http://127.0.0.1:35001", common["STUDYROOM_CORE_URL"])

    def test_cleanup_does_not_signal_finished_processes(self):
        process = Mock()
        process.poll.return_value = 0
        harness.stop_owned(process)
        process.terminate.assert_not_called()
        process.kill.assert_not_called()

    def test_fixture_environment_is_restored_even_on_failure(self):
        with patch.dict(os.environ, {"CORE_URL": "original"}):
            with self.assertRaises(ValueError):
                with harness.temporary_environment({"CORE_URL": "isolated", "CHECK_ONLY_KEY": "temporary"}):
                    raise ValueError("failed")
            self.assertEqual("original", os.environ["CORE_URL"])
            self.assertNotIn("CHECK_ONLY_KEY", os.environ)

    def test_failure_summary_does_not_echo_assertion_credentials_or_cookies(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "report.txt"
            path.write_text("FAIL: test_permissions (__main__.Fixture.test_permissions)\n"
                            "AssertionError: 200 != 403 : {'password': 'secret-value', 'Cookie': 'session-secret'}\n"
                            "AssertionError: token-secret\nRan 5 tests in 1.234s\nFAILED (failures=1)\n")
            result = harness.failure_summary(path)
            self.assertIn("test_permissions", result)
            self.assertIn("200 != 403", result)
            self.assertNotIn("secret", result)
            self.assertNotIn("Cookie", result)

    def test_delivery_state_diagnostic_only_allows_known_enums_and_test_basenames(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "report.txt"
            path.write_text('  File "/private/user-name/tests/integration/repair_acceptance_http.py", line 284, in test_delivery\n'
                            'AssertionError: \'SENT\' != \'PENDING\' : secret-cookie\n'
                            '  File "/private/credential-name.py", line 17\n'
                            'AssertionError: \'password-secret\' != \'SENT\'\n')
            result = harness.failure_summary(path)
            self.assertIn("repair_acceptance_http.py:284", result)
            self.assertIn("SENT != PENDING", result)
            for hidden in ("private", "user-name", "secret", "credential"):
                self.assertNotIn(hidden, result)


if __name__ == "__main__":
    unittest.main()
