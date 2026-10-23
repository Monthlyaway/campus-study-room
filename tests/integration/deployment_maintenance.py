#!/usr/bin/env python3
"""Exercise real Nginx maintenance and both services' pause/drain probes."""
import importlib.util
import json
from pathlib import Path
import sys
import urllib.request
import urllib.error

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('studyroom_ops',ROOT/'scripts/studyroom.py')
ops=importlib.util.module_from_spec(spec);spec.loader.exec_module(ops)
environment=sys.argv[1] if len(sys.argv)>1 else 'test'
if environment not in ('dev','test'):
    raise RuntimeError('Use an isolated dev/test container environment')
state,config=ops.config(environment)
maintenance=state/'control/maintenance';paused=state/'control/jobs-paused'
if maintenance.exists() or paused.exists():
    raise RuntimeError('An existing deployment pause must not be modified by this test')
def status(path):
    try:
        with urllib.request.urlopen(config['PUBLIC_BASE_URL']+path,timeout=5) as response:return response.status
    except urllib.error.HTTPError as error:return error.code
try:
    maintenance.touch();paused.touch()
    ops.wait_status(config['PUBLIC_BASE_URL'],503)
    assert status('/')==503
    assert status('/api/v1/auth/csrf')==503
    assert status('/internal/v1/resource-commands')==404
    assert status('/actuator/jobs')==404
    assert status('/healthz')==200
    ops.wait_drained(environment)
    probes={}
    for service in ('core','repair'):
        probes[service]=json.loads(ops.compose(environment,'exec','-T',service,'curl','-fsS','http://localhost:8080/actuator/jobs',capture=True).stdout)
        assert probes[service]=={'paused':True,'active':0,'activeRequests':0}
    ops.smoke(environment)
finally:
    maintenance.unlink(missing_ok=True);paused.unlink(missing_ok=True)
ops.wait_status(config['PUBLIC_BASE_URL'],200)
assert status('/')==200
report={'result':'passed','environment':environment,'businessDuringMaintenance':503,'privatePublicPaths':404,'privateProbes':probes,'reopened':200}
(ROOT/'output/maintenance-drain.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report))
