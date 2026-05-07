-- V5: Field cleanup + soft-delete audit.
--
-- Rationale (audit 2026-04-21):
--   1. `tenant_id` column has never been set or read by service code.
--      Entity uses `user_id` exclusively; Kafka events map userId→tenantId
--      as a payload name only. Drop the column to remove dead storage.
--   2. `price` renamed to `rent_amount` — matches Request DTO naming,
--      eliminates the MapStruct `@Mapping(source="rentAmount", target="price")`
--      indirection which was a leftover from pre-legal-fields era.
--   3. `tenant_identity_number` renamed to `cccd_number` — the column stores
--      ONLY Vietnamese citizen ID (CCCD) values (12-digit). Foreign tenants
--      use the separate `passport_number` column. New name makes intent obvious.
--   4. Add `deleted_at` + `deleted_by` for soft-delete. Vietnamese Law on
--      Accounting + Law on Contracts require retaining contract records for
--      ~10 years even after termination. Physical DELETE is not compliant.

-- ---------- DROP dead column ----------
ALTER TABLE econtracts DROP COLUMN IF EXISTS tenant_id;

-- ---------- RENAME for semantic clarity ----------
-- Use IF EXISTS + DO blocks so reruns on partially-migrated DBs are safe.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'public'
                 AND table_name = 'econtracts'
                 AND column_name = 'price') THEN
        ALTER TABLE econtracts RENAME COLUMN price TO rent_amount;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'public'
                 AND table_name = 'econtracts'
                 AND column_name = 'tenant_identity_number') THEN
        ALTER TABLE econtracts RENAME COLUMN tenant_identity_number TO cccd_number;
    END IF;
END $$;

-- ---------- ADD soft-delete audit ----------
ALTER TABLE econtracts
    ADD COLUMN IF NOT EXISTS deleted_at timestamp(6) with time zone,
    ADD COLUMN IF NOT EXISTS deleted_by uuid;

-- Partial index: 99% of queries filter out deleted rows. Keeps the index tiny.
CREATE INDEX IF NOT EXISTS idx_econtracts_active
    ON econtracts (id) WHERE deleted_at IS NULL;
