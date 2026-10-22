#!/usr/bin/env python3
"""STUDYROOM installation, local development and release operations."""
import argparse
import contextlib
import datetime as dt
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / "infra/compose/compose.yml"


def run(args, *, env=None, capture=False, cwd=ROOT, **kwargs):
    kwargs.setdefault("text", True)
    kwargs.setdefault("check", True)
    return subprocess.run([str(x) for x in args], cwd=cwd, env=env,
                          stdout=subprocess.PIPE if capture else None, **kwargs)


def config(environment):
    state = Path(os.environ.get("STUDYROOM_STATE_DIR", ROOT / ".local" / environment)).resolve()
    path = state / "compose.env"
    if not path.exists():
        run([sys.executable, ROOT / "scripts/init-env", environment, "--directory", state])
    values = {}
    for line in path.read_text().splitlines():
        if line and not line.startswith("#"):
            key, value = line.split("=", 1)
            values[key] = value
    if values.get("ENV_NAME") != environment:
        raise RuntimeError("Environment configuration does not match requested environment")
    release = state / "release.env"
    if release.exists():
        allowed = {"RELEASE_ID", "RELEASE_COMMIT", "CORE_IMAGE", "REPAIR_IMAGE", "WEB_IMAGE", "POSTGRES_IMAGE", "MAILPIT_IMAGE"}
        for line in release.read_text().splitlines():
            if line and not line.startswith("#"):
                key, value = line.split("=", 1)
                if key not in allowed:
                    raise RuntimeError("Unexpected setting in release.env")
                values[key] = value
    (state / "control").mkdir(exist_ok=True)
    return state, values


def compose(environment, *args, native=False, overrides=None, capture=False, **kwargs):
    state, values = config(environment)
    command = ["docker", "compose", "--env-file", state / "compose.env", "-f", BASE]
    if native:
        command += ["-f", ROOT / "infra/compose/development.yml"]
    return run(command + list(args), env={**os.environ, **values, **(overrides or {})}, capture=capture, **kwargs)


def secret(state, name):
    return (state / "secrets" / name).read_text().strip()


def java_env():
    env = os.environ.copy()
    if not env.get("JAVA_HOME"):
        brew_java = Path("/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home")
        if brew_java.exists():
            env["JAVA_HOME"] = str(brew_java)
    if env.get("JAVA_HOME"):
        env["PATH"] = env["JAVA_HOME"] + "/bin:" + env["PATH"]
    return env


def native_env(environment, service):
    state, values = config(environment)
    env = {**java_env(), **values}
    role = "migration" if service == "migration" else service
    env.update(DB_URL=f"jdbc:postgresql://localhost:{values['DB_PORT']}/studyroom",
               DB_USERNAME=f"studyroom_{role}", DB_PASSWORD=secret(state, f"{role}_db_password"),
               INTERNAL_PASSWORD=secret(state, "internal_password"),
               CHECKIN_CODE_ENCRYPTION_KEY=secret(state, "checkin_key"),
               CORE_INTERNAL_URL="http://localhost:8081", CONTROL_DIR=str(state / "control"),
               SMTP_HOST="localhost", SMTP_PORT=values["SMTP_PORT_HOST"],
               GIT_COMMIT=values.get("RELEASE_COMMIT", "working-tree"))
    if environment != "prod":
        env["DEMO_PASSWORD"] = secret(state, "demo_password")
    env["SERVER_PORT"] = "8081" if service == "core" else "8082"
    return env


def jar(service):
    directory = ROOT / ("tools/migration-runner" if service == "migration" else f"services/{service}-service") / "target"
    jars = [p for p in directory.glob("*.jar") if not p.name.endswith(("-sources.jar", "-javadoc.jar"))]
    if len(jars) != 1:
        raise RuntimeError(f"Build the backend first: expected one jar in {directory}")
    return jars[0]


def migrate(environment, native=False, action="migrate", overrides=None):
    if action == "demo" and environment == "prod":
        raise RuntimeError("Demo seed is forbidden in prod")
    if native:
        run(["java", "-jar", jar("migration"), action], env=native_env(environment, "migration"))
    else:
        extra = []
        if action == "demo":
            state, _ = config(environment)
            extra += ["-e", "DEMO_PASSWORD"]
            overrides = {**(overrides or {}), "DEMO_PASSWORD": secret(state, "demo_password")}
        compose(environment, "run", "--rm", *extra, "migrate", action, overrides=overrides)
    if action == "migrate":
        sql = "REVOKE UPDATE,DELETE ON core.audit_event FROM studyroom_core; REVOKE UPDATE,DELETE ON repair.audit_event FROM studyroom_repair;"
        compose(environment, "exec", "-T", "postgres", "psql", "-v", "ON_ERROR_STOP=1", "-U", "studyroom_bootstrap", "-d", "studyroom", "-c", sql)


def wait_http(url, timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=3) as response:
                if response.status == 200:
                    return
        except (OSError, ValueError):
            pass
        time.sleep(1)
    raise RuntimeError(f"Readiness timed out: {url}")


def wait_status(url, expected, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=3) as response:
                if response.status == expected:
                    return
        except urllib.error.HTTPError as error:
            if error.code == expected:
                return
        except OSError:
            pass
        time.sleep(0.2)
    raise RuntimeError(f"Public endpoint did not reach HTTP {expected}: {url}")


def web_command(*args):
    command = ["npx", "--yes", "pnpm@10.34.7", *args]
    version = run(["node", "--version"], capture=True).stdout.strip()
    if not version.startswith("v24."):
        if shutil.which("fnm"):
            command = ["fnm", "exec", "--using=24.22.1", *command]
        else:
            raise RuntimeError("Node 24 is required. Install it or use ./install.sh for Docker-only installation.")
    return command


def build_backend():
    run([ROOT / "mvnw", "-B", "-ntp", "-DskipTests", "package"], env=java_env())


def native_stop(environment):
    if environment == "prod":
        raise RuntimeError("Native development mode is not a production deployment")
    state, _ = config(environment)
    owned = []
    for service in ("repair", "core"):
        pidfile = state / (service + ".pid")
        if not pidfile.exists():
            continue
        pid = int(pidfile.read_text())
        result = run(["ps", "-p", pid, "-o", "command="], capture=True, check=False)
        if not result.stdout.strip():
            pidfile.unlink()
            continue
        if str(state / "run" / (service + ".jar")) not in result.stdout:
            raise RuntimeError("PID file belongs to a different process; refusing to stop it")
        os.kill(pid, signal.SIGTERM)
        owned.append((pid, pidfile))
    deadline = time.monotonic() + 60
    while owned and time.monotonic() < deadline:
        remaining = []
        for pid, pidfile in owned:
            result = run(["ps", "-p", pid, "-o", "stat="], capture=True, check=False)
            if not result.stdout.strip() or result.stdout.strip().startswith("Z"):
                pidfile.unlink(missing_ok=True)
            else:
                remaining.append((pid, pidfile))
        owned = remaining
        if owned:
            time.sleep(0.25)
    if owned:
        raise RuntimeError("Backend did not stop gracefully; no process was force-killed")


def native_up(environment):
    if environment == "prod":
        raise RuntimeError("Native development mode is not a production deployment")
    state, _ = config(environment)
    compose(environment, "up", "-d", "--wait", "postgres", "mailpit", native=True)
    build_backend()
    migrate(environment, native=True)
    migrate(environment, native=True, action="reference")
    migrate(environment, native=True, action="demo")
    for service in ("core", "repair"):
        pidfile = state / f"{service}.pid"
        if pidfile.exists():
            try:
                os.kill(int(pidfile.read_text()), 0)
                continue
            except ProcessLookupError:
                pass
        log = open(state / f"{service}.log", "a")
        run_dir = state / "run"
        run_dir.mkdir(exist_ok=True)
        runtime_jar = run_dir / f"{service}.jar"
        shutil.copy2(jar(service), runtime_jar)
        process = subprocess.Popen(["java", "-jar", str(runtime_jar)], cwd=ROOT,
                                   env=native_env(environment, service), stdout=log, stderr=log, start_new_session=True)
        pidfile.write_text(str(process.pid))
        wait_http(f"http://localhost:{8081 if service == 'core' else 8082}/actuator/health/readiness")
    print("Backend ready: core localhost:8081, repair localhost:8082")
    print("Run ./scripts/web dev for the frontend. Mail inbox: http://localhost:" + config(environment)[1]["MAIL_UI_PORT"])


def install(environment):
    if environment == "prod":
        raise RuntimeError("Production requires configured TLS/SMTP and a tested release; use bootstrap then deploy")
    state, _ = config(environment)
    if (state / "current-release.json").exists():
        raise RuntimeError("This environment uses a released image set; use deploy with a new manifest")
    compose(environment, "up", "-d", "--wait", "postgres", "mailpit")
    compose(environment, "build", "core", "repair", "web")
    for action in ("migrate", "reference", "demo"):
        migrate(environment, action=action)
    compose(environment, "up", "-d", "--wait", "--wait-timeout", "180", "core", "repair", "web")
    print("Installed: " + config(environment)[1]["PUBLIC_BASE_URL"])


def digest(path):
    h = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def image_id(ref):
    return run(["docker", "image", "inspect", "--format", "{{.Id}}", ref], capture=True).stdout.strip()


def validate_manifest_fields(manifest):
    def matches(pattern, value):
        return isinstance(value, str) and re.fullmatch(pattern, value) is not None
    if not isinstance(manifest, dict):
        raise RuntimeError("Release manifest must be a JSON object")
    if not matches(r"[A-Za-z0-9][A-Za-z0-9._-]{0,95}", manifest.get("releaseId")):
        raise RuntimeError("Invalid releaseId: use 1–96 safe letters, digits, dots, hyphens or underscores")
    if not matches(r"[0-9a-f]{40}", manifest.get("commit")):
        raise RuntimeError("Invalid release commit: expected 40 lowercase hexadecimal characters")
    if not matches(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", manifest.get("rollbackCompatibility")):
        raise RuntimeError("Invalid rollbackCompatibility: expected a nonempty safe token")
    images = manifest.get("images")
    if not isinstance(images, dict) or set(images) != {"core", "repair", "web"}:
        raise RuntimeError("Release must contain exactly core, repair and web")
    runtimes = manifest.get("runtimeImages", {})
    if not isinstance(runtimes, dict) or not set(runtimes) <= {"postgres", "mailpit"}:
        raise RuntimeError("Invalid runtimeImages in release manifest")
    for name, info in {**images, **runtimes}.items():
        if not isinstance(info, dict) or not matches(r"sha256:[0-9a-f]{64}", info.get("id")):
            raise RuntimeError(f"Invalid image content ID: {name}")
        if not matches(r"[A-Za-z0-9][A-Za-z0-9._:/@-]{0,511}", info.get("ref")):
            raise RuntimeError(f"Invalid image reference: {name}")


def object_digest(value):
    """Stable identities for manifests and observed schema, independent of formatting."""
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def write_json(path, value):
    path = Path(path)
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("w") as stream:
        json.dump(value, stream, indent=2)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def database_inventory(environment):
    """Read PostgreSQL/Flyway state; never infer the database version from running apps."""
    def query(sql):
        return json.loads(compose(environment, "exec", "-T", "postgres", "psql", "-X", "-v", "ON_ERROR_STOP=1",
                                  "-U", "studyroom_bootstrap", "-d", "studyroom", "-At", "-c", sql, capture=True).stdout)
    metadata = query("SELECT json_build_object('postgresVersion',current_setting('server_version_num'),"
                     "'schemas',ARRAY(SELECT table_schema FROM information_schema.tables WHERE "
                     "table_name='flyway_schema_history' AND table_schema IN ('session','core','repair') ORDER BY table_schema))")
    migrations = []
    for schema in metadata["schemas"]:
        if schema not in {"session", "core", "repair"}:
            raise RuntimeError("Unexpected migration schema")
        rows = query("SELECT coalesce(json_agg(row_to_json(m) ORDER BY installed_rank),'[]'::json) FROM "
                     f"(SELECT installed_rank,version,description,type,script,checksum,success FROM {schema}.flyway_schema_history) m")
        migrations.extend({"schema": schema, **row} for row in rows)
    return {"postgresVersion": metadata["postgresVersion"], "migrations": migrations}


def schema_context(state, previous):
    """A failed migration may have committed earlier scripts in another schema."""
    recorded_path, pending_path = state / "schema-release.json", state / "pending-release.json"
    recorded = json.loads(recorded_path.read_text()) if recorded_path.exists() else None
    pending = json.loads(pending_path.read_text()) if pending_path.exists() else None
    if previous:
        validate_manifest_fields(previous)
    if recorded and (recorded.get("manifestSha256") != object_digest(recorded["manifest"])
                     or recorded.get("databaseSha256") != object_digest(recorded["database"])):
        raise RuntimeError("Recorded database schema state is corrupt")
    source = recorded["manifest"] if recorded else previous
    if source:
        validate_manifest_fields(source)
    known = dict(source.get("migrations", {})) if source else {}
    if pending:
        # Read old installations' raw pending manifest conservatively as well.
        attempted = pending.get("schemaManifest", pending.get("manifest", pending))
        validate_manifest_fields(attempted)
        for name, checksum in attempted.get("migrations", {}).items():
            if name in known and known[name] != checksum:
                raise RuntimeError("Pending release conflicts with recorded schema checksums")
            known[name] = checksum
        source = attempted
    return {"manifest": source, "migrations": known, "recorded": recorded, "pending": pending}


def compatibility_proof(state, source, target, database=None):
    if source.get("rollbackCompatibility") != target.get("rollbackCompatibility"):
        raise RuntimeError("Rollback compatibility families differ")
    proof_path = state / "compatibility" / (source["releaseId"] + "--" + target["releaseId"] + ".json")
    if not proof_path.is_file():
        raise RuntimeError("Schema-changing rollback requires a recorded test compatibility proof")
    proof = json.loads(proof_path.read_text())
    if proof.get("fromCommit") != source["commit"] or proof.get("toCommit") != target["commit"] or proof.get("result") != "passed":
        raise RuntimeError("Rollback compatibility proof does not match both commits")
    checks = {"threeImageIds", "threeVersions", "oldSessionCore", "oldSessionRepair",
              "businessData", "oldVersionWrite", "expandedColumnPreserved"}
    if (proof.get("formatVersion") != 2 or proof.get("fromManifestSha256") != object_digest(source)
            or proof.get("toManifestSha256") != object_digest(target)
            or proof.get("fromImages") != {k: v["id"] for k, v in source["images"].items()}
            or proof.get("toImages") != {k: v["id"] for k, v in target["images"].items()}
            or not isinstance(proof.get("database"), dict)
            or proof.get("databaseSha256") != object_digest(proof["database"])
            or any(proof.get("checks", {}).get(check) is not True for check in checks)):
        raise RuntimeError("Rollback proof must bind exact manifests, images, database and successful compatibility checks")
    if database is not None and proof["databaseSha256"] != object_digest(database):
        raise RuntimeError("Rollback proof differs from the actual PostgreSQL/Flyway state")
    return proof


def release_preflight(state, previous, manifest, rollback):
    context = schema_context(state, previous)
    if rollback and not previous:
        raise RuntimeError("Rollback requires an existing recorded deployment")
    if rollback and context["manifest"] and context["manifest"].get("rollbackCompatibility") != manifest.get("rollbackCompatibility"):
        raise RuntimeError("Rollback compatibility families differ")
    if not rollback:
        if any(manifest.get("migrations", {}).get(name) != checksum for name, checksum in context["migrations"].items()):
            raise RuntimeError("Deploy cannot remove or change known database migrations; use a verified rollback")
    elif context["manifest"] and manifest.get("migrations") != context["migrations"]:
        compatibility_proof(state, context["manifest"], manifest)
    return context


def record_schema(state, manifest, database):
    write_json(state / "schema-release.json", {"formatVersion": 1, "manifest": manifest,
               "manifestSha256": object_digest(manifest), "database": database,
               "databaseSha256": object_digest(database), "recordedAt": dt.datetime.now(dt.timezone.utc).isoformat()})


def verify_running_release(environment, manifest):
    for service, image in manifest["images"].items():
        container = compose(environment, "ps", "-q", service, capture=True).stdout.strip()
        if not container or "\n" in container:
            raise RuntimeError(f"Expected one running release container: {service}")
        actual = run(["docker", "inspect", "--format", "{{.Image}}", container], capture=True).stdout.strip()
        if actual != image["id"]:
            raise RuntimeError(f"Running image identity mismatch: {service}")
        if service == "web":
            version = json.loads(compose(environment, "exec", "-T", service, "cat", "/usr/share/nginx/html/version.json", capture=True).stdout)
        else:
            version = json.loads(compose(environment, "exec", "-T", service, "curl", "-fsS", "http://localhost:8080/api/v1/version", capture=True).stdout)["data"]
        if version.get("commit") != manifest["commit"] or version.get("releaseId") != manifest["releaseId"]:
            raise RuntimeError(f"Release version mismatch: {service}")


def write_release_environment(state, overrides):
    temporary = state / "release.env.tmp"
    temporary.write_text("\n".join(f"{k}={v}" for k, v in overrides.items()) + "\n")
    os.replace(temporary, state / "release.env")


def recover_previous(environment, state, previous, attempt, configured, overrides):
    """Called only inside the existing deployment lock, after successful migrations."""
    verified = verify_manifest(state / "current-release.json", allow_older_schema=True)
    if object_digest(verified) != object_digest(previous):
        raise RuntimeError("Previous release changed during failed deployment")
    context = schema_context(state, previous)
    source = context["manifest"]
    if not source or source.get("rollbackCompatibility") != previous.get("rollbackCompatibility"):
        raise RuntimeError("Automatic recovery compatibility families differ")
    actual_database = database_inventory(environment)
    if previous.get("migrations") != context["migrations"]:
        compatibility_proof(state, source, previous, actual_database)
    elif context["recorded"] and context["recorded"]["databaseSha256"] != object_digest(actual_database):
        raise RuntimeError("Automatic recovery detected unrecorded PostgreSQL/Flyway state")
    restored = {**overrides, **{name.upper() + "_IMAGE": image["ref"] for name, image in previous["images"].items()},
                "RELEASE_ID": previous["releaseId"], "RELEASE_COMMIT": previous["commit"]}
    compose(environment, "stop", "-t", "60", "core", "repair", "web")
    compose(environment, "up", "-d", "--wait", "--wait-timeout", "180", "core", "repair", "web", overrides=restored)
    verify_running_release(environment, previous)
    smoke(environment)
    (state / "control/maintenance").unlink(missing_ok=True)
    wait_status(configured["PUBLIC_BASE_URL"], 200)
    # Keep the expanded database record, and retain the failed release's full evidence.
    record_schema(state, source, actual_database)
    attempt["recovery"] = {"result": "passed", "manifestSha256": object_digest(previous),
                           "releaseId": previous["releaseId"], "databaseSha256": object_digest(actual_database)}
    failures = state / "release-failures"
    failures.mkdir(exist_ok=True)
    name = attempt["manifest"]["releaseId"] + "-" + dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ") + ".json"
    write_json(failures / name, attempt)
    write_json(state / "current-release.json", previous)
    write_release_environment(state, restored)
    (state / "pending-release.json").unlink(missing_ok=True)
    (state / "control/jobs-paused").unlink(missing_ok=True)


def build_release(release_id):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,95}", release_id):
        raise RuntimeError("Invalid release ID")
    if run(["git", "status", "--porcelain"], capture=True).stdout.strip():
        raise RuntimeError("Release builds require a clean committed checkout")
    commit = run(["git", "rev-parse", "HEAD"], capture=True).stdout.strip()
    directory = ROOT / ".local/releases" / release_id
    directory.mkdir(parents=True, exist_ok=False)
    overrides = {"RELEASE_ID": release_id, "RELEASE_COMMIT": commit,
                 "CORE_IMAGE": f"studyroom-core:{release_id}", "REPAIR_IMAGE": f"studyroom-repair:{release_id}", "WEB_IMAGE": f"studyroom-web:{release_id}"}
    compose("local", "build", "core", "repair", "web", overrides=overrides)
    images = {name: {"ref": overrides[name.upper() + "_IMAGE"], "id": image_id(overrides[name.upper() + "_IMAGE"])} for name in ("core", "repair", "web")}
    manifest = {"releaseId": release_id, "commit": commit, "createdAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                "images": images, "composeSha256": digest(BASE), "dependencyBaselineSha256": digest(ROOT / "docs/dependency-baseline.json"),
                "migrations": {str(p.relative_to(ROOT)): digest(p) for p in sorted((ROOT / "db/migration").rglob("*.sql"))},
                "referenceSeed": "1", "rollbackCompatibility": "expand-only-v1", "transport": "docker-save-content-addressed"}
    (directory / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(directory / "manifest.json")


def verify_manifest(path, *, allow_older_schema=False):
    manifest = json.loads(Path(path).read_text())
    validate_manifest_fields(manifest)
    if manifest["composeSha256"] != digest(BASE):
        raise RuntimeError("Compose configuration differs from release manifest")
    if manifest["dependencyBaselineSha256"] != digest(ROOT / "docs/dependency-baseline.json"):
        raise RuntimeError("Dependency baseline differs from release manifest")
    expected_migrations = {str(p.relative_to(ROOT)): digest(p) for p in sorted((ROOT / "db/migration").rglob("*.sql"))}
    recorded = manifest.get("migrations", {})
    if not recorded or any(expected_migrations.get(name) != sha for name, sha in recorded.items()) or (not allow_older_schema and recorded != expected_migrations):
        raise RuntimeError("Migration contents differ from release manifest")
    if set(manifest["images"]) != {"core", "repair", "web"}:
        raise RuntimeError("Release must contain exactly core, repair and web")
    for name, info in manifest["images"].items():
        if image_id(info["ref"]) != info["id"]:
            raise RuntimeError(f"Image identity mismatch: {name}")
    return manifest


@contextlib.contextmanager
def environment_lock(environment):
    state, _ = config(environment)
    with open(state / "deployment.lock", "a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        yield state


def backup(environment):
    state, _ = config(environment)
    directory = state / "backups"
    directory.mkdir(exist_ok=True, mode=0o700)
    name = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    path = directory / f"{name}.dump"
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as output:
        command = ["docker", "compose", "--env-file", str(state / "compose.env"), "-f", str(BASE), "exec", "-T", "postgres", "pg_dump", "-U", "studyroom_bootstrap", "-d", "studyroom", "-Fc"]
        subprocess.run(command, check=True, cwd=ROOT, stdout=output)
    os.chmod(path, 0o600)
    compose(environment, "exec", "-T", "postgres", "pg_restore", "--list", input=path.read_bytes(), text=False, capture=True)
    (directory / f"{name}.json").write_text(json.dumps({"file": path.name, "sha256": digest(path), "createdAt": name}, indent=2))
    print(path)
    return path



def restore_isolated(environment, backup_path):
    state, _ = config(environment)
    path = Path(backup_path).resolve()
    meta = json.loads(path.with_suffix(".json").read_text())
    if digest(path) != meta["sha256"]:
        raise RuntimeError("Backup checksum mismatch")
    database = "studyroom_restore_" + dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d%H%M%S")
    with environment_lock(environment):
        compose(environment, "exec", "-T", "postgres", "createdb", "-U", "studyroom_bootstrap", "--owner", "studyroom_migration", database)
        compose(environment, "exec", "-T", "postgres", "pg_restore", "-U", "studyroom_bootstrap", "--exit-on-error", "-d", database, input=path.read_bytes(), text=False)
        summary = compose(environment, "exec", "-T", "postgres", "psql", "-U", "studyroom_bootstrap", "-d", database, "-At", "-c", "SELECT 'users='||count(*) FROM core.app_user UNION ALL SELECT 'reservations='||count(*) FROM core.reservation UNION ALL SELECT 'applications='||count(*) FROM core.room_application UNION ALL SELECT 'repairs='||count(*) FROM repair.repair_order;", capture=True).stdout
        report = {"backup": path.name, "sha256": meta["sha256"], "isolatedDatabase": database, "counts": summary.splitlines(), "result": "restored"}
        (state / "backups" / (database + ".json")).write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(report, indent=2))
        print("The live database was not modified. The isolated restored database remains available for inspection.")


def wait_drained(environment, timeout=60):
    running = set(compose(environment, "ps", "--status", "running", "--services", capture=True).stdout.split())
    services = [service for service in ("core", "repair") if service in running]
    deadline = time.monotonic() + timeout
    stable = 0
    while time.monotonic() < deadline:
        drained = True
        for service in services:
            result = compose(environment, "exec", "-T", service, "curl", "-fsS", "http://localhost:8080/actuator/jobs", capture=True)
            state = json.loads(result.stdout)
            drained = drained and state.get("paused") is True and state.get("active") == 0 and state.get("activeRequests") == 0
        stable = stable + 1 if drained else 0
        if stable >= 2:
            return
        time.sleep(1)
    raise RuntimeError("Requests or background tasks did not drain; deployment was not started")


def smoke(environment):
    # The private network has no host-published backend ports. These GET probes are usable during maintenance.
    for service, endpoint in (("core", "/api/v1/auth/csrf"), ("repair", "/api/v1/repairs/version")):
        result = compose(environment, "exec", "-T", service, "curl", "-fsS", "http://localhost:8080" + endpoint, capture=True)
        payload = json.loads(result.stdout)
        if "data" not in payload:
            raise RuntimeError("Private smoke failed: " + service)
    for path in ("/internal/v1/resource-commands", "/actuator/jobs"):
        result = compose(environment, "exec", "-T", "web", "wget", "-S", "-O", "/dev/null", "http://127.0.0.1:8080" + path, capture=True, stderr=subprocess.PIPE, check=False)
        if "404" not in result.stderr:
            raise RuntimeError("Public proxy exposes a private path: " + path)


def deploy(environment, path, rollback=False, runtime_images=None):
    if environment == "prod":
        raise RuntimeError("Production is outside the current local delivery. Configure approved TLS/SMTP/deployment gates before enabling prod.")
    manifest = verify_manifest(path, allow_older_schema=rollback)
    if runtime_images and set(runtime_images) != {"POSTGRES_IMAGE", "MAILPIT_IMAGE"}:
        raise RuntimeError("Offline runtime override must contain PostgreSQL and Mailpit")
    with environment_lock(environment) as state:
        current = state / "current-release.json"
        previous = json.loads(current.read_text()) if current.exists() else None
        context = release_preflight(state, previous, manifest, rollback)
        pending = state / "pending-release.json"
        control = state / "control"
        flags = {name: (control / name).exists() for name in ("maintenance", "jobs-paused")}
        for name in flags:
            (control / name).touch()
        overrides = {name.upper() + "_IMAGE": info["ref"] for name, info in manifest["images"].items()}
        _, configured = config(environment)
        for key in ("POSTGRES_IMAGE", "MAILPIT_IMAGE"):
            if key in configured:
                overrides[key] = configured[key]
        overrides.update(runtime_images or {})
        overrides.update(RELEASE_ID=manifest["releaseId"], RELEASE_COMMIT=manifest["commit"])
        shutdown_started = False
        attempt = None
        try:
            compose(environment, "up", "-d", "--wait", "postgres", "mailpit", overrides=overrides)
            running = compose(environment, "ps", "--status", "running", "--services", capture=True).stdout.split()
            if "web" in running:
                wait_status(configured["PUBLIC_BASE_URL"] + "/api/v1/auth/csrf", 503)
            wait_drained(environment)
            before = database_inventory(environment)
            recorded = context["recorded"]
            if recorded and not context["pending"] and recorded["databaseSha256"] != object_digest(before):
                raise RuntimeError("Actual PostgreSQL/Flyway state differs from the recorded release; investigate schema drift")
            if rollback and context["manifest"] and manifest.get("migrations") != context["migrations"]:
                compatibility_proof(state, context["manifest"], manifest, before)
            # Preserve the actual schema independently of whichever applications run next.
            if context["manifest"]:
                record_schema(state, context["manifest"], before)
            attempt = {"formatVersion": 2, "operation": "rollback" if rollback else "deploy", "manifest": manifest,
                       "schemaManifest": context["manifest"] or manifest,
                       "phase": "stopping", "databaseBefore": before, "startedAt": dt.datetime.now(dt.timezone.utc).isoformat()}
            write_json(pending, attempt)
            shutdown_started = True
            compose(environment, "stop", "-t", "60", "core", "repair")
            backup(environment)
            if not rollback:
                attempt["phase"] = "migration_started"
                attempt["schemaManifest"] = manifest
                write_json(pending, attempt)
                migrate(environment, overrides=overrides)
                migrated = database_inventory(environment)
                record_schema(state, manifest, migrated)
                attempt.update(phase="migrated", databaseAfter=migrated)
                write_json(pending, attempt)
                migrate(environment, action="reference", overrides=overrides)
                if previous is None:
                    migrate(environment, action="demo", overrides=overrides)
            attempt["phase"] = "starting"
            write_json(pending, attempt)
            compose(environment, "up", "-d", "--wait", "--wait-timeout", "180", "core", "repair", "web", overrides=overrides)
            verify_running_release(environment, manifest)
            smoke(environment)
            attempt["phase"] = "private_checks_passed"
            write_json(pending, attempt)
            # A failed public reopen must keep the attempt and maintenance state recoverable.
            (control / "maintenance").unlink(missing_ok=True)
            wait_status(configured["PUBLIC_BASE_URL"], 200)
            history = state / "release-history"
            history.mkdir(exist_ok=True)
            write_json(history / (manifest["releaseId"] + ".json"), manifest)
            if previous:
                write_json(state / "previous-release.json", previous)
            write_json(current, manifest)
            write_release_environment(state, overrides)
            pending.unlink(missing_ok=True)
            (control / "jobs-paused").unlink(missing_ok=True)
            print(f"Deployed {manifest['releaseId']} to {environment}")
        except Exception as error:
            if shutdown_started:
                for name in flags:
                    (control / name).touch()
                if attempt:
                    attempt.update(failed=True, errorType=type(error).__name__)
                    try:
                        attempt["databaseAfterFailure"] = database_inventory(environment)
                    except Exception:
                        attempt["databaseAfterFailureUnavailable"] = True
                    write_json(pending, attempt)
                recovered = False
                if not rollback and previous and attempt and attempt["phase"] == "starting":
                    try:
                        recover_previous(environment, state, previous, attempt, configured, overrides)
                        recovered = True
                    except Exception as recovery_error:
                        for name in flags:
                            (control / name).touch()
                        attempt["recovery"] = {"result": "blocked_or_failed", "errorType": type(recovery_error).__name__}
                        write_json(pending, attempt)
                if recovered:
                    print("New release failed; verified previous applications were restored. Expanded database and failure evidence were preserved.", file=sys.stderr)
                else:
                    print("Deployment failed; maintenance and job pause remain enabled. A verified rollback is required.", file=sys.stderr)
            else:
                # Retrying a previously failed release must not reopen its broken application set.
                for name, existed in flags.items():
                    if existed or context["pending"]:
                        (control / name).touch()
                    else:
                        (control / name).unlink(missing_ok=True)
                print("Deployment stopped before application shutdown; prior maintenance state was preserved.", file=sys.stderr)
            raise


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "web":
        run(web_command(*sys.argv[2:]), cwd=ROOT / "apps/web")
        return
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["deps", "native-up", "native-stop", "native-restart", "install", "migrate", "seed", "check", "web", "status", "stop", "build-release", "deploy", "rollback", "backup", "restore", "export-release"])
    parser.add_argument("arguments", nargs="*")
    options = parser.parse_args()
    command, args = options.command, options.arguments
    environment = args[0] if args else "local"
    if command == "web":
        run(web_command(*args), cwd=ROOT / "apps/web")
        return
    if command == "build-release":
        build_release(args[0])
        return
    if command == "export-release":
        path = Path(args[0]).resolve()
        manifest = verify_manifest(path)
        from portable_release import export_release
        print(export_release(path, manifest))
        return
    if environment not in ("local", "dev", "test", "prod"):
        raise RuntimeError("Environment must be local, dev, test or prod")
    if command in ("deps", "native-up", "native-stop", "native-restart", "install", "migrate", "seed", "stop"):
        with environment_lock(environment):
            if command == "deps":
                compose(environment, "up", "-d", "--wait", "postgres", "mailpit", native=True)
            elif command == "native-up":
                native_up(environment)
            elif command == "native-stop":
                native_stop(environment)
            elif command == "native-restart":
                native_stop(environment)
                native_up(environment)
            elif command == "install":
                install(environment)
            elif command == "migrate":
                migrate(environment, native=True)
            elif command == "seed":
                migrate(environment, native=True, action=args[1] if len(args) > 1 else "reference")
            else:
                compose(environment, "stop")
    elif command == "status":
        compose(environment, "ps")
    elif command == "check":
        run([ROOT / "mvnw", "-B", "-ntp", "verify"], env=java_env())
        run(web_command("generate"), cwd=ROOT / "apps/web")
        run(["git", "diff", "--exit-code", "--", "apps/web/src/services/generated"])
        run(web_command("typecheck"), cwd=ROOT / "apps/web")
        run(web_command("test"), cwd=ROOT / "apps/web")
        run(web_command("build"), cwd=ROOT / "apps/web")
        compose(environment, "config", "--quiet")
        run([sys.executable, "-m", "unittest", "discover", "-s", "tests/ops", "-v"])
    elif command == "backup":
        with environment_lock(environment):
            backup(environment)
    elif command == "restore":
        restore_isolated(environment, args[1])
    elif command in ("deploy", "rollback"):
        deploy(environment, args[1], rollback=command == "rollback")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError, OSError, ValueError) as error:
        print(f"STUDYROOM: {error}", file=sys.stderr)
        sys.exit(1)
