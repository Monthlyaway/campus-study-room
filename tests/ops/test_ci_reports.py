import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('ci_reports', ROOT / 'scripts/ci_reports.py')
reports = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reports)


class CiEvidenceGates(unittest.TestCase):
    def check_report(self, content, reader):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'result.txt'
            path.write_text(content)
            return reader(path)

    def test_python_accepts_completed_single_and_multiple_suites(self):
        for count, word in ((1, 'test'), (5, 'tests')):
            self.assertEqual(count, self.check_report(f'Ran {count} {word} in 1.234s\n\nOK\n', reports.require_unittest_report))

    def test_python_zero_skipped_failed_or_missing_evidence_is_rejected(self):
        for text in ('Ran 0 tests in 0.001s\nOK\n',
                     'Ran 5 tests in 1.234s\nOK (skipped=1)\n',
                     'Ran 5 tests in 1.234s\nFAILED (failures=1)\n',
                     'OK\n', 'Ran 5 tests in 1.234s\n'):
            with self.subTest(text=text), self.assertRaises(RuntimeError):
                self.check_report(text, reports.require_unittest_report)

    def test_junit_requires_positive_unskipped_success(self):
        self.assertEqual(2, self.check_report('<testsuite tests="2" failures="0" errors="0" skipped="0"/>', reports.require_junit_report))
        for values in ('tests="0"', 'tests="2" skipped="1"', 'tests="2" errors="1"', 'tests="2" failures="1"'):
            with self.subTest(values=values), self.assertRaises(RuntimeError):
                self.check_report('<testsuite ' + values + '/>', reports.require_junit_report)


if __name__ == '__main__':
    unittest.main()
