"""Legacy runner serialization without starting databases, services, or Maven."""
import contextlib
import importlib
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tests/integration"))
import runner_locks
import integration_harness as harness
from run_core_persistence import ops


@contextlib.contextmanager
def recorded_lock(events, name):
    events.append("enter " + name)
    try:
        yield
    finally:
        events.append("exit " + name)


class RunnerLockTests(unittest.TestCase):
    def test_environment_precedes_workspace_and_release_order_is_reversed(self):
        events = []
        local = SimpleNamespace(environment_lock=lambda env: recorded_lock(events, env))
        with patch.object(runner_locks, "workspace_lock", side_effect=lambda: recorded_lock(events, "workspace")):
            with runner_locks.local_runner_lock(local, workspace=True):
                events.append("body")
        self.assertEqual(["enter local", "enter workspace", "body", "exit workspace", "exit local"], events)

    def test_http_only_does_not_acquire_workspace(self):
        events = []
        local = SimpleNamespace(environment_lock=lambda env: recorded_lock(events, env))
        with patch.object(runner_locks, "workspace_lock", side_effect=AssertionError("unexpected workspace lock")):
            with runner_locks.local_runner_lock(local):
                events.append("body")
        self.assertEqual(["enter local", "body", "exit local"], events)

    def test_failed_workspace_acquisition_releases_environment(self):
        events = []
        local = SimpleNamespace(environment_lock=lambda env: recorded_lock(events, env))
        with patch.object(runner_locks, "workspace_lock", side_effect=OSError("lock unavailable")):
            with self.assertRaisesRegex(OSError, "lock unavailable"):
                with runner_locks.local_runner_lock(local, workspace=True):
                    self.fail("Body must not run without both locks")
        self.assertEqual(["enter local", "exit local"], events)

    def test_real_file_locks_exclude_other_process_and_release_after_failure(self):
        probe = """import fcntl, sys
with open(sys.argv[1], 'a') as lock:
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        sys.exit(7)
"""
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder)
            paths = [base / "deployment.lock", base / ".local/workspace-build.lock"]
            with patch.object(ops, "config", return_value=(base, {})), patch.object(harness, "ROOT", base):
                with self.assertRaisesRegex(RuntimeError, "suite failed"):
                    with runner_locks.local_runner_lock(ops, workspace=True):
                        for path in paths:
                            result = subprocess.run([sys.executable, "-c", probe, str(path)], timeout=5)
                            self.assertEqual(7, result.returncode, f"Lock did not exclude another process: {path.name}")
                        raise RuntimeError("suite failed")
                for path in paths:
                    result = subprocess.run([sys.executable, "-c", probe, str(path)], timeout=5)
                    self.assertEqual(0, result.returncode, f"Lock leaked after failure: {path.name}")

    def test_runner_entrypoints_cover_work_and_release_on_exception(self):
        runners = {
            "run_admin_persistence": True,
            "run_core_acceptance": True,
            "run_core_contract": True,
            "run_core_inputs": True,
            "run_core_persistence": True,
            "run_repair_persistence": True,
            "run_shared_persistence": True,
            "run_smtp_acceptance": True,
            "run_repair_deadlines": True,
            "run_repair_acceptance": False,
            "run_repair_faults": False,
        }
        for name, workspace in runners.items():
            with self.subTest(runner=name):
                module = importlib.import_module(name)
                events = []

                @contextlib.contextmanager
                def locked(actual_ops, *, workspace):
                    self.assertTrue(callable(actual_ops.environment_lock))
                    events.append(("enter", workspace))
                    try:
                        yield
                    finally:
                        events.append(("exit", workspace))

                def body():
                    self.assertEqual([("enter", workspace)], events)
                    raise RuntimeError("runner failed")

                with patch.object(module, "local_runner_lock", side_effect=locked), \
                     patch.object(module, "run_local", side_effect=body):
                    with self.assertRaisesRegex(RuntimeError, "runner failed"):
                        module.main()
                self.assertEqual([("enter", workspace), ("exit", workspace)], events)

    def test_fault_matrix_only_setup_phases_need_shared_build_artifacts(self):
        # The legacy module sets public API URLs during import. Keep them local to
        # this test so test discovery cannot redirect other suites to its ports.
        with patch.dict(os.environ), patch.dict(sys.modules):
            module = importlib.import_module("run_fault_matrix")
        actions = {"setup": True, "all": True, "prepare": False,
                   "recover-block": False, "recover-release": False,
                   "verify": False, "stop": False}
        for action, workspace in actions.items():
            with self.subTest(action=action), tempfile.TemporaryDirectory() as folder:
                events = []

                @contextlib.contextmanager
                def locked(actual_ops, *, workspace):
                    self.assertIs(module.ops, actual_ops)
                    events.append(("enter", workspace))
                    try:
                        yield
                    finally:
                        events.append(("exit", workspace))

                def body(*args):
                    self.assertEqual([("enter", workspace)], events)

                with patch.object(module, "STATE", Path(folder) / "state"), \
                     patch.object(module, "OUTPUT", Path(folder) / "output"), \
                     patch.object(module, "local_runner_lock", side_effect=locked), \
                     patch.object(module, "setup", side_effect=body), \
                     patch.object(module, "all_steps", side_effect=body), \
                     patch.object(module, "prepare", side_effect=body), \
                     patch.object(module, "recover", side_effect=body), \
                     patch.object(module, "verify", side_effect=body), \
                     patch.object(module, "stop", side_effect=body), \
                     patch.object(sys, "argv", ["run_fault_matrix.py", action]), \
                     contextlib.redirect_stdout(io.StringIO()):
                    module.main()
                self.assertEqual([("enter", workspace), ("exit", workspace)], events)


if __name__ == "__main__":
    unittest.main()
