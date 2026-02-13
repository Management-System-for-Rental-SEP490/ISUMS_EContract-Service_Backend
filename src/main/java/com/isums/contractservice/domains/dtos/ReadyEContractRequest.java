package com.isums.contractservice.domains.dtos;

import java.util.UUID;

public record ReadyEContractRequest(
        String eContractId,
        String token
) {
}
