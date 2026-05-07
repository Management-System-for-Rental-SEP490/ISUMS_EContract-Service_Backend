-- DEPRECATED: this file is a duplicate of V9__contract_relocation_requests.sql.
-- Originally intended as a backfill but the body was a copy of V9.
-- Kept as a no-op so Flyway's recorded checksum remains stable across deployments.
-- All schema changes for relocation requests live in:
--   - V9__contract_relocation_requests.sql (initial table + econtracts ALTER)
--   - V20260428_1600__landlord_fault_relocation.sql
--   - V20260428_1700__active_lease_relocation.sql
--   - V20260430_1200__relocation_one_open_per_contract_unique.sql

SELECT 1 WHERE FALSE;
