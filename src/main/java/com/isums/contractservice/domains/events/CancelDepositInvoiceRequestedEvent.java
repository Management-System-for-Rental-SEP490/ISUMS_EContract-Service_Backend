package com.isums.contractservice.domains.events;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record CancelDepositInvoiceRequestedEvent(
        String messageId,
        UUID contractId,
        UUID tenantId,
        UUID houseId,
        String reason,
        Instant requestedAt
) {}
