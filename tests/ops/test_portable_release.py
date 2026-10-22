"""Offline transport gates: verify content/platform before Docker or deploy effects."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import tarfile
import tempfile
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("portable_release", ROOT / "scripts/portable_release.py")
portable = importlib.util.module_from_spec(spec)
spec.loader.exec_module(portable)


def sha(data):
    return hashlib.sha256(data).hexdigest()


class PortableReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.bundle = self.root / "bundle"
        self.bundle.mkdir()
        self.commit = "1" * 40
        self.ids = {name: "sha256:" + char * 64 for name, char in zip(("core", "repair", "web", "postgres", "mailpit"), "abcde")}
        baseline = {"images": {"postgres": "postgres:fixed@sha256:" + "d" * 64,
                               "mailpit": "mailpit:fixed@sha256:" + "e" * 64}}
        self.source = {
            "scripts/studyroom.py": b"def deploy(*args, **kwargs): pass\n",
            "scripts/portable_release.py": b"# offline transport\n",
            "scripts/import-release": b"#!/bin/sh\n",
            "scripts/init-env": b"#!/usr/bin/env python3\n",
            "infra/compose/compose.yml": b"services: {}\n",
            "docs/dependency-baseline.json": json.dumps(baseline).encode(),
            "db/migration/core/V1__base.sql": b"CREATE TABLE sample(id integer);\n",
        }
        self.manifest = {"releaseId": "test-1", "commit": self.commit, "rollbackCompatibility": "expand-only-v1",
                         "images": {n: {"ref": f"studyroom-{n}:test-1", "id": self.ids[n]} for n in ("core", "repair", "web")},
                         "composeSha256": sha(self.source["infra/compose/compose.yml"]),
                         "dependencyBaselineSha256": sha(self.source["docs/dependency-baseline.json"]),
                         "migrations": {n: sha(v) for n, v in self.source.items() if n.endswith(".sql")}}
        self.write_source()
        (self.bundle / "manifest.json").write_text(json.dumps(self.manifest))
        (self.bundle / "images.tar").write_bytes(b"docker image transport")
        (self.bundle / "INSTALL.md").write_text("Offline installation instructions")
        images = {}
        for n in self.ids:
            ref = self.manifest["images"][n]["ref"] if n in self.manifest["images"] else baseline["images"][n]
            alias = ref if n in self.manifest["images"] else f"studyroom-runtime-{n}:offline-{self.ids[n][7:]}"
            images[n] = {"sourceRef": ref, "archiveRef": alias, "id": self.ids[n], "os": "linux", "architecture": "arm64", "variant": ""}
        self.metadata = {"formatVersion": 1, "releaseId": "test-1", "commit": self.commit,
                         "platform": {"os": "linux", "architecture": "arm64"}, "images": images}
        self.seal()

    def tearDown(self):
        self.temp.cleanup()

    def write_source(self, extra=None):
        with tarfile.open(self.bundle / "source.tar.gz", "w:gz", format=tarfile.PAX_FORMAT,
                          pax_headers={"comment": self.commit}) as tar:
            for name, data in self.source.items():
                member = tarfile.TarInfo(name)
                member.size = len(data)
                member.mode = 0o755 if name.startswith("scripts/") else 0o644
                tar.addfile(member, io.BytesIO(data))
            if extra is not None:
                tar.addfile(extra)

    def seal(self):
        self.metadata["artifacts"] = {n: portable.digest(self.bundle / n) for n in ("manifest.json", "images.tar", "source.tar.gz")}
        (self.bundle / "portable.json").write_text(json.dumps(self.metadata))
        (self.bundle / "SHA256SUMS").write_text("".join(f"{portable.digest(self.bundle / n)}  {n}\n" for n in sorted(portable.PAYLOAD_FILES)))

    def inspect(self, ref, **kwargs):
        entry = next(i for i in self.metadata["images"].values() if ref in (i["sourceRef"], i["archiveRef"]))
        return {k: entry[k] for k in ("id", "os", "architecture", "variant")}

    def test_valid_bundle_checks_all_five_images_and_committed_source(self):
        result = portable.verify_bundle(self.bundle)
        self.assertEqual(set(result["portable"]["images"]), portable.IMAGE_NAMES)
        self.assertEqual(result["source"]["commit"], self.commit)

    def test_corrupt_payload_rejected_before_docker(self):
        for name in ("images.tar", "source.tar.gz", "manifest.json", "portable.json", "INSTALL.md"):
            path = self.bundle / name
            original = path.read_bytes()
            path.write_bytes(original + b"tampered")
            with patch.object(portable, "run") as run:
                with self.assertRaisesRegex(RuntimeError, "checksum mismatch"):
                    portable.import_release(self.bundle, "test", self.root / "source")
                run.assert_not_called()
            path.write_bytes(original)

    def test_missing_checksum_rejected(self):
        path = self.bundle / "SHA256SUMS"
        path.write_text(path.read_text().splitlines()[0] + "\n")
        with self.assertRaisesRegex(RuntimeError, "every payload"):
            portable.verify_bundle(self.bundle)

    def test_self_consistent_bundle_with_unsafe_manifest_rejected_before_docker(self):
        self.manifest['releaseId'] = '../outside'
        self.metadata['releaseId'] = '../outside'
        (self.bundle / 'manifest.json').write_text(json.dumps(self.manifest))
        self.seal()
        with patch.object(portable, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'Invalid releaseId'):
                portable.import_release(self.bundle, 'test', self.root / 'source')
            run.assert_not_called()

    def test_source_manifest_mismatch_rejected_even_with_updated_transport_hashes(self):
        self.source["infra/compose/compose.yml"] = b"changed compose"
        self.write_source()
        self.seal()
        with self.assertRaisesRegex(RuntimeError, "Source content differs"):
            portable.verify_bundle(self.bundle)

    def test_path_traversal_and_private_source_rejected(self):
        for name in ("../outside", "/absolute", ".local/local/secrets/password", "secrets/key"):
            self.write_source(tarfile.TarInfo(name))
            self.seal()
            with self.assertRaises(RuntimeError):
                portable.verify_bundle(self.bundle)
        self.assertFalse((self.root / "outside").exists())

    def test_symlinks_and_hardlinks_rejected(self):
        for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE):
            member = tarfile.TarInfo("linked")
            member.type, member.linkname = kind, "../../outside"
            self.write_source(member)
            self.seal()
            with self.assertRaisesRegex(RuntimeError, "symlinks, hardlinks"):
                portable.verify_bundle(self.bundle)

    def test_arm64_bundle_rejected_on_amd64_before_load_or_extraction(self):
        with patch.object(portable, "run", return_value="linux/x86_64\n") as run:
            with self.assertRaisesRegex(RuntimeError, "architecture differs"):
                portable.import_release(self.bundle, "test", self.root / "source")
            self.assertEqual(run.call_count, 1)
        self.assertFalse((self.root / "source").exists())

    def test_mixed_image_architecture_rejected(self):
        self.metadata["images"]["postgres"]["architecture"] = "amd64"
        self.seal()
        with self.assertRaisesRegex(RuntimeError, "Invalid image platform"):
            portable.verify_bundle(self.bundle)

    def test_import_without_git_loads_verifies_and_passes_runtime_aliases(self):
        ops = Mock()
        source = self.root / "source"
        def run(args, **kwargs):
            return "linux/aarch64\n" if args[1] == "info" else "loaded"
        with patch.object(portable, "run", side_effect=run) as command, patch.object(portable, "inspect_image", side_effect=self.inspect) as inspect, patch.object(portable, "_load_ops", return_value=ops):
            result = portable.import_release(self.bundle, "test", source)
        self.assertEqual(result, source)
        self.assertFalse((source / ".git").exists())
        self.assertEqual(inspect.call_count, 5)
        self.assertEqual(command.call_args_list[1].args[0][:3], ["docker", "load", "-i"])
        ops.deploy.assert_called_once_with("test", self.bundle / "manifest.json", runtime_images={
            "POSTGRES_IMAGE": self.metadata["images"]["postgres"]["archiveRef"],
            "MAILPIT_IMAGE": self.metadata["images"]["mailpit"]["archiveRef"]})

    def test_modified_unpacked_source_rejected_before_load(self):
        target = portable._prepare_source(portable.verify_bundle(self.bundle), self.root / "source")
        (target / "scripts/studyroom.py").write_text("changed")
        with patch.object(portable, "run", return_value="linux/arm64\n") as run:
            with self.assertRaisesRegex(RuntimeError, "Unpacked source differs"):
                portable.import_release(self.bundle, "test", target)
            self.assertEqual(run.call_count, 1)

    def test_wrong_loaded_content_id_never_deploys(self):
        with patch.object(portable, "run", return_value="linux/arm64\n"), patch.object(portable, "inspect_image", return_value={"id": "sha256:" + "f" * 64,"os":"linux","architecture":"arm64","variant":""}), patch.object(portable, "_load_ops") as load:
            with self.assertRaisesRegex(RuntimeError, "Loaded image identity"):
                portable.import_release(self.bundle, "test", self.root / "source")
            load.assert_not_called()

    def test_export_uses_git_commit_and_five_tagged_images(self):
        target = self.root / "export"
        def run(args, **kwargs):
            if args[0] == "git":
                self.assertEqual(args[-1], self.commit)
                shutil.copyfile(self.bundle / "source.tar.gz", args[-2])
            elif args[1] == "save":
                self.assertEqual(len(args[4:]), 5)
                Path(args[3]).write_bytes(b"docker image transport")
            elif args[1] == "info":
                return "linux/arm64\n"
            return ""
        with patch.object(portable, "run", side_effect=run), patch.object(portable, "inspect_image", side_effect=self.inspect):
            self.assertEqual(portable.export_release(self.bundle / "manifest.json", self.manifest, target, root=self.root), target)
        self.assertEqual(portable.verify_bundle(target)["manifest"], self.manifest)
        self.assertFalse((target / "secrets").exists())

    def test_export_rejects_runtime_id_changed_since_manifest_verification(self):
        self.manifest["runtimeImages"] = {"postgres": {
            "ref": self.metadata["images"]["postgres"]["sourceRef"],
            "id": "sha256:" + "f" * 64}}
        (self.bundle / "manifest.json").write_text(json.dumps(self.manifest))
        def run(args, **kwargs):
            if args[0] == "git":
                shutil.copyfile(self.bundle / "source.tar.gz", args[-2])
            if args[:2] == ["docker", "save"]:
                self.fail("Invalid runtime identity must prevent docker save")
            return ""
        with patch.object(portable, "run", side_effect=run), patch.object(portable, "inspect_image", side_effect=self.inspect):
            with self.assertRaisesRegex(RuntimeError, "Image changed after verification: postgres"):
                portable.export_release(self.bundle / "manifest.json", self.manifest, self.root / "export", root=self.root)
        self.assertFalse((self.root / "export").exists())

    def test_export_rejects_daemon_architecture_before_save(self):
        def run(args, **kwargs):
            if args[0] == "git":
                shutil.copyfile(self.bundle / "source.tar.gz", args[-2])
            if args[:2] == ["docker", "info"]:
                return "linux/amd64\n"
            if args[:2] == ["docker", "save"]:
                self.fail("Incompatible daemon must prevent docker save")
            return ""
        with patch.object(portable, "run", side_effect=run), patch.object(portable, "inspect_image", side_effect=self.inspect):
            with self.assertRaisesRegex(RuntimeError, "Export Docker architecture differs"):
                portable.export_release(self.bundle / "manifest.json", self.manifest, self.root / "export", root=self.root)
        self.assertFalse((self.root / "export").exists())

    def test_manifest_changed_since_verification_rejected_before_export(self):
        (self.bundle / "manifest.json").write_text(json.dumps({**self.manifest, "commit": "2" * 40}))
        with patch.object(portable, "run") as run:
            with self.assertRaisesRegex(RuntimeError, "Manifest changed after verification"):
                portable.export_release(self.bundle / "manifest.json", self.manifest, self.root / "export", root=self.root)
            run.assert_not_called()

    def test_production_import_rejected_before_any_side_effect(self):
        with patch.object(portable, "run") as run:
            with self.assertRaisesRegex(RuntimeError, "prod requires"):
                portable.import_release(self.bundle, "prod")
            run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
