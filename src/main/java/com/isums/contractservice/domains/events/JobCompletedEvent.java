package com.isums.contractservice.domains.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobCompletedEvent {
    private UUID referenceId;
    private UUID slotId;
    private UUID staffId;
    private UUID contractId;
    private String referenceType;
    private String action;
}
