package com.isums.contractservice.infrastructures.abstracts;


import com.isums.contractservice.domains.dtos.CreateDocumentDto;
import com.isums.contractservice.domains.dtos.VnptDocumentDto;
import com.isums.contractservice.domains.dtos.VnptResult;

public interface VnptEContractClient {

    VnptResult<VnptDocumentDto> createDocument(String token, CreateDocumentDto create);
    String getToken();
}
