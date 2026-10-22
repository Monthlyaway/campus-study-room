#!/usr/bin/env python3
"""Deploy both built services into an isolated GitLab job and exercise real HTTP."""
import base64
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent.parent
output = root / "output/ci"
output.mkdir(parents=True, exist_ok=True)
env = os.environ.copy()
env.update(PGUSER="studyroom_bootstrap", PGDATABASE="studyroom", PGPASSWORD=env["POSTGRES_PASSWORD"],
           CORE_DB_PASSWORD=secrets.token_urlsafe(32), REPAIR_DB_PASSWORD=secrets.token_urlsafe(32),
           MIGRATION_DB_PASSWORD=secrets.token_urlsafe(32))
for attempt in range(60):
    if subprocess.run(["pg_isready"], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
        break
    time.sleep(1)
else:
    raise RuntimeError("PostgreSQL service was not ready")
sql = (root / "infra/compose/init-db.sh").read_text().split("<<'SQL'\n", 1)[1].rsplit("\nSQL", 1)[0]
subprocess.run(["psql", "-v", "ON_ERROR_STOP=1"], input=sql, env=env, text=True, check=True)
demo_password = secrets.token_urlsafe(24)
common = {**env, "DB_URL": f"jdbc:postgresql://{env.get('PGHOST','postgres')}:{env.get('PGPORT','5432')}/studyroom",
          "ENV_NAME": "test", "DEMO_PASSWORD": demo_password, "COOKIE_SECURE": "false",
          "CHECKIN_CODE_ENCRYPTION_KEY": base64.b64encode(secrets.token_bytes(32)).decode(),
          "INTERNAL_USERNAME": "repair-service", "INTERNAL_PASSWORD": secrets.token_urlsafe(36),
          "MAIL_MODE": "capture", "MAIL_DIRECTORY": str(output / "mail"),
          "CONTROL_DIR": str(output / "control"), "CORE_INTERNAL_URL": "http://localhost:8081",
          "STUDYROOM_CORE_LOG": str(output / "core.log"), "STUDYROOM_REPAIR_LOG": str(output / "repair.log"),
          "RELEASE_ID": "ci-" + env.get("CI_PIPELINE_ID", "local"), "GIT_COMMIT": env.get("CI_COMMIT_SHA", "ci")}


def artifact(pattern):
    jars = list(root.glob(pattern))
    if len(jars) != 1:
        raise RuntimeError(f"Expected one artifact: {pattern}")
    return str(jars[0])


for action in ("migrate", "reference", "demo", "migrate", "reference", "demo"):
    subprocess.run(["java", "-jar", artifact("tools/migration-runner/target/*.jar"), action],
                   env={**common, "DB_USERNAME": "studyroom_migration", "DB_PASSWORD": env["MIGRATION_DB_PASSWORD"]}, check=True)
subprocess.run(["psql", "-v", "ON_ERROR_STOP=1", "-c", "REVOKE UPDATE,DELETE ON core.audit_event FROM studyroom_core; REVOKE UPDATE,DELETE ON repair.audit_event FROM studyroom_repair;"], env=env, check=True)
processes = []
try:
    for service, port in (("core", 8081), ("repair", 8082)):
        log = open(output / f"{service}.log", "w")
        service_env = {**common, "SERVER_PORT": str(port), "PORT": str(port), "DB_USERNAME": f"studyroom_{service}", "DB_PASSWORD": env[f"{service.upper()}_DB_PASSWORD"]}
        processes.append(subprocess.Popen(["java", "-jar", artifact(f"services/{service}-service/target/*.jar")], env=service_env, stdout=log, stderr=log))
        for attempt in range(120):
            try:
                with urllib.request.urlopen(f"http://localhost:{port}/actuator/health/readiness", timeout=2) as response:
                    if response.status == 200:
                        break
            except OSError:
                time.sleep(1)
        else:
            raise RuntimeError(f"{service} failed readiness; see artifact log")
    tests = sorted((root / "tests/integration").glob("*_http.py"))
    if len(tests) < 2:
        raise RuntimeError("Both core and repair real HTTP suites must be present")
    for test in tests:
        with open(output / (test.stem + ".txt"), "w") as report:
            subprocess.run([sys.executable, str(test)], env={**common, "STUDYROOM_TEST_PASSWORD": demo_password}, stdout=report, stderr=subprocess.STDOUT, check=True)
    # Every JPA suite gets its own migrated database and a paused scheduler.
    persistence = [("core", "CorePersistenceTest"), ("core", "AdminPersistenceTest"),
                   ("core", "SharedPersistenceTest"), ("repair", "RepairPersistenceTest")]
    for service, test_class in persistence:
        database = "studyroom_ci_" + test_class.lower()
        subprocess.run(["createdb", "--owner=studyroom_migration", database], env=env, check=True)
        db_env = {**env, "PGDATABASE": database}
        grants = "CREATE SCHEMA core AUTHORIZATION studyroom_migration; CREATE SCHEMA repair AUTHORIZATION studyroom_migration; CREATE SCHEMA session AUTHORIZATION studyroom_migration;"
        grants += f"GRANT USAGE ON SCHEMA {service},session TO studyroom_{service};"
        for schema in (service, "session"):
            grants += f"ALTER DEFAULT PRIVILEGES FOR ROLE studyroom_migration IN SCHEMA {schema} GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO studyroom_{service};"
        subprocess.run(["psql", "-v", "ON_ERROR_STOP=1", "-c", grants], env=db_env, check=True)
        jdbc = common["DB_URL"].rsplit("/", 1)[0] + "/" + database
        for action in ("migrate", "reference", "demo"):
            subprocess.run(["java", "-jar", artifact("tools/migration-runner/target/*.jar"), action], env={**common, "DB_URL": jdbc, "DB_USERNAME": "studyroom_migration", "DB_PASSWORD": env["MIGRATION_DB_PASSWORD"]}, check=True)
        subprocess.run(["psql", "-v", "ON_ERROR_STOP=1", "-c", f"REVOKE UPDATE,DELETE ON {service}.audit_event FROM studyroom_{service}"], env=db_env, check=True)
        control = output / (test_class + "-control")
        control.mkdir(exist_ok=True)
        (control / "jobs-paused").touch()
        test_env = {**common, "DB_URL": jdbc, "DB_USERNAME": "studyroom_" + service,
                    "DB_PASSWORD": env[service.upper() + "_DB_PASSWORD"], "CONTROL_DIR": str(control),
                    "STUDYROOM_CORE_DB_TESTS": "true", "STUDYROOM_ADMIN_DB_TESTS": "true", "STUDYROOM_REPAIR_DB_TESTS": "true"}
        with open(output / (test_class + ".txt"), "w") as report:
            subprocess.run([str(root / "mvnw"), "-B", "-ntp", "-pl", "services/" + service + "-service", "-am", "-Dtest=" + test_class,
                            "-Dsurefire.failIfNoSpecifiedTests=false", "test"], env=test_env, stdout=report, stderr=subprocess.STDOUT, check=True)
        reports = list((root / ("services/" + service + "-service/target/surefire-reports")).glob("TEST-*." + test_class + ".xml"))
        if len(reports) != 1:
            raise RuntimeError("Missing exact JUnit evidence for " + test_class)
        evidence = ET.parse(reports[0]).getroot()
        if int(evidence.attrib["tests"]) < 1 or any(int(evidence.attrib.get(key, 0)) for key in ("skipped", "errors", "failures")):
            raise RuntimeError("Persistence suite skipped or failed: " + test_class)
        (output / (test_class + ".xml")).write_bytes(reports[0].read_bytes())
    # Drop a successful upstream BLOCK response and require the durable command to replay unchanged.
    processes[-1].terminate()
    processes[-1].wait(timeout=30)
    events = output / "fault-events.jsonl"
    proxy = subprocess.Popen([sys.executable, str(root / "tests/integration/fault_proxy.py"), "--events", str(events)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    processes.append(proxy)
    repair_env = {**common, "SERVER_PORT": "8082", "DB_USERNAME": "studyroom_repair", "DB_PASSWORD": env["REPAIR_DB_PASSWORD"], "CORE_INTERNAL_URL": "http://localhost:18081"}
    with open(output / "repair-fault.log", "w") as log:
        processes.append(subprocess.Popen(["java", "-jar", artifact("services/repair-service/target/*.jar")], env=repair_env, stdout=log, stderr=log))
    for attempt in range(120):
        try:
            with urllib.request.urlopen("http://localhost:8082/actuator/health/readiness", timeout=2) as response:
                if response.status == 200:
                    break
        except OSError:
            time.sleep(1)
    else:
        raise RuntimeError("Fault test repair process failed readiness")
    with open(output / "repair_fault_http.txt", "w") as report:
        subprocess.run([sys.executable, str(root / "tests/integration/repair_http.py"), "RepairHttpTests.test_02_real_resource_lifecycle_and_multiple_sources"],
                       env={**common, "STUDYROOM_TEST_PASSWORD": demo_password}, stdout=report, stderr=subprocess.STDOUT, check=True)
    observed = [json.loads(line) for line in events.read_text().splitlines()]
    dropped = next(event for event in observed if event["responseDropped"])
    replays = [event for event in observed if event["commandId"] == dropped["commandId"]]
    assert len(replays) >= 2 and len({event["payloadHash"] for event in replays}) == 1
    assert all(event["upstreamStatus"] == 200 for event in replays)
    (output / "result.json").write_text(json.dumps({"commit": common["GIT_COMMIT"], "releaseId": common["RELEASE_ID"], "postgres": "18.6", "realServices": ["core", "repair"], "suites": [p.name for p in tests], "persistenceSuites": [name for _, name in persistence], "responseLossReplay": "passed", "result": "passed"}, indent=2))
finally:
    for process in reversed(processes):
        process.terminate()
    for process in processes:
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
