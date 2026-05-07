package com.isums.contractservice.domains.events;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record ConfirmAndSendToTenantEvent(
        String messageId,
        UUID recipientUserId,
        UUID contractId,
        String contractName,
        String url,
        String confirmUrl,
        Instant startDate,
        Instant endDate,
        // Contract language enum name (VI, VI_EN, VI_JA). Notification-Service
        // maps this to a LocaleType to pick the right email template.
        // Null / unknown → notification falls back to VI.
        String contractLanguage
) {
}