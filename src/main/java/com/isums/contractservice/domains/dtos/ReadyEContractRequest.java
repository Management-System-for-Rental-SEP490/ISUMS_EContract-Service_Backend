package com.isums.contractservice.domains.dtos;

import java.util.UUID;

public record ReadyEContractRequest(
        UUID eContractId,
        String token
) {
}
