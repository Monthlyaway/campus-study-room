#!/usr/bin/env python3
"""Direct core HTTP timing; secret is sent on stdin to curl, never argv/logged."""
import concurrent.futures
import json
import sys
from environment import exclusive_run, OUTPUT, STATE, compose, ident
from run import percentile


def main():
    name=sys.argv[1]
    if name not in ('before','after'):raise SystemExit('Use before or after')
    secret=(STATE/'secrets/internal_password').read_text().strip()
    endpoint='/internal/v1/rooms/'+ident('room',0)+'/reference'
    cases={'public':('/api/v1/version',None,None,200),'valid':(endpoint,'repair-service',secret,200),'invalid':(endpoint,'repair-service',secret+'x',401),'wrong-user':(endpoint,'wrong-service',secret,401),'empty':(endpoint,'repair-service','',401),'missing':(endpoint,None,None,401)}
    report={}
    for case,(path,username,password,expected) in cases.items():
        config='url = "http://localhost:8080'+path+'"\n'
        if username is not None:config+='user = "'+username+':'+password+'"\n'
        def request(_):
            raw=compose('exec','-T','core','curl','--config','-','--silent','--output','/dev/null','--write-out','%{http_code} %{time_total}',capture=True,input=config).stdout
            status,seconds=raw.split();return {'status':int(status),'seconds':float(seconds)}
        with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:rows=list(pool.map(request,range(20)))
        report[case]={'passed':all(r['status']==expected for r in rows),'concurrency':20,'requests':rows,'p50Ms':percentile([r['seconds']*1000 for r in rows],50),'p95Ms':percentile([r['seconds']*1000 for r in rows],95)}
    directory=OUTPUT/(OUTPUT/'latest-run.txt').read_text().strip()
    (directory/('internal-auth-'+name+'.json')).write_text(json.dumps(report,indent=2)+'\n')
    if not all(row['passed'] for row in report.values()):raise RuntimeError('Internal HTTP authentication status assertion failed')
    print(json.dumps({case:{k:v for k,v in row.items() if k!='requests'} for case,row in report.items()},indent=2))
if __name__=='__main__':
    with exclusive_run():main()
