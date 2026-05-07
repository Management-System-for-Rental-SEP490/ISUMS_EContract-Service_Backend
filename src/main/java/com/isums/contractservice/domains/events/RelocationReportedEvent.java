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
public class RelocationReportedEvent {
    private UUID relocationRequestId;
    private UUID oldContractId;
    private UUID oldHouseId;
    private UUID tenantId;
    private UUID staffReportedBy;
    private String reportReason;
    private Instant reportedAt;
}
