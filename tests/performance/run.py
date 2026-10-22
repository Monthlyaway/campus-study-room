#!/usr/bin/env python3
"""PRD 12 / T50-03: fixed 20 users, 60 s warmup, 300 s measured, 80:20 traffic."""
import collections
import concurrent.futures
import datetime as dt
import http.client
import http.cookies
import json
import math
import os
import shutil
from pathlib import Path
import threading
import time
import urllib.parse
import uuid
from environment import *

CONCURRENCY=20
WARMUP_SECONDS=60
MEASURE_SECONDS=300

class Client:
    def __init__(self,number):
        self.number=number;self.cookies={};self.csrf=None;self.connection=None
        self.csrf=self.need('GET','/api/v1/auth/csrf')['csrfToken']
        self.me=self.need('POST','/api/v1/auth/login',{'username':f'perf{number:04d}','password':(STATE/'secrets/demo_password').read_text().strip()})
        self.csrf=self.need('GET','/api/v1/auth/csrf')['csrfToken']
    def call(self,method,path,body=None):
        if self.connection is None:self.connection=http.client.HTTPConnection('127.0.0.1',19080,timeout=20)
        headers={'Content-Type':'application/json','Cookie':'; '.join(k+'='+v for k,v in self.cookies.items())}
        if method!='GET':
            headers['Idempotency-Key']=str(uuid.uuid4())
            if self.csrf:headers['X-CSRF-TOKEN']=self.csrf
        started=time.perf_counter()
        try:
            self.connection.request(method,path,None if body is None else json.dumps(body).encode(),headers)
            response=self.connection.getresponse();raw=response.read();status=response.status
            for name,value in response.getheaders():
                if name.lower()=='set-cookie':
                    jar=http.cookies.SimpleCookie();jar.load(value)
                    for key,cookie in jar.items():self.cookies[key]=cookie.value
            data=json.loads(raw) if raw else {}
            return status,data,time.perf_counter()-started
        except Exception as ex:
            self.connection.close();self.connection=None
            return 0,{'transportError':type(ex).__name__},time.perf_counter()-started
    def need(self,method,path,body=None):
        status,data,_=self.call(method,path,body)
        if not 200<=status<300:raise RuntimeError(f'{method} {path} returned {status}: {data}')
        return data.get('data')


def percentile(values,p):
    if not values:return None
    ordered=sorted(values);return ordered[max(0,math.ceil(len(ordered)*p/100)-1)]

def summary(records):
    groups={}
    for name in ['read','write',*sorted(set(r['operation'] for r in records))]:
        selected=[r for r in records if r['kind']==name or r['operation']==name]
        successful=[r['seconds']*1000 for r in selected if 200<=r['status']<300]
        groups[name]={'requests':len(selected),'status':dict(collections.Counter(str(r['status']) for r in selected)),'successful':len(successful),'p50Ms':percentile(successful,50),'p95Ms':percentile(successful,95),'p99Ms':percentile(successful,99),'maxMs':max(successful) if successful else None}
    return groups


def probe_sql(client,read_paths,output):
    pause=STATE/'control/jobs-paused';pause.touch()
    try:
        for _ in range(60):
            states=[json.loads(compose('exec','-T',service,'curl','-fsS','http://localhost:8080/actuator/jobs',capture=True).stdout) for service in ['core','repair']]
            if all(s['paused'] and s['active']==0 for s in states):break
            time.sleep(1)
        else:raise RuntimeError('Could not pause scheduler for isolated SQL-count probes')
        probes={}
        for operation,path in read_paths:
            sql('SELECT pg_stat_statements_reset();')
            client.need('GET',path)
            rows=json.loads(sql("SELECT coalesce(json_agg(q),'[]') FROM (SELECT query,calls,rows,total_exec_time,shared_blks_hit,shared_blks_read FROM pg_stat_statements WHERE query NOT LIKE '%pg_stat_statements%' ORDER BY total_exec_time DESC) q;"))
            probes[operation]={'totalStatements':sum(r['calls'] for r in rows),'statements':rows}
        output.write_text(json.dumps(probes,indent=2)+'\n')
    finally:pause.unlink(missing_ok=True)


def add_deadlines(label,delay):
    # Seed overdue-submission fixtures at exact submitted_at +24h boundaries; all subsequent work is real scheduling/HTTP.
    query=f"""WITH rows AS (SELECT n,md5('performance:{label}:'||n)::uuid id,clock_timestamp()+interval '{delay} seconds' due FROM generate_series(0,19) n)
    INSERT INTO repair.repair_order(id,reporter_id,room_id,title,description,status,submitted_at,assign_due_at,created_at,updated_at)
    SELECT id,md5('performance:user:'||n)::uuid,md5('performance:room:'||n)::uuid,'期限性能验收 {label}','隔离负载下的真实持久化期限任务','SUBMITTED',due-interval '24 hours',due,due-interval '24 hours',due-interval '24 hours' FROM rows;
    INSERT INTO repair.scheduled_task(id,task_type,source_type,source_id,dedup_key,cycle,due_at,status,next_attempt_at,created_at)
    SELECT md5('performance:task:{label}:'||n)::uuid,'M3_ASSIGN_OVERDUE','M3',r.id,'PERF:{label}:'||r.id,0,r.assign_due_at,'PENDING',r.assign_due_at,r.created_at FROM generate_series(0,19) n JOIN repair.repair_order r ON r.id=md5('performance:{label}:'||n)::uuid;
    SELECT json_agg(json_build_object('id',r.id,'dueEpoch',extract(epoch FROM r.assign_due_at))) FROM generate_series(0,19) n JOIN repair.repair_order r ON r.id=md5('performance:{label}:'||n)::uuid;"""
    text=sql(query);return json.loads(text.splitlines()[-1])


def probe_completion(label):
    return json.loads(sql(f"SELECT coalesce(json_agg(json_build_object('id',a.object_id,'auditEpoch',extract(epoch FROM min_created))),'[]') FROM (SELECT object_id,min(created_at) min_created FROM repair.audit_event WHERE action='M3_ASSIGN_OVERDUE' AND object_id IN(SELECT md5('performance:{label}:'||n)::uuid FROM generate_series(0,19) n) GROUP BY object_id) a;"))


def contention(clients,start,end):
    barrier=threading.Barrier(CONCURRENCY);seat=ident('seat',999)
    def action(client):
        barrier.wait();status,data,seconds=client.call('POST','/api/v1/reservations',{'seatId':seat,'startAt':start,'endAt':end})
        return {'user':client.number,'status':status,'seconds':seconds,'data':data.get('data'),'error':data.get('error')}
    with concurrent.futures.ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:results=list(pool.map(action,clients))
    success=[r for r in results if r['status']==201];conflicts=[r for r in results if r['status']==409]
    active=int(sql(f"SELECT count(*) FROM core.resource_claim WHERE seat_id='{seat}' AND released_at IS NULL AND start_at='{start}'::timestamptz AND end_at='{end}'::timestamptz;"))
    outcome={'success':len(success),'conflicts':len(conflicts),'activeClaims':active,'passed':len(success)==1 and len(conflicts)==19 and active==1,'requests':results}
    for r in success:clients[r['user']].need('POST','/api/v1/reservations/'+r['data']['id']+'/cancel',{'expectedVersion':r['data']['version']})
    return outcome


def sampler(stop,started,probe,records,first_seen):
    while not stop.is_set():
        try:
            result=json.loads(sql("""SELECT json_build_object('atEpoch',extract(epoch FROM clock_timestamp()),'waitingLocks',(SELECT count(*) FROM pg_locks WHERE NOT granted),'maxLockWaitSeconds',coalesce((SELECT max(extract(epoch FROM clock_timestamp()-waitstart)) FROM pg_locks WHERE NOT granted),0),'deadlocks',(SELECT deadlocks FROM pg_stat_database WHERE datname=current_database()),'activeBackends',(SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND state='active'),'coreOverdue',(SELECT count(*) FROM core.scheduled_task WHERE status<>'DONE' AND due_at<clock_timestamp()),'repairOverdue',(SELECT count(*) FROM repair.scheduled_task WHERE status<>'DONE' AND due_at<clock_timestamp()),'notificationBacklog',(SELECT count(*) FROM core.notification WHERE status<>'SENT'));"""))
            result['elapsed']=time.perf_counter()-started;records.append(result)
            for row in probe_completion(probe):
                if row['id'] not in first_seen:first_seen[row['id']]={'firstObservedEpoch':time.time(),'auditEpoch':row['auditEpoch']}
        except Exception as ex:records.append({'elapsed':time.perf_counter()-started,'samplingError':type(ex).__name__})
        stop.wait(1)


def validate_data_scale(initial):
    # Recovery fixtures accumulate legitimate history; keep it instead of deleting it.
    if initial['accounts']!=1000 or initial['rooms']!=20 or initial['seats']!=1000 or initial['historicalReservations']<100000:
        raise RuntimeError('Dataset differs from acceptance baseline (at least 100000 historical reservations): '+str(initial))


def main():
    capture_actual_limits()
    initial=counts()
    validate_data_scale(initial)
    run_id=dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ');directory=OUTPUT/run_id;directory.mkdir()
    for name in ['environment.json','actual-container-limits.json','data-scale.json']:
        shutil.copy2(OUTPUT/name,directory/name)
    clients=[Client(n) for n in range(CONCURRENCY)]
    zone=dt.timezone(dt.timedelta(hours=8));date=(dt.datetime.now(zone)+dt.timedelta(days=2)).replace(hour=10,minute=0,second=0,microsecond=0)
    start=date.isoformat();end=(date+dt.timedelta(hours=1)).isoformat()
    search_start=(date+dt.timedelta(hours=2)).isoformat();search_end=(date+dt.timedelta(hours=3)).isoformat()
    availability='/api/v1/availability/seats?'+urllib.parse.urlencode({'startAt':search_start,'endAt':search_end,'page':1,'size':20})
    paths=[('availability',availability),('reservation-history','/api/v1/reservations?page=1&size=20'),('room-detail','/api/v1/rooms/'+ident('room',0)),('repair-history','/api/v1/repairs?page=1&size=20')]
    probe_sql(clients[0],paths,directory/'sql-counts.json')
    collision=contention(clients,(date+dt.timedelta(hours=4)).isoformat(),(date+dt.timedelta(hours=5)).isoformat())
    (directory/'contention.json').write_text(json.dumps(collision,indent=2)+'\n')
    if not collision['passed']:raise RuntimeError('20-way same-seat concurrency invariant failed')
    label='healthy-'+run_id;probes=add_deadlines(label,WARMUP_SECONDS+60)
    stats_before=json.loads(sql("SELECT coalesce(json_agg(s),'[]') FROM (SELECT query,calls,total_exec_time,rows FROM pg_stat_statements) s;"))
    (directory/'sql-before.json').write_text(json.dumps(stats_before,indent=2)+'\n')
    records=[[] for _ in clients];metrics=[];seen={};ready=threading.Barrier(CONCURRENCY+1);start_event=threading.Event();timing={};stop=threading.Event()
    def worker(client):
        order=None;sequence=0;ready.wait();start_event.wait()
        try:
            while time.perf_counter()<timing['end']:
                for phase in range(5):
                    at=time.perf_counter()
                    if at>=timing['end']:break
                    if phase<4:
                        operation,path=paths[phase]
                        if operation=='room-detail':path='/api/v1/rooms/'+ident('room',client.number)
                        method='GET';body=None;kind='read'
                    elif order is None:
                        operation='reservation-create';path='/api/v1/reservations';method='POST';kind='write';body={'seatId':ident('seat',client.number*50),'startAt':start,'endAt':end}
                    else:
                        operation='reservation-cancel';path='/api/v1/reservations/'+order['id']+'/cancel';method='POST';kind='write';body={'expectedVersion':order['version']}
                    status,data,seconds=client.call(method,path,body)
                    if kind=='write' and 200<=status<300:order=data['data'] if operation=='reservation-create' else None
                    if kind=='write' and operation=='reservation-cancel' and status==409:
                        current=client.need('GET','/api/v1/reservations/'+order['id']);order=current if current['status']=='RESERVED' else None
                    records[client.number].append({'worker':client.number,'operation':operation,'kind':kind,'status':status,'seconds':seconds,'elapsed':at-timing['start'],'measured':at>=timing['measure'],'errorCode':data.get('error',{}).get('code') if isinstance(data,dict) else None})
                    sequence+=1
        finally:
            if order is not None:client.call('POST','/api/v1/reservations/'+order['id']+'/cancel',{'expectedVersion':order['version']})
    print(json.dumps({'ready':True,'warmupSeconds':WARMUP_SECONDS,'measureSeconds':MEASURE_SECONDS,'concurrency':CONCURRENCY,'output':str(directory)}),flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
        futures=[pool.submit(worker,c) for c in clients];ready.wait();now=time.perf_counter();timing.update(start=now,measure=now+WARMUP_SECONDS,end=now+WARMUP_SECONDS+MEASURE_SECONDS)
        sample_thread=threading.Thread(target=sampler,args=(stop,now,label,metrics,seen),daemon=True);sample_thread.start();start_event.set()
        for minute in range(6):
            time.sleep(60);print(json.dumps({'elapsedSeconds':round(time.perf_counter()-now,2),'requests':sum(len(r) for r in records),'phase':'warmup-complete' if minute==0 else 'measured'}),flush=True)
        for future in futures:future.result()
    stop.set();sample_thread.join(timeout=30)
    all_records=[r for worker_records in records for r in worker_records];measured=[r for r in all_records if r['measured']]
    with (directory/'requests.jsonl').open('w') as file:
        for row in all_records:file.write(json.dumps(row)+'\n')
    (directory/'database-samples.json').write_text(json.dumps(metrics,indent=2)+'\n')
    deadline=[{**p,**seen.get(p['id'],{}),'observedDelaySeconds':seen[p['id']]['firstObservedEpoch']-p['dueEpoch'] if p['id'] in seen else None} for p in probes]
    stats_after=json.loads(sql("SELECT coalesce(json_agg(s),'[]') FROM (SELECT query,calls,total_exec_time,rows,shared_blks_read,shared_blks_hit,temp_blks_written FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 80) s;"))
    (directory/'sql-after.json').write_text(json.dumps(stats_after,indent=2)+'\n')
    groups=summary(measured);server_errors=sum(r['status']>=500 for r in measured);transport=sum(r['status']==0 for r in measured);unexpected=sum(400<=r['status']<500 and r['status']!=409 for r in measured)
    report={'runId':run_id,'dataBefore':initial,'dataAfter':counts(),'warmupSeconds':WARMUP_SECONDS,'measurementSeconds':MEASURE_SECONDS,'concurrency':CONCURRENCY,'readRatio':groups['read']['requests']/len(measured),'requests':len(measured),'throughputPerSecond':len(measured)/MEASURE_SECONDS,'serverErrors':server_errors,'serverErrorRate':server_errors/len(measured),'transportErrors':transport,'unexpected4xx':unexpected,'groups':groups,'deadlineProbes':deadline,'contentionPassed':collision['passed'],'lockSamples':len(metrics),'lockWaitSamples':sum(m.get('waitingLocks',0)>0 for m in metrics),'maxLockWaitSeconds':max(m.get('maxLockWaitSeconds',0) for m in metrics),'passed':groups['read']['p95Ms'] is not None and groups['write']['p95Ms'] is not None and groups['read']['p95Ms']<=1000 and groups['write']['p95Ms']<=2000 and server_errors/len(measured)<0.01 and transport==0 and unexpected==0 and all(p['observedDelaySeconds'] is not None and p['observedDelaySeconds']<=60 for p in deadline)}
    (directory/'result.json').write_text(json.dumps(report,indent=2)+'\n');(OUTPUT/'latest-run.txt').write_text(run_id+'\n')
    print(json.dumps(report,indent=2),flush=True)
    # Recovery is measured after load, without deleting volumes or adjusting clocks.
    compose('stop','-t','60','repair');recovery_label='recovery-'+run_id;backlog=add_deadlines(recovery_label,-10);restart=time.time();compose('up','-d','--no-deps','repair')
    recovered={};deadline_end=time.monotonic()+120
    while time.monotonic()<deadline_end and len(recovered)<len(backlog):
        for row in probe_completion(recovery_label):
            recovered.setdefault(row['id'],time.time())
        time.sleep(1)
    recovery={'restartRequestedEpoch':restart,'backlogCount':len(backlog),'processedCount':len(recovered),'firstProcessingSeconds':min(recovered.values())-restart if recovered else None,'allProcessedSeconds':max(recovered.values())-restart if recovered else None,'passed':len(recovered)==len(backlog) and min(recovered.values())-restart<=60}
    (directory/'recovery.json').write_text(json.dumps(recovery,indent=2)+'\n');print(json.dumps({'recovery':recovery}),flush=True)
    if not report['passed'] or not recovery['passed']:raise SystemExit(2)

if __name__=='__main__':
    with exclusive_run():main()
