package com.isums.contractservice.domains.events;

import lombok.Builder;

import java.util.UUID;

@Builder
public record ContractReadyForLandlordSignatureEvent(
        String messageId,
        UUID contractId,
        UUID recipientUserId,
        UUID tenantId,
        String tenantName,
        String contractName,
        String documentId
) {
}
