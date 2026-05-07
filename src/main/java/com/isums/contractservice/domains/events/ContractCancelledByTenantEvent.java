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
public class ContractCancelledByTenantEvent {
    private String messageId;
    private UUID contractId;
    private UUID houseId;
    private UUID tenantId;
    private String tenantName;
    private String reason;
    private Instant cancelledAt;
    private UUID initiatedByUserId;
}
