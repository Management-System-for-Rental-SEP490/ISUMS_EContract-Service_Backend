package com.isums.contractservice.domains.dtos;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ConfirmRefundRequest(
        @NotNull @Min(0) Long refundAmount,
        String note
) {
}
