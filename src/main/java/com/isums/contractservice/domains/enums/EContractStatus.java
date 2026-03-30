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
    DELETED;

    private static final Map<EContractStatus, Set<EContractStatus>> TRANSITIONS = Map.ofEntries(
            Map.entry(DRAFT, Set.of(PENDING_TENANT_REVIEW, DELETED)),
            Map.entry(PENDING_TENANT_REVIEW, Set.of(CORRECTING, READY, CANCELLED_BY_TENANT, PENDING_TENANT_REVIEW)),
            Map.entry(CORRECTING, Set.of(PENDING_TENANT_REVIEW, CANCELLED_BY_LANDLORD)),
            Map.entry(READY, Set.of(IN_PROGRESS, CANCELLED_BY_LANDLORD)),
            Map.entry(IN_PROGRESS, Set.of(COMPLETED, CANCELLED_BY_TENANT)),
            Map.entry(COMPLETED, Set.of()),
            Map.entry(CANCELLED_BY_TENANT, Set.of()),
            Map.entry(CANCELLED_BY_LANDLORD, Set.of()),
            Map.entry(DELETED, Set.of())
    );

    public void validateTransition(EContractStatus next) {
        if (!TRANSITIONS.getOrDefault(this, Set.of()).contains(next)) {
            throw new IllegalStateException("Không thể chuyển trạng thái hợp đồng: " + this + " → " + next);
        }
    }
}