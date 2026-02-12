package com.isums.contractservice.domains.dtos;

public record ProcessCodeLoginRequest(
        String processCode,
        String token
) {
}
