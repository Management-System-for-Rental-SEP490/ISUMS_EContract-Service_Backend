package com.isums.contractservice.domains.dtos;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record CreateEContractRequest(
        String name,
        String email,
        @Nullable String phoneNumber,
        @Nullable String identityNumber,
        UUID houseId,
        Instant dateOfIssue,
        String placeOfIssue,
        String tenantAddress,
        Instant startDate,
        Instant endDate,
        Long rentAmount,
        Integer payDate,
        Long depositAmount,
        Instant depositDate,
        Instant handoverDate
) {
}
