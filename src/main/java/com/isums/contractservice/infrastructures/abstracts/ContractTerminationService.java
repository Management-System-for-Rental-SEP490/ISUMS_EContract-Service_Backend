package com.isums.contractservice.infrastructures.abstracts;

import com.isums.contractservice.domains.dtos.CompleteInspectionRequest;
import com.isums.contractservice.domains.entities.EContract;

import java.util.UUID;

public interface ContractTerminationService {

    void handleExpiredContract(EContract contract);

    void confirmTerminationOverdue(UUID contractId, UUID actorId);
}
