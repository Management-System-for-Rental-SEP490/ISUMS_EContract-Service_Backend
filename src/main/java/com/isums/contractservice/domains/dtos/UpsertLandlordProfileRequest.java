package com.isums.contractservice.domains.dtos;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public record UpsertLandlordProfileRequest(
        @NotBlank String fullName,
        @NotBlank String identityNumber,
        String identityIssueDate,
        String identityIssuePlace,
        String address,
        String phoneNumber,
        @NotBlank String email,
        String bankAccount,
        LocalDate dateOfBirth,
        String permanentAddress,
        String bankName,
        String taxCode,
        @Min(1) @Max(30) Integer depositWaitDays,
        @Min(1) @Max(168) Integer forceMajeureNoticeHours
) {
}