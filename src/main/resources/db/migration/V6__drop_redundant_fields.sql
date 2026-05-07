-- V6: Drop fields that are either redundant with House data (usable_area_m2)
-- or meaningless for e-contracts.
--
-- Rationale (decision 2026-04-21):
--   1. usable_area_m2 — house area is a fact about the house, not about the
--      contract. Moved to houses.area_m2 and pulled into the template via
--      gRPC at render time. No two leases for the same house should ever
--      disagree on the physical area.
--
--   Note: `purpose`, `area`, `structure`, `copies`, `each_keep` were never
--   persisted as columns on econtracts — they lived only as DTO fields
--   routed directly into the HTML template render. Dropping them is a
--   Java-side cleanup, no SQL needed.
--
--   Note: gas meter reading was a key inside the `meter_readings_start`
--   jsonb; no schema change needed — the builder just stops binding that key.

ALTER TABLE econtracts DROP COLUMN IF EXISTS usable_area_m2;
