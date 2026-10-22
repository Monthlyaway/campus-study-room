#!/usr/bin/env python3
"""Run real resource response-loss recovery locally, restoring the normal repair process afterward."""
from runner_locks import local_runner_lock
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("studyroom_ops", ROOT / "scripts/studyroom.py")
ops = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ops)


def stop_owned(pidfile):
    if not pidfile.exists():
        return
    pid = int(pidfile.read_text())
    command = subprocess.run(["ps", "-p", str(pid), "-o", "command="], text=True, capture_output=True).stdout
    if not command.strip():
        return
    if str(ROOT / ".local/local/run/repair.jar") not in command:
        raise RuntimeError("Refusing to stop a PID that is not this checkout's repair runtime")
    os.kill(pid, signal.SIGTERM)
    for _ in range(120):
        state = subprocess.run(["ps", "-p", str(pid), "-o", "stat="], text=True, capture_output=True).stdout.strip()
        if not state or state.startswith("Z"):
            try:
                os.waitpid(pid, os.WNOHANG)
            except ChildProcessError:
                pass
            return
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return
        time.sleep(0.25)
    raise RuntimeError("Repair did not stop gracefully")


def start_repair(state, core_url="http://localhost:8081"):
    env = ops.native_env("local", "repair")
    env["CORE_INTERNAL_URL"] = core_url
    with (state / "repair.log").open("a") as log:
        process = subprocess.Popen(["java", "-jar", str(state / "run/repair.jar")], cwd=ROOT, env=env, stdout=log, stderr=log, start_new_session=True)
    (state / "repair.pid").write_text(str(process.pid))
    ops.wait_http("http://localhost:8082/actuator/health/readiness")


def run_local():
    state, _ = ops.config("local")
    events = state / ("repair-fault-events-" + str(int(time.time())) + ".jsonl")
    stop_owned(state / "repair.pid")
    proxy = subprocess.Popen([sys.executable, ROOT / "tests/integration/fault_proxy.py", "--events", events], cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        start_repair(state, "http://localhost:18081")
        env = os.environ.copy()
        env["STUDYROOM_TEST_PASSWORD"] = ops.secret(state, "demo_password")
        ops.run([sys.executable, ROOT / "tests/integration/repair_http.py", "RepairHttpTests.test_02_real_resource_lifecycle_and_multiple_sources"], env=env)
        records = [json.loads(line) for line in events.read_text().splitlines()]
        dropped = next(event for event in records if event["responseDropped"])
        replays = [event for event in records if event["commandId"] == dropped["commandId"]]
        assert len(replays) >= 2, "No retry of the committed BLOCK response was observed"
        assert len({event["payloadHash"] for event in replays}) == 1, "Retry changed the durable payload"
        assert all(event["upstreamStatus"] == 200 for event in replays), "Core did not accept the identical replay"
        print(f"PASS: dropped committed BLOCK response; {len(replays)} attempts shared one commandId/payload. Evidence: {events}")
    finally:
        stop_owned(state / "repair.pid")
        proxy.terminate()
        proxy.wait(timeout=10)
        start_repair(state)


def main():
    with local_runner_lock(ops, workspace=False):
        run_local()


if __name__ == "__main__":
    main()
