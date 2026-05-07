package com.isums.contractservice.domains.events;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record ContractCompletedEvent(
        UUID contractId,
        UUID tenantId,
        String tenantEmail,
        Boolean isNewAccount,
        UUID houseId,
        UUID landlordId,
        Long depositAmount,
        Long originalDepositAmount,
        Long transferredDepositAmount,
        UUID relocationSourceContractId,
        Long rentAmount,
        Integer payDate,
        Instant startAt,
        Instant endAt,
        Instant completedAt,
        Instant depositDueAt,
        String signedPdfUrl
) {
}