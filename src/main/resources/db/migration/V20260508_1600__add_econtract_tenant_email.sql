ALTER TABLE econtracts
    ADD COLUMN IF NOT EXISTS tenant_email VARCHAR(255);

UPDATE econtracts
SET tenant_email = lower(trim(tenant_email))
WHERE tenant_email IS NOT NULL
  AND tenant_email <> lower(trim(tenant_email));

