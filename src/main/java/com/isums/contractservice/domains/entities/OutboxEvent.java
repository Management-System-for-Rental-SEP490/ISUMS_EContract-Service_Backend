package com.isums.contractservice.domains.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional outbox row. Written inside the business transaction, picked up
 * by {@code OutboxPoller} and published to Kafka. See V4 migration for schema
 * rationale (dual-write problem mitigation).
 */
@Entity
@Table(name = "outbox_events")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, length = 128)
    private String topic;

    @Column(name = "partition_key", length = 128)
    private String partitionKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "message_id", nullable = false, length = 64)
    private String messageId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, String> headers;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(nullable = false)
    private Integer attempts;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;
}
