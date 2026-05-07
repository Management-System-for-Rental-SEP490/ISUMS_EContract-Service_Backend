ALTER TABLE contract_relocation_requests
    ALTER COLUMN requested_house_id DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS resolution_type varchar(40),
    ADD COLUMN IF NOT EXISTS staff_reported_by uuid,
    ADD COLUMN IF NOT EXISTS staff_reported_at timestamptz,
    ADD COLUMN IF NOT EXISTS staff_report_reason text,
    ADD COLUMN IF NOT EXISTS staff_evidence text,
    ADD COLUMN IF NOT EXISTS refund_amount bigint DEFAULT 0,
    ADD COLUMN IF NOT EXISTS refund_due_at timestamptz,
    ADD COLUMN IF NOT EXISTS legal_basis text;

UPDATE contract_relocation_requests
SET resolution_type = 'REPLACE_HOUSE'
WHERE resolution_type IS NULL;

CREATE INDEX IF NOT EXISTS idx_relocation_staff_reported_by
    ON contract_relocation_requests(staff_reported_by, status);
