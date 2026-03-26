package com.isums.contractservice.domains.dtos;

import jakarta.validation.constraints.NotBlank;

public record UpsertLandlordProfileRequest(
        @NotBlank String fullName,
        @NotBlank String identityNumber,
        String identityIssueDate,
        String identityIssuePlace,
        String address,
        String phoneNumber,
        @NotBlank String email,
        String bankAccount
) {
}