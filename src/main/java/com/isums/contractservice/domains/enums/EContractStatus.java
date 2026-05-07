package com.isums.contractservice.domains.enums;

import java.util.Map;
import java.util.Set;

public enum EContractStatus {
    DRAFT,
    PENDING_TENANT_REVIEW,
    CORRECTING,
    READY,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED_BY_TENANT,
    CANCELLED_BY_LANDLORD,
    DELETED,
    PENDING_TERMINATION,
    INSPECTION_DONE,
    DEPOSIT_REFUND_PENDING,
    TERMINATED,
    REPLACED_BEFORE_DEPOSIT,
    REPLACED_AFTER_DEPOSIT,
    /**
     * Active-lease relocation: replacement contract has been created but the
     * tenant is still legally occupying the old premises until physical handover.
     * The old contract remains in force (rent obligations etc.) until a manager
     * confirms handover via {@code /relocation-requests/{id}/confirm-handover}.
     * Required by Civil Code 2015 Art. 477 + Housing Law 2023 Art. 163: the
     * lessee retains right of occupancy until the property is actually returned.
     */
    PENDING_REPLACEMENT_HANDOVER;

    private static final Map<EContractStatus, Set<EContractStatus>> TRANSITIONS = Map.ofEntries(
            Map.entry(DRAFT, Set.of(PENDING_TENANT_REVIEW, DELETED)),
            Map.entry(PENDING_TENANT_REVIEW, Set.of(CORRECTING, READY, CANCELLED_BY_TENANT, PENDING_TENANT_REVIEW)),
            Map.entry(CORRECTING, Set.of(PENDING_TENANT_REVIEW, CANCELLED_BY_LANDLORD)),
            Map.entry(READY, Set.of(IN_PROGRESS, CANCELLED_BY_LANDLORD)),
            Map.entry(IN_PROGRESS, Set.of(COMPLETED, CANCELLED_BY_TENANT, PENDING_TERMINATION)),
            Map.entry(COMPLETED, Set.of(PENDING_TERMINATION, DEPOSIT_REFUND_PENDING, TERMINATED,
                    REPLACED_BEFORE_DEPOSIT, REPLACED_AFTER_DEPOSIT, PENDING_REPLACEMENT_HANDOVER,
                    CANCELLED_BY_LANDLORD)),
            Map.entry(CANCELLED_BY_TENANT, Set.of()),
            Map.entry(CANCELLED_BY_LANDLORD, Set.of()),
            Map.entry(DELETED, Set.of()),
            Map.entry(PENDING_TERMINATION, Set.of(INSPECTION_DONE)),
            Map.entry(INSPECTION_DONE, Set.of(DEPOSIT_REFUND_PENDING)),
            Map.entry(DEPOSIT_REFUND_PENDING, Set.of(TERMINATED)),
            Map.entry(TERMINATED, Set.of()),
            Map.entry(REPLACED_BEFORE_DEPOSIT, Set.of()),
            Map.entry(REPLACED_AFTER_DEPOSIT, Set.of()),
            Map.entry(PENDING_REPLACEMENT_HANDOVER, Set.of(REPLACED_AFTER_DEPOSIT))
    );

    public void validateTransition(EContractStatus next) {
        if (!TRANSITIONS.getOrDefault(this, Set.of()).contains(next)) {
            throw new IllegalStateException("Cannot transition contract status: " + this + " -> " + next);
        }
    }
}
