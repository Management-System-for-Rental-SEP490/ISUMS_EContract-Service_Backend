package com.isums.contractservice.infrastructures.abstracts;

import com.isums.contractservice.domains.dtos.CompleteInspectionRequest;
import com.isums.contractservice.domains.dtos.ContractInspectionDto;
import com.isums.contractservice.domains.entities.EContract;

import java.util.UUID;

public interface ContractTerminationService {

    void completeInspection(UUID inspectionId, CompleteInspectionRequest req);

    void handleExpiredContract(EContract contract);

    ContractInspectionDto getInspectionById(UUID inspectionId);

    ContractInspectionDto getInspectionByContractId(UUID contractId);
}
