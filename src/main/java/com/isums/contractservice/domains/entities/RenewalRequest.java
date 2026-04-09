package com.isums.contractservice.domains.entities;

import com.isums.contractservice.domains.enums.RenewalRequestStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "renewal_requests",
        indexes = {
                @Index(columnList = "contract_id"),
                @Index(columnList = "house_id, status")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RenewalRequest {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @Column(name = "house_id", nullable = false)
    private UUID houseId;

    @Column(name = "tenant_user_id", nullable = false)
    private UUID tenantUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RenewalRequestStatus status;

    private String tenantNote;

    @Column(name = "has_competing_deposit")
    private Boolean hasCompetingDeposit;

    @Column(name = "new_contract_id")
    private UUID newContractId;

    @Column(name = "decline_reason")
    private String declineReason;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    private Instant resolvedAt;
}
