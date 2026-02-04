package com.isums.contractservice.abstracts;


import com.isums.contractservice.domains.dtos.ApiResponse;
import com.isums.contractservice.domains.dtos.CreateEContractRequest;
import com.isums.contractservice.domains.dtos.VnptDocumentDto;
import com.isums.contractservice.domains.dtos.VnptResult;

import java.util.UUID;

public interface EContractService {
    public VnptDocumentDto CreateDraftVnptEContract(UUID actorId, CreateEContractRequest req);
}
