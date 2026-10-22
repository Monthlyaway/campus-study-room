#!/usr/bin/env python3
"""Run only after the load window; inspect page query growth and real PostgreSQL plans."""
import datetime as dt
import json
from environment import exclusive_run, ROOT, OUTPUT, ident, sql
from run import Client, probe_sql


def main():
    run_id=(OUTPUT/'latest-run.txt').read_text().strip()
    directory=OUTPUT/run_id
    client=Client(0)
    probe_sql(client,[
        ('room-list-1','/api/v1/rooms?page=1&size=1'),
        ('room-list-20','/api/v1/rooms?page=1&size=20'),
        ('reservation-list-1','/api/v1/reservations?page=1&size=1'),
        ('reservation-list-20','/api/v1/reservations?page=1&size=20'),
    ],directory/'page-query-counts.json')
    user=ident('user',0)
    history=f"select r.*,s.code seat_code,s.has_power,rm.name room_name,rm.code room_code from core.reservation r join core.seat s on s.id=r.seat_id join core.room rm on rm.id=r.room_id where r.user_id='{user}'::uuid order by r.start_at desc,r.id"
    date=(dt.datetime.now(dt.timezone(dt.timedelta(hours=8)))+dt.timedelta(days=2)).replace(hour=12,minute=0,second=0,microsecond=0)
    start=date.isoformat();end=(date+dt.timedelta(hours=1)).isoformat()
    # Exact production projection/predicates with known fixture bind values substituted.
    availability=f"""select s.id,s.id seat_id,s.room_id,s.code,s.has_power,r.name room_name,r.code room_code,b.name building_name,c.name campus_name,r.building_id,b.campus_id,true available
      from core.room r join core.seat s on s.room_id=r.id and s.enabled
      join core.building b on b.id=r.building_id and b.enabled
      join core.campus c on c.id=b.campus_id and c.enabled
      join core.app_user u on u.id='{user}'::uuid
      where r.enabled and (r.department_id is null or r.department_id=u.department_id)
      and not exists(select 1 from generate_series('{start}'::timestamptz,'{end}'::timestamptz-interval '1 hour',interval '1 hour') requested_hour
        where not exists(select 1 from core.opening_rule opening where opening.room_id=r.id
          and opening.weekday=extract(isodow from requested_hour at time zone 'Asia/Shanghai')
          and opening.start_minute<=extract(hour from requested_hour at time zone 'Asia/Shanghai')*60
          and opening.end_minute>=(extract(hour from requested_hour at time zone 'Asia/Shanghai')+1)*60))
      and not exists(select 1 from core.resource_claim claim where claim.room_id=r.id
        and (claim.seat_id is null or claim.seat_id=s.id) and claim.released_at is null
        and claim.start_at<'{end}'::timestamptz and (claim.end_at is null or claim.end_at>'{start}'::timestamptz))
      and not exists(select 1 from core.reservation own where own.user_id='{user}'::uuid
        and own.status in('RESERVED','CHECKED_IN') and own.start_at<'{end}'::timestamptz and own.end_at>'{start}'::timestamptz)
      order by r.code,s.code"""
    plans={}
    for name,query in [('reservation-history-page',history+' limit 20 offset 0'),('reservation-history-count','select count(*) from ('+history+') page_count'),('availability-page',availability+' limit 20 offset 0'),('availability-count','select count(*) from ('+availability+') page_count')]:
        plans[name]={'sql':query,'explain':json.loads(sql('EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) '+query))}
    (directory/'query-plans.json').write_text(json.dumps(plans,indent=2)+'\n')
    counts=json.loads((directory/'page-query-counts.json').read_text())
    print(json.dumps({name:row['totalStatements'] for name,row in counts.items()},indent=2))
    print(json.dumps({name:row['explain'][0]['Execution Time'] for name,row in plans.items()},indent=2))

if __name__=='__main__':
    with exclusive_run():main()
