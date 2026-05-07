ALTER TABLE landlord_profiles
    ADD COLUMN IF NOT EXISTS deposit_wait_days INTEGER NOT NULL DEFAULT 3
        CHECK (deposit_wait_days BETWEEN 1 AND 30);

ALTER TABLE landlord_profiles
    ADD COLUMN IF NOT EXISTS force_majeure_notice_hours INTEGER NOT NULL DEFAULT 24
        CHECK (force_majeure_notice_hours BETWEEN 1 AND 168);

ALTER TABLE econtracts
    ADD COLUMN IF NOT EXISTS deposit_due_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_econtracts_deposit_expiry
    ON econtracts (deposit_due_at)
    WHERE deposit_due_at IS NOT NULL
      AND deposit_status = 'UNPAID'
      AND status = 'COMPLETED';
