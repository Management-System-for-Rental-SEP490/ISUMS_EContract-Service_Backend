package com.isums.contractservice.domains.dtos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RenewalStatusDto {
    private boolean canRequestRenewal;
    private boolean hasActiveRequest;
    private String activeRequestStatus;
    private boolean hasCompetingDeposit;
    private long daysUntilExpiry;
    private boolean windowOpenForNewTenants;
}