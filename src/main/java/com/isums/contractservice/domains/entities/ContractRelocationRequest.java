package com.isums.contractservice.domains.entities;

import com.isums.contractservice.domains.enums.DepositHandling;
import com.isums.contractservice.domains.enums.DepositStatus;
import com.isums.contractservice.domains.enums.RelocationFaultParty;
import com.isums.contractservice.domains.enums.RelocationRequestKind;
import com.isums.contractservice.domains.enums.RelocationRequestStatus;
import com.isums.contractservice.domains.enums.RelocationResolutionType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "contract_relocation_requests", indexes = {
        @Index(name = "idx_relocation_old_contract", columnList = "old_contract_id,status"),
        @Index(name = "idx_relocation_tenant", columnList = "tenant_id,status"),
        @Index(name = "idx_relocation_new_contract", columnList = "new_contract_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContractRelocationRequest {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "old_contract_id", nullable = false)
    private UUID oldContractId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "old_house_id", nullable = false)
    private UUID oldHouseId;

    @Column(name = "requested_house_id")
    private UUID requestedHouseId;

    @Column(name = "approved_house_id")
    private UUID approvedHouseId;

    @Column(name = "new_contract_id")
    private UUID newContractId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private RelocationRequestStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_kind", nullable = false, length = 40)
    private RelocationRequestKind requestKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "fault_party", nullable = false, length = 24)
    private RelocationFaultParty faultParty;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_type", length = 40)
    private RelocationResolutionType resolutionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "deposit_status_snapshot", nullable = false, length = 40)
    private DepositStatus depositStatusSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "deposit_handling", length = 40)
    private DepositHandling depositHandling;

    @Column(name = "deposit_amount")
    private Long depositAmount;

    @Column(name = "transferred_deposit_amount")
    private Long transferredDepositAmount;

    @Column(name = "forfeit_amount")
    private Long forfeitAmount;

    @Column(name = "additional_deposit_amount")
    private Long additionalDepositAmount;

    @Column(name = "refund_amount")
    private Long refundAmount;

    @Column(name = "refund_due_at")
    private Instant refundDueAt;

    @Column(name = "new_rent_amount")
    private Long newRentAmount;

    @Column(name = "new_deposit_amount")
    private Long newDepositAmount;

    @Column(name = "new_start_at")
    private Instant newStartAt;

    @Column(name = "new_end_at")
    private Instant newEndAt;

    @Column(name = "new_handover_date")
    private Instant newHandoverDate;

    @Column(name = "desired_move_date")
    private Instant desiredMoveDate;

    @Column(name = "occupant_count")
    private Integer occupantCount;

    @Column(name = "old_rent_prorated_amount", nullable = false)
    private Long oldRentProratedAmount;

    @Column(name = "old_utilities_amount", nullable = false)
    private Long oldUtilitiesAmount;

    @Column(name = "old_damage_amount", nullable = false)
    private Long oldDamageAmount;

    @Column(name = "admin_fee_amount", nullable = false)
    private Long adminFeeAmount;

    @Column(name = "settlement_amount", nullable = false)
    private Long settlementAmount;

    @Column(name = "refundable_deposit_amount", nullable = false)
    private Long refundableDepositAmount;

    @Column(name = "total_additional_payment_amount", nullable = false)
    private Long totalAdditionalPaymentAmount;

    @Column(name = "inspection_note", columnDefinition = "text")
    private String inspectionNote;

    @Column(name = "tenant_reason", columnDefinition = "text")
    private String tenantReason;

    @Column(name = "staff_report_reason", columnDefinition = "text")
    private String staffReportReason;

    @Column(name = "staff_evidence", columnDefinition = "text")
    private String staffEvidence;

    @Column(name = "legal_basis", columnDefinition = "text")
    private String legalBasis;

    @Column(name = "manager_note", columnDefinition = "text")
    private String managerNote;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "staff_reported_by")
    private UUID staffReportedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "staff_reported_at")
    private Instant staffReportedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "contract_created_at")
    private Instant contractCreatedAt;

    @Column(name = "tenant_accepted_at")
    private Instant tenantAcceptedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
