package com.isums.contractservice.domains.dtos;

import com.isums.contractservice.domains.enums.RelocationRequestKind;

import java.time.Instant;
import java.util.UUID;

public record ReplacementContext(
        UUID oldContractId,
        String oldContractNumber,
        Instant oldContractSignedAt,
        UUID relocationRequestId,
        RelocationRequestKind requestKind,
        Long oldDepositAmount,
        Long transferredDepositAmount,
        Long newDepositAmount,
        Long additionalDepositAmount,
        Long oldRentProratedAmount,
        Long oldUtilitiesAmount,
        Long oldDamageAmount,
        Long adminFeeAmount,
        Long totalAdditionalPaymentAmount,
        Long refundableDepositAmount,
        String inspectionNote,
        String legalBasisSnapshot,
        String oldHouseAddress,
        String newHouseAddress,
        Instant newHandoverDate
) {}
