ALTER TABLE econtracts
    ADD COLUMN IF NOT EXISTS deposit_status varchar(40),
    ADD COLUMN IF NOT EXISTS relocation_source_contract_id uuid,
    ADD COLUMN IF NOT EXISTS replaced_by_contract_id uuid,
    ADD COLUMN IF NOT EXISTS transferred_deposit_amount bigint DEFAULT 0;

UPDATE econtracts
SET deposit_status = CASE
    WHEN COALESCE(deposit_amount, 0) = 0 THEN 'PAID'
    ELSE 'PENDING'
END
WHERE deposit_status IS NULL;

CREATE TABLE IF NOT EXISTS contract_relocation_requests (
    id uuid PRIMARY KEY,
    old_contract_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    old_house_id uuid NOT NULL,
    requested_house_id uuid NOT NULL,
    approved_house_id uuid,
    new_contract_id uuid,
    status varchar(40) NOT NULL,
    fault_party varchar(24) NOT NULL,
    deposit_status_snapshot varchar(40) NOT NULL,
    deposit_handling varchar(40),
    deposit_amount bigint,
    transferred_deposit_amount bigint,
    forfeit_amount bigint,
    additional_deposit_amount bigint,
    new_rent_amount bigint,
    new_deposit_amount bigint,
    new_start_at timestamptz,
    new_end_at timestamptz,
    new_handover_date timestamptz,
    tenant_reason text,
    manager_note text,
    requested_by uuid NOT NULL,
    reviewed_by uuid,
    requested_at timestamptz NOT NULL,
    reviewed_at timestamptz,
    contract_created_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz
);

CREATE INDEX IF NOT EXISTS idx_relocation_old_contract
    ON contract_relocation_requests(old_contract_id, status);

CREATE INDEX IF NOT EXISTS idx_relocation_tenant
    ON contract_relocation_requests(tenant_id, status);

CREATE INDEX IF NOT EXISTS idx_relocation_new_contract
    ON contract_relocation_requests(new_contract_id);
