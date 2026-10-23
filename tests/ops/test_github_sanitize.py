"""The upload boundary rejects secrets even when the test command never cleaned up."""
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/"scripts"))
spec=importlib.util.spec_from_file_location("github_sanitize",ROOT/"scripts/github-sanitize.py")
gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)

class UploadPrivacyTest(unittest.TestCase):
    def test_interrupted_check_secret_is_quarantined(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            state=root/".local/check/run";state.mkdir(parents=True)
            (state/"credentials.json").write_text(json.dumps({"password":"test-only-private-long-value"}))
            out=root/"output/check/run";out.mkdir(parents=True)
            (out/"log.txt").write_text("accident: test-only-private-long-value")
            with self.assertRaises(RuntimeError):gate.sanitize(root)
            self.assertFalse((out/"log.txt").exists())
            self.assertTrue(any((root/".local/private-upload-quarantine").rglob("log.txt")))
    def test_ordinary_summary_can_be_uploaded(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            state=root/".local/ci-release-1/secrets";state.mkdir(parents=True)
            (state/"demo_password").write_text("test-only-private-long-value")
            out=root/"output/container-release";out.mkdir(parents=True)
            (out/"result.json").write_text('{"result":"passed"}')
            gate.sanitize(root)
            self.assertTrue((out/"result.json").exists())
    def test_unknown_credentials_and_symlinks_fail_closed(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            out=root/"output/browser";out.mkdir(parents=True)
            (out/"result.txt").write_text("unknown")
            with self.assertRaises(RuntimeError):gate.sanitize(root)
            (out/"alias.txt").symlink_to(out/"result.txt")
            with self.assertRaises(RuntimeError):gate.sanitize(root)
    def test_directory_and_root_links_are_rejected(self):
        for mode in ('nested', 'root', 'broken'):
            with tempfile.TemporaryDirectory() as d:
                root=Path(d)
                output=root/'output';output.mkdir()
                target=root/'private';target.mkdir()
                if mode=='root':
                    (output/'browser').symlink_to(target,target_is_directory=True)
                else:
                    out=output/'browser';out.mkdir()
                    (out/'linked').symlink_to(target if mode=='nested' else root/'missing',target_is_directory=True)
                with self.subTest(mode=mode),self.assertRaises(RuntimeError): gate.sanitize(root)
if __name__=="__main__":unittest.main()
