package com.isums.contractservice.domains.events;

import lombok.Builder;

import java.util.UUID;

@Builder
public class PowerCutReviewRequestedEvent {
    private UUID contractId;
    private UUID houseId;
    private UUID managerId;
    private String tenantName;
    private int daysLate;
    private Long totalAmount;
    private String messageId;
}
