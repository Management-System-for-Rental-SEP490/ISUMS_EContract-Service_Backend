-- V4: Transactional outbox for critical contract lifecycle Kafka events.
--
-- Problem solved: dual-write race between DB commit and Kafka send. Previously
-- confirmByAdmin saved status=PENDING_TENANT_REVIEW then called kafka.send —
-- if Kafka was unavailable between the two, the tenant email was silently lost
-- and the contract sat forever waiting for an email that never left.
--
-- Pattern: enqueue events by INSERT inside the service transaction (atomic
-- with the business state change). A background poller reads rows with
-- sent_at IS NULL, publishes to Kafka, marks sent_at on success. At-least-once
-- delivery (consumers already idempotent via IdempotencyService).
--
-- Only events where loss is unacceptable go here. Informational events
-- (audit, metrics) keep using direct kafka.send to avoid table growth.

CREATE TABLE IF NOT EXISTS outbox_events (
    id              uuid PRIMARY KEY,
    topic           varchar(128) NOT NULL,
    partition_key   varchar(128),                         -- Kafka key for ordering; null = no key
    payload         jsonb        NOT NULL,
    message_id      varchar(64)  NOT NULL,                -- same as event's messageId for idempotency
    headers         jsonb,                                -- optional Kafka headers (trace context, etc.)
    created_at      timestamp(6) with time zone NOT NULL DEFAULT now(),
    sent_at         timestamp(6) with time zone,          -- populated after successful publish
    attempts        integer      NOT NULL DEFAULT 0,
    last_error      text,                                 -- truncated error msg from last attempt
    last_attempt_at timestamp(6) with time zone
);

-- Poller query: "WHERE sent_at IS NULL ORDER BY created_at LIMIT N FOR UPDATE SKIP LOCKED"
-- Partial index keeps it lean even when millions of events have been processed.
CREATE INDEX IF NOT EXISTS idx_outbox_unsent
    ON outbox_events (created_at)
    WHERE sent_at IS NULL;

-- Quickly find by messageId for idempotency / duplicate detection.
CREATE INDEX IF NOT EXISTS idx_outbox_message_id
    ON outbox_events (message_id);
