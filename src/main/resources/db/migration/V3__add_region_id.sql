-- V3: Denormalize region_id onto econtracts to enable region-scoped authorization
-- without cross-service joins or N+1 gRPC calls.
--
-- Rationale: MANAGER role scopes by managed_regions; TECHNICAL_STAFF scopes by
-- region_staff assignments. Filtering contracts by region requires either
-- (a) cross-service join via gRPC per row (slow) or (b) denormalizing the
-- house's region onto the contract at creation time. We pick (b) because
-- a contract's house rarely changes region (only when the ward is merged,
-- which is a one-time sysadmin operation).

ALTER TABLE econtracts
    ADD COLUMN IF NOT EXISTS region_id uuid;

-- Partial index: most queries filter by (region_id, status) for active contracts.
CREATE INDEX IF NOT EXISTS idx_econtracts_region_status
    ON econtracts (region_id, status)
    WHERE region_id IS NOT NULL;

-- Existing rows have null region_id; they will remain scoped only to
-- the LANDLORD (who sees all) and the TENANT (who sees by userId).
-- MANAGER/TECHNICAL_STAFF will not see legacy rows without region_id —
-- admin must backfill via a one-time UPDATE from house-service if needed.
-- New contracts populate region_id from House gRPC response on create.
