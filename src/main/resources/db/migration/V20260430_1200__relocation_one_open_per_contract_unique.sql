-- Enforce: at most one OPEN relocation request per contract (B5).
-- The service-level existsByOldContractIdAndStatusIn check is racy under concurrent submits
-- and report endpoints; this partial unique index makes it impossible at the DB layer.

-- Step 1: defensively close any existing duplicates (oldest open ones lose; newest wins).
WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY old_contract_id
               ORDER BY created_at DESC, requested_at DESC
           ) AS rn
    FROM contract_relocation_requests
    WHERE status IN (
        'REQUESTED', 'QUOTED', 'APPROVED',
        'CONTRACT_CREATED', 'ADDITIONAL_PAYMENT_PENDING', 'REFUND_PENDING'
    )
)
UPDATE contract_relocation_requests cr
SET status = 'CANCELLED',
    completed_at = COALESCE(cr.completed_at, now()),
    updated_at = now()
FROM ranked
WHERE cr.id = ranked.id AND ranked.rn > 1;

-- Step 2: partial unique index — at most one row in OPEN states per old_contract_id.
CREATE UNIQUE INDEX IF NOT EXISTS uq_relocation_one_open_per_contract
    ON contract_relocation_requests (old_contract_id)
    WHERE status IN (
        'REQUESTED', 'QUOTED', 'APPROVED',
        'CONTRACT_CREATED', 'ADDITIONAL_PAYMENT_PENDING', 'REFUND_PENDING'
    );
