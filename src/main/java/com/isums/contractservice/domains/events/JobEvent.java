package com.isums.contractservice.domains.events;

import com.isums.contractservice.domains.enums.JobAction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobEvent {
    private UUID referenceId;
    private UUID houseId;
    private UUID slotId;
    private UUID staffId;
    private String referenceType;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private JobAction action;
}
