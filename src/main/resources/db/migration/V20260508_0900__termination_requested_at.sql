ALTER TABLE econtracts
    ADD COLUMN IF NOT EXISTS termination_requested_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_econtracts_termination_requested
    ON econtracts(termination_requested_at)
    WHERE termination_requested_at IS NOT NULL;
