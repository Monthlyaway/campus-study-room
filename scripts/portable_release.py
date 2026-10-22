#!/usr/bin/env python3
"""Content-checked, single-platform offline release transport (stdlib + Docker)."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parent.parent
PAYLOAD_FILES = {"manifest.json", "portable.json", "images.tar", "source.tar.gz", "INSTALL.md"}
IMAGE_NAMES = {"core", "repair", "web", "postgres", "mailpit"}
SHA = re.compile(r"^[0-9a-f]{64}$")
IMAGE_ID = re.compile(r"^sha256:[0-9a-f]{64}$")
COMMIT = re.compile(r"^[0-9a-f]{40}$")
EXCLUDED = {".git", ".local", "node_modules", "target", "dist", "secrets", "output", ".playwright-cli"}


def run(args, *, cwd=ROOT):
    return subprocess.run([str(x) for x in args], cwd=cwd, check=True, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def platform(os_name, architecture):
    arch = {"x86_64": "amd64", "aarch64": "arm64"}.get(architecture, architecture)
    if os_name != "linux" or arch not in {"amd64", "arm64"}:
        raise RuntimeError(f"Unsupported Docker platform: {os_name}/{arch}; use linux/amd64 or linux/arm64")
    return {"os": os_name, "architecture": arch}


def inspect_image(ref, *, root=ROOT):
    info = json.loads(run(["docker", "image", "inspect", ref], cwd=root))[0]
    image_platform = platform(info["Os"], info["Architecture"])
    if not IMAGE_ID.fullmatch(info["Id"]):
        raise RuntimeError("Docker returned an invalid image content ID")
    return {"id": info["Id"], **image_platform, "variant": info.get("Variant", "")}


def _safe_member(member):
    path = PurePosixPath(member.name)
    if path.is_absolute() or not path.parts or ".." in path.parts or "\\" in member.name:
        raise RuntimeError("Unsafe path in source archive")
    if not (member.isfile() or member.isdir()):
        raise RuntimeError("Source archive must not contain symlinks, hardlinks or special files")
    if any(p in EXCLUDED for p in path.parts) or (path.name.startswith(".env") and path.name != ".env.example"):
        raise RuntimeError(f"Runtime/private file is forbidden in source archive: {member.name}")
    if path.name.endswith((".log", ".pid", ".dump", ".tar", ".tar.gz")):
        raise RuntimeError(f"Generated/runtime artifact is forbidden in source archive: {member.name}")
    return path.as_posix().rstrip("/")


def source_inventory(archive):
    """Validate every archive member before extraction; return hashes and small metadata."""
    files, modes, content = {}, {}, {}
    with tarfile.open(archive, "r:gz") as tar:
        commit = tar.pax_headers.get("comment", "")
        if not COMMIT.fullmatch(commit):
            raise RuntimeError("Source archive has no full git commit provenance")
        seen = set()
        for member in tar.getmembers():
            name = _safe_member(member)
            if name in seen:
                raise RuntimeError(f"Duplicate path in source archive: {name}")
            seen.add(name)
            if member.isfile():
                stream = tar.extractfile(member)
                h = hashlib.sha256()
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    h.update(chunk)
                files[name] = h.hexdigest()
                modes[name] = member.mode & 0o777
                if name == "docs/dependency-baseline.json":
                    if member.size > 1024 * 1024:
                        raise RuntimeError("Dependency baseline is unexpectedly large")
                    content[name] = json.load(tar.extractfile(member))
    required = {"scripts/studyroom.py", "scripts/portable_release.py", "scripts/import-release",
                "scripts/init-env", "infra/compose/compose.yml", "docs/dependency-baseline.json"}
    if not required <= files.keys():
        raise RuntimeError("Release commit does not contain the complete offline installer")
    return {"commit": commit, "files": files, "modes": modes,
            "baseline": content["docs/dependency-baseline.json"]}


def _verify_source_manifest(source, manifest):
    if source["commit"] != manifest["commit"]:
        raise RuntimeError("Source commit differs from release manifest")
    for file, key in (("infra/compose/compose.yml", "composeSha256"),
                      ("docs/dependency-baseline.json", "dependencyBaselineSha256")):
        if source["files"][file] != manifest[key]:
            raise RuntimeError(f"Source content differs from release manifest: {file}")
    migrations = {name: sha for name, sha in source["files"].items()
                  if name.startswith("db/migration/") and name.endswith(".sql")}
    if not migrations or migrations != manifest.get("migrations"):
        raise RuntimeError("Source migrations differ from release manifest")


def export_release(manifest_path, manifest, output_dir=None, *, root=ROOT):
    """Export an already verified application manifest; never include working-tree files."""
    root, manifest_path = Path(root).resolve(), Path(manifest_path).resolve()
    manifest_bytes = manifest_path.read_bytes()
    if json.loads(manifest_bytes) != manifest:
        raise RuntimeError("Manifest changed after verification")
    validate_manifest_fields(manifest)
    if not COMMIT.fullmatch(manifest.get("commit", "")):
        raise RuntimeError("Manifest requires a full committed source SHA")
    if set(manifest.get("images", {})) != {"core", "repair", "web"}:
        raise RuntimeError("Release requires exactly three application images")
    if not set(manifest.get("runtimeImages", {})) <= {"postgres", "mailpit"}:
        raise RuntimeError("Unknown runtime image in release manifest")
    destination = Path(output_dir).resolve() if output_dir else manifest_path.parent / "portable"
    if destination.exists():
        raise RuntimeError("Portable output already exists; choose a new directory")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".portable-", dir=destination.parent) as temp:
        work = Path(temp)
        run(["git", "archive", "--format=tar.gz", "--output", work / "source.tar.gz", manifest["commit"]], cwd=root)
        source = source_inventory(work / "source.tar.gz")
        _verify_source_manifest(source, manifest)
        images = {}
        for name in ("core", "repair", "web", "postgres", "mailpit"):
            expected = manifest["images"].get(name) or manifest.get("runtimeImages", {}).get(name)
            ref = expected["ref"] if expected else source["baseline"]["images"][name]
            if name in {"postgres", "mailpit"} and ref != source["baseline"]["images"][name]:
                raise RuntimeError(f"Runtime reference differs from committed dependency baseline: {name}")
            info = inspect_image(ref, root=root)
            if expected and info["id"] != expected["id"]:
                raise RuntimeError(f"Image changed after verification: {name}")
            alias = ref
            if name in {"postgres", "mailpit"}:
                # Save explicit tags: Docker load need not preserve registry RepoDigests.
                alias = f"studyroom-runtime-{name}:offline-{info['id'][7:]}"
                run(["docker", "tag", ref, alias], cwd=root)
            images[name] = {"sourceRef": ref, "archiveRef": alias, **info}
        platforms = {(x["os"], x["architecture"]) for x in images.values()}
        if len(platforms) != 1:
            raise RuntimeError("All five images must target the same OS and architecture")
        host = run(["docker", "info", "--format", "{{.OSType}}/{{.Architecture}}"], cwd=root).strip().split("/")
        if len(host) != 2 or platform(*host) != platform(*next(iter(platforms))):
            raise RuntimeError("Export Docker architecture differs from the image platform")
        run(["docker", "save", "-o", work / "images.tar", *[x["archiveRef"] for x in images.values()]], cwd=root)
        for name, image in images.items():
            if inspect_image(image["archiveRef"], root=root) != {k: image[k] for k in ("id", "os", "architecture", "variant")}:
                raise RuntimeError(f"Image changed during export: {name}")
        (work / "manifest.json").write_bytes(manifest_bytes)
        portable = {"formatVersion": 1, "releaseId": manifest["releaseId"], "commit": manifest["commit"],
                    "platform": platform(*next(iter(platforms))), "images": images,
                    "artifacts": {name: digest(work / name) for name in ("manifest.json", "source.tar.gz", "images.tar")}}
        (work / "portable.json").write_text(json.dumps(portable, indent=2) + "\n")
        (work / "INSTALL.md").write_text(_instructions(portable))
        (work / "SHA256SUMS").write_text("".join(f"{digest(work / name)}  {name}\n" for name in sorted(PAYLOAD_FILES)))
        # Keep one atomic, complete directory. No credentials or data volumes are copied.
        os.replace(work, destination)
    return destination


def _instructions(portable):
    p = portable["platform"]
    return f"""# 校园自习室离线安装包\n\n版本：{portable['releaseId']}；源码：{portable['commit']}；镜像平台：{p['os']}/{p['architecture']}。\n\n需要 Python 3.10+、Docker Engine/Desktop 与 Docker Compose v2；无需 Git、Java、Node 或网络下载。\n校验和用于检测传输损坏，不替代可信来源或签名验证。应从可信渠道取得整包和校验和。\n\n```sh\n# 在此发布包目录操作，先检查，再解包源码到一个新目录\nsha256sum -c SHA256SUMS  # macOS 可用 shasum -a 256 -c SHA256SUMS\nmkdir campus-study-room\ntar -xzf source.tar.gz -C campus-study-room\n./campus-study-room/scripts/import-release test .\n```\n\n导入器再次验证所有文件、源码与镜像架构，执行 docker load 后校验五个镜像内容 ID，\n随后调用现有部署流程生成目标服务器自己的秘密、迁移和基线数据。已有环境保留数据并先备份。\n默认 test 仅绑定本机 18082，演示密码位于解包源码 .local/test/secrets/demo_password。\n可通过 STUDYROOM_STATE_DIR 指向持久目录；更改端口需在该目录 compose.env 中配置。\n此包不含来源服务器的秘密、账号运行数据或数据库备份，不会迁移已有业务数据。\n\nARM64 包不能直接用于 AMD64 部署；目标不同需构建并导出对应平台的全部五个镜像。\n导入器按 Docker daemon 的平台检查，拒绝用模拟器默默运行另一架构。prod 的正式部署门禁沿用现有工具。\n"""


def verify_bundle(bundle_dir):
    bundle = Path(bundle_dir).resolve()
    checksums = {}
    try:
        for line in (bundle / "SHA256SUMS").read_text().splitlines():
            sha, name = line.split("  ", 1)
            if name in checksums or name not in PAYLOAD_FILES or not SHA.fullmatch(sha):
                raise ValueError("unexpected checksum entry")
            checksums[name] = sha
    except (OSError, ValueError) as error:
        raise RuntimeError("Invalid portable SHA256SUMS") from error
    if set(checksums) != PAYLOAD_FILES:
        raise RuntimeError("Portable checksums must cover every payload file")
    for name, sha in checksums.items():
        path = bundle / name
        if path.is_symlink() or not path.is_file() or digest(path) != sha:
            raise RuntimeError(f"Portable checksum mismatch: {name}")
    manifest = json.loads((bundle / "manifest.json").read_text())
    validate_manifest_fields(manifest)
    portable = json.loads((bundle / "portable.json").read_text())
    if portable.get("formatVersion") != 1 or portable.get("commit") != manifest.get("commit") or portable.get("releaseId") != manifest.get("releaseId"):
        raise RuntimeError("Portable metadata differs from release manifest")
    if portable.get("artifacts") != {n: checksums[n] for n in ("manifest.json", "source.tar.gz", "images.tar")}:
        raise RuntimeError("Portable artifact hashes differ from checksum inventory")
    source = source_inventory(bundle / "source.tar.gz")
    _verify_source_manifest(source, manifest)
    images = portable.get("images", {})
    if set(images) != IMAGE_NAMES or set(manifest.get("images", {})) != {"core", "repair", "web"}:
        raise RuntimeError("Portable release must include exactly five required images")
    if not set(manifest.get("runtimeImages", {})) <= {"postgres", "mailpit"}:
        raise RuntimeError("Unknown runtime image in release manifest")
    target = portable["platform"]
    if target != platform(target["os"], target["architecture"]):
        raise RuntimeError("Invalid portable target platform")
    for name, info in images.items():
        if platform(info["os"], info["architecture"]) != target or not IMAGE_ID.fullmatch(info["id"]):
            raise RuntimeError(f"Invalid image platform or identity: {name}")
        if name in manifest["images"]:
            expected = manifest["images"][name]
            if info["sourceRef"] != expected["ref"] or info["archiveRef"] != expected["ref"] or info["id"] != expected["id"]:
                raise RuntimeError(f"Portable application image differs from manifest: {name}")
        else:
            expected_runtime = manifest.get("runtimeImages", {}).get(name)
            if (info["sourceRef"] != source["baseline"]["images"][name] or
                info["archiveRef"] != f"studyroom-runtime-{name}:offline-{info['id'][7:]}" or
                (expected_runtime and (expected_runtime["ref"] != info["sourceRef"] or expected_runtime["id"] != info["id"]))):
                raise RuntimeError(f"Portable runtime image differs from dependency baseline: {name}")
    return {"bundle": bundle, "manifest": manifest, "portable": portable, "source": source}


def _prepare_source(verified, source_dir):
    destination = Path(source_dir).resolve()
    source = verified["source"]
    if not destination.exists():
        destination.mkdir(parents=True)
        with tarfile.open(verified["bundle"] / "source.tar.gz", "r:gz") as tar:
            # source_inventory already rejected every non-regular/non-directory entry.
            for member in tar.getmembers():
                name = _safe_member(member)
                target = destination / name
                if member.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with tar.extractfile(member) as incoming, target.open("xb") as outgoing:
                        for chunk in iter(lambda: incoming.read(1024 * 1024), b""):
                            outgoing.write(chunk)
                    target.chmod(member.mode & 0o777)
    for name, sha in source["files"].items():
        path = destination / name
        if (not path.is_file() or any(p.is_symlink() for p in [path, *path.parents] if p != destination.parent)
                or digest(path) != sha or (path.stat().st_mode & 0o111) != (source["modes"][name] & 0o111)):
            raise RuntimeError(f"Unpacked source differs from verified release: {name}")
    return destination


def _load_ops(source):
    spec = importlib.util.spec_from_file_location("portable_studyroom_ops", source / "scripts/studyroom.py")
    ops = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(ops)
    return ops


# Reuse the installed tool's field rules before any Docker calls. Do not execute
# the incoming archive's installer merely to inspect an untrusted manifest.
validate_manifest_fields = _load_ops(ROOT).validate_manifest_fields


def import_release(bundle_dir, environment, source_dir=None):
    """Verify before Docker mutation, then deploy verified unpacked source without Git."""
    if environment not in {"local", "dev", "test"}:
        raise RuntimeError("Portable installation supports local/dev/test; prod requires separate deployment gates")
    verified = verify_bundle(bundle_dir)
    host = run(["docker", "info", "--format", "{{.OSType}}/{{.Architecture}}"])
    parts = host.strip().split("/")
    if len(parts) != 2 or platform(*parts) != verified["portable"]["platform"]:
        raise RuntimeError("Target Docker architecture differs from portable images; export the matching platform")
    source = _prepare_source(verified, source_dir or ROOT)
    # All content/platform checks finish before docker load or environment creation.
    run(["docker", "load", "-i", verified["bundle"] / "images.tar"], cwd=source)
    for name, image in verified["portable"]["images"].items():
        inspected = inspect_image(image["archiveRef"], root=source)
        if any(inspected[k] != image[k] for k in ("id", "os", "architecture", "variant")):
            raise RuntimeError(f"Loaded image identity/platform mismatch: {name}")
    overrides = {name.upper() + "_IMAGE": verified["portable"]["images"][name]["archiveRef"] for name in ("postgres", "mailpit")}
    ops = _load_ops(source)
    ops.deploy(environment, verified["bundle"] / "manifest.json", runtime_images=overrides)
    return source


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("environment", choices=["local", "dev", "test"])
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--source-dir", type=Path, help="Extract into this new directory, or verify an existing matching source directory")
    args = parser.parse_args(argv)
    try:
        destination = import_release(args.bundle, args.environment, args.source_dir)
    except (RuntimeError, OSError, KeyError, ValueError, tarfile.TarError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Offline import failed: {error}\n")
    print(f"Verified offline release deployed from {destination}")


if __name__ == "__main__":
    main()
