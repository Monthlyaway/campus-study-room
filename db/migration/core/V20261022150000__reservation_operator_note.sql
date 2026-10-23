-- Expand-only metadata for operational annotations; existing application versions ignore it.
-- Keep nullable and without a default so existing reservations and old-version writes remain valid.
ALTER TABLE core.reservation ADD COLUMN operator_note TEXT;
COMMENT ON COLUMN core.reservation.operator_note IS
    'Optional operational annotation; does not affect reservation state, permission or resource claims';
