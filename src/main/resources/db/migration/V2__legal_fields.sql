-- V2: Vietnamese legal-compliance fields for rental contracts.
-- Pháp lý: Luật Nhà ở 2023 (Đ.163), BLDS 2015 (Đ.472-482, Đ.408),
-- Luật Cư trú 2020, Luật Kinh doanh BĐS 2023.
-- Supports Vietnamese + foreign tenants (passport OCR), single-landlord
-- whole-house rental (no utility billing), bilingual contracts (VI | VI_EN | VI_JA).

-- =========================================================================
-- econtracts: tenant identity (VN vs foreign), legal metadata, handover snapshot
-- =========================================================================
ALTER TABLE econtracts
    ADD COLUMN IF NOT EXISTS tenant_type              varchar(32),
    ADD COLUMN IF NOT EXISTS contract_language        varchar(16),
    ADD COLUMN IF NOT EXISTS date_of_birth            date,
    ADD COLUMN IF NOT EXISTS gender                   varchar(16),
    ADD COLUMN IF NOT EXISTS nationality              varchar(64),
    ADD COLUMN IF NOT EXISTS occupation               varchar(255),
    ADD COLUMN IF NOT EXISTS permanent_address        varchar(512),
    ADD COLUMN IF NOT EXISTS detailed_address         jsonb,
    ADD COLUMN IF NOT EXISTS passport_number          varchar(64),
    ADD COLUMN IF NOT EXISTS passport_issue_date      date,
    ADD COLUMN IF NOT EXISTS passport_issue_place     varchar(255),
    ADD COLUMN IF NOT EXISTS passport_expiry_date     date,
    ADD COLUMN IF NOT EXISTS visa_type                varchar(64),
    ADD COLUMN IF NOT EXISTS visa_expiry_date         date,
    ADD COLUMN IF NOT EXISTS passport_front_key       varchar(255),
    ADD COLUMN IF NOT EXISTS passport_verified_at     timestamp(6) with time zone,
    ADD COLUMN IF NOT EXISTS land_cert_number         varchar(128),
    ADD COLUMN IF NOT EXISTS land_cert_issue_date     date,
    ADD COLUMN IF NOT EXISTS land_cert_issuer         varchar(255),
    ADD COLUMN IF NOT EXISTS usable_area_m2           numeric(10, 2),
    ADD COLUMN IF NOT EXISTS pet_policy               varchar(32),
    ADD COLUMN IF NOT EXISTS smoking_policy           varchar(32),
    ADD COLUMN IF NOT EXISTS sublease_policy          varchar(32),
    ADD COLUMN IF NOT EXISTS visitor_policy           varchar(32),
    ADD COLUMN IF NOT EXISTS temp_residence_register_by varchar(32),
    ADD COLUMN IF NOT EXISTS tax_responsibility       varchar(32),
    ADD COLUMN IF NOT EXISTS meter_readings_start     jsonb;

-- Backfill tenant_type for existing rows (all pre-V2 contracts are Vietnamese).
UPDATE econtracts SET tenant_type = 'VIETNAMESE' WHERE tenant_type IS NULL;
UPDATE econtracts SET contract_language = 'VI' WHERE contract_language IS NULL;

-- Partial indexes to query foreign tenants and non-VI contracts fast.
CREATE INDEX IF NOT EXISTS idx_econtracts_tenant_type_foreign
    ON econtracts (tenant_type) WHERE tenant_type = 'FOREIGNER';
CREATE INDEX IF NOT EXISTS idx_econtracts_contract_language
    ON econtracts (contract_language) WHERE contract_language <> 'VI';

-- =========================================================================
-- landlord_profiles: DOB + permanent address + bank + tax code for invoicing
-- =========================================================================
ALTER TABLE landlord_profiles
    ADD COLUMN IF NOT EXISTS date_of_birth      date,
    ADD COLUMN IF NOT EXISTS permanent_address  varchar(512),
    ADD COLUMN IF NOT EXISTS bank_name          varchar(255),
    ADD COLUMN IF NOT EXISTS tax_code           varchar(32);

-- =========================================================================
-- contract_co_tenants: người ở cùng (Luật Cư trú 2020)
-- =========================================================================
CREATE TABLE IF NOT EXISTS contract_co_tenants (
    id                  uuid PRIMARY KEY,
    contract_id         uuid NOT NULL REFERENCES econtracts (id) ON DELETE CASCADE,
    full_name           varchar(255) NOT NULL,
    identity_number     varchar(64)  NOT NULL,
    identity_type       varchar(32)  NOT NULL,  -- CCCD | PASSPORT
    date_of_birth       date,
    gender              varchar(16),
    nationality         varchar(64),
    relationship        varchar(64)  NOT NULL,
    phone_number        varchar(32),
    created_at          timestamp(6) with time zone NOT NULL,
    updated_at          timestamp(6) with time zone
);

CREATE INDEX IF NOT EXISTS idx_contract_co_tenants_contract ON contract_co_tenants (contract_id);

-- =========================================================================
-- contract_translations: cache AWS Translate results keyed by source hash
-- =========================================================================
CREATE TABLE IF NOT EXISTS contract_translations (
    id                 uuid PRIMARY KEY,
    source_hash        varchar(64)  NOT NULL,
    source_language    varchar(8)   NOT NULL,
    target_language    varchar(8)   NOT NULL,
    source_text        text         NOT NULL,
    translated_text    text         NOT NULL,
    hit_count          bigint       NOT NULL DEFAULT 1,
    created_at         timestamp(6) with time zone NOT NULL,
    updated_at         timestamp(6) with time zone,
    CONSTRAINT uk_contract_translations UNIQUE (source_hash, source_language, target_language)
);

CREATE INDEX IF NOT EXISTS idx_contract_translations_lookup
    ON contract_translations (source_hash, source_language, target_language);
