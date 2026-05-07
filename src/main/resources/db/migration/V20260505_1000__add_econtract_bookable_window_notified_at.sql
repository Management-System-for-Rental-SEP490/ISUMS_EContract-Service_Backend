ALTER TABLE econtracts
    ADD COLUMN IF NOT EXISTS bookable_window_notified_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_econtracts_bookable_window_pending
    ON econtracts (status, end_at)
    WHERE bookable_window_notified_at IS NULL;
