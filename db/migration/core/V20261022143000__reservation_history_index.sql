-- Completed/cancelled history is deliberately excluded from reservation_user_time.
-- Personal history filters by user/date and orders by start_at DESC, id; keep this
-- separate full-history index so pagination does not scan every user's old rows.
CREATE INDEX reservation_user_history
    ON core.reservation (user_id, start_at DESC, id);
