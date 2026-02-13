package com.isums.contractservice.domains.dtos;

public record GetEContractOutSystemRequest(
        String documentId,
        String token
) {
}
