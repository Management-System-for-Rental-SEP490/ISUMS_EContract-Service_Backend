-- V8: extend contract_co_tenants with passport + visa for foreign co-tenants
--
-- Temporary-residence registration per Luật Cư Trú 2020 requires VN
-- authorities to know passport details and visa validity for non-VN
-- residents living in the leased unit. Previously the ContractCoTenant
-- entity captured only the primary ID document; landlords had to track
-- foreign co-tenant visas out-of-band.
--
-- Columns NULLABLE because:
--   - VN co-tenants never have passport_number / visa_*,
--   - legacy rows have no visa data by definition.
-- No backfill needed.

ALTER TABLE contract_co_tenants
    ADD COLUMN IF NOT EXISTS passport_number  VARCHAR(64),
    ADD COLUMN IF NOT EXISTS visa_type        VARCHAR(64),
    ADD COLUMN IF NOT EXISTS visa_expiry_date DATE;
