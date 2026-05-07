package com.isums.contractservice.domains.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverdueTerminationRequestedEvent {
    private UUID contractId;
    private UUID houseId;
    private UUID managerId;
    private String tenantName;
    private String messageId;
}
