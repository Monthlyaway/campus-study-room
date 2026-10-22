#!/usr/bin/env python3
"""Run the same real integration plan as scripts/check on disposable GitLab services."""
import os
from integration_harness import run_ci

if __name__ == '__main__':
    run_ci(os.environ)
