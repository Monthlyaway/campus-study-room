import base64
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('private_artifacts', ROOT / 'scripts/private_artifacts.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class PrivateArtifactGateTests(unittest.TestCase):
    def test_known_secret_and_embedded_playwright_zip_are_quarantined(self):
        sentinel = 'Only-A-Fake-Artifact-Sentinel-123'
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, 'w') as z:
            z.writestr('data/error-context.md', 'textbox password: ' + sentinel)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); reports = root / 'output'; reports.mkdir()
            (reports / 'safe.json').write_text('{"tests":6}')
            (reports / 'unsafe.txt').write_text(sentinel)
            (reports / 'index.html').write_bytes(b'data:application/zip;base64,' + base64.b64encode(archive.getvalue()))
            with self.assertRaisesRegex(RuntimeError, '2 report files'):
                gate.protect_reports([reports], [sentinel], root / 'private')
            self.assertEqual(['safe.json'], [p.name for p in reports.iterdir()])
            self.assertEqual(2, len(list((root / 'private').rglob('*.*'))))
            self.assertEqual(0o600, (root / 'private/0/unsafe.txt').stat().st_mode & 0o777)

    def test_malformed_archive_does_not_leave_later_secret_available_for_upload(self):
        sentinel = 'Only-A-Fake-Artifact-Sentinel-789'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); reports = root / 'output'; reports.mkdir()
            (reports / 'malformed.html').write_bytes(b'data:application/zip;base64,' + base64.b64encode(b'not a ZIP'))
            (reports / 'later-secret.txt').write_text(sentinel)
            (reports / 'safe.json').write_text('{"tests":6}')
            with self.assertRaisesRegex(RuntimeError, '2 report files'):
                gate.protect_reports([reports], [sentinel], root / 'private')
            self.assertEqual(['safe.json'], [p.name for p in reports.iterdir()])
            for name in ('malformed.html', 'later-secret.txt'):
                self.assertEqual(0o600, (root / 'private/0' / name).stat().st_mode & 0o777)

    def test_empty_secret_inventory_fails_and_safe_report_is_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); data = root / 'safe.json'; data.write_text('{"tests":6}')
            with self.assertRaisesRegex(RuntimeError, 'No runtime secrets'):
                gate.protect_reports([root], [], root.parent / 'unused-quarantine')
            gate.protect_reports([root], ['Different-Fake-Sentinel-456'], root.parent / 'unused-quarantine')
            self.assertEqual('{"tests":6}', data.read_text())


if __name__ == '__main__':
    unittest.main()
