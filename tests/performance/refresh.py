#!/usr/bin/env python3
"""Replace only this isolated project's app images and apply additive migrations; retain all data."""
import argparse
from environment import *


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release-manifest')
    args=parser.parse_args()
    if not (OUTPUT/'latest-run.txt').exists():raise RuntimeError('Use prepare.py for the first run')
    # No active load can hold this same exclusive lock. Stop writers before migration.
    pause=STATE/'control/jobs-paused';pause.touch()
    compose('stop','-t','60','web','repair','core')
    initialize(args.release_manifest)
    compose('run','--rm','migrate','migrate')
    compose('up','-d','--no-build','--wait','--wait-timeout','180','core','repair','web')
    capture_actual_limits()
    pause.unlink()
    print('Performance images refreshed; dataset retained and jobs resumed.')

if __name__=='__main__':
    with exclusive_run():main()
