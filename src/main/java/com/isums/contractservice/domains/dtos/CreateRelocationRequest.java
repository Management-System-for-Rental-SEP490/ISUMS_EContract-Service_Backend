package com.isums.contractservice.domains.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public record CreateRelocationRequest(
        @NotNull UUID requestedHouseId,
        @NotBlank @Size(max = 1000) String reason,
        Boolean activeLease,
        Instant desiredMoveDate,
        @Min(1) Integer occupantCount
) {}
