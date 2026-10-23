#!/usr/bin/env python3
"""Prepare the exact PRD data scale in a fresh isolated Docker project."""
import argparse
import json
import subprocess
from environment import *

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--release-manifest',type=str);args=parser.parse_args()
    existing=subprocess.check_output(['docker','ps','-aq','--filter','label=com.docker.compose.project='+PROJECT],text=True).strip()
    if existing:raise RuntimeError('Performance project already exists; preparation never deletes or overwrites a dataset')
    initialize(args.release_manifest)
    compose('up','-d','--wait','postgres','mailpit')
    for action in ('migrate','reference'):compose('run','--rm','migrate',action)
    sql((ROOT/'tests/performance/seed.sql').read_text(),capture=False,password=True)
    sql('REVOKE UPDATE,DELETE ON core.audit_event FROM studyroom_core; REVOKE UPDATE,DELETE ON repair.audit_event FROM studyroom_repair;')
    initial=counts()
    assert initial['accounts']==1000 and initial['rooms']==20 and initial['seats']==1000 and initial['historicalReservations']==100000,initial
    (OUTPUT/'data-scale.json').write_text(json.dumps(initial,indent=2)+'\n')
    compose('up','-d','--no-build','--wait','--wait-timeout','180','core','repair','web')
    limits=capture_actual_limits()
    print(json.dumps({'prepared':True,'counts':initial,'containers':limits},indent=2))
if __name__=='__main__':
    with exclusive_run():main()
