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
public class ContractReplacedEvent {
    private String messageId;
    private UUID oldContractId;
    private UUID newContractId;
    private UUID oldHouseId;
    private UUID newHouseId;
    private UUID tenantId;
    private boolean keepHouseUnavailable;
    private String depositHandling;
    private Long transferredDepositAmount;
    private String reason;
    private Instant replacedAt;
}
