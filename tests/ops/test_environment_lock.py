"""Exercise the deployment lock with separate operating-system processes."""
import os
from pathlib import Path
import select
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKER = '''
import importlib.util, sys
spec = importlib.util.spec_from_file_location('ops', sys.argv[1])
ops = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ops)
print('ready', flush=True)
with ops.environment_lock('test'):
    print('acquired', flush=True)
    sys.stdin.readline()
print('released', flush=True)
'''


class EnvironmentLockTests(unittest.TestCase):
    def worker(self, state):
        process = subprocess.Popen([sys.executable, '-u', '-c', WORKER, str(ROOT / 'scripts/studyroom.py')],
                                   env={**os.environ, 'STUDYROOM_STATE_DIR': str(state)},
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.addCleanup(self.close, process)
        self.assertEqual(self.line(process), b'ready')
        return process

    @staticmethod
    def close(process):
        if process.poll() is None:
            process.communicate(b'\n', timeout=10)
        for stream in (process.stdin, process.stdout, process.stderr):
            stream.close()

    def line(self, process):
        self.assertTrue(select.select([process.stdout], [], [], 10)[0], 'Lock worker did not make progress')
        return process.stdout.readline().strip()

    def acquire(self, process):
        # The first holder also initializes the state directory without exposing secrets.
        for _ in range(4):
            if self.line(process) == b'acquired':
                return
        self.fail('Worker did not acquire lock')

    def test_same_environment_serializes_and_other_environment_remains_independent(self):
        with tempfile.TemporaryDirectory() as directory:
            shared = Path(directory) / 'shared'
            first = self.worker(shared)
            self.acquire(first)
            second = self.worker(shared)
            self.assertFalse(select.select([second.stdout], [], [], 0.2)[0], 'Second process bypassed the held deployment lock')
            independent = self.worker(Path(directory) / 'independent')
            self.acquire(independent)
            first.stdin.write(b'\n'); first.stdin.flush()
            self.assertEqual(self.line(first), b'released')
            self.acquire(second)
            for process in (second, independent):
                process.stdin.write(b'\n'); process.stdin.flush()
                self.assertEqual(self.line(process), b'released')
            for process in (first, second, independent):
                self.assertEqual(process.wait(timeout=10), 0)


if __name__ == '__main__':
    unittest.main()
