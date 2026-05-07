package com.isums.contractservice.domains.dtos;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record CashDepositReceiptRequest(
        @NotNull @Min(1) Long amount,
        Instant paidAt,
        String note
) {
}
