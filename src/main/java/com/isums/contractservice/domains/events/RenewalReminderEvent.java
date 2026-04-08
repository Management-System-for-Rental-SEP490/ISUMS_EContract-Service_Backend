package com.isums.contractservice.domains.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RenewalReminderEvent {
    private UUID contractId;
    private UUID tenantId;
    private int daysRemaining;
    private Instant endDate;
    private String messageId;
}
