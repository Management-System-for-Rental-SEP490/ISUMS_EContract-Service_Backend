package com.isums.contractservice.domains.entities;

import com.isums.contractservice.domains.enums.ContractDemoScenario;
import com.isums.contractservice.domains.enums.ContractDemoStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "contract_demo_sessions", indexes = {
        @Index(name = "idx_contract_demo_contract_status", columnList = "contract_id,status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractDemoSession {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ContractDemoScenario scenario;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ContractDemoStatus status;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    @Column(name = "started_by", nullable = false)
    private UUID startedBy;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
