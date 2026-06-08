package com.isums.contractservice.domains.dtos;

import java.util.UUID;

public record ContractBriefDto(
        UUID id,
        String contractNumber,
        String name,
        String tenantName,
        String tenantEmail
) {
}
