"""Dedicated performance project; no writes to other deployment state directories."""
import contextlib
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.request

ROOT=Path(__file__).resolve().parents[2]
PROJECT='studyroom-performance'
STATE=ROOT/'.local/performance'
OUTPUT=ROOT/'output/performance'
spec=importlib.util.spec_from_file_location('studyroom_ops',ROOT/'scripts/studyroom.py')
ops=importlib.util.module_from_spec(spec);spec.loader.exec_module(ops)
BASE_URL='http://127.0.0.1:19080'

def ident(kind,number=None):
    value='performance:'+kind+(':'+str(number) if number is not None else '')
    import uuid
    return str(uuid.UUID(hashlib.md5(value.encode(),usedforsecurity=False).hexdigest()))

def initialize(release_manifest=None):
    STATE.mkdir(parents=True,exist_ok=True,mode=0o700);OUTPUT.mkdir(parents=True,exist_ok=True)
    if not (STATE/'compose.env').exists():
        subprocess.run([sys.executable,str(ROOT/'scripts/init-env'),'test','--directory',str(STATE)],check=True,cwd=ROOT)
    (STATE/'control').mkdir(exist_ok=True)
    manifest=ops.verify_manifest(release_manifest) if release_manifest else None
    source={name:info['ref'] for name,info in manifest['images'].items()} if manifest else {'core':'studyroom-core:development','repair':'studyroom-repair:development','web':'studyroom-web:development'}
    image_ids={name:ops.image_id(ref) for name,ref in source.items()}
    services={name:{'cpuset':'0-3','mem_limit':mem,'memswap_limit':mem} for name,mem in [('postgres','3g'),('core','2g'),('repair','2g'),('mailpit','512m'),('web','512m')]}
    for name,image in image_ids.items():services[name].update(image=image,pull_policy='never')
    # Persist runtime identity alongside immutable image IDs. Every later Compose
    # operation, including the recovery restart, must use the same labels.
    runtime_identity={'RELEASE_ID':manifest['releaseId'] if manifest else 'development',
                      'RELEASE_COMMIT':manifest['commit'] if manifest else 'working-tree',
                      'GIT_COMMIT':manifest['commit'] if manifest else 'working-tree'}
    for name in ('core','repair'):services[name]['environment']=runtime_identity.copy()
    services['migrate']={'image':image_ids['core'],'pull_policy':'never','cpuset':'0-3','mem_limit':'1g','memswap_limit':'1g'}
    services['postgres']['command']=['postgres','-c','shared_preload_libraries=pg_stat_statements','-c','track_io_timing=on','-c','log_lock_waits=on','-c','deadlock_timeout=100ms']
    override={'name':PROJECT,'services':services}
    (OUTPUT/'compose.override.json').write_text(json.dumps(override,indent=2)+'\n')
    metadata={'project':PROJECT,'state':str(STATE),'baseUrl':BASE_URL,'images':image_ids,'sourceRefs':source,'releaseId':manifest['releaseId'] if manifest else None,'releaseCommit':manifest['commit'] if manifest else None,'cpuSet':'0-3','memoryLimits':{k:v['mem_limit'] for k,v in services.items()},'docker':json.loads(subprocess.check_output(['docker','info','--format','{{json .}}'],text=True)),'commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()}
    # Avoid Docker server configuration or host identifiers in the portable report.
    metadata['docker']={k:metadata['docker'].get(k) for k in ['Architecture','NCPU','MemTotal','OperatingSystem','KernelVersion','ServerVersion']}
    (OUTPUT/'environment.json').write_text(json.dumps(metadata,indent=2)+'\n')
    return metadata

def compose(*args,capture=False,input=None,env_extra=None,check=True):
    if not (OUTPUT/'compose.override.json').exists():raise RuntimeError('Run prepare.py first')
    values={}
    for line in (STATE/'compose.env').read_text().splitlines():
        if line and not line.startswith('#'):
            k,v=line.split('=',1);values[k]=v
    env={**os.environ,**values,'STUDYROOM_STATE_DIR':str(STATE),'COMPOSE_PROJECT_NAME':PROJECT,'WEB_PORT':'19080','MAIL_UI_PORT':'19025','ENV_NAME':'test',**(env_extra or {})}
    command=['docker','compose','--project-name',PROJECT,'--env-file',str(STATE/'compose.env'),'-f',str(ops.BASE),'-f',str(OUTPUT/'compose.override.json'),*map(str,args)]
    return subprocess.run(command,cwd=ROOT,env=env,text=True,input=input,stdout=subprocess.PIPE if capture else None,stderr=subprocess.PIPE if capture else None,check=check)

def sql(statement,capture=True,password=False):
    args=['exec','-T']
    env_extra=None
    if password:
        env_extra={'PERFORMANCE_PASSWORD':(STATE/'secrets/demo_password').read_text().strip()}
        args+=['-e','PERFORMANCE_PASSWORD']
    args+=['postgres','psql','-X','-v','ON_ERROR_STOP=1','-U','studyroom_bootstrap','-d','studyroom','-At']
    return compose(*args,input=statement,capture=capture,env_extra=env_extra).stdout

def counts():
    return json.loads(sql("SELECT json_build_object('accounts',(select count(*) from core.app_user),'rooms',(select count(*) from core.room),'seats',(select count(*) from core.seat),'historicalReservations',(select count(*) from core.reservation where status='COMPLETED'),'reservations',(select count(*) from core.reservation),'claims',(select count(*) from core.resource_claim),'historicalTasks',(select count(*) from core.scheduled_task where status='DONE'));"))


@contextlib.contextmanager
def exclusive_run():
    """A measurement cannot overlap preparation, SQL probes or an image refresh."""
    STATE.mkdir(parents=True,exist_ok=True,mode=0o700)
    with (STATE/'performance.lock').open('a') as lock:
        os.chmod(STATE/'performance.lock',0o600)
        try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise RuntimeError('Another performance operation is running') from None
        try:yield
        finally:fcntl.flock(lock,fcntl.LOCK_UN)


def capture_actual_limits():
    ids=compose('ps','--status','running','-q',capture=True).stdout.split()
    inspect=json.loads(subprocess.check_output(['docker','inspect',*ids],text=True))
    limits=[{'service':c['Config']['Labels']['com.docker.compose.service'],'image':c['Image'],'cpuSet':c['HostConfig']['CpusetCpus'],'memory':c['HostConfig']['Memory'],'memorySwap':c['HostConfig']['MemorySwap']} for c in inspect]
    if {c['service'] for c in limits}!={'postgres','core','repair','web','mailpit'}:raise RuntimeError('Expected exactly five running performance services')
    if not all(c['cpuSet']=='0-3' and c['memory']>0 and c['memorySwap']==c['memory'] for c in limits):raise RuntimeError('Performance CPU/memory limits drifted')
    if sum(c['memory'] for c in limits)!=8*1024**3:raise RuntimeError('Performance memory total must equal 8GiB')
    metadata_path=OUTPUT/'environment.json'
    metadata=json.loads(metadata_path.read_text())
    expected=metadata['images']
    for c in limits:
        if c['service'] in expected and c['image']!=expected[c['service']]:raise RuntimeError('Performance image drift: '+c['service'])
    versions={}
    for service,path in [('web','/version.json'),('core','/api/v1/version'),('repair','/api/v1/repairs/version')]:
        try:
            with urllib.request.urlopen(BASE_URL+path,timeout=10) as response:
                payload=json.load(response)
            value=payload.get('data',payload)
            if not all(isinstance(value.get(key),str) and value[key] for key in ('releaseId','commit')):
                raise ValueError('Missing version fields')
            versions[service]={key:value[key] for key in ('releaseId','commit')}
        except (OSError,ValueError,TypeError,AttributeError) as error:
            raise RuntimeError('Performance version probe failed: '+service) from error
    release_mode=metadata.get('releaseId') is not None or metadata.get('releaseCommit') is not None
    mismatches=[name for name,value in versions.items()
                if value['releaseId']!=metadata.get('releaseId') or value['commit']!=metadata.get('releaseCommit')]
    metadata['observedVersions']=versions
    metadata['releaseVersionCheck']=('failed' if mismatches else 'passed') if release_mode else 'not-applicable'
    # Only current preparation metadata is updated; archived run directories are immutable.
    metadata_path.write_text(json.dumps(metadata,indent=2)+'\n')
    if release_mode and mismatches:
        raise RuntimeError('Performance release version mismatch: '+', '.join(mismatches))
    (OUTPUT/'actual-container-limits.json').write_text(json.dumps(limits,indent=2)+'\n')
    return limits
