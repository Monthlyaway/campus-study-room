"""Native lifecycle ownership and artifact locking; no Java or Docker is started."""
import contextlib
import fcntl
import importlib.util
import io
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import integration_harness as harness

spec = importlib.util.spec_from_file_location("native_startup_ops", ROOT / "scripts/studyroom.py")
ops = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ops)


class NativeStartupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.state = self.root / "state"
        self.state.mkdir()
        self.events = []
        self.files = {}
        for service in ("core", "repair", "migration"):
            source = self.root / (service + "-build.jar")
            source.write_bytes(b"test artifact; never executed")
            self.files[service] = source
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(patch.object(ops, "config", return_value=(self.state, {"MAIL_UI_PORT": "18025"})))
        self.stack.enter_context(patch.object(harness, "ROOT", self.root))

    def assert_locked(self, name):
        self.events.append(name)
        for path in (self.state / "deployment.lock", self.root / ".local/workspace-build.lock"):
            with path.open("a") as stream:
                with self.assertRaises(BlockingIOError, msg=f"{name} escaped {path.name}"):
                    fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)

    def assert_unlocked(self):
        for path in (self.state / "deployment.lock", self.root / ".local/workspace-build.lock"):
            with path.open("a") as stream:
                fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)

    def stub_startup(self, fail_at=None):
        def stage(name):
            self.assert_locked(name)
            if fail_at == name:
                raise RuntimeError("injected lifecycle failure")

        def copy(source, destination):
            stage("copy " + Path(destination).name)
            return shutil_copy(source, destination)

        def start(*args, **kwargs):
            stage("start")
            return SimpleNamespace(pid=123456, poll=lambda: None)

        shutil_copy = shutil.copy2
        mocks = {}
        replacements = {
            "compose": lambda *a, **kw: stage("dependencies"),
            "_build_backend_unlocked": lambda: stage("build"),
            "migrate": lambda *a, **kw: stage("migration " + kw.get("action", "migrate")),
            "jar": lambda service: self.files[service],
            "native_env": lambda *a: {},
            "wait_http": lambda *a, **kw: stage("readiness"),
        }
        for name, replacement in replacements.items():
            mocks[name] = self.stack.enter_context(patch.object(ops, name, side_effect=replacement))
        self.stack.enter_context(patch.object(ops.shutil, "copy2", side_effect=copy))
        mocks["Popen"] = self.stack.enter_context(patch.object(ops.subprocess, "Popen", side_effect=start))
        return mocks

    def invoke(self, *args):
        with patch.object(sys, "argv", ["studyroom.py", *args]), contextlib.redirect_stdout(io.StringIO()) as output:
            ops.main()
        return output.getvalue()

    def test_native_up_holds_environment_then_workspace_through_migrations_and_copy(self):
        order = []
        environment_lock, workspace_lock = ops.environment_lock, harness.workspace_lock

        @contextlib.contextmanager
        def environment(name):
            with environment_lock(name):
                order.append("environment")
                try:
                    yield
                finally:
                    order.append("environment released")

        @contextlib.contextmanager
        def workspace():
            self.assertEqual(["environment"], order)
            with workspace_lock():
                order.append("workspace")
                try:
                    yield
                finally:
                    order.append("workspace released")

        self.stub_startup()
        with patch.object(ops, "environment_lock", side_effect=environment), patch.object(harness, "workspace_lock", side_effect=workspace):
            output = self.invoke("native-up", "local")
        self.assertEqual(["environment", "workspace", "workspace released", "environment released"], order)
        self.assertEqual(["dependencies", "build", "migration migrate", "migration reference", "migration demo",
                          "copy core.jar", "start", "readiness", "copy repair.jar", "start", "readiness"], self.events)
        self.assertIn("Backend ready", output)
        self.assert_unlocked()

    def test_migration_and_copy_failure_release_both_locks(self):
        for failure in ("migration reference", "copy core.jar"):
            with self.subTest(stage=failure), contextlib.ExitStack() as stage_stack:
                original_stack, self.stack = self.stack, stage_stack
                try:
                    self.stub_startup(failure)
                    with self.assertRaisesRegex(RuntimeError, "injected lifecycle failure"):
                        self.invoke("native-up", "local")
                    self.assert_unlocked()
                finally:
                    self.stack = original_stack

    def test_standalone_migration_and_seed_hold_both_locks_and_release_on_failure(self):
        for args, expected in ((["migrate", "local"], "migrate"), (["seed", "local"], "reference"), (["seed", "local", "demo"], "demo")):
            with self.subTest(command=args):
                def migrate(environment, *, native, action):
                    self.assertEqual(("local", True, expected), (environment, native, action))
                    self.assert_locked("standalone " + action)
                    raise RuntimeError("migration rejected")

                with patch.object(ops, "migrate", side_effect=migrate):
                    with self.assertRaisesRegex(RuntimeError, "migration rejected"):
                        self.invoke(*args)
                self.assert_unlocked()

    def test_build_backend_wrapper_acquires_workspace_only_once(self):
        def inside_build():
            with (self.root / ".local/workspace-build.lock").open("a") as stream:
                with self.assertRaises(BlockingIOError):
                    fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)

        with patch.object(ops, "_build_backend_unlocked", side_effect=inside_build) as build:
            ops.build_backend()
        build.assert_called_once_with()
        with (self.root / ".local/workspace-build.lock").open("a") as stream:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)

    def test_unrelated_live_child_pid_is_rejected_without_any_signal_or_startup(self):
        runtime = self.state / "run/core.jar"
        child = subprocess.Popen([sys.executable, "-u", "-c", "import sys; print('ready', flush=True); sys.stdin.readline()", "-jar", str(runtime)],
                                 stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        try:
            self.assertEqual("ready", child.stdout.readline().strip())
            (self.state / "core.pid").write_text(str(child.pid))
            with patch.object(ops.os, "kill", side_effect=AssertionError("Unrelated process was signaled")), \
                 patch.object(ops, "compose") as compose, patch.object(ops, "_build_backend_unlocked") as build, \
                 patch.object(ops, "migrate") as migrate, patch.object(ops, "wait_http") as health:
                with self.assertRaisesRegex(RuntimeError, "different process"):
                    self.invoke("native-up", "local")
                for mocked in (compose, build, migrate, health):
                    mocked.assert_not_called()
                self.assertIsNone(child.poll())
                self.assertEqual(str(child.pid), (self.state / "core.pid").read_text())
            self.assert_unlocked()
        finally:
            child.communicate("exit\n", timeout=5)
            self.assertEqual(0, child.returncode)
            child.stdin.close()
            child.stdout.close()

    def test_existing_owned_processes_need_health_and_same_identity_afterward(self):
        mocks = self.stub_startup()
        for service in ("core", "repair"):
            (self.state / (service + ".pid")).write_text("123456")
        core, repair = ("core start",), ("repair start",)
        with patch.object(ops, "native_process_identity", side_effect=[core, repair, core, repair]) as identity:
            output = self.invoke("native-up", "local")
        self.assertEqual(4, identity.call_count)
        self.assertEqual(2, mocks["wait_http"].call_count)
        mocks["Popen"].assert_not_called()
        self.assertIn("Backend ready", output)

    def test_changed_identity_or_failed_readiness_cannot_report_success(self):
        self.stub_startup()
        (self.state / "core.pid").write_text("123456")
        for failure in ("identity", "health"):
            with self.subTest(failure=failure), contextlib.redirect_stdout(io.StringIO()) as output:
                with patch.object(ops, "native_process_identity", side_effect=[("old",), ("new",)]), \
                     patch.object(ops, "wait_http", side_effect=RuntimeError("not ready") if failure == "health" else None), \
                     patch.object(sys, "argv", ["studyroom.py", "native-up", "local"]):
                    with self.assertRaisesRegex(RuntimeError, "not ready|identity changed"):
                        ops.main()
                self.assertNotIn("Backend ready", output.getvalue())
                self.assert_unlocked()

    def test_process_identity_matches_exact_java_jar_and_preserves_spaces(self):
        runtime = Path("/isolated workspace/run/core.jar")
        prefix = "Sl Thu Oct 22 15:00:00 2026 "
        valid = "/opt/Java Home/bin/java -jar " + str(runtime)
        with patch.object(ops, "run", return_value=SimpleNamespace(returncode=0, stdout=prefix + valid)):
            self.assertEqual(valid, ops.native_process_identity(123456, runtime)[-1])
        for command in (valid + ".backup", valid + " --other", "python -jar " + str(runtime), "java -jar /other/run/core.jar"):
            with self.subTest(command=command), patch.object(ops, "run", return_value=SimpleNamespace(returncode=0, stdout=prefix + command)):
                with self.assertRaisesRegex(RuntimeError, "different process"):
                    ops.native_process_identity(123456, runtime)
        with patch.object(ops, "run", return_value=SimpleNamespace(returncode=1, stdout="")):
            self.assertIsNone(ops.native_process_identity(123456, runtime))
        with patch.object(ops, "run", return_value=SimpleNamespace(returncode=0, stdout="Z Thu Oct 22 15:00:00 2026 java <defunct>")):
            self.assertIsNone(ops.native_process_identity(123456, runtime))


if __name__ == "__main__":
    unittest.main()
