package com.isums.contractservice.domains.events;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record ConfirmAndSendToTenantEvent(
        UUID recipientUserId,
        UUID contractId,
        String contractName,
        String url,
        String confirmUrl,
        Instant startDate,
        Instant endDate
) {
}