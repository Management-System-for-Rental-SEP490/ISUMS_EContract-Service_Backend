package com.isums.contractservice.domains.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "renewal_notification_logs",
        indexes = @Index(columnList = "milestone_key", unique = true))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RenewalNotificationLog {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "milestone_key", nullable = false, unique = true)
    private String milestoneKey;

    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @Column(name = "days_remaining", nullable = false)
    private int daysRemaining;

    @CreationTimestamp
    private Instant sentAt;
}
