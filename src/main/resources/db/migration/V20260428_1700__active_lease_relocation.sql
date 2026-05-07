ALTER TABLE contract_relocation_requests
    ADD COLUMN IF NOT EXISTS request_kind varchar(40),
    ADD COLUMN IF NOT EXISTS desired_move_date timestamptz,
    ADD COLUMN IF NOT EXISTS occupant_count integer,
    ADD COLUMN IF NOT EXISTS old_rent_prorated_amount bigint DEFAULT 0,
    ADD COLUMN IF NOT EXISTS old_utilities_amount bigint DEFAULT 0,
    ADD COLUMN IF NOT EXISTS old_damage_amount bigint DEFAULT 0,
    ADD COLUMN IF NOT EXISTS admin_fee_amount bigint DEFAULT 0,
    ADD COLUMN IF NOT EXISTS settlement_amount bigint DEFAULT 0,
    ADD COLUMN IF NOT EXISTS refundable_deposit_amount bigint DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_additional_payment_amount bigint DEFAULT 0,
    ADD COLUMN IF NOT EXISTS inspection_note text,
    ADD COLUMN IF NOT EXISTS tenant_accepted_at timestamptz;

UPDATE contract_relocation_requests
SET request_kind = CASE
    WHEN fault_party = 'LANDLORD' THEN 'LANDLORD_FAULT_UNINHABITABLE'
    ELSE 'PRE_HANDOVER_TENANT_REQUEST'
END
WHERE request_kind IS NULL;

ALTER TABLE contract_relocation_requests
    ALTER COLUMN request_kind SET NOT NULL,
    ALTER COLUMN old_rent_prorated_amount SET NOT NULL,
    ALTER COLUMN old_utilities_amount SET NOT NULL,
    ALTER COLUMN old_damage_amount SET NOT NULL,
    ALTER COLUMN admin_fee_amount SET NOT NULL,
    ALTER COLUMN settlement_amount SET NOT NULL,
    ALTER COLUMN refundable_deposit_amount SET NOT NULL,
    ALTER COLUMN total_additional_payment_amount SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_relocation_kind_status
    ON contract_relocation_requests(request_kind, status);
