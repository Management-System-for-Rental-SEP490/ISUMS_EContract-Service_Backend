package com.isums.contractservice.infrastructures.abstracts;

import com.isums.contractservice.domains.dtos.RenewalRequestBody;
import com.isums.contractservice.domains.dtos.RenewalRequestDto;
import com.isums.contractservice.domains.dtos.RenewalStatusDto;

import java.util.UUID;

public interface RenewalService {

    RenewalRequestDto requestRenewal(UUID contractId, UUID tenantUserId, RenewalRequestBody body);

    void declineRenewal(UUID renewalRequestId, UUID actorId, String reason);

    void markNewContractDrafted(UUID renewalRequestId, UUID newContractId);

    void markCompleted(UUID renewalRequestId);

    RenewalStatusDto getRenewalStatus(UUID contractId, UUID tenantUserId);
}
