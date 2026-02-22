package com.isums.contractservice.domains.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

public record ConfirmAndSendToTenantEvent(
        String url,
        UUID recipientUserId
) {}
