package com.isums.contractservice.domains.entities;

import com.isums.contractservice.domains.enums.EContractStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "econtracts", indexes = {
        @Index(name = "idx_econtracts_house_status",  columnList = "house_id,status"),
        @Index(name = "idx_econtracts_tenant",        columnList = "tenant_id"),
        @Index(name = "idx_econtracts_user",          columnList = "user_id"),
        @Index(name = "idx_econtracts_end_at",        columnList = "end_at,status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EContract implements Serializable {

    @Id @GeneratedValue @UuidGenerator
    private UUID id;

    @Column(name = "document_id", unique = true)
    private String documentId;

    @Column(name = "document_no", unique = true)
    private String documentNo;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false, columnDefinition = "text")
    private String html;

    private String name;

    @Column(name = "snapshot_key")
    private String snapshotKey;

    @Column(name = "house_id", nullable = false)
    private UUID houseId;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at")
    private Instant endAt;

    private Long price;

    @Column(name = "deposit_amount")
    private Long depositAmount;

    @Column(name = "pay_date")
    private Integer payDate;

    @Column(name = "late_days")
    private Integer lateDays;

    @Column(name = "late_penalty_percent")
    private Integer latePenaltyPercent;

    @Column(name = "deposit_refund_days")
    private Integer depositRefundDays;

    @Column(name = "handover_date")
    private Instant handoverDate;

    @Column(name = "tenant_identity_number")
    private String tenantIdentityNumber;

    @Column(name = "cccd_front_key")
    private String cccdFrontKey;

    @Column(name = "cccd_back_key")
    private String cccdBackKey;

    @Column(name = "terminated_at")
    private Instant terminatedAt;

    @Column(name = "terminated_reason")
    private String terminatedReason;

    @Column(name = "terminated_by")
    private UUID terminatedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EContractStatus status;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}