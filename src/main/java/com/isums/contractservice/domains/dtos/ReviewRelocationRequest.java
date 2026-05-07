package com.isums.contractservice.domains.dtos;

import com.isums.contractservice.domains.enums.DepositHandling;
import com.isums.contractservice.domains.enums.RelocationResolutionType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public record ReviewRelocationRequest(
        @NotNull Boolean approved,
        RelocationResolutionType resolutionType,
        UUID approvedHouseId,
        DepositHandling depositHandling,
        Long newRentAmount,
        Long newDepositAmount,
        Instant newStartAt,
        Instant newEndAt,
        Instant newHandoverDate,
        Long transferredDepositAmount,
        Long forfeitAmount,
        Long additionalDepositAmount,
        @Min(0) Long oldRentProratedAmount,
        @Min(0) Long oldUtilitiesAmount,
        @Min(0) Long oldDamageAmount,
        @Min(0) Long adminFeeAmount,
        @Min(0) Long settlementAmount,
        @Min(0) Long refundableDepositAmount,
        @Min(0) Long totalAdditionalPaymentAmount,
        @Size(max = 1200) String inspectionNote,
        @Min(0) Long refundAmount,
        Instant refundDueAt,
        @Size(max = 1600) String legalBasis,
        @Size(max = 1000) String managerNote
) {}
