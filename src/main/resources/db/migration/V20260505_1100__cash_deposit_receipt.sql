CREATE SEQUENCE IF NOT EXISTS cash_deposit_receipt_seq
    START WITH 1
    INCREMENT BY 1
    NO MAXVALUE
    NO CYCLE;

CREATE TABLE IF NOT EXISTS cash_deposit_receipt (
    id                      uuid          PRIMARY KEY,
    contract_id             uuid          NOT NULL REFERENCES econtracts(id),
    receipt_number          varchar(32)   NOT NULL UNIQUE,
    amount                  bigint        NOT NULL CHECK (amount > 0),
    paid_at                 timestamptz   NOT NULL,
    confirmed_by_user_id    uuid          NOT NULL,
    payer_name              varchar(255),
    payee_name              varchar(255),
    note                    text,
    voided_at               timestamptz,
    voided_by_user_id       uuid,
    void_reason             text,
    voids_receipt_id        uuid          REFERENCES cash_deposit_receipt(id),
    idempotency_key         varchar(64)   UNIQUE,
    created_at              timestamptz   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_cash_deposit_receipt_active
    ON cash_deposit_receipt (contract_id)
    WHERE voided_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_cash_deposit_receipt_confirmed_by
    ON cash_deposit_receipt (confirmed_by_user_id, created_at DESC);
