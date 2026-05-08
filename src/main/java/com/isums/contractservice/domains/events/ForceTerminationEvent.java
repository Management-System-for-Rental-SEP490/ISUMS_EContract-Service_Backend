package com.isums.contractservice.domains.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ForceTerminationEvent {
    private UUID contractId;
    private UUID houseId;
    private UUID tenantId;
    private String reason;
    private UUID actorId;
    private Instant terminatedAt;
    private String messageId;
}
