package com.isums.contractservice.infrastructures.abstracts;

import com.isums.contractservice.domains.dtos.CashDepositReceiptRequest;
import com.isums.contractservice.domains.dtos.CashDepositReceiptResponse;

import java.util.UUID;

public interface CashDepositReceiptService {

    CashDepositReceiptResponse confirmCashDeposit(
            UUID contractId,
            UUID actorId,
            String idempotencyKey,
            CashDepositReceiptRequest request
    );

    byte[] renderReceiptPdf(UUID contractId, String receiptNumber);

    CashDepositReceiptResponse getActive(UUID contractId);
}
