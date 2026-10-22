CREATE TABLE repair.repair_order (
 id uuid PRIMARY KEY, reporter_id uuid NOT NULL, room_id uuid NOT NULL,
 title varchar(100) NOT NULL CHECK (length(trim(title))>0), description varchar(2000) NOT NULL CHECK (length(trim(description))>0),
 status varchar(32) NOT NULL CHECK (status IN ('SUBMITTED','ASSIGNED','BLOCK_PENDING','IN_REPAIR','AWAITING_ACCEPTANCE','RELEASE_PENDING','CLOSED','CANCELLED')),
 assigned_to uuid, assigned_by uuid, submitted_at timestamptz NOT NULL, first_assigned_at timestamptz, assigned_at timestamptz,
 work_completed_at timestamptz, accepted_at timestamptz, closed_at timestamptz, assign_due_at timestamptz NOT NULL,
 work_due_at timestamptz, accept_due_at timestamptz, acceptance_round integer NOT NULL DEFAULT 0 CHECK(acceptance_round>=0),
 last_work_note varchar(2000), resource_version bigint NOT NULL DEFAULT 0 CHECK(resource_version BETWEEN 0 AND 2),
 version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 CHECK ((status IN ('SUBMITTED','CANCELLED')) OR assigned_to IS NOT NULL)
);
CREATE INDEX repair_order_reporter ON repair.repair_order(reporter_id,submitted_at DESC);
CREATE INDEX repair_order_room_status ON repair.repair_order(room_id,status);
CREATE INDEX repair_order_assigned_status ON repair.repair_order(assigned_to,status);
CREATE INDEX repair_order_assign_due ON repair.repair_order(status,assign_due_at);
CREATE INDEX repair_order_work_due ON repair.repair_order(status,work_due_at);
CREATE INDEX repair_order_accept_due ON repair.repair_order(status,accept_due_at);
CREATE TABLE repair.repair_history (
 id uuid PRIMARY KEY, order_id uuid NOT NULL REFERENCES repair.repair_order(id), from_status varchar(32), to_status varchar(32) NOT NULL,
 actor_id uuid, reason varchar(2000), old_assignee uuid, new_assignee uuid, request_id uuid NOT NULL, created_at timestamptz NOT NULL
);
CREATE INDEX repair_history_order ON repair.repair_history(order_id,created_at,id);
CREATE TABLE repair.repair_acceptance (
 id uuid PRIMARY KEY, order_id uuid NOT NULL REFERENCES repair.repair_order(id), round_no integer NOT NULL CHECK(round_no>0),
 actor_id uuid NOT NULL, decision varchar(16) NOT NULL CHECK(decision IN ('ACCEPT','REJECT')), comment varchar(500), created_at timestamptz NOT NULL,
 UNIQUE(order_id,round_no), CHECK(decision<>'REJECT' OR length(trim(comment))>0)
);
CREATE TABLE repair.repair_rating (
 id uuid PRIMARY KEY, order_id uuid NOT NULL UNIQUE REFERENCES repair.repair_order(id), reporter_id uuid NOT NULL,
 score integer NOT NULL CHECK(score BETWEEN 1 AND 5), comment varchar(500), created_at timestamptz NOT NULL
);
CREATE TABLE repair.outbox_command (
 command_id uuid PRIMARY KEY, order_id uuid NOT NULL REFERENCES repair.repair_order(id), command_type varchar(16) NOT NULL CHECK(command_type IN ('BLOCK','RELEASE','NOTIFY')),
 source_version bigint NOT NULL, payload_json jsonb NOT NULL, payload_hash varchar(64) NOT NULL,
 status varchar(16) NOT NULL CHECK(status IN ('PENDING','LEASED','DONE')), attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
 next_attempt_at timestamptz NOT NULL, lease_owner uuid, lease_until timestamptz, last_attempt_at timestamptz, last_error_code varchar(100), request_id uuid NOT NULL,
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, version bigint NOT NULL DEFAULT 0,
 CHECK((command_type='BLOCK' AND source_version=1) OR (command_type='RELEASE' AND source_version=2) OR (command_type='NOTIFY' AND source_version=0))
);
CREATE INDEX outbox_due ON repair.outbox_command(status,next_attempt_at);
CREATE INDEX outbox_order ON repair.outbox_command(order_id,created_at);
CREATE TABLE repair.scheduled_task (
 id uuid PRIMARY KEY, task_type varchar(64) NOT NULL, source_type varchar(8) NOT NULL CHECK(source_type='M3'), source_id uuid NOT NULL REFERENCES repair.repair_order(id),
 dedup_key varchar(255) NOT NULL UNIQUE, cycle integer NOT NULL DEFAULT 0, due_at timestamptz NOT NULL,
 status varchar(16) NOT NULL CHECK(status IN ('PENDING','LEASED','DONE')), attempts integer NOT NULL DEFAULT 0,
 next_attempt_at timestamptz NOT NULL, lease_owner uuid, lease_until timestamptz, last_error_code varchar(100), created_at timestamptz NOT NULL, version bigint NOT NULL DEFAULT 0
);
CREATE INDEX repair_task_due ON repair.scheduled_task(status,next_attempt_at);
CREATE TABLE repair.audit_event (
 id uuid PRIMARY KEY, actor_type varchar(16) NOT NULL CHECK(actor_type IN ('USER','SERVICE','JOB')), actor_id uuid, action varchar(100) NOT NULL,
 object_type varchar(32) NOT NULL, object_id uuid NOT NULL, room_id uuid, source_type varchar(8), source_id uuid,
 before_json jsonb, after_json jsonb, reason varchar(2000), request_id uuid NOT NULL, created_at timestamptz NOT NULL
);
CREATE INDEX repair_audit_created ON repair.audit_event(created_at DESC);
CREATE INDEX repair_audit_room_created ON repair.audit_event(room_id,created_at DESC);
CREATE INDEX repair_audit_object ON repair.audit_event(object_id,created_at DESC);
CREATE TABLE repair.idempotency_record (
 id uuid PRIMARY KEY, actor_type varchar(16) NOT NULL, actor_id uuid NOT NULL, method varchar(8) NOT NULL, canonical_path varchar(255) NOT NULL,
 idempotency_key uuid NOT NULL, request_hash varchar(64) NOT NULL, http_status integer NOT NULL,
 response_data_json jsonb NOT NULL, created_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
 UNIQUE(actor_id,method,canonical_path,idempotency_key)
);
