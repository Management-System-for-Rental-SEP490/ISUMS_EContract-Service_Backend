package com.isums.contractservice.domains.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PowerCutConfirmedEvent {
    private UUID contractId;
    private UUID houseId;
    private UUID tenantId;
    private UUID confirmedBy;
    private Instant executeAt;
    private String messageId;
}
