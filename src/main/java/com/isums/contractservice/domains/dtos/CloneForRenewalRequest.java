package com.isums.contractservice.domains.dtos;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public record CloneForRenewalRequest(
        @NotNull Long newRentAmount,
        @NotNull Instant newStartDate,
        @NotNull Instant newEndDate,
        UUID renewalRequestId
) {}
