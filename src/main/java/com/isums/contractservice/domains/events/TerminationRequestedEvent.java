package com.isums.contractservice.domains.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TerminationRequestedEvent {
    private UUID contractId;
    private UUID houseId;
    private UUID tenantId;
    private UUID invoiceId;
    private String reason;
    private String messageId;
}
