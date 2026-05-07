-- V1 baseline snapshot of the Hibernate-generated schema (as of 2026-04-21).
-- On existing dev/prod databases, Flyway skips this via baseline-on-migrate=true
-- and treats version 1 as already applied. On fresh databases, this file creates
-- the complete schema so later migrations can ALTER safely.
--
-- All statements use IF NOT EXISTS so the file is safe to re-apply.

-- =========================================================================
-- econtracts
-- =========================================================================
CREATE TABLE IF NOT EXISTS econtracts (
    id                      uuid PRIMARY KEY,
    document_id             varchar(255) UNIQUE,
    document_no             varchar(255) UNIQUE,
    user_id                 uuid NOT NULL,
    tenant_id               uuid,
    html                    text NOT NULL,
    name                    varchar(255),
    snapshot_key            varchar(255),
    house_id                uuid NOT NULL,
    start_at                timestamp(6) with time zone NOT NULL,
    end_at                  timestamp(6) with time zone,
    price                   bigint,
    deposit_amount          bigint,
    pay_date                integer,
    late_days               integer,
    late_penalty_percent    integer,
    deposit_refund_days     integer,
    handover_date           timestamp(6) with time zone,
    tenant_identity_number  varchar(255),
    cccd_front_key          varchar(255),
    cccd_back_key           varchar(255),
    terminated_at           timestamp(6) with time zone,
    terminated_reason       varchar(255),
    terminated_by           uuid,
    renew_notice_days       integer,
    has_power_cut_clause    boolean,
    status                  varchar(255) NOT NULL,
    tenant_name             varchar(255),
    cccd_verified_at        timestamp(6) with time zone,
    created_by              uuid NOT NULL,
    created_at              timestamp(6) with time zone NOT NULL,
    updated_at              timestamp(6) with time zone
);

CREATE INDEX IF NOT EXISTS idx_econtracts_house_status ON econtracts (house_id, status);
CREATE INDEX IF NOT EXISTS idx_econtracts_tenant       ON econtracts (tenant_id);
CREATE INDEX IF NOT EXISTS idx_econtracts_user         ON econtracts (user_id);
CREATE INDEX IF NOT EXISTS idx_econtracts_end_at       ON econtracts (end_at, status);

-- =========================================================================
-- landlord_profiles
-- =========================================================================
CREATE TABLE IF NOT EXISTS landlord_profiles (
    id                   uuid PRIMARY KEY,
    user_id              uuid NOT NULL,
    full_name            varchar(255) NOT NULL,
    identity_number      varchar(255) NOT NULL,
    identity_issue_date  varchar(255),
    identity_issue_place varchar(255),
    address              varchar(255),
    phone_number         varchar(255),
    email                varchar(255),
    bank_account         varchar(255),
    created_at           timestamp(6) with time zone,
    updated_at           timestamp(6) with time zone
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_landlord_profile_user ON landlord_profiles (user_id);

-- =========================================================================
-- econtract_templates
-- =========================================================================
CREATE TABLE IF NOT EXISTS econtract_templates (
    id           uuid PRIMARY KEY,
    code         varchar(255) NOT NULL,
    name         varchar(255) NOT NULL,
    content_html text,
    created_at   timestamp(6) with time zone NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_template_code ON econtract_templates (code);

-- =========================================================================
-- renewal_requests
-- =========================================================================
CREATE TABLE IF NOT EXISTS renewal_requests (
    id                      uuid PRIMARY KEY,
    contract_id             uuid NOT NULL,
    house_id                uuid NOT NULL,
    tenant_user_id          uuid NOT NULL,
    status                  varchar(255) NOT NULL,
    tenant_note             varchar(255),
    has_competing_deposit   boolean,
    new_contract_id         uuid,
    decline_reason          varchar(255),
    created_at              timestamp(6) with time zone,
    updated_at              timestamp(6) with time zone,
    resolved_at             timestamp(6) with time zone
);

CREATE INDEX IF NOT EXISTS idx_renewal_requests_contract ON renewal_requests (contract_id);
CREATE INDEX IF NOT EXISTS idx_renewal_requests_house    ON renewal_requests (house_id, status);

-- =========================================================================
-- renewal_notification_logs
-- =========================================================================
CREATE TABLE IF NOT EXISTS renewal_notification_logs (
    id              uuid PRIMARY KEY,
    milestone_key   varchar(255) NOT NULL,
    contract_id     uuid NOT NULL,
    days_remaining  integer NOT NULL,
    sent_at         timestamp(6) with time zone
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_renewal_notification_logs_milestone_key
    ON renewal_notification_logs (milestone_key);
