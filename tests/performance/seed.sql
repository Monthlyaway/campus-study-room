\set ON_ERROR_STOP on
\getenv perf_password PERFORMANCE_PASSWORD
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM core.app_user) OR EXISTS(SELECT 1 FROM core.room) THEN
   RAISE EXCEPTION 'Performance seed requires a fresh, reference-only isolated database';
 END IF;
END $$;
BEGIN;
CREATE TEMP TABLE fixture_password AS SELECT '{bcrypt}'||crypt(:'perf_password',gen_salt('bf',12)) value;
INSERT INTO core.department(id,code,name) VALUES(md5('performance:department')::uuid,'PERF','性能验收院系');
INSERT INTO core.campus(id,code,name) VALUES(md5('performance:campus')::uuid,'PERF','性能验收校区');
INSERT INTO core.building(id,campus_id,code,name) VALUES(md5('performance:building')::uuid,md5('performance:campus')::uuid,'PERF','性能验收教学楼');
INSERT INTO core.app_user(id,username,school_no,display_name,password_hash,email,department_id)
 SELECT md5('performance:user:'||n)::uuid,'perf'||lpad(n::text,4,'0'),'PERF'||lpad(n::text,4,'0'),'性能用户'||n,p.value,'perf'||n||'@example.invalid',md5('performance:department')::uuid
 FROM generate_series(0,999) n CROSS JOIN fixture_password p;
INSERT INTO core.booking_guard SELECT id FROM core.app_user;
INSERT INTO core.user_role(id,user_id,role_id)
 SELECT md5('performance:student-role:'||n)::uuid,md5('performance:user:'||n)::uuid,r.id FROM generate_series(0,999) n CROSS JOIN core.role r WHERE r.code='STUDENT';
INSERT INTO core.user_role_scope(id,user_role_id,scope_type)
 SELECT md5('performance:student-scope:'||n)::uuid,md5('performance:student-role:'||n)::uuid,'SELF' FROM generate_series(0,999) n;
INSERT INTO core.user_role(id,user_id,role_id)
 SELECT md5('performance:admin-role:'||r.code)::uuid,md5('performance:user:999')::uuid,r.id FROM core.role r WHERE r.code IN('SYSTEM_ADMIN','ROOM_ADMIN','PROPERTY_DISPATCHER','PROPERTY_ADMIN','OPERATOR');
INSERT INTO core.user_role_scope(id,user_role_id,scope_type)
 SELECT md5('performance:admin-scope:'||r.code)::uuid,md5('performance:admin-role:'||r.code)::uuid,'ALL' FROM core.role r WHERE r.code IN('SYSTEM_ADMIN','ROOM_ADMIN','PROPERTY_DISPATCHER','PROPERTY_ADMIN','OPERATOR');
INSERT INTO core.room(id,building_id,code,name)
 SELECT md5('performance:room:'||n)::uuid,md5('performance:building')::uuid,'PERF-'||lpad(n::text,2,'0'),'性能教室'||n FROM generate_series(0,19) n;
INSERT INTO core.seat(id,room_id,code,has_power)
 SELECT md5('performance:seat:'||n)::uuid,md5('performance:room:'||(n/50))::uuid,lpad((n%50+1)::text,2,'0'),n%2=0 FROM generate_series(0,999) n;
INSERT INTO core.opening_rule(id,room_id,weekday,start_minute,end_minute)
 SELECT md5('performance:opening:'||r||':'||d)::uuid,md5('performance:room:'||r)::uuid,d,0,1440 FROM generate_series(0,19) r CROSS JOIN generate_series(1,7) d;
CREATE TEMP TABLE historical AS
 SELECT n,md5('performance:history:'||n)::uuid id,md5('performance:user:'||(n%1000))::uuid user_id,md5('performance:seat:'||(n%1000))::uuid seat_id,md5('performance:room:'||((n%1000)/50))::uuid room_id,
 (date_trunc('day',now() AT TIME ZONE 'Asia/Shanghai')-interval '200 days'+(n/1000)*interval '1 day'+interval '10 hours') AT TIME ZONE 'Asia/Shanghai' start_at FROM generate_series(0,99999) n;
INSERT INTO core.reservation(id,user_id,room_id,seat_id,start_at,end_at,status,checked_in_at,ended_at,config_version,max_duration_hours,version,created_at,updated_at)
 SELECT id,user_id,room_id,seat_id,start_at,start_at+interval '1 hour','COMPLETED',start_at,start_at+interval '1 hour',0,4,2,start_at-interval '1 day',start_at+interval '1 hour' FROM historical;
INSERT INTO core.resource_claim(id,room_id,seat_id,start_at,end_at,source_type,source_id,source_version,released_at,created_by,created_request_id,release_request_id)
 SELECT md5('performance:claim:'||n)::uuid,room_id,seat_id,start_at,start_at+interval '1 hour','M1',id,1,start_at+interval '1 hour',user_id,md5('performance:create:'||n)::uuid,md5('performance:release:'||n)::uuid FROM historical;
INSERT INTO core.audit_event(id,actor_type,actor_id,action,object_type,object_id,room_id,source_type,source_id,after_json,request_id,created_at)
 SELECT md5('performance:audit:'||n)::uuid,'JOB',user_id,'M1_COMPLETE','M1',id,room_id,'M1',id,'{"status":"COMPLETED"}',md5('performance:release:'||n)::uuid,start_at+interval '1 hour' FROM historical;
INSERT INTO core.scheduled_task(id,task_type,source_type,source_id,dedup_key,due_at,status,next_attempt_at)
 SELECT md5('performance:task:'||h.n||':'||t.kind)::uuid,t.kind,'M1',h.id,'PERF:'||h.id||':'||t.kind,h.start_at+t.offset_time,'DONE',h.start_at+t.offset_time
 FROM historical h CROSS JOIN(VALUES('M1_NO_SHOW',interval '15 minutes'),('M1_COMPLETE',interval '1 hour')) t(kind,offset_time);
INSERT INTO core.notification(id,recipient_id,room_id,event_type,source_type,source_id,dedup_key,payload_json,due_at,status,next_attempt_at,sent_at,created_at)
 SELECT md5('performance:notification:'||n)::uuid,user_id,room_id,'RESERVED','M1',id,'PERF:'||id||':RESERVED','{"fixture":"historical"}',start_at-interval '1 day','SENT',start_at-interval '1 day',start_at-interval '1 day',start_at-interval '1 day' FROM historical;
COMMIT;
ANALYZE;
