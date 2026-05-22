CREATE TABLE IF NOT EXISTS maintenance_settings (
    id              SERIAL PRIMARY KEY,
    enabled         BOOLEAN     NOT NULL DEFAULT FALSE,
    scope           VARCHAR(32) NOT NULL DEFAULT 'TENANT_PORTAL',
    severity        VARCHAR(16) NOT NULL DEFAULT 'INFO',
    title_vi        VARCHAR(200) NOT NULL,
    title_en        VARCHAR(200),
    title_ja        VARCHAR(200),
    message_vi      TEXT        NOT NULL,
    message_en      TEXT,
    message_ja      TEXT,
    scheduled_start TIMESTAMPTZ,
    scheduled_end   TIMESTAMPTZ,
    allow_read_only BOOLEAN     NOT NULL DEFAULT FALSE,
    contact_email   VARCHAR(255),
    contact_phone   VARCHAR(32),
    status_page_url VARCHAR(255),
    version         INTEGER     NOT NULL DEFAULT 1,
    updated_by      UUID        NOT NULL,
    updated_by_email VARCHAR(255),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO maintenance_settings (id, enabled, scope, severity, title_vi, message_vi, updated_by)
VALUES (1, FALSE, 'TENANT_PORTAL', 'INFO',
        'Hệ thống bảo trì',
        'Hệ thống đang được bảo trì. Vui lòng quay lại sau.',
        '00000000-0000-0000-0000-000000000000')
ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS maintenance_audit_log (
    id               BIGSERIAL PRIMARY KEY,
    action           VARCHAR(16) NOT NULL,
    enabled_before   BOOLEAN,
    enabled_after    BOOLEAN,
    scope            VARCHAR(32),
    title_vi         VARCHAR(200),
    actor_id         UUID        NOT NULL,
    actor_email      VARCHAR(255),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_maintenance_audit_created_at
    ON maintenance_audit_log (created_at DESC);
