-- SELF is the explicit personal scope specified by the authorization contract.
-- It never grants a room-management scope. Preserve existing SHARED room scopes.
ALTER TABLE core.user_role_scope DROP CONSTRAINT user_role_scope_scope_type_check;
ALTER TABLE core.user_role_scope DROP CONSTRAINT user_role_scope_check;
ALTER TABLE core.user_role_scope ADD CONSTRAINT user_role_scope_scope_type_check
 CHECK (scope_type IN ('SELF','ALL','DEPARTMENT','ROOM','SHARED'));
ALTER TABLE core.user_role_scope ADD CONSTRAINT user_role_scope_check
 CHECK ((scope_type='DEPARTMENT' AND department_id IS NOT NULL AND room_id IS NULL)
 OR (scope_type='ROOM' AND room_id IS NOT NULL AND department_id IS NULL)
 OR (scope_type IN ('SELF','ALL','SHARED') AND room_id IS NULL AND department_id IS NULL));
