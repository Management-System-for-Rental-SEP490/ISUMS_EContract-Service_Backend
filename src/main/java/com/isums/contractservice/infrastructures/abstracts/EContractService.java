package com.isums.contractservice.infrastructures.abstracts;


import com.isums.contractservice.domains.dtos.*;

import java.util.List;
import java.util.UUID;

public interface EContractService {
    public EContractDto createDraftEContract(UUID actorId, CreateEContractRequest req);

    public EContractDto getEContractById(UUID id);

    public List<EContractDto> getAllEContracts();

    public EContractDto updateEContractById(UUID id, UpdateEContractRequest req);

    public VnptDocumentDto confirmEContract(UUID contractId);

    public ProcessLoginInfoDto getAccessInfoByProcessCode(String processCode);

//    public VnptDocumentDto readyEContract(ReadyEContractRequest req);

    public EContractDto getEContractOutSystem(String processCode);

    public ProcessResponse signProcess(VnptProcessDto process);
}
