package com.isums.contractservice.domains.dtos;

import com.isums.contractservice.domains.enums.EContractStatus;

import java.time.Instant;
import java.util.UUID;

public record EContractDto(
        UUID id,
        UUID userId,
        String html,
        String name,
        String snapshotKey,
        EContractStatus status,
        UUID createdBy,
        Instant createdAt
) {
}
