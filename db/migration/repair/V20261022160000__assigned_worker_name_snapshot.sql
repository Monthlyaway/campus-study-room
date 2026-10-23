-- Expand-only: old application versions keep working and old orders remain readable.
-- Populate only from the authorized core candidate response at assignment time.
ALTER TABLE repair.repair_order ADD COLUMN assigned_to_name varchar(64);
