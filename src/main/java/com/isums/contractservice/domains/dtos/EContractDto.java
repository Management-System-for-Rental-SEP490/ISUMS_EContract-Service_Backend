package com.isums.contractservice.domains.dtos;

import com.isums.contractservice.domains.enums.EContractStatus;

import java.time.Instant;
import java.util.UUID;

public record EContractDto(
        UUID id,
        String documentId,
        String documentNo,
        UUID userId,
        UUID tenantId,
        String html,
        String name,
        String snapshotKey,
        UUID houseId,
        Instant startAt,
        Instant endAt,
        EContractStatus status,
        UUID createdBy,
        Instant createdAt
) {
}
