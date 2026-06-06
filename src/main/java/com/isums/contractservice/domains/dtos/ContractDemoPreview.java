package com.isums.contractservice.domains.dtos;

import com.isums.contractservice.domains.enums.ContractDemoScenario;
import com.isums.contractservice.domains.enums.ContractDemoStatus;
import com.isums.contractservice.domains.enums.DepositStatus;
import com.isums.contractservice.domains.enums.EContractStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ContractDemoPreview(
        UUID sessionId,
        ContractDemoStatus sessionStatus,
        ContractDemoScenario scenario,
        Instant realNow,
        Instant effectiveAt,
        long daysRemaining,
        ContractSummary currentContract,
        ContractSummary nextContract,
        NextTenantReadiness nextTenantReadiness,
        List<String> actions,
        List<String> warnings
) {
    public record ContractSummary(
            UUID id,
            String documentNo,
            UUID houseId,
            UUID tenantId,
            String tenantName,
            String tenantEmail,
            Instant startAt,
            Instant endAt,
            EContractStatus status,
            DepositStatus depositStatus
    ) {
    }

    public record NextTenantReadiness(
            boolean contractSigned,
            boolean depositPaid,
            boolean firstRentPaid,
            boolean startDateReached,
            boolean readyForHandover
    ) {
    }
}
