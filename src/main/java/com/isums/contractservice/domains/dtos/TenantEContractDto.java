package com.isums.contractservice.domains.dtos;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.isums.contractservice.domains.enums.EContractStatus;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TenantEContractDto(
        UUID id,
        String name,
        UUID houseId,
        Instant startAt,
        Instant endAt,
        EContractStatus status,
        String pdfUrl,
        Instant createdAt
) {}