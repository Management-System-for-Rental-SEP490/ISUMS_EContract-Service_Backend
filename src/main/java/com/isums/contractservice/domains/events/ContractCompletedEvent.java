package com.isums.contractservice.domains.events;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record ContractCompletedEvent(
        UUID contractId,
        UUID tenantId,
        String tenantEmail,
        boolean isNewAccount,
        UUID houseId,
        UUID landlordId,
        Long depositAmount,
        Long rentAmount,
        Integer payDate,
        Instant startAt,
        Instant endAt,
        Instant completedAt
) {}