CREATE TABLE IF NOT EXISTS contract_demo_sessions (
    id UUID PRIMARY KEY,
    contract_id UUID NOT NULL REFERENCES econtracts(id),
    scenario VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL,
    started_by UUID NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_contract_demo_contract_status
    ON contract_demo_sessions(contract_id, status);

CREATE UNIQUE INDEX IF NOT EXISTS uq_contract_demo_active
    ON contract_demo_sessions(contract_id)
    WHERE status = 'ACTIVE';
