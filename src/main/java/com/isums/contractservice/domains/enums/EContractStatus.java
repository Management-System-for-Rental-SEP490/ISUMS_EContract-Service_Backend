package com.isums.contractservice.domains.enums;

import java.util.Set;
import java.util.Map;

public enum EContractStatus {
    DRAFT,
    READY,
    CONFIRM_BY_LANDLORD,
    IN_PROGRESS,
    CONFIRM_BY_TENANT,
    COMPLETED,
    CORRECTING,
    CANCELLED,
    REJECTED_BY_TENANT,
    REJECTED_BY_LANDLORD;

    private static final Map<EContractStatus, Set<EContractStatus>> ALLOWED = Map.of(
            DRAFT, Set.of(READY, CANCELLED),
            READY, Set.of(CONFIRM_BY_LANDLORD, CORRECTING, CANCELLED),
            CONFIRM_BY_LANDLORD, Set.of(IN_PROGRESS, CORRECTING, CANCELLED),
            IN_PROGRESS, Set.of(CONFIRM_BY_TENANT, REJECTED_BY_TENANT),
            CONFIRM_BY_TENANT, Set.of(COMPLETED, REJECTED_BY_LANDLORD),
            CORRECTING, Set.of(READY, CANCELLED),
            COMPLETED, Set.of(CANCELLED),
            REJECTED_BY_TENANT, Set.of(),
            REJECTED_BY_LANDLORD, Set.of(),
            CANCELLED, Set.of()
    );

    public void validateTransition(EContractStatus next) {
        if (!ALLOWED.getOrDefault(this, Set.of()).contains(next)) {
            throw new IllegalStateException(
                    "Cannot transition contract from " + this + " to " + next);
        }
    }
}