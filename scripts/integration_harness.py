"""One real PostgreSQL/HTTP/SMTP test plan for local checks and disposable CI jobs."""
import base64
import contextlib
import datetime as dt
import fcntl
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.request
import uuid

from ci_reports import require_unittest_report, require_junit_report
from private_artifacts import protect_reports

ROOT = Path(__file__).resolve().parent.parent
PERSISTENCE_SUITES = (
    ("core", "CorePersistenceTest", "STUDYROOM_CORE_DB_TESTS"),
    ("core", "AdminPersistenceTest", "STUDYROOM_ADMIN_DB_TESTS"),
    ("core", "SharedPersistenceTest", "STUDYROOM_CORE_DB_TESTS"),
    ("core", "CoreAcceptanceMatrixTest", "STUDYROOM_MATRIX_DB_TESTS"),
    ("core", "CoreSmtpAcceptanceTest", "STUDYROOM_SMTP_DB_TESTS"),
    ("core", "CoreContractTest", "STUDYROOM_CONTRACT_DB_TESTS"),
    ("core", "CoreInputBoundaryTest", "STUDYROOM_INPUT_DB_TESTS"),
    ("repair", "RepairPersistenceTest", "STUDYROOM_REPAIR_DB_TESTS"),
    ("repair", "RepairDeadlineHttpTest", "STUDYROOM_REPAIR_DEADLINE_TESTS"),
)
EXCLUDED_PORTS = {8081, 8082, 18081, 18180, 18181, 18182, 18183, 19282}


@contextlib.contextmanager
def file_lock(path):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        yield


def workspace_lock():
    return file_lock(ROOT / ".local/workspace-build.lock")


def private_json(path, value):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as stream:
        os.fchmod(stream.fileno(), 0o600)
        json.dump(value, stream, indent=2)


@contextlib.contextmanager
def interrupted_cleanup():
    previous = signal.getsignal(signal.SIGTERM)
    def terminate(_signum, _frame):
        raise KeyboardInterrupt("Integration interrupted; cleaning owned resources")
    signal.signal(signal.SIGTERM, terminate)
    try:
        yield
    finally:
        signal.signal(signal.SIGTERM, previous)


def run(args, *, env, **kwargs):
    return subprocess.run([str(x) for x in args], cwd=ROOT, env=env,
                          check=True, text=True, **kwargs)


def artifact(service):
    folder = "tools/migration-runner" if service == "migration" else f"services/{service}-service"
    jars = [p for p in (ROOT / folder / "target").glob("*.jar")
            if not p.name.endswith(("-sources.jar", "-javadoc.jar"))]
    if len(jars) != 1:
        raise RuntimeError(f"Expected one built artifact in {folder}/target")
    return jars[0]


def reserve_ports(count):
    held = []
    try:
        while len(held) < count:
            candidate = socket.socket()
            candidate.bind(("127.0.0.1", 0))
            if candidate.getsockname()[1] in EXCLUDED_PORTS:
                candidate.close()
            else:
                held.append(candidate)
        return held
    except BaseException:
        for candidate in held:
            candidate.close()
        raise


def wait_http(url, *, process=None, timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process is not None and process.poll() is not None:
            raise RuntimeError("Owned integration process exited before readiness; see its log")
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                if response.status == 200:
                    return
        except OSError:
            pass
        time.sleep(0.5)
    raise RuntimeError(f"Required integration dependency failed readiness: {url}")


def stop_owned(process):
    # Only Popen handles created by this invocation can reach cleanup.
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)


def failure_summary(path):
    """Whitelist identifiers/counts only; assertion payloads may contain cookies/passwords."""
    lines = []
    allowed_files = {p.name for p in (ROOT / "tests/integration").glob("*_http.py")}
    allowed_files.update(name + ".java" for _, name, _ in PERSISTENCE_SUITES)
    for line in Path(path).read_text(errors="replace").splitlines():
        match = re.match(r"^(?:FAIL|ERROR): (test_[A-Za-z0-9_]+|setUpClass|tearDownClass)(?: |$)", line)
        if match:
            lines.append("Failed test: " + match[1])
        match = re.match(r"^AssertionError: ([0-9]+) != ([0-9]+)(?:$| :)", line)
        if match:
            lines.append("Assertion counts/status: " + match[1] + " != " + match[2])
        match = re.match(r"^AssertionError: ['\"](SENT|PENDING|LEASED|FAILED)['\"] != ['\"](SENT|PENDING|LEASED|FAILED)['\"]", line)
        if match:
            lines.append("Assertion delivery state: " + match[1] + " != " + match[2])
        match = re.search(r'File "([^"\n]+)", line ([0-9]+)', line)
        if match and Path(match[1]).name in allowed_files:
            lines.append("Test location: " + Path(match[1]).name + ":" + match[2])
        match = re.search(r'\(([A-Za-z0-9_]+\.java):([0-9]+)\)', line)
        if match and match[1] in allowed_files:
            lines.append("Test location: " + match[1] + ":" + match[2])
        match = re.match(r"^(Ran [0-9]+ tests? in [0-9.]+s|FAILED \([a-z=0-9, ]+\))$", line)
        if match:
            lines.append(match[1])
        match = re.search(r"Tests run: ([0-9]+), Failures: ([0-9]+), Errors: ([0-9]+), Skipped: ([0-9]+)", line)
        if match:
            lines.append("JUnit tests/failures/errors/skipped: " + "/".join(match.groups()))
    return "\n".join(lines[-12:]) or "Test subprocess failed; inspect the protected report."


def reported(args, env, path):
    try:
        with Path(path).open("w") as report:
            run(args, env=env, stdout=report, stderr=report)
    except subprocess.CalledProcessError:
        print(f"Integration failed ({Path(path).name}):\n{failure_summary(path)}", file=sys.stderr, flush=True)
        raise


@contextlib.contextmanager
def temporary_environment(values):
    old = {key: os.environ.get(key) for key in values}
    os.environ.update(values)
    try:
        yield
    finally:
        for key, value in old.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


class Dependencies:
    """Local owns a new Compose project; CI owns its disposable job services."""
    def __init__(self, env, output, state, *, local):
        self.env = dict(env)
        self.output, self.state, self.local = Path(output), Path(state), local
        self.project = "studyroom-check-" + uuid.uuid4().hex[:16]
        self.env.update(PGUSER="studyroom_bootstrap", PGDATABASE="studyroom",
                        CORE_DB_PASSWORD=secrets.token_urlsafe(32),
                        REPAIR_DB_PASSWORD=secrets.token_urlsafe(32),
                        MIGRATION_DB_PASSWORD=secrets.token_urlsafe(32))
        if local:
            self.env["POSTGRES_PASSWORD"] = secrets.token_urlsafe(32)
            self.env["CHECK_STATE_DIR"] = str(self.state)
        elif not self.env.get("POSTGRES_PASSWORD"):
            raise RuntimeError("Disposable CI PostgreSQL password is required")
        self.env["PGPASSWORD"] = self.env["POSTGRES_PASSWORD"]

    def compose(self, *args, **kwargs):
        return run(["docker", "compose", "--env-file", os.devnull, "-p", self.project,
                    "-f", ROOT / "infra/compose/check.yml", *args], env=self.env, **kwargs)

    def database_command(self, command, *args, **kwargs):
        if self.local:
            return self.compose("exec", "-T", "-e", "CORE_DB_PASSWORD", "-e", "REPAIR_DB_PASSWORD",
                                "-e", "MIGRATION_DB_PASSWORD", "postgres", command, *args, **kwargs)
        return run([command, *args], env=self.env, **kwargs)

    def sql(self, sql, database="studyroom"):
        return self.database_command("psql", "-v", "ON_ERROR_STOP=1", "-U", "studyroom_bootstrap",
                                     "-d", database, input=sql, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout

    def published_port(self, service, port):
        address = self.compose("port", service, str(port), stdout=subprocess.PIPE).stdout.strip()
        host, actual = address.rsplit(":", 1)
        if host != "127.0.0.1" or not actual.isdigit():
            raise RuntimeError("Check dependency must publish exclusively on loopback")
        return actual

    @contextlib.contextmanager
    def started(self):
        self.output.mkdir(parents=True, exist_ok=True)
        self.state.mkdir(parents=True, exist_ok=True, mode=0o700)
        private_json(self.state / "credentials.json", {key: self.env[key] for key in
                     ("POSTGRES_PASSWORD", "CORE_DB_PASSWORD", "REPAIR_DB_PASSWORD", "MIGRATION_DB_PASSWORD")})
        if self.local:
            private_json(self.state / "environment.json", {
                "project": self.project, "volumes": [self.project + "_postgres-data"],
                "credentials": "credentials.json", "volumePolicy": "retained for review; no automatic deletion"})
        try:
            if self.local:
                password_file = self.state / "postgres_password"
                password_file.write_text(self.env["POSTGRES_PASSWORD"])
                password_file.chmod(0o600)
                with (self.output / "dependencies.txt").open("w") as log:
                    self.compose("up", "-d", "--wait", "--wait-timeout", "100", stdout=log, stderr=log)
                self.env.update(PGHOST="127.0.0.1", PGPORT=self.published_port("postgres", 5432),
                                SMTP_HOST="127.0.0.1", SMTP_PORT=self.published_port("mailpit", 1025),
                                STUDYROOM_MAILPIT_URL="http://127.0.0.1:" + self.published_port("mailpit", 8025))
            else:
                self.env.setdefault("PGHOST", "postgres")
                self.env.setdefault("PGPORT", "5432")
                self.env.setdefault("SMTP_HOST", self.env.get("STUDYROOM_SMTP_HOST", "mailpit"))
                self.env.setdefault("SMTP_PORT", self.env.get("STUDYROOM_SMTP_PORT", "1025"))
                self.env.setdefault("STUDYROOM_MAILPIT_URL", "http://mailpit:8025")
                for _ in range(60):
                    try:
                        self.database_command("pg_isready", stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                        break
                    except subprocess.CalledProcessError:
                        time.sleep(1)
                else:
                    raise RuntimeError("Disposable CI PostgreSQL was not ready")
            wait_http(self.env["STUDYROOM_MAILPIT_URL"] + "/api/v1/messages?limit=1", timeout=60)
            sql = (ROOT / "infra/compose/init-db.sh").read_text().split("<<'SQL'\n", 1)[1].rsplit("\nSQL", 1)[0]
            self.sql(sql)
            yield self
        finally:
            if self.local:
                with (self.output / "cleanup.txt").open("w") as log:
                    self.compose("down", "--remove-orphans", "--timeout", "30", stdout=log, stderr=log)


def common_environment(deps, core_port, repair_port):
    env = dict(deps.env)
    # Never follow inherited native/production overrides to another database or service.
    for key in list(env):
        if key.startswith(("SPRING_", "STUDYROOM_")) or key in {
            "DB_URL", "DB_USERNAME", "DB_PASSWORD", "REPAIR_DB_URL", "REPAIR_DB_USER",
            "INTERNAL_PASSWORD_FILE", "SMTP_USERNAME", "SMTP_PASSWORD", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS"}:
            env.pop(key)
    core_url, repair_url = f"http://127.0.0.1:{core_port}", f"http://127.0.0.1:{repair_port}"
    env.update(DB_URL=f"jdbc:postgresql://{deps.env['PGHOST']}:{deps.env['PGPORT']}/studyroom",
               ENV_NAME="test", DEMO_PASSWORD=secrets.token_urlsafe(24), COOKIE_SECURE="false",
               CHECKIN_CODE_ENCRYPTION_KEY=base64.b64encode(secrets.token_bytes(32)).decode(),
               INTERNAL_USERNAME="repair-service", INTERNAL_PASSWORD=secrets.token_urlsafe(36),
               MAIL_MODE="smtp", SMTP_HOST=deps.env["SMTP_HOST"], SMTP_PORT=deps.env["SMTP_PORT"],
               SMTP_FROM="check@studyroom.test", JOBS_PAUSED="false",
               STUDYROOM_SMTP_HOST=deps.env["SMTP_HOST"], STUDYROOM_SMTP_PORT=deps.env["SMTP_PORT"],
               STUDYROOM_MAILPIT_URL=deps.env["STUDYROOM_MAILPIT_URL"],
               CONTROL_DIR=str(deps.state / "control"), CORE_INTERNAL_URL=core_url, CORE_URL=core_url,
               STUDYROOM_CORE_URL=core_url, STUDYROOM_REPAIR_URL=repair_url,
               STUDYROOM_CORE_LOG=str(deps.output / "core.log"), STUDYROOM_REPAIR_LOG=str(deps.output / "repair.log"),
               RELEASE_ID="check-" + uuid.uuid4().hex, GIT_COMMIT=deps.env.get("CI_COMMIT_SHA", "working-tree"))
    return env


def application_environment(common, service, port, core_url=None):
    env = {**common, "SERVER_PORT": str(port), "PORT": str(port),
           "DB_USERNAME": "studyroom_" + service, "DB_PASSWORD": common[service.upper() + "_DB_PASSWORD"]}
    # Python test URL aliases also match Spring relaxed property binding. In particular
    # STUDYROOM_CORE_URL would override CORE_INTERNAL_URL and bypass the fault proxy.
    for key in ("STUDYROOM_CORE_URL", "STUDYROOM_REPAIR_URL", "STUDYROOM_BASE_URL"):
        env.pop(key, None)
    if core_url:
        env["CORE_INTERNAL_URL"] = core_url
    return env


def run_integration(deps):
    """Run the identical acceptance plan for both infrastructure adapters."""
    output = deps.output
    ports = reserve_ports(3)
    core_port, repair_port, proxy_port = [sock.getsockname()[1] for sock in ports]
    common = common_environment(deps, core_port, repair_port)
    private_json(deps.state / "credentials.json", {key: common[key] for key in
                 ("POSTGRES_PASSWORD", "CORE_DB_PASSWORD", "REPAIR_DB_PASSWORD", "MIGRATION_DB_PASSWORD",
                  "DEMO_PASSWORD", "INTERNAL_PASSWORD", "CHECKIN_CODE_ENCRYPTION_KEY")})
    Path(common["CONTROL_DIR"]).mkdir(parents=True, exist_ok=True)
    processes, logs = [], []
    result = {"result": "failed", "commit": common["GIT_COMMIT"], "releaseId": common["RELEASE_ID"]}
    runtime = deps.state / "runtime"
    runtime.mkdir(parents=True, exist_ok=True)

    def migrate(database="studyroom", repeated=False):
        env = {**common, "DB_URL": common["DB_URL"].rsplit("/", 1)[0] + "/" + database,
               "DB_USERNAME": "studyroom_migration", "DB_PASSWORD": common["MIGRATION_DB_PASSWORD"]}
        with (output / (database + "-migration.txt")).open("w") as log:
            for action in ("migrate", "reference", "demo") * (2 if repeated else 1):
                run(["java", "-jar", runtime / "migration.jar", action], env=env, stdout=log, stderr=log)

    def start_service(service, port, logfile=None, core_url=None):
        log = (output / (logfile or service + ".log")).open("w")
        logs.append(log)
        env = application_environment(common, service, port, core_url)
        process = subprocess.Popen(["java", "-jar", str(runtime / (service + ".jar"))], cwd=ROOT,
                                   env=env, stdout=log, stderr=log, start_new_session=True)
        processes.append(process)
        base = f"http://127.0.0.1:{port}"
        wait_http(base + "/actuator/health/readiness", process=process)
        with urllib.request.urlopen(base + "/api/v1/" + ("repairs/" if service == "repair" else "") + "version", timeout=3) as response:
            identity = json.load(response)["data"]
        if process.poll() is not None or identity.get("releaseId") != common["RELEASE_ID"]:
            raise RuntimeError("Readiness belongs to another application; refusing to test it")
        return process

    try:
        for service in ("migration", "core", "repair"):
            shutil.copy2(artifact(service), runtime / (service + ".jar"))
        print("Integration: real PostgreSQL, SMTP and isolated core/repair HTTP", flush=True)
        migrate(repeated=True)
        deps.sql("REVOKE UPDATE,DELETE ON core.audit_event FROM studyroom_core; REVOKE UPDATE,DELETE ON repair.audit_event FROM studyroom_repair;")
        ports[0].close()
        start_service("core", core_port)
        ports[1].close()
        repair = start_service("repair", repair_port)
        tests = sorted((ROOT / "tests/integration").glob("*_http.py"))
        if not {"core_http.py", "repair_http.py"} <= {p.name for p in tests}:
            raise RuntimeError("Both core and repair real HTTP suites are required")
        http_counts = {}
        result["httpTestCounts"] = http_counts
        for test in tests:
            print("Integration HTTP: " + test.stem, flush=True)
            report = output / (test.stem + ".txt")
            reported([sys.executable, test], {**common, "STUDYROOM_TEST_PASSWORD": common["DEMO_PASSWORD"]}, report)
            http_counts[test.stem] = require_unittest_report(report)
        persistence_counts = {}
        result["persistenceTestCounts"] = persistence_counts
        for service, test_class, flag in PERSISTENCE_SUITES:
            print("Integration PostgreSQL: " + test_class, flush=True)
            database = "studyroom_check_" + test_class.lower()
            deps.sql("CREATE DATABASE " + database + " OWNER studyroom_migration;")
            grants = "CREATE SCHEMA core AUTHORIZATION studyroom_migration; CREATE SCHEMA repair AUTHORIZATION studyroom_migration; CREATE SCHEMA session AUTHORIZATION studyroom_migration;"
            grants += f"GRANT USAGE ON SCHEMA {service},session TO studyroom_{service};"
            for schema in (service, "session"):
                grants += f"ALTER DEFAULT PRIVILEGES FOR ROLE studyroom_migration IN SCHEMA {schema} GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO studyroom_{service};"
            deps.sql(grants, database)
            migrate(database)
            deps.sql(f"REVOKE UPDATE,DELETE ON {service}.audit_event FROM studyroom_{service};", database)
            control = deps.state / (test_class + "-control")
            control.mkdir()
            (control / "jobs-paused").touch()
            test_env = {**common, "DB_URL": common["DB_URL"].rsplit("/", 1)[0] + "/" + database,
                        "DB_USERNAME": "studyroom_" + service, "DB_PASSWORD": common[service.upper() + "_DB_PASSWORD"],
                        "CONTROL_DIR": str(control), flag: "true"}
            if test_class == "RepairDeadlineHttpTest":
                sys.path.insert(0, str(ROOT / "tests/integration"))
                from run_repair_deadlines import prepare_fixture, assert_recipients
                with temporary_environment({**common, "STUDYROOM_TEST_PASSWORD": common["DEMO_PASSWORD"]}):
                    deadline_fixture, deadline_source = prepare_fixture(common["DEMO_PASSWORD"], output / "deadlines")
                test_env["STUDYROOM_REPAIR_DEADLINE_FIXTURE"] = str(deadline_source)
            exact_report = ROOT / f"services/{service}-service/target/surefire-reports/TEST-com.campus.studyroom.{service}.{test_class}.xml"
            exact_report.unlink(missing_ok=True)
            reported([ROOT / "mvnw", "-B", "-ntp", "-pl", "services/" + service + "-service", "-am",
                      "-Dtest=" + test_class, "-Dsurefire.failIfNoSpecifiedTests=false", "test"], test_env, output / (test_class + ".txt"))
            persistence_counts[test_class] = require_junit_report(exact_report)
            shutil.copy2(exact_report, output / (test_class + ".xml"))
            if test_class == "RepairDeadlineHttpTest":
                assert_recipients(deadline_fixture, output / "deadlines")
        print("Integration: committed response-loss replay", flush=True)
        stop_owned(repair)
        events = output / "fault-events.jsonl"
        ports[2].close()
        proxy = subprocess.Popen([sys.executable, str(ROOT / "tests/integration/fault_proxy.py"), "--port", str(proxy_port),
                                  "--upstream", common["CORE_INTERNAL_URL"], "--events", str(events)], cwd=ROOT,
                                 env=common, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        processes.append(proxy)
        proxy_url = f"http://127.0.0.1:{proxy_port}"
        wait_http(proxy_url + "/actuator/health/readiness", process=proxy)
        start_service("repair", repair_port, "repair-fault.log", proxy_url)
        reported([sys.executable, ROOT / "tests/integration/repair_http.py", "RepairHttpTests.test_02_real_resource_lifecycle_and_multiple_sources"],
                 {**common, "STUDYROOM_TEST_PASSWORD": common["DEMO_PASSWORD"]}, output / "repair_fault_http.txt")
        require_unittest_report(output / "repair_fault_http.txt")
        observed = [json.loads(line) for line in events.read_text().splitlines()]
        dropped = next(event for event in observed if event["responseDropped"])
        replays = [event for event in observed if event["commandId"] == dropped["commandId"]]
        if len(replays) < 2 or len({event["payloadHash"] for event in replays}) != 1 or not all(event["upstreamStatus"] == 200 for event in replays):
            raise RuntimeError("Committed response-loss replay evidence failed")
        result.update(postgres="18.6", realServices=["core", "repair"], suites=[p.name for p in tests],
                      httpTestCounts=http_counts, realSmtp="Mailpit", deadlineRecipientAssertions=27,
                      persistenceSuites=[name for _, name, _ in PERSISTENCE_SUITES], persistenceTestCounts=persistence_counts,
                      responseLossReplay="passed", result="passed")
    finally:
        for sock in ports:
            sock.close()
        for process in reversed(processes):
            stop_owned(process)
        for log in logs:
            log.close()
        (output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        protect_reports([output], [common[key] for key in ("DEMO_PASSWORD", "INTERNAL_PASSWORD", "CHECKIN_CODE_ENCRYPTION_KEY",
                         "CORE_DB_PASSWORD", "REPAIR_DB_PASSWORD", "MIGRATION_DB_PASSWORD", "POSTGRES_PASSWORD")], deps.state / "private-reports")


def run_local(env):
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S") + "-" + uuid.uuid4().hex[:8]
    output, state = ROOT / "output/check" / stamp, ROOT / ".local/check" / stamp
    with file_lock(ROOT / ".local/check/deployment.lock"), interrupted_cleanup():
        deps = Dependencies(env, output, state, local=True)
        with deps.started():
            run_integration(deps)
    print("Real PG/HTTP/SMTP/contract checks passed: " + str(output), flush=True)


def run_ci(env):
    if env.get("CI") != "true":
        raise RuntimeError("CI adapter requires a disposable CI job; use ./scripts/check locally")
    output, state = ROOT / "output/ci", ROOT / ".local/ci-integration"
    with workspace_lock(), file_lock(state / "deployment.lock"), interrupted_cleanup():
        deps = Dependencies(env, output, state, local=False)
        with deps.started():
            run_integration(deps)
