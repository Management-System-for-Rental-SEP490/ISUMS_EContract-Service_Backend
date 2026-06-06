package com.isums.contractservice.domains.dtos;

import java.util.UUID;

public record ContractPaymentStatus(
        boolean depositPaid,
        boolean firstRentPaid,
        UUID pendingInvoiceId
) {
    public static ContractPaymentStatus unavailable() {
        return new ContractPaymentStatus(false, false, null);
    }
}
