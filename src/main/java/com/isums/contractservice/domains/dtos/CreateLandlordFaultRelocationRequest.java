package com.isums.contractservice.domains.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateLandlordFaultRelocationRequest(
        UUID recommendedHouseId,
        @NotBlank @Size(max = 1000) String reportReason,
        @Size(max = 2000) String evidence
) {}
