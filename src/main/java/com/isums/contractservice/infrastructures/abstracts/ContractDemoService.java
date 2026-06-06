package com.isums.contractservice.infrastructures.abstracts;

import com.isums.contractservice.domains.dtos.ContractDemoPreview;
import com.isums.contractservice.domains.dtos.ContractDemoRequest;

import java.time.Instant;
import java.util.UUID;

public interface ContractDemoService {
    ContractDemoPreview preview(UUID contractId,
                                com.isums.contractservice.domains.enums.ContractDemoScenario scenario,
                                Instant customEffectiveAt);

    ContractDemoPreview run(ContractDemoRequest request, UUID actorId);

    ContractDemoPreview getActive(UUID contractId);

    void cancel(UUID contractId, UUID actorId);
}
