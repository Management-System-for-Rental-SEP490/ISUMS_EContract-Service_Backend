package com.isums.contractservice.domains.dtos;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record CashDepositReceiptResponse(
        UUID id,
        UUID contractId,
        String receiptNumber,
        Long amount,
        Instant paidAt,
        UUID confirmedByUserId,
        String payerName,
        String payeeName,
        String note,
        Instant createdAt
) {
}
