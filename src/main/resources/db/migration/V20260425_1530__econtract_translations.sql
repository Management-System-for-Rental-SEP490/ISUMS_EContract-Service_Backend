-- Phase 5c i18n: contract metadata names. Body HTML is intentionally NOT
-- translated automatically to avoid semantic drift in legal text.
ALTER TABLE econtracts
    ADD COLUMN IF NOT EXISTS name_translations TEXT;

ALTER TABLE econtract_templates
    ADD COLUMN IF NOT EXISTS name_translations TEXT;

COMMENT ON COLUMN econtracts.name_translations IS
    'JSON map of locale -> translated contract display name. Body HTML is not auto-translated.';
COMMENT ON COLUMN econtract_templates.name_translations IS
    'JSON map of locale -> translated template name.';
