package com.isums.contractservice.domains.dtos;

import com.isums.contractservice.domains.entities.RenewalRequest;
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
public class RenewalRequestDto {
    private UUID id;
    private UUID contractId;
    private UUID houseId;
    private String status;
    private String tenantNote;
    private Boolean hasCompetingDeposit;
    private UUID newContractId;
    private String declineReason;
    private Instant createdAt;

    public static RenewalRequestDto from(RenewalRequest r) {
        return RenewalRequestDto.builder()
                .id(r.getId())
                .contractId(r.getContractId())
                .houseId(r.getHouseId())
                .status(r.getStatus().name())
                .tenantNote(r.getTenantNote())
                .hasCompetingDeposit(r.getHasCompetingDeposit())
                .newContractId(r.getNewContractId())
                .declineReason(r.getDeclineReason())
                .createdAt(r.getCreatedAt())
                .build();
    }
}
