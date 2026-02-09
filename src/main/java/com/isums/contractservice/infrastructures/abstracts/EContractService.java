package com.isums.contractservice.infrastructures.abstracts;


import com.isums.contractservice.domains.dtos.*;

import java.util.List;
import java.util.UUID;

public interface EContractService {
    public EContractDto CreateDraftEContract(UUID actorId, CreateEContractRequest req);

    public EContractDto getEContractById(UUID id);

    public List<EContractDto> getAllEContracts();

    public EContractDto updateEContractById(UUID id, UpdateEContractRequest req);

    public void confirmAndSendToTenant(UUID contractId);
}
