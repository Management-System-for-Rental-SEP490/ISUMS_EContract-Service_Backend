package com.isums.contractservice.domains.enums;

import java.util.Map;
import java.util.Set;

/**
 * Authoritative transition map for {@link RelocationRequestStatus}. Service
 * code MUST call {@link #validate(RelocationRequestStatus, RelocationRequestStatus)}
 * before persisting any status change so that no caller can sneak through an
 * invalid jump (e.g. CONTRACT_CREATED -> CANCELLED).
 *
 * <p>Terminal states (no outbound transitions): REJECTED, CANCELLED, COMPLETED.</p>
 */
public final class RelocationStateMachine {

    private RelocationStateMachine() {}

    private static final Map<RelocationRequestStatus, Set<RelocationRequestStatus>> ALLOWED = Map.ofEntries(
            Map.entry(RelocationRequestStatus.REQUESTED, Set.of(
                    RelocationRequestStatus.QUOTED,
                    RelocationRequestStatus.APPROVED,
                    RelocationRequestStatus.REJECTED,
                    RelocationRequestStatus.CANCELLED,
                    RelocationRequestStatus.COMPLETED)),
            Map.entry(RelocationRequestStatus.QUOTED, Set.of(
                    RelocationRequestStatus.APPROVED,
                    RelocationRequestStatus.REJECTED,
                    RelocationRequestStatus.CANCELLED)),
            Map.entry(RelocationRequestStatus.APPROVED, Set.of(
                    RelocationRequestStatus.CONTRACT_CREATED,
                    RelocationRequestStatus.CANCELLED)),
            Map.entry(RelocationRequestStatus.CONTRACT_CREATED, Set.of(
                    RelocationRequestStatus.ADDITIONAL_PAYMENT_PENDING,
                    RelocationRequestStatus.REFUND_PENDING,
                    RelocationRequestStatus.COMPLETED)),
            Map.entry(RelocationRequestStatus.ADDITIONAL_PAYMENT_PENDING, Set.of(
                    RelocationRequestStatus.COMPLETED)),
            Map.entry(RelocationRequestStatus.REFUND_PENDING, Set.of(
                    RelocationRequestStatus.COMPLETED)),
            Map.entry(RelocationRequestStatus.REJECTED, Set.of()),
            Map.entry(RelocationRequestStatus.CANCELLED, Set.of()),
            Map.entry(RelocationRequestStatus.COMPLETED, Set.of())
    );

    public static void validate(RelocationRequestStatus from, RelocationRequestStatus to) {
        if (from == to) return;
        if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
            throw new IllegalStateException(
                    "Invalid relocation request transition: " + from + " -> " + to);
        }
    }
}
