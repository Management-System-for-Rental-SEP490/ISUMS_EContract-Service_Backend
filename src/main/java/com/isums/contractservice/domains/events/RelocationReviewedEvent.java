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
public class RelocationReviewedEvent {
    private UUID relocationRequestId;
    private UUID oldContractId;
    private UUID oldHouseId;
    private boolean approved;
    private UUID reviewedBy;
    private Instant reviewedAt;
}
