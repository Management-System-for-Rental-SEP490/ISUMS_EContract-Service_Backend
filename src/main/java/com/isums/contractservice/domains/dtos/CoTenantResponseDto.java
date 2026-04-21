package com.isums.contractservice.domains.dtos;

import com.isums.contractservice.domains.enums.CoTenantIdentityType;
import lombok.Builder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Builder
public record CoTenantResponseDto(
        UUID id,
        UUID contractId,
        String fullName,
        String identityNumber,
        CoTenantIdentityType identityType,
        LocalDate dateOfBirth,
        String gender,
        String nationality,
        String relationship,
        String phoneNumber,
        Instant createdAt,
        Instant updatedAt
) {
}
