#!/usr/bin/env python3
"""Prove Linux UID/0600 secret handling in a disposable PostgreSQL container."""
import json
from pathlib import Path
import secrets
import subprocess
import time
import uuid

ROOT=Path(__file__).resolve().parents[2]
image=json.loads((ROOT/'docs/dependency-baseline.json').read_text())['images']['postgres']
name='studyroom-secret-check-'+uuid.uuid4().hex[:10]
def run(*args,check=True):
    return subprocess.run([str(x) for x in args],check=check,text=True,capture_output=True)
try:
    command='''mkdir -p /run/secrets
for key in core_db_password repair_db_password migration_db_password; do
  printf '%s' "$BOOTSTRAP_TEST_PASSWORD" > "/run/secrets/$key"
  chown 1000:1000 "/run/secrets/$key"
  chmod 600 "/run/secrets/$key"
done
exec /bin/sh /opt/studyroom/postgres-entrypoint.sh postgres
'''
    password=secrets.token_urlsafe(24)
    run('docker','run','-d','--name',name,'--entrypoint','/bin/sh',
        '--tmpfs','/var/lib/postgresql','--tmpfs','/run/secrets',
        '-e','POSTGRES_USER=studyroom_bootstrap','-e','POSTGRES_DB=studyroom',
        '-e','POSTGRES_PASSWORD='+password,'-e','BOOTSTRAP_TEST_PASSWORD='+password,
        '-v',str(ROOT/'infra/compose/postgres-entrypoint.sh')+':/opt/studyroom/postgres-entrypoint.sh:ro',
        '-v',str(ROOT/'infra/compose/init-db.sh')+':/docker-entrypoint-initdb.d/10-studyroom.sh:ro',image,'-c',command)
    for attempt in range(60):
        result=run('docker','exec',name,'psql','-U','studyroom_bootstrap','-d','studyroom','-Atc',"SELECT count(*) FROM pg_roles WHERE rolname IN ('studyroom_core','studyroom_repair','studyroom_migration')",check=False)
        if result.returncode==0 and result.stdout.strip()=='3': break
        time.sleep(1)
    else:
        raise RuntimeError('Linux secret bootstrap failed: '+run('docker','logs',name,check=False).stderr[-3000:])
    denied=run('docker','exec','--user','postgres',name,'test','-r','/run/secrets/core_db_password',check=False)
    if denied.returncode==0:
        raise RuntimeError('Fixture did not reproduce Linux owner-only secret permissions')
    report={'result':'passed','image':image,'secretUid':1000,'mode':'0600','postgresCanReadSecretFile':False,'createdRuntimeAndMigrationRoles':3}
    (ROOT/'output').mkdir(exist_ok=True)
    (ROOT/'output/linux-secret-bootstrap.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
finally:
    run('docker','rm','-f',name,check=False)
