package com.isums.contractservice.domains.dtos;

public record GetEContractOutSystemRequest(
        String eContractId,
        String token
) {
}
