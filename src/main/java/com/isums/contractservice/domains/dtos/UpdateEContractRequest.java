package com.isums.contractservice.domains.dtos;

import com.isums.contractservice.domains.enums.EContractStatus;

public record UpdateEContractRequest(
        String name,
        String html,
        String snapshotKey,
        EContractStatus status
) {
}
