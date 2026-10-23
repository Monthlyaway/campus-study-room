"""Entry-point locks for legacy runners which share the native local PostgreSQL host."""
from contextlib import contextmanager
from pathlib import Path
import sys

SCRIPTS = Path(__file__).resolve().parents[2] / 'scripts'
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))
from integration_harness import workspace_lock


@contextmanager
def local_runner_lock(ops, *, workspace=False):
    # Match native-up: environment first, then shared build artifacts. Keep fixture
    # helpers lock-free so CI and the default isolated harness do not reacquire it.
    with ops.environment_lock('local'):
        if workspace:
            with workspace_lock():
                yield
        else:
            yield
